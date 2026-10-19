# Final plan, 20 Oct: everything discussed

Waiting for your approval. Nothing below is built yet.

The detailed plans stay where they are; this page is the decision list and the build order.
- Storyboard: `template-chat-plan.md`
- Keywords, voice start, widget, colour, mic: `tags-and-more-plan.md`
- Achievements: `achievements-plan.md`
- Voice at speed: `voice-at-speed-plan.md`

## Decisions (yours, and mine where you said "you decide")

| # | What | Decision |
|---|---|---|
| 40 | Storyboard | Named Storyboard. Floating button on the Studio page. Prototype approved, including the story example and a voice-over line per shot. |
| 41 | Keywords | English only. Lean from **your own lean setting** (Moments › Lean, today's clip trigger). Acceleration and braking as speed runs ("20–85 km/h in 6.1 s", "0–60 km/h in 4.8 s", "80–20 km/h in 2.6 s"), with the peak g small beside it. km/h or mph from the units setting. No "Best" tags. |
| 41 | Sound keywords (mine) | From the transcript request Gemini already makes, so no extra request. That means only when "Write down what I say" is on. "Shouted" also comes from the mic level, on the phone, for every clip. |
| 41 | Scene and b-roll (mine) | B-roll by rule (no talking, steady, road camera, moving, 5 s+). Scene words from Google's free on-phone picture labeller, on 3 frames. No Gemini picture descriptions (they'd cost a request per clip). |
| 42 | Start a ride | Widget (small and large), app-icon shortcut, Quick Settings tile, and "Hey Google, start a ride on Keppo Moto". All open the app and start after a 3 s countdown with Cancel. Automatic start when you ride off: parked. |
| 43 | Friends | Parked. |
| 44 | Bike colour | Full colour wheel, brightness, hex code, quick picks, live preview. A readable shade is used for buttons and text when needed. The app's colour follows the bike you ride. |
| 45 | Phone mic used with the DJI connected | Check the mic Android really records from; switch back to the receiver; the pop-up shows the real mic; each clip logs its mic; an error-log entry when this steps in. |
| 46 | WhatsApp alerts | Skipped. |
| 47 | Achievements | Redesign without a prototype. Level names Rookie → Rider → Tourer → Road Captain → Legend. All the badges in the plan. Celebrate after the ride only. **No speed or lean records** (mine: it keeps the app's safety rule; top speed stays on each ride and moment as before). |
| 39 | Voice at speed | You film in the app and send the video, the export and when you talked. I fix it from that, whenever it arrives (it jumps the queue). |
| 42 ("42") | | Nothing. |

## Build order

Each step is one commit. Where it says APK, I run the checks and send you `keppo-moto-<commit>.zip`.

### Part A: fixes and quick wins (APK 1)
1. **Mic fix (#45).**
   - Follow the real routed mic; reopen on the DJI receiver (at most every 30 s).
   - The pop-up shows the real mic.
   - "mic: DJI / phone" on each saved clip's log line.
   - An error-log entry.
   - Tests for the decision ("phone mic routed, receiver still there → reopen").
2. **Colour wheel and the app's colour (#44).**
   - The picker in Bike › Edit.
   - The theme follows the ridden bike, with readable shades for light and dark.
   - "Match the app to my bike" in Settings › Appearance.
   - Tests for the readable-shade maths.
3. **Start a ride from anywhere (#42).**
   - The start link with the 3 s countdown and Cancel.
   - App-icon shortcut and Quick Settings tile.
   - Widget, small and large: readiness, last ride; during a ride, time, distance, Pause and End.
   - Google voice action, plus how to set up a routine in Settings in case your phone's Gemini ignores it.
   - **Note:** the voice phrase can only be tried from a Play Store install (the internal test track is fine). Everything else works from the APK.

### Part B: keywords (APK 2)
4. **Ride keywords (#41):**
   - lean (from your setting) with its side;
   - speed runs and the 0–60 and 0–100 benchmarks;
   - top speed, downshifts, rev, hairpins, stopped;
   - time of day, place, camera.
   - A new keywords table (database update).
   - Pure tests on recorded rides.
5. **Sound keywords:**
   - the transcript request also returns sounds (scream, sneeze, laugh, cough, horn, siren, singing, whistle, rain, wind) with their times;
   - "Shouted" from the mic level.
6. **B-roll and scene words:** the rule, plus the on-phone picture labeller.
7. **Showing and finding them:**
   - keyword chips on the moment viewer (remove, add your own);
   - search and quick filters in Moments and Studio search;
   - Gemini's Reel suggestions get the keywords.
8. **Older moments:** tagged once in the background while charging. Keywords go in the ride export (`ride.json`, documented) and in the backup.

### Part C: achievements (APK 3)
9. **The engine:**
   - tiers;
   - new badges (exploring, seasons, time with the bike, moments and Studio, the secret ones from keywords);
   - level points, records (no speed or lean), the riding-year calendar.
   - New data: new-road areas, the state in place names.
   - Tests.
10. **The page:** level ring, Next up, riding year, badge grid, records, setup checklist.
11. **Badge art** (about 25 drawings, 5 tier colours), **badge detail** with the tilt shine, and the **share card**.
12. **Celebration** on the ride summary (shine and confetti), and the quiet note for badges earned off the bike. Each badge is celebrated once.

### Part D: Storyboard (#40) (APK 4 after step 17, APK 5 at the end)
13. **Model:** shot id, storyboard, voice-over line, locked fields, versions; old scripts load unchanged. Tests.
14. **Gear memory and the chat engine:**
    - prompt and JSON checks with one retry;
    - patches and locking;
    - memory updates with Undo;
    - its own Gemini allowance;
    - 3 chats a month free, unlimited with Pro.
15. **Chat screen:** the Storyboard button on Studio, saved storyboards, voice notes, the first-chat gear questions.
16. **Shot cards:**
    - direct edits ("edited by you");
    - versions;
    - the To film tab;
    - your edits teach Studio.
17. **Making the template:**
    - placeholder pictures as 1080×1920 stills on the video track;
    - on-screen texts on the Text row, music note;
    - templates in Your Reels.
18. **Fill a shot:** Record, A moment, Phone video; auto-trim (centred on the event, else the middle; a shorter clip is slowed to 0.75× or the slot shrinks).
19. **Auto-suggest** from all your rides, using the new keywords (Part B), with the "Suggested" badge until you confirm.
20. **To-film list** in the shot list and the ride pop-up, grouped by setup; record straight into a slot with its instruction and countdown.
21. **Voice-over lines:** read all of them in one go; Studio places each line on its shot.
22. **Gear & style settings page; backup** (chats, templates, placeholder pictures, gear); polish.

### Whenever your video arrives: voice at speed (#39)
- Replay your video's sound through the voice detector on the computer.
- Score each speed band.
- Change the detector only if your voice is audible at 80–94 km/h. If it isn't, the answer is mic placement and the media button.

## Checks before every APK
- `:core:telemetry:test :app:testDebugUnitTest :app:lintDebug assembleDebug -PabiFilter=arm64-v8a -PshrinkDebug=true`, expecting 0 lint errors.
- Screenshot tests updated where screens change: colour picker, widget, keywords chips, Achievements, Storyboard.
- Studio suggestions, Reels, the editor and ride recording keep working.

## Not in this plan
- Parked: automatic ride start, friends.
- Skipped: WhatsApp alerts.
- Still pending from before: other video shapes (4:5, 1:1, 16:9), #32 part 2 (Find ideas / Ask in the feed, Script tool), UI audit part 2, the store pack and Play launch steps.
