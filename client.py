"""
SY01 / RWFit smart ring BLE client (the vendor app's `AB`-framed protocol; not a JieLi chip).

Reverse-engineered from the RWFit Android app (com.rw.revivalfit) by
decompiling with jadx, then verified live by bridging into the paired
Android phone over adb (wireless debugging) and capturing logcat while
the RWFit app talked to the ring. See docs/PROTOCOL.md for the full writeup
and what's still unconfirmed.

Usage:
    pip install bleak
    python client.py scan
    python client.py connect <MAC_OR_UUID>

Requires: bleak (cross-platform BLE library)
"""

from __future__ import annotations

import asyncio
import dataclasses
import struct
import sys
from typing import Callable

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    BleakClient = None  # type: ignore
    BleakScanner = None  # type: ignore

SERVICE_UUID = "0000a00a-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000b002-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000b003-0000-1000-8000-00805f9b34fb"

PREAMBLE = 0xAB

# Command groups (byte 0 of content). Confirmed live via logcat capture.
GROUP_SETTINGS = 2   # device settings / feature queries
GROUP_BIND = 3        # bind / unbind
GROUP_DEVICE_INFO = 6  # device/system info (battery etc, not captured live)
GROUP_SYNC = 5         # historical data sync (steps/sleep/HR/SpO2/stress)

# Real-time / on-demand measurement sensor types, used with group 6 subcmd 9.
# {6, 9, 0, sensorType, 5, action} -- action: 1=start, 0=stop.
# Live-confirmed this session: SENSOR_HEART_RATE start via the RWFit UI ACK'd
# (receiveBleData 9, resolved id 0x1F). No numeric reading came back because
# the ring wasn't on skin at the time -- see docs/PROTOCOL.md.
SENSOR_HEART_RATE = 3
SENSOR_HRV = 10
SENSOR_SPO2 = 9
SENSOR_BLOOD_PRESSURE = 4   # ring almost certainly can't actually measure this
SENSOR_STRESS = 13
SENSOR_TEMPERATURE = 8
SENSOR_BLOOD_SUGAR = 16     # definitely not a real ring sensor

FLAG_SET = 0x00
FLAG_QUERY = 0x10
FLAG_DATA_REPLY = 0x30  # paired with FLAG_QUERY for group-5 sync replies


def crc16_arc(data: bytes) -> int:
    """CRC-16/ARC (poly 0x8005 / reflected 0xA001, init 0, no xorout).
    Reconstructed from the app's lookup table and confirmed against it."""
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


def build_packet(content: bytes, bulk: bool = False) -> bytes:
    """Build a single-fragment JL command packet (content must fit one MTU)."""
    header = bytearray(6)
    header[0] = PREAMBLE
    header[1] = 0x11 if bulk else 0x01
    header[2:4] = struct.pack(">H", len(content))
    header[4:6] = struct.pack(">H", crc16_arc(content)) if content else b"\x00\x00"
    return bytes(header) + content


def build_request(group: int, subcmd: int, flag: int, args: bytes = b"") -> bytes:
    content = bytes([group, subcmd, flag]) + args
    return build_packet(content)


@dataclasses.dataclass
class Frame:
    protocol_flag: int
    content: bytes  # [cmd/group, key/subcmd, keyFlag, ...payload]

    @property
    def group(self) -> int:
        return self.content[0]

    @property
    def key(self) -> int:
        return self.content[1]

    @property
    def key_flag(self) -> int:
        return self.content[2]

    @property
    def payload(self) -> bytes:
        return self.content[3:]


def parse_frame(data: bytes) -> Frame | None:
    """Parses a single-fragment JL frame. Multi-fragment reassembly
    (continuation packets with no header) is not implemented here -- see
    docs/PROTOCOL.md; add buffering keyed by content-length if you hit it."""
    if len(data) < 6 or data[0] != PREAMBLE:
        return None
    proto_flag = data[1]
    content_len = struct.unpack(">H", data[2:4])[0]
    content = data[6:6 + content_len]
    if len(content) < 3:
        return None
    return Frame(protocol_flag=proto_flag, content=content)


def u32(b: bytes, off: int) -> int:
    return struct.unpack_from(">I", b, off)[0]


def u24(b: bytes, off: int) -> int:
    return (b[off] << 16) | (b[off + 1] << 8) | b[off + 2]


def u16(b: bytes, off: int) -> int:
    return struct.unpack_from(">H", b, off)[0]


# ---- payload parsers (static, unverified against real bytes -- see docs/PROTOCOL.md) ----

@dataclasses.dataclass
class StepBucket:
    index: int
    steps: int
    calories: int
    distance: int


@dataclasses.dataclass
class StepDay:
    timestamp: int
    total_steps: int
    total_calories: int
    total_distance: int
    buckets: list[StepBucket]


