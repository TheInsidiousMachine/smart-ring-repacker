# Roadmap

Ideas, not promises. None of these is verified; each is small enough to do in a PR. Order is rough value for effort.

1. **Persist API settings** (enabled, port, token, LAN) and offer "start API on launch", so integrations survive an app restart. Security trade-off: it would re-expose a LAN service after reboot, so make it an explicit opt-in.
2. **Capability-aware UI and a ring picker.** Hide/disable controls from `RingController.capabilities`; choose among rings; support more than one ring. Prerequisite for new rings to feel native.
3. **More rings.** Colmi R02-R09 battery/clock/find/HR from public docs ([ADDING_A_RING.md](ADDING_A_RING.md)); needs someone with the hardware. Touch-capable rings are the interesting ones for button-style input.
4. **Haptic replies.** A `POST /v1/haptic {pattern}` that buzzes the ring (silent acknowledgements). The stock "find device" path uses the motor, but the motor GPIO is not identified (one candidate pin is also driven by the power-off routine), so it needs a careful live check.
5. **Yes / no / pick answers.** A firmware question mode where tap = yes, hold = no, 2-4 taps choose an option, plus `POST /v1/ask` and an `answer` event.
6. **Push-to-talk action.** A gesture action that starts and stops dictation on the phone and hands off through the API.
7. **PC presence lock.** `pc/ring_bridge.py` already watches the WebSocket; lock the screen when the ring disconnects.
8. **Heart mode on real data.** Record PPG next to a reference pulse and retune the detector offline (model -> C -> emulator) before touching the ring.
9. **Measure battery cost** of the ~99 Hz accelerometer and 50 Hz timer.
10. **Recovery research.** Identify the SoC; find out whether a debug port exists; test ROM fallback with a sacrificial ring. This is what would let us drop the "you can brick your ring" banner.
11. **Reproducible firmware builds** (pinned toolchain container) so image hashes match across machines and the allowlist can carry real hashes.

Not planned: step, sleep or SpO2 tracking (removed by choice); an air mouse driven by hand translation (an accelerometer without a gyro cannot do it reliably).
