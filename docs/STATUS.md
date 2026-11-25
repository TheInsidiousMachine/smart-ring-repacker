# Status

As of **2026-11-25** (release 0.1.0). "Ring" = a real SY01 with stock firmware V2.1.4 or our custom builds. See [PROTOCOL.md](PROTOCOL.md) for per-command labels.

## Verified on a ring

- BLE transport `a00a`/`b002`/`b003`, `AB` framing, CRC-16/ARC; 24 command triples seen in live vendor-app traffic match the extracted table. No `ae00` service; `ff00` present.
- Stock HID mode set/query, brightness set/query, shutter-mode touch taps (including app-side double/triple tap grouping), Music-mode events, feature-support bitmap.
- Stock OTA round trip: the unmodified V2.1.4 image was re-flashed through the app (all burst checksums matched, verify read-back OK, reset, reconnect, still v2.1.4 with working touch).
- Custom builds were flashed and run through build 14 (not every intermediate build is recorded as run). Observed: build 1 mouse worked; builds 3-4 showed mouse degradation and a link timeout traced to the stock accel drain being called too often; builds 5-7b recorded nothing (state clobbered by our own timer struct, fixed in 8);
  build 9 measured ~99 samples/s with no FIFO overrun and no disconnects while recording; build 14 broke the BLE link (wrong RAM address) and was withdrawn.
- OTA from the app takes about 60-90 s; the app now reconnects by itself after the reset (earlier it did not, so message edits never reached the ring).
- Text push (`{4,1,...}`) is **not supported** by this ring's stock firmware (no ack, nothing shown).

## Tested on host / emulator only

- Firmware builds 15 and 16 (keys mode, ring tick, pointer fix, wild-write guard): host C tests and the Unicorn emulation of the real patched image. Gesture detector: 10/10 on the target recording, 0 on bumps-only, flick-only, everyday and 40 synthetic multi-flick sequences.
- Pointer-mouse model (`model/pointer.py`): still-hand jitter ~2 px / 8 s, 5 px response ~40 ms at 100 deg/s (simulation).
- API (`RingApiServerTest`, real Ktor server, fake ring), Gesture Studio on the recordings (held-out 5/5 and 7/7, 0 false hits), Compose screens compose to idle (`UiComposeTest`).
- Release preparation, 2026-11-25: `python3 -m pytest -q tests/` 28 passed; `./gradlew --offline testDebugUnitTest` 61 passed (JDK 21); `tools/check_all.sh --no-android --fw` firmware host tests passed, image/emulator steps skipped (no vendor image in the staging tree).

## Unverified

- **Keys mode and the gaze-keyboard integration** over real BLE (builds 15/16 not run on a ring in our records).
- Pointer mouse feel and gains on a hand, axis mapping, HID path to a host, clock accuracy after sync, battery cost of ~99 Hz accelerometer reads.
- Heart mode: beat detection and LED sync on a worn ring (an earlier capture may have been taken off-finger).
- Real-world false-trigger rate of bump-bump-flick (only ~40 s of everyday motion recorded); real snaps and real shaking (no recordings).
- API from another machine; the PC bridge on desktops other than KDE Wayland; Android media-key action; Gesture Studio over real BLE.
- The GitHub Actions workflow (written, not yet run), a JDK 17 build, a cold-cache build.
- Whether the ring falls back to the old slot after a hard-faulting image; any recovery path (see [FIRMWARE.md](FIRMWARE.md)).
- Haptic motor control (GPIO not identified; do not toggle blind). The `0x44` serial sensor part and the exact SoC are unidentified.
- Any ring other than the SY01.

## Known limitations

- API settings (enabled, port, token, LAN) are not persisted; the API is off after an app restart and a new token is generated.
- `RingCapability` exists but the UI does not hide unsupported controls yet. There is no ring picker; the app wires the SY01 controller in `RingApplication`.
- The app uses the first `SY01` it finds; one ring at a time.
- Our firmware removes step counting, sleep analysis and SpO2. Stock knock/tap macros do not run while the gesture detector owns the accelerometer.
- A custom image's hash must be in `PxiOtaSession.ALLOWED_SHA256` or the app refuses to flash it; different toolchain versions may produce a different hash.
- The built-in bump message is a profane two-word phrase; set your own text.
- Heart and mouse tuning curves in the UI are sketches, not the firmware's exact functions.

## Next

See [ROADMAP.md](ROADMAP.md). First: run keys mode against the gaze keyboard on real hardware and record the result here.
