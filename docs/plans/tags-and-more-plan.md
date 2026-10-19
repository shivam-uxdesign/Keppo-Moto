# Keywords on moments, and the 20 Oct list: plan

Plan only, nothing built. Items are marked **Claude's idea** or **Found in the code**. Answer the **Question:** lines.

Log rows #41–#46. Voice at speed stays #39.

Answers 20 Oct: auto ride start and friends parked; WhatsApp skipped; start by voice and a widget instead (§2); a full colour wheel (§4); the mic bug is the phone mic used while the DJI mic was connected (§5); km/h.

---

## 1. Keywords on every moment (#41)

You asked for every moment video to carry keywords, for example "Acceleration 0.6 g", "Lean 26° right", "Talking", "Sneezed", "Aaaah", "Shouted". You also asked for b-roll tags and a way to find moments by keyword.

### What exists today (Found in the code)
- A moment already knows:
  - its event types (acceleration, lean, brake, turns, voice, rev);
  - one peak value;
  - its top speed;
  - what you said (the transcript);
  - which camera filmed it (selfie or road).
- Saved clips (Studio) get a few tags: "road" or "selfie", "talking", "fast" (60 km/h and above), and the 3 most-used words. Moments themselves have no tags, and nothing can be searched by keyword except in Studio's search.

### 1.1 The keyword list (Claude's idea: please check it)

Each keyword has a **kind**, which decides its colour and where it comes from.

**Ride (from the sensors and GPS, on the phone, free, ready the moment the clip is saved)**

| Keyword | When | Example |
|---|---|---|
| Lean | Biggest lean in the clip, 15° or more, with its side | Lean 26° right |
| Acceleration (rider, 20 Oct: as speeds and time) | The speed-up run in the clip, from where it starts to where it peaks | 20–85 km/h in 6.1 s |
| Benchmark sprint | When the run passes a standard mark | 0–60 km/h in 4.8 s · 0–100 km/h in 9.7 s |
| Braking (as speeds and time) | The slow-down run | 80–20 km/h in 2.6 s · 60–0 km/h in 3.1 s |
| Peak force | Kept small next to the run, for the curious | 0.6 g |
| Top speed | Always | Top 94 km/h (or mph) |
| Downshift | Downshifts heard in the engine sound | Downshift ×3 |
| Rev | A rev while stopped | Rev |
| Turn | A sharp turn or hairpin in the clip | Left hairpin |
| Stopped | The bike didn't move | Stopped |
| Time of day | From the clock and the sun's position where you were | Sunrise · Day · Sunset · Night |
| Place | The area name, as ride names already use | Sector 70 |
| Camera | Which camera | Selfie · Road |

