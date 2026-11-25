import sys
sys.argv = sys.argv[:3] + ['--fake-acc']
src = open(__file__.replace('emu_modes.py', 'emu_recorder.py')).read()
src = src[:src.index('cmd(next(')]
exec(src)
import struct
# heart on -> ticks -> off
cmd('ab 01 00 04 d8 28 7a 03 00 01')
print('heart ON : glue', bytes(mu.mem_read(G, 6)).hex(' '), 'timer', mu.mem_read(0x2000681c, 1)[0], 'meas mode', mu.mem_read(0x200022b4, 1)[0])
for _ in range(50): call(syms['app_tick'])
print('after 1 s: glue', bytes(mu.mem_read(G, 6)).hex(' '), 'idle pinned', struct.unpack('<H', mu.mem_read(0x200024d0, 2))[0])
cmd('ab 01 00 04 18 e9 7a 03 00 00')
print('heart OFF: glue', bytes(mu.mem_read(G, 6)).hex(' '), 'timer', mu.mem_read(0x2000681c, 1)[0])
# clicker: HID mode 1, stock drain calls hook_accel
mu.mem_write(0x2000230f, b'\x01')
call(syms['hook_accel'], 0, 0, 2048)
for _ in range(5): call(syms['app_tick'])
print('clicker  : glue', bytes(mu.mem_read(G, 6)).hex(' '), 'timer', mu.mem_read(0x2000681c, 1)[0])
print('stock writes into our RAM:', bad if bad else 'none')
