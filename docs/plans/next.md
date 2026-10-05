# Next build: Keppo Moto

Planned with the rider, not built yet. Build in this order, one commit each, then a single APK (`keppo-moto-<sha>.apk`) at the end.

Prototypes (private Artifacts):
- Phone mic pop-up: https://claude.ai/artifact/4bme3EbpVK5g5RpUNVbtoM (version 3 is the approved look)
- New Home: https://claude.ai/artifact/HTjce3tXh5FqiEJ1xgSeAw (waiting for the rider's feedback)
- 3D ride video share: https://claude.ai/artifact/1gbRDXH81Kh9rst4XonTj6 (version 2: big, centred moment card)

Already done and pushed (in the next APK): the zoom bar under the ride-detail timeline scrolls when zoomed (commit 1e5f110).

---

## 1. Pop-up: which mic is recording (approved)

| Mic | Riding | Filming |
|---|---|---|
| DJI / USB-C (or wired, headset) | grey "cam" (as today) | red timer (as today) |
| Phone mic | blue "cam · phone" | blue timer |

- **Bubble:** with the phone mic, blue "phone" in place of km/h, and a blue timer while filming.
- **DJI drops mid-ride:** the existing one-time note "<mic> disconnected · recording with the phone mic" and the "<mic> back" note stay. So does Reconnect mic.
- **Code:**
  - `MomentState.micType: MicType?`, set in `MomentRecorder.startAudio()` and cleared in `stopAudio()`.
  - `HudData.phoneMic`.
  - New tone `HudTopRow.Tone.PHONE` (blue, e.g. #60A5FA in `HudCard.toneColor`). `right()` returns:
    - while filming with the phone mic: the timer in PHONE tone instead of REC
    - when idle with the phone mic: "cam · phone" in PHONE tone
  - Replaces today's amber "cam · phone mic". `micFallback` stays for the notes.
  - Bubble (`HudBubble`): the same rule.
- **Tests:** `HudTopRowTest`, covering both mics × riding/filming, and the bubble label.

## 2. Top speed in moments

- **What:** each clip stores its top speed: the highest speed in the ride's samples from the clip's first frame to its end.
  - Computed when the moment is saved (`MomentRecorder.writeClip` / `stopLive`).
  - Older clips are worked out from samples when first shown, then stored.
- **Storage:**
  - `MomentEntity.topSpeedMps` (Room migration 9 → 10).
  - `Moment.topSpeedMps`.
- **Where it shows:**
  - The clip viewer: "Top 98 km/h".
  - The share overlay: a "Top speed" detail chip in `MomentField`.
  - Keppo Journal's `ride.json`: `moments[].topSpeedMps`. Document it in `docs/keppo-ride-format.md` as an addition.
- **Tests:** top speed over a sample window; the migration.

## 3. Share a ride as a 3D video

Rider's choices: pick by **speed** (16× / 30× / 60× / 120×). At moments the video **slows down and shows the clip**. **9:16** (1080×1920). Typical output 15–30 s, sometimes more.

### Share screen (Share ride → "3D video", next to "Story card")
1. **Preview (9:16):** the follow camera behind the bike, as in ride-detail 3D replay. The route draws in speed colours, and moments show as pins. Play previews the final video in real time.
2. **Length, always shown:**
   - e.g. "Video 0:44 · 0:14 map at 30× + 3 clips".
   - Formula: map time ÷ speed + clip time.
   - Amber past 1:00, because Stories cut there.
3. **Trim before rendering:**
   - A strip of the whole ride, coloured by speed, with moment dots and two handles. It shows the start and end times and how much ride is selected.
   - Shortcuts:
     - **Best 5 min:** the fastest 5-min stretch, at 16×.
     - **Around moments:** the densest cluster of clips, at 30×.
     - **Whole ride:** at 120×.
4. **Speed chips**, each showing the map time it gives.
5. **Moment clips:**
   - Clip length: Off / 5 s / 10 s / Full (20 s), centred on the event.
   - A tick box for each moment inside the trim.
   - Photos show for 1 s.
6. **Show on the video:**
   - Layouts: Minimal / Bar / HUD.
   - Details: speed, lean, G-force, distance, time. Same model as moment sharing (`MomentField`, `MomentLayout`).
   - The ride's story card as an optional end frame.
7. **Create video**, with progress, time left and Cancel. Then Share / Save (Movies/Keppo Moto) and "Change and create again".

### Moments in the video
- **Approved change:** the clip plays in a **large card in the centre** (about 2/3 of the width, 9:16, white border, shadow) while the map pauses behind it. It is not a corner window.
- **Sound:** only during clips, from each clip's own audio. There's no music; the rider can add it in Instagram.

### How it renders
- **Off-screen 3D map:**
  - An off-screen MapLibre `MapView` in texture mode, 540×960 at pixel ratio 2, dark style.
  - The camera is stepped per output frame (30 fps): position and bearing from the samples, as in `RideDetailViewModel.bearingAtTime`.
  - Wait for the frame to finish rendering, then grab the bitmap.
  - Fallback if tile loading stalls: a dark background with the route.
- **Each frame:**
  - Draw the heat route (reuse the `ShareCardRenderer` route drawing) and the overlay (`MomentShareRenderer.draw`) on the map frame.
  - Encode with `MediaCodec` H.264.
- **Moments:**
  - Clip segments come from the moment files (with `TrimRange`-style clipping), composited into the centre card.
  - Final mux of video and clip audio with Media3 Transformer (a `Composition` of sequences). Silent gaps between clips.
- **Speed:** about 2–4 s of work per second of output. Warn about battery for long renders. Needs internet for map tiles.

### Code (new)
- `share/RideVideoScreen.kt`: the UI above, inside `ShareRideScreen` as a mode.
- `share/RideVideoPlan.kt` (pure, unit-tested):
  - trim, speed and clip choices → a timeline of map stretches and clip holds, and the total length
  - "Best 5 min" and "Around moments"
- `share/RideVideoRenderer.kt`: the off-screen map and per-frame camera, overlay drawing, encoding, clip compositing.
- `share/ShareImages.saveVideo` and `share`: reused.

### Tests
- `RideVideoPlan`: the length formula, clips inside and outside the trim, ticked-off moments, shortcuts on the Monday ride.
- On a phone:
  - render 30 s of the Monday ride at 30× with 5 s clips
  - check the length, the clip order, the sound, and Share and Save

## 4. New Home (prototype waiting for feedback; build only once approved)

- **Readiness above Start:**
  - One quiet line "✓ Ready to ride · DJI mic · GPS · mount" when everything's fine.
  - When something's wrong, it expands: the problem with its fix (e.g. "DJI mic not connected · Plug in the receiver"), and the other checks as chips: GPS, mount, camera, pop-up permission, battery, storage.
- **Last ride:**
  - Route map, km / time / top speed / max lean, and a scrollable row of moments.
  - After a ride, it moves to the top with Share and Open, until the next ride.
- **Bike care:**
  - Odometer per bike, and rider-set reminders (chain lube every N km, oil change by km or date).
  - New: a bike odometer and reminder settings on the Bike tab.
- **Milestones:** new top speed, longest ride, distance totals, shown as they happen.
- **This week:** one line, e.g. "1 ride · 29.8 km · 44 min".
- **Moves out:** the Today / Week / Month charts move off Home (details stay in Rides and Profile › Statistics).
- **Later:** "on the bike" hints (charging from the bike, DJI plugged in, phone at the mount angle) to expand readiness automatically.

## Verification (whole batch)
- Run `:core:telemetry:test :app:testDebugUnitTest :app:lintDebug assembleDebug -PabiFilter=arm64-v8a -PshrinkDebug=true`; expect 0 lint errors.
- Commit and push each step, then one APK named by commit, sent to the rider.
