"""Build 12+: touch, modes and the tilt mouse on the real patched image.

Touch pin (GPIO 10 read, 0x100153fc) and mouse reports (0x100154a8) are stubbed and recorded;
the accel FIFO serves gravity at a chosen pitch; I2C is a register file; the stock clock page,
draw_text, heart measurement start/stop and timers are real stock code.

usage: python emu/emu_touch.py build/<img>.bin build/syms.txt
"""
import sys, struct, math
sys.argv = sys.argv[:3]
src = open(__file__.replace('emu_touch.py', 'emu_recorder.py')).read()
src = src[:src.index('cmd(next(')]
exec(src)

regs = {0x20: 0x47, 0x2f: 0x20}
def i2c_rw(uc, write):
    reg = uc.reg_read(UC_ARM_REG_R3); sp = uc.reg_read(UC_ARM_REG_SP)
    buf, ln = struct.unpack('<II', uc.mem_read(sp, 8))
    if write: regs[reg & 0x7f] = uc.mem_read(buf, 1)[0]
    else: uc.mem_write(buf, bytes([regs.get((reg + i) & 0x7f, 0) for i in range(ln)]))
    return 0
STUB[0x100184d8] = lambda uc: i2c_rw(uc, False)
STUB[0x100185ac] = lambda uc: i2c_rw(uc, True)

st = {'touch': 0, 'pitch': 0.0, 'pend': 0, 'free': 10}
def gpio(uc):
    return st['touch'] if uc.reg_read(UC_ARM_REG_R0) == 10 else 0
STUB[0x100153fc] = gpio
reports = []
def mouse(uc):
    b, dx, dy = (uc.reg_read(r) for r in (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2))
    reports.append((b & 0xff, struct.unpack('b', bytes([dx & 0xff]))[0], struct.unpack('b', bytes([dy & 0xff]))[0])); return 0
STUB[0x100154a8] = mouse
def acc(uc):
    n = st['pend']; st['pend'] = 0
    gy, gz = math.sin(st['pitch']) * 2048, math.cos(st['pitch']) * 2048
    for i in range(n):
        uc.mem_write(0x20006598 + 2*i, struct.pack('<h', 0))
        uc.mem_write(0x200065d8 + 2*i, struct.pack('<h', int(gy) * 4))
        uc.mem_write(0x20006618 + 2*i, struct.pack('<h', int(gz) * 4))
    uc.mem_write(0x200025b6, bytes([n])); return n
STUB[0x10008150] = acc
draws = []
def on_draw(uc, addr, size, ud):
    draws.append((bytes(uc.mem_read(uc.reg_read(UC_ARM_REG_R0), 5)).decode('latin1'), uc.reg_read(UC_ARM_REG_R1)))
mu.hook_add(UC_HOOK_CODE, on_draw, begin=0x10019254, end=0x10019255)
OURS += [(0x20006c08, 0x20006c68, 'gesture'), (0x20006c70, 0x20006d18, 'message'), (0x20006d20, 0x20006d80, 'ui'),
         (0x20006d88, 0x20006d98, 'gesture2'), (0x20006da0, 0x20006de0, 'pointer')]
STUB[0x4e2a] = lambda uc: st['free']                 # ROM: free BLE TX buffers
fastreq = {'n': 0}
mu.hook_add(UC_HOOK_CODE, lambda uc, a, sz, ud: fastreq.__setitem__('n', fastreq['n'] + 1), begin=0x1000f214, end=0x1000f215)

UI = 0x20006d20
def mode(): return mu.mem_read(UI + 2, 1)[0]
def ticks(n):
    for _ in range(n):
        st['pend'] += 2; call(syms['app_tick'])
def tap(): st['touch'] = 1; ticks(3); st['touch'] = 0; ticks(5)

