# Emulator checks (run before every flash)

Needs your own vendor stock image (see docs/FIRMWARE.md), `python3 build_image.py <image>` first, and `pip install unicorn`. `tools/check_all.sh --fw` runs `emu_gesture.py`, `emu_modes.py` and `emu_touch.py` when the image is present; run `emu_recorder.py` by hand.

These run the **real patched image** under Unicorn: our cave code plus the real stock timer, drain and accel-read code.
Only I2C transfers, delays and the BLE frame sender are stubbed. Any write by stock code into our RAM regions is flagged.

```
G=${ARM_GCC_PREFIX:-arm-none-eabi-}   # the Arm GNU toolchain prefix
${G}nm build/app.elf > build/syms.txt
python emu/emu_recorder.py build/<image>.bin build/syms.txt --fake-acc   # expect ~100 samples / 2 s, seq continuous
python emu/emu_modes.py    build/<image>.bin build/syms.txt              # heart + clicker, expect "none"
python emu/emu_touch.py    build/<image>.bin build/syms.txt              # touch, modes, tilt mouse
```

These checks were added after builds 5–7b, whose recorder flag sat inside our own timer struct and was wiped on every timer start.
The host tests used a fake timer that didn't write its struct, so they missed it. The fake timer now writes the same bytes as the real one.

Build 10+: gesture detector on the real recordings (boot on garbage RAM, replay at ~100 Hz, I2C register file):
```
python emu/emu_gesture.py build/<image>.bin build/syms.txt testdata   # expect target 10, others 0, RESULT: PASS
```
`testdata/*.txt` are the 2026-11-19 fast recordings (raw>>2, gaps interpolated) exported by `model/sm.py`; the C detector must match the model.
