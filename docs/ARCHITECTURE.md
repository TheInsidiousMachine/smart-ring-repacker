# Architecture

## App layers and data flow

```
 +----------------------------- Android app (package com.smartring) -----------------------------+
 |                                                                                              |
 |   ui/ (Compose)          api/RingApiServer (Ktor CIO)        gestures/ (Gesture Studio)       |
 |   RingAppViewModel  <--+        HTTP + WS /v1/*       <--+     MotionSegmenter, DtwMatcher,    |
 |   screens/, components |        events fan-out            |     ActionExecutor, persistence   |
 |          |             |                |                 |            |                      |
 |          v             v                v                 v            v                      |
 |      +-----------------------------------------------------------------------------+         |
 |      |  core/RingController (interface)   core/GestureStudio (interface)           |  <-- THE SEAM
 |      |  RingEvent, TouchKind, RingMode, RingStatus, MotionBatch, RingCapability    |         |
 |      +-----------------------------------------------------------------------------+         |
 |              ^  FakeRingController (tests, previews)      ^  RealGestureStudio               |
 |              |  ExampleRingController (template)                                            |
 |              |                                                                               |
 |   testbed/RealRingController  (adapter: text lines -> RingEvent / RingStatus StateFlows)      |
 |              |  RingLink.Listener                                                            |
 |   testbed/RingLink  (single shared BLE link: scan, GATT queue, notify, reconnect, OTA, logs)  |
 |              |                                                                               |
 |   testbed/RingProtocol (framing, CRC-16/ARC, builders, FrameAssembler, classify)             |
 |   testbed/PxiOta (stock OTA client, hash allowlist)                                          |
 +----------------|-----------------------------------------------------------------------------+
                  | BLE: service a00a (write b002 / notify b003); ff00/ff01 only for OTA
                  v
 +--------------------------------------- SY01 ring ---------------------------------------------+
 |  stock vendor firmware V2.1.4  (BLE stack, OTA updater, drivers, boot)   <- unchanged            |
 |  + fw/ring_app patch layer (touch, modes, accel ~99 Hz, gestures, tilt mouse, heart, keys)       |
 +--------------------------------------------------------------------------------------------------+

 Outside the app:   pc/ring_bridge.py --ws--> /v1/events (pc_keys -> uinput, or `ydotool key`)
                    other apps (gaze keyboard) --ws--> /v1/events (touch HOLD_START/TAP/HOLD_END)
```

Data flow for a touch event: ring GPIO poll -> `7A 15` frame on `b003` -> `RingLink` (GATT notify, `FrameAssembler`, CRC check) -> `RingProtocol.classify`
(text "Ring touch press t=1200") -> `RealRingController.onSetting` (regex) -> `RingEvent.Touch(HOLD_START, atMs, ringTick)` on `events` ->
`RingApiServer` broadcasts `{"event":"touch",...}` to every `/v1/events` WebSocket, and the UI activity feed shows it.

Data flow for motion: `setMotionStreaming(true)` -> `7A 04` -> ring streams `7A 11` batches -> `MotionBatch` on `motion` -> `RealGestureStudio`
(segment, features, DTW) -> `GestureHit` -> `ActionExecutor` -> action; also `/v1/motion` subscribers get the raw batches.

### Packages

| Package / path | Role |
|---|---|
| `core/` | The contracts: `RingController`, `GestureStudio`, data classes, `FakeRingController`, `FakeGestureStudio`, `RingServices` (where the UI gets its instances), `example/` (template for new rings) |
| `testbed/` | SY01-specific BLE and protocol: `RingLink`, `RealRingController`, `RingProtocol`, `CommandTable`, `PxiOta`, `SessionLog`, the app class `RingApplication` that wires real implementations, and the original Views screens (`HomeActivity`, `MainActivity`) |
| `ui/` | Compose app: `RingApp` (nav), `vm/RingAppViewModel`, `screens/`, `components/`, `logic/` (pure helpers, tested), `prefs/`, `theme/` |
| `gestures/` | Phone-side gesture engine and actions; pure JVM, no Android classes except `AndroidPlatformActions` |
| `api/` | `RingApiServer`: bearer-token HTTP + WebSocket |

### Invariants

- UI, gestures and API depend only on `core/` interfaces, never on `RingLink`.
- One GATT operation at a time; `RingLink` is a singleton so every screen shares one link.
- `RingLink` never writes `ff00/ff01/ff02` except through an approved `PxiOtaSession`.
- Firmware images are sent only if their SHA-256 is in `PxiOtaSession.ALLOWED_SHA256`.
- Unknown frames are logged, never fatal. Event JSON shapes are API: change `docs/API.md`, the server, and the fixture together.

## Firmware layers

```
 ring boot ROM (mask ROM, flash primitives)  --- unchanged, not reachable from OTA (inference)
        |
 vendor image V2.1.4 @ 0x10000000 (174,952 B): vendor BLE stack, HID, OTA updater (ff01), drivers (accel I2C, PPG, GPIO display, touch)
        |   fixed `bl` redirects (build_image.py patch list, each checked against stock bytes)
        v
 fw/ring_app  (ring_app.c, stub.S, cave.ld)  in the freed SpO2 code region 0x10026404..0x1002831c
   - touch_tick (20 ms): press/release/tap/hold, modes: CLOCK / MOUSE / HEART / KEYS
   - gest_tick: accel FIFO @ ~99 Hz -> bump-bump-flick detector, motion recorder (7A 11)
   - pointer / joystick mouse -> stock mouse_report (HID)
   - heart: PPG batch -> beat detector, LED flash, BPM
   - hook_cmd: group 0x7A commands; everything else -> stock processor
   - state in RAM regions A-H (memory only removed stock code used)
```

Verification layers (cheapest to strongest): `fw/ring_app/model/*.py` (Python reference models) -> `test_ring_app.c` (host C, mocked stock functions; the C detector must match the model on the recordings)
-> `emu/*.py` (Unicorn runs the **real patched image**, real stock timer/drain/accel-read code, I2C and BLE sender stubbed, flags any write outside our regions or the stack)
-> a ring. `fw/mouse/` is the earlier standalone mouse core with its own host tests.

Other host-side tools: `client.py` / `ring_features.py` / `command_table.py` (Python BLE client and parsers), `pxi_ota.py` (OTA state machine with simulator, no BLE transport on purpose),
`tools/analyze_stock_firmware.py`, `pc/ring_bridge.py`.