**How the speed runs are measured (Claude's idea)**
- From the ride's speed samples while the clip was filmed:
  - a run starts where the speed starts rising (or falling) steadily;
  - it ends where it stops rising (or falling);
  - short wobbles under 2 km/h don't break a run.
- Only runs of at least 20 km/h change are kept.
- Speeds are rounded to 5 km/h, and "0" means below 5 km/h.
- Times use the moments between samples, shown to 0.1 s. GPS updates about once a second, so the times are good to about ±0.3 s. The keyword says "about" when the GPS was weak.
- Benchmarks: 0–60 and 0–100 km/h; 60–0 and 100–0 km/h for braking. In miles, 0–30 and 0–60 mph.
- ~~Personal bests with a "Best" keyword and a HUD note~~: dropped. They break the app's rule of no speed or lean goals (see `achievements-plan.md` §5). The runs stay as plain keywords.

**Sound (what was heard)**

| Keyword | Example |
|---|---|
| Talking, plus the 3 most-used words | Talking · tunnel · bhai |
| Shouted | A shout, loud speech |
| Scream | "Aaaaahhhh" |
| Laugh | |
| Sneeze, Cough | |
| Singing, Whistle | |
| Horn, Siren | Other vehicles |
| Rain, Wind | |

How sound keywords are found:
- **Gemini, in the transcript request it already makes.** That request already listens to the clip, so it is asked to also list the sounds it hears (scream, sneeze, laugh…), each with its time. No extra request. **Claude's recommendation.**
- Later, if needed offline: a small on-phone sound recogniser (Google's YAMNet, about 4 MB, 521 kinds of sound, including sneeze, scream, laughter, shout and horn). It costs nothing per clip and works without internet, but it adds to the app's size.

**Scene and b-roll (#41, part 3)**

- **B-roll:** a rule on the phone, from what's already measured. A clip counts as b-roll when:
  - nobody talks;
  - the camera is steady (little shake);
  - it's the road camera;
  - the bike is moving;
  - it's at least 5 s long.
- **What's in the picture:** Google's on-phone picture labeller (ML Kit, free and offline) looks at 3 frames of each clip. It gives words like Sky, Mountain, Tree, Road, Bridge, Building, Night, Rain or Sunset. Only the confident ones are kept.
- Gemini could describe frames in more detail (a tunnel, traffic, a ghat road), but it costs one request per clip. **Question:** keep that off, and use it only for clips you open?

### 1.2 Your own keywords and finding moments (#41, part 4)
- **Moment viewer:** keyword chips under the video, coloured by kind.
  - Tap ✕ to remove a wrong keyword. It stays removed, and the removal is remembered so Studio learns.
  - Tap **+ Keyword** to add your own, typed or picked from the ones you've used before.
- **Moments list and Studio search:** a search box that matches any keyword, the words you said and your own keywords. Quick filters: Lean · Fast · Talking · Scream · B-roll · Selfie · Road.
  - Number searches work too: "lean 30" finds leans of 30° or more.
- **Storyboard (#40)** matches placeholder shots using these keywords, so "you screaming as you exit the tunnel" finds clips tagged Scream + Road + Tunnel.
- **Gemini's Reel suggestions** get the keywords, so they can pick moments better.

### 1.3 Storage
- A new table for moment keywords. Each row holds the moment, the keyword, its kind, its number and unit (26, °, right), where it came from (ride, sound, picture, you), and whether you removed it.
- This needs a Room migration. Older moments are tagged once in the background, after an update, while the phone is charging.
- The ride export (`ride.json`) gains `moments[].keywords`. It is documented as an addition.
- Backup: your own keywords and removals go with the moments.

### 1.4 Questions
- **Decided (rider):** speeds in km/h (following the units setting). Acceleration and braking shown as speeds and time ("0–60 km/h in 4.8 s"), with the peak g small next to it.
- **Question:** Keywords in English only, or Hinglish too (for example "Cheekh" for scream)? Claude's pick: English, because search also matches your Hinglish words.
- **Question:** Are the limits right (lean from 15°; speed runs of 20 km/h or more)?

---

## 2. Start a ride by voice, from a widget, or from a tile (#42)

**Parked (rider, 20 Oct):** starting a ride automatically when you ride off, and friends (#43). The WhatsApp alerts (#46) are skipped.

Android won't let an app turn on the camera or mic from the background, but all three ways below open the app first, so they're allowed. Each one opens the ride screen and starts the ride after a **3 s countdown with Cancel**. You asked for it, so no extra tap is needed. The usual checks (GPS, mic, mount) run as they do today; a check that fails stops the countdown and says why.

### 2.1 "Hey Google, start a ride on Keppo Moto" (Claude's idea)
- **App Actions:** the app tells Google Assistant it can "start an exercise". The exercise name is "ride" (also "motorcycle ride" and "bike ride"). Saying any of these opens Keppo Moto's start link:
  - "Hey Google, start a ride on Keppo Moto"
  - "Hey Google, start Keppo Moto ride"
- **Also works without any setup:**
  - "Hey Google, open Keppo Moto".
  - A Google Assistant routine of your own, for example "Hey Google, let's ride" → open Keppo Moto's **Start ride** shortcut.
- **Risks:**
  - On many phones Gemini has replaced Google Assistant, and Google has been changing how far Gemini follows App Actions.
  - App Actions only take effect for an app installed from the Play Store (the internal test track is fine).
  - So this gets tested on your phone. If the exact phrase doesn't work, the routine always does, and Settings will show you how to set it up.

### 2.2 Home-screen widget
- **Small (2×1):** a round **Start ride** button in your bike's colour, with the bike's name.
- **Large (4×2):**
  - Start ride;
  - the bike;
  - a readiness line: "DJI mic connected · GPS ready" or "DJI mic not connected";
  - last ride: "29.8 km · 44 min · Sunday".
- **During a ride:** the widget switches to the time and distance so far, with **Pause** and **End**. End asks to confirm in the app.
- Built with Jetpack Glance (Android's way of building widgets with Compose).

### 2.3 Also, almost free
- **Long-press the app icon → Start ride** (an app shortcut).
- **Quick Settings tile "Start ride":** in the swipe-down panel, next to Wi-Fi and Bluetooth.
- These, the widget and the voice command all use the same start link, so they behave the same.

---

## 3. Friends (#43): parked

---

## 4. Bike colour: a full colour wheel (#44)

**Found in the code:** a bike's colour is stored as any colour already (`routeColor`), so a free choice needs no data change, only the picker.

The colour picker (Bike › Edit › Bike colour):
- **A colour wheel** (hue and strength) with a **brightness slider**.
- **Hex code field**: type or paste, for example `#E4572E`. It updates the wheel, and the wheel updates it.
- **Quick picks:** the 8 current colours, plus your recent ones.
- **Live preview:** a Start button, a selected tab, and a stretch of route on the dark map, in light and dark mode.
- **Readable everywhere:**
  - The app checks the colour against the dark and light backgrounds.
  - When text on it or around it wouldn't be readable, the app uses a slightly lighter or darker shade of the same colour for buttons and text, and says so: "Adjusted for readability: #C8471F".
  - Your exact colour is still used for the routes and the bike.
- **Close to the recording red:** a note says "Close to the recording colour" so you know. The recording dot still blinks, so it stays recognisable.

Where the colour shows:
- The app's main colour (buttons, selected tabs, the Storyboard button, the HUD accent) follows the bike you're riding, the one picked on Home.
- These stay fixed: the recording red, the brake and acceleration colours, left and right lean.
- Settings › Appearance: "Match the app to my bike" (on by default).

---

## 5. The phone mic was used although the DJI mic was connected (#45, bug)

What you saw: a few times the app recorded with the phone's mic even though the DJI receiver was plugged in. It couldn't be reproduced.

**Found in the code:**
- The app asks Android for the DJI receiver, but Android treats that as a preference. If Android routes the sound to the phone mic anyway, the app writes "audio now from Phone…" in the log and **carries on**. It still thinks it's on the DJI mic, the pop-up shows the DJI mic, and its 10-second check compares against what it asked for, not what it actually gets. So the phone mic can stay for the rest of the ride. This is the likely cause.
  - Android does this when another app grabs the mic for a moment: Google Assistant, a call, a voice note.
- Two smaller known cases, both by design:
  - When the receiver drops for a moment (cable bump, low battery), the app switches to the phone mic at once. It goes back only after the receiver has stayed connected for a while, so a loose receiver doesn't chop clips.
  - When a ride starts before the receiver is ready, the 10-second check moves to it once it appears.
- The 18 Oct ride's log shows only one switch to the phone mic: at 10:26:54, 23 s before the ride ended. The receiver was reported "disconnected", which looks like it was unplugged at the end. That's not this bug.

Plan:
1. **Watch the real mic, not the asked-for one:**
   - every 10 s, and on every routing change, check which mic Android is actually recording from;
   - if it's the phone mic while the DJI receiver is still connected, reopen the mic on the receiver at most once every 30 s;
   - log "phone mic used although the DJI mic is connected: reopened".
2. **The pop-up tells the truth:** the blue "phone" label shows whenever the sound really comes from the phone mic, even if the app asked for the DJI mic.
3. **Each clip notes its mic:** the log line "video saved" gains "mic: DJI" or "mic: phone". If it happens again, the export shows exactly which clips and why.
4. **The error log** (Send details) gets one entry per ride when the fix in step 1 had to step in, so it isn't lost.

---

## 6. WhatsApp for crash alerts (#46): skipped

---

## 7. Voice not caught at high speed (#39): what "refer any 18 Oct ride" can give

- The only 18 Oct ride I have is the export you sent (Sector 79 → Sector 70, 15:38). It's already analysed in `voice-at-speed-plan.md`:
  - up to about 65 km/h voice is caught;
  - from 65–94 km/h the wind fills the helmet mic and every signal fails together.
- That export has **no sound**, only readings every 5 s. It can't show whether your voice is audible at all under the wind at 90 km/h, so any fix from it would be a guess.
- If you have other 18 Oct rides, export them too. But a missed shout has no recording either way, because the camera wasn't filming. So the ways forward are still:
  - **A.** Your in-app video talking at several speeds (no build needed), as agreed.
  - **B.** I build the voice test mode now (T1–T3, one APK): both mics recorded all ride, every reading every 46 ms. You do one ride and export it.
- **Decided (rider, 20 Oct): A.** You film in the app and send the video, the export and when you talked.

---

## 8. "42"

Nothing (rider, 20 Oct).
