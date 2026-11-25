# Changelog

Dates are 2026. Firmware "builds" are our patch layer on vendor V2.1.4. The images themselves are never distributed (they are derivatives of the vendor image); the SHA-256 of each image the author flashed is in the app's allowlist (`PxiOta.kt`), and `build_image.py` prints the hash of yours.

## 0.1.1 - review fixes (2026-11-25)

- **API is now local-only by default.** The LAN switch starts off (the server binds to `127.0.0.1`); turn it on to reach the phone from a PC, glasses or the gaze keyboard. The app explains this under the switch.
- PC bridge: the `ydotool` fallback now presses the real key codes (`ydotool key 29:1 46:1 46:0 29:0` for `ctrl+c`); before, it typed the combo as text. New tests in `tests/test_ring_bridge.py`.
- Removed the optional JieLi auth probe (`JlAuthSession`, the `RcspAuth` JNI stub and its dev button). It never applied to the SY01 and needed a vendor library.
- `POST /v1/mode` error replies are now valid JSON for any message.
- Example ring template: the frame assembler returns every complete frame in a notification and resyncs on garbage (new test).
- `tools/capture_fw_url.sh` creates `captures/` itself and says that it clears logcat.
- Docs: firmware allowlist and build 14 history made consistent, quickstart gaps filled (SDK without Android Studio, adb, same network), stale "unverified" CI/JDK 17 claims updated, stray-TAP rule added to the event contract.

## 0.1.0 - initial public release (2026-11-25)

First public cut of the app, tooling and firmware source built over 2026-11-18 to 2026-11-25.

### App
- Built-in gesture message is now "HI THERE" (builds 9-16 used a different phrase). Source-only change, same length so timing is unchanged; host tests updated and passing, **not built into an image or flashed**, so any image you build is a new hash (see docs/OTA_ROLLBACK_CHECKLIST.md).
- Compose app (Ring, Display, Gestures, Mouse, Health), developer mode with console, raw commands and firmware update.
- `RingController` / `GestureStudio` seam; `FakeRingController` for tests; `RingCapability` (advisory) and a documented template for new rings (`core/example/`).
- Auto-reconnect: after an OTA reset or a drop the link retries until the user disconnects.
- Local HTTP + WebSocket API with bearer token (`/v1/status|display|message|mode|clock/sync|find|gestures`, `WS /v1/events`, `WS /v1/motion`).
- Gesture Studio: phone-side trainable, rotation-invariant motion gestures with webhook, ring-text, media-key, PC-keys and API-event actions.
- `pc/ring_bridge.py` PC bridge. Python client, command table (162 triples), OTA state-machine simulator.
- Stock OTA client with SHA-256 allowlist, typed-hash confirmation, per-burst checksum and verify-before-reset.

### Firmware builds (fw/ring_app)
| Build | Date | Notes |
|---|---|---|
| 1 | 11-18 | First image: SpO2 code region reused for our code; touch/HID mouse hooks. Worked on the ring (axes reversed). |
| 2 | 11-18 | Command hook (group 0x7A), mouse settings, screen text, fix for a RAM overlap with the PPG reader. |
| 3-4 | 11-18 | Live heartbeat (LED per beat, BPM), PPG stream; build 3 degraded the mouse (stock accel drain called too often), build 4 reads the FIFO only. |
| 5-8 | 11-18 | Motion recorder (`7A 04/11`). Builds 5-7b recorded nothing: our state sat inside our own timer struct. Found with the emulator (build 8). Stock step counting and sleep analysis removed. |
| 9 | 11-19 | Recorder ODR code; ~99 samples/s measured on the ring. |
| 10 | 11-19 | Always-on bump-bump-flick detector. |
| 11 | 11-19 | User-set gesture message (`7A 07/08`). |
| 12 | 11-19 | Touch is ours: clock page, 5 taps = mouse, 2 s hold = heart; tilt mouse; touch/mode events (`7A 14/15`). |
| 13 | 11-19 | Gesture v2: needs a real arm swing, rejects flicks/snaps/shaking. |
| 14 | 11-20 | Pointer mouse, fast BLE link in mouse mode. **Withdrawn** and removed from the allowlist: it wrote to a wrong RAM address, and after ~19 s in mouse mode the link slowed and then no data reached the phone (connection stayed up). |
| 15 | 11-20 | Correct addresses, emulator wild-write guard (fails build 14), gentler mouse gains, pulse-synced heart LED. |
| 16 | 11-22 | Keys mode for external keyboard apps: touch edges with a free-running 20 ms ring tick, fast link held. |

### Release preparation
- Removed vendor binaries and personal data; `build_image.py` now takes the user's image path and refuses on a hash mismatch; `tools/check_all.sh` skips image-dependent steps cleanly.
- Fixed a flaky `RealGestureStudioTest` (virtual-time timeout).
- Event contract fixture and test; documentation rewrite.
