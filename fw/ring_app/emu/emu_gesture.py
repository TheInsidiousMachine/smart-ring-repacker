"""Gesture detector on the real patched image (build 10+).

Boot path (stock drain restart -> hook_drain_start) on garbage RAM, then the real
recordings replayed through the accel FIFO at ~100 Hz with app_tick every 20 ms.
I2C is a register file (so the CTRL1 readback works), BLE sender and delays are
stubbed, everything else is real stock code. Reports fires per recording, draw
calls, instructions and stack per tick, and stock writes into our RAM.

usage: python emu/emu_gesture.py build/<img>.bin build/syms.txt testdata
"""
import sys, struct, os
sys_argv = sys.argv[:]
sys.argv = sys.argv[:3]
src = open(__file__.replace('emu_gesture.py', 'emu_recorder.py')).read()
src = src[:src.index('cmd(next(')]
exec(src)
TD = sys_argv[3] if len(sys_argv) > 3 else 'testdata'

# I2C register file for the accel (addr 0x18/0x19): read 0x100184d8, write 0x100185ac
regs = {0x20: 0x47, 0x2f: 0x20}
i2c_log = []
def i2c_rw(uc, write):
    reg = uc.reg_read(UC_ARM_REG_R3); sp = uc.reg_read(UC_ARM_REG_SP)
    buf, ln = struct.unpack('<II', uc.mem_read(sp, 8))
    if write:
        v = bytes(uc.mem_read(buf, ln)); regs[reg & 0x7f] = v[0]; i2c_log.append(('w', reg, v[0]))
    else:
        uc.mem_write(buf, bytes([regs.get((reg + i) & 0x7f, 0) for i in range(ln)]))
    return 0
STUB[0x100184d8] = lambda uc: i2c_rw(uc, False)
STUB[0x100185ac] = lambda uc: i2c_rw(uc, True)

rp = {'d': [], 'pos': 0, 'pend': 0}
def acc_read(uc):
    n = 0
    while rp['pend'] > 0 and n < 32 and rp['pos'] < len(rp['d']):
        x, y, z = rp['d'][rp['pos']]
        uc.mem_write(0x20006598 + 2*n, struct.pack('<h', x * 4))
        uc.mem_write(0x200065d8 + 2*n, struct.pack('<h', y * 4))
        uc.mem_write(0x20006618 + 2*n, struct.pack('<h', z * 4))
        rp['pos'] += 1; rp['pend'] -= 1; n += 1
    uc.mem_write(0x200025b6, bytes([n])); return n
STUB[0x10008150] = acc_read

draws = []
def on_draw(uc, addr, size, ud):
    if addr == 0x10019254:
        draws.append((rp['pos'], bytes(uc.mem_read(uc.reg_read(UC_ARM_REG_R0), 5)).decode('latin1')))
mu.hook_add(UC_HOOK_CODE, on_draw, begin=0x10019254, end=0x10019255)
minsp = {'v': 0x20007680}
def on_sp(uc, addr, size, ud):
    s = uc.reg_read(UC_ARM_REG_SP)
    if s < minsp['v']: minsp['v'] = s
mu.hook_add(UC_HOOK_CODE, on_sp)
OURS.append((0x20006c08, 0x20006c68, 'gesture'))
OURS.append((0x20006c70, 0x20006d18, 'message'))
OURS.append((0x20006d88, 0x20006d98, 'gesture v2'))

GS = 0x20006c08
# boot on garbage RAM in region D
mu.mem_write(GS, b'\xa5' * 0x60)
call(syms['hook_drain_start'])
gs = bytes(mu.mem_read(GS, 24))
print('boot: timer running', mu.mem_read(0x2000681c, 1)[0], ' magic %04x' % struct.unpack_from('<H', gs)[0],
      ' flags', gs[19])
call(syms['app_tick'])
print('first tick: CTRL1 = %#x' % regs[0x20], ' drain-start calls stubbed? n/a', ' i2c', i2c_log[-3:])

def replay(name):
    rp['d'] = [tuple(map(int, l.split())) for l in open(os.path.join(TD, name + '.txt'))]
    rp['pos'] = 0; rp['pend'] = 0
    f0 = len(frames); d0 = len(draws)
    icount['n'] = 0; ticks = 0; worst = 0
    while rp['pos'] < len(rp['d']):
        rp['pend'] += 2
        before = icount['n']; call(syms['app_tick']); ticks += 1
        worst = max(worst, icount['n'] - before)
    fired = [round(rp_pos_at / 100, 2) for rp_pos_at in [None] if False]
    ev = [f for i, f in frames[f0:] if i == 0x7a13]
    nf = sum(1 for e in ev if e[0] == 2)
    words = [w for _, w in draws[d0:]]
    print('%-11s fired %2d, armed %2d; draws %s; instr/tick avg %d worst %d' % (
        name, nf, sum(1 for e in ev if e[0] == 1), (words[:4] + ['...']) if len(words) > 4 else words, icount['n'] // ticks, worst))
    return nf
res = {n: replay(n) for n in ('target', 'bumps_only', 'flick_only', 'everyday', 'multiflick')}
print('fire times (target) by draw:', [round(p / 100, 2) for p, w in draws if w == 'HI TH'][:12])
print('CTRL1 at end %#x' % regs[0x20])
# stock re-inits the accel (e.g. wake): drain restart -> we re-apply our ODR
regs[0x20] = 0x47
call(syms['hook_drain_start']); call(syms['app_tick'])
print('after stock drain restart: CTRL1 %#x' % regs[0x20])
# stock powers the accel down: we stop reading within 2 s
regs[0x20] = 0
for _ in range(101): call(syms['app_tick'])
print('accel off by stock: flags %#x (8 = asleep)' % mu.mem_read(GS + 19, 1)[0])
# custom message over BLE: 3 frames, 100 ms each, played twice; then fire by a test command
regs[0x20] = 0x67
cmd('ab 01 00 0e 00 00 7a 07 00 00 68 65 6c 6c 6f 57 4f 52 4c 44')
cmd('ab 01 00 09 00 00 7a 07 00 02 20 21 21 21 20')
cmd('ab 01 00 06 00 00 7a 08 00 03 05 02')
d0 = len(draws); cmd('ab 01 00 03 00 00 7a 06 00')
for _ in range(40): call(syms['app_tick'])
custom = [w for _, w in draws[d0:]]
print('custom message draws:', custom)
custom_ok = custom == ['HELLO', 'WORLD', ' !!! '] * 2
print('max stack used by a tick: %d B' % (0x20007680 - minsp['v']))
ok = res == {'target': 10, 'bumps_only': 0, 'flick_only': 0, 'everyday': 0, 'multiflick': 0}
print('stock writes into our RAM:', bad if bad else 'none')
print('RESULT:', 'PASS' if ok and custom_ok and not bad else 'FAIL')
