# Stock firmware analysis: SY01 V2.1.4

Evidence behind [FIRMWARE.md](FIRMWARE.md). Everything is labelled **confirmed** (read from the image or seen live),
**inference** (strong but unverified) or **hypothesis**. Static findings come from reading the image offline (Thumb disassembly with
capstone, base `0x10000000`); no vendor bytes are reproduced here, only addresses and behaviour. Findings are specific to the image below,
not to other devices or versions.

| Property | Value |
|---|---|
| File | `firmware/SY01_30330000_V2.1.4_stock.bin` (you supply it; not in this repo) |
| Size | 174,952 bytes (`0x2ab68`) |
| SHA-256 | `8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50` |
| Mapped range | `0x10000000`–`0x1002ab67` |
| Analysis date | 2026-11-18 (live checks through 2026-11-20) |

Run `python3 tools/analyze_stock_firmware.py [path]` to verify the image hash and reproduce
the fixed-offset facts. It only reads the file.

## Image and startup layout

The file is a raw little-endian ARM Cortex-M application image, not a packaged
OTA container. Its vector table begins at file offset zero:

| Vector | Value | Evidence |
|---|---:|---|
| Initial stack pointer | `0x20007688` | file `+0x0000` |
| Reset | `0x100000d5` | file `+0x0004`; Thumb bit set |
| NMI | `0x100000dd` | file `+0x0008`; Thumb bit set |
| HardFault | `0x10004c51` | file `+0x000c`; Thumb bit set |

The reset stub at `0x100000d4` calls `0x10009d0c`, which stores the SDK's
16 MHz clock value (`0x00f42400`), then transfers to the runtime entry at
`0x100000c0`. Runtime startup restores the stack, performs the compiler's data
initialization at `0x10003788`, and branches to the application entry at
`0x1001ad08`.

This proves the application's link address. It does **not** prove that the
174,952-byte file contains the resident OTA updater or every flash partition.
No credible direct reference outside the mapped image was found, but absence of
a reference is not proof that a bootloader is absent. The safe conclusion is
still “application image; updater location unknown.”

## Confirmed components and behavior

### Motion sensor: Silan SC7A20/SC7A20H family

The probe beginning at `0x100062b0` scans I²C addresses `0x18` through `0x1f`.
For each responding address it:

1. writes reset/control values to registers `0x24`, `0x1e`, and `0x1b`;
2. reads register `0x0f` and requires `0x11`;
3. reads register `0x70` and accepts `0x11`, `0x26`, or `0x28`.

That register identity, address range, and version behavior match the Silan
SC7A20/SC7A20H three-axis accelerometer family. The stock code processes three
axes and uses motion/step state internally. The part provides single/double-click,
orientation, FIFO, and motion interrupts, so the stock tap gestures can be
implemented by this sensor; the exact event path still needs live correlation
before labeling a particular gesture as confirmed.

The important app-side consequence is that raw three-axis samples exist inside
the firmware even though the stock BLE command set does not expose a raw stream.
Custom firmware can expose them without adding new sensor
hardware.

### BLE and HID data

The image contains a BLE attribute database near file offset `0x295c0`, the
device-name value `*PixArt BLE Keyboard` at `+0x295cb`, and the HID report map
immediately following it. The report map contains keyboard, mouse/axis, consumer
control, and vendor-defined reports. This independently agrees with the live
HID-over-GATT bond record and with the PixArt-style `ff00` OTA path.

“PixArt BLE Keyboard” identifies the linked BLE SDK/template. It does not, by
itself, identify the silicon vendor. The `0x093a` value in the link-layer version
data is strong evidence for a LinkedSemi Bluetooth controller/stack, but it does
not identify an exact part. The previous leap from that value to the LE501x
family remains unsupported and should not be used to select a toolchain or flash
layout.

### Display implementation

