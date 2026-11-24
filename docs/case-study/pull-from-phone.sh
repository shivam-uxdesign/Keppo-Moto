#!/usr/bin/env bash
# Real numbers for the case study, straight from the phone over USB, with no in-app export.
#
# 1. Install the unlocked test build once (same key, so your rides and clips stay):
#      bash docs/case-study/pull-from-phone.sh install path/to/keppo-moto-unlocked.apk
# 2. Pull the database and make the stats:
#      bash docs/case-study/pull-from-phone.sh
#
# Writes docs/case-study/captures/ridetrack.db (git-ignored, stays on this computer) and
# docs/case-study/captures/case-study-stats.json (no places in it; that's the file to share).
set -euo pipefail
cd "$(dirname "$0")"
PKG=com.keppo.moto
OUT=captures; mkdir -p "$OUT"
command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools."; exit 1; }
[ "$(adb devices | grep -c 'device$')" -ge 1 ] || { echo "No phone found. Plug it in and allow USB debugging."; exit 1; }

if [ "${1:-}" = "install" ]; then
  [ -f "${2:-}" ] || { echo "Give the APK path: install path/to/apk"; exit 1; }
  adb install -r "$2"
  echo "Installed. Open Keppo Moto once, check your rides are there, then run this script again without arguments."
  exit 0
fi

if ! adb shell run-as "$PKG" true 2>/dev/null; then
  echo "This build of Keppo Moto doesn't allow reading its data. Install the unlocked build first:"
  echo "  bash docs/case-study/pull-from-phone.sh install path/to/keppo-moto-unlocked.apk"
  exit 1
fi

# Close the app so the database is written out, then copy it with its journal files.
adb shell am force-stop "$PKG" || true
for f in ridetrack.db ridetrack.db-wal ridetrack.db-shm; do
  if adb shell run-as "$PKG" ls "databases/$f" >/dev/null 2>&1; then
    adb exec-out run-as "$PKG" cat "databases/$f" > "$OUT/$f"
    echo "copied $f ($(wc -c < "$OUT/$f") bytes)"
  fi
done
python3 ride-stats.py "$OUT/ridetrack.db"
echo
echo "Done: $OUT/case-study-stats.json"
