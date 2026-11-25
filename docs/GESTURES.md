# Gestures

There are three separate things called "gestures". Know which one you mean.

| | Where it runs | Needs | Output |
|---|---|---|---|
| **Touch events** (tap, press, release, hold) | On the ring (our firmware) | custom firmware | `touch` events, see [API.md](API.md#event-kinds) |
| **Bump-bump-flick** | On the ring (our firmware) | custom firmware | Plays a message on the ring screen, emits `gesture_armed` / `gesture_fired` |
| **Gesture Studio** (trained gestures) | On the phone | ring motion stream (custom firmware) | `gesture_hit` + a configurable action |

Stock firmware has none of the motion features: it streams no accelerometer data and has no hold event.

## Touch events

The touch pad is a digital GPIO input that our firmware polls every 20 ms. Every press sends `HOLD_START`; the release sends `TAP`
(press < 300 ms) or `HOLD_END`. Mode shortcuts (outside keys mode): five quick taps toggle mouse mode, a 2 s hold toggles heart mode.
In mouse mode: tap = left click, press and move = drag, press and hold still ~0.7 s (adjustable) = right click.
Details and wire format: [PROTOCOL.md](PROTOCOL.md#custom-command-group-0x7a-our-firmware-only).
The firmware logic is tested in `fw/ring_app/test_ring_app.c` (host) and `fw/ring_app/emu/emu_touch.py` (real image under emulation).
**Unverified:** feel and gains of the tilt mouse on a hand; the HID path to a host.

## Bump-bump-flick (on the ring)

Two fist bumps, then a finger flick. The ring plays a short message and the app shows the fired count. It works from the accelerometer only
and uses `|a[i] - a[i-1]|` ("jerk"), so ring rotation on the finger does not matter. It arms when, within 400 ms, at least 15 samples have jerk
above 0.4 g including one above 1.6 g, **and** there was a real arm swing; it fires on a flick (jerk >= 0.75 g) at least 200 ms after the
last bump jolt and within 1 s. Then 1.5 s refractory. A jolt run still going 0.7 s after arming counts as shaking and disarms.

Evidence (model -> C -> real image under Unicorn, `fw/ring_app/testdata/`): 10 of 10 real repetitions fire (one recorded
repetition had no flick); `bumps_only`, `flick_only` and `everyday` recordings fire 0 times; 40 synthetic flick-flick(-flick)
sequences fire 0 times. **Unverified:** real-world false-trigger rate (only ~40 s of everyday motion was recorded), real snaps and
real shaking (no recordings).

The message: `POST /v1/message` or the Display tab. Words mode shows each word (long words split in 5s); scroll mode shows a marquee,
one character per step, up to 32 steps. 60..1500 ms per step, 1..5 plays. The default built-in message is a profane two-word phrase (blank text = built-in);
set your own.

## Gesture Studio (on the phone)

Train any motion with about 5 examples; the phone recognises it from the ring's ~99 Hz accelerometer stream.

- **Features** (`OrientationRobustFeatures`): jerk magnitude, dynamic-acceleration magnitude, and its components parallel/perpendicular to gravity.
  All are invariant to rotating the ring, resampled to 32 points.
- **Segmenter** (`MotionSegmenter`): a burst starts at jerk > 0.5 g (with a 6-sample pre-roll), ends after ~300 ms quiet; bursts shorter than 120 ms
  or with peak jerk < 0.9 g are dropped.
- **Matcher** (`DtwMatcher`, `GestureClassifier`): banded dynamic time warping against each example. Recognition is enabled at >= 3 examples.
  The rejection threshold comes from the spread of your own examples and the **strictness** slider (0..100, default 60).
- **Training:** Gestures tab -> add a name -> Train -> repeat the gesture when prompted ("Got it (3/5) - hold still..."). Examples are
  stored on the phone (`gestures.json` in the app's files dir). **Listening** must be on for recognition; it turns the ring's motion stream on.
- **Measured** (`RealGestureStudioTest`, 5 trained / rest held out): target gesture 5/5, flick 7/7, 0 false hits on the `bumps_only` and `everyday`
  recordings. Small sample, one person: treat as a sanity check, not an accuracy claim. Rotation invariance was checked by rotating a recording by 65 degrees (features differ by <= 0.0011).

Actions per gesture:

| Action | Effect |
|---|---|
| Webhook | HTTP request; URL/body/headers may use `{gesture}`, `{confidence}`, `{time}` |
| Ring text | Show text on the ring |
| Media key | Android media key: play_pause, next, previous, volume_up, volume_down (**unverified on a device**; the Android implementation `AndroidPlatformActions` is untested) |
| PC keys | Publishes `pc_keys` on `/v1/events`; [pc/ring_bridge.py](../pc/README.md) turns it into a key combo on a Linux PC |
| API event only | Publishes `api_event` for your own software |

Every recognised gesture also emits `gesture_hit`.