The application contains UI strings (`FIND`, `PHONE`, `LONG`, `PRESS`, `SPO2`,
`SLEEP`), font/segment tables, and a GPIO pair map at file offset `0x2a0a8`.
The display refresh path at `0x10019344` selects GPIO pairs and drives them in
opposite directions through routines at `0x10019098`, `0x10019128`, and
`0x10019190`. One of three timing constants (20, 60, or 150 time units) is
selected according to the active display configuration.

This is direct multiplexed/charlieplex-style GPIO drive, not a conventional
external framebuffer controller. No pixel-buffer transfer corresponding to a
48×64 panel was found. Treat the reported 48×64 fields as logical UI metadata,
not proof that arbitrary 48×64 pixels can be addressed through the stock
hardware or protocol.

### Second serial sensor at write address `0x88`

A software two-wire bus on GPIO 16 and GPIO 18 accesses a device using the
8-bit write address `0x88` (7-bit address `0x44`). The driver at `0x10018474`
writes a large register configuration, checks register `0x00 == 0x27`, and
reads multi-byte sample/calibration data. Its location beside the health-sensor
algorithms makes a biometric/optical front end likely, but the exact part has
not been established from public primary documentation. It remains labeled
**unidentified**, rather than guessed from the chip-ID byte alone.

## What this analysis resolves

- The image load address and startup path are confirmed.
- The motion sensor family and discoverable I²C address range are confirmed.
- Raw motion samples exist internally but are not exported by the stock BLE API.
- The display is directly multiplexed from GPIO; a generic pixel framebuffer
  was not found.
- The image is application-shaped and gives no evidence that OTA overwrites the
  resident updater, but updater survival and bounds checks remain unproven.

## Open risks (still unresolved)

1. The exact MCU/SoC is unidentified (needs primary evidence: marking, vendor SDK match, or a trusted memory-map document).
2. The resident updater's slot addresses and bounds checks are unproven; whether the ROM falls back to the old slot if a new image hard-faults at boot is unknown.
3. No recovery route that works after an image that boots but breaks BLE/OTA has been demonstrated (no SWD or serial access identified).
4. The 16-bit additive sum is the only integrity check (no signature), so a corrupted-but-summing image would be accepted.

These are why [FIRMWARE.md](FIRMWARE.md) tells you that flashing can brick a ring.

## OTA updater path (offline, 2026-11-18)

Same image (sha256 `8fb5ee37…1e50`).

**Confirmed (addresses):**
- ff01 opcode dispatcher at `0x1001b2c2`–`0x1001b3ca`: 0x27 INIT_NEW → `0x100140a4`, 0x25 OBJECT_CREATE → `0x10014060`, data → `0x10014154`, 0x18 UPGRADE/verify → `0x10014110`, 0x22 reset → callback +0x5c, plus 0x10, 0x23, 0x28, 0x29. There's no handler for SET_ADDRESS 0x11, ERASE 0x16, or SECTOR_ERASE 0x19 in this dispatcher.
- The OTA state block is in RAM at `0x20002074`. Its callback slots +0x44…+0x60 are filled at `0x1001b468` (literals `0x1001b49c`…): +0x44 `0x1001b502` (always returns 1), +0x48 verify `0x1001cbec`, +0x54 write `0x1001cbfc`, +0x58 object-start `0x1001b410`, +0x5c reset `0x1001b420`.
- Object-create stores the phone's u32 **offset** in state+0x34 (`0x10014060`). The write callback (`0x1001cbfc`) computes the destination as **base + offset**, where the base comes from a header pointer at `[0x20002518+0xc]`: field +0x14 if the bank byte `[0x20002518+7]`==0, field +0x10 if ==1. Object-start (`0x1001db4c`) reads a 0x57-byte record via ROM `0x5359`, picks the same +0x14/+0x10 base, and erases one sector at base+offset via ROM `0x5359+0xea`.
- **Flash program/erase/verify run from mask ROM**, not this image: write → `0x000053c1`, erase → `0x00005443`, read → `0x00005359`, verify/commit → `0x00004a7b` (tail call at `0x1001cbec`).
- The data path clamps each chunk to the remaining declared size (`0x10014154`). The phone never supplies an absolute address.

