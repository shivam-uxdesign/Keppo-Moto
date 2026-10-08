# Keppo Studio feature plan

Everything we've discussed for Studio, plus ideas of my own (marked as Claude's idea). Nothing here is built. Mark each item Yes, Not now or No, add a note if you like, and answer the questions. Review it on the shared page; answers are read back from there.


## A. Fix first: better suggestions you can trust

Studio suggested one 11 s Reel from 71 clips. These fix why, before anything new.

### A1 Read every clip once, and remember it (Discussed)
- No more 32-clip limit: all clips are read (8 per Gemini request), your own and voice clips first.
- Studio remembers per ride which clips are read, so reopening the page never starts over.
- Reading happens only when suggestions are prepared. Making a suggestion never shows "Reading what you said" again, unless its script uses a clip not read yet (then only that one).
- **Question:** A ride with 71 clips needs about 9 caption requests plus 1 for suggestions. On the free tier (20 a day for the main model) two long rides can use a day's allowance. Is that acceptable for now, or do you want to look at the paid plan?

### A2 Gentler checks, and enough suggestions (Discussed)
- The app's checks never drop a shot Gemini chose; they lengthen or merge it instead.
- A piece never shrinks below about 70% of its planned length; if it would, more of the same clip fills it.
- Rides with 45 s or more of talking get at least 3 different pieces, each filling at least 80% of its length.
- **Question:** Is "at least 3 pieces" the right minimum, or should it scale with the ride (e.g. one per 20 s of talking)?

### A3 See what Studio did (Discussed)
- Each card shows the plan and the result, e.g. "Planned 30 s · 0:24 after checks".
- The error log keeps Gemini's full answer and every fix the checks made.
- A "Send Studio details" button shares a ride's suggestions and checks with the developer.

### A4 Really make Reels in the background (Discussed)
- Today making stops when you switch apps. Making moves to a background service with its own notification and progress bar, so it carries on with the screen off or in another app.
- Make all, and the after-ride suggestions, use the same service.
- If the phone stops it anyway, it picks up where it left off next time.
- **Question:** If you swipe Keppo Moto away from recent apps, should making carry on (the notification stays) or stop?


## B. Ready after the ride

Decided: suggestions are waiting when you open Studio.

### B1 Read and suggest automatically after each ride (Decided)
- Battery above 50%: starts as soon as the ride ends, on any connection.
- 50% or less: waits for Wi-Fi, with a switch to allow mobile data.
- Gemini's daily limit used up: waits for the reset and runs by itself; the countdown shows meanwhile.
- A notification when it's ready: "3 suggestions ready for Tuesday Evening Ride", opening the ride's Studio page.

### B2 A richer "ready" notification (Claude's idea)
- The notification shows the top suggestion's cover and title, with a Make it button that starts making it in the background.
- A second notification when it's made, with Share and Open.

### B3 Make the top suggestion automatically too (Claude's idea)
- Optional switch: after reading the ride, Studio also makes the best suggestion, so a finished Reel is waiting (only with battery above 50% or charging).
- **Question:** Would you want this, or only suggestions?


## C. Studio home and a ride's Studio page

Decided: Studio lives in the Studio tab and inside each ride.

