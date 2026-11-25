"""
Stock SY01 OTA protocol ("PXI" path on service ff00 / char ff01), reconstructed
from the stock app: com/example/blesdk/service/b.java (state machine),
PXIOtaService$PXI_CMD_FW.java (opcodes), com/example/test/presenter/device/z0.java
(start), g5/a.java:930 (reply is fetched by *reading* ff01 after INIT_NEW).
[static] — none of this has been exchanged with the ring yet.

SAFETY: this module only drives a `Transport`. The shipped transports are
DryRunTransport (records, never sends) and a simulator. There is deliberately
no BLE transport here; adding one is a gated step (docs/FIRMWARE.md).

Flow (all integers little-endian):
  app  -> INIT_NEW   [0x27, size:u32]
  ring -> EVT        [0x0E, ?, 0x27, ?, ?, a:u16, b:u16, max_obj:u32, mtu:u16, prn:u16]
  loop per object (offset = bytes sent so far):
    app  -> OBJECT_CREATE [0x25, offset:u32, max_obj:u32]
    ring -> [0x25, ...]
    loop: app sends up to `prn` raw data chunks (each <= mtu, <= object remainder)
          ring -> [0x17, sum16_so_far:u16, ...]   (running byte-sum of the image)
  app  -> UPGRADE   [0x18, size:u32, sum16_of_image:u16]
  ring -> [0x18, ...]
  app  -> MCU_RESET [0x22, 0x00]

Offsets are image-relative: the phone never names a flash address in this
flow; the ring's resident updater decides placement. (SET_ADDRESS 0x11,
ERASE 0x16, SECTOR_ERASE 0x19 exist in the enum but the stock app never sends
them — we treat them as forbidden.)
"""

from __future__ import annotations

import dataclasses
import struct
from typing import Protocol

CMD_FW_INIT = 0x10
CMD_FW_SET_ADDRESS = 0x11
CMD_FW_ERASE = 0x16
CMD_FW_WRITE = 0x17
CMD_FW_UPGRADE = 0x18
CMD_FW_SECTOR_ERASE = 0x19
CMD_FW_WRITE_SET = 0x20
CMD_FW_CONNECTION_UPDATE = 0x21
CMD_FW_MCU_RESET = 0x22
CMD_FW_GET_ATT_MTU = 0x24
CMD_FW_OBJECT_CREATE = 0x25
CMD_FW_EXECUTE = 0x26
CMD_FW_OTA_INIT_NEW = 0x27
EVT_COMMAND_COMPLETE = 0x0E

FORBIDDEN_OPCODES = {CMD_FW_SET_ADDRESS, CMD_FW_ERASE, CMD_FW_SECTOR_ERASE, CMD_FW_INIT, CMD_FW_WRITE_SET}


def sum16(data: bytes, start: int = 0) -> int:
    """Stock checksum: running byte sum masked to 16 bits (0xFFFF)."""
    return (start + sum(data)) & 0xFFFF


class Transport(Protocol):
    def write_command(self, data: bytes) -> None: ...
    def write_data(self, data: bytes) -> None: ...


class DryRunTransport:
    """Records every packet; never touches a radio."""

    def __init__(self) -> None:
        self.log: list[tuple[str, bytes]] = []

    def write_command(self, data: bytes) -> None:
        if data and data[0] in FORBIDDEN_OPCODES:
            raise RuntimeError(f"forbidden opcode 0x{data[0]:02X}")
        self.log.append(("cmd", bytes(data)))

    def write_data(self, data: bytes) -> None:
        self.log.append(("data", bytes(data)))


@dataclasses.dataclass
class OtaParams:
    max_object_size: int
    mtu: int
    prn: int


def parse_init_reply(r: bytes) -> OtaParams:
    if len(r) < 17 or r[0] != EVT_COMMAND_COMPLETE or r[2] != CMD_FW_OTA_INIT_NEW:
        raise ValueError(f"not an INIT_NEW completion: {r.hex()}")
    max_obj = struct.unpack_from("<I", r, 9)[0]
    mtu, prn = struct.unpack_from("<HH", r, 13)
    if not (0 < mtu <= 512 and 0 < prn and 0 < max_obj <= 1 << 20):
        raise ValueError(f"implausible params max_obj={max_obj} mtu={mtu} prn={prn}")
    return OtaParams(max_obj, mtu, prn)


