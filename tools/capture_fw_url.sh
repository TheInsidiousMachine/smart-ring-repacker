#!/usr/bin/env bash
# Passive capture of the stock app's firmware-check traffic (read-only, headless).
# Usage: tools/capture_fw_url.sh [seconds=120]
# While it runs, YOU open RWFit -> Device -> Firmware update (do NOT press update).
# Note: this clears the phone's logcat buffer first (adb logcat -c) so only new lines are kept.
# The log goes to captures/ (git-ignored: it can contain your ring's address, never commit it).
set -euo pipefail
cd "$(dirname "$0")/.."
secs=${1:-120}
mkdir -p captures
out="captures/fw_check_$(date +%Y%m%d_%H%M%S).log"
adb get-state >/dev/null
adb logcat -c
echo "Capturing ${secs}s to $out — open RWFit's firmware page now (don't press update)."
timeout "$secs" adb logcat -v time > "$out" || true
echo "--- firmware-related lines ---"
grep -a -E 'getOtaUpgradeInfo|downloadUrl|initView|FirmwareInfoBean|tDeviceModel|onAttachFirmware|toVersion|fromVersion|analysisJLData 2 4 ' "$out" | head -50 || echo "(none found)"
