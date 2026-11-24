# Case study: the phone part (run on the computer with the phone on USB)

For a Claude Code session on the rider's computer, in their clone of Keppo-Moto, with the
phone plugged in and USB debugging allowed. The cloud session built the case study; this
session gets what only the phone has.

## 0. Setup
- `git fetch origin claude/modest-meitner-d5zidp && git checkout claude/modest-meitner-d5zidp && git pull`
- `adb devices` must list the phone as `device`. Python 3 must be installed.
- The unlocked APK (`keppo-moto-unlocked.apk`) was sent in the cloud chat; it should be in the rider's Downloads.

## 1. Real numbers (about a minute)
1. `bash docs/case-study/pull-from-phone.sh install ~/Downloads/keppo-moto-unlocked.apk`
   - Same signing key and a higher version, so rides, clips and settings stay.
   - If Android refuses with a signature or version error, stop and tell the rider. Never uninstall: that deletes their rides.
2. Ask the rider to open Keppo Moto once and confirm their rides are there.
3. `bash docs/case-study/pull-from-phone.sh`
4. Show the rider the totals it prints. Then copy `docs/case-study/captures/case-study-stats.json`
   to `docs/case-study/data/case-study-stats.json` and commit it (it holds no places; the
   database stays git-ignored in `captures/`).

## 2. Captures (with the rider moving through the app)
- `bash docs/case-study/capture.sh 04 17 17b 17c 17d 05 08 R2 R4 R5` (each prompt: the rider sets up the screen, then Enter).
- Shot 17 and R2 matter most. A demo ride is fine where a ride must be running.
- Check every capture for personal details (contact names and numbers, a place called Home,
  the home location on a map). Ask the rider before keeping any capture that shows one.
- Convert what the rider approves into `docs/case-study/media/`:
  screenshots to 780-px-wide WebP (`cwebp -q 82 -resize 780 0 in.png -o out.webp`, or Pillow),
  recordings to H.264 MP4 under 8 MB (`ffmpeg -i in.mp4 -vf scale=720:-2 -an -c:v libx264 -crf 28 -movflags +faststart out.mp4`).
  Keep the shot-list names (e.g. `17-directions-dock.webp`, `R2-keep-moving.mp4`).

## 3. Also from the rider (ask, don't guess)
- P1 hero video (phone on the bike mount while riding), P2 bike photo, P3 riding photo, if they have them on the computer: same conversion, into `media/`.
- Two or three sentences on why they ride and why they built this: into `docs/case-study/data/rider-notes.md`.
- Any crash-alert false alarm, ever (yes/no), and one line of beta feedback with a first name or initials if allowed: same file.

## 4. Hand back
Commit `docs/case-study/data/` and `docs/case-study/media/` on `claude/modest-meitner-d5zidp`, push, and
tell the rider to go back to the cloud session and say "captures pushed".
Never commit `captures/`, the database, APKs or anything with personal details.
