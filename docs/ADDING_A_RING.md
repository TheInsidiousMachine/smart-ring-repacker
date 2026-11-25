# Adding support for another ring

Everything above the BLE layer (Compose UI, Gesture Studio, the HTTP/WebSocket API, the PC bridge, and external projects such as the
[gaze keyboard](https://github.com/TheInsidiousMachine/gaze-keyboard)) talks to the ring through **one interface**:

```
android/app/src/main/java/com/smartring/core/RingController.kt     interface RingController, RingEvent, TouchKind, RingMode, RingCapability
```

To support a new ring you write a class that implements `RingController`. You do not touch the UI or the API.
A compiling, tested template is in `core/example/` (`ExampleRingController`, `RingTransport`, `FakeTransport`, and
`ExampleRingControllerTest`). The SY01 implementation to compare against is `testbed/RealRingController.kt` (adapter) over `testbed/RingLink.kt`
(BLE) and `testbed/RingProtocol.kt` (framing and parsing).

## 1. What each member means

"Required" = the app misbehaves without a real implementation. Everything else may be a **no-op**: the convention is
*unsupported = do nothing harmless (log a line), or return an error string where the signature allows one, and leave it out of `capabilities`*.

| Member | Meaning | Required? |
|---|---|---|
| `capabilities: Set<RingCapability>` | Which of the features below are real. Default is "everything" (SY01 + our firmware), so override it. Advisory: the UI does not hide controls from it yet (see section 3). | override it |
| `connection: StateFlow<Connection>` | `DISCONNECTED / SCANNING / CONNECTING / CONNECTED`, device name, detail text. | **yes** |
| `connect()` / `disconnect()` | `connect()` means "stay connected": after a drop, retry until `disconnect()`. Re-send saved settings on each connect. | **yes** |
| `events: SharedFlow<RingEvent>` | `Touch(kind, atMs, ringTick?)`, `ModeChanged`, `GestureArmed/Fired`, `HeartBeat`, `Info(text)`. | **yes, for `Touch`** |
| `status: StateFlow<RingStatus>` | Battery %, charging, firmware version, mode, clock-sync time, heart BPM... `null` = unknown. | yes (fill what you know) |
| `log: StateFlow<List<LogLine>>` | Last ~2000 human-readable lines for the dev console. | yes (may stay empty) |
| `syncClock()` | Set the ring clock to the phone's local time. | optional |
| `findRing()` | Make the ring flash/buzz. | optional |
| `showText(text)` | Show text on a ring display. | optional (display) |
| `gestureMessage` / `setGestureMessage` / `playGestureMessage` / `setGestureDetector` | On-ring bump-bump-flick message. SY01-firmware-specific. | no-op |
| `setBrightness` / `setScreenTimeout` | Display settings. | optional |
| `setMode(RingMode)` | `CLOCK / MOUSE / HEART / KEYS`. Our firmware only. | no-op |
| `mouseSettings` / `setMouseSettings` | Tilt-mouse tuning; store it, send if the ring can do it. | no-op |
| `motion: SharedFlow<MotionBatch>` / `setMotionStreaming(on)` | Raw accelerometer batches (`samples` of `[x,y,z]`, ~2048 per g). Needed by Gesture Studio. | optional (motion) |
| `sendRaw(hex)` | Developer console: send hex bytes; return an error string or `null`. | optional |
| `recording` / `startRecording` / `stopRecording` | Save motion samples to CSV. | return an error string |
| `firmwareImages()` / `ota` / `startFirmwareUpdate(image, confirm)` | Firmware update with the typed-hash confirmation. **Do not implement for a new ring** unless you can meet every rule in [FIRMWARE.md](FIRMWARE.md). Return `emptyList()` and an error string. | no |
| `powerOff()` | Power the ring off. | optional |

Threading: members are callable from the main thread; do your own threading. Emit events with `tryEmit` on a `MutableSharedFlow(extraBufferCapacity = ...)`.

### The touch contract (what the gaze keyboard needs)

An external keyboard app uses the ring as a **button**. For that, `events` must carry, for every physical press:

1. `Touch(HOLD_START)` when the finger goes down, then
2. `Touch(TAP)` if released quickly (our firmware: < 300 ms) **or** `Touch(HOLD_END)` after a longer hold.

`ringTick` (the ring's own 20 ms counter) is optional but gives much better timing than BLE arrival time. If your ring only reports a single
discrete "tap" with no down/up edges, emit `TAP` only: it works for tap-to-select, **not** for hold/release (glide) input.
See [API.md](API.md#event-kinds) and `docs/fixtures/ring_events.jsonl`.

## 2. Steps

1. **Collect protocol facts first** and label each *verified on hardware* or *from a write-up*. Use the labels in [PROTOCOL.md](PROTOCOL.md). A packet capture of the vendor app on your own phone (Android "Bluetooth HCI snoop log", or nRF Connect) is the fastest way.
2. **Discovery.** Decide how to find the ring: advertised name prefix/regex, advertised service UUID, or bonded-device lookup. SY01: name contains `SY01`, bonded devices first, then a 10 s LE scan (`RingLink.connect`). Prefer a scan filter on a service UUID when the ring advertises it.
3. **GATT.** Find the service, the write characteristic, the notify characteristic; enable notifications (CCCD); negotiate MTU if packets exceed 20 bytes; keep **one GATT operation in flight** (Android silently drops overlapping writes; see the queue in `RingLink`).
4. **Framing and checksum.** Write a pure-Kotlin `build(cmd, payload)` and a reassembling parser (`FrameAssembler`-style) that verifies the checksum. Unit-test them with known byte vectors, with no Android classes.
5. **Map notifications to `RingEvent`s and `RingStatus`.** Unknown frames become `Info("unknown ...")`, never a crash.
6. **Battery / status** on connect and periodically. **Clock sync** on connect if the ring has a clock.
7. **Optional features:** display (`showText`), motion (`motion` + `setMotionStreaming`). Leave the rest as documented no-ops and trim `capabilities`.
8. **Tests with a fake transport.** Put the radio behind a small interface (`RingTransport` in the template), drive the controller only through a fake in unit tests (`ExampleRingControllerTest`). No Bluetooth in tests.
9. **Wire it in.** `testbed/RingApplication.kt` sets `RingServices.ring`. There is no ring picker yet; to run your driver, construct it there instead of `RealRingController`. (A picker is a welcome contribution.)
10. **Document it:** add a "Supported hardware" row in the README, protocol notes in `docs/PROTOCOL.md` (or a new `docs/PROTOCOL_<ring>.md`) with confidence labels, and anything unverified marked so.

Run the gate, `tools/check_all.sh` (see [TESTING.md](TESTING.md)).

## 3. What the UI assumes

The Compose UI is SY01-shaped and does **not** consult `capabilities` yet. On a ring without a feature the control is shown and does nothing.

| Screen | Reads / calls |
|---|---|
| Connection pill (all tabs) | `connection`, `connect()`, `disconnect()` |
| Ring | `status` (battery, charging, firmware, customBuild, clockSyncedAtMs, mode, brightness, timeout), `setMode`, `setBrightness`, `setScreenTimeout`, `syncClock`, `findRing`; dev mode adds `firmwareImages/startFirmwareUpdate/ota`, `powerOff`, `sendRaw`, `log` |
| Display | `gestureMessage`, `setGestureMessage`, `playGestureMessage`, `showText`, `setGestureDetector`, `status.gestureFired/Armed` |
| Gestures | `GestureStudio` (needs `motion` + `setMotionStreaming` to do anything), `events` for the activity feed, API card |
| Mouse | `mouseSettings`, `setMouseSettings`, `setMode(MOUSE)` |
| Health | `setMode(HEART)`, `status.heartBpm`, `HeartBeat` events |

Making the UI honour `capabilities` (hide or disable unsupported tabs/controls) is the obvious next contribution. It needs UI tests, which is why it
was not bundled into the seam change.

## 4. Worked outline: Colmi R02-R09 family

> **UNVERIFIED on our hardware. We have no Colmi ring and have not run any of this.** Everything below comes from public write-ups and
> open-source projects, read in November 2026, and is labelled with how well it is sourced. "R08" appears in none of the sources we found;
> the documented names are R02, R03, R05, R06, R07, R09, R10, R11, R12 (and OEM clones). Not implemented in this repo.

Public prior art (cite, do not copy: only the first is MIT; Gadgetbridge is AGPL-3.0, atc1441's repo is GPL-3.0; check each licence before reusing code):

- `colmi_r02_client` (Python, MIT, by tahnok): https://github.com/tahnok/colmi_r02_client ("TAH" below)
- Colmi protocol docs by Puxtril: https://colmi.puxtril.com/ , source https://github.com/Puxtril/colmi-docs ("PUX")
- Gadgetbridge Yawell/Colmi ring support: https://codeberg.org/Freeyourgadget/Gadgetbridge (`devices/yawell/ring/`, `service/devices/yawell/ring/YawellRingDeviceSupport.java`) ("GB")
- atc1441's ring tooling and web writer: https://atc1441.github.io/ATC_RF03_Writer.html ("ATC")
- Gadgetbridge PR discussion with a command list: https://codeberg.org/Freeyourgadget/Gadgetbridge/pulls/3896

### Discovery
- Advertised names look like `R02_341C` (model prefix + last 4 hex of the MAC). TAH scans without a service filter and matches name prefixes (R01-R07, R09, R10, COLMI, and several OEM names). GB matches by regex such as `R02_.*`, `R09_.*`, `^COLMI R10_.*`. *(confirmed in code, 2 sources)*
- Rings are paired with a phone app (QRing); a ring bound to another phone may not be connectable. *(not verified)*

### GATT (confirmed in code, 3 sources agree)
| | UUID |
|---|---|
| Service ("Nordic UART-like") | `6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E` |
| Write (to ring) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` (TAH writes without response) |
| Notify (from ring) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |
| Newer "big data" service | `de5bf728-d711-4e47-af26-65e3012a5dc7`, write `de5bf72a-...`, notify `de5bf729-...` *(confirmed in GB code and PUX)* |
| Device Info | standard `0x180A` (firmware `0x2A26`, hardware `0x2A27`) *(TAH)* |

### Packets (confirmed in code: TAH `packet.py`, GB `buildPacket`, PUX)
16 bytes in both directions: `byte0 = command id`, `bytes 1..14 = payload (zero padded)`, `byte15 = sum(bytes 0..14) & 0xFF`
(TAH's README says "modulo 255", but its code is `& 255`, i.e. mod 256). Bit 7 of byte 0 set in a reply means error (PUX). Some replies span several packets (HR log, steps).

```kotlin
fun colmiPacket(cmd: Int, vararg payload: Int): ByteArray {
    require(payload.size <= 14)
    val p = ByteArray(16); p[0] = cmd.toByte()
    payload.forEachIndexed { i, v -> p[i + 1] = v.toByte() }
    p[15] = (0 until 15).sumOf { p[it].toInt() and 0xFF }.toByte()   // sum of first 15 bytes, mod 256
    return p
}
```

### Commands (request -> response; all confirmed in code unless noted)
| Id | Purpose | Request payload | Notes / response |
|---|---|---|---|
| `0x01` | Set time | BCD `yy mm dd hh mm ss`, then language byte (1 = English) | TAH sends UTC; GB omits the language byte. Response is a capability bitfield (TAH `set_time.py`). Maps to `syncClock()`. |
| `0x03` | Battery | none | byte1 = percent, byte2 = charging flag. Maps to `status.batteryPercent/charging`. |
| `0x08` | Reboot | `01` | GB sends the same bytes and calls it "power off"; behaviour not verified. |
| `0x50` | Find device | `55 AA` | LED lights ~10 s (PUX, GB). Maps to `findRing()`. |
| `0x10` | Blink twice | none | TAH and ATC say `0x10`; PUX says `0x0F`. Conflict: medium confidence. |
| `0x69` / `0x6A` | Start-continue / stop a measurement | `69 [type, action]` (action 1 start, 2 pause, 3 continue, 4 stop); `6A [type,0,0]` | Types: 1 HR, 2 BP, 3 SpO2, 4 fatigue, 5 health check, 7 ECG, 8 pressure, 9 blood sugar, 10 HRV. Reply `69 [type, err, value]`. TAH sends `69 03 03` to continue; GB re-sends "continue" about every 30 packets. Could feed `HeartBeat`. |
| `0x1E` | Realtime HR | `1E 01` enable, `02` disable, `03` continue | HR in reply byte 1 (GB, PUX). |
| `0x15` | HR log | `15` + u32 LE local-midnight timestamp | Sub-type in byte 1: 0 header (count, interval 5 min), 1 first data (timestamp + 9 values), 2.. 13 values each, 0xFF = none. 288 slots/day. Ignore future slots (stale bytes). |
| `0x16` | HR log settings | read `16 01`; write `16 02 <1 on/2 off> <minutes>` | |
| `0x43` | Steps | `43 [dayOffset, 0x0F, 0x00, 0x5F, 0x01]` | Layout in TAH `steps.py`. |
| `0x73` | Unsolicited notification (ring -> phone) | n/a | type in byte 1: 1 new HR data, 3 SpO2, 4 steps, `0x0C` battery (`[2]` = %, `[3]` = charging), `0x12` live activity. |
| `0xFF` | Factory reset | `FF 66 66` (GB) | **Do not implement.** |

Big-data service framing *(GB code; PUX prose)*: `0xBC, type, len u16 LE, crc16 u16 LE, payload` (GB uses CRC16-Modbus over the payload); responses may arrive
in several notifications. Types: `0x27` sleep, `0x2A` SpO2, `0x25` temperature (R09/R10-class), `0x2C` alarms. PUX and GB disagree on the exact request bytes;
GB's (`BC 27 01 00 FF 00 FF` for sleep) are known to work in a shipped app. TAH does not implement this service.

### Touch: the honest part
- **There is no capacitive touch pad and no hold/press/release event** in any source we found. R02-class rings have no physical button.
- What does exist: when the ring's camera/"shoot" mode is on, a **thumb-to-finger double tap** makes the ring send a notification. Evidence: the Gadgetbridge maintainer (PR 3896 thread, Jan 2025: "if you tap the ring twice, it will send a bluetooth packet"), TAH issue #16, and ATC's help text (`02 04` enable camera feedback, `02 06` disable). GB handles any packet starting with `0x02` as a "button" event (`handleButtonEvent`).
  PUX documents the camera command as: request actions 4 = enter camera UI, 5 = keep screen on, 6 = finish; response actions 1 = in UI, 2 = take photo, 3 = finish.
  **The exact bytes of the tap notification are not documented anywhere we found**; `02 02 ...` is inferred, not confirmed.
- A raw accelerometer stream may exist: `0xA1`, start `A1 04`, stop `A1 02`; reply `A1 <kind>` with kind 3 = accelerometer (12-bit signed values, +/-4 g, decoding in ATC). It is in an **open, unmerged TAH pull request** (#45) and ATC's page, not in TAH's released client. Stock-firmware sample rate is **not established** (reports range from about 1 Hz to hundreds of Hz on custom firmware). Treat it as research.

**Consequences for the gaze keyboard:** a Colmi ring **cannot be the hold/release button**, because there are no press and release edges. At best it could emit
discrete `TAP` events from the camera-mode double tap (high latency, two taps per event, not usable for glide typing), or a phone-side
motion gesture over the experimental `0xA1` stream. If you implement it, say so in `capabilities` (no `TOUCH_EVENTS` edges) and in the README table. The sibling project must decide whether tap-only input is acceptable.

### Outline of `ColmiRingController`
1. `ColmiTransport : RingTransport` over BluetoothGatt for service `6E40FFF0-...`.
2. `ColmiFrames`: `colmiPacket()` above plus a 16-byte parser that rejects bad sums.
3. `onConnected`: send `03` (battery) and `01` (time); on `0x73 0x0C` update battery; on `0x69` reply with `type = 1` emit `HeartBeat` and `status.heartBpm`.
4. `findRing()` -> `50 55 AA`; `syncClock()` -> `01 ...`; everything else no-op; `capabilities = {BATTERY, CLOCK_SYNC, FIND_RING, HEART_RATE, RAW_COMMANDS}`.
5. Tests: feed canned 16-byte vectors through a `FakeTransport`; add vectors only from hardware captures when someone with a ring has them.

## 5. Checklist before opening a PR

- [ ] `RingController` implemented; unsupported members are no-ops and `capabilities` is accurate
- [ ] Parser and framing covered by unit tests with fixed byte vectors
- [ ] Controller driven only through a fake transport in tests (no Bluetooth)
- [ ] README hardware table row and protocol notes, every claim labelled verified / unverified
- [ ] No vendor binaries, firmware images, captures with your device address, or tokens
