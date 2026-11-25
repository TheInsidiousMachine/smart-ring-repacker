#!/usr/bin/env bash
# Passive capture of the stock app's firmware-check traffic (read-only, headless).
# Usage: tools/capture_fw_url.sh [seconds=120]
# While it runs, YOU open RWFit -> Device -> Firmware update (do NOT press update).
set -euo pipefail
secs=${1:-120}
out="captures/fw_check_$(date +%Y%m%d_%H%M%S).log"
adb get-state >/dev/null
adb logcat -c
echo "Capturing ${secs}s to $out — open RWFit's firmware page now (don't press update)."
timeout "$secs" adb logcat -v time > "$out" || true
echo "--- firmware-related lines ---"
grep -a -E 'getOtaUpgradeInfo|downloadUrl|initView|FirmwareInfoBean|tDeviceModel|onAttachFirmware|toVersion|fromVersion|analysisJLData 2 4 ' "$out" | head -50 || echo "(none found)"
