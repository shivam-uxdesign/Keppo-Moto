# Keppo Moto: approved, waiting to build

**Built** (7 Oct batch): compact Last ride card 6160212, voice trigger a751f3a, Home switches, HUD preview 7b0c4e4, map and route colours 50687cc. Notes on what changed while building are at the end.

New items go below the line at the end; don't build them until the rider says so.

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

---

## As built (7 Oct)
- **Voice start rule:** 60 % of the last 1.5 s loud, not 75 %. Normal speech is loud about 60–70 % of the time, so 75 % missed real talking in tests. Breathing (0.6 s on / 0.6 s off) and a 0.8 s horn still don't start it. Once speaking, 15 % of the last 1.5 s loud keeps it going, so slow talk isn't cut off.
- **Background:** the 10th percentile of the last 4 s.
- **Home switches:** both "Moments" and "Film when I speak" (the second shows only while Moments is on). Turning Moments on asks for camera and mic if needed.
- **Rides list:** its thumbnails are route-on-map pictures coloured by speed, so the bike colour shows as a dot by the bike name. The plain route sketch, shown while the map picture is made, uses the bike colour.

---

# Next batch (7 Oct): A–E and the battery estimate BUILT (190acf0, 9495f81, f598338, battery); fuel log (2nd commit after f4d8d25) and transcripts (f4d8d25) also BUILT

From the Tuesday Evening Ride export (27½ min, 73 moments, 41 of them with no speech; the process died at 12:44:44).

