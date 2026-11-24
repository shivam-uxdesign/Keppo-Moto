#!/usr/bin/env bash
# Phone captures for the case study. Walks the shot list one by one: set the screen up on the
# phone, press Enter, and it's saved with the right name in docs/case-study/captures/.
#
# Needs: adb (Android platform-tools), USB debugging on, the phone plugged in and allowed.
#   Check with:  adb devices
# Run from the repo:  bash docs/case-study/capture.sh          (everything)
#                     bash docs/case-study/capture.sh 17 R2    (just these)
# At each prompt: Enter = capture, s = skip, q = quit. Re-running overwrites a shot.
# Blur nothing here: contacts, numbers and "Home" get hidden before anything is published.
set -euo pipefail
cd "$(dirname "$0")"
OUT=captures; mkdir -p "$OUT"
command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first."; exit 1; }
[ "$(adb devices | grep -c 'device$')" -ge 1 ] || { echo "No phone found. Plug it in and allow USB debugging."; exit 1; }

# id | file name | what to put on screen
SHOTS=(
  "02|02-live-ride|The live ride screen while riding (demo ride is fine)"
  "03|03-hud-over-app|The floating HUD over Maps or a music app"
  "04|04-ride-summary|The summary right after a ride ends"
  "05|05-ride-detail|Ride detail: map and charts (open the 3D replay for 05b next)"
  "05b|05b-replay|The 3D replay playing"
  "06|06-rides-list|The rides list"
  "06b|06b-rides-map|The all-rides map"
  "07|07-profile|Profile, main page"
  "08|08-filming|Filming a moment: the HUD or viewfinder while REC"
  "10|10-studio-editor|Studio editor on a Reel"
  "11|11-ask-all|Ask across all rides, with an answer"
  "13|13-safety-settings|Safety settings (contacts get blurred later)"
  "14|14-slide-keep-moving|Slide to ride with Keep moving on, as Where to? opens"
  "15|15-where-to|Where to? with saved places and a search typed"
  "16a|16a-plan-normal|Plan screen, Normal"
  "16b|16b-plan-less-traffic|Plan screen, Less traffic"
  "16c|16c-plan-no-foot-down|Plan screen, No foot down"
  "17|17-directions-dock|Directions screen, the new dock, riding"
  "17b|17b-directions-filming|Directions while filming"
  "17c|17c-directions-jam|Directions with the amber jam warning (if you catch it)"
  "17d|17d-directions-offer|Directions with the clearer-road offer (if you catch it)"
  "18|18-top-bar|The Keep moving top bar on another screen during directions"
  "19|19-your-roads|Profile > Keep moving > Your roads"
  "20|20-widget-tile|Home screen with the widget, or Quick Settings with the tile"
)
# id | file name | seconds | what to do
RECS=(
  "R1|R1-slide-to-ride|15|Slide to ride, then the live ride screen"
  "R2|R2-keep-moving|30|Keep moving end to end: slide, Where to?, pick, plan, Start, directions"
  "R3|R3-riding-directions|25|Riding with directions, ideally a warning or offer appearing"
  "R4|R4-reel|25|A finished Reel playing"
  "R5|R5-hud-over-app|20|The floating HUD over another app while riding"
)

PICK=" $* "
want() { [ "$PICK" = "  " ] || case "$PICK" in *" $1 "*) true;; *) false;; esac; }

ask() { local a; read -r -p "$1 [Enter/s/q] " a </dev/tty; case "$a" in q) echo "Stopped."; exit 0;; s) return 1;; *) return 0;; esac; }

for row in "${SHOTS[@]}"; do
  IFS='|' read -r id name what <<<"$row"
  want "$id" || continue
  echo; echo "[$id] $what"
  ask "Ready?" || { echo "  skipped"; continue; }
  adb exec-out screencap -p > "$OUT/$name.png" && echo "  saved $OUT/$name.png"
done

for row in "${RECS[@]}"; do
  IFS='|' read -r id name secs what <<<"$row"
  want "$id" || continue
  echo; echo "[$id] $what  ($secs s)"
  ask "Start recording?" || { echo "  skipped"; continue; }
  adb shell rm -f "/sdcard/$name.mp4" >/dev/null 2>&1 || true
  echo "  recording for $secs s..."
  adb shell screenrecord --time-limit "$secs" --bit-rate 8000000 "/sdcard/$name.mp4" || true
  sleep 1; adb pull "/sdcard/$name.mp4" "$OUT/$name.mp4" >/dev/null && adb shell rm -f "/sdcard/$name.mp4" && echo "  saved $OUT/$name.mp4"
done

echo; echo "Done. Zip docs/case-study/captures and send it in the chat. (It is git-ignored, so nothing personal gets committed.)"
