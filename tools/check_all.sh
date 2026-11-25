#!/usr/bin/env bash
# One gate for every change (run before any merge / flash).
#   tools/check_all.sh              Python tests + Android unit tests + debug build
#   tools/check_all.sh --fw         + firmware host C tests; and, if you supplied the vendor image,
#                                     the image build and the Unicorn emulator checks
#   tools/check_all.sh --no-android skip the Android part (any combination of flags works)
#
# Needs: python3 + pytest; for Android: JDK 17 or 21 (JAVA_HOME) and ANDROID_HOME;
# for --fw: gcc; for the image steps: arm-none-eabi-gcc (or ARM_GCC_PREFIX), `pip install unicorn`,
# and the vendor stock image (see docs/FIRMWARE.md). Missing image/toolchain => those steps are SKIPPED, not failed.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FW=0; ANDROID=1
for a in "$@"; do
  case "$a" in
    --fw) FW=1 ;;
    --no-android) ANDROID=0 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

echo "== python tests"
(cd "$ROOT" && python3 -m pytest -q tests/)

if [[ $ANDROID == 1 ]]; then
  echo "== android unit tests + debug build"
  (cd "$ROOT/android" && ./gradlew -q testDebugUnitTest assembleDebug)
  for f in "$ROOT"/android/app/build/test-results/testDebugUnitTest/*.xml; do
    grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"
  done
fi

if [[ $FW == 1 ]]; then
  cd "$ROOT/fw/ring_app"
  T=$(mktemp -d)
  echo "== firmware host tests (no vendor image needed)"
  gcc -DHOST_TEST -O1 -Wall -Wextra -Werror -o "$T/t" test_ring_app.c -lm && "$T/t" | tail -1
  (cd "$ROOT/fw/mouse" && make -s test OUT="$T/mouse" | tail -1)

  STOCK=${SY01_STOCK_IMAGE:-$ROOT/firmware/SY01_30330000_V2.1.4_stock.bin}
  G=${ARM_GCC_PREFIX:-arm-none-eabi-}
  if [[ ! -f "$STOCK" ]]; then
    echo "== SKIP image build + emulator: vendor stock image not found at $STOCK (see docs/FIRMWARE.md)"
  elif ! command -v "${G}gcc" >/dev/null 2>&1; then
    echo "== SKIP image build + emulator: ${G}gcc not found (install the Arm GNU toolchain or set ARM_GCC_PREFIX)"
  else
    echo "== firmware image (hash-checked against the known-good stock image)"
    python3 build_image.py "$STOCK" | tail -2
    IMG=$(ls -t build/SY01*custom*.bin | head -1)
    ${G}nm build/app.elf > build/syms.txt
    PY=${EMU_PY:-python3}
    if "$PY" -c 'import unicorn' 2>/dev/null; then
      echo "== emulator (real image)"
      "$PY" emu/emu_gesture.py "$IMG" build/syms.txt testdata | tail -1
      "$PY" emu/emu_modes.py "$IMG" build/syms.txt | tail -1
      "$PY" emu/emu_touch.py "$IMG" build/syms.txt | tail -1
    else
      echo "== SKIP emulator: python module 'unicorn' missing (pip install unicorn, or set EMU_PY)"
    fi
  fi
fi
echo "ALL CHECKS PASSED"