## A. Ride survives the app dying
- **Record why the app last stopped:** on launch, read `ActivityManager.getHistoricalProcessExitReasons` (crash + stack trace, low memory, killed by the system, …). Write it to the moments log and the export.
- **Auto-continue:** if the process dies mid-ride while the phone stays on, the recording service restarts itself and continues the *same* ride (a few seconds' gap; no second ride, nothing to merge).
- **Auto-save:** an unfinished ride is saved properly on the next launch (no more "save it from the ride page").
- **Phone switched off (battery died):** it can't record while off, and Android doesn't let a camera/location service start by itself after a reboot. On the next launch the ride is auto-saved. If the phone is back within 30 min, ask "Continue Tuesday Evening Ride?"; continuing merges into the same ride, with the gap shown as a break.
- **No low-battery cut-off** (the rider charges on the bike): recording never stops because of battery.

## B. Silero voice detector for "Film when I speak"
- **Why:** 26 of the 41 no-speech triggers were below 20 km/h. Traffic, horns and engines overlap the voice in both loudness (+3–29 dB vs speech +7–35 dB) and length, so loudness and duration can't separate them.
- **How:** Silero VAD (ONNX), on-device, run live on the mic audio while recording, resampled to 16 kHz in 32 ms windows. Filming needs loud-above-background **and** voice probability ≥ ~0.6 for the 1.5 s rule.
- **Cost:** about +6–8 MB APK (onnxruntime + 2 MB model). About 1 ms of CPU per 32 ms of audio, roughly 3 % of one core, so about 2 min of CPU spread over a 1-hour ride. Nothing is processed after the ride.
- **Logging:** voice probability on the voice start lines.

## C. No overlapping videos
- A new video's look-back stops where the previous video ended.

## D. Fixes found in the log
- The `mic:` line every 5 s never logged: `lastMicLog = Long.MIN_VALUE` overflows in `now - last`. Start at 0.

## E. One video, not a hand-off (approved in principle)
- **Seen 12:24 on 6 Oct:** a voice video ended at 12:24:04.344 and a GPS-lost video started 1 ms later. When GPS came back at 12:24:37, that video ended and a new voice video started at once.
- **Fix:** one video keeps running while *any* reason wants filming (event chain, voice, GPS lost). Reasons join the running video and add their types (e.g. `VOICE,GPS_LOST`), instead of stopping it and starting another.

## Under discussion (not approved yet)
- **Mileage (km/l):** no fuel data today. Proposal: a fuel log on the Bike tab (litres, price, full tank?), with the odometer from rides. That gives km/l between full fills, cost per km, and an estimate per ride.
- **Transcribe moments into Keppo Journal:** speech-to-text of clip audio after the ride (on device, e.g. Whisper), saved as `moments[].transcript` in ride.json (contract addition), shown by the journal. Open questions: language (English / Hindi / mixed), on-device vs cloud.
- **Battery estimate (proposed, waiting for the rider's OK on the details):** the exports have no battery data today. Log battery %, charging state and temperature at ride start and every minute (moments log and export). During a ride, measure the drain from the last ~10 min; for the first minutes, use the average of past rides with the same settings. When not charging and below 30 %, show "With these settings your phone will last about 35 min. Charge before then." on the pop-up, the live screen and the notification, again at 15 %, and whenever the estimate drops below 20 min. Also warn when it's charging but still draining (a weak bike charger). Information only; nothing is ever stopped. Also shown on Home's readiness line before a ride.
- **Sensitivity (rider, 7 Oct):** Medium and High are useless on a bike (engine noise always passes). Proposal for this build: drop them. Keep **Normal** (+12 dB, today's Low) and add **Strict** (+16 dB), default Strict, together with the Silero voice check. Settings stored as Medium/High read as Strict.
- **Fuel stops from the map (discussion):** stops of 1–15 min are checked against OpenStreetMap fuel stations (`amenity=fuel`, Overpass API, within ~60 m), after the ride or when online. This sends only the stop's coordinates. Then: "Filled up at Indian Oil, Sector 12?", and enter litres (or ₹ and price/L). The rider always fills the tank, so km/l = km since the last fill ÷ litres. Feeds the fuel log (mileage).
- **Transcripts language:** English + Hinglish. Proposal: Hinglish in Latin script ("bhai ye road mast hai"). Code-mixed speech needs a stronger model than on-device Whisper base, so online transcription is likely; to decide.
- **Fuel amount from the card SMS (discussion):** the rider pays by credit card and gets an SMS. Options:
  - (a) READ_SMS, reading messages ±15 min around a pump stop. It's a sensitive permission that Play Protect already flagged once, and Play restricts it, so it risks install blocks.
  - (b) Share the SMS to Keppo Moto (long-press → Share), and the app reads the ₹ and the merchant. No permission.
  - (c) Type the ₹ in the pump prompt.
  - Price per litre is remembered from the last fill and confirmed; litres = ₹ ÷ price.
- **Transcripts (decided):** on the phone, Whisper (whisper.cpp), Hinglish in English letters (an initial prompt biases it to Latin script). Runs after the ride in the background, best while charging. Try it and judge the results.
- **Fuel SMS (decided):** (a) READ_SMS. After a pump stop, read bank or card SMS from about 15 min before to 30 min after, and parse the ₹ amount and merchant (fuel names: Indian Oil, HP, BPCL, Shell, Nayara…). (c) Typing the amount is the backup. The rider accepts the Play Protect risk. The permission is asked for only when the fuel log is first used.
- **Transcripts (revisit):** the 60–150 MB model is too big. Free online options to compare:
  - Android's built-in speech recognizer (en-IN): free, nothing added to the app, works offline once Google's language pack is downloaded.
  - Gemini API free tier: can be told "Hinglish in Latin script". Needs a free key, and free-tier data may be used by Google.
  - Groq's free Whisper large-v3.
- **Fuel SMS toggle (decided):** Bike tab › Fuel log: "Read card SMS at petrol pumps", **off by default**. Turning it on asks for the SMS permission; while off, the amount is typed (c), and READ_SMS is never asked for.
- **Transcripts (decided):** Gemini API free tier, after the ride on Wi-Fi, talking (VOICE) clips only. Prompt: transcribe in Hinglish, English letters. The rider creates a free Google AI Studio key and pastes it in Profile (stored on the phone only, never backed up). Saved as `moments[].transcript` (ride.json contract addition), shown in the clip viewer, and needs a Keppo Journal note. Re-check the free-tier terms when building.

---

# Keppo Studio (built 6 Oct, first version; testing on the phone)

Prototype: https://claude.ai/artifact/AhQg2FK3PPZuzsdHbf8qm1 (real route, speeds and moment times from the 6 Oct evening ride; footage, captions and songs simulated).

- **Flow:** new Studio tab, then:
  - Pick a ride, then "Make my video": vibe (Hype / Cinematic / Chill / Vlog, suggested from the ride), music, length 30/45/60.
  - Extras: intro, outro, map between clips.
  - Generating steps, then a full-screen draft with Remix / Song / Edit / Share.
- **Rider's decisions:**
  - Intro and outro can each be turned on or off (both default on).
  - Map transitions are optional (default off).
  - Music: all three: suggested royalty-free tracks, the rider's own song, and no music with a tip to add one in Instagram.
- **Auto edit:**
  - The hook moment first, then the ride in order.
  - Moments scored on speech, speed, lean and events, with variety.
  - Cuts on the beat, never mid-sentence. Music ducks while the rider talks (level adjustable).
  - Word-by-word captions in the vibe's style; speed counter and lean gauge.
  - Stats outro with an optional "made with Keppo Moto" mark.
- **Edit:**
  - Clips: tap a clip to change its caption or length, move it earlier or later, remove it, or swap it for another moment.
  - Caption styles; overlay switches.
- **Share:** Reels / Shorts / WhatsApp / Save, plus a suggested post caption.
- **Later phases:** Gemini as director (order, title, post caption); slow-mo; multi-ride compilations; templates; Keppo Journal.
- **Rider's decisions (7 Oct, after the prototype):**
  - Studio flow approved.
  - The 3D map stays very limited: a 2-second route sketch in the intro and faintly behind the stats.
  - One "Map" switch before creating removes it completely; the intro then puts the title over the first clip.
  - Map transitions: optional, off.
  - Watermark optional.
  - Gemini director: yes. It gets moment times, speed, lean and transcripts (no video), and returns the order, the hook, the title and the post caption. Fallback: on-device scoring.

- **Built (6 Oct, after the UX audit):** audit https://claude.ai/artifact/Qoism9gPNQ9pyDSr7EAqEz; prototype v6 (real clips, clean motion) is the reference for the look.
  - Entry: Share ride › **Reel** (new tab next to Story card and 3D video), "Make a Reel" on Home's just-ridden card (rides with 2+ moments) and on the ride summary. No new bottom tab.
  - Captions: Studio asks Gemini for **timed lines** for the clips likely to have talking (VOICE, filmed on purpose, or with a transcript; at most 12, 4 s apart), saved next to each clip as `<clip>.lines.json` (an empty list = no speech, never asked again). "Skip captions" while it reads; offline or busy → made without captions, with a note.
  - Director: Gemini (text only) scores the bits, picks the hook, the title and the post caption; the app's own scoring is the fallback.
  - Plan (`studio/StudioPlan.kt`, unit-tested): speech split into bits at 1.5 s pauses (max 8 s); silent clips give 4 s around the event; bits never reuse the same seconds (clips filmed back to back overlap); the hook first, then ride order (Vlog: ride order, no hook); only lengths the clips can fill are offered.
  - Video (`studio/StudioRenderer.kt`): Media3 Transformer. Each part of a clip is cropped to 1080×1920 with its camera move (MatrixTransformation), the vibe's grade (contrast, saturation, RGB) and the graphics drawn on top every frame (`studio/StudioArt.kt`, a port of prototype v6). The title and stats play over a clip with its sound off. The rider's own song is a second, looping track that dips to 25 % while they talk (`GainProcessor`).
  - Music: own song or none (no licensed library yet). Cutting on the song's beats: later.
  - Fonts bundled: Anton, Instrument Serif (OFL), Permanent Marker (Apache 2.0); licences in assets/licenses.
- **Also in this build:** a fixed App Check debug token for test builds (`APPCHECK_DEBUG_TOKEN` in local.properties, gitignored), so reinstalling doesn't break transcripts; the transcription job is no longer replaced while running, and a cancelled job isn't shown as an error; App Check refusals get a plain message.
- **Next / not done:** a list of past Studio videos; fit-with-borders for landscape clips; map flights between clips; beat matching; deleting `.lines.json` with its clip.
- **Built 7 Oct (after the Reels research; audit v2 has the research and sources):**
  - Hook first: no 2 s title card before the action. The title becomes a small label on the hook clip, and Gemini writes a short hook line for the first frame (works with sound off). The route-sketch opening becomes an option, off by default.
  - Lengths 15 / 30 / 45 / 60. Stats card 1.5 s. A looping end: the rider's sign-off if there is one, else a cut back to the hook's first frame.
  - One story per Reel: Gemini names the story and picks the clips that tell it; Remix tries another story.
  - The coach: under every finished Reel, 3–5 tips (Gemini, text only: what was said, clips used and skipped, speeds, wind noise, length, how it opens and ends), with a one-tap action where possible (put the title on the hook, make that story, record a voice-over).
  - Voice-over recorded in Studio over any part (the clip's sound dips; captioned like the rest).
  - Clips from other rides: talking lines and riding shots, best first by their saved scores.
  - A shot list for the next ride on Home (from the coach's "film next time" tips); the rider ticks them off (the app can't tell what was filmed). Nothing while riding.
  - Series name on the title ("Evening Ride Diaries · ep 4").

---

# Next batch (planned 7 Oct; build only when the rider says "build")

## 1. Show when Gemini's limit resets (wherever "Gemini is busy" shows)
- A live countdown worked out from the saved reset time (`GeminiQuota`), not text frozen at the time of the error: "Free again at 5:29 AM · in 3 h 12 min".
- Per task, since models run out separately: "Captions free again at 5:29 AM; story and tips at 12:30 PM".
- When the time passes the message disappears and the app retries quietly (no cache clearing).
- Where: Profile › Moments ("Write down what I say" status line); Studio's "Reading what you said" step and the yellow note under the video; a line under "Make my Reel" before starting ("Captions will be skipped: Gemini is free again at 5:29 AM").

## 2. Profile › Moments redesign: one screen, no long scroll
- Main page, five rows:
  1. **Capture moments**: switch + one-line status ("15 s clips · DJI Mic").
  2. **What to film ›**: "Braking, acceleration, lean, when I speak"; its own page with the switches and thresholds (value chips with small menus, not chip rows).
  3. **Film when I speak**: switch (used most).
  4. **Write down what I say ›**: "On · free again at 5:29 AM"; sub-page with Wi-Fi only, status/countdown and the test token.
  5. **Storage**: "1.2 GB · Manage ›" (Delete all inside).
- **More settings ›** at the bottom: clip length, photos, video quality, microphone, voice level meter and sensitivity, each one short row.
- No explanations on the main page: they move to the "How Moments works" sheet, opened from an ⓘ in the title.
- While riding, one banner ("Settings are locked while riding") instead of a message on every row.
- Nothing removed; rarely changed settings are one tap deeper.
