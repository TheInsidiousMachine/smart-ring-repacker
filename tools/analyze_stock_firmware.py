#!/usr/bin/env python3
"""Reproduce the non-invasive facts recorded for the stock SY01 image.

This script only reads a local firmware file.  It does not use Bluetooth and
contains no firmware-write path.  The offsets are specific to the vendor image
whose SHA-256 is listed below; a different image is rejected by default.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path


EXPECTED_NAME = "SY01_30330000_V2.1.4_stock.bin"
EXPECTED_SIZE = 174_952
EXPECTED_SHA256 = "8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50"
LOAD_ADDRESS = 0x1000_0000


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def require(data: bytes, offset: int, expected: bytes, label: str) -> None:
    actual = data[offset : offset + len(expected)]
    if actual != expected:
        raise SystemExit(
            f"signature mismatch for {label} at file offset 0x{offset:x}: "
            f"expected {expected.hex()}, got {actual.hex()}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "image",
        nargs="?",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "firmware" / EXPECTED_NAME,
    )
    args = parser.parse_args()

    data = args.image.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != EXPECTED_SIZE or digest != EXPECTED_SHA256:
        raise SystemExit(
            "unrecognized image; refusing to apply V2.1.4-specific offsets\n"
            f"size={len(data)} sha256={digest}"
        )

    # Fixed signatures used by docs/FIRMWARE_ANALYSIS.md.  These checks avoid
    # presenting offsets from one build as facts about another build.
    require(data, 0x9A90, b"30330000\x00", "model string")
    require(data, 0xD7CC, b"SY01\x00", "product string")
    require(data, 0x295CB, b"*PixArt BLE Keyboard", "BLE device-name attribute")
    require(data, 0x62F0, bytes.fromhex("0f 21 20 46"), "motion-sensor probe")
    require(data, 0x6304, bytes.fromhex("68 46 00 78 11 28"), "WHO_AM_I check")

    initial_sp = u32(data, 0)
    reset_vector = u32(data, 4)
    nmi_vector = u32(data, 8)
    hardfault_vector = u32(data, 12)
    image_end = LOAD_ADDRESS + len(data)

    print(f"file: {args.image}")
    print(f"size: {len(data)} (0x{len(data):x})")
    print(f"sha256: {digest}")
    print(f"mapped range: 0x{LOAD_ADDRESS:08x}-0x{image_end:08x}")
    print(f"initial SP: 0x{initial_sp:08x}")
    print(f"reset vector: 0x{reset_vector:08x} (Thumb)")
    print(f"NMI vector: 0x{nmi_vector:08x} (Thumb)")
    print(f"HardFault vector: 0x{hardfault_vector:08x} (Thumb)")
    print("application entry after runtime setup: 0x1001ad09 (Thumb)")
    print("motion probe: I2C 0x18-0x1f; reg 0x0f == 0x11")
    print("motion version acceptance: reg 0x70 in {0x11, 0x26, 0x28}")
    print("motion-family match: Silan SC7A20/SC7A20H")
    print("stock image verified; no radio or device write was performed")


if __name__ == "__main__":
    main()
