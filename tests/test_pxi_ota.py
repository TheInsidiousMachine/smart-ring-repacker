import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest  # noqa: E402

import pxi_ota as ota  # noqa: E402


@pytest.mark.parametrize("size", [1, 179, 180, 4096, 4097, 10000, 65537])
def test_simulated_round_trip(size):
    img = bytes(random.Random(size).randrange(256) for _ in range(size))
    ring = ota.SimulatedRing(max_obj=4096, mtu=180, prn=4)
    s, t = ota.run_simulation(img, ring)
    assert bytes(ring.flash) == img
    assert ring.upgraded_ok and ring.reset and s.done
    cmds = [p[0] for k, p in t.log if k == "cmd"]
    assert cmds[0] == ota.CMD_FW_OTA_INIT_NEW and cmds[-1] == ota.CMD_FW_MCU_RESET
    assert not set(cmds) & ota.FORBIDDEN_OPCODES


def test_checksum_mismatch_aborts():
    s = ota.OtaSession(b"\x01" * 10, ota.DryRunTransport())
    s.start()
    s.on_notify(bytes([0x0E, 0, 0x27, 0, 0, 0, 0, 0, 0]) + (4096).to_bytes(4, "little") + (180).to_bytes(2, "little") + (4).to_bytes(2, "little"))
    s.on_notify(bytes([0x25, 0]))
    with pytest.raises(RuntimeError, match="checksum"):
        s.on_notify(bytes([0x17, 0x00, 0x00]))


def test_forbidden_opcode_blocked():
    with pytest.raises(RuntimeError):
        ota.DryRunTransport().write_command(bytes([ota.CMD_FW_ERASE]))


def test_init_reply_validation():
    with pytest.raises(ValueError):
        ota.parse_init_reply(bytes(17))
