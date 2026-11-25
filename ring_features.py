"""
Ring feature layer for the user's own SY01 ring: request builders, payload
parsers, multi-fragment frame reassembly, and a tap-gesture classifier.

Provenance tags used below:
  [live]    observed on the user's ring (see docs/PROTOCOL.md)
  [static]  transcribed from the stock RWFit app (jadx), NOT yet seen on the ring
Nothing tagged [static] should be treated as confirmed until a live capture
matches it.
"""

from __future__ import annotations

import dataclasses
import struct

from client import PREAMBLE, build_packet, build_request
from command_table import resolve_id

# ---------------------------------------------------------------------------
# Request builders (content = [group, subcmd, flag, ...args])
# ---------------------------------------------------------------------------

def query_device_support() -> bytes:
    """[live] {2,99,16} -> feature-support bitmap (resolved id 0x12)."""
    return build_request(2, 99, 0x10)


def query_factory_state() -> bytes:
    """[static] {2,112,16} -> [gsensorState, heartState, wearState] (id 0x31).
    Read-only query. The only accelerometer-related signal in the stock
    protocol is this self-test status byte -- there is no raw IMU stream."""
    return build_request(2, 112, 0x10)


def query_video_hid() -> bytes:
    """[live query seen] {2,100,16} -> [hidMode, defaultOpen]."""
    return build_request(2, 100, 0x10)


def set_video_hid(mode: int, default_open: bool) -> bytes:
    """[static] {2,100,0,mode,defaultOpen}. mode: 0 off, 1 short-video,
    2 e-book, 3 music (UI strings in TRingDeviceFragment.K0)."""
    if mode not in (0, 1, 2, 3):
        raise ValueError("mode must be 0..3")
    return build_request(2, 100, 0, bytes([mode, 1 if default_open else 0]))


def query_led() -> bytes:
    """[live query seen] {2,102,16} -> [open, lcdLevel] ("analysisRingLedLevel")."""
    return build_request(2, 102, 0x10)


def set_led(open_: bool, level: int) -> bytes:
    """[static] {2,102,0,open,lcdLevel} (BrightScreenLedBean)."""
    return build_request(2, 102, 0, bytes([1 if open_ else 0, level & 0xFF]))


def query_bright_time() -> bytes:
    """[static] {2,8,16} -> screen-on seconds."""
    return build_request(2, 8, 0x10)


def set_bright_time(seconds: int) -> bytes:
    """[static] {2,8,0,seconds} ("getBrightTimeWCmdJL")."""
    return build_request(2, 8, 0, bytes([seconds & 0xFF]))


def query_wear_hand() -> bytes:
    """[live query seen] {2,104,16} -> [0 left | 1 right]."""
    return build_request(2, 104, 0x10)


def set_wear_hand(right: bool) -> bytes:
    """[static] {2,104,0,right}."""
    return build_request(2, 104, 0, bytes([1 if right else 0]))


def query_vibration() -> bytes:
    """[static] {2,11,16} -> [level, count] (BrightVibrationBean)."""
    return build_request(2, 11, 0x10)


def set_vibration(level: int, count: int) -> bytes:
    """[static] {2,11,0,level,count}. This is the vibration *setting*
    ("getVibrationCountCmdJL"), not a 'buzz now' trigger."""
    return build_request(2, 11, 0, bytes([level & 0xFF, count & 0xFF]))


def find_device() -> bytes:
    """[static] {2,52,0,1} -- stock app's "find device" (获取查找设备).
    The closest thing to a 'buzz/flash now' command."""
    return build_request(2, 52, 0, b"\x01")


def camera_mode(enter: bool) -> bytes:
    """[static] {6,1,0,1|0} -- stock TakePhotoActivity enters (1) / leaves (0)
    remote-shutter mode. While active, ring taps are pushed back as
    {6,1,0,x} frames (see parse_event)."""
    return build_request(6, 1, 0, bytes([1 if enter else 0]))


def app_foreground(foreground: bool) -> bytes:
    """[static] {6,18,0,1|2} -- app foreground(1)/background(2) notice, sent
    by the stock app's lifecycle callbacks when the ring advertises
    appFrontAndBack. NOT a tap-reporting arm command."""
    return build_request(6, 18, 0, bytes([1 if foreground else 2]))


def set_count_mode(on: bool) -> bytes:
    """[static] {2,120,0,on} -- stock "设备赞念开关" (prayer/tasbih counter
    switch). Count updates are pushed as {6,16,0,n} / {6,17,0,n}."""
    return build_request(2, 120, 0, bytes([1 if on else 0]))


