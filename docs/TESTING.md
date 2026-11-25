# Testing

One gate runs everything that does not need a ring: `tools/check_all.sh`.

```bash
tools/check_all.sh                 # Python tests + Android unit tests + debug build
tools/check_all.sh --fw            # + firmware host C tests; image build + emulator only if you supplied the vendor image
tools/check_all.sh --no-android    # skip Gradle (combine with --fw)
```

Needs: Python 3 with `pytest`; for Android see [BUILDING.md](BUILDING.md); for `--fw` gcc; for the image steps `arm-none-eabi-gcc` (or `ARM_GCC_PREFIX`),
`pip install unicorn` (or `EMU_PY`), and your own vendor image ([FIRMWARE.md](FIRMWARE.md)). Missing image or toolchain means those steps print `SKIP` and the gate still passes.
Never run it against hardware: nothing here talks to a phone, ring, or BLE device.

## What runs

| Suite | Command | What it covers | Count at release prep |
|---|---|---|---|
| Python | `python3 -m pytest -q tests/` | CRC and framing, command table vs live triples, feature parsers, OTA state machine round-trip against a simulator (forbidden opcodes refused), **event fixture contract**, PC bridge key codes (ydotool/evdev) | 32 |
| Android unit | `cd android && ./gradlew testDebugUnitTest` | `RingProtocolTest` (14), `RingApiServerTest` (6, real Ktor server on a fake ring), `RealGestureStudioTest` (5, incl. recognition on the real recordings), `PxiOtaTest` (3), `LogicTest` (10), `RingAppViewModelTest` (8), `UiComposeTest` (6, Robolectric composes each screen), `ShotsTest` (1, no-op unless `SHOTS_DIR` is set), `ExampleRingControllerTest` (6, template) | 59 |
| Firmware host | `gcc -DHOST_TEST -O1 -Wall -Wextra -Werror -o t fw/ring_app/test_ring_app.c -lm && ./t`; `make -C fw/mouse test` | Firmware logic with mocked stock functions: touch modes, mouse, gesture detector vs the recordings, recorder, heart, keys mode. The C detector must equal the Python model. | all pass |
| Image build | `python3 fw/ring_app/build_image.py <stock image>` | Needs the vendor image. Asserts hash, patch bytes, allowed ranges, vector table, OTA code identical, size == stock. | skipped without image |
| Emulator | `fw/ring_app/emu/emu_gesture.py`, `emu_modes.py`, `emu_touch.py` (run by `check_all.sh --fw`); `emu_recorder.py` by hand (see `emu/README.md`) | The real patched image under Unicorn with real stock timer/drain/accel code; flags any write by stock code into our RAM and any wild write by ours. | skipped without image |

The emulator and image-build steps are **not run in CI** (no vendor image there). Run them locally before any flash.

## Event fixture contract

`docs/fixtures/ring_events.jsonl` holds sample `WS /v1/events` lines; `tests/test_event_fixture.py` validates each line's field names and types, that touch kinds and
modes are real enum values, the HOLD_START -> TAP/HOLD_END pairing, ring-tick vs wall-clock agreement, and (when the Android sources are present)
that every event name in `RingApiServer.kt` is covered. A consumer project can copy the fixture and the shape part of the test.

## Writing tests for a new ring

Follow `core/example/ExampleRingControllerTest`: drive the controller through a `FakeTransport`, no Bluetooth. See [ADDING_A_RING.md](ADDING_A_RING.md).

## Known test issues

- `RealGestureStudioTest` used virtual-time timeouts around work on a real dispatcher and failed intermittently; it now waits on the real clock (fixed during release prep, ran green once afterwards; the flake was not reproduced to prove the cause).
- The Compose tests only prove screens compose to idle: no clicks, no dialogs, no light-theme screenshots.
- Nothing here exercises real BLE. Anything the app does over the air is **unverified** unless [STATUS.md](STATUS.md) says it was seen on a ring.
