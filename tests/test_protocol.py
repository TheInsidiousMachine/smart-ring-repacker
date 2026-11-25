"""Deterministic protocol tests. Run: python3 -m pytest -q tests/"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest  # noqa: E402

import ring_features as rf  # noqa: E402
from client import build_request, crc16_arc, parse_frame  # noqa: E402
from command_table import COMMAND_TABLE, resolve_id  # noqa: E402

# Triples seen live on the user's ring (PROTOCOL.md command table).
LIVE = {
    (2, 1, 0): 0xF5, (2, 2, 0): 0xF4, (2, 3, 16): 0xF3, (2, 4, 16): 0xF2,
    (2, 14, 0): 0xF0, (2, 99, 16): 0x12, (2, 100, 16): 0x14, (2, 102, 16): 0x19,
    (2, 104, 16): 0x1E, (3, 1, 0): 0xFD, (3, 1, 32): 0xFC, (3, 2, 32): 0xFB,
    (5, 2, 16): 0xC4, (5, 3, 16): 0xC2, (5, 3, 48): 0xC1, (5, 4, 16): 0xC0,
    (5, 5, 16): 0xBE, (5, 9, 16): 0xBA, (5, 9, 48): 0xB9, (5, 10, 16): 0xB8,
    (5, 13, 16): 0xB6, (5, 26, 16): 0x97, (5, 26, 48): 0x1C, (6, 9, 0): 0x1F,
}


def test_crc_check_value():
    assert crc16_arc(b"123456789") == 0xBB3D  # CRC-16/ARC catalogue check


def test_table_matches_live_triples():
    for k, v in LIVE.items():
        assert resolve_id(*k) == v, k
    assert len(COMMAND_TABLE) == 162


def test_request_framing():
    pkt = build_request(2, 99, 16)
    assert pkt[:4] == bytes([0xAB, 0x01, 0x00, 0x03])
    assert int.from_bytes(pkt[4:6], "big") == crc16_arc(bytes([2, 99, 16]))
    f = parse_frame(pkt)
    assert (f.group, f.key, f.key_flag) == (2, 99, 16)


def test_builders():
    assert rf.find_device()[6:] == bytes([2, 52, 0, 1])
    assert rf.camera_mode(True)[6:] == bytes([6, 1, 0, 1])
    assert rf.app_foreground(False)[6:] == bytes([6, 18, 0, 2])
    assert rf.query_factory_state()[6:] == bytes([2, 112, 16])
    assert rf.set_video_hid(3, True)[6:] == bytes([2, 100, 0, 3, 1])
    with pytest.raises(ValueError):
        rf.set_video_hid(4, False)


def test_push_message_layout_and_utf8_truncation():
    title = "é" * 20  # 40 bytes UTF-8 -> must cut at 32 without splitting
    pkt = rf.push_message(title, "x" * 400, (26, 11, 18, 1, 2, 3))
    c = pkt[6:]
    assert c[:10] == bytes([4, 1, 0, 0x7F, 26, 11, 18, 1, 2, 3])
    assert c[10:25] == b"com.android.mms"
    t = c[42:74].rstrip(b"\0")
    assert t.decode("utf-8") == "é" * 16
    assert len(c) == 74 + rf.MSG_CONTENT_MAX


def test_assembler_multi_fragment():
    content = bytes([5, 3, 48]) + bytes(range(250))
    pkt = rf.build_packet(content)
    a = rf.FrameAssembler()
    assert a.feed(pkt[:100]) == []
    out = a.feed(pkt[100:])
    assert out == [(0x01, content, True)]


def test_support_bitmap():
    p = bytearray(95)
    p[2] = 1       # take_photo
    p[6] = 1       # video_hid
    p[9] = 1       # find_device
    p[29] = 0b10   # bo_reminder only
    p[17:21] = (0x01020304).to_bytes(4, "little")
    p[80] = 1      # health all switch
    p[82] = 1      # hr
    p[90] = 1      # muslim_count
    s = rf.parse_support(bytes([2, 99, 16]) + bytes(p))
    assert {"take_photo", "video_hid", "find_device", "bo_reminder", "hr",
            "muslim_count", "health_all_switch"} == set(s.supported())
    assert s.push_msg_mask == 0x01020304


def test_support_health_gated_by_master_switch():
    p = bytearray(95)
    p[82] = 1
    s = rf.parse_support(bytes([2, 99, 16]) + bytes(p))
    assert not s.flags["hr"]


def test_events():
    e = rf.parse_event(bytes([6, 1, 0, 1]))
    assert e.kind == "tap_shutter" and e.value == 1
    assert rf.parse_event(bytes([6, 1, 0, 1, 0])).kind == "setting"
    e = rf.parse_event(bytes([6, 16, 0, 7]))
    assert e.kind == "count" and e.value == 7
    assert "mode=3" in rf.parse_event(bytes([2, 100, 16, 3, 1])).detail
    assert rf.parse_event(bytes([9, 9, 9])).kind == "unknown"


def test_factory_state():
    s = rf.parse_factory_state(bytes([2, 112, 16, 1, 0, 1]))
    assert (s.gsensor, s.heart, s.wear) == (1, 0, 1)


def test_tap_classifier():
    c = rf.TapGestureClassifier(window_ms=400)
    assert c.tap(0) is None
    assert c.tap(200) is None
    assert c.poll(500) is None
    assert c.poll(601) == "double"
    assert c.tap(1000) is None
    assert c.tap(2000) == "single"  # previous burst closed
    assert c.tap(2100) is None
    assert c.tap(2200) is None
    assert c.poll(3000) == "triple"