**Inferences (strong but unverified):**
- **A/B (dual-bank) layout:** two image slots whose addresses come from a flash/ROM header (fields +0x10/+0x14). The updater writes into the slot selected by the bank byte (presumably the non-running one), and verify/commit (ROM `0x4a7b`, given size + 16-bit sum) switches slots. An interrupted transfer or a checksum failure therefore leaves the running image untouched.
- **The boot loader is in ROM.** All flash primitives live below 0x10000 and the app image is linked at 0x10000000 (remapped XIP), so an OTA transfer has no path to overwrite the boot code.
- **No signature:** integrity is the 16-bit additive sum only. A modified image will be accepted if size and sum are correct.

**Still unknown (the remaining real risk):**
1. Slot addresses and sizes (header contents are runtime/ROM data).
2. Whether the ROM falls back to the old slot if the new image hard-faults at boot.
3. Whether a new image that boots but has broken BLE/OTA can be recovered. Without SWD that would strand the ring, so **every custom image must keep the stock OTA code path byte-identical**.

## SoC check against the public LinkedSemi SDK (2026-11-18)

The official `linkedsemi/LS_SDK` (GitHub) supports only `le501x`. Its GNU linker
script (`soc/arm_cm/le501x/compiler/gnu/ble.ld`) puts RAM at `0x00000000`
(48 KB) and app flash at `0x18034000`. The SY01 image instead runs from flash
`0x10000000` with RAM at `0x20000000` and flash primitives in ROM
`0x00004a7b`–`0x00005443`. **The SY01 is not an LE501x**, so there's no matching
public SDK. Company id 0x093A most likely reflects licensed BLE controller IP,
not the MCU. The SoC remains unidentified.

## IMU identification (2026-11-18, offline)
Confirmed from code:
- Probe 0x100062b0 scans I2C addresses 0x18–0x1F. At each address it writes 0x24=0x80 (reboot),
  reads 0x0F and requires 0x11, then reads 0x70 and accepts 0x11/0x26/0x28. The register map is LIS3DH-style.
- Init 0x10007834…:
  - 0x20=0x47: 50 Hz ODR, XYZ enabled.
  - 0x23=0x90 or 0x98: BDU on, ±4 g (0x98 also sets HR).
  - 0x24=0xCA, 0x1F=0x40.
  - 0x2E=0x5F: FIFO stream mode, watermark 31.
- Variant 0x28 additionally gets 0x68=0xA5.
- The FIFO is drained into int16 buffers at 0x2000485e (X), +0x40 (Y), +0x80 (Z), up to 32 samples, with the count at 0x200023fd.
  Then 0x10009764 passes each sample >>2 to the tap detector 0x1000d80c.
- No second motion-sensor probe and no gyro register traffic were found. This is a **3-axis accelerometer only**.

Hypothesis (not verified on hardware):
- WHO_AM_I 0x11 at 0x18/0x19 matches the Silan SC7A20 family (a LIS3DH-compatible clone).
- 0x70 = 0x11/0x26/0x28 most likely tells the SC7A20 / SC7A20E / SC7A20H variants apart.
- The exact variant on this ring is unknown until a custom build reports the byte.

## Touch input + HID descriptor (2026-11-18, offline)
Confirmed from code:
- **The touch pad is a digital GPIO input on pin 10**, not I2C. (The only I2C device in the image is the accelerometer, at 0x18/0x19.)
  - Init 0x1001d900: pin 10 is configured via 0x10015284 and 0x10015380; the edge handler is 0x1002932c; NVIC IRQ 12 is enabled.
  - Pin read: 0x100153fc(10). Non-zero means pressed (active-high).
