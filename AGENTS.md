# AGENTS.md

Entry point for AI coding agents (and humans in a hurry). Repo: https://github.com/TheInsidiousMachine/smart-ring-repacker. Licence MIT. Read [README.md](README.md) first, then this file, then the doc for your task.

## Scope and safety (non-negotiable)

- Work only on devices the user owns. No code aimed at other people's rings, phones or networks.
- **Never flash firmware, run anything that contacts a ring/phone over BLE/adb, or use a device, unless the user asked for exactly that.** Tests in this repo never touch hardware.
- Firmware writes need: the user's own ring, the exact image SHA-256, a completed [rollback checklist](docs/OTA_ROLLBACK_CHECKLIST.md), and the user's explicit confirmation of that hash. A working handshake is not permission. **A bad image can brick the ring.**
- Never commit vendor artifacts: vendor APKs/decompiled source, firmware `*.bin` (or images built from them), vendor native libraries, captures with device addresses, tokens, keystores ([THIRD_PARTY.md](THIRD_PARTY.md)).
- Label every claim **verified** (seen on a ring), **tested** (unit/host/emulator) or **unverified**. Public write-ups are not verification. Do not present guesses as facts in docs.

## Naming

Project: **Smart Ring Repacker**. Repo and Gradle project: `smart-ring-repacker`. Kotlin package `com.smartring`, applicationId `com.smartring.testbed` (historical, not worth a migration). PC bridge env vars: `REPACKER_URL`, `REPACKER_TOKEN`.

## Repo map

```
android/app/src/main/java/com/smartring/
  core/        RingController.kt  <-- THE SEAM (interface, RingEvent, TouchKind, RingMode, RingCapability)
               GestureStudio.kt, FakeRingController, FakeGestureStudio, RingServices, example/ (template for new rings)
  testbed/     SY01 BLE: RingLink (GATT queue, scan, OTA), RealRingController (adapter), RingProtocol (framing/CRC/builders),
               CommandTable, PxiOta (stock OTA client + hash allowlist), RingApplication (wiring)
  ui/          Compose app (RingApp, vm/, screens/, components/, logic/)
  gestures/    Gesture Studio engine (pure JVM)         api/  RingApiServer (Ktor HTTP + WS)
android/app/src/test/    unit tests (Robolectric for UI)
fw/ring_app/   custom firmware: ring_app.c, build_image.py (patches the user's own vendor image), test_ring_app.c, emu/ (Unicorn), model/ (Python reference), testdata/ (accelerometer recordings)
fw/mouse/      standalone mouse core + host tests
client.py, ring_features.py, command_table.py   Python BLE client/parsers      pxi_ota.py   OTA state machine + simulator (no BLE transport on purpose)
pc/ring_bridge.py   Linux PC bridge for pc_keys events     tools/   check_all.sh, analyze_stock_firmware.py, capture_fw_url.sh
tests/          Python tests (protocol, OTA sim, event fixture, PC bridge keys)      scripts/fetch-sibling.sh   clone the sibling repo
docs/           INTEGRATION ARCHITECTURE API PROTOCOL GESTURES FIRMWARE OTA_ROLLBACK_CHECKLIST FIRMWARE_ANALYSIS ADDING_A_RING TESTING BUILDING STATUS ROADMAP APP_GUIDE; fixtures/ring_events.jsonl
firmware/       README only: you supply the vendor image (git-ignored)
```

## Commands

```bash
export JAVA_HOME=/path/to/jdk-17-or-21   # JDK 17 or 21 (CI tests both); ANDROID_HOME must be set
tools/check_all.sh                       # python tests + android unit tests + assembleDebug  (the gate)
tools/check_all.sh --fw                  # + firmware host tests; image build + emulator only with the vendor image present
tools/check_all.sh --no-android --fw     # firmware/python only
python3 -m pytest -q tests/              # Python only (32 tests)
cd android && ./gradlew testDebugUnitTest          # Android tests (59)
cd android && ./gradlew assembleDebug              # APK at app/build/outputs/apk/debug/app-debug.apk
python3 fw/ring_app/build_image.py <your stock image>   # refuses unless SHA-256 matches; offline
```

