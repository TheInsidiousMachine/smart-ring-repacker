# Contributing

Welcome. Good first contributions: support for another ring ([docs/ADDING_A_RING.md](docs/ADDING_A_RING.md)), making the UI honour `RingCapability`, a ring picker, API settings persistence, documentation fixes.

## Ground rules

1. **Your own devices only.** No code or reports aimed at other people's rings or phones.
2. **No vendor artifacts.** Never commit vendor apps/APKs, decompiled vendor source, firmware images or images built from them, vendor native libraries, captures containing device addresses, API tokens, keys. See [THIRD_PARTY.md](THIRD_PARTY.md).
3. **Label what you verified.** Protocol or behaviour claims are *verified on hardware* (say which ring/firmware), *tested* (unit/host/emulator) or *unverified*. Public write-ups are not verification.
4. **Firmware is high risk.** Read [docs/FIRMWARE.md](docs/FIRMWARE.md). Firmware changes need host tests and the emulator checks; never flash from CI or an automated agent; do not weaken the hash allowlist, the typed confirmation, the verify-before-reset rule, or the builder's hash/patch/range assertions.
5. **The seam is an API.** `core/RingController.kt`, `core/GestureStudio.kt`, the event JSON in [docs/API.md](docs/API.md) and `docs/fixtures/ring_events.jsonl` are used by other projects. Change them deliberately and update docs and tests in the same PR.

## Workflow

```bash
git clone https://github.com/TheInsidiousMachine/smart-ring-repacker && cd smart-ring-repacker
tools/check_all.sh            # must pass; add --fw when touching fw/
```

- Branch from `main`, keep PRs focused, describe what you tested and on which hardware.
- Add tests with code: pure logic in `ui/logic`, `gestures`, `testbed/RingProtocol`; BLE drivers behind a fake transport; firmware logic in `test_ring_app.c` plus the Python model/emulator where relevant.
- Match the surrounding style (Kotlin official style, no wildcard imports in new code, small functions). Python: standard library first, type hints where cheap.
- Docs: tight and accurate. If you cannot verify a sentence, mark it unverified or leave it out.
- Commits: short imperative subject. By contributing you agree your work is released under the MIT licence.

## Reporting problems

Use the issue templates (bug, new ring support). Remove your ring's MAC address and any API token from logs. Security issues: [SECURITY.md](SECURITY.md).

## For AI coding agents

Start at [AGENTS.md](AGENTS.md).
