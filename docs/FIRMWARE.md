# Custom firmware for the SY01

> **YOU CAN BRICK YOUR RING.** Flashing firmware over BLE can leave a ring that does not boot or reconnect, and **no recovery path has been
> demonstrated**. The SoC is unidentified, no SWD/serial access has been found, and the ring may be potted. Flash **only a ring you own**,
> only an image you built yourself from your own vendor image, and only after reading [OTA_ROLLBACK_CHECKLIST.md](OTA_ROLLBACK_CHECKLIST.md).
> Everything else in this project (the app, the API, gestures with stock firmware) works without ever flashing.
> Provided as is, without warranty (see LICENSE).

## What "custom firmware" means here

We do **not** replace the whole firmware. We have no BLE stack source for this chip, and the only recovery path we know (the stock OTA) is stock code.
Instead `fw/ring_app/` is a small C application that is **patched into the unchanged V2.1.4 vendor image**: our code goes into the space freed by removing the stock SpO2 algorithm, and
a fixed list of `bl` call sites is redirected to it. The vendor BLE stack, OTA updater, drivers and boot code stay byte-identical (the builder asserts it).
Analysis and addresses: [FIRMWARE_ANALYSIS.md](FIRMWARE_ANALYSIS.md).

What our build changes compared to stock V2.1.4:

| Area | Change |
|---|---|
| Touch | Our 20 ms GPIO poll owns the touch pad. Press/release edges with a ring tick, modes (clock, mouse, heart, keys), 5 taps = mouse, 2 s hold = heart. Stock touch menu and knock macros are off. |
| Accelerometer | ~99 Hz instead of ~25 Hz; our timer reads the FIFO, the stock 500 ms drain stays stopped. Raw stream to the app (`7A 11`). |
| Gestures | On-ring bump-bump-flick detector with a user-set message. |
| Mouse | HID mouse from hand tilt: pointer or joystick style, tap = click, press-and-hold-still = right click. |
| Heart | Live beat detector, LED flash per beat, BPM on screen (**unverified** on a worn ring). |
| Keys mode (build 16) | Touch edges only, fast BLE link held, no HID, no shortcuts; for [external keyboard apps](INTEGRATION.md). |
| Clock | Stock clock page shown on press; phone syncs the time on every connect. |
| Removed | **Step counting, sleep analysis, SpO2** (frees RAM and code; deliberate). |