def query_count_mode() -> bytes:
    """[static] {2,120,16}."""
    return build_request(2, 120, 0x10)


def start_measurement(sensor_type: int) -> bytes:
    """[live ack] {6,9,0,sensor,5,1}."""
    return build_request(6, 9, 0, bytes([sensor_type, 5, 1]))


def stop_measurement(sensor_type: int) -> bytes:
    return build_request(6, 9, 0, bytes([sensor_type, 5, 0]))


MSG_TITLE_MAX = 32
MSG_CONTENT_MAX = 250


def _utf8_truncate(text: str, limit: int) -> bytes:
    """Truncate to <= limit UTF-8 bytes without splitting a code point."""
    out = text.encode("utf-8")
    if len(out) <= limit:
        return out
    cut = out[:limit]
    return cut.decode("utf-8", errors="ignore").encode("utf-8")


def push_message(title: str, content: str, when: tuple[int, int, int, int, int, int],
                 app_id: str = "com.android.mms") -> bytes:
    """[static] JL message push (lc.A): content =
    [4,1,0,0x7F, yy,MM,dd,hh,mm,ss, appId(32, zero-pad), title(32, zero-pad), body(<=250)]
    `when` = (yy, month, day, hour, minute, second)."""
    app = _utf8_truncate(app_id, 32).ljust(32, b"\0")
    ttl = _utf8_truncate(title, MSG_TITLE_MAX).ljust(32, b"\0")
    body = _utf8_truncate(content, MSG_CONTENT_MAX)
    head = bytes([4, 1, 0, 0x7F]) + bytes(v & 0xFF for v in when)
    return build_packet(head + app + ttl + body)


# ---------------------------------------------------------------------------
# Frame reassembly (first fragment has the AB header; continuations are raw)
# ---------------------------------------------------------------------------

class FrameAssembler:
    """Reassembles notifications into complete JL frames. Returns
    (protocol_flag, content, crc_ok) tuples."""

    def __init__(self) -> None:
        self._buf = bytearray()
        self._need = 0
        self._flag = 0
        self._crc = 0

    def feed(self, data: bytes) -> list[tuple[int, bytes, bool]]:
        from client import crc16_arc
        out: list[tuple[int, bytes, bool]] = []
        if self._need == 0:
            if len(data) < 6 or data[0] != PREAMBLE:
                return out
            self._flag = data[1]
            self._need = struct.unpack(">H", data[2:4])[0]
            self._crc = struct.unpack(">H", data[4:6])[0]
            self._buf = bytearray(data[6:])
        else:
            self._buf += data
        if len(self._buf) >= self._need:
            content = bytes(self._buf[: self._need])
            out.append((self._flag, content, crc16_arc(content) == self._crc))
            self._buf = bytearray()
            self._need = 0
        return out


# ---------------------------------------------------------------------------
# Parsers. `content` = full frame content incl. [group, key, flag].
# ---------------------------------------------------------------------------

SUPPORT_BYTE_FIELDS = {
    # payload offset (= stock bArr index - 3) -> field name, bit 0 of that byte
    0: "address_book", 1: "msg_notification", 2: "take_photo", 3: "dnd",
    4: "led_light", 5: "wear_dir", 6: "video_hid", 7: "health_monitor",
    8: "set_bt_name", 9: "find_device", 10: "recovery", 11: "power_off",
    12: "video_hid_book", 13: "video_hid_music", 14: "video_hid_android_book",
    15: "child_app_switch", 16: "push_msg_enable_switch",
    21: "girl_care", 22: "alarm", 23: "bright_screen_time",
    24: "default_en_system", 25: "default_no_view_workout", 26: "new_sport",
    27: "app_front_and_back", 28: "remember_switch",
    30: "bright_screen", 31: "motor_vibration_level",
}
HEALTH_BYTE_FIELDS = {
    81: "step", 82: "hr", 83: "blood_pressure", 84: "sleep",
    86: "blood_oxygen", 87: "hrv", 88: "stress", 89: "blood_sugar",
    90: "muslim_count", 91: "body_temp",
}


@dataclasses.dataclass
class SupportMap:
    flags: dict[str, bool]
    push_msg_mask: int
    push_msg_mask2: int
    raw: bytes

    def supported(self) -> list[str]:
        return sorted(k for k, v in self.flags.items() if v)


