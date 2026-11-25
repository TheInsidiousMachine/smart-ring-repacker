"""Full recorder flow on the real patched image: our code + real stock timer/drain/accel-read code.
Only I2C transfers, delays and the BLE frame sender are stubbed. Flags any stock write into our RAM."""
import sys, struct
from unicorn import *
from unicorn.arm_const import *
img = open(sys.argv[1], 'rb').read()
syms = {l.split()[2]: int(l.split()[0], 16) for l in open(sys.argv[2]) if len(l.split()) == 3}
CAVE = (0x10026404, 0x1002831c)
mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
mu.mem_map(0x0, 0x40000); mu.mem_write(0x0, b'\x00\x20\x70\x47' * (0x40000 // 4))
mu.mem_map(0x10000000, 0x40000); mu.mem_write(0x10000000, img)
mu.mem_map(0x20000000, 0x10000)
for base in (0x40000000, 0x50000000, 0xe0000000): mu.mem_map(base, 0x100000)
RET = 0x0003fff0; mu.mem_write(RET, b'\xfe\xe7')
frames = []; fifo = {'n': int(next((a.split('=')[1] for a in sys.argv if a.startswith('--n=')), 5)), 'k': 0}
def send_frame(uc):
    r1 = uc.reg_read(UC_ARM_REG_R1); r3 = uc.reg_read(UC_ARM_REG_R3); sp = uc.reg_read(UC_ARM_REG_SP)
    ln = struct.unpack('<I', uc.mem_read(sp, 4))[0]
    frames.append((r1, bytes(uc.mem_read(r3, ln)))); return 1
def acc_read(uc):
    # fake FIFO: 5 samples, z = 1 g, x counts up
    n = fifo['n']
    for i in range(n):
        uc.mem_write(0x20006598 + 2*i, struct.pack('<h', 4 * (fifo['k'] + i)))
        uc.mem_write(0x200065d8 + 2*i, struct.pack('<h', 0))
        uc.mem_write(0x20006618 + 2*i, struct.pack('<h', 8192))
    fifo['k'] += n
    uc.mem_write(0x200025b6, bytes([n])); return n
STUB = {0x10014834: send_frame, 0x100184d8: 0, 0x100185ac: 0, 0x1001eea4: 0}
if '--fake-acc' in sys.argv: STUB[0x10008150] = acc_read
def hc(uc, addr, size, ud):
    if addr in STUB:
        v = STUB[addr]; v = v(uc) if callable(v) else v
        uc.reg_write(UC_ARM_REG_R0, v); uc.reg_write(UC_ARM_REG_PC, uc.reg_read(UC_ARM_REG_LR))
icount = {'n': 0}
def cnt(uc, addr, size, ud): icount['n'] += 1
mu.hook_add(UC_HOOK_CODE, hc)
mu.hook_add(UC_HOOK_CODE, cnt)
OURS = [(0x20004b24, 0x20004b64, 'A'), (0x20002878, 0x20002888, 'B'), (0x20006822, 0x20006828, 'glue')]
bad = []
# every RAM byte our code may write: own regions, our timer struct, the stack, and the
# stock variables we set on purpose. Anything else is a wild write (build 14 hit ROM RAM).
MAY_WRITE = [(0x20004b24, 0x20004b64), (0x20002878, 0x20002888), (0x20006808, 0x20006828),
             (0x20006c08, 0x20006de0), (0x20007000, 0x20007680),
             (0x200024d0, 0x200024d2), (0x200024e9, 0x200024ea),   # UI idle seconds, icon LED
             (0x20002001, 0x20002003),                             # BLE fast flag + counter
             (0x20004a68, 0x20004a6a)]                             # stock HR (cleared on heart start)
wild = []
def hw(uc, access, addr, size, value, ud):
    pc = uc.reg_read(UC_ARM_REG_PC)
    if CAVE[0] <= pc < CAVE[1]:
        if 0x20000000 <= addr < 0x20010000 and not any(lo <= addr and addr + size <= hi for lo, hi in MAY_WRITE):
            wild.append((hex(pc), hex(addr), size))
        return
    for lo, hi, n in OURS:
        if lo <= addr < hi: bad.append((n, hex(pc), hex(addr), size))
mu.hook_add(UC_HOOK_MEM_WRITE, hw)
def call(fn, *args):
    mu.reg_write(UC_ARM_REG_SP, 0x20007680)
    for r, a in zip((UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3), args): mu.reg_write(r, a)
    mu.reg_write(UC_ARM_REG_LR, RET | 1)
    mu.emu_start(fn | 1, RET, count=5_000_000)
def cmd(hexs):
    f = bytes.fromhex(hexs); mu.mem_write(0x20001000, f); call(syms['hook_cmd'], 0x20001000, len(f))
G = int(sys.argv[3],16) if len(sys.argv)>3 and sys.argv[3].startswith("0x") else 0x20006822
cmd(next((a.split('=')[1] for a in sys.argv if a.startswith('--on=')), 'ab 01 00 04 19 99 7a 04 00 01'))
print('after ON : glue', bytes(mu.mem_read(G, 6)).hex(' '), ' timer running', mu.mem_read(0x2000681c, 1)[0])
icount['n'] = 0
for _ in range(100): call(syms['app_tick'])          # 2 s of 20 ms ticks
print('instructions per 20 ms tick (avg, excl. I2C/BLE ROM): %d' % (icount['n'] // 100))
samples = sum(f[1] for i, f in frames if i == 0x7a11)
status = [f.hex(' ') for i, f in frames if i == 0x7a12]
print('2 s of ticks: %d recorder frames, %d samples; status frames: %s' % (sum(1 for i, _ in frames if i == 0x7a11), samples, status))
seqs = [f[0] for i, f in frames if i == 0x7a11]
print('seq continuous:', seqs == list(range(seqs[0], seqs[0] + len(seqs))) if seqs else None)
if samples:
    first = next(f for i, f in frames if i == 0x7a11)
    print('first frame decoded (x,y,z):', [struct.unpack_from('<3h', first, 2 + 6*k) for k in range(first[1])])
cmd('ab 01 00 04 d9 58 7a 04 00 00')
print('after OFF: glue', bytes(mu.mem_read(G, 6)).hex(' '), ' timer running', mu.mem_read(0x2000681c, 1)[0])
print('stock writes into our RAM:', bad if bad else 'none')