Protocol for the new commands: [PROTOCOL.md](PROTOCOL.md#custom-command-group-0x7a-our-firmware-only). Build history: [CHANGELOG.md](../CHANGELOG.md).

## You must supply the vendor image yourself

The vendor firmware is third-party material. It is **not** in this repository and must not be committed or redistributed, and neither must images built from it
(they contain vendor code). Put your copy at `firmware/SY01_30330000_V2.1.4_stock.bin` (git-ignored).

Known-good image: ring model `30330000`, V2.1.4, **174,952 bytes**, SHA-256

```
8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50
```

Check it: `cd firmware && sha256sum -c SY01_30330000_V2.1.4_stock.bin.sha256`.

How the official app gets it (so you can obtain it for **your own ring** through **your own account/app**): RWFit asks the vendor server for an update with a
`getOtaUpgradeInfo` request and downloads the `.bin` named in the reply from the vendor CDN (host `cdn.ruiwo168.com`, path `ota/30330000/`). With your phone in USB or wireless debugging,
`tools/capture_fw_url.sh` passively records `adb logcat` while **you** open RWFit -> Device -> Firmware update (do **not** press update) and prints the matching
log lines (URL, version). The script sends nothing to the ring. Then download that file yourself and verify the hash. If your ring reports a different version or model, you have a
different image: the builder and the app will refuse it. We do not host the file and we do not provide the CDN URL for your device.

## Build

Needs `arm-none-eabi-gcc` (developed with Arm GNU Toolchain 14.3.rel1; other versions may produce different bytes), Python 3 and gcc for the host tests.

```bash
python3 fw/ring_app/build_image.py firmware/SY01_30330000_V2.1.4_stock.bin
# or SY01_STOCK_IMAGE=/path/to.bin ; toolchain not on PATH: ARM_GCC_PREFIX=/path/to/bin/arm-none-eabi-
```

`build_image.py` **refuses to run unless the input's SHA-256 equals the value above**, applies the fixed patch list (each patch checks the stock bytes it replaces),
and refuses to write an image if any byte outside the allowed ranges changed or the size differs from stock. Output:
`fw/ring_app/build/SY01_30330000_V2.1.4_custom_<sha8>.bin` plus the image's SHA-256.

Before any flash, run the gate with your image present: `tools/check_all.sh --fw` (host C tests, image build, Unicorn emulation of the **real patched image**; needs `pip install unicorn`).
See [TESTING.md](TESTING.md). Without your image the host tests still run and the rest is skipped.

## Flash flow (app, developer mode)

The app only ever sends images through `PxiOtaSession` (stock OTA protocol on `ff01`), and several independent gates stand in the way:

1. **Hash allowlist.** `PxiOtaSession.ALLOWED_SHA256` (`testbed/PxiOta.kt`) lists the exact images that may be sent: stock V2.1.4 and our builds 9-16. `RingLink.startFirmwareUpdate` refuses anything else.
   Your own build only appears in the picker if its hash is listed. Builds are reproducible only with the same toolchain; **if your hash differs, the app will not flash it** until you
   add your hash to the allowlist yourself (a deliberate act: do it only after the rollback checklist for that exact image).
2. **Staging.** Copy the `.bin` files (your custom build **and** the stock image, which is your way back) into the app's files dir on the phone:
   `Android/data/com.smartring.testbed/files/firmware/` (e.g. `adb push`).
3. **Developer mode -> Ring tab -> Firmware**: pick the image; the dropdown shows name and first 8 hash characters.
4. **Explicit confirmation.** Type the first 8 hex characters of the image hash; the button enables only on an exact match, and the controller re-checks.
5. The client sends only `INIT_NEW`, `OBJECT_CREATE`, data, `UPGRADE`, `MCU_RESET`. It checks the ring's running 16-bit sum after every burst and aborts on mismatch,
   and sends the reset **only after reading back the ring's verify result `0E 02 18 00`**. The phone never sends a flash address; offsets are image-relative.
6. After the reset the app auto-reconnects. Confirm the firmware version and a working touch before you do anything else.

Rule for any image: keep the stock OTA code path byte-identical so the next update can still be done over BLE, and **flash the unmodified stock image first** to prove the path on your ring.

## Recovery: what is and is not known

| Situation | Status |
|---|---|
| Transfer aborts or the link drops before verify | **Expected** (inference from the OTA code, not tested by interrupting): the update writes the other slot, the running image is untouched. |
| Ring verifies, resets, image boots, BLE works | **Demonstrated live** for stock V2.1.4 (re-flash round trip) and for custom builds up to 14, including updating from one custom build to the next over the same OTA path. Flashing stock *back from* a custom build is expected to work the same way but was not separately recorded. Builds 15 and 16: host tests and emulator only in our records. |
| Image boots but BLE/OTA is broken | **Not recoverable by us.** You would need physical access (SWD or similar) and nobody has shown that route. |
| Image hard-faults at boot | **Unknown.** Whether the ROM falls back to the old slot has not been tested. |
| Integrity | 16-bit additive sum only, no signature. |

We never wrote to the bootloader area (it is in mask ROM per our analysis, unverified) and the builder asserts the OTA code is byte-identical to stock.
Recovery through the official app (RWFit) with the same stock image was **not** tested after a custom image.

## Rules for contributors and agents

1. Firmware changes are offline work: host tests + emulator. Never flash from CI or from an automated agent.
2. Any flash needs: your own ring, the exact SHA-256 of the image, a completed [rollback checklist](OTA_ROLLBACK_CHECKLIST.md), and the user's explicit confirmation of that hash.
3. A working handshake or transport is not permission to write firmware.
4. Do not commit vendor images, images derived from them, captures that contain your device address, or extracted vendor libraries.
5. Label every finding verified (seen on a ring), tested (host/emulator) or unverified, as in [FIRMWARE_ANALYSIS.md](FIRMWARE_ANALYSIS.md).
6. New patch sites go through `build_image.py`'s patch list, which checks the stock bytes and the allowed ranges.