def parse_step_log(payload: bytes) -> list[StepDay]:
    out = []
    i = 0
    while i < len(payload):
        ts = u32(payload, i)
        total_steps = u16(payload, i + 4)
        total_cal = u24(payload, i + 6)
        total_dist = u24(payload, i + 9)
        item_count = u16(payload, i + 12)
        i += 14
        buckets = []
        for _ in range(item_count):
            idx = payload[i]
            steps = u16(payload, i + 1)
            cal = u24(payload, i + 3)
            dist = u16(payload, i + 6)
            buckets.append(StepBucket(idx, steps, cal, dist))
            i += 8
        out.append(StepDay(ts, total_steps, total_cal, total_dist, buckets))
    return out


@dataclasses.dataclass
class SleepSegment:
    length: int
    sleep_type: int


@dataclasses.dataclass
class SleepNight:
    timestamp: int
    total_minutes: int
    asleep_at: int
    awake_at: int
    segments: list[SleepSegment]


def parse_sleep_log(payload: bytes) -> list[SleepNight]:
    out = []
    i = 0
    while i < len(payload):
        ts = u32(payload, i)
        total_minutes = payload[i + 4]
        asleep_at = u32(payload, i + 5)
        awake_at = u32(payload, i + 9)
        item_count = u16(payload, i + 13)
        i += 15
        segs = []
        for _ in range(item_count):
            segs.append(SleepSegment(payload[i], payload[i + 1]))
            i += 2
        out.append(SleepNight(ts, total_minutes, asleep_at, awake_at, segs))
    return out


@dataclasses.dataclass
class TimedReading:
    timestamp: int
    value: int


@dataclasses.dataclass
class ReadingLog:
    timestamp: int
    items: list[TimedReading]


def _parse_timed_log(payload: bytes) -> list[ReadingLog]:
    """Shared shape for heart-rate and SpO2 logs."""
    out = []
    i = 0
    while i < len(payload):
        ts = u32(payload, i)
        item_count = u16(payload, i + 4)
        i += 6
        items = []
        for _ in range(item_count):
            item_ts = u32(payload, i)
            value = payload[i + 4]
            items.append(TimedReading(item_ts, value))
            i += 5
        out.append(ReadingLog(ts, items))
    return out


parse_heart_rate_log = _parse_timed_log
parse_spo2_log = _parse_timed_log


@dataclasses.dataclass
class Battery:
    low_power: bool
    power_status: int
    percent: int


def parse_battery(payload: bytes) -> Battery:
    return Battery(low_power=bool(payload[0]), power_status=payload[1], percent=payload[2])


class RingClient:
    def __init__(self, client: "BleakClient"):
        self._client = client
        self._on_frame: Callable[[Frame], None] | None = None

    async def send(self, group: int, subcmd: int, flag: int, args: bytes = b"") -> None:
        pkt = build_request(group, subcmd, flag, args)
        await self._client.write_gatt_char(WRITE_CHAR_UUID, pkt, response=True)

    async def start_notify(self, callback: Callable[[Frame], None]) -> None:
        self._on_frame = callback

        def _handler(_sender, data: bytearray):
            frame = parse_frame(bytes(data))
            if frame and self._on_frame:
                self._on_frame(frame)

        await self._client.start_notify(NOTIFY_CHAR_UUID, _handler)

    async def query_device_support(self) -> None:
        """Confirmed live: {2, 99, 16} -> device feature-support bitmap."""
        await self.send(GROUP_SETTINGS, 99, FLAG_QUERY)

    async def sync_history(self, category: int) -> None:
        """Group 5 = historical sync. `category` is the subcmd (2,3,4,5,9,10,13,26
        confirmed live so far) -- which metric each maps to (steps/sleep/HR/
        SpO2/stress) is not yet confirmed; see docs/PROTOCOL.md."""
        await self.send(GROUP_SYNC, category, FLAG_QUERY)

    async def start_measurement(self, sensor_type: int) -> None:
        """Live-confirmed for SENSOR_HEART_RATE via the RWFit UI this session."""
        await self.send(GROUP_DEVICE_INFO, 9, FLAG_SET, bytes([sensor_type, 5, 1]))

    async def stop_measurement(self, sensor_type: int) -> None:
        await self.send(GROUP_DEVICE_INFO, 9, FLAG_SET, bytes([sensor_type, 5, 0]))


def _on_frame_debug(frame: Frame) -> None:
    print(f"<- group={frame.group} key={frame.key} keyFlag={frame.key_flag} "
          f"payload={frame.payload.hex()}")


async def scan():
    print("Scanning for 10s...")
    devices = await BleakScanner.discover(timeout=10.0)
    for d in devices:
        print(f"{d.address}  {d.name}")


async def connect(address: str):
    async with BleakClient(address) as client:
        print("Connected:", client.is_connected)
        ring = RingClient(client)
        await ring.start_notify(_on_frame_debug)
        await ring.query_device_support()
        print("Listening... Ctrl+C to quit.")
        while True:
            await asyncio.sleep(1)


def main():
    if BleakClient is None:
        print("Install bleak first: pip install bleak", file=sys.stderr)
        sys.exit(1)
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    if sys.argv[1] == "scan":
        asyncio.run(scan())
    elif sys.argv[1] == "connect" and len(sys.argv) > 2:
        asyncio.run(connect(sys.argv[2]))
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
