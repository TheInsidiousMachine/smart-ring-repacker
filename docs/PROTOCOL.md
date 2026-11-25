# SY01 BLE protocol

How the SY01 smart ring talks over Bluetooth LE, and the extra command group our custom firmware adds.
Written for people who want to build on it or port another ring (see [ADDING_A_RING.md](ADDING_A_RING.md)).

## Confidence labels

| Label | Meaning |
|---|---|
| **live** | Seen on a real SY01 ring (vendor app traffic or our own app), result matched. |
| **static** | Transcribed from the vendor Android app (RWFit, package `com.rw.revivalfit`, decompiled with jadx) but **not** yet seen on a ring. Do not rely on it. |
| **fw-test** | Implemented in our custom firmware and checked in host C tests and the Unicorn emulator, plus (where noted) on a ring. |
| **unverified** | Hypothesis or public research that has not been reproduced on this hardware. |

"JL path" in older notes means the vendor app's `AB`-framed protocol branch, **not** a JieLi chip. In the vendor app,
`isJLPlatform` is set by `ae00` *or* a Telink OTA service *or* `ff00`. The SY01 exposes `ff00` (PixArt-style OTA) and no `ae00`
(live), and the Bluetooth company id in its bond record is `0x093A` (LinkedSemi Microelectronics). The exact SoC is **unidentified**.
The firmware image contains the string "PixArt BLE Keyboard" (PixArt BLE SDK).

## Transport (live)

