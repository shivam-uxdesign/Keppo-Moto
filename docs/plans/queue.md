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

# Next batch (planned 7 Oct): all 8 BUILT 7 Oct (music 273398b, Your Reels f4e8ff6, covers + Journal d8599ca, Reel ideas 2318b9a, phone videos 7447502, camera flip 699e0b8, Gemini countdown a9bc1dc, Moments page next commit)

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

## 3. Studio: your own videos, and seeing every clip
- **Start from phone videos:** a "Make a Reel from your videos" card at the top of the Studio tab opens Android's photo picker (videos and photos, multi-select, no permission). A video filmed during a recorded ride (by its time) is linked to that ride and gets real speed, route and stats; otherwise it's a plain clip (captions and music, no speed badge). Long videos: Studio uses the best parts, as with ride clips.
- **Add more while making a Reel:** an "Add videos" button by the clip strip in setup, and "Add from your phone" in Edit next to "Add from another ride". Added videos get captions, the story picker and beat cuts like the rest.
- **See every clip:** before making, a thumbnail strip "Studio picks from these 12 clips" (length, 🗣 if you talk; tap to preview, long-press to leave out). After making, the strip shows the clips used, in Reel order, the opening one marked; tap to jump there in the video. Clips from the phone or other rides are labelled ("Phone", "Mon 5 Oct").
- Picked videos aren't copied: Studio keeps access to them in the gallery and skips (and says so) any that were deleted. Landscape videos are cropped to 9:16; "fit with borders" stays on the later list.

## 4. HUD: flip to the back camera while filming by hand
- While filming manually in HUD mode, a flip button (⟲) next to stop/record on the pop-up and the live screen switches to the back camera (road view, or filming off the bike) and back.
- Same clip if the phone allows (the encoder keeps going; CameraX rebinds with the back-camera selector, a sub-second blip); otherwise the clip ends and a new back-camera clip starts at once, joined in Studio.
- Can also pick Front/Back on the viewfinder before starting. The timer shows which camera: "● 0:12 · back".
- Event and voice moments always stay on the selfie camera; when a manual recording stops, the camera returns to selfie.
- Each clip stores which camera filmed it (and where it switched) so Studio can cut between face and road; the coach's "film the road ahead" shot ticks itself off; the clip strip labels back-camera clips "Road".

