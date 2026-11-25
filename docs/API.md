# Local HTTP + WebSocket API (`/v1`)

The Android app can run a small local server so other software (a PC bridge, scripts, home automation, smart glasses,
the [gaze keyboard](https://github.com/TheInsidiousMachine/gaze-keyboard)) can read ring events and control the ring.
Source: `android/app/src/main/java/com/smartring/api/RingApiServer.kt`. Tests: `RingApiServerTest.kt`.
Event shapes are pinned by `docs/fixtures/ring_events.jsonl` and `tests/test_event_fixture.py`.

Status: unit-tested against a fake ring. Used end-to-end over real BLE: **unverified** (the app has not been exercised against the
API from another machine in a recorded session).

## Enable it

App -> **Gestures** tab -> API card: switch it on, pick a port (default `8765`), and choose "LAN" to listen on all interfaces
(`0.0.0.0`) or off for loopback only (`127.0.0.1`). The card shows the URLs and the bearer token. Plain HTTP and WebSocket, no TLS.

Known limitation: **API settings are not saved.** After the app process restarts the API is off and the next enable generates
a new token. Read the token from the card every time.

Security notes are in [SECURITY.md](../SECURITY.md): anyone on your network with the token can control the ring.

## Authentication

Every endpoint needs the token:

- HTTP: `Authorization: Bearer <token>`
- WebSocket: the same header, or `?token=<token>` in the URL (handy for browsers, but URLs end up in logs)

A bare `Authorization: <token>` header is also accepted. HTTP failures return `401` with `{"error":"unauthorized"}`. A WebSocket with a bad token
is closed immediately with policy-violation. The token is compared in constant time.

## REST endpoints

| Method + path | Body | Response |
|---|---|---|
| `GET /v1/status` | | connection + ring status (below) |
| `POST /v1/display` | `{"text":"HELLO"}` | `{"ok":true,"text":"HELLO"}`. Shows text now: up to 5 characters static, longer scrolls. Invalid JSON is treated as empty text (still `ok`). |
| `GET /v1/message` | | `{"text":"..","scroll":true,"msPerStep":160,"plays":1}` |
| `POST /v1/message` | any of `text`, `scroll`, `msPerStep`, `plays` | `{"ok":true}`. Unspecified fields keep their value. The API does not range-check; the app UI offers 60..1500 ms and 1..5 plays, and the protocol clamps to 20 ms steps and 1..20 plays. `400` + `{"ok":false,"error":..}` on bad JSON. The values are saved on the phone and re-sent on every connect. |
| `POST /v1/mode` | `{"mode":"CLOCK"}` (any of `CLOCK`, `MOUSE`, `HEART`, `KEYS`; case-insensitive) | `{"ok":true,"mode":"CLOCK"}`. `400` on an unknown name. `UNKNOWN` is accepted and sends the clock mode. |
| `POST /v1/clock/sync` | | `{"ok":true}`. Sets the ring clock to the phone's local time. |
| `POST /v1/find` | | `{"ok":true}`. Sends the stock "find device" command. |
| `GET /v1/gestures` | | JSON array of Gesture Studio gestures (below). |

`ok:true` means the command was handed to the ring controller, not that the ring acknowledged it. `mode` and `message` need our custom
firmware; on stock firmware the ring ignores them.

### `GET /v1/status`

```json
{
  "connection": {"state": "CONNECTED", "deviceName": "SY01", "detail": "Connected: SY01"},
  "status": {
    "batteryPercent": 76, "charging": null, "firmwareVersion": "2.1.4", "customBuild": "build 16",
    "mode": "CLOCK", "clockSyncedAtMs": 1795000000000, "gestureDetectorOn": true,
    "gestureFired": 0, "gestureArmed": 0, "heartBpm": null,
    "screenBrightness": 2, "screenTimeoutSec": 5
  },
  "listening": false
}
```

`connection.state` is one of `DISCONNECTED`, `SCANNING`, `CONNECTING`, `CONNECTED`. `mode` is `UNKNOWN`, `CLOCK`, `MOUSE`, `HEART`, `KEYS`.
Fields are `null` until the ring has reported them. `charging` is currently never reported by the SY01 implementation (always `null`).
`listening` is whether Gesture Studio recognition is on. `customBuild` is `null` on stock firmware.

### `GET /v1/gestures`

The action is a kotlinx.serialization polymorphic object: `type` is the fully-qualified class name.

```json
[{"id":"g1","name":"Swipe right","examples":5,"enabled":true,"strictness":60,
  "action":{"type":"com.smartring.core.GestureAction.PcKeys","keys":"alt+tab"}}]
```

Action types: `GestureAction.None`, `.Webhook` (`url`, `method`, `body`, `headers`), `.RingText` (`text`), `.PcKeys` (`keys`),
`.MediaKey` (`key`), `.ApiEventOnly`.

## WebSocket `/v1/events`

Server-push only (client messages are ignored). Each message is one JSON object in a text frame. No replay: you only see events
that happen while you are connected. Several clients can connect at once. Every event has `event` (string) and `atMs`
(phone wall-clock, epoch milliseconds, `System.currentTimeMillis()` when the app processed it).

| `event` | Extra fields | When |
|---|---|---|
| `touch` | `kind` (see below), optional `ringTick` | A touch-pad edge or gesture on the ring. |
| `mode_changed` | `mode` | The ring reports a mode (also sent when the app sets it). |
| `gesture_armed` | | On-ring bump-bump-flick detector armed. |
| `gesture_fired` | | On-ring bump-bump-flick detector fired. |
| `heart_beat` | `bpm` (int) | Heart mode: sent when the BPM value changes. |
| `info` | `text` | Free-text status/diagnostic line (battery, firmware, recorder status, raw `Ring touch ...` lines). Do not parse; ignore unknown `info`. |
| `gesture_hit` | `gestureId`, `name`, `confidence` (0..1) | A phone-side trained gesture was recognised. |
| `pc_keys` | `gestureName`, `keys` | A gesture with a `PcKeys` action fired (for `pc/ring_bridge.py`). |
| `api_event` | `gestureName` | A gesture with an `ApiEventOnly` action fired. |

Unknown events and fields must be ignored by clients; new ones may be added.

### Event kinds

`touch.kind` is the Kotlin `TouchKind` enum name, exactly: `TAP`, `HOLD_START`, `HOLD_END`, `RIGHT_CLICK`, `FIVE_TAPS`, `LONG_HOLD`.
The firmware's own wire names are `tap`, `press`, `release`, `right_click`, `five_taps`, `long_hold`, and appear only in `info` text
(`Ring touch press t=1200`). **The WebSocket never emits `press` or `release`.**

| `kind` | Firmware name | Meaning |
|---|---|---|
| `HOLD_START` | press | Finger down. Sent for **every** press. |
| `TAP` | tap | Finger up after a press shorter than 300 ms. Replaces `HOLD_END` for short presses. |
| `HOLD_END` | release | Finger up after a press of 300 ms or longer. |
| `RIGHT_CLICK` | right_click | Mouse mode: held still ~0.7 s. |
| `FIVE_TAPS` | five_taps | Five quick taps (toggles mouse mode). |
| `LONG_HOLD` | long_hold | Held ~2 s outside mouse/keys mode (toggles heart mode). |

So a button consumer sees `HOLD_START` then either `TAP` or `HOLD_END` for every press. `ringTick` (build 16+) is the ring's own 20 ms counter
at the edge, wrapping at 65536; use differences between edges for press duration free of BLE jitter. It is absent for older
firmware and other rings. In `KEYS` mode only press/release edges are sent (no mode shortcuts).

Example stream: [`docs/fixtures/ring_events.jsonl`](fixtures/ring_events.jsonl).

## WebSocket `/v1/motion`

Raw accelerometer batches. Data flows only while the ring is streaming motion, which the app turns on when Gesture Studio
is *Listening* or training (or a recording runs). Connecting here does not start the stream.

```json
{"event":"motion","seq":142,"samples":[[-273,1912,-663],[-256,1916,-686]],"receivedAtMs":1795000000000}
```

`seq` wraps at 256. Samples are `[x, y, z]` raw>>2 units, about 2048 per g, about 99 samples/s, axes as the chip reports them.

## Examples

```bash
TOKEN=...; HOST=<phone-ip>:8765
curl -H "Authorization: Bearer $TOKEN" http://$HOST/v1/status
curl -X POST -H "Authorization: Bearer $TOKEN" -d '{"text":"HI"}' http://$HOST/v1/display
curl -X POST -H "Authorization: Bearer $TOKEN" -d '{"mode":"KEYS"}' http://$HOST/v1/mode
websocat "ws://$HOST/v1/events?token=$TOKEN"
```

```python
# pip install websockets
import asyncio, json, websockets

async def main(host, token):
    async with websockets.connect(f"ws://{host}/v1/events", additional_headers={"Authorization": f"Bearer {token}"}) as ws:
        async for msg in ws:
            e = json.loads(msg)
            if e["event"] == "touch":
                print(e["kind"], e.get("ringTick"))

asyncio.run(main("<phone-ip>:8765", "<token>"))
```
(`additional_headers` is the `websockets` >= 14 spelling; older versions use `extra_headers`.)

## Adding endpoints

Keep `docs/API.md`, `RingApiServer.kt`, `RingApiServerTest.kt` and the fixture in sync; the fixture test fails if an event name in
the server source is unknown to it.