- Key scanner 0x10018698 runs on a periodic soft timer (start 0x10010da4, reload literal 0xA3D). State lives at 0x200021ca.
  - Pressed: increment the hold count at [+1]. Above 20 ticks it fires event 1 (LONG).
  - Released after a press: fires event 0 (SHORT), then increments the tap count at [+2].
    A second tap inside the window timer (0x10010d8c, literal 0x4CCD) fires event 2.
    The expiry path is 0x1001867c → 0x10018724.
- Event dispatcher 0x1001c560(ev):
  - If the screen is off, any event wakes it (0x1001e034).
  - 0 → 0x1001e0b4 (masks IRQ 12, then calls next-page 0x10019578).
  - 1 → 0x1001e0e8 (long-press page action).
  - 2 → 0x1001d9fc.
- Next-page 0x10019578: on page 0x13 (camera) it fires the shutter. Otherwise it advances the menu index at [0x200024c4+4] through the page list.
- HID report map at 0x1002961a:
  - Report ID 2 = mouse: 8 buttons, X/Y **16-bit relative** (−32767..32767), wheel int8, AC Pan int8.
  - Report ID 1 = keyboard.
  - Real button clicks are supported by the descriptor. Stock firmware simply never sends them.

Hypothesis:
- Soft-timer ticks are 32.768 kHz. That makes the scan period about 80 ms, long press over about 1.6 s, and the double-tap window about 600 ms.
- Whether the touch pad is a separate touch-IC output or the SoC's own sense pin is unknown. Either way the firmware only sees a digital level.

## LIVE: stock OTA round trip (2026-11-18)
Write #1 was the unmodified stock V2.1.4 image (sha256 8fb5ee37…), explicitly approved by the user, sent through the app's `PxiOtaSession`.
- Every burst checksum ack matched. UPGRADE was acked with `18 00 00`.
  The verify read-back returned `0E 02 18 00` (OK). Then MCU_RESET `22 00` was sent and the link dropped as expected (status 8).
- **Confirmed live:** INIT_NEW params, OBJECT_CREATE, burst/sum16 acks, UPGRADE, verify read, and reset.
  Afterwards the ring rebooted, reconnected and still reported v2.1.4 with touch working (so the reboot into the new slot is confirmed for the stock image).

## Side LEDs, screen UI, PPG path (2026-11-18, offline)
Confirmed from code:
- The display is a software-multiplexed (charlieplexed) LED matrix: pins 0, 0x19, 0x1e, 1, 0x18, 0x16, 0x15. The scan routine is around 0x10019350.
  The **side LEDs are the icon slot**, char at 0x200024e9 (UI buffer 0x200024c4 + 0x25):
  - `'r'` → 0x10019190 (pin 0x15 high, 0x16 low) = red heart LED;
  - `'g'` → 0x10019098 = green footstep LED;
  - `'b'` → both.
  draw_text sets it to 'r' for pages 2..14, 'g' for 0x11 (steps), 'b' for 0x1a. Otherwise it's taken from str[5].
- UI state at 0x200024c4: +3 page, +0xc idle seconds (u16), +0x1c screen on. The 1 Hz UI tick 0x10018d64 turns the screen off via 0x100194a8 once idle ≥ timeout (0x1001d66c).
- Measurement control:
  - 0x1000f830(mode) start, 0x1000f79c(mode) stop; the current mode byte is at 0x200022b4.
  - The app's HR command (sensor 3) maps to mode 8, then step type 0, then 0x100167c0.
  - Jump table at 0x10017571: type 0/10 HR, 1 SpO2, 2 0x10018430, 3/9 0x10016df0.
- PPG: 0x100167c0 → 0x10016a38 reads the sensor FIFO (I2C 8-bit 0x88, FIFO level reg 0x40, data 0x41, 3 bytes/value, 2 channels).
  - Count is at 0x20004aa0; the channel used by the HR algorithm (int32) is at 0x20004aa4; the other channel is at 0x20004b64.
  - The FIFO read buffer allows up to 33 pairs. Our build 3+ keeps 8 guard bytes after 0x20004b24 and resets its state if the count is over 34.

