#!/usr/bin/env python3
"""Build the custom SY01 ring-app image from the user's own stock V2.1.4 image.

Usage: python3 build_image.py [path/to/SY01_30330000_V2.1.4_stock.bin]
       (or set SY01_STOCK_IMAGE; default firmware/SY01_30330000_V2.1.4_stock.bin)
Toolchain: arm-none-eabi-gcc on PATH, or ARM_GCC_PREFIX=/path/to/bin/arm-none-eabi-
           (developed with Arm GNU Toolchain 14.3.rel1; other versions may emit different bytes).

The vendor image is NOT part of this repository. You obtain it yourself (docs/FIRMWARE.md) and this
script REFUSES TO RUN unless its SHA-256 equals the known-good value below.

Offline only (no device contact): compiles ring_app.c into the freed SpO2 region, applies a fixed
list of branch patches (each checked against the expected stock bytes), and refuses to emit anything
if a byte outside the allowed ranges changed or the size differs from stock.
The output is a derivative of the vendor image: do not redistribute it.
"""
import hashlib, os, struct, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
DEFAULT_STOCK = os.path.join(ROOT, 'firmware', 'SY01_30330000_V2.1.4_stock.bin')
STOCK_SHA = '8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50'
OUT = os.path.join(HERE, 'build')
CC = os.environ.get('ARM_GCC_PREFIX', 'arm-none-eabi-')
BASE = 0x10000000
CAVE_LO, CAVE_HI = 0x10026404, 0x1002831c

def run(*a):
    subprocess.run(a, check=True)

def bl(src, dst):
    off = dst - (src + 4)
    assert -(1 << 24) <= off < (1 << 24) and off % 2 == 0, (hex(src), hex(dst))
    s = 1 if off < 0 else 0
    off &= (1 << 25) - 1
    i1, i2 = (off >> 23) & 1, (off >> 22) & 1
    imm10, imm11 = (off >> 12) & 0x3ff, (off >> 1) & 0x7ff
    j1, j2 = (1 - i1) ^ s, (1 - i2) ^ s
    hw1 = 0xf000 | (s << 10) | imm10
    hw2 = 0xd000 | (j1 << 13) | (j2 << 11) | imm11
    return struct.pack('<HH', hw1, hw2)

def load_stock():
    path = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('SY01_STOCK_IMAGE', DEFAULT_STOCK)
    if not os.path.isfile(path):
        sys.exit(f'stock image not found: {path}\nObtain your own copy (see docs/FIRMWARE.md) and pass its path.')
    data = open(path, 'rb').read()
    got = hashlib.sha256(data).hexdigest()
    if got != STOCK_SHA:
        sys.exit(f'REFUSING: SHA-256 mismatch for {path}\n  expected {STOCK_SHA}\n  got      {got}\n'
                 'Only the exact vendor V2.1.4 (model 30330000) image is supported.')
    return data