## 5. Music: let Instagram add it (the rider doesn't like the library; trends change daily)
1. Studio's default music is "No music · add yours in Instagram": the Reel goes out with the rider's voice and ride sound, clean and loud enough for a song underneath.
2. Cut to a tempo, not a song: each vibe cuts on a beat grid shown as "Cut for ~120 BPM songs" (Hype; slower for Cinematic/Chill), so a trending song of a similar tempo added in Instagram feels in time.
3. Share screen, shown once (with "Don't show again"): how to add a trending song in Instagram: Music → Trending ↗ → pick one; set Original audio high and the song low so the voice stays clear. Trending sounds also help reach.
4. Remove the built-in library: the song pack, "Add the song pack", MusicLibrary and src/music go (the app doesn't grow).
5. Keep "Your own song" for WhatsApp or keeping; note that Instagram may mute copyrighted songs added outside it.
- Later (optional): "Trending now" suggestions: Gemini with Google Search lists three songs trending for moto Reels this week (India) to search in Instagram. Uses Gemini allowance and Search grounding (5,000 free a month, then paid); names can be a bit off.

## 6. Several Reels from one ride ("Reel ideas")
- A ride's Studio page shows idea cards (cover frame, title, length, clips used): **The story** (1–3, Gemini's stories, one idea each), **Highlights** (best of the ride, 30 s), **The 15-second hook** (best line + a moment either side, built to loop), **Speed run** (fastest stretch: route, top speed, riding shots), **Bloopers** (only if there are any). Tap one to make it; each can still be changed (vibe, length, Edit).
- Clips may be reused freely across Reels. Within one Reel a clip may appear twice only as a 1–2 s flash-forward teaser at the start, then in full in its place (the planner's "no same seconds twice" rule allows this one case).
- "Make all" queues them one after another; runs only while charging unless the rider starts it.
- Gemini: captions read once per ride (~2 requests), one director request returns all the ideas, tips only for Reels actually made: ~3 requests for 5 ideas.
- Each finished Reel is its own video with Share and Save.

## 7. Your Reels: every Reel saved, reopen with all edit options
- Today a finished Reel lives only in the shares cache, which is cleared on the next share: unshared Reels are lost. Fix: every Reel is saved in app storage (files/reels) the moment it's made.
- Saved with it, the whole project: vibe, length, clips (order, trims), captions, hook line, title, series/episode, voice-over takes, music choice, switches; plus the coach's tips. Reopening restores it exactly.
- Optional setting "Also save to Gallery" (off by default): each new Reel also goes to Movies/Keppo Moto.
- Studio tab: "Your Reels" grid at the top (cover frame, length, vibe, ride; newest first), rides list below. Each ride's Studio page also lists its Reels next to the Reel ideas.
- A Reel opens full screen with Share · Save to Gallery · Copy post caption · Edit (all Studio edit options; "Make it again" updates that Reel) · Duplicate (try another take, keep the original) · Remix · Voice-over · Delete.
- Storage: ~15–25 MB per 30 s Reel; Profile › Moments › Storage shows "Reels: 180 MB" with a way to clear old ones. Delete goes to Recently deleted (30 days) like rides and moments. Not in the Drive backup for now (large; remakeable from the saved project).

## 8. Reel covers, and sending Reels to Keppo Journal
- **Cover for every Reel**, made with it: the hook's best frame (face mid-line, sharp, eyes open where possible) with the hook line or title in the vibe's style; 1080×1920 with text inside the middle 3:4 (Instagram's profile grid crops to 3:4). Editable from the Reel's screen: pick another frame on a filmstrip, text on/off/edit, or use the route card. Used in Your Reels, Reel ideas, share preview. **Save cover** puts it in the Gallery for Instagram's "Edit cover → Add from camera roll".
- **Send to Journal** on each Reel (and **Send all** for a ride): the Reel and its cover go into that ride's shared folder (like moments) and the RIDE_SAVED signal fires. The latest sent Reel's cover becomes the Journal entry's cover; any Reel can be marked "Use as Journal cover". **Remove from Journal** takes it out (cover falls back to the previous Reel's, then route.png). Reels show an "In Journal" label.
- **Contract additions** (keppo.ride v1, docs/keppo-ride-format.md): `reels[]` = `{file, cover, title, durationMs, createdAt, postCaption}`; top-level `cover` (file name) used instead of `route.png` when present. Additive: the current Journal ignores them.
- **Keppo Journal note** (separate app, needs its own update): use `cover` as the entry's cover, falling back to `route.png`; list and play `reels[]` (files in the ride folder, with thumbnails via the provider); re-read on RIDE_SAVED / COLUMN_LAST_MODIFIED as for other generated files.

---

# Studio, rethought: script first, change after: BUILT 7 Oct (bugs a598cbf, script engine b5342b3, Gemini suggestions 42e8907, Script view + style 0a38080, Your Reels page 472dc36)

The rider's notes on the first real Reel (Tuesday Evening Ride, 30 s, Hype; shared as VID-20261007-WA0005): too many cuts, no storyline, feels abrupt; wants to shape the script and have Studio learn how they like stories written; not happy with the outcome, partly because there wasn't much footage.

## What was wrong with that Reel (frame-by-frame review)
- ~16 cuts in 30 s (one every ~1.8 s): the Hype beat grid applied to mostly silent 2–4 s riding bits.
- Every shot is the same selfie angle under the flyover, so fast cuts read as jitter, not editing.
- The first 6 s have no voice and no text: no hook.
- Speech cut into fragments ("Arre yaar, ye toh thoda…", "Paani nahi piya…", "Ek detailed report…", "I think you… bhool…"), each cut before it ends.
- The best hook, a long "Tooooooo" at 20–23 s, was buried mid-Reel.
- No captions (likely skipped: Gemini limit), so nothing ties the shots together on mute.
- Why, in the code: the planner fills the length with the best-scored pieces (cut at 1.5 s pauses, silent bits 2–4 s, on the vibe's beat). Gemini's "story" only boosts a list of clips; nothing decides what's first, middle or last, or why. The teaser, a transition on every cut, the stats card and the loop tail add more jumps.

## New approach: Gemini writes a script and the video is made; the rider changes it afterwards

### Sections: each has a job and several forms, with its own length
Gemini picks the form and length per section from what was actually filmed. Sections are optional and their order can change.
1. **Hook** (0.5–8 s, stop the scroll): a word or sound ("Fhit!", "No…", "Aaaahhh", "Tooooo"); a line that raises a question; a 5–8 s monologue when the opening is the story; picture-led (fastest or prettiest shot plus a text question); a flash-forward ("3 hours earlier…"); the number ("98 km/h" on the moment).
2. **Setup** (0–8 s, where and why; often skipped at 15 s): one line from the rider; text over the road ("Sunday · Nahan · 86 km"); a 1–2 s route sketch; the rider's voice from a later clip over the road; nothing.
3. **Journey / build** (0–15 s): a quick montage (3–5 shots × 1–2 s, only if the shots look different); one long calm road shot (6–12 s) under voice or text; a "conversation" of 2–3 of the rider's lines with road between them; a speed build (shots shorten, km/h counts up). Skipped when content is thin: never padding.
4. **Peak** (2–12 s): one long uncut clip (a story, a reaction, a lean); the reaction close-up; the event with its numbers (hard brake, "0.8 G", a beat of slow-mo); face then road (back camera).
5. **Turn / twist** (optional, 1–5 s): a contradiction ("Actually nahi…"); a surprise (rain, closed road, a dog); a blooper. Only when it's really in the footage.
6. **Payoff** (1–6 s): the line that answers the hook; a reaction (laugh, "worth it"); the result on screen ("Made it · 2 h 40 min").
7. **Ending** (0.5–3 s): a loop back to the hook; stats; a sign-off line; a question for comments; cut to black on a word.

### Story shapes (overall arrangement Gemini chooses)
- Straight story: hook → setup → build → peak → payoff.
- Cold open: peak first → "earlier…" → build → payoff.
- One take: hook → one long clip → ending (a great 10–12 s monologue).
- Problem → solution: hook (problem) → turn → payoff.
- Reaction-led: several short reactions → peak → payoff.
- Mood piece: long road shots with 2–3 lines or text (little talking, good footage).
- Countdown / list: "3 things on this ride…" → 3 mini-peaks.

### Shot lengths and the length the rider picks
- Mixed shot lengths, matched to the moment: quick 1–2 s, medium 3–6 s, long 8–12 s (one 10–12 s shot in a 30 s Reel is fine).
- The chosen length (15/30/45/60) is a time budget, not a template: Gemini spends it where the content is strong. 15 s is often a hook plus one peak, or one long clip; 30 s has room for a 10–12 s peak or a short build; 60 s has room for a turn and two chapters.
- If the footage can't fill the length well, Studio suggests a shorter Reel instead of padding.

### What Gemini gets and returns (one request per script, plus one per "Try another")
- Gets: every clip with timed words; reaction sounds marked as such (long vowels, "aaah", "toooo", "fhit": ask Gemini to flag them even when it isn't sure of the words); speed, events, time of day, which camera; how alike clips look (same selfie angle vs road/phone); the rider's style rules and last few before/after edits.
- Returns: the shape, then each section with its form, clip and exact in/out, on-screen text, and a one-line reason.
- Uses the main model (scripts need it), not the lite one; well within 20 a day.

### Guardrails the app enforces (a Gemini mistake can't break the video)
- Never cut a sentence in the middle; never run past a clip's end; total within ~1 s of the chosen length; never reuse the same seconds (except a flash-forward hook).
- Similar-looking shots in a row become one longer shot, not several short ones; quick montages only with visually different shots (back camera, phone videos, other rides).
- Mostly plain cuts; the vibe's transition effect only between sections. The flash-forward teaser is off by default (Gemini can choose it as a hook form).
- A broken section is fixed quietly (trimmed, extended or swapped), not a failure.
- Without Gemini (offline or out of allowance): fewer, longer shots with text cards, not a fast montage.

### Make first, change after (no approval step; rider's decision 7 Oct)
- **Make my Reel** works as today: Gemini writes the script and the video is made straight away.
- Under the finished Reel, a **Script** view: the sections as Gemini built them (form, length, words, on-screen text, its one-line reason). Tapping a section jumps the video there.
- Two ways to change it, then **Make it again**:
  - **Edit directly:** change text, make a shot longer or shorter, swap the clip, move or remove a section, pick a different hook. Remade from the edits with no Gemini request.
  - **Tell it in your words:** "start with the Tooooo", "too many cuts", "make it funnier", "focus on the water thing", "end on the flyover". Gemini rewrites the script with the note (1 request), then it's remade.
  - Both can be combined in one remake.
- Every version is kept (v1, v2, v3…) so the rider can go back.
- Rendering time on each remake is fine (rider's call); the Script view shows the new length at once.

### Learning the rider's style
- Each change after a Reel is made saves the script before, the script after and the rider's note ("too formal", "more Hinglish").
- Studio turns repeated edits into style rules ("prefers one-word reaction hooks", "keeps long monologues"); the rider can see and edit them (Studio › Your style).
- The next scripts get the rules plus the last few before/after pairs.
- Export the whole log (before/after/note) to share with the developer, to improve Studio's instructions.

### When there isn't much footage
- Before writing, Studio says what the ride has: "40 s of you talking, 3 good moments: enough for a 15 s Reel."
- When it's thin, it offers formats that work with little: **One moment** (one 10–15 s clip done well, captions and the route); **Voice-over story** (Studio suggests 2–3 lines to say over riding footage; works with no talking clips).
- The next-ride shot list gets specific ("say why you're riding today, at the start"; "film the road with the back camera once").

### Applied to the reviewed Reel (what it would have been)
Hook: "Tooooooo" plus the text "3 hours. No water." (3 s) → Setup: "Arre yaar, ye toh thoda…" in full (3 s) → Peak: "Paani nahi piya…" and the next line, one uncut clip with captions (8–10 s) → Build: one calm flyover shot under the voice (6 s) → Payoff/ending: "I think you… bhool…" then loop to "Tooooo" (4 s). About 5 shots instead of 16; a 15 s version offered too.

## Bugs found in that Reel (fix with this batch)
1. Stats card says "Top 22 km/h" while the speed badge shows up to 54 km/h: one of them is wrong.
2. "6.6 km · 6 minutes" means ~66 km/h average, which doesn't fit a top of 22: the card's numbers disagree.
3. "0 km/h" shown at 2–3 s while moving (a gap in the speed data): hide the badge when there's no speed instead.
4. Hype transitions show mostly black for a moment (around 5, 11, 19 s) with a yellow slash: looks like a dropout, not an effect.

## Your Reels: its own page: BUILT 7 Oct (472dc36). The Studio tab doesn't show the latest Reel (only the icon); say if you want a row.
- **Studio tab:** a "Your Reels" icon at the top right of the header, with a small count badge (e.g. 12). The tab itself is only about making: the "Make a Reel from your videos" card, then the rides list. The Reels grid and Recently deleted move off it.
- **Your Reels page:**
  - All Reels as covers, 3 per row, newest first; each shows length, vibe, ride name, and "In Journal" where it applies.
  - Filters at the top: All · From rides · From your videos · In Journal.
  - Storage at the top ("Reels · 180 MB").
  - Tap a Reel to open it with everything: watch, Script, Cover, Share, Save, Duplicate, Delete.
  - Long-press to select several: Delete, Send to Journal.
  - Recently deleted at the bottom (Restore / Delete, 30 days), as today.
- **Unchanged:** a ride's own Studio page keeps its "This ride's Reels" strip. Profile › Moments › Storage shows the Reels size, with a link to this page.
- **Open question:** should the Studio tab also show the latest Reel as a small row, or should Reels live only behind the icon?

## Built with it (7 Oct)
- Instead of fixed idea cards, Gemini suggests the pieces worth making from each ride, in one request: Reels, YouTube Shorts, Stories and long videos (2–5 min). They differ per ride, with fewer and shorter ones when the footage is thin. Without Gemini, the app suggests a Reel the footage fills well plus a 15 s Short.
- Make all runs in the background with a progress notification.
- New Reel vs Make it again are separate buttons.
- Not done yet:
  - Long videos are vertical 9:16 like the rest (no 16:9 yet).
  - Your voice carrying over a road shot (J/L cuts) isn't there yet: each shot plays its own sound.
  - Suggestions are made when the ride's Studio page opens, not automatically after the ride.
  - The posting plan is left out (rider's call).

---

# Timeline editor (BUILT 7 Oct, c298b9a; replaces Fine-tune)
- Built: a live preview with no rendering; scrubbing under a fixed playhead; zoom; tracks for clips, captions, text and voice-over; trim by dragging the edges; split, move, sound (mute/50/100/150%), delete; edit captions (words, timing, add, delete); text with time and height; + Clip at the playhead (this ride, other rides, phone); undo/redo; safe zones; Save renders once and syncs the script and Your style.
- Preview limits: the vibe's colour and camera moves only show in the saved video; captions in the preview are a plain white style.
- Next (should have): speed (slow-mo, 2×, ramps); reframe (zoom and pan in a clip); freeze frame with text; voice-over recorded on the timeline; overlays you can turn on and off (speed, lean, map); a transition choice per cut; long-press drag to reorder; a pinch to zoom.
- Later: J/L cuts; beat markers for an Instagram song; colour per clip; stabilise; templates; stickers.
- To think about after this build: editing on the iPad (send a Reel project to the iPad: an iOS app, or a web app working on the same project files; how to sync).

## Reel page: suggestions only (BUILT 7 Oct)
- The Reel page shows only the clips, this ride's Reels, Gemini's suggestions as big cards (format, vibe, title, why, length) and **Ask for something** (describe a piece in your words). The bottom button is **Make all** (in the background).
- Gemini picks each piece's vibe. Change it afterwards in Script (Vibe), then Make it again.
- Vibe and length presets are gone. Music, series and the on-video switches moved to ⚙ **Studio settings**, the same for every ride (my choice: your question wasn't answered, so say if you want them per ride).
- When Gemini can't be reached (no internet, or a blocked address such as Private DNS, an ad-blocker or the Wi-Fi's filter), Studio says so with **Try again** and **Make them without Gemini**, and retries by itself.

---

# Studio page improvements (planning 7 Oct; discuss only, build when the rider says "build")

## Rider's decisions so far
- **Studio lives in both places:** the Studio tab, and inside each ride (a ride's own Studio page).
- **Suggestions are ready automatically after a ride:** Studio reads the clips and asks Gemini in the background, so they're waiting when the rider opens Studio.
- **Story card and 3D video stay separate** (under Share) for now.
- More ideas, UX changes and workflows from the rider to follow.

## Ready after the ride (decided)
- **When:** battery above 50% → as soon as the ride ends, on any connection. 50% or less → wait for Wi-Fi, with a switch to allow mobile data.
- **Tell the rider:** a notification ("3 suggestions ready for Tuesday Evening Ride") that opens the ride's Studio page.
- **Gemini's daily limit used up:** wait for the reset and run by itself then; the countdown shows meanwhile.

## Reuse a clip in the editor (proposed)
- Today, + Clip only offers clips not in the Reel yet, so a clip can't be used twice.
- **Duplicate** (clip toolbar): copies the selected clip right after itself, the same seconds. Good for an instant replay or a punch-in (later with slow-mo or zoom). Trim or move the copy as usual.
- **+ Clip shows every clip**, with the used ones marked "In this Reel" (×2 when used twice). Picking one opens a small clip trimmer: the whole clip with in/out handles and a preview, to choose which part (e.g. a different moment of a long clip).
- Gemini's first draft still avoids repeating the same seconds (except a flash hook); in the editor the rider can reuse anything.

## Saved clips and drafts (decided 7 Oct)
- **Save a clip** from: the editor (selected clip → Save, the exact part), a ride's clip viewer, and the Studio clip strip (long-press opens a small menu: Watch · Leave out · Save).
- **Saved** page: in Your Reels, tabs "Reels | Saved clips". Thumbnails, length, the words said, which ride, with filters (talking, reactions, road). Remove from there.
- **Use it anywhere:** the editor's + Clip gets tabs "This ride | Saved | Other rides | Phone". It's added at the playhead and can be trimmed like any clip.
- **Only the rider uses them:** Gemini's suggestions never pull in saved clips.
- **Kept safe:** the saved part is copied (a few MB each), so it stays when the ride or its moments are deleted. (Rider: OK.)
- **Drafts too:** Reels made but not posted yet. Studio knows a Reel was posted when Share or Save was used on it. Your Reels gets a "Not posted yet" filter, and those Reels carry a small "Draft" mark.

## Search what you said (open for discussion)
- A **search bar** at the top of the Studio tab searches the words in every clip of every ride (transcripts and captions). It's also in the editor's + Clip and in Saved clips.
- Matching is forgiving of Hinglish spelling ("pani" finds "paani", "bhaai" finds "bhai"): repeated letters and common vowel differences are ignored.
- Each result shows the line with the match highlighted, the clip's thumbnail, the ride and the time. Tap it to play from that line.
- Result actions: **Save clip** (just that sentence, with a little room), **Add to Reel** (in the editor, at the playhead), **Open ride**.
- Clips not read yet don't show up; a line says how many ("12 clips aren't written out yet").

## Editor: transitions, text and stickers (open for discussion)
Today: transitions follow the vibe and only play between script sections; text has one style and can only move up or down; there are no stickers. The preview doesn't show the real styles.
- **Transitions per cut:** a small ◇ marker sits on each join on the timeline. Tap it to choose:
  - Cut
  - Fade
  - Flash
  - Zoom punch
  - Whip
  - the vibe's own (Slash, Shutter, Sun, Card)

  Also short or normal length, and "Use on all cuts".
- **Text:**
  - Styles: the four vibe styles plus plain, outline, box and handwritten.
  - Colour, size, alignment.
  - Drag anywhere on the video; pinch to resize or rotate.
  - Animation in and out: pop, type-on, fade, slide, none.
  - Its time is set on the timeline (drag both ends).
- **Captions styling:** the caption style for the whole Reel (vibe style, plain, box), plus size and position.
- **Stickers:**
  - **Ride stickers, live from your data:** a speed gauge, lean angle, G-force, a mini route map with you moving, distance, top speed, time of day, the place name. Nobody else has these.
  - **Emoji**, plus arrows and circles to point at things.
  - Each sticker can be dragged, pinched and rotated, and has its own time on a stickers track.
- **The preview shows the real thing:** text, captions and stickers are drawn in the preview exactly as in the saved video. Only colour and camera moves still wait for Save.
- Open questions:
  - Which ride stickers matter most?
  - Phone emoji only, or animated GIF stickers (these need an online service such as Giphy)?
  - Should a text style you use become your default (Your style)?

## Rider's notes, collecting (questions held until asked)
- Suggestions: 71 clips and 61 s of talking gave only one 11 s Reel (see the "Suggestions quality" fixes discussed: read all clips, gentler checks, at least 3 pieces, show what the checks removed, log Gemini's answer).
- "Reading what you said" runs again when making a suggestion (clips read state is forgotten between visits; only 32 clips read per pass).
- Making Reels stops when the rider switches to another app: not really in the background.
- Microphone: the rider never wants recording from a headset (Bluetooth). Other riders may want a Bluetooth mic, so it has to be a choice, not removed.
- Editor: multiple audio and multiple video layers (picture-in-picture, overlays), with effects and transitions placed on the timeline itself.
- Recording with two microphones on the next ride: one in the helmet (voice), one for the engine sound. Each should be its own audio track (record both, mix in the editor).
- Timeline: improve it with all the features a creator needs (a full editor, not the basics). A complete feature list is to be proposed when the rider asks for questions.
- Styles (the rider: "this is where the magic will happen"; think it through carefully): see all the styles in one place, edit any style in detail, and add new ones. A style covers captions, text, transitions, colour, cuts and stickers.