mu.mem_write(0x20006c08, b'\xa5' * 0x180)          # garbage RAM in D/E/F
call(syms['hook_drain_start']); ticks(20)
ok = {}
# powered-off state (0x200022a3 = 0): our touch logic must stay out of the way
d0 = len(draws)
for _ in range(5): tap()
ok['powered off: touch ignored'] = mode() == 0 and len(draws) == d0
mu.mem_write(0x200022a3, b'\x01')                  # ring on (stock power-on sets this)
d0 = len(draws); tap()
ok['press wakes clock'] = any(p == 1 for _, p in draws[d0:])
kc = {'n': 0}
mu.hook_add(UC_HOOK_CODE, lambda uc, a, sz, ud: kc.__setitem__('n', kc['n'] + 1), begin=0x1001c560, end=0x1001c561)
call(syms['hook_key'], 0)
ok['stock key swallowed when on'] = kc['n'] == 0
mu.mem_write(0x200022a3, b'\x00'); call(syms['hook_key'], 1); mu.mem_write(0x200022a3, b'\x01')
ok['stock key passed when off (power-on)'] = kc['n'] == 1
mu.mem_write(0x2000228c, b'\x01'); mu.mem_write(0x20002001, b'\x00'); mu.mem_write(0x20002002, b'\x07')   # connected, stock went slow
for _ in range(5): tap()
ok['5 taps -> mouse'] = mode() == 1 and any(w == 'MOUSE' for w, _ in draws)
ticks(40)                                            # neutral
ok['fast BLE interval requested once (stock 0x1000f214), kept'] = fastreq['n'] == 1 and mu.mem_read(0x20002001, 2) == b'\x01\x00'
mu.mem_write(0x20002002, b'\x15'); ticks(1)                  # stock periodic count would be past 20 ...
call(0x100112cc)                                            # ... real stock check: must NOT ask for the slow link
ok['real stock periodic check sees the fast link held'] = mu.reg_read(UC_ARM_REG_R0) == 0 and mu.mem_read(0x20002001, 1) == b'\x01'
r0 = len(reports)
for i in range(1, 26): st['pitch'] = 0.35 * i / 25; ticks(1)   # tilt 20 deg in 0.5 s
ticks(15)
moved = reports[r0:]
ok['tilt moves cursor'] = sum(dy for _, _, dy in moved) > 30 and all(dx == 0 for _, dx, _ in moved)
r1 = len(reports); ticks(50)
ok['pointer: held tilt = still (no drift)'] = len(reports) == r1
for i in range(1, 26): st['pitch'] = 0.35 - 0.35 * i / 25; ticks(1)
ticks(15)
ok['tilt back moves back'] = sum(dy for _, _, dy in reports[r1:]) < -30
r1 = len(reports); ticks(20)
ok['level = still'] = len(reports) == r1
tap()
ok['tap = click'] = [b for b, _, _ in reports[r1:]] == [1, 0]
r2 = len(reports); st['touch'] = 1; ticks(45); st['touch'] = 0; ticks(5)
ok['hold still = right click'] = [b for b, _, _ in reports[r2:]] == [2, 0]
mu.mem_write(0x20001000, bytes.fromhex('ab 01 00 08 00 00 7a 0a 00 32 32 04 08 23')); call(syms['hook_cmd'], 0x20001000, 14)
ticks(5); r3 = len(reports); st['pitch'] = 0.35; ticks(30); n1 = len(reports); ticks(30)
ok['joystick style: held tilt keeps moving'] = len(reports) > n1 > r3
st['pitch'] = 0.0; ticks(10)
for _ in range(5): tap()
ok['5 taps -> clock'] = mode() == 0
st['touch'] = 1; ticks(105); st['touch'] = 0; ticks(5)
ok['hold 2 s -> heart'] = mode() == 2 and mu.mem_read(0x200022b4, 1)[0] == 8
st['touch'] = 1; ticks(105); st['touch'] = 0; ticks(5)
ok['hold 2 s again -> clock'] = mode() == 0
# build 16 keys mode: edges stamped, fast link, no HID, no shortcuts, drop after 2 s unlinked
mu.mem_write(0x20002001, b'\x00'); mu.mem_write(0x20002002, b'\x09'); fq0 = fastreq['n']
mu.mem_write(0x20001000, bytes.fromhex('ab 01 00 04 00 00 7a 09 00 03')); call(syms['hook_cmd'], 0x20001000, 10); ticks(5)
ok['7A 09 3 -> keys mode, "KEYS" banner'] = mode() == 3 and any('KEYS' in w for w, _ in draws)
ok['keys: fast link requested once and held'] = fastreq['n'] == fq0 + 1 and mu.mem_read(0x20002001, 2) == b'\x01\x00'
f0 = len(frames); r0 = len(reports)
st['touch'] = 1; ticks(1)
tf = [f for i, f in frames[f0:] if i == 0x7a15]
ok['keys: press sent on the first tick with a tick stamp'] = len(tf) == 1 and tf[0][0] == 1 and len(tf[0]) == 3
ticks(2); st['touch'] = 0; ticks(2)
tf = [f for i, f in frames[f0:] if i == 0x7a15]
ok['keys: short press = TAP, stamps 3 ticks apart'] = len(tf) == 2 and tf[1][0] == 0 and ((tf[1][1] | tf[1][2] << 8) - (tf[0][1] | tf[0][2] << 8)) & 0xffff == 3
for _ in range(6): tap()
st['touch'] = 1; ticks(130); st['touch'] = 0; ticks(3)
ok['keys: 6 taps and a 2.6 s hold change nothing'] = mode() == 3 and mu.mem_read(0x200022b4, 1)[0] != 8
ok['keys: no HID reports'] = len(reports) == r0
mu.mem_write(0x20002002, b'\x19'); ticks(1); call(0x100112cc)
ok['keys: real stock periodic check sees the fast link held'] = mu.reg_read(UC_ARM_REG_R0) == 0 and mu.mem_read(0x20002001, 1) == b'\x01'
mu.mem_write(0x2000228c, b'\x00'); ticks(105)
ok['keys: 2 s without a link -> clock'] = mode() == 0
mu.mem_write(0x2000228c, b'\x01'); ticks(5)
icount['n'] = 0; ticks(100)
print('instructions per tick (clock mode):', icount['n'] // 100)
for k, v in ok.items(): print(('PASS ' if v else 'FAIL ') + k)
print('stock writes into our RAM:', bad if bad else 'none')
print('our writes outside allowed RAM:', sorted(set(wild))[:8] if wild else 'none')
print('RESULT:', 'PASS' if all(ok.values()) and not bad and not wild else 'FAIL')