def main():
    stock = load_stock()
    os.makedirs(OUT, exist_ok=True)
    elf = os.path.join(OUT, 'app.elf')
    flags = ['-mcpu=cortex-m0', '-mthumb', '-Os', '-std=c11', '-ffreestanding', '-fno-common',
             '-ffunction-sections', '-DMC_TRANS_ADDR=0x20002878', '-Wall', '-Wextra', '-Werror', '-nostdlib']
    run(CC + 'gcc', *flags, '-T', os.path.join(HERE, 'cave.ld'), '-Wl,--gc-sections',
        '-Wl,-e,hook_accel', '-Wl,--undefined=hook_key', '-Wl,--undefined=hook_cmd', '-Wl,--undefined=hook_ppg', '-Wl,--undefined=hook_draw_gate', '-Wl,--undefined=hook_drain_start', '-Wl,-Map,' + os.path.join(OUT, 'app.map'),
        os.path.join(HERE, 'stub.S'), os.path.join(HERE, 'ring_app.c'), '-lgcc', '-o', elf)
    blob = os.path.join(OUT, 'cave.bin')
    run(CC + 'objcopy', '-O', 'binary', '-j', '.text', elf, blob)
    syms = {}
    for line in subprocess.run([CC + 'nm', elf], capture_output=True, text=True, check=True).stdout.splitlines():
        p = line.split()
        if len(p) == 3: syms[p[2]] = (int(p[0], 16), p[1])
    bad = [n for n, (a, t) in syms.items() if t in 'dDbB' or (t in 'tTrR' and not CAVE_LO <= a < CAVE_HI)]
    assert not bad, f'symbols outside cave / RAM sections: {bad}'
    code = open(blob, 'rb').read()
    assert syms['spo2_stub'][0] == CAVE_LO
    assert CAVE_LO + len(code) <= CAVE_HI, f'cave overflow: {len(code)} > {CAVE_HI - CAVE_LO}'
    ha, hk, hc = syms['hook_accel'][0], syms['hook_key'][0], syms['hook_cmd'][0]
    hp, hd = syms['hook_ppg'][0], syms['hook_draw_gate'][0]
    hs = syms['hook_drain_start'][0]

    img = bytearray(stock)
    allowed = [(CAVE_LO, CAVE_LO + len(code))]
    def patch(addr, expect, new, why):
        o = addr - BASE
        assert img[o:o + len(expect)] == expect, f'{why}: unexpected stock bytes at {addr:#x}'
        img[o:o + len(new)] = new
        allowed.append((addr, addr + len(new)))
        print(f'  patch {addr:#010x} {expect.hex()} -> {new.hex()}  {why}')
    print('patches:')
    patch(0x10009764, bl(0x10009764, 0x1000d80c), bl(0x10009764, ha), 'accel sample -> hook_accel')
    for site in (0x100186ce, 0x10018702, 0x1001870c, 0x10018730):
        patch(site, bl(site, 0x1001c560), bl(site, hk), 'touch event -> hook_key')
    patch(0x1001330e, bl(0x1001330e, 0x10012230), bl(0x1001330e, hc), 'BLE command -> hook_cmd')
    patch(0x100167ea, bl(0x100167ea, 0x10016a38), bl(0x100167ea, hp), 'PPG read -> hook_ppg')
    patch(0x1001925a, bl(0x1001925a, 0x1000e1c8), bl(0x1001925a, hd), 'draw_text gate -> hook_draw_gate')
    for site in (0x1000fb4c, 0x10010a2e, 0x1001ad8e, 0x1001de96):
        patch(site, bl(site, 0x100068ec), bl(site, hs), 'stock accel drain restart -> hook_drain_start')
    patch(0x10017584, bl(0x10017584, 0x10017f44), bytes.fromhex('c046c046'), 'SpO2 measurement step -> nop (frees its buffer for our RAM)')
    # deliberate removals: step counting and sleep analysis (frees RAM and code space for our app layer)
    patch(0x10009818, bl(0x10009818, 0x1000ef94), bytes.fromhex('c046c046'), 'step algorithm (drain path A) -> nop')
    patch(0x1000983a, bl(0x1000983a, 0x1000ef94), bytes.fromhex('c046c046'), 'step algorithm (drain path B) -> nop')
    patch(0x1001758a, bl(0x1001758a, 0x10016df0), bytes.fromhex('c046c046'), 'sleep/HRV measurement step -> nop')
    img[CAVE_LO - BASE:CAVE_LO - BASE + len(code)] = code

    # --- invariants -------------------------------------------------------
    assert len(img) == len(stock), 'size changed'
    for i, (a, b) in enumerate(zip(stock, img)):
        if a != b:
            ad = BASE + i
            assert any(lo <= ad < hi for lo, hi in allowed), f'unexpected change at {ad:#x}'
    assert img[:0x100] == stock[:0x100], 'vector table changed'
    for lo, hi, name in [(0x1001b400, 0x1001b520, 'OTA callback setup'), (0x1001cb00, 0x1001cc60, 'OTA verify/write'),
                         (0x1001db00, 0x1001dbd0, 'OTA erase'), (0x1002a840, 0x1002ab68, 'scatter table + RW data')]:
        assert img[lo - BASE:hi - BASE] == stock[lo - BASE:hi - BASE], f'{name} changed'
    sha = hashlib.sha256(img).hexdigest()
    name = f'SY01_30330000_V2.1.4_custom_{sha[:8]}.bin'
    open(os.path.join(OUT, name), 'wb').write(img)
    changed = sum(a != b for a, b in zip(stock, img))
    print(f'cave code {len(code)} B of {CAVE_HI - CAVE_LO} B; hook_accel={ha:#x} hook_key={hk:#x} hook_cmd={hc:#x}')
    print(f'{changed} bytes differ from stock, all inside allowed ranges; size {len(img)} == stock')
    print(f'image: {os.path.join(OUT, name)}\nsha256: {sha}')

if __name__ == '__main__':
    main()