| Purpose | UUID |
|---|---|
| Main data service | `0000a00a-0000-1000-8000-00805f9b34fb` |
| Write characteristic | `0000b002-0000-1000-8000-00805f9b34fb` |
| Notify characteristic | `0000b003-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| Stock OTA service | `0000ff00-...`, control/notify on `0000ff01-...`. The vendor app treats this as the PixArt ("pxi") OTA control point. **Only the firmware-update code may write it.** |
| HID-over-GATT | Standard; Android registers the ring as "SY01 Mouse" and "SY01 Consumer Control" (live). |

Discovery: the advertised name contains `SY01` (our app matches case-insensitively, first on bonded devices, then a 10 s scan).
Connection flow the vendor app and our app use: `connectGatt` -> `requestConnectionPriority(HIGH)` -> `discoverServices` ->
enable notify on `b003` and `ff01` -> `requestMtu(185)` -> commands on `b002`. One GATT operation at a time (Android drops overlapping writes).

## Packet framing (live)

Both directions use the same frame:

```
byte 0    0xAB                          preamble
byte 1    flag: 0x01 normal, 0x11 bulk (we send 0x01; the ring answers 0x11)
bytes 2-3 content length, big-endian u16
bytes 4-5 CRC-16/ARC of the content, big-endian u16
bytes 6.. content = [group, key, keyFlag, payload...]
```

CRC-16/ARC: polynomial `0x8005` (reflected `0xA001`), init `0x0000`, no final xor. Check value: `crc16_arc(b"123456789") == 0xBB3D`
(unit-tested in Python and Kotlin).

Content larger than one BLE write is split: only the first fragment carries the 6-byte header, continuation fragments are raw
content bytes. `RingProtocol.FrameAssembler` (Kotlin) and `ring_features.py` (Python) reassemble and check the CRC.

`keyFlag`: `0x00` set/action, `0x10` (16) get/query, `0x30` (48) data reply to a group-5 history query. The ring's frames carry
protocol flag `0x11` (live).

## Stock commands

`(group, key, keyFlag)` triples resolve to a "response id" in the vendor app. The full 162-entry table, extracted statically, is in
`command_table.py` and `testbed/CommandTable.kt`; **all 24 triples seen live match it**. Only the ring-relevant ones are below.

### Seen live

| Content | Meaning |
|---|---|
| `{2,99,16}` | Feature-support bitmap. Live result: supported = blood_oxygen, find_device, health_all_switch, health_monitor, hr, hrv, led_light, power_off, recovery, set_bt_name, sleep, step, stress, take_photo, video_hid, video_hid_book, video_hid_music, wear_dir. **Not supported: msg_notification.** Layout (`parse_support`) is static. |
| `{2,3,16}` | Battery query: reply `[percent, voltage_mV BE16]` (layout static; stock OTA requires >= 30 %). |
| `{2,4,16}` | Firmware version query. Reply `[major, minor, patch, screenType, w LE16, h LE16, model(8 ASCII), ...]` (layout static, query live). Live: v2.1.4, model `30330000`, screen type 0, 48x64 (logical metadata only). |
| `{2,100,0,mode,def}` / `{2,100,16}` | Touch -> phone HID mode: 0 off, 1 short-video ("clicker"), 2 e-book ("pages"), 3 music. Set and query acked; readback matches. |
| `{2,102,0,open,level}` / `{2,102,16}` | Display brightness (level 0..3). Acked, readback matches. |
| `{6,1,0,1/0}` | Remote-shutter ("camera") mode. Acked; each touch then returns a 4-byte `{6,1,0,02}`. |
| `{6,9,0,sensor,5,1/0}` | Start/stop a measurement (3 = heart rate, 9 = SpO2). Acked. No reading was seen on stock firmware (the ring was not on a finger). |
| `{5,26,16}`, `{5,2..13,16}`, `{3,1,0}`, `{3,2,32}` | History sync and bind/unbind (seen in vendor traffic; payload layouts not verified, the ring had no data). |
| `{4,2,0,v}` ring -> phone | Music-mode touch event (v=3 seen). Stock mapping (static): 2 play/pause, 3 next, 4 previous, 5 volume up, 6 volume down. |

Observed stock behaviour (live): touch in "clicker" HID mode plays a fixed scripted mouse swipe, **not** button events; "pages" mode
sends one wheel step; hold plus hand movement produces no motion events. No press-and-hold event exists in the stock protocol.

### Static only (not seen on a ring)

| Content | Meaning |
|---|---|
| `{2,8,0,sec}` / `{2,8,16}` | Screen-on time. Our app reads and sets it. |
| `{2,1,0,yy,mo,dd,hh,mi,ss}` | Set clock, local time, year-2000. Our app sends it on every connect and the firmware's clock page (build 12+) shows it; accuracy after a sync is unverified. |
| `{2,11,0,level,count}` / `{2,11,16}` | Vibration **preference** (not buzz-now). |
| `{2,52,0,1}` | "Find device", closest to buzz-now. Haptic motor pin not identified. |
| `{2,104,0,hand}` | Wear hand, 0 left / 1 right. |
| `{2,112,16}` | Factory self-test state `[gsensor, heart, wear]`. **Warning:** appears to start the ring's factory self-test; developer use only. |
| `{2,103,0,0/1}` | Factory mode. **Do not send.** |
| `{2,120,0,on}` | Counter (tasbih) mode; counts return as `{6,16,0,n}` / `{6,17,0,n}`. |
| `{2,34,0,1}` | Power off. `{2,34,0,2}` is **factory restore, never sent by our code.** |
| `{4,1,0,0x7F,yy..ss,appId32,title32,body<=250}` | Message push. Live: **unsupported on this ring** (no ack, nothing shown). |
| `{6,18,0,1/2}` | App foreground/background notice (earlier notes mislabelled it "arm tap reporting"). |

Payload layouts for step/sleep/heart-rate/SpO2 history blocks are transcribed in `client.py` and are **unverified** against real payloads.

### What the stock firmware cannot do

- No raw accelerometer/gyro stream (only the factory self-test byte). The accelerometer is a 3-axis Silan SC7A20/SC7A20H-family part,
  no gyro (static analysis of the firmware image, see [FIRMWARE_ANALYSIS.md](FIRMWARE_ANALYSIS.md)).
- No hold events, no arbitrary pixel drawing (the display is a GPIO-multiplexed LED matrix, 5 characters plus two side LEDs).

## Custom command group `0x7A` (our firmware only)

Stock firmware never uses group `0x7A` and ignores it. Our firmware (`fw/ring_app/ring_app.c`, builds 9 to 16) handles every `0x7A`
frame itself and never forwards it to the stock command processor. Frames use the normal framing above, `keyFlag = 0`.

### Commands (phone -> ring)

| Content | Meaning | Since |
|---|---|---|
| `7A 02 00 [text<=5]` | Show up to 5 ASCII characters now (wakes the screen). | b2 |
| `7A 03 00 [on]` | Heart mode on/off (`on=0` returns to clock). | b3 |
| `7A 04 00 [on] [odr]` | Motion recorder on/off. `odr` ignored since build 10; the accelerometer runs ~100 Hz while anything owns it. Streams frames `7A 11`. | b5 |
| `7A 05 00 [on]` | Bump-bump-flick detector on/off (on after boot). | b10 |
| `7A 06 00` | Play the gesture message now. | b10 |
| `7A 07 00 [i][5 chars]...` | Upload message frames from index `i` (5 chars each, <= 32 frames, RAM only; the app re-sends on connect). | b11 |
| `7A 08 00 [n][tpf][reps]` | Use frames `0..n-1`, `tpf` x 20 ms each, `reps` plays (1..20). `n=0` = built-in message. | b11 |
| `7A 09 00 [mode]` | Mode: 0 clock, 1 mouse, 2 heart, 3 keys. | b12 (keys: b16) |
| `7A 0A 00 [speed][curve][dz][flags][rc]` | Tilt mouse: speed 0..100 (0 = pure clicker), curve 0..100, deadzone deg (<= 20), flags (1 invert X, 2 invert Y, 4 swap, 8 joystick style), right-click hold in 20 ms ticks (>= 10). | b12 (flag 8: b14) |
| `7A 0B 00` | Mouse: re-capture the neutral hand angle. | b12 |
| `7A 01` | Retired (build 2 mouse settings); swallowed, does nothing. | |

### Events (ring -> phone)

| Frame | Meaning |
|---|---|
| `7A 15 [kind][t lo][t hi]` | Touch. `kind`: 0 tap, 1 press, 2 release (hold), 3 right click, 4 five taps, 5 long hold. Build 16 adds the 20 ms ring tick `t` (u16, free-running, wraps at 65536) so timing is free of BLE jitter. Older builds send the kind byte only. |
| `7A 14 [mode]` | Mode changed (0 clock, 1 mouse, 2 heart, 3 keys). |
| `7A 13 [kind][fired][armed]` | Gesture detector: kind 1 armed, 2 fired. |
| `7A 12 [...]` | Status (every 2 s while recording, and after mode/config changes): `[on, timer, lastRead, hidMode, seq, ctrl1, fifo_src, gestureOff, fired, armed, msgN, msgTpf, msgReps, mode, build]`. |
| `7A 11 [seq][n][x,y,z int16 LE]*n` | Raw accelerometer batch, units raw>>2 (about 2048 per g), ~99 samples/s. |
| `7A 10`, `7A 16` | PPG batch / diagnostics for heart tuning (logged, not a stable interface). |

**Touch semantics (fw-test, build 16):** every physical press sends `press` (kind 1). On release it sends `tap` (kind 0) if the press
lasted less than 300 ms (15 ticks of 20 ms), otherwise `release` (kind 2). So a short press is `press` then `tap`, a long press
is `press` then `release`. In **keys mode** (mode 3) the ring sends only these edges: no HID, no five-tap / long-hold mode switching.
Our app maps kinds to `TouchKind` as `tap -> TAP`, `press -> HOLD_START`, `release -> HOLD_END`, and so on
([API.md](API.md#event-kinds)).

## Stock OTA protocol ("PXI", service `ff00`, characteristic `ff01`)

Reconstructed from the vendor app and the firmware's handlers; the full flow round-trips against a simulator (`pxi_ota.py`, `tests/test_pxi_ota.py`)
and was **live-confirmed** on a ring for the stock image (INIT_NEW params, OBJECT_CREATE, burst acks, UPGRADE, verify read-back, reset).
All integers little-endian. Only the firmware-update code may touch `ff01` (see [FIRMWARE.md](FIRMWARE.md)).

```
app  -> INIT_NEW      [0x27, size u32]
ring -> (read ff01)   [0x0E, ?, 0x27, ?, ?, a u16, b u16, max_obj u32, mtu u16, prn u16, ok]
loop per object (offset = bytes sent so far):
  app  -> OBJECT_CREATE [0x25, offset u32, max_obj u32];   ring -> notify [0x25, ...]
  app  -> up to `prn` raw data chunks (<= mtu, <= object remainder);   ring -> notify [0x17, running_sum16 u16]
