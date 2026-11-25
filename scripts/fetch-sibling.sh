#!/usr/bin/env bash
# Clone the sibling repo (gaze-keyboard) next to this one if it is missing. Idempotent; never overwrites.
#   scripts/fetch-sibling.sh
# Result: ../gaze-keyboard
set -euo pipefail
URL=${SIBLING_URL:-https://github.com/TheInsidiousMachine/gaze-keyboard}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST=$(dirname "$ROOT")/gaze-keyboard
if [[ -e "$DEST" ]]; then
  echo "already present: $DEST (left untouched; update it yourself with 'git -C $DEST pull')"
  exit 0
fi
if ! command -v git >/dev/null 2>&1; then echo "git is required" >&2; exit 1; fi
git clone "$URL" "$DEST"
echo "cloned $URL -> $DEST"
echo "read first: README.md, AGENTS.md, docs/INPUT_SOURCES.md, docs/INTEGRATION.md"
