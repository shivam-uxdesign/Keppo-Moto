# Keywords on moments, and the 20 Oct list: plan

Plan only, nothing built. Items are marked **Claude's idea** or **Found in the code**. Answer the **Question:** lines.

Log rows #41–#46. Voice at speed stays #39.

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
| Acceleration | Strongest pull, 0.3 g or more | Acceleration 0.6 g |
| Hard brake | Strongest braking, 0.4 g or more | Hard brake 0.8 g |
| Top speed | Always | Top 94 km/h (or mph) |
| Downshift | Downshifts heard in the engine sound | Downshift ×3 |
| Rev | A rev while stopped | Rev |
| Turn | A sharp turn or hairpin in the clip | Left hairpin |
| Stopped | The bike didn't move | Stopped |
| Time of day | From the clock and the sun's position where you were | Sunrise · Day · Sunset · Night |
| Place | The area name, as ride names already use | Sector 70 |
| Camera | Which camera | Selfie · Road |

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
- **Question:** Show g-force as "0.6 g" (Claude's pick: what riders use) or "6 m/s²"?
- **Question:** Keywords in English only, or Hinglish too (for example "Cheekh" for scream)? Claude's pick: English, because search also matches your Hinglish words.
- **Question:** Are the limits in the table right (lean from 15°, acceleration from 0.3 g, braking from 0.4 g)?

---

## 2. Auto ride start (#42)

**Found in the code:** nothing starts a ride by itself today.

**Android's limit:** since Android 11 and 14, an app may not turn on the camera or mic from the background. Keppo Moto can't silently start filming when you ride off; something you do has to open it. Here are three ways that stay within the rules (Claude's idea):

1. **"Riding? Start" notification.**
   - Android's activity recognition, which costs little battery, notices you're in a vehicle.
   - A short GPS check confirms more than 15 km/h for 30 s.
   - Then a notification appears with **Start ride**. One tap and recording begins, with a 5 s countdown and Cancel.
   - It can also be shown on the lock screen.
2. **Plug in the DJI receiver.**
   - Android can open an app when a particular USB device is plugged in. You tick "always open Keppo Moto" once.
   - Plugging the DJI receiver into the phone then opens Keppo Moto and starts the ride with the countdown.
   - This needs testing on your phone to confirm the receiver identifies itself.
3. **NFC sticker on the phone mount.**
   - A cheap NFC sticker on the mount: putting the phone in the mount opens the app and starts the ride with the countdown.
   - This works on any phone with NFC.

- **Claude's recommendation:** 1 and 2 first. 3 if you like it.
- **Question:** Which ones do you want? Should the countdown start the ride on its own, or wait for a tap?

---

## 3. Add friends (#43)

This needs accounts and a server. **Found in the code:** there's no sign-in today; Firebase is used only for Gemini and App Check. It's a big feature, so first: what should friends do? Some options:
- **A. Ride together, live:** during a group ride, everyone's dot is on everyone's map, with "Waiting for you" and "Stopped" notes. This is the most useful while riding, but it shares live location, so it's opt-in per ride.
- **B. See each other's rides and Reels:** a small feed, likes and comments.
- **C. Compare:** the same road, top speeds, distance this month.
- **D. Share without accounts:** a link to a ride or a live location that anyone can open. This is the quickest, and needs no friend list.

- **Question:** Which of these do you want, and which first? Claude's pick: D first (no accounts), then A with Google sign-in.

---

## 4. The app's colour follows your bike (#44)

**Found in the code:** each bike already has a colour, used for its routes. You can pick it from 8 colours (teal, amber, pink, violet, lime, orange, sky, red); otherwise it gets one by its place in the garage. The first bike keeps the app's teal.

Plan (Claude's idea):
- The app's main colour (buttons, selected tabs, the Studio button, the HUD accent) becomes the colour of the **bike you're riding**, the one picked on Home.
- In light mode the colour is darkened automatically so text on it stays readable. Each of the 8 colours is checked for this.
- These stay fixed, so they still mean the same thing: the recording red, the brake and acceleration colours, and left/right lean.
- **Risk:** the red bike colour is close to the recording red. Fix: drop red from the picks, or change the recording colour to a deeper red when the bike is red.
- In Bike › Edit, the colour row becomes "Bike colour · used for the app and its routes", with a live preview.
- Settings › Appearance gets "Match the app to my bike" (on by default).
- **Question:** Only the 8 colours, or a free colour picker? Claude's pick: the 8, because any colour can be unreadable somewhere.

---

## 5. The mic is sometimes on in the background (#45, bug)

**Found in the code:** while a ride is running, the mic keeps listening even when the camera rests: at stops, on a break (off the bike) and on a manual pause. That's because "Film when I speak" needs it. Android shows its green mic dot then. So you'd see the mic in use whenever:
- a ride is paused or on a break (by design today);
- a ride wasn't ended, for example when stop detection didn't end it;
- the voice-over recorder or the mic test was left open.

Plan:
1. **Stop listening when it can't matter:**
   - on a manual pause;
   - after 2 minutes on a break (you got off the bike);
   - listening resumes the moment you move.
2. **Say so:** the ride notification says "Listening for your voice" whenever the mic is on.
3. **Catch it:**
   - log a line each time the mic opens or closes, with the reason;
   - a watchdog writes an error if the mic is open with no ride running, naming who opened it.
- **Question:** When did you notice it? During a ride (stopped or on a break), after a ride ended, or with no ride at all? The answer tells me whether it's the design (fix 1) or a leak (fix 3 finds it).

---

## 6. WhatsApp instead of SMS for crash alerts (#46)

**Found in the code:** crash alerts send an SMS straight from the phone. It works with no internet and nobody has to tap anything.

**WhatsApp's limit:** WhatsApp doesn't let an app send a message by itself. An app can only open WhatsApp with the message typed in, and then someone must tap Send, which is useless if you're hurt. The only automatic way is the **WhatsApp Business Platform**:
- the message is sent from a business number through our server;
- it needs Meta business verification and pre-approved message templates;
- it costs a small amount per message;
- it needs internet at the crash spot.

Plan (Claude's idea):
- **Keep SMS** as the alert that always works.
- **Add WhatsApp as a second channel** once the server exists (server step 8): the same alert with the live location link.
- **Now:** a "Share live location on WhatsApp" button when starting a ride, which you tap yourself.
- **Question:** What's the problem with SMS for you: cost, delivery, or that your contacts don't read SMS? If it's delivery, the SMS log already shows whether each text really left the phone.

---

## 7. Voice not caught at high speed (#39): what "refer any 18 Oct ride" can give

- The only 18 Oct ride I have is the export you sent (Sector 79 → Sector 70, 15:38). It's already analysed in `voice-at-speed-plan.md`:
  - up to about 65 km/h voice is caught;
  - from 65–94 km/h the wind fills the helmet mic and every signal fails together.
- That export has **no sound**, only readings every 5 s. It can't show whether your voice is audible at all under the wind at 90 km/h, so any fix from it would be a guess.
- If you have other 18 Oct rides, export them too. But a missed shout has no recording either way, because the camera wasn't filming. So the ways forward are still:
  - **A.** Your in-app video talking at several speeds (no build needed), as agreed.
  - **B.** I build the voice test mode now (T1–T3, one APK): both mics recorded all ride, every reading every 46 ms. You do one ride and export it.
- **Question:** A or B? Claude's pick: B if you want me to focus on it now, because it gives both mics and exact readings, and A can't.

---

## 8. "42"

- **Question:** You wrote "42". Did you mean a row in the log, or something else?