### C1 Each ride gets its own Studio page (Decided)
- Studio moves out of Share ride (where it's a tab next to Story card and 3D video) into its own page, opened from the Studio tab, the ride detail page and the after-ride notification.
- Story card and 3D video stay under Share (decided).
- **Question:** On the ride detail page, should Studio be a button next to Share, or its own section showing the suggestions?

### C2 Studio tab opens on what's ready (Discussed)
- Top: "Ready for you": new suggestions from recent rides, as cover cards.
- Then your rides, each with its state: 3 suggestions ready · 2 made · 5 clips not read yet · waiting for Wi-Fi · Gemini limit (free at 5:29 AM).
- Search, Your Reels and settings in the header.

### C3 A ride's Studio page, suggestions first (Discussed)
- Order: suggestions, Ask for something, this ride's Reels, then the clips (a compact "From these 71 clips" row that opens the full strip).

### C4 Quick prompts under Ask for something (Claude's idea)
- Tap-to-fill chips: Funnier · 15 s · Just the road · Only my voice · Slow and cinematic · For Shorts.
- Recent asks are remembered, so a good request can be reused on the next ride.

### C5 A first-time introduction (Discussed)
- Three short cards the first time Studio opens: what Studio does with your clips, that Gemini needs internet (and what it sends), and how suggestions and editing work.

### C6 Search what you said (Open)
- A search bar in the Studio tab across every clip of every ride; also in the editor's + Clip and in Saved clips.
- Forgiving of Hinglish spelling (pani = paani, bhaai = bhai).
- Results show the line, the clip, the ride and the time; tap to play from that line.
- Actions: Save clip (just that sentence), Add to Reel (in the editor), Open ride.
- Clips not read yet can't be searched; a line says how many.
- **Question:** Should search also find things Gemini saw in the video (e.g. "rain", "dog", "flyover")? That means sending a frame or two per clip to Gemini: more requests, but much richer search.

### C7 Saved clips and drafts (Decided)
- Save a clip (the exact part) from the editor, a ride's clip viewer, or Studio's clip strip (long-press menu: Watch · Leave out · Save).
- Your Reels gets tabs: Reels | Saved clips. The editor's + Clip gets a Saved tab.
- Saved parts are copied, so they stay when a ride is deleted. Gemini never uses them; only you do.
- Drafts: Reels made but not shared or saved yet carry a "Draft" mark, with a "Not posted yet" filter.
- **Question:** Should you be able to name or tag saved clips (e.g. "intro", "funny")?


## D. The editor: a full timeline

You asked for everything a creator needs. This is the full set, grouped so you can approve parts.

### D1 Video layers (Discussed)
- More than one video track: picture-in-picture (your face in a corner over the road shot), overlays, side-by-side.
- For each layer: position, size, rotation, crop, shape mask (circle, rounded), opacity, and a border or shadow.
- Keyframes to move or zoom a layer over time.
- **Question:** Which layouts do you see yourself using most: face in a corner, split screen (top/bottom), or before/after?

### D2 Audio tracks (Discussed)
- Separate tracks: each clip's sound, helmet mic (voice), engine mic, voice-over, your music, sound effects.
- Per track: volume, mute, solo. Volume curves (keyframes) and fades.
- Auto-duck: music and engine dip while you talk.
- Detach a clip's sound, so your voice carries over the next shot (J/L cuts).
- Voice clean-up: reduce wind and engine noise in the voice track.

### D3 Clip tools (Discussed)
- Split, trim by dragging, duplicate, delete, replace a clip and keep its length.
- Reuse a different part of a clip already in the Reel (a small trimmer).
- Speed: 0.25× to 4×, slow-mo presets, and speed ramps (slow into the lean, fast out).
- Reverse, freeze frame (with text), rotate and flip, crop.
- Reframe: zoom and pan inside a clip, with keyframes; an automatic punch-in on emphasised words.
- Colour: exposure, contrast, saturation, warmth; or the style's look.
- Stabilise shaky footage.
- **Question:** Is reverse useful for riding footage, or noise?

### D4 Transitions on the timeline (Open)
- A ◇ on every cut: Cut, Fade, Flash, Zoom punch, Whip, Glitch, or the style's own (Slash, Shutter, Sun, Card).
- Length (short, normal, long) and "Use on all cuts".

### D5 Effects on the timeline (Discussed)
- An effects track: camera shake, flash, zoom punch, speed lines, motion blur, film grain, light leak, vignette, glitch, blur.
- Each effect has its own time range and intensity, with keyframes.
- **Question:** Any effect you've seen in motovlogs that's missing here?

### D6 Text and captions (Open)
- Text styles: the style's own plus plain, outline, box, handwritten; colour, size, alignment; drag anywhere, pinch to scale or rotate.
- Animations in and out: pop, type-on, fade, slide, bounce, none.
- Captions: the whole Reel's caption style, size and position; word-by-word highlight (karaoke); fix a single word; read one clip again.
- Emoji inside text.
- **Question:** Should a text style you use become your default for the next Reels?

### D7 Stickers (Open)
- Ride stickers with live data: speed gauge, lean angle, G-force, mini route map with you moving, distance, top speed, time of day, place name, weather.
- Emoji, arrows and circles; a "look here" callout that follows a point.
- Drag, pinch, rotate; each has its own time on a stickers track.
- **Question:** Which ride stickers matter most to you?
- **Question:** Phone emoji only, or animated GIF stickers too (these need an online service such as Giphy)?

### D8 How editing feels (Discussed)
- Pinch to zoom the timeline; snapping to cuts, the playhead and beats; no gaps between clips (magnetic).
- Long-press and drag to reorder; select several clips and move or delete them together.
- Copy a clip's settings (speed, colour, volume) and paste them onto others.
- Markers you drop while watching; an undo history list.
- Edits save as you go, so nothing is lost if the app closes.
- Full-screen preview, frame-by-frame stepping, and comparing with the previous version.

### D9 A preview that matches the final video (Discussed)
- Text, captions, stickers, transitions and colour show in the preview exactly as in the saved video.
- Small preview copies of clips are made in the background, so scrubbing stays smooth on long Reels.

### D10 Export settings (Claude's idea)
- Quality (1080p, or 4K when the clips allow), 30 or 60 fps, file size estimate.
- Presets per place: Instagram Reel, YouTube Short, Story (split into 60 s parts automatically), WhatsApp (smaller file).
- Export runs in the background like Make all.

### D11 Beats and sound effects (Claude's idea)
- Beat markers from your own song; "Cut to the beat" for selected clips.
- A small built-in sound-effects set (whoosh, impact, rev, notification ping), free to use.


## E. Styles: where the magic happens

You flagged this as the heart of Studio. A style is a full recipe for how a piece looks, sounds and moves, not just a caption font.

### E1 What a style contains (Discussed)
- Captions: font, colours, size, position, animation, word highlight.
- Text presets for titles and callouts.
- Transitions: which ones, and how often (every cut, or only between sections).
- Pace: shot lengths, cut rhythm, how often to cut on the beat.
- Camera moves: punch-ins on emphasis, slow pushes, shake on hard braking.
- Colour look (the grade).
- Ride overlays: how speed, lean and the map look.
- Stickers that come with it; intro and outro cards; sound (fades, how much music dips under your voice); watermark.

### E2 A Styles page (Discussed)
- All styles in one gallery, each previewed live on your own best clip (a short loop), not stock footage.
- Built-in styles (Hype, Cinematic, Chill, Vlog) and yours; favourites; a default per format (Reel, Short, Story, long video).
- **Question:** Where should Styles live: Studio tab header, Profile, or inside the editor?

### E3 Edit a style in detail (Discussed)
- One screen per part (captions, transitions, pace, camera, colour, overlays, sound), each with a live preview that updates as you change it.
- Reset any part to the original; Shuffle tries variations within the style.

### E4 Create new styles (Discussed)
- Duplicate a style and change it.
- "Save this Reel's look as a style" from any finished Reel.
- Describe it in words and Gemini drafts it: "night ride, neon, punchy, big yellow captions".
- Claude's idea: give a reference Reel (a video from your gallery) and Gemini describes its pace and look to approximate it.
- **Question:** Is the reference-Reel idea worth trying? It sends frames of that video to Gemini.

### E5 Brand kit (Claude's idea)
- Your fonts, colours, logo, handle ("@yourname") and intro/outro, applied across styles so every piece looks like yours.
- **Question:** Do you have brand fonts and a logo for your channel already?

### E6 Share styles (Claude's idea)
- Export a style as a small file or code and import one from another rider.

### E7 Gemini picks from your styles (Discussed)
- Suggestions choose among your styles (not only the four built-in vibes), favouring the ones you use and keep.
- Your style rules (from Script notes) feed the style choice too.


## F. Recording

Two microphones on the next ride, and microphone choices.

### F1 Two microphones: voice and engine (Discussed)
- Each mic becomes its own audio track: helmet mic (voice) and engine mic.
- Captions, voice detection and "Film when I speak" use only the voice track; the engine track is for sound in the edit (revs, exhaust).
- Claude's note: Android phones usually record one microphone input at a time. The practical way is a two-transmitter receiver in stereo mode (e.g. DJI Mic 2: transmitter 1 on the left channel, transmitter 2 on the right). Keppo records the stereo stream and splits it into two tracks.
- **Question:** Which microphones will you use (model), and does the receiver support a stereo / split mode?

### F2 Never the headset mic, unless chosen (Discussed)
- Automatic mic choice skips Bluetooth headsets by default, with a switch "Allow Bluetooth headset mic" for riders who want it.
- You keep Automatic, or pick a specific mic, as today.

### F3 Engine moments (Claude's idea)
- With an engine mic, revs and exhaust pops are detected as moments (like hard braking), so Studio can use them as sound hooks.

### F4 Shot list on the pop-up (Claude's idea)
- The coach's "film next time" shots show briefly on the HUD pop-up at the start of a ride, e.g. "Say why you're riding today".


## G. Your content and learning

### G1 Learn from what performs (Claude's idea)
- After posting, add a Reel's views and likes (or paste the numbers from Instagram Insights).
- Studio learns which hooks, shapes, lengths and styles do best for you, and Gemini uses that when suggesting.
- **Question:** Would you enter numbers by hand, or is that too much effort?

### G2 Captions and post text per platform (Claude's idea)
- For each piece: a post caption and hashtags for Instagram, a title and description for YouTube, and Hindi or English subtitles on request.

### G3 Hook check before posting (Claude's idea)
- Before sharing, Studio plays the first 3 seconds muted and says whether it would stop the scroll (Gemini, one request), with a fix suggestion.


## H. Later or parked

Kept on the list, not for the next builds.

### H1 Edit on the iPad (Parked)
- Options: a Keppo project package synced through Google Drive; a web editor on the iPad reading those projects; or a native iOS app. To decide after the editor work.

### H2 Story card and 3D video as Studio pieces (Parked)
- Parked: they stay under Share for now (your decision).

### H3 Posting plan (Parked)
- A week's posting order from one ride. Parked: too much for now (your decision).

### H4 Horizontal long videos (Open)
- Long videos (2–5 min) are vertical today. A 16:9 version for YouTube needs horizontal footage or a blurred-sides layout.
- **Question:** Would you film horizontally for long YouTube videos?

---

## Rider's review (7 Oct)

**Yes (37):**
- **Fixes:** A1 (read all clips; the request cost is acceptable), A2 (needs more thinking on the minimum number of pieces), A3, A4 (keep making even when swiped away).
- **After the ride:** B1. B3: make the top suggestion automatically; the rider will keep saying what "top" means to them.
- **Studio pages:** C1, C2, C3, C4.
- **Search:** C6, including search of what Gemini sees in the video.
- **Saved clips:** C7, with saved clips auto-tagged from their transcripts.
- **Editor:**
  - D1: the second video can be resized freely; presets for side by side and 3-stack (1 on top, 2 below).
  - D2, D3 (all clip tools, reverse too), D4, D6, D8, D9, D10.
  - D7: speed and lean stickers; phone emoji now, Giphy later.
- **Styles:**
  - E1, E3, E7.
  - E2: no default style per format.
  - E4: yes to the reference-Reel idea.
  - E5: the rider makes their own brand assets; needs an upload option.
  - E6: styles also saved in the Google Drive backup.
- **Recording:** F1 (DJI Mic Mini, 2 transmitters + 1 receiver), F2, F3, F4.
- **Learning:**
  - G1: views and likes entered by hand.
  - G2.
  - G3: optional, not automatic.
- **Later:**
  - H1: iPad, to discuss.
  - H4: long videos max 3–5 min.

**Not now (4):**
- B2: richer ready notification.
- D5: effects track (parked).
- H2: Story card and 3D video in Studio.
- H3: posting plan.

**No (2):**
- C5: first-time introduction ("don't need training for now").
- D11: beats and sound effects.

**Follow-ups (7 Oct):**
- A2 decided: the number of pieces follows the ride's strong moments (a story, a reaction, a funny or useful line, a fast stretch). At least 1, at most 6. No two pieces open on the same moment or share a shape.
- H4 decided: vertical only, long videos max 3–5 min.
- F1: the DJI Mic Mini has mono and stereo settings; the rider will send a screenshot.
- H1 (iPad): options discussed without publishing the app (see the chat). The rider is to choose.
- F1 update: on the rider's DJI Mic Mini, the **mono** setting gives two different audio streams (one per transmitter). Plan:
  - Keppo records the receiver's two channels as they arrive and checks whether they differ.
  - If they differ: two tracks, voice and engine. If they're the same: one track.
  - A two-mic test in Moments settings shows a level meter per transmitter, so the rider can see which is voice and which is engine and swap them if needed.
- H1 (iPad): skipped for now. Option 1, the phone serving the editor over Wi-Fi, is the one to think about later.

---

## Rider's review (8 Oct, after testing the APK)

Planned, not built yet. Waiting for the rider's answers on the questions below.

### R1 Suggest every possible story from a ride
- **Today:** Gemini suggests 1–6 pieces, and only the strongest moments.
- **Change:** "Show all" asks Gemini for every story, reaction, useful line and fast stretch in the ride, not just the best few, then lists them best first. It's one more request, made only when asked.

### R2 Open a suggestion and read everything
- **Today:** the Script view shows each section's shots and a short quote of what was said.
- **Change:**
  - The story in full, written out.
  - For each shot, two lines: what was said (the transcription) and the caption that will show on screen.
  - Tap a caption to change it. Captions can be split, joined or hidden.
  - "Add a clip" in the Script view itself, from this ride, another ride, Saved clips or Search, without going to the editor.

### R3 See transitions
- **Today:** the editor's Transition tab picks the kind and length, but the preview shows a straight cut, and the timeline shows nothing between clips.
- **Change:**
  - A transition plays in the preview when it's picked: about 1.5 s around the cut, on a loop.
  - The timeline shows a small mark between clips with a transition. Tap the mark to change it.

### R4 Styles inside Studio
- **Today:** the Ready screen has Style, but full editing and creating is only on the Styles page.
- **Change:**
  - A Style button in the editor too: switch style, or "Edit this style" in a sheet without leaving the edit.
  - "New style" in the same sheet.
  - After a video is made: "Save this style", which makes a style from the video's current look (captions, colour, cuts, pace, stickers), then names it.

### R5 Styles feel restrictive (to discuss)
Where they're limited today:
1. One style covers the whole video: the hook, the middle and the ending all look the same.
2. A style is all or nothing: you can't take the captions from one style and the colour from another.
3. Each part is a fixed list (caption looks, text animations, transition kinds), with little control over size, position, font or colour.
4. A style changes the look only, not the shape (how long the hook is, a stats card in the middle, how it ends).
Options, which can be combined:
- **A. Mix and match:** a style is made of parts (Captions, Text, Colour, Cuts, Overlays, Sound), and each part can come from any style.
- **B. Per section:** a different look for the hook, the middle and the ending.
- **C. More freedom in each part:** any colour, size, position and font for captions and text; your own transition length; colour sliders.
- **D. Shape templates:** a style can carry a structure ("3 s hook → fast montage → talking → stat card → sign-off").

### R6 Your Reels: select several, then delete or save
- **Today:** long-press selects several, with Delete only.
- **Change:** add "Save to phone" (all selected go to Movies/Keppo Moto) and "Share" (several at once) to the selection bar.

### R7 Petrol price updates itself
- **Today:** after a ride with a stop at a pump, Keppo asks about the fill and takes the amount from the card SMS. The rider types the price or litres.
- **Change:**
  - When a pump stop and its card SMS are found, Keppo looks up today's petrol price for that city online, works out the litres (amount ÷ price) and saves the fill. No input.
  - A notification says what was saved, with Undo: "Saved fill · ₹500 · 4.6 L at ₹103.4 (Pune)".
  - The price is looked up through Gemini with Google Search. There's no free official price feed. If the lookup fails, the last known price is used and marked as "about".
  - It only looks when a pump stop is found after a ride, never by tracking location in the background, so there's no extra battery use.
- **Risk:** a card payment at the pump that isn't fuel (snacks, air) would be saved as a fill; Undo covers it.

### Decisions (8 Oct)
- R1–R4 and R6: build as planned.
- R5: **more freedom in each part** (option C): any colour, size, position and font for captions and text; your own transition length; colour sliders. Mix and match, per-section looks and shape templates are not wanted for now.
- R7: save on its own, with Undo.
- Build order: R6 → R3 → R2 → R1 → R4 + R5 → R7, one commit each, one APK at the end.

### Built 8 Oct
- **R6:** Your Reels selection bar has Save to phone and Share (several at once) next to Send to Journal and Delete.
- **R3:**
  - Picking a transition, or tapping its mark, plays across the cut three times.
  - The simple preview now draws the transition's graphic and camera move too.
  - Every cut with a transition has a mark on the timeline; tap it to change it.
- **R2:**
  - Tapping a suggestion opens its script, with "Make this" (cards also have "Make it").
  - The story is written out. Each shot shows what was said and its caption; tap a caption to change or hide it.
  - "+ Add a clip" in each section (this ride, other rides, Saved clips), and ✕ to remove a shot.
- **R1:** "Show all" next to "Suggest again": up to 15 pieces, smaller ones too (one more Gemini request).
- **R4:**
  - A Style button in the editor puts a style on the edit (Undo takes it off).
  - Edit any style, or make a new one, from the same sheet.
  - "Save this style ›" on the finished video.
- **R5:**
  - Captions: any colour, the colour of the word being said, a font, size and height sliders, a dark band behind them.
  - Text: font, size and height.
  - Transitions: an exact length (0.15–2 s).
  - Colour: sliders.
- **R7:**
  - After a ride, a pump stop with a card SMS amount: the city's petrol price for today is looked up (Gemini with Google Search), the litres are worked out and the fill is saved.
  - A notification shows it, with Undo; Undo brings back "Filled up here?".
  - If no price is found, the last known one is used and marked "about".
  - Not tested against live Gemini yet: the first real fill-up will show whether the price search answers well.

### Rider's feedback (8 Oct, afternoon) · Built 8 Oct
- **Only 1 suggestion from a ride with lots of talking:**
  - Gemini is now asked for at least 2–4 pieces when there's lots of talking (4 for 24+ talking clips).
  - When some of its suggestions can't be used, Studio says how many and why.
  - The talking time is shown in minutes, with how many clips have the rider's voice.
- **Connection drop while reading clips** (error log, 8 Oct 12:56):
  - The phone lost the internet for a moment. Studio now waits up to 30 s for it and reads those clips again.
  - It's logged as a warning, not an error.
- **New: caption clips:**
  - One uncut 15–20 s riding shot (accelerating, leaning, a fast or pretty stretch) with 1–3 funny or relatable lines written on it, shown in turn.
  - Suggested whenever there are riding shots with speed, acceleration or lean, and in "Show all".
  - A "Caption clip" quick prompt under Ask for something.
  - The lines show and can be changed in the Script view.

---

## Build order (approved 7 Oct)
Each build ends with one APK to test on the phone before the next starts.

1. **Suggestions you can trust** (A1, A2, A3, A4, B1, B3). Medium-large. **Built 7 Oct, waiting for the rider's test.**
   - Built: every clip read once (voice and filmed-on-purpose first, no cap); making a suggestion never reads again; gentler checks and the fill to 70%; "Planned 0:30 · 0:24 after checks" on cards; Gemini's answer and the fixes kept for Studio details.
   - Built: a foreground service while making; suggestions are made by the background maker; the queue is saved and picked up when the app starts again.
   - Built: after each ride (above 50% battery straight away, otherwise on Wi-Fi or with the mobile-data switch), a notification that opens the ride's Studio, and the top suggestion made. Switches in Studio settings › After each ride.
   - Built: warnings in the error log, "N new" on Profile, the "made a simpler way" note on a Reel, and Send Studio details.
   - Not covered: a Reel being remade from Edit, Script or Voice-over keeps going when you switch apps, but stops if the app is swiped away (suggestions and Make all carry on).
   - Read all clips once and remember them; the new rule for how many pieces; gentler checks; show plan vs result.
   - A real background service (keeps going in other apps and after a swipe-away).
   - Auto-read and suggest after each ride, with the battery and Wi-Fi rules; make the top suggestion automatically.
2. **Studio pages, saved clips, two mics recorded** (C1, C2, C3, C4, C7, F1 recording part, F2). Medium. **Built 7 Oct.**
   - Built: the ride's Studio page (Studio button on the ride, next to Share; Share keeps Story card and 3D video); suggestions first, clips folded; quick prompts and recent asks.
   - Built: Studio tab "Ready for you" cards and each ride's state (ready, made, not read, waiting for a connection or Gemini).
   - Built: saved clips (Keep in the clip viewer, Save clip in the strip and the editor), auto-tagged, with tag search and editing in Your Reels › Saved clips, and in the editor's + Clip. Draft mark and "Not posted yet" filter.
   - Built: two-mic recording from a USB-C receiver (voice in the clip, engine next to it when the two sides differ), the two-mic test with swap, and no Bluetooth headset mic unless allowed.
   - Not yet: the engine track for long "film now" recordings (event clips only for now).
   - A ride's own Studio page; the Studio tab with "Ready for you" and ride states; quick prompts.
   - Saved clips with auto tags; drafts.
   - Record both DJI channels and keep them, with the two-mic test; headset mic off by default. (So the next ride already captures voice and engine separately.)
3. **Editor foundation: layers and tracks** (D1, D2, D8, D9). Large; the biggest build. **Built 7 Oct.**
   - Built: layers (Corner, Top half, Bottom half, Below left and right for the stack, Full), dragged and pinched on the preview, move points that glide, shape (square, rounded, circle), frame, opacity, rotate, its own sound. Made with Media3's compositor; if a phone can't, they're drawn frame by frame (slower) and the Reel says so.
   - Built: sound tracks: detached clip sound (J and L cuts), the engine mic track from two-mic clips (follows its clips), voice-over, music, layers' sound; volume points and fades; a mixer with volume, mute and solo per track, the dip under your voice, and a voice clean-up (high-pass).
   - Built: pinch to zoom the timeline, snapping to cuts and markers, select several clips to move, delete or paste settings, copy a clip's settings, markers, a History list, frame stepping, full-screen preview, Before (compare), and autosave that carries on after the app closes.
   - Built: an exact preview (Media3's composition player) with graphics, colour, camera moves and the mix; layers show as stills in it. If a phone can't play it, the simple preview is used, labelled, and a warning is logged.
   - Not yet: small preview copies of clips for long Reels; stills instead of moving layers in the preview.
   - The edit becomes multi-track: video layers (free size, side by side, 3-stack) and audio tracks (voice, engine, voice-over, music) with volume curves, auto-duck and detached sound.
   - Pinch zoom, snapping, no gaps, multi-select, copy and paste settings, markers, autosave.
   - A preview that plays the real composition (Media3's composition player), with small preview copies of clips.
4. **Clip tools, transitions, export** (D3, D4, D10). Large. **Built 7 Oct.**
   - Built: in the editor, a clip's tools in groups. Edit: split, move, duplicate, replace (same length), use another part (slip), sound, detach, copy and paste settings, save, delete. Speed: 0.25× to 4×, ramps (slow in the middle, fast then slow, slow then fast), reverse (made once, up to 10 s). Look: colour (exposure, contrast, saturation, warmth), the style's look on or off, rotate, mirror. Frame: crop in and out, zoom and pan moves, punch in on words. Freeze holds the frame under the playhead for 2 s.
   - Built: a transition on every cut: Style, Cut, Fade, Flash, Zoom punch, Whip, Glitch, or any style's own (Slash, Shutter, Sun, Card); short, normal or long; "Use on all cuts".
   - Built: Export: Instagram Reel, YouTube Short, Story (cut into 60 s parts), WhatsApp (720p, small); 4K and 60 fps when every clip allows; a size estimate; runs in the background and saves to Movies/Keppo Moto.
   - Not covered: stabilise (dropped). The simple preview plays speed changes at normal speed; the exact preview and the saved video play them right.
   - Speed and ramps, freeze frame, reverse, reframe with keyframes, colour, crop and rotate, reuse another part.
   - Transitions per cut; export quality, fps and presets per platform.
5. **Text, captions and stickers** (D6, D7). Medium. **Built 7 Oct.**
   - Built: text looks (the style's own, Plain, Outline, Box, Handwritten), colours, size, turn, alignment; drag anywhere and pinch to size and turn; animations in and out (Fade, Pop, Type on, Slide, Bounce, None); new text takes the last styled text's look (answers the D6 question: per Reel for now).
   - Built: the captions' look for the whole Reel: any style's captions or Plain, size, height, and lighting up each word as it's said (karaoke) or the whole line; fix a word (Edit words) and read one clip again (one Gemini request).
   - Built: stickers: live speed gauge and lean (from the ride's numbers), any phone emoji (a ride set to pick from, or type one), an arrow and a pulsing circle; drag, pinch, turn, each with its own time on a stickers track.
   - Not yet: Giphy (later, as decided); route, G-force, weather and place stickers.
   - Text styles and animations; karaoke captions and fixing single words.
   - Speed and lean stickers; phone emoji.
6. **Styles** (E1–E7). Large. **Built 7 Oct.**
   - Built: a style is a full recipe: base look, captions (look, size, height, word highlight), text (look, colour, in and out), transitions (which, length, every cut or between sections), pace of riding shots, punch-in on words, colour, overlays (corner speed, speed and lean stickers, map, route opening, stats, Keppo mark, brand kit), sound (dip, voice clean-up).
   - Built: Styles page (palette icon in the Studio tab header; also "All styles" from a Reel): every style previewed on your own best clip, favourites and how often each was used; the style editor with one part at a time, Reset per part, Shuffle and Share.
   - Built: new styles by duplicating, "Keep this Reel's look as a style" (Style on a finished Reel), describing it in words (Gemini) or from a video you like (Gemini looks at a few frames); share as a code and add a code from another rider.
   - Built: brand kit: upload your logo and font, your handle and colour; on pieces whose style has Brand on (handle low left, logo instead of the Keppo mark, your font for plain text and captions).
   - Built: styles and the brand kit go in the Google Drive backup. Suggestions choose among your styles (favourites and the most used first); a finished Reel can be made again in any style.
   - Decided for you (open question E2): Styles lives in the Studio tab header, with a Style button on a finished Reel.
   - The recipe model; the Styles page with live previews on your clips; the detailed style editor.
   - Create styles by duplicating, from a Reel, from words, or from a reference Reel.
   - Brand kit upload; styles in the Drive backup; Gemini picks from your styles.
   - Comes after builds 3–5 because a style is made of their parts.
7. **Recording extras** (F3, F4). Small. **Built 7 Oct.**
   - Built: with two mics, a rev or exhaust pop on the engine mic (well above the engine's usual level, held a moment, then a 15 s rest) films a moment like hard braking; labelled "Engine"; switch in Moments › What to film.
   - Built: the coach's "film next time" shots show on the pop-up for the ride's first 30 s, one every 6 s.
   - Also (rider's request): Profile › Error log entries can be ticked and sent or copied on their own, one or several.
   - Engine moments from the engine mic; shot list on the HUD pop-up.
8. **Search and learning** (C6, G1, G2, G3). Medium. **Built 7 Oct.**
   - Search what you said and what Gemini saw.
   - Views and likes entered by hand, feeding suggestions; post text per platform; the optional hook check.
   - Built: Studio › Search (magnifier in the Studio tab, and in the editor's + Clip) finds words you said and things Gemini saw (rain, dogs, flyovers) across every ride. Gemini looks at clips a few at a time while the free limit lasts; after a ride it looks at the newest 24. A found sentence can be kept in Saved clips.
   - Built: "How did it do?" on the finished video: type the views and likes from Instagram. Your Reels shows the views; Studio's suggestions lean towards the Reels that did best.
   - Built: "Write for YouTube" (title and description, plus an Instagram caption if there isn't one), each with Copy.
   - Built: subtitles in English or Hindi: Gemini translates the captions and the video is made again.
   - Built: "Check the hook": three frames of the first 3 seconds and the hook line go to Gemini, which says whether it would stop the scroll and what to change.

### Risks to know
- **Stabilise (D3):** Android has no built-in stabiliser. It needs a large library (OpenCV, +20 MB or more) or a simple version of our own. It may need to drop or come later.
- **Reverse (D3):** works, but needs a slow pass over the clip first.
- **Layers and the matching preview (D1, D9):** these need a newer Media3 (its multi-video compositing and composition player are still marked experimental).
- **Gemini requests:** "what Gemini saw" search adds requests on top of reading clips, so it is planned last and runs only when the daily allowance has room.
- D3: **stabilise dropped** (rider, 7 Oct).
- Layers fallback plan:
  - Pin the Media3 version, so nothing changes unless we choose to upgrade.
  - If the composition preview fails on the phone, fall back to today's preview, with layers shown as still frames.
  - If layered export fails, the existing retry chain applies; the last resort draws the second video's frames into the overlay ourselves (slower, but works on any phone).
- **Knowing when something failed or fell back** (proposed with build 1, extending A3):
  - Fallbacks are logged as "Warning" entries in the error log, not only errors, with what failed, which fallback was used, the phone, the Android version and the app version.
  - The rider sees it where it happened: a small note on the Reel ("Made with a simpler method on this phone · Details"), and a "Simple preview" label in the editor when the preview fell back.
  - A count on Profile › Error log when there are new entries ("2 new").
  - Send to the developer: the existing Send/Copy, plus "Send Studio details" for a Reel (its plan, checks, fallbacks and Gemini's answer).