Python needs only `python3` and `pip install pytest` (plus `evdev` for the PC bridge, `unicorn` for the emulator).

## Invariants and contracts

1. **`RingController` is the seam.** UI, gestures and API depend on `core/` only. To support a ring, implement the interface ([docs/ADDING_A_RING.md](docs/ADDING_A_RING.md)); unsupported members are harmless no-ops and are left out of `capabilities`.
2. **Touch contract:** every press emits `Touch(HOLD_START)`; release emits `TAP` (< 300 ms) or `HOLD_END`. External projects depend on this and on the WS JSON shape.
3. **Event JSON is an API.** Change `RingApiServer.kt`, [docs/API.md](docs/API.md), `docs/fixtures/ring_events.jsonl` and `tests/test_event_fixture.py` together. WS `kind` strings are the `TouchKind` enum names, never `press`/`release` (those appear only in `info` text).
4. One GATT operation at a time. `RingLink` never writes `ff00/ff01/ff02` except via an approved `PxiOtaSession`.
5. A firmware image is sent only if its SHA-256 is in `PxiOtaSession.ALLOWED_SHA256` and the user typed its first 8 hex characters. Agents never add hashes to the allowlist or weaken these gates; a human adds a hash by hand, only after the rollback checklist for that exact image.
6. `build_image.py` refuses a non-matching input hash and asserts patch bytes, allowed ranges and OTA-code identity. Keep the stock OTA path byte-identical in any image.
7. Group `0x7A` BLE commands/events are ours; stock firmware ignores them ([docs/PROTOCOL.md](docs/PROTOCOL.md)). The C detector must match the Python model on `fw/ring_app/testdata`.
8. Pure logic goes in testable classes without Android types; radio goes behind a transport interface; tests never use Bluetooth.

## Verified vs not (summary; details in [docs/STATUS.md](docs/STATUS.md))

- **Verified on a real SY01:** transport/framing, stock HID modes, brightness, shutter-mode taps, stock OTA round trip, custom builds up to 13 flashed and run (touch, recorder at ~99 Hz, gesture detector, tilt mouse in builds 12-13), auto-reconnect after OTA. Build 14 was flashed and failed in use (link stopped delivering data in mouse mode); it was withdrawn and fixed in 15.
- **Tested only on host/emulator:** firmware builds 15 and 16 (incl. keys mode), the API against a fake ring, Gesture Studio on recordings, the whole Compose UI (composes to idle only). CI on GitHub (JDK 17 and 21, cold cache) passes.
- **Unverified:** keys mode and the gaze-keyboard integration over real BLE, API use from another machine, heart BPM on a worn ring, media-key action, PC bridge on desktops other than the author's, any other ring (Colmi outline is from public docs only).

## Firmware rules

Read [docs/FIRMWARE.md](docs/FIRMWARE.md). Firmware work is offline: edit `ring_app.c`, run host tests and the emulator, update the Python model if detector logic changes. Never run or script a flash.
New `bl` patch sites go through `build_image.py`'s patch list. RAM regions A-H at the top of `ring_app.c` are the only RAM we may use (memory only removed stock code referenced); the emulator flags wild writes.

## Sibling repo

Another project uses this ring as its input button over `WS /v1/events`.

- URL: https://github.com/TheInsidiousMachine/gaze-keyboard (Everysight glasses gaze-glide keyboard)
- Expected local checkout: `../gaze-keyboard` (next to this repo). If it is missing, run `scripts/fetch-sibling.sh` (idempotent; clones if absent, never overwrites).
- Read first there: `README.md`, `AGENTS.md`, `docs/INPUT_SOURCES.md`, `docs/INTEGRATION.md`. Here: [docs/INTEGRATION.md](docs/INTEGRATION.md).
- Shared contract: `docs/fixtures/ring_events.jsonl` (copied by the maintainers to the sibling repo) and the `RingMode.KEYS` firmware mode.
