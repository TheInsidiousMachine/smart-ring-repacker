"""Contract test for the WebSocket event stream (docs/fixtures/ring_events.jsonl).

The fixture is what `WS /v1/events` emits, one JSON object per line. Consumers (e.g. the
gaze-keyboard project) copy it as their contract fixture. This test checks each line's shape and,
when the Android sources are present, that the event names and touch kinds in the fixture are ones
the server can really emit (so the fixture cannot silently drift from RingApiServer.kt).

Run: python3 -m pytest -q tests/test_event_fixture.py
"""
import json
import os
import re

import pytest

ROOT = os.path.join(os.path.dirname(__file__), "..")
FIXTURE = os.path.join(ROOT, "docs", "fixtures", "ring_events.jsonl")
SERVER = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "smartring", "api", "RingApiServer.kt")
CONTROLLER = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "smartring", "core", "RingController.kt")

TOUCH_KINDS = {"TAP", "HOLD_START", "HOLD_END", "RIGHT_CLICK", "FIVE_TAPS", "LONG_HOLD"}
MODES = {"UNKNOWN", "CLOCK", "MOUSE", "HEART", "KEYS"}
INT, STR, NUM = int, str, (int, float)

# event name -> {field: (type, required)}   ("event" and "atMs" are required on every event)
SHAPES = {
    "touch": {"kind": (STR, True), "ringTick": (INT, False)},
    "mode_changed": {"mode": (STR, True)},
    "gesture_armed": {},
    "gesture_fired": {},
    "heart_beat": {"bpm": (INT, True)},
    "info": {"text": (STR, True)},
    "gesture_hit": {"gestureId": (STR, True), "name": (STR, True), "confidence": (NUM, True)},
    "pc_keys": {"gestureName": (STR, True), "keys": (STR, True)},
    "api_event": {"gestureName": (STR, True)},
}


def load():
    with open(FIXTURE, encoding="utf-8") as f:
        lines = [ln for ln in f.read().splitlines() if ln.strip()]
    return [json.loads(ln) for ln in lines]


def test_fixture_size_and_each_line_is_a_json_object():
    events = load()
    assert 8 <= len(events) <= 10
    assert all(isinstance(e, dict) for e in events)


def test_every_event_matches_its_shape():
    for e in load():
        name = e.get("event")
        assert name in SHAPES, f"unknown event {name!r}"
        assert isinstance(e.get("atMs"), int) and e["atMs"] > 0
        spec = SHAPES[name]
        allowed = {"event", "atMs", *spec}
        assert set(e) <= allowed, f"{name}: unexpected fields {set(e) - allowed}"
        for field, (typ, required) in spec.items():
            if field in e:
                assert isinstance(e[field], typ) and not isinstance(e[field], bool), f"{name}.{field}"
            else:
                assert not required, f"{name}: missing {field}"


def test_enum_values():
    for e in load():
        if e["event"] == "touch":
            assert e["kind"] in TOUCH_KINDS
            if "ringTick" in e:
                assert 0 <= e["ringTick"] <= 65535
        if e["event"] == "mode_changed":
            assert e["mode"] in MODES


def test_timestamps_never_go_backwards():
    ts = [e["atMs"] for e in load()]
    assert ts == sorted(ts)


def test_keys_mode_button_contract():
    """What a button consumer needs: KEYS mode, HOLD_START on press, then TAP or HOLD_END on release."""
    events = load()
    assert any(e["event"] == "mode_changed" and e["mode"] == "KEYS" for e in events)
    touches = [e for e in events if e["event"] == "touch"]
    kinds = [t["kind"] for t in touches]
    assert {"HOLD_START", "TAP", "HOLD_END"} <= set(kinds)
    # every release (TAP/HOLD_END) is preceded by an unmatched HOLD_START, except events without
    # a ringTick, which model older firmware / rings that only report taps
    down = False
    for t in touches:
        if t["kind"] == "HOLD_START":
            assert not down, "two presses without a release"
            down = True
        elif t["kind"] in ("TAP", "HOLD_END") and "ringTick" in t:
            assert down, "release without a press"
            down = False


def test_ring_tick_agrees_with_wall_clock():
    """The ring tick is 20 ms; consecutive stamped edges must agree with atMs within BLE jitter."""
    prev = None
    for e in load():
        if e["event"] == "touch" and "ringTick" in e:
            if prev:
                d_ticks = (e["ringTick"] - prev["ringTick"]) & 0xFFFF
                d_ms = e["atMs"] - prev["atMs"]
                assert abs(d_ticks * 20 - d_ms) <= 250, (prev, e)
            prev = e


@pytest.mark.skipif(not (os.path.exists(SERVER) and os.path.exists(CONTROLLER)), reason="Android sources not present")
def test_names_exist_in_the_server_source():
    server = open(SERVER, encoding="utf-8").read()
    emitted = set(re.findall(r'\{"event":"(\w+)"', server)) - {"motion"}   # "motion" is on /v1/motion only
    assert set(SHAPES) == emitted, f"SHAPES out of date vs RingApiServer.kt: {set(SHAPES) ^ emitted}"
    controller = open(CONTROLLER, encoding="utf-8").read()
    kinds = re.search(r"enum class TouchKind \{([^}]*)\}", controller).group(1)
    assert set(re.findall(r"[A-Z_]+", kinds)) == TOUCH_KINDS
    modes = re.search(r"enum class RingMode \{([^}]*)\}", controller).group(1)
    assert set(re.findall(r"[A-Z_]+", modes)) == MODES
    for e in load():
        assert e["event"] in emitted
