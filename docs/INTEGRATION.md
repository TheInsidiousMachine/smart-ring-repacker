# Integration: smart-ring-repacker + gaze-keyboard

This file is the **contract** between two repos. The same file lives in both. The source of truth is
[smart-ring-repacker/docs/INTEGRATION.md](https://github.com/TheInsidiousMachine/smart-ring-repacker/blob/main/docs/INTEGRATION.md);
if the copies differ, that one wins.

| Repo | Role |
|---|---|
| [smart-ring-repacker](https://github.com/TheInsidiousMachine/smart-ring-repacker) | Android app + firmware for the SY01 ring. Publishes ring touch events over a local API. |
| [gaze-keyboard](https://github.com/TheInsidiousMachine/gaze-keyboard) | Glide keyboard for Everysight Maverick glasses. Eyes pick letters, the ring is the button. |

```
 SY01 ring --BLE--> smart-ring-repacker app (phone) --WS /v1/events--> gaze-keyboard (web page / relay)
   touch edges        RingController -> RingApiServer                   HOLD_START = button down
                                                                        TAP / HOLD_END = button up
```

Any ring or app that emits the events below can replace the ring side. Any consumer can use the events;
the keyboard is just the first one. See [ADDING_A_RING.md](ADDING_A_RING.md) for new hardware.

## The contract

1. **Transport**: WebSocket `ws://<phone-ip>:<port>/v1/events` (default port `8765`).
2. **Auth**: the API token from the app's Gestures tab, as `?token=<token>` or `Authorization: Bearer <token>`.
   Browsers cannot set headers on a WebSocket, so the keyboard page uses the query form.
3. **Messages**: one JSON object per text frame. Ignore anything you do not understand.
4. **Button events** (`"event":"touch"`):

   | `kind` | Meaning | Keyboard action |
   |---|---|---|
   | `HOLD_START` | Finger touched the ring. Sent on **every** press. | button **down** (start a glide) |
   | `TAP` | Released after < 300 ms. | button **up** (commit as a tap) |
   | `HOLD_END` | Released after >= 300 ms. | button **up** (commit the glide) |

   Optional field `ringTick` (20 ms ring clock, wraps at 65536, firmware build 16+) is free of BLE jitter; use it for timing if present.
   Other kinds (`RIGHT_CLICK`, `FIVE_TAPS`, `LONG_HOLD`) and other events (`mode_changed`, `info`, `gesture_*`, `pc_keys`, `heart_beat`) must be ignored by the keyboard.
5. **Fixture**: [`docs/fixtures/ring_events.jsonl`](fixtures/ring_events.jsonl) is a real-shaped sample stream. Both repos test against an identical copy.
   Full schema: [API.md](API.md).

## Setup, end to end

1. **Ring**: flash firmware build 16 or newer (see [FIRMWARE.md](FIRMWARE.md); it can brick the ring, read the warnings first).
   Without custom firmware the ring sends no touch events and the keyboard has no button.
2. **Phone**: install the app, pair the ring, open **Gestures -> API**, switch it on, choose **LAN** (so the PC/glasses host can reach it), copy the token.
3. **Ring mode**: set mode `KEYS` (app, or `POST /v1/mode {"mode":"KEYS"}`). KEYS mode sends touch edges only, holds a fast BLE link, and disables HID and shortcuts.
4. **Keyboard**: open the keyboard page ([README quickstart](https://github.com/TheInsidiousMachine/gaze-keyboard#readme)), enter `ws://<phone-ip>:8765/v1/events` and the token, press connect. State shows `connected`.
5. **Check**: press and hold the ring pad. The page should start a glide on press and commit on release.
   Debug without the keyboard: `websocat "ws://<phone-ip>:8765/v1/events?token=<token>"`.

## Known limits (honest)

- **API is off after an app restart** and a **new token is generated** when you enable it again. Re-enter the token in the keyboard page.
- **Latency**: outside KEYS mode the stock BLE link interval is about 187.5 ms, too slow for glide timing. KEYS mode exists to fix that. Real-world latency through phone -> WebSocket is **not measured yet**.
- **Not verified**: the full path ring -> app -> keyboard -> glasses with real eyes. The pieces are tested separately (firmware emulator, app unit tests, keyboard engine tests, fixture parsing on both sides).
- Rings that report no touch-hold (for example the Colmi R02-R09 family) cannot drive a glide on their own. See ADDING_A_RING.md for options.

## For AI agents

- Read this file, then the other repo's `AGENTS.md`. If the sibling is not checked out, run `scripts/fetch-sibling.sh`.
- Do not change event names or `kind` values in one repo without changing the fixture and tests in both.
- Ring side tests: `tests/test_event_fixture.py`. Keyboard side tests: `tools/ring_events_test.mjs` (run by `tools/check.sh`).