app  -> UPGRADE       [0x18, size u32, sum16_of_image u16];   ring -> notify [0x18, ...]
ring -> (read ff01)   verify result [0x0E, 0x02, 0x18, 0x00] = OK, 0xFF = rejected
app  -> MCU_RESET     [0x22, 0x00]       (only after the verify result is OK)
```

The phone never sends a flash address. `SET_ADDRESS`, `ERASE` and `SECTOR_ERASE` opcodes exist in the vendor enum but the stock app never sends them and our client refuses to.
Integrity is a 16-bit additive byte sum, checked on every ack by our client.

## Observing your own traffic

To learn what the vendor app sends to **your own** ring: use Android's Bluetooth HCI snoop log or adb over wireless debugging on **your
own** phone. The vendor app logs each resolved command via `Log.e` even in release builds, in lines like
`analysisJLData <group> <key> <flag> <resolvedId>`. Bluetooth GATT system logs show UUIDs and MTU but not payload bytes. Do not use this
on devices you do not own.

## Still unknown

1. Live payload bytes for step/sleep/HR/SpO2 history (needs a worn ring with data).
2. A one-shot "measure now" command.
3. Meaning of group-5 sub-commands 2, 3, 4, 5, 9, 10, 13, 26 (which metric is which).
4. The exact SoC, and the resident updater's behaviour (see [FIRMWARE.md](FIRMWARE.md)).