class OtaSession:
    """Event-driven client mirroring the stock state machine, with checks the
    stock app lacks (bounds, opcode allow-list, checksum on every ack)."""

    def __init__(self, image: bytes, transport: Transport) -> None:
        if not image:
            raise ValueError("empty image")
        self.image = image
        self.t = transport
        self.params: OtaParams | None = None
        self.sent = 0
        self.obj_sent = 0
        self.running_sum = 0
        self.done = False
        self.progress = 0.0

    def start(self) -> None:
        self.t.write_command(bytes([CMD_FW_OTA_INIT_NEW]) + struct.pack("<I", len(self.image)))

    def _create_object(self) -> None:
        assert self.params
        self.obj_sent = 0
        self.t.write_command(bytes([CMD_FW_OBJECT_CREATE]) + struct.pack("<II", self.sent, self.params.max_object_size))

    def _send_burst(self) -> None:
        p = self.params
        assert p
        for _ in range(p.prn):
            n = min(p.mtu, len(self.image) - self.sent, p.max_object_size - self.obj_sent)
            if n <= 0:
                break
            chunk = self.image[self.sent:self.sent + n]
            self.running_sum = sum16(chunk, self.running_sum)
            self.t.write_data(chunk)
            self.sent += n
            self.obj_sent += n

    def on_notify(self, r: bytes) -> None:
        op = r[0]
        if op == EVT_COMMAND_COMPLETE:
            self.params = parse_init_reply(r)
            self._create_object()
        elif op == CMD_FW_OBJECT_CREATE:
            self._send_burst()
        elif op == CMD_FW_WRITE:
            ack = struct.unpack_from("<H", r, 1)[0]
            if ack != self.running_sum:
                raise RuntimeError(f"checksum mismatch ring=0x{ack:04X} ours=0x{self.running_sum:04X}")
            self.progress = self.sent / len(self.image)
            if self.sent >= len(self.image):
                self.t.write_command(bytes([CMD_FW_UPGRADE]) + struct.pack("<IH", len(self.image), sum16(self.image)))
            elif self.obj_sent >= self.params.max_object_size:
                self._create_object()
            else:
                self._send_burst()
        elif op == CMD_FW_UPGRADE:
            self.t.write_command(bytes([CMD_FW_MCU_RESET, 0]))
            self.done = True


class SimulatedRing:
    """Minimal device model for tests: stores what it receives, acks like the
    stock app expects. Says nothing about the real ring's behavior."""

    def __init__(self, max_obj: int = 4096, mtu: int = 180, prn: int = 4) -> None:
        self.max_obj, self.mtu, self.prn = max_obj, mtu, prn
        self.flash = bytearray()
        self.size = 0
        self.sum = 0
        self.reset = False
        self.upgraded_ok = None
        self._pending_in_burst = 0

    def handle_cmd(self, c: bytes) -> bytes | None:
        op = c[0]
        if op == CMD_FW_OTA_INIT_NEW:
            self.size = struct.unpack_from("<I", c, 1)[0]
            return bytes([EVT_COMMAND_COMPLETE, 0, CMD_FW_OTA_INIT_NEW, 0, 0, 0, 0, 0, 0]) + \
                struct.pack("<IHH", self.max_obj, self.mtu, self.prn)
        if op == CMD_FW_OBJECT_CREATE:
            off = struct.unpack_from("<I", c, 1)[0]
            assert off == len(self.flash), "non-contiguous object"
            self._pending_in_burst = 0
            return bytes([CMD_FW_OBJECT_CREATE, 0])
        if op == CMD_FW_UPGRADE:
            size, s = struct.unpack_from("<IH", c, 1)
            self.upgraded_ok = size == len(self.flash) == self.size and s == sum16(bytes(self.flash))
            return bytes([CMD_FW_UPGRADE, 0])
        if op == CMD_FW_MCU_RESET:
            self.reset = True
            return None
        raise AssertionError(f"unexpected opcode 0x{op:02X}")

    def handle_data(self, d: bytes, burst_end: bool) -> bytes | None:
        assert len(d) <= self.mtu
        self.flash += d
        self.sum = sum16(d, self.sum)
        return bytes([CMD_FW_WRITE]) + struct.pack("<H", self.sum) if burst_end else None


def run_simulation(image: bytes, ring: SimulatedRing) -> tuple[OtaSession, DryRunTransport]:
    """Drive OtaSession against SimulatedRing through a recording transport."""
    t = DryRunTransport()
    s = OtaSession(image, t)
    s.start()
    seen = 0
    while not s.done:
        new = t.log[seen:]
        seen = len(t.log)
        if not new:
            raise RuntimeError("stalled")
        datas = [p for k, p in new if k == "data"]
        for k, p in new:
            if k == "cmd":
                r = ring.handle_cmd(p)
                if r:
                    s.on_notify(r)
        for i, p in enumerate(datas):
            r = ring.handle_data(p, i == len(datas) - 1)
            if r:
                s.on_notify(r)
    for k, p in t.log[seen:]:  # deliver trailing commands (MCU_RESET)
        if k == "cmd":
            ring.handle_cmd(p)
    return s, t