## Hook points our firmware uses (stock V2.1.4 addresses)

Our app layer (`fw/ring_app/ring_app.c`) runs on the vendor's unchanged BLE stack, OTA updater and drivers. It lives in the code space freed by
removing the stock SpO2 algorithm (`0x10026404..0x1002831c`, single caller, result unused) and redirects these `bl` call sites (all asserted by
`build_image.py` against the expected stock encodings):

| Site(s) | Was | Now |
|---|---|---|
| `0x10009764` | accel sample -> tap detector `0x1000d80c` | `hook_accel` |
| `0x100186ce`, `0x10018702`, `0x1001870c`, `0x10018730` | touch event -> `0x1001c560` | `hook_key` |
| `0x1001330e` | BLE command -> `0x10012230` | `hook_cmd` (group `0x7A` ours; the rest forwarded) |
| `0x100167ea` | PPG read | `hook_ppg` |
| `0x1001925a` | draw-text gate | `hook_draw_gate` |
| `0x1000fb4c`, `0x10010a2e`, `0x1001ad8e`, `0x1001de96` | stock accel drain restart `0x100068ec` | `hook_drain_start` |
| `0x10017584` | SpO2 step | nop |
| `0x10009818`, `0x1000983a`, `0x1001758a` | step algorithm / sleep-HRV step | nop (deliberate removals of step counting, sleep and SpO2) |

Stock helpers we call: `draw_text` `0x10019254`, `mouse_report` `0x100154a8` (HID report 4), frame sender `0x10014834`, clock page `0x100192dc`,
accelerometer FIFO read `0x10008150`. RAM we use is memory only the removed step/SpO2 code referenced; the regions A-H are listed at the top of `ring_app.c`.
The builder asserts the vector table, OTA callback setup, OTA verify/write, OTA erase and the scatter table are byte-identical to stock,
and that the size equals stock.

**Why the tilt mouse behaves as it does:** the ring has a 3-axis accelerometer and **no gyro**. Translation shows up as acceleration then the opposite
acceleration when the motion stops, so acceleration-integrating mouse modes wander (an early build did). Tilt (the direction of gravity) is measured reliably, which is
what the pointer and joystick modes use. Turning the hand flat about the vertical axis is invisible to an accelerometer.

## Why this is probably not the JieLi RCSP path

- The vendor app sets its `isJLPlatform` flag for `ae00` **or** Telink **or** `ff00` services, so the flag does not mean a JieLi chip.
- The app requests MTU 185 only on the `ff00`/Telink branch; a JieLi-only device gets 509. Live: MTU 185 and notify on `ff01`, no `ae00` (confirmed live).
- The Bluetooth company id in the bond record is `0x093A` (LinkedSemi); JieLi would be `0x05D6`.
- Consequence: the JieLi RCSP auth handshake (and the vendor native library that implements it) is **not used** with this ring. Our code keeps a small
  optional compatibility probe (`JlAuthSession`, `RcspAuth`); it needs a vendor library that is not shipped here (see THIRD_PARTY.md) and does nothing on an SY01.

## Public recovery research (all unverified for this ring)

Public write-ups of JieLi chips describe a mask-ROM USB bootloader and UART bootloader (JieLi repositories by kagaimiq), and LinkedSemi's public
LS_SDK documents SWD programming and FOTA modes for its LE501x parts. None of it is established for the SY01: the SoC is unidentified, the
SY01 is not an LE501x (flash `0x10000000`, RAM `0x20000000`, flash primitives in ROM `0x4a7b..0x5443`, unlike LE501x), the charging contacts are not assumed to expose a
programming interface, and the ring may be potted (unverified). Treat "can I recover with SWD or USB" as **unknown** until someone opens a ring and shows it.