def parse_support(content: bytes) -> SupportMap:
    """[static layout, query live] analysisDeviceSupportV2 for {2,99,16}."""
    p = content[3:]
    if len(p) < 91:
        raise ValueError(f"support payload too short: {len(p)} < 91")
    bit = lambda off, n=0: bool((p[off] >> n) & 1)  # noqa: E731
    flags = {name: bit(off) for off, name in SUPPORT_BYTE_FIELDS.items()}
    flags["hr_reminder"] = bit(29, 0)
    flags["bo_reminder"] = bit(29, 1)
    health_all = bit(80)
    flags["health_all_switch"] = health_all
    for off, name in HEALTH_BYTE_FIELDS.items():
        flags[name] = health_all and off < len(p) and bit(off)
    mask = struct.unpack_from("<I", p, 17)[0]
    mask2 = struct.unpack_from("<I", p, 36)[0] if len(p) >= 40 else 0
    return SupportMap(flags, mask, mask2, bytes(p))


@dataclasses.dataclass
class FactoryState:
    gsensor: int
    heart: int
    wear: int


def parse_factory_state(content: bytes) -> FactoryState:
    """[static] analysisRingFactoryState, {2,112,16}."""
    p = content[3:]
    return FactoryState(p[0], p[1], p[2])


@dataclasses.dataclass
class Event:
    kind: str           # "tap_shutter" | "count" | "measurement_ack" | "setting" | "unknown"
    value: int | None
    resolved_id: int | None
    detail: str


SETTING_DECODERS = {
    (2, 100): lambda p: f"video_hid mode={p[0]} default_open={p[1] == 1}" if len(p) >= 2 else None,
    (2, 102): lambda p: f"led open={p[0] == 1} level={p[1]}" if len(p) >= 2 else None,
    (2, 104): lambda p: f"wear_hand={'right' if p[0] == 1 else 'left'}" if p else None,
    (2, 8): lambda p: f"bright_time={p[0]}s" if p else None,
    (2, 11): lambda p: f"vibration level={p[0]} count={p[1]}" if len(p) >= 2 else None,
    (2, 120): lambda p: f"count_mode={p[0]}" if p else None,
}


def parse_event(content: bytes) -> Event:
    """Classify a reassembled frame content."""
    g, k, f = content[0], content[1], content[2]
    p = content[3:]
    rid = resolve_id(g, k, f)
    if (g, k, f) == (6, 1, 0):
        # Stock analysisJLTakePhoto: only 4-byte contents are shutter taps;
        # 5-byte replies are filtered as acks.
        if len(content) == 4:
            return Event("tap_shutter", p[0], rid, f"shutter tap value={p[0]}")
        return Event("setting", None, rid, f"camera-mode ack {p.hex()}")
    if (g, k, f) in ((6, 16, 0), (6, 17, 0)) and p:
        return Event("count", p[0], rid, f"count={p[0]} via ({g},{k},{f})")
    if (g, k, f) == (6, 9, 0):
        return Event("measurement_ack", p[0] if p else None, rid, f"measure {p.hex()}")
    dec = SETTING_DECODERS.get((g, k))
    if dec and f in (0x00, 0x10):
        d = dec(p)
        if d:
            return Event("setting", None, rid, d)
    return Event("unknown", None, rid, f"({g},{k},{f}) id={rid} payload={p.hex()}")


# ---------------------------------------------------------------------------
# Tap gesture classifier (pure logic; events come from tap_shutter/count)
# ---------------------------------------------------------------------------

class TapGestureClassifier:
    """Groups discrete tap timestamps into single/double/triple/multi gestures.

    The stock protocol only reports discrete taps (shutter or counter events),
    so press-and-hold is NOT observable here unless the ring reports it some
    other way; that remains a hypothesis to test live.
    """

    NAMES = {1: "single", 2: "double", 3: "triple"}

    def __init__(self, window_ms: int = 450) -> None:
        self.window_ms = window_ms
        self._taps: list[int] = []

    def tap(self, t_ms: int) -> str | None:
        """Register a tap. Returns a completed gesture if the previous burst
        ended before this tap, else None."""
        done = self.poll(t_ms)
        self._taps.append(t_ms)
        return done

    def poll(self, now_ms: int) -> str | None:
        """Call periodically; returns a gesture once the burst window closes."""
        if self._taps and now_ms - self._taps[-1] > self.window_ms:
            n = len(self._taps)
            self._taps = []
            return self.NAMES.get(n, f"multi{n}")
        return None
