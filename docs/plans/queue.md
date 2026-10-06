# Keppo Moto: approved, waiting to build

Approved by the rider. **Don't build until the rider says so.** When they do, build these together, one commit each, then one APK (`keppo-moto-<sha>.apk`).

Already built and pushed, not yet in an APK: the compact Last ride card on Home (6160212).

---

## 1. Rides map zooms to where you are

- **Today:** Rides › Map opens fitted to every ride (`RidesMapScreen`, `newLatLngBounds(bounds, 96)`).
- **Change:**
  - Open on all routes as now, then animate in to the rider's current location, about town level (zoom ~13).
  - Show a blue dot for where the rider is (MapLibre location component, or our own marker from the last known fix).
  - Add a small "my location" button to jump back after panning.
  - Location off or not allowed: stay on all routes, with no prompt.
- **Location fix:** use the last known location straight away, then one fresh fix. Don't keep GPS running on this screen.

## 2. A route colour per bike

- **Model:**
  - `Bike.routeColor` (ARGB Int, nullable). Null means the default by garage order: the first bike keeps today's teal (`primary`), the next ones take the next colours from an 8-colour palette that's easy to tell apart on the dark map.
  - Room migration 10 → 11.
  - Garage backup (`BackupFormat`): write and read the field; older backups read as null.
- **Choosing:** Bike › Edit gets a row of 8 swatches. A tap selects; the selected swatch shows a ring.
- **Where it shows:**
  - routes on Rides › Map
  - route thumbnails in the Rides list
  - the Last ride card on Home
  - a small dot next to the bike name on the Bike tab
- **Not used:** the ride page and share images. They stay coloured by speed.
- **Bike chips on the map (approved):**
  - A row at the top of Rides › Map: "All bikes", then one chip per bike with its colour dot.
  - A tap shows only that bike's rides, and the map zooms to fit them.
  - "All bikes" is the default.
  - Hidden with only one bike.
- **Tests:** default colour by order; migration; backup round trip.

## 3. Pop-up HUD settings: mic meter in the preview

- **Bug:** the preview on Profile › Pop-up HUD uses fixed sample data (`previewData` in `HudSettingsScreen`) with `voiceOn = false`. So the mic level meter never shows there, even with "Start filming when I speak" on. The real pop-up during a ride is fine.
- **Fix:**
  - When `settings.moments.voice` is on, the preview passes `voiceOn = true`, the rider's own `voiceThresholdDb`, and a sample `micLevelDb`.
  - The sample level moves gently (a slow loop), sometimes crossing the threshold so `speaking` lights the bar.
  - When voice filming is off, the preview stays as it is now.
- Settings screen only; no change to the pop-up itself.

## 4. "Film when I speak": cut wind and background noise (no voice model this build)

Ride of 6 Oct (export 6): 13 voice videos in an 11-min ride, at every speed including stopped; more than half the ride filmed. The rider wants it fully automatic: no buttons, no wake word.

- **Voice range only:** replace the 150 Hz high-pass in `AudioEncoder.levelDb` with a 300–3,400 Hz band-pass. Wind and engine rumble sit mostly below 500 Hz.
- **Background tracking:** a running estimate of the background level. It falls fast and rises slowly (seconds), so wind at speed becomes the background and talking doesn't get absorbed into it. A chunk counts as loud when it is at least the margin above the background:
  - Sensitivity Low +12 dB / Medium +9 dB / High +6 dB
  - This replaces today's fixed dBFS threshold setting.
- **Duration (rider's rule):** film only after about **1.5 s of sustained loudness**, so breathing and short horn taps don't trigger.
  - Speech has short gaps between words, so allow dips of up to ~250 ms, and require ≥75 % of the last 1.5 s above the margin.
  - Configurable later between 1 and 2 s.
  - Change `SpeechGate` (window/minAbove) and its tests.
- **Long horns (to discuss):** a steady horn of 1–2 s passes the duration rule. A cheap optional check: speech level fluctuates syllable by syllable, a horn is steady. Waiting for the logs before deciding.
- **No voice detector (Silero / WebRTC VAD) this build:** try without it first.
- **Logging in moments-log.txt:**
  - Every 5 s: `mic: level · background · margin · speed`
  - Each trigger: `voice start: level · background (+margin) · sustained s · speed`
  - Speaking stopped: one line
- **Meter:** shows level above background, with a tick at the margin. Same in the pop-up, settings and the HUD preview.
- **Also approved (earlier):**
  - Keep listening while stopped, paused or on a break (not off the mount). While stopped, the camera wakes on speech: no look-back, about 1 s to start.
  - Moments toggle on Home above the slide card. Still to answer: Moments only, or also "Film when I speak".
- **After the build:** one ride, the rider sends the export, then tune margins and times from the logged levels.
