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
- **"Show all" still gave 1** (8 Oct): it relied on Gemini writing up to 15 full scripts in one answer.
  - Now, after Gemini's ideas, Show all lists a piece for every other part of the clips, in filming order, marked "From your clips":
    - each take of what the rider said (lines less than 2.5 s apart, up to 45 s), uncut
    - each riding shot without words, as a caption clip, with Gemini writing the funny lines for all of them in one more request
  - Make all still makes only Gemini's ideas, not dozens of videos.

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

---

## Studio redesign: simple, like Instagram's editor (proposal, 8 Oct, not built)

The rider: "Studio got way too complicated; make it very simple and intuitive", with gestures, not only buttons. Prototype: https://claude.ai/artifact/DmWPHLWsGSQXBcPhBhw3QP (private).

- **Three places:**
  - **Studio:** a feed of ideas, one big card at a time; Search and Your Reels icons on top; an Ask pill at the bottom; settings behind ⋯.
  - **Editor:** opens a suggestion straight away, playing live (no making first). It replaces Ready, Script and Edit.
    - Top: close, Ideas, →. Then the preview, play/time/undo, the timeline (clips, captions, sound).
    - A bottom bar that changes with the selection: nothing, a clip, a caption or text, or the sound.
  - **Share:** after →. The video is made once, here: cover, captions for Instagram and YouTube, Check the hook, Save, Share.
- **Gestures (buttons stay too):**
  - Ideas: swipe up/down for the next idea, swipe left to hide, tap to open, hold for options.
  - Video: tap to play/pause, swipe sideways for the next style, hold to see it before your changes, swipe down to close, drag/pinch text.
  - Timeline: drag to scrub, pinch to zoom, tap to select, drag edges to trim, hold and drag to move a clip, flick up to remove. Tap a cut mark for the transition picker (swipe the circles).
  - Sheets: swipe down to close. Share: swipe down to go back.
- **Open questions:**
  - Making the video only at → (a 30–60 s wait at share; the top idea is still made after each ride)?
  - One feed across rides, or a list of rides?
  - The story inside Captions, or its own button?
  - What can go behind ⋯ or away?
- **Motion (rider, 8 Oct):** things move, never just appear.
  - Tapping a cut: the timeline glides to centre it, the clips part (left ones slide left, right ones right) with the cut mark growing between them, and the transition picker rises in under the timeline. Circles slide to the centre as you swipe; Done glides everything back.
  - Ideas slide up and down like a pager.
  - The editor zooms open; sheets rise from the bottom and their backdrops fade.
  - The bottom bar's tools rise in one by one when the selection changes; a selected clip lifts.
  - The style name pops on the video when you swipe; Share slides in from the right.
- **Previous rides, own videos, adding more (rider, 8 Oct):**
  - Studio has a strip of rides under the title (newest first, each with its date and number of ideas), then "All rides ›" for older ones. Tapping a ride shows its ideas.
  - A ride with no ideas yet shows "Find ideas".
  - A + at the top starts a new Reel: from the phone's videos, from Saved clips, or from a ride's clips in your own order. The editor opens with them.
  - In the editor:
    - + at the end of the clips adds videos: from the phone, this or another ride, Saved clips, or over the video (picture in picture).
    - "+ Sound" under the sound rows adds music, a voice-over or a sound from another clip, each on its own row from the playhead, as many as you like. The video above shrinks a little to make room.
- **Every inner page redesigned (12 Oct, prototype only):** Apple-like lists, sheets and context menus; Procreate-like panels, quick sliders and gestures. On the same canvas:
  - **Library and settings:**
    - Your Reels: Reels, Saved clips and Deleted as one segmented control; hold a Reel for its menu; swipe across tiles to pick several.
    - Search: what you said, what's in it, or both; results grouped by ride.
    - Making in the background: a status pill on top, then a queue sheet with Gemini's state.
    - Settings: grouped lists.
  - **Editing a clip:**
    - Speed: quick sliders for speed and volume on the video's edge; speed curves you can see.
    - Look and crop: pinch, drag and twist on the video; filter looks; one adjustment dial at a time.
    - Text: typed straight on the video, with a font and colour bar above the keyboard.
    - Captions: Look and Words; tap a word on the video to fix it.
  - **Sound, layers and history:**
    - Mixer: faders side by side; double-tap to mute.
    - Voice-over: hold to talk; takes on the timeline.
    - Layers: layout presets; live speed, lean and map stickers.
    - History: two fingers to undo, three to redo; Changes, Versions and Marks.
  - **Styles:**
    - Library: groups on the left, like Procreate's brushes; + for words, a video you love, a Reel or a code.
    - Style editor: like Brush Studio, with the live preview on top.
    - Brand kit.
    - What Studio learned: habits as cards; swipe left to forget.
  - **Ideas and sharing:**
    - Ideas sheet: "Change this one" or "Make a new one", plus quick prompts.
    - The story: reads like a note.
    - Share: Reel, Short, Story or WhatsApp; cover; caption copied for you; subtitles, hook check and Journal.
    - After posting: views and likes, your Reels by views, "Make one like this".
- Also seen on the rider's phone (8 Oct): the editor showed "Simple preview" on every clip. One failure turns the exact preview off for the whole session. The cause is in the error log ("Studio preview"); waiting for it.

## Studio redesign: build order (approved 12 Oct: "make all, start building")

One commit per build. The APK comes at the end, after asking. Defaults taken where questions were still open:
- The video is made at → (Share); the top idea is still made in the background after each ride.
- Rides are a strip on Studio.
- The story lives in its own page, reached from Captions › Words and from Ideas.

1. **S1 Flow:** the new Studio (rides strip, idea pager, + New Reel, Ask pill). An idea opens straight in the editor, playing; → goes to Share, which makes the video. The exact preview is retried per video instead of off for the session.
2. **S2 Editor shell:**
   - Top bar (close, Ideas, →). Video gestures: tap, swipe for style, hold to compare, swipe down to close.
   - Context bottom bar; + Add videos and + Sound sheets, with many sound rows.
   - Transition marks and the picker, with the clips parting.
   - Built: songs and sounds from the phone each get their own row (they dip while you talk); the Ideas pill switches ideas in place (an unsaved edit stays as a draft) or asks Gemini for a new one, which opens in the editor. The clip's speed, look and crop still use the old chips until S3.
3. **S3 Tools:** speed with quick sliders and curves, look and crop dial, text on video, captions (Look/Words, fix a word), layers and stickers, mixer faders, voice-over, history (two-finger undo, three-finger redo).
   - Built in three commits (S3a–c). The voice-over is recorded in the editor now; its captions are read when → makes the video. History has Changes and Marks (Versions waits for S4's library).
4. **S4 Library:** Your Reels (segmented, hold menu, swipe-select), Search, the making pill and queue sheet, grouped Settings.
5. **S5 Styles:** library with groups, Brush-Studio-like editor, brand kit, what Studio learned (swipe to forget).
6. **S6 Ideas and sharing:** the Ideas sheet, the story page, Share (places, cover, caption copied, subtitles, hook, Journal), After posting with a next-day reminder.
7. **S7 Placing the rest, plus the new ideas:**
   - The nine missing pieces: leave out a clip, safe zones, frame step, copy and paste settings, timeline multi-select, Send all to Journal and Make all, Write for YouTube on Short, Gemini issues in the pill, the title.
   - Hold an idea for a 3-second peek; a "Continue editing" card; snap haptics; Make all overnight.

**Status (12 Oct): S1–S7 built and pushed.** Where the nine missing pieces live now:
- Leave out a clip: the ride's own Studio page, clip strip (hold a clip).
- Safe zones, frame step, copy and paste the look, picking several clips: the editor (tools, the ‹ › by the time, Copy look / Paste look, Select more).
- Make all and Send all to Journal: Studio tab › ⋯ for the ride shown (and the ride's own page).
- Write for YouTube: Share › Short.
- Gemini issues: the pill on the Studio tab says when Gemini is free again; its sheet has the error log.
- The title: Share page, tap it.
- New: hold an idea to peek at its opening clip; "Continue editing" on the Studio tab when an edit was left with changes; a tick when the playhead snaps to a cut, a style changes or a transition is picked; Settings › Make every idea overnight (while charging).

## Ride ideas: long reel, variations, Make all, top slots (planned 13 Oct, not built)

From the rider's feedback on the ride's Studio page (13 October, two rides, 20 clips, 6 with voice). Many ideas are fine; duplicates are not. No long piece was suggested, because the prompt asked for 2+ minutes of talking.

1. **Long reel, 1–2 minutes.**
   - `PieceFormat.LONG` becomes "Long reel", 60–120 s, 9:16. It no longer means a 2–5 min YouTube video.
   - The rule "only if there's enough talking for 2+ minutes" goes. In its place: when the ride has at least about 75 s of usable footage, always include one long reel. It's a recap of the ride in order.
     - Riding shots carry it, with music, speed and lean stickers, and the map at the end.
     - Talking and reactions are the highlights, not a requirement.
   - Checks: the long reel may be trimmed to 60 s at the shortest. If the footage can't fill 60 s, it's left out quietly.
2. **No duplicates: variations under one card.**
   - Ideas that open on the same clip, or share most of their clips, become one card: the best one shows, and the others become its variations. Grouping is done in the app (pure, unit-tested), so it works whatever Gemini returns.
   - Under the card:
     - a "2 variations" row of chips (e.g. Caption clip · Short · Story). Tapping one swaps the card to it. Make it and Read the script follow the one shown.
     - "Try something else with this": asks Gemini for a new take on the same clips. The answer is added as a variation.
3. **Make all.**
   - The button says "Make all · N videos" and makes every card on the page, Show all's ideas included. Variations are made only when picked.
   - It uses no Gemini allowance (videos are made on the phone), only time and battery.
4. **Top slots made automatically after every ride.**
   - Up to three, each from a different card:
     - **Long reel**
     - **Best moment**: the top caption clip or short
     - **Your story**: the top talking piece
   - One notification: "3 ready from 13 October | Uttam Nagar to …: Recap 1:12 · …". On the ride's page they sit at the top, marked Ready.
   - Settings: "Make the top idea" becomes "Make the top 3 after each ride" (on).
5. **Small fixes on the same page:**
   - Remove the yellow "Planned 0:06 · 0:04 after checks" (the thumbnail shows the length).
   - "1 shots" → "1 shot".
   - While loading, say what's coming instead of empty grey boxes.

Tests:
- Grouping: same opening clip, overlapping clips, and different ideas kept apart.
- Slot picking: no two slots from one card, and a slot is skipped when nothing fits.
- The prompt asks for a long reel only when there's enough footage.
- The long reel's length range.

Not in this round (raised earlier): joining two rides from the same day into one long reel, and a week's vlog.

## Series, and the rider's picks from "What else" (planned 13 Oct, not built)

Rider's answers: yes to 1, 2, 3, 5, 6, 7, 8, 9 and 11; maybe to 10; no to 4 (rough clips look fine on Reels, so none are left out). Build in this order after the round above (long reel, variations, Make all, top 3). Ask before the APK.

### A. Series folders
- **Several series at once,** each with an on/off switch. They run alongside ordinary Reels that belong to no series.
- **Set up once:**
  - name
  - what the series is, in the rider's words (Gemini's brief)
  - format and length range
  - how many per ride: 1, or every moment that fits
  - look: style, transitions, text and captions, intro card with "Ep N", outro, cover template, caption template
- **After each ride:** one Gemini pass per active series, with its brief and the ride's clips.
  - It makes the episodes that fit, automatically.
  - Or it says "Nothing for <series> today".
- **Clips can be shared:** a clip may be used by a series episode and by an ordinary idea. On the ride's page, series episodes sit at the top with a series badge.
- **Your Reels › one folder per series:**
  - episodes in order, each marked Ready / Posted / Draft
  - the number is given when shared
  - a header with the brief, the look and "Make next episode"
- **Shared look:** a change in one episode stays in that episode unless "Use for the whole series" is tapped. Then unposted episodes are updated too.
- **Learning per series:** notes and how each episode did shape only that series.
- **Suggest a series:** when several Reels look alike (same kind of moment, format and style), suggest "Make these a series?". It's set up with a brief Gemini writes from those Reels, which the rider can edit.
- **Model:**
  - `Series(id, name, brief, format, perRide, styleId and look overrides, captionTemplate, active, nextEpisode)`
  - `ReelProject.seriesId`
  - The old single series name and episode in StudioPrefs become the first series.

**Status (13 Oct): A built.**
- An episode's number is given when it's made, not when it's shared, because "Ep N" is drawn on the video.
- "Make these a series?" writes a starting brief from the Reels' titles (no Gemini request); the rider can edit it.
- "Use its look" is under each episode in the folder.

### B. Swipe review
- Ready videos play full screen, one after another.
- Gestures:
  - swipe up: next
  - Share: caption copied
  - Edit: opens the editor
  - swipe left: skip
- Opened from the "N ready" notification and from the ride's page.

### C. One post a day
- Ready videos are lined up one per day; series episodes keep their order.
- Every day there's a reminder at the rider's usual posting time (learnt from when they shared before).
- **Home:** a "Today's post" card **below the Start ride card**, with the video, Share and "Swap".

**Status (13 Oct): B and C built.**
- The line of videos to post: oldest first (made in the last 30 days), skipped ones last, and a series' episodes in episode order.
- Entry points:
  - "Watch N ready" in Your Reels
  - "Watch N ready to post" on a ride's page
  - the Home card
  - the daily reminder

### D. "Clip that" by voice: dropped (13 Oct, the rider's call)

### E. Better videos without editing
- **Jump cuts:** long pauses, "umm"s and false starts are cut from talking pieces, using the timed caption lines. On by default, with a switch per video.
- **English subtitles for Hinglish:** an option to show each line with a small English line under it. The translation comes in the same Gemini request as the captions (no extra request).
- **Engine-sound pieces ("Sound on"):** a fast stretch with only the engine channel (DJI stereo) and a speed sticker, no talking.
- **Two hooks per video:** each video gets a second opening (a different first shot or line), as a variation. One can be posted now and the other later.

**Status (13 Oct): E built.**
- Jump cuts and English subtitles each have a switch for new videos (Settings, and the ride's settings sheet).
- For one video: subtitles have a switch in the editor (Captions › Look), and clips read before subtitles existed get their English in one text request. Jump cuts can be undone by trimming in the editor.
- Sound on: up to 2 per ride, from the fastest clips (35 km/h or more) with 8 s or more without words.
- Second hooks: made without Gemini for the top 3 ideas, shown as takes under their cards.

### F. Grow the page
- **Theme compilations across rides:** from the saved clips' tags and words.
  - Examples: "Delhi traffic police, 6 times · 0:48", "Every 'bhai kya kar raha hai' this month".
  - Suggested when 4+ clips share a theme. A compilation can become a series.
- **Learn from what worked:** how posted videos did (views noted after posting) ranks the next ride's ideas, formats and series. For example: "Caption clips get you 3× the views, so they come first".
- **Maybe, decide later:** 3–5 hashtags and the ride's area added to the copied caption, matched to Reels or Shorts.

**Status (13 Oct): F built, without hashtags (still "maybe").**
- Compilations: things Gemini saw (seen in 4+ clips) and phrases said (in 4+ clips), from 2 rides or more.
  - Offered one at a time in Your Reels, with Make it, Not now and Next idea.
  - Made from the newest ride's Studio, with the other rides' clips borrowed.
- Learning:
  - Once 4 videos have views, a kind doing 1.5× better goes first in new suggestions.
  - Gemini is told so, and Your Reels says "What works: …".

### Later
- **Blur faces and number plates:** after the rider gets a second camera.
- **Weekly long video, and series episodes built from several rides:** last of all. **Ask the rider for permission before building this one.**

## Bugs and requests from the rider (13 Oct evening, not built yet)

1. **Preview: transitions lag, plain playback is fine.**
   - Likely causes:
     1. Every clip in the preview is its own item with its own effects: overlay, colour, crop and camera move. At each cut, Media3 rebuilds the effect chain (shaders and textures) for the next clip, and that rebuild lands right where the transition plays.
     2. The next clip starts in the middle of its file, so the decoder first decodes from the keyframe before it (clips have a keyframe every 1 s).
     3. The overlay is drawn at full 1080×1920 on every frame, which adds to both.
   - Planned fix:
     1. Measure first: frame timing at cuts, with transitions on and off.
     2. The preview keeps the same effect chain for the whole edit, so nothing is rebuilt at cuts.
     3. The overlay is drawn at half size in the preview only (540×960); the made video stays full size.
     4. The next clip is decoded ahead, before the cut.
2. **After an edit the editor drops to the simple preview, and in full screen the simple preview lags too. It should never use the simple preview.**
   - **Why it drops:** after every change, the exact preview is rebuilt. A single error then turns it off for the rest of that edit: a playback error from the player, a failure while putting the edit together (a missing frozen-frame or reversed file), or no ride card. The error log has it as "Studio preview: The exact preview didn't play on this phone".
   - **Planned fix:**
     1. **Never switch on an error.**
        - Rebuild the exact preview from scratch (a fresh player) and try again, up to 3 times.
        - Meanwhile, keep the last frame on screen with "Updating preview…".
        - If it still fails, say so on the video ("Preview couldn't update: tap to try again") instead of quietly switching.
     2. **Make the rebuild itself safer:**
        - wait until edits stop for 0.3 s
        - pause before swapping in the new edit
        - check every file the edit needs before handing it over
        - log the exact reason when it fails
     3. **Remove the simple preview from the editor** once that holds on the rider's phone.
     4. **Lag:** the fix in item 1 (one effect chain, half-size overlay, next clip decoded ahead) applies here too.
   - **Cause found (rider's log, 13 Oct 15:26):**
     - In Media3 1.5.1, `CompositionPlayer.setComposition` can only be called once per player: it does `checkState(composition == null)`. Confirmed in the library's code.
     - So the first edit always threw, and the editor dropped to the simple preview every time. This is a design limit, not a phone problem.
   - **Done (322bd2d):** option A, a fresh exact-preview player after each edit. Still open from the fix list above:
     - retrying instead of switching to the simple preview on another error
     - "Updating preview…" while it rebuilds
     - option B
   - **Options:**
     - **A. Quick fix:** a fresh exact-preview player for each version of the edit.
       - The new one is put together in the background while the old one keeps showing its frame, then swapped in, and the old one released.
       - About 0.3–1 s after each edit before the change shows.
     - **B. Rebuild the editor's preview (recommended for "never again"):** one player for the whole edit session that draws the video itself.
       - **Playback:**
         - clips play through a normal video player into the app's own drawing surface
         - the next clip is opened before the cut
       - **Drawing:** each frame, the app draws colour, crop, camera moves, speed, transitions, captions, text, stickers and layers. It uses the same drawing code as the made video, so the preview matches it, at preview size.
       - **Edits:**
         - Look changes (text, captions, colour, transitions, stickers) show on the next frame, with nothing rebuilt.
         - Changes to clips (order, trims, speed) only update the list of clips, which a normal player handles any number of times.
       - **Result:** no fallback, no reload pause, smooth cuts.
       - **Size:** the biggest job in this list.
     - **C. Check a newer Media3:** whether a later version lets one composition player take a new edit. That fixes A's cause, but not the lag at cuts, and needs a library upgrade tested across the editor and the made videos.

## Style redesign (Keppo Studio visual system)

Done in one pass on the drawing code, because the token rewrite, the restyled vibes and the new vibes all live in the same file (`StudioArt.kt`):

- **Tokens** (`studio/Tokens.kt`): 9 type sizes, 8-swatch palette, 3 scrim alphas, 2 shadows, 3 radii, 4 durations, 5 easings, the layout slots and the safe-area clamp. The defaults table is `Vibe.spec`.
- **Seven vibes**: Clean, Cinematic, Bold, Playful, Film, Editorial and Native (the last three are added at the end of the enum, so saved Reels still load). Each vibe has its own type, accent, captions, title, badge, stats and section transition.
- **New caption styles**: Clean, Type and Strip.
- **Transitions**:
  - New: Dip, Zoom-through and Light leak.
  - Rebuilt: Sun is now Pop wipe, and Card is now Paper slide.
  - The picker shows only the current vibe's transitions.
  - No transition shows text any more.
  - Each half of a transition is limited to 40% of the clip it falls on.
- **Preview parity**:
  - The export and the editor preview both draw through `ReelOverlay`.
  - The caption tap area matches where the caption is actually drawn.
  - The preview camera uses the same fill clamp as the export.
  - The exact preview gets a fresh player for every edit (fixes bug 2: "falls back to simple preview").
- **Global fixes**:
  - The speed shows only once: the corner badge hides while a speed sticker is up.
  - Bold's beat kick runs only when a song tempo is set.
  - Word timing is weighted by word length, and the highlight lands 60 ms before the word.
  - Caption groups never leave a single word alone.
  - The English line sits inside the caption block.
  - The dark band is sized to the measured caption block.
  - The caption colour control is split into Text colour and Background colour.
  - The font options no longer offer the vibe's own font twice.
  - The opening text ends before the hook clip does.
  - Section text goes through the same path as timeline text.
- **Film**:
  - Grain and flicker.
  - Gate weave, plus a small jump at each cut.
  - Lifted blacks, using an offset in a custom RgbMatrix.
- **Editorial**: graphics animate at 12 frames a second (stop-motion).
- **Native**: no route-opening card.
- **Frame checker**: `shots/StudioFrames` renders still frames to `app/build/frames`.

### Bold rebuilt as "Poster" (rider's pick of four mocked-up directions)

- **Font:** Archivo Black (OFL, licence in assets), replacing Anton.
- **Captions:** 1–2 words at a time, as big as fits in two rows (up to 92). White with a soft shadow, and no block or hard shadow behind them. Each word snaps in from 1.08× to 1× in 120 ms with no bounce. Words not yet said wait at 40%. Gemini's key word turns yellow once it's said.
- **Opening text and titles:** flush left. The first line is huge and white, and the rest is a size smaller in yellow.
- **Speed badge:** the number is drawn as an outline, with a small yellow "KM/H".
- **Section change:** "Yellow cut" (saved as SLASH). The camera punches in, then shows one frame of yellow at the cut.
- **Stats:** top speed as one huge number (230, deliberately above the type scale), with the other three in a row below it.
- **Choices I made:**
  - The English line under the captions is capped at body size (36).
  - The "Punch" caption style is renamed "Poster".
- **Mockup frames:** `shots/BoldMocks`.

### Agreed next (to build together, step by step)

- **Speedtrap (new style):** red, white and black, bike-themed.
  - **Redline effect:** a red pulse while the engine is at the redline. Two variants:
    - **Full (default):** a flat red overlay over the whole frame, with no gradient, up to 30% at the peak of a pulse.
    - **Stroke:** a red frame round the edge, 14 px wide on 1080 with 8 px blur on the inside; solid red at the peak.
  - **Pulse rhythm:** speeds up as the revs climb, about 2 per second at the line and 8 at the limiter.
  - **Redline detection:**
    - From the engine sound (the DJI engine track): the engine's pitch, learnt per bike.
    - Plus "Tag redline" in the editor.
  - **Effect timing:** ramps in over 150 ms and drops off at the gear shift.
  - Mockup: `shots/StyleIdeas` (speedtrap/base) plus a scratch script that animates it.
- **Filters:** their own setting. The filter sits on the video, under every graphic.
  - The rider chooses; there's no auto-suggest.
  - Candidates:

    | Group | Filters |
    |---|---|
    | Time | Golden hour, Blue hour, Night ride, Midday punch |
    | Weather | Monsoon, Haze cut, Dust |
    | Terrain | Asphalt, Ghat green, High desert |
    | Signature | Red only, Grit, Teal & orange, Chrome |

- **Colour picker on every style:** curated swatches, with the style's own colour as the default.

### Build order (agreed)

1. **Colour picker on every style.**
   - **Data:** `StudioOptions.accent` (null = the style's own), saved in the Reel JSON as "accent" and in Styles as "accent".
   - **Drawing:** `StudioArt` takes the accent and draws every accent element with it.
   - **Picker:** a "Style colour" row, using the 8 swatches plus "Style's", in the ride settings sheet and the style editor.
   - **Native:** the colour only sets the caption highlight.
2. **Filters:** their own setting. Applied to the video under all graphics, with an intensity slider, chosen by the rider.
3. **Speedtrap redline detection:**
   - Rev level from the engine track's pitch, normalised per bike.
   - Plus manual "Tag redline" ranges in the editor.
4. **Speedtrap style:**
   - The redline effect: Full by default, Stroke as an option.
   - Captions, titles, speed badge and stats are to be decided with the rider before building.

### Progress

1. **Colour picker on every style:** done (1620b9e).
2. **Filters:** done (7ea9350).
3. **Redline detection:** done.
   - **Pitch:** `Revs` finds the engine's pitch every 100 ms by autocorrelation, in the 20–320 Hz range.
   - **Bike's range:** the 10th to 98th percentile of every clip's readings; the redline is at 0.9 of that range.
   - **Decoding:** `EngineAudio` decodes the engine file, or else the clip's own sound, to mono at about 8 kHz. Results are cached in `<clip>.revs.json`.
   - **Tags:** "Tag redline" adds 2 s from the playhead. Tags show as red bars on the timeline (tap one to remove it) and save with the Reel. A tag reads as climbing from the line to the limiter.
   - **Pulse:** fades in over 150 ms and out over 100 ms, at 2–8 pulses a second.
   - **Light:** `StudioArt.redline` draws it two ways:
     - **Full:** a flat overlay, up to 30%.
     - **Stroke:** 7s wide (14 px at 1080) with 4s of soft edge.
   - It's only drawn when `VibeSpec.redline` is set: Speedtrap, in step 4.
   - The revs are only worked out when the style uses the light.
4. **Speedtrap:** done, built from the Redline dashboard mockup (rider's pick).
   - **Font:** Orbitron (OFL, licence in assets). **Accent:** red.
   - **Captions:** a new "Dash" style: capitals on a chamfered dash panel with a red top edge; the word being said is red.
   - **Titles:** flush left, with a red chamfered tag above; each row boots on.
   - **Speed badge:** the number, plus a rev bar (from the engine sound, else the speed). There's no gear indicator, because we don't know the gear.
   - **Section change:** a new "Rev sweep": the frame darkens and the rev counter sweeps to the redline.
   - **Stats:** TRIP A, top speed in a rev counter, and three panels.
   - **Redline light:** Full by default; "Redline light round the edge" in the ride settings switches it to Stroke.
   - **Editor:** "Tag redline" is in its tools.
   - **Grade:** contrast +0.1, saturation −6, slightly cool.

## Next APK (after keppo-moto-f1bfcfb): what's in it, what's still open

**In it:**
- **Style redesign:** 7 styles on design tokens; Clean, Type and Strip captions; Dip, Zoom-through and Light leak; Pop wipe and Paper slide rebuilt; the 40% transition cap; safe zones.
- **Bold as Poster.**
- **Speedtrap:** the redline light (Full or Stroke) and Tag redline.
- **Style colour** on every style.
- **Filters:** 9, with strength.
- **Preview:**
  - the editor preview draws through the same overlay code as the made video
  - caption taps use the real bounds
  - a fresh exact-preview player after each edit (bug 2, option A)

- **Editor preview rebuilt (option B):** one player, no fallback, the next clip preloaded, half-size graphics; the look and the camera drawn on the picture; the sound tracks mixed in the background; reload-and-retry on errors. This covers bugs 1 and 2.

**Still open:**
- **Preview checks on the rider's phone:**
  - the cut smoothness
  - the sound tracks' sync
  - Red only (a shader on Android 13 and later)
  - freeze frames
- **Later (agreed):**
  - blur faces and number plates, after a second camera
  - weekly long video, last, with the rider's permission first
- **Not checked yet:**
  - redline detection on a real ride's engine sound
  - the style colour and filters in a made video

## Editor preview rebuild (option B, rider's pick, 13 Oct)

There's one preview in the editor; it never switches. It replaces both the exact preview (`CompositionPlayer`) and the simple one.

- **Picture:** one ExoPlayer plays the edit as a playlist, one item per segment, into a `TextureView`.
  - A change to the clips only replaces the playlist. A normal player handles that any number of times, so there's no one-edit limit.
  - The next clip is preloaded before the cut (bug 1).
- **Per segment:**
  - the clip's part at its speed (the player's speed per item; a ramp plays at its average)
  - reversed clips play their reversed copy
  - freeze frames play as images and are shown over the video
- **Look:** the style's grade, the clip's colour and the filter go into one colour matrix on the video (the same numbers as the made video, shared in `Looks`).
  - Red only uses a GPU shader on Android 13 and later, and shows as black and white before that.
- **Camera:** the style's camera and transitions, plus the clip's crop keys, rotation and flip, applied to the picture. The made video's fill clamp applies, so there are no black edges.
- **Graphics:** drawn by the same `ReelOverlay` as the made video, at 540×960 (half size, bug 1).
  - Layers show as stills where they'll be.
- **Sound:**
  - The clips' own sound plays from the video player, at each clip's level.
  - The song, voice-over, engine track, detached sounds and layer sound are mixed by the renderer into one audio file in the background, after any change to them.
  - A second player plays that file, kept in step with the picture.
  - Until a new mix is ready, the previous one plays.
- **Errors:** a playback error reloads the player at the same spot (up to 3 tries), then says so on the video ("Preview couldn't update: tap to try again"). It never switches to another preview.

## Bugs from the rider, APKs keppo-moto-6e2d564 and 85c0fee (13 Oct)

1. **Edit page broken.**
   - **What the rider sees (screenshot):** only the video, in the top ~40% of the screen, running up under the status bar. Below it the screen is black.
   - **Missing:** the top bar (close, Ideas, Next), the timeline and the tools.
   - **Captions:** they draw ("tight ho raha"), so the preview plays.
   - **Cause (found):** not the preview. The new Filter sheet (`FilterSheet`) was called outside the editor's full-size box. A sheet's root fills the whole screen, so even while hidden it took a screen of height in the page's column. That pushed the whole editor up: the top bar went off screen, the preview's top sat above the screen, and the timeline and tools fell below it.
   - **Fixed:** the call moved inside the editor's box, next to the other sheets, so it lies on top and takes no space.

### From APK keppo-moto-85c0fee

2. **Timeline rows: move things, more room.**
   - **Asked:** hold and drag layers left/right and up/down; taller rows; the bottom half adjustable.
   - **Fixed:**
     - Rows are 34 dp (were 18–24 dp).
     - Hold any block (layer, caption, text, sticker, sound) and drag: sideways moves it in time. Layers, text and stickers also move up or down a row; a higher row is drawn over the rows below (the newest layer starts on top).
     - A handle between the video and the timeline: drag it up for a taller timeline, down for a bigger video. The timeline scrolls up and down when its rows don't fit.
   - **Code:** `Lanes` (pure: rows, moving to a row), `TrackEdits.stackLayer/stackText/stackSticker`, `TrackBlock` and `Rows` in the editor.
   - **Also fixed:** the slower "drawn" layer method stacked layers upside down compared with the main method; both now put the last layer on top.
3. **Lag with many layers.**
   - **Cause:** the whole preview area was rebuilt about 30 times a second while playing, because it read the playhead directly. Each rebuild also made a new Red only shader.
   - **Fixed:** the preview rebuilds only at cuts. The camera, the graphics and the layers read the playhead where they're drawn. The shader is set only when the filter changes.
4. **Over video showed a still.**
   - **Fixed:** each layer plays its own video in the preview, kept in step with the edit; scrubbing shows the right frame. The top 3 layers on screen play; more show a still, so the phone keeps up.
5. **Filter: tapping did nothing visible.**
   - **Cause:** the sheet covered the video, and the tiles' selected mark was a thin white line.
   - **Fixed:** the filters sit in the tools' place, under the video, so each shows on the video as it's tapped. The one on has a thick frame, a tick and its name lit; its name pops up on the video. A Strength slider shows under them. The Filter tool says which filter is on. A stand-in picture is used for the tiles if the playhead's frame can't be read.
6. **Move captions on the video.**
   - **Fixed:** drag the caption on the video up or down: all captions move together (the same setting as Captions › Height). Tap still opens Fix words.
7. **Captions said at the same time take their own rows; hide things.**
   - **Fixed:** captions that overlap go on rows of their own.
   - Layers, text and stickers have Hide/Show (kept on the timeline, dimmed with a crossed-out eye; left out of the preview and the made video, a hidden layer's sound too). A selected caption has Hide captions/Show captions.

## Transitions, round 2 (13 Oct, with the rider)

- **Instant:** "None" is now called Instant, first in the picker for every style (icon ⚡).
- **Every transition, every style:** the picker shows Instant, the style's own, the style's picks, then all the rest, each with its name under it. Glitch stays hidden (kept only for older edits).
- **Length:** Short/Normal/Long (0.5×, 1× and 1.6× of the transition's own length) are replaced by one slider, Instant → 1.5 s in 0.05 s steps. All the way left is Instant; dragging up from Instant brings back the last transition. Picking a transition sets its own length.
  - The longest is 1.5 s, not 2 s: each transition is shared by the clips either side of the cut, and never takes more than 40% of either. On 1.5–4 s Reel clips, longer is mostly cut short and feels slow.
  - When the clips are too short for the length, the slider says so ("0.8 s · plays 0.6 s (short clips)").
  - The style editor has the same single slider (its own length, or 0.1–1.5 s).
- **New transitions:**
  - **Gear shift:** the camera squats into the cut and kicks out, with a short shake and a light kick at the shift. 0.3 s.
  - **Throttle blip:** two quick zoom revs into the cut, then it lands zoomed and settles. 0.5 s. (Not timed to the engine sound yet.)
  - **Visor drop:** a tinted helmet visor (curved rim, glint, reflection) slides down over the clip and lifts off the next. 0.6 s.
  - **Mirror:** the view closes down into a rear-view mirror (oval, chrome rim, stem) by the cut; the next clip opens out of it. 0.7 s.
  - **Exhaust:** smoke puffs roll up from the bottom corner, thick at the cut, and clear off the next clip. 0.8 s.
  - **Split, Blur, Luma fade:** these change the picture itself (a new shader, `PictureCuts`, after the camera). Split parts the frame down the middle into the dark; Blur blurs out and in; Luma fade takes the dark parts first and brings the next clip's bright parts first.
  - Each clip still does its own half (out to the cut, then the next one in), so Split, Blur and Luma fade go through the dark at the cut rather than mixing the two clips. Mixing two clips at once would need the export rebuilt to overlap them: a later step if wanted.
- **Preview:** Split, Blur and Luma fade show in the editor on Android 13+ (the same shader). Android 12 shows Blur, and older phones a fade to black instead.
- **Frame checker:** draws all of these, with the shader done pixel by pixel. Unit tests: `PictureCutsTest`.

## Production readiness: audit and Play Store launch (planned 13 Oct; phase 1 done, see `release-audit.md`)

The rider is happy with the app. Next: a full UI, UX and code audit, then a global launch on Google Play. Plan agreed; the inventory starts when the rider says go.

### Decisions
- **App ID:** `com.keppo.moto`, already set (`applicationId`; the code's namespace `com.ridetrack.app` is internal and stays). Permanent once uploaded.
- **SMS from day 1:**
  - **Crash texts (`SEND_SMS`):** declared under Play's "Physical safety/emergency alerts" exception, in the Permissions Declaration Form with a video. Safety is made prominent in onboarding and the listing so it reads as a core feature.
  - **Fuel log (`READ_SMS`):** declared under "SMS-based money management". Only payment SMS are read, on the phone, never uploaded; the audit checks this.
  - `CALL_PHONE` isn't restricted.
  - **No SMS fallback built.** If Google rejects either one, the rider says so; then a server SMS (DLT registration in India) or the SMS User Consent API.
- **Crash alert screen, in order:**
  1. Full-screen alert where allowed.
  2. Otherwise, with "Display over other apps" on, the alert screen opens directly as a modal over any app and over the lock screen.
  3. Otherwise a loud, high-priority notification with the countdown.
  - The countdown and texts run the same in every case.
- **Paid version:**
  - A subscription through Google Play Billing, with a 1-week free trial.
  - **Locked:** Studio (Reels).
  - **Free:** everything else (rides, moments, pop-up, safety, fuel, journal, ride cards).
  - **Restore:** the purchase belongs to the Google account; checked on every launch, plus a Restore purchase button in Settings.
  - **Built (7a, 14 Oct):** `pro/Billing` (Play Billing 8.3.0) asks Play what the rider owns on launch and on every return to the app, prices the plans, opens Play's payment sheet and acknowledges new purchases. `Entitlements` remembers the answer for offline use. Profile has a Keppo Moto Pro card: status, Unlock, Manage subscription and Restore purchase.
  - **Built (7b, 14 Oct):** the paywall (`ui/pro/PaywallScreen`, route `pro`), opened from Profile's Unlock.
    - Contents: four benefits; Yearly (chosen by default, "Save N%" worked out from Play's prices) and Monthly.
    - Buttons and terms: "Start your free week" (or Subscribe without a trial); plain terms under it (price, auto-renewal, cancel in Google Play); Restore purchase; close.
    - Without Play's prices it says to install from the Play Store. Screenshot test: `PaywallShots`.
  - **Built (7c, 14 Oct):**
    - **Counting:** `pro/FreeReels` keeps the free Reel on the phone (the server will count it too); it's used when a Reel is made (a new save, or a background make), and is back 7 days later.
    - **The lock:** a locked free rider sees Studio blurred under a card (when the next one comes, Unlock Studio · 1 week free, Restore purchase, Your Reels). With the free Reel still there, a "1 free Reel this week · Go Pro" chip.
    - **New Reels:** opening a new Reel's Studio is locked; Reels already made still open. The lock is decided on the way in, so the Reel being made isn't taken away. Make, make one and make all check too.
    - **Test builds:** "This week's free Reel is made" in Profile, to try the lock.
  - **Play Console set-up:** subscription `keppo_pro`; base plans `monthly` and `yearly` (auto-renewing); on each, an offer `free-week` (7-day free trial, new customers only).
  - **Access codes (beta testers, influencers):** codes made on the server that give full access for a set time and a set number of uses, redeemed in Settings → Redeem code. No payment, so allowed by Play; their Gemini use still counts against a cap. Until the paywall ships, everything is free anyway.
  - **Discount codes (campaigns):** Play subscription offers with developer-chosen eligibility (for example 3 months at 90% off), shown only after the server accepts a code. Play's minimum price per country rules out 99% off; use an access code for "almost free". Play's own promo codes (extra free months) are a simpler option, limited per quarter.
  - **Prices by country (tiers; up to 4× India allowed by the rider):**
    1. US, Canada, UK, Western and Northern Europe, Switzerland, Australia, NZ, Japan, Korea, Singapore, Hong Kong, Israel, the Gulf: **$9.99 a month / $64.99 a year** (about 3.2× India; for now, to discuss later; 4× would be $12.99 / $84.99).
    2. Southern and Eastern EU, Taiwan, Malaysia, Chile: **$6.99 / $44.99**.
    3. Brazil, Mexico, Türkiye, South Africa, Thailand, Indonesia, Vietnam, Philippines, Colombia: **$4.99 / $29.99**.
    4. India **₹299 / ₹1,999**; Pakistan, Bangladesh, Sri Lanka, Nepal, Egypt, Nigeria, Kenya **$3.49 / $19.99**.
    - Claude prepares the full country list for the Play Console.
  - **What the rider receives (about ₹95 per $, before income tax):** India ₹215 a month (monthly) or ₹120 a month (yearly). Tier 1 US monthly about ₹807, UK about ₹672.
    - Google takes 15% of the price before tax; VAT and GST are inside the price, except US sales tax, which is added on top.
  - **Launch price:** sold as a launch price, raised later for new subscribers only; existing subscribers keep theirs (Play's legacy price).
  - **No fake struck-out prices:** these are dark patterns under India's 2023 rules and EU price rules.
  - **Free riders:** one free Reel every week, to keep them coming back.
    - **When it counts:** when a Reel is made or shared; browsing ideas and editing don't count, and an unfinished draft can still be finished.
    - **When it comes back:** 7 days after it's used, with a countdown ("Next free Reel in 3 days").
    - **Counting:** on the server, against Android's per-app device ID, which survives reinstalls (declared in the data safety form).
    - **After it's used:** Studio shows the latest ride's Reel ideas blurred, built from the ride's own frames and numbers with no Gemini cost, under a lock. On top: the countdown, Unlock Studio (price and "1 week free") and Restore purchase.
    - **Paywall honesty:** the price, that the trial becomes paid after 7 days unless cancelled, how to cancel, easy to close, no fake urgency.
    - **No automatic Reels for free riders:** no Reel ideas planned after a ride and no Reel made in the background (no Gemini cost). They make their weekly Reel themselves in Studio; paying riders keep the automatic Reels.
    - **No watermark** on free Reels.
  - **No Gemini at all for free riders,** apart from the one free Reel a week:
    - Moments captions are manual (tap to caption) for free riders.
    - The petrol price lookup becomes paid; free riders type the price.
- **Gemini cost cap:**
  - **35% of what each rider actually pays** (after Google's cut and tax), by their country; the server reads the subscription's country from Google.
    - For example, India about ₹75 a month (monthly) or ₹42 (yearly); Tier 1 monthly about ₹280.
    - A daily share of it stops one day using the whole month.
  - A smaller cap for free features that use Gemini.
  - Measured from Gemini's own token counts. At the cap: "You've made a lot today, more tomorrow".
  - Enforced by a small server function with an invisible anonymous sign-in, which also verifies purchases and hears renewals and cancellations.
  - The rider will revisit the numbers.
- **Crash reporting:** Firebase Crashlytics. EU and UK riders are asked first.
- **Face and plate blur:** after launch.
- **Map tiles (done, 14 Oct):** switched from CARTO (needs a commercial licence) to OpenFreeMap (free, commercial use allowed, OpenStreetMap data), with our own dark style to match today's look.
  - Same features; satellite only ever worked with a MapTiler key, which isn't set.
  - If OpenFreeMap becomes unreliable, host the same data ourselves.
- **Music:** riders add their own; nothing is bundled (checked). The Terms of Use make riders responsible for music rights.
- **Developer account:** personal. The rider finds 12+ testers for the 14-day closed test.
- **Release:** global, in English. Text kept ready for translation later.

### Workstreams
1. **Inventory** (no fixes):
   - every screen and flow
   - every permission and why
   - where each piece of data goes, including which features use Gemini
   - starting numbers: app size, start-up time, battery per riding hour, crashes
2. **Play and legal readiness:**
   - **Platform rules:** target API (check the current rule; likely Android 16), 16 KB page size for MapLibre's native code, an app bundle signed with the rider's own release key (Play App Signing), and versioning.
   - **Play Console forms:** foreground service declarations with videos (location, camera, microphone, data sync, media processing), the SMS and full-screen-alert declarations, data safety, content rating, target audience 18+, reviewer instructions for hardware features, and EU trader status (public address: consider a business address).
   - **Legal:** the privacy policy (GDPR and UK, CCPA, LGPD, India DPDP), Terms of Use, data deletion in the app, the Gemini disclosure and consent, and reporting of bad AI output.
   - **Licences:** fonts, map tiles and their credit line, and an open-source licences screen.
   - **Store listing:** icon, feature graphic, screenshots and copy.
3. **Code audit:**
   - **Correctness and crash risks:** low storage and battery, calls mid-ride, permissions revoked mid-ride, Android killing the app.
   - **Resource handling:** releasing players, video decoders, camera and bitmaps; cleaning up caches.
   - **Background work and data:** Android 14 and 15 background rules and time limits; database upgrades tested on real old data.
   - **Release build:** test it specifically (R8 shrinking with Media3, MapLibre and Firebase).
   - **Security:**
     - App Check with Play Integrity
     - no secrets in the app
     - only the needed components open to other apps
     - personal data kept out of logs and backups
   - **Housekeeping:** dead code, lint at 0 warnings, missing tests (ride recording, crash alert, saving).
4. **UX audit:**
   - **First run:** onboarding and asking for permissions, including saying no.
   - **States:** empty, loading, error and offline.
   - **Riding safety:** glanceable with gloves on, no taps needed while moving.
   - **Copy and deleting:** undo or confirm on every delete, and consistent copy.
   - **Accessibility:** TalkBack, 48 dp targets, contrast, text at 200%, reduce motion.
   - **Screens:** small phones, tablets and foldables, landscape, and edge-to-edge.
5. **UI audit:** design-system consistency; every screen captured as images for the rider to review (screenshot tests, if the tool can be downloaded here).
6. **New builds:**
   - **App ID** change.
   - **Crash alert** fallbacks.
   - **Subscription:** paywall, trial and restore.
   - **Server function:** cost cap and purchase checks.
   - **Crashlytics** with consent.
   - **Legal screens:** data deletion, the Gemini consent, AI reporting and licences.
7. **Release:**
   - findings report (blocker, major, minor), reviewed by the rider before fixing
   - fixes in batches
   - a signed bundle to a closed test (Play's pre-launch report runs it on real devices)
   - 14 days with 12+ testers
   - production

### Order (changed 14 Oct)
1. Done: maps, App ID, Android 16, release build, crash alert fallbacks, free riders without Gemini, subscription app side (7a–7c).
2. Step 9: Crashlytics with consent; Gemini consent and AI reporting; data deletion and licences.
3. The audits (code, UX, UI), then the store pack.
4. **Last, just before the closed test:** the rider switches Firebase to Blaze, whose $300 Google Cloud trial credit lasts 90 days. Then step 8, the server function (purchase checks, free-Reel and cost-cap counts, access codes). The credit then covers the closed test and roughly the first 2.5 months after launch.

### What the rider does
Create and keep the release key (walked through), click through the Play Console, record the declaration videos, find testers, test on the phone. Claude prepares every form's answers, the policy texts and the listing copy.


## Two mics visible everywhere (14 Oct, rider request)
The rider recorded with two mics (DJI Mic Mini, mono) and saw no sign of it anywhere.
- **HUD:** while a two-channel receiver records, a bar per mic ("VOICE", "ENGINE").
  - Grey "1 MIC" when both sides sound the same.
- **Settings:** a new Moments › **Mics** page holds the mic choice, the Bluetooth headset switch, Two mics, and the meters.
  - Off the bike, the meters run as the mic test (with swap).
  - During a ride, they show the ride's live levels.
- **Editor:**
  - **Voice row:** each clip's own sound now has its own row ("Voice" with two mics, "Clip sound" otherwise).
  - **Engine row:** the engine sound is added automatically when the clips have it.
  - **Removing it:** if the rider deletes the engine sound, it stays out; Add sound › Engine brings it back.
- **Log:** the moments log now says once whether the two channels differ ("engine sound kept with each clip") or are the same (nothing kept). That tells us why a ride has no engine sound.
- **Still true:** the engine sound is saved only from a USB-C receiver, and only when the two sides really differ.

## Global launch decisions (14 Oct, from the UX audit)
1. **Units:** km or miles. The default comes from the phone's country (US, UK, Liberia and Myanmar get miles); it can be changed in Profile. It applies everywhere: HUD, ride pages, share cards and Reel overlays.
2. **Fuel worldwide:**
   - Money is shown in the currency of the phone's country.
   - India works as today.
   - Elsewhere, card SMS reading and the petrol price lookup are **Beta**, off by default.
   - Their failures are recorded for review (error log and Crashlytics) without any SMS text: only the kind of failure, the country and the currency.
3. **Captions in any language:** Gemini writes what the rider says in their own language. Hindi mixed with English stays Hinglish in English letters.

### Built (14 Oct)
- **Captions:**
  - One language rule (`SpeechLanguage.RULE`) for transcripts and every caption prompt: the rider's own language, never translated, Hindi in English letters.
  - English subtitles are now added for any non-English line.
- **Fuel:**
  - `FuelRegion` gives the currency from the phone's country; amounts show as "$12.40", "€61.20", "₹1,250" (no cents for rupiah, dong and won).
  - Outside India, card SMS reading (and the price lookup that follows it) is Beta and off by default. It understands many currencies and number styles ("1.234,56").
  - Failures go to the error log and Crashlytics as `FuelBetaIssue`: only the kind of failure, the country, the currency, and each message's shape ("spend+currency+number"), never its words.
  - The fill-up dialog accepts a comma as the decimal point.
- **Units:**
  - `Units` (Compose state) is read by `Format` and every screen, share card, Reel overlay, sticker and Gemini prompt.
  - It's set from the phone's country (US, GB, LR, MM: miles) or by the rider in Profile › Appearance.
  - Stored values stay metric. Odometer and reminder fields are typed in the rider's unit and saved in km.
  - Fuel: US in gallons and US mpg; UK in litres with imperial mpg.
  - The ride export CSV stays metric (its columns say so).
- **Fallback Instagram caption:** "#ridersofindia" became "#motorcycle".

## Questions and bugs log (from 14 Oct)
Everything the rider reports in chat goes here: what was said, the status, and the build that has the fix. Log only; nothing is built until the rider says so.
Statuses: Open · Fixing · To test (fixed, waiting for the rider) · Done · Later.

| # | Type | What | Status | Build / notes |
|---|---|---|---|---|
| 1 | Bug | Recorded with 2 mics (mono): nothing showed in the HUD, settings or editor; the clip's own sound had no row of its own | To test | 6a19ba8: HUD meters, Moments › Mics page, Voice and Engine rows in the editor |
| 2 | Question | Why no separate engine sound on that ride? | To test | Saved only from a USB-C receiver when both sides differ. The moments log now says which case it was; check after the next two-mic ride |
| 3 | Question | Which APK has all the recent changes? | Done | 6a19ba8 |
| 4 | Idea | Achievements page: earned ones and ones still to reach (seen on Home today) | To test | Built in 68b05b7. Proposed: in Profile, earned (date, ride) and still to reach (progress bar); distance, consistency, exploring, long days, bike care, Reels, setup. No speed or lean goals (safety, Play). Build when the rider says |
| 5 | Bug | Bike stopped, rider spoke: the HUD's cam badge stayed grey, so there's no way to tell if it's recording | To test | Built in 68b05b7. Speech while stopped should wake the camera and show red "cam 00:00" at once. Grey means either speech wasn't detected or the camera didn't start in time (it falls back to grey). The moments log says which ("voice start", "video requested", "video not started"). Proposed: show "cam · starting" while it wakes, and a note when it fails |
| 6 | Bug | At speed the voice meter barely moves (about 20% while screaming) and speech isn't detected; fine when riding slowly | To test | Built in 68b05b7. Cause: the meter shows dB above the background noise (the quietest 10% of the last 4 s). At speed, wind noise fills the voice range, so the background rises close to the voice and even screaming is only a few dB above it; Silero also rates voice in heavy wind below its 0.5 bar. Both checks fail. Proposed: lower margin and voice bar as the background rises; with two mics, compare the helmet mic to the engine mic; recommend the DJI windscreen and the mic inside the helmet near the mouth. Confirm from the log's "mic:" lines (level, background, voice, speed) |
| 7 | Bug | Export ride (with the moments log) is only in debug builds; tester APKs can't send logs | To test | Built in 68b05b7. Show it in tester builds too (TEST_BUILD) |
| 8 | Change | Speech must last 1 s, not 1.5 s, before recording starts (with #6) | To test | Built in 68b05b7. SpeechGate startMillis 1,500 → 1,000 ms; update the wording "held about 1.5 s" in Moments settings and the HUD explainer |
| 9 | Question | Are clips under 5 s apart joined into one? | Done | Yes, since b2a2aa8. During a ride a running clip is extended; once the ride is saved, clips that overlap or are under 5 s apart are joined (same camera only). Rides from the last 2 days are joined at app start, unless a Reel was already made from them |
| 10 | Idea | Horns and bumps spoil the engine sound in Reels (engine mic) | To test | Built in 68b05b7. Rider's priority: horns and bumps in Reels (not false engine moments). Bumps: a short bang on the engine mic at the same moment as a jolt on the phone's motion sensors (the ride's samples have it); turned down for just that instant (about 0.1–0.3 s, with smooth fades) so the engine runs on underneath; marked on the Engine row with Soften bump (default) / Keep. Horns: Proposed: find horns on the engine track (a loud tone that holds a fixed pitch and doesn't follow the engine's pitch; with two mics, about as loud on the helmet mic too). Then (1) turn the horn's own pitches down with narrow filters that follow it, leaving the engine's pitches; usually 10–20 dB quieter, not gone; (2) mark horns on the editor's Engine row with Reduce horn (default on) / Mute here / Keep; (3) Studio prefers horn-free stretches when choosing engine sound, as a tie-break only: no clip or Reel is dropped because of a horn or bump |
| 11 | Bug | Studio home: tapping "Ask for something" opens another page; it should be a text field you type into right there | To test | Built in 68b05b7. StudioHomeScreen.kt:480 is a tappable label that navigates; make it a real input |
| 12 | Idea | Make "Ask for something" an AI chat that works across all rides as well as one ride, e.g. "a video of all my downshifting clips" makes it from every ride, or asks "since when?" | Next | Possible: a chat with Gemini that uses the app's own tools (find clips by what was said, seen, or what happened: braking, lean, revs, speed; across rides and dates), asks when unsure (which rides, how long, which style), then makes the Reel. Needs a new "downshift" tag (an engine rev blip while slowing down), not tagged today. Pro only (Gemini) |
| 13 | Idea | Tag downshifts from engine sound and braking (OBD later) | To test | Built in 68b05b7: engine pitch jumps up 15–120% while the speed is falling; tagged after each ride and for the last 30 days; Studio ideas and search use it |
| 14 | Bug | Filled petrol today (a 2–3 min stop): no "Filled up here?" prompt | To test | Code review found three ways a real fuel stop is dropped: (1) the saved speed is the raw GPS speed, so one reading at or over 1 m/s splits the stop: inching forward in the pump queue, or GPS jitter under the canopy; each piece under 1 min is thrown away; (2) under a metal canopy GPS can lose its fix entirely, so the stop has no position and is dropped, though the position just before it is known; (3) the pump on OpenStreetMap must be within 70 m of where the bike stood, too tight for big stations. Ride saving order, Home display and the 1–15 min window are fine. Fix proposed: treat a stop as slow on average (allowing creeping and jitter, up to about 15 m of movement), use the last known position when there's no fix, widen the pump search to 150 m, and log why a stop wasn't counted. The ride export would confirm which one it was. **Built:** a stop is slow on average (queue creep up to 10 s / 30 m and GPS wobble allowed), the last position is used under the canopy, pumps match within 150 m, and the ride log says why each stop was or wasn't counted |
| 15 | Idea | More fuel visibility: total filled, when each fill was registered and how (automatic or entered), and mileage | To test | Proposed: a Fuel page per bike: mileage (average, last tank, trend), totals (litres and money, this month and all time), cost per km; every fill with date and time, litres, amount, price, station, and how it got there ("Automatic · card SMS", "Confirmed from a stop", "Added by you"); edit or delete each; fuel stops that weren't counted, with Add. **Built:** Bike tab › Fuel › See all fuel |
| 16 | Idea | Make fill-up detection foolproof | To test | Layers, so no single miss loses a fill: (1) better stop detection (#14 fixes); (2) more signals, any one is enough: card SMS with a fuel merchant (even without a detected stop), slowing near a mapped station, and stations the rider has used before (remembered, works where OSM is wrong); (3) ask during the ride, not after: when you ride off from a station, a notification "Filled up at <station>?" with Yes / No; (4) offline: station locations kept on the phone for the rider's area and routes; (5) catch what slips through: "Missed stops" on the Fuel page, and after about a tank's distance with no fill, "Did you fill up?" on Home; (6) a one-tap "Filled up" on the HUD pop-up and the ride notification. **Built:** all six: pumps you used are remembered; card SMS alone is enough; "Filled up at …?" as you ride away (offline, from pumps kept on the phone); unsure stops under Not sure on the Fuel page; "Did you fill up?" on Home after about a tank's distance (Not yet waits 30% of a tank more); FILLED UP in the pop-up controls (long-press) and on the ride notification |
| 17 | Idea | Mark the petrol pump on the ride map afterwards, so the app remembers it | To test | Ride › ⋮ › Mark petrol pump: tap the place on the map, name it (the map's name is filled in when known), and tick "I filled up here on this ride". The pump is kept on the phone as yours: stopping there later asks if you filled up, even offline. With the tick, this ride's fill-up waits on Home and the Fuel page for the amount (an unsure stop there is replaced). Fuel page › Your petrol pumps lists them, with Forget |
| 18 | Idea | A log of fuel detection: pump found but no SMS, etc. | To test | Fuel page › Fuel log, newest first: each check after a ride and on the way (stops found or none, pump matched or not, card SMS found / none between which times / found but not fuel, SMS access missing, price found or not, saved automatically, your Yes / No / Filled up / marked pump). Lines that explain a miss are amber. The same lines go in the ride's own log (exported with the ride) |
| 19 | Bug | Two mics: still only one sound layer in the editor; ride clips should show two layers too | To test | Recorded with the DJI receiver on Mono, which mixes both transmitters into one sound on both channels, so the app sees the same sound and keeps no engine track (it can't be split afterwards). Plan: keep mic 2 always when recording two channels, tell the rider when the receiver is on Mono (set it to Stereo), name layers Mic 1 · Voice / Mic 2 · Engine, show both in the editor, viewer and moment share. See `audio-layers-plan.md` (A, B4, D). **Built (0197284):** mic 2 always kept with two channels; Mono hint; Mic 1 · Voice / Mic 2 · Engine rows; viewer Voice · Engine · Both; share mix. Clips recorded in Mono stay one mix. |
| 20 | Bug | Empty gap between the video row and the other rows in the editor timeline; bars have no names | To test | Empty caption and text rows still take a row each (the gap). Bar names sit at the bar's left edge and scroll off screen. Plan: no empty rows; a fixed column of row names on the left, and names that stick to the screen edge. See `audio-layers-plan.md` (B1, B2). **Built:** no empty rows; row names column; labels slide to stay on screen. |
| 21 | Idea | Control how loud each sound is over time: points (markers), ease in / ease out, everywhere a video is shared | Partly built | A volume line on every sound (tap to add points, drag them; ease in, out, in-out, hold between points), fade handles with shapes, presets (dip at markers, lower under my voice, swell at downshifts). One shared sound editor in Studio, the Sound sheet, moment share (with the exporter mixing both mics) and the 3D ride video. See `audio-layers-plan.md` (C, D, E). **Built:** volume line (tap/drag/hold, ease per point) and fade sliders with shapes for engine, sounds and music; share screen voice/engine levels and fades. **Not yet:** the volume line on the clip's own sound (Mic 1), and the 3D ride video's per-clip mix. |
| 22 | Bug | HUD two-mic meter reads VOICE / 1 MIC most of the time, VOICE / ENGINE only briefly; it should always say Voice and Engine | To test | The second bar is labelled "1 MIC" whenever the app decides both channels sound the same (`TwoMics`: difference energy under 15% of the total, over about the last 2 s). With the DJI receiver on Mono, both channels carry the same mix, so it's "same" nearly all the time; a moment of wind or clipping on one side tips it over 15% for a second, which is the brief ENGINE. There's no hysteresis, so it flips back and forth. Fix proposed: labels always VOICE and ENGINE; decide "same" only after it holds for about 10 s, and once two different mics are heard keep them for the ride; when the receiver mixes them, an amber line under the bars: "Receiver on Mono · set it to Stereo for a separate engine sound" (ties in with #19, plan A2). **Rider's decision:** the mics are fixed (one in the helmet = voice, one by the back seat = engine), so once set they stay set. With Two mics on, the app never decides "1 MIC" by itself: the labels are always VOICE and ENGINE, the left/right roles come only from Settings (mic test / Swap), and the engine track is always kept (plan A1). Sounding the same only adds the amber Mono hint, never a relabel. **Built:** always VOICE / ENGINE; two stays two; Mono decided after 10 s, amber hint. |
| 23 | Bug | Notes under the HUD ("Fill-up noted…", "Clip saved", mic notes) are one long line wider than the HUD; they should wrap to the HUD's width | To test | `SavedNote` is a one-line pill (`maxLines = 1`) with no width limit, so the pop-up window grows to fit it. Fix proposed: same width as the card (196 dp, 168 dp in Minimal; the bubble's width when collapsed), wrap up to 3 lines, centred text, rounded rectangle instead of a pill; contact sheet with long notes at 100% and largest text. **Built:** notes as wide as the card, up to 3 lines. |
| 24 | Idea | Moment preview: the info overlay (kind, time, speed/lean/accel/elevation, what was said) should collapse | To test | Today it covers most of the clip, and a long transcript makes it bigger. Proposed: a handle on the overlay; tap or swipe down to collapse it to one line ("Talking · 9:14 pm · 12 km/h"), swipe up or tap to expand; the transcript shows 3 lines with "More" when expanded; the choice is remembered for the next moments; tapping the video hides all of it for a clean look. Seen on the same screen: the Delete button's label wraps one letter per line (no room in the button row); the buttons should scroll or Delete become an icon button. **Built:** handle collapses to one line (remembered), transcript 3 lines + More, Delete as an icon. Tapping the video still plays/pauses. |
| 25 | Bug | Voice still not recognised at high speed after the #6 fix | Partly built | Likely causes: wind fills the mic (a shout is only 1–2 dB above it, sometimes clipping); the voice detector's score collapses in wind and vetoes loud chunks; the two-mic comparison (best in wind) was off because the receiver was on Mono; long shouting raises the 4 s background. Plan in `voice-at-speed-plan.md`: V0 a Stereo ride export shouting at 40/70/90 km/h; V1 the helmet-vs-engine lift counts on its own; V2 a wind-proof voice check (pitch strength + syllable rhythm); V3 wind learned per speed and subtracted, background not learned while talking; V4 clipping warning and mic placement guide; V5 a voice test at speed that sets thresholds; V6 intercom/media button starts a clip. **Built:** V1 (lift alone counts), background over 8 s and not learned while talking, V4 overload warning + tips. **Waiting for the Stereo ride export (V0):** V2 wind-proof voice check, V3 wind per speed, V5 voice test; V6 media button not built. |
| 26 | Bug | When stopped, a voice trigger has no 10 s look-back: the camera rests at stops | To test | When the ride auto-pauses at a stop, the camera keeps its buffer for 20 s and then switches off (`MomentRecorder.setRidePaused`, reason RIDE_PAUSED) to save battery, heat and storage; only the mic keeps listening. Speech then wakes the camera, and the video starts from that moment with nothing before it (`RideSessionManager`: look-back 0 while paused). Fix proposed: keep the camera buffering through ordinary stops (traffic lights, pumps, chatting on the bike), so speech gets the usual 10 s look-back; rest only when you get off the bike (break detected), pause by hand, stop longer than about 10 min, or the phone is hot or low on battery or storage (the existing guards). The look-back then comes from the buffer whenever it has footage, paused or not. **Rider confirmed:** 10 min is enough. **Built.** |
| 27 | Idea | A new recording right after a clip continues that clip live, as one video | To test | Example: a clip ends at 0:23; you start talking 7 s later; the same video carries on from 0:23 (the 7 s between come from the camera's buffer) instead of a second clip. Today the second clip starts where the first ended (no overlap) but stays a separate file; clips are only joined after the ride, and only when under 5 s apart (`MomentMerge.GAP_MILLIS`), so a 7 s gap stays two clips. Fix proposed: when a trigger comes within the look-back (10 s) of the last clip's end and the buffer still holds the gap, the new recording is appended to that clip as soon as it's saved (one moment, its kinds combined, e.g. Talking + Hard braking), with no cut; the HUD timer carries on from the first clip's length (0:23 → 0:30 …). Works the same after a stop once #26 keeps the camera buffering. The after-ride join (under 5 s) stays as a safety net. **Rider:** it must work for any two clips, including two talking clips (talk, 5 s quiet ends the clip, talk again within 10 s of that end: the same video carries on). Test: talk / pause 7 s / talk gives one clip; talk / pause 20 s / talk gives two. **Built:** joined right after saving; HUD timer counts on. |
| 28 | Idea | Editor: a selected clip shows the rest of its recording on both sides, to drag back in or trim | To test | Selected clip: the unused parts of the recording before and after it show beside it, faded and striped, with their length ("← 11.0 s"); the neighbouring clips slide apart to make room ("Make room", chosen) and back when deselected. Drag a white edge out to bring footage back, in to cut; a bubble shows the new start/end and the change; the edge turns red at the recording's start or end. The preview plays through the unused parts too (play or swipe), with a small "Not in the video" tag. Use all / Reset in the clip's tools. Captions follow their words. Next: the same for every timeline item (#29). **Built:** rest of the recording beside a selected clip (make room), red edge at the limits, Use all. **Not yet:** the preview playing the unused parts (the playhead holds at the clip's edge there). |
| 29 | Idea | Editor: + Add menu, the same trim/move controls for everything on the timeline, and tool bars of at most 5 icons that open their own sets | To test | See `editor-toolbar-plan.md`. Row names on the left; every block (clip, filter, effect, text, sticker, caption, video on top, sound, mics) is selected, trimmed by its edges and moved by hold-and-drag; things with a recording show the rest of it (#28). Tool bar: max 5 icons, no scrolling, ‹ fixed on the left, "Add › Sticker" line; values open a slider, choices a scrolling strip; Add first, More last; ✂ split by the time under the preview. Revised tool bars per selection; new Speed ramp, Move (drag on the preview), per-clip and all-cuts transitions; Format moves to Share, clip sound lives on the Mic rows, Look renamed Colour. **Built:** 5-icon tree, ‹ fixed, "you are here" line, sliders for values, ✂ by the time; existing pickers (filters, styles, stickers, transitions) open from it. |
| 30 | Idea | Editor UX: snapping, trim by words, ride moments on the timeline, tap the video to select | To test | See `editor-toolbar-plan.md` › UX. Snapping: edges and blocks pull onto the playhead, cuts, other blocks, pauses in what was said and ride moments when you slow down near them (6 px before, let go 2 px after; fast drags pass through), yellow guide line, light haptic tick, "at …" in the bubble. Trim by words: words on the Voice row (faded in unused parts); letting go mid-word glides to the nearer side; More › Remove pauses. Ride moments: speed line row with downshift / hard braking / top speed / lean markers, tap to jump; faded in unused parts. Tap the video: selects the text, sticker or video on top under the finger (block flashes), else the clip; text and stickers drag on the preview. Redraw at most once per frame while dragging. **Built:** snapping with guide and haptic; what was said on the Voice row (by line, as there are no word timings yet); ride row with jump; Remove pauses. **Not yet:** the mid-word glide (needs word timings). |
| 31 | Bug | Share clip screen: the preview can't be played, and the screen is cluttered | Done | Seen: the preview is a small still card ("Numbers move with the video") you can't play, so neither the overlay nor the sound mix can be checked before making the video; trim, sound, layouts and details are stacked in one long scroll with oversized sliders and chips; "Original" Share/Save sits oddly at the bottom. Proposed: a large playable preview (play/pause, scrub, the chosen layout drawn live on the video, the Voice/Engine mix and fades heard as set); the trim strip right under it with the playhead; below, three tabs, one at a time: Look (layouts), Details (what to show), Sound (Mic 1 · Voice, Mic 2 · Engine, fades) with compact sliders; one bottom bar: Share (makes the video, then the share sheet) and Save; the plain original under ⋮. Prototype: https://claude.ai/artifact/Rede11zxUgVCiXw6vEPHMQ (approved). **Built:** the clip plays in the preview with the chosen graphic drawn live on each frame and the Voice/Engine mix and fades heard as set (levels above 100% play at 100% in the preview; the shared video gets the full level); the trim strip has a white playhead (tap or drag the frames to move it); Look / Details / Sound tabs in a fixed-height panel; Share and Save each create the video first (with Cancel), then share or save; Share/Save original under ⋮. Photos get the same screen with Look and Details |
| 32 | Bug | Same ride, different landing: Studio tab opens the editor; Ride › Studio (and Home's Make a Reel, search, notifications) opens an older ride Studio page (ideas list, Read the script, a script page with Vibe and Make this) | Part done | Two generations of Studio live side by side: the Studio tab's swipe feed (S1) opens an idea straight in the editor (`Routes.idea`), while every other entry still goes to the ride's Studio page from before (`Routes.reel` → ideas list and script view). Proposed: one way in. Every entry opens the Studio feed on that ride (its chip selected), and tapping an idea opens the editor; the script view becomes a tool in the editor (Script: the lines written on the video, the shots, Vibe), and Make all moves to the feed's ⋯. The old ride Studio page is removed. **Done (part 1):** Ride › Studio, Home's Make a Reel, the ride summary, search and notifications now all open the Studio feed with that ride picked (a back arrow at top left; a ride with fewer than 2 clips says so). **Still to do (part 2):** Find ideas, Ask for something and "your own way" still run on the old page; they move into the feed, the script view becomes the editor's Script tool, then the old page goes |
| 33 | Bug | Voice and Engine are the wrong way round, and a video keeps recording from engine noise, not the rider's voice | Fixed (needs a ride to confirm) | Cause: which receiver side is Voice comes only from Settings (Transmitter 1 = Voice unless Swap is on, #22), and the helmet transmitter is on the other side. Two things followed. The HUD bars were labelled backwards. Worse, V1 made "the Voice mic stands out from the Engine mic by 6 dB" count as speaking without the voice detector; backwards, every throttle opening makes the back-seat mic stand out, so revs read as talking and the clip ran on. Fix: (1) a second voice detector on the Engine side (`MicRoleCheck`): when a voice is heard on Mic 2 and not Mic 1 (4 s of it, 3 to 1), the pop-up says the mics look swapped, the helmet-vs-engine rule is turned off for that ride, and Settings › Moments › Two mics offers Swap now after the ride (roles are still never swapped silently). (2) A safety net (`LiftGuard`): 30 s of "speaking" with no voice-like sound in it ends the speech, says "That was engine noise", and turns that rule off for 5 min. Both logged. Clips already recorded keep their tracks as they were; in them the "Voice" and "Engine" tracks are swapped. Right now: Settings › Moments › Two mics › Swap voice and engine |
| 34 | Bug | Some clips from the same ride have no Engine sound | Fixed (needs a ride to confirm) | Cause: clips are made two ways. (a) An event clip (downshift, lean…) is cut from the camera's last 45 s in memory, and its engine sound is written beside it (`writeClip` → `writeEngine`). (b) A video that keeps filming while it happens (started by your voice, GPS lost, your own video, a chain of events) is streamed straight to a file (`startLive`/`stopLive`); that stream carries the picture and the Voice mic only, and the engine mic's sound is never written, so every voice-started clip has no Engine. (c) Joining a clip to the one before (#27) keeps the engine sound only when every piece has one; otherwise it deletes it, so a continued clip loses even the part that had it. Plan: E1 live videos stream the engine mic too, into `<clip>.engine.m4a` (seeded with the look-back from memory, then each new chunk, in step with the video), for every live source. E2 joining keeps the engine sound, with silence where a piece has none, so it still lines up. E3 the log says per clip "engine sound kept" or why not (one mic, phone mic, receiver unplugged). E4 the editor says "No engine sound for this clip" on its Engine row instead of leaving it empty. Tests: a live clip with look-back has an engine file the same length as its video; joining with and without engine pieces. Clips already recorded can't get it back: the engine sound wasn't saved. Rule (rider): the engine mic records everything the voice mic records. **Built:** E1 (`LiveRecording` writes `<clip>.engine.m4a` on the same `LiveTimeline`, seeded from the look-back via `RollingBuffer.startTap(onExtra)`), E2 (joining fills a piece with no engine sound with silence of its length), E3 (each saved video's log line says "engine sound kept" or why not), E4 ("No engine sound for this clip" on the Engine row) |
| 35 | Idea | Edit menu: learn from another editor's screens (13 screenshots) | Built (the rider skipped the prototype) | What works there: category tabs always visible; selecting something shows only its tools; every tool opens a panel with ✕/✓ and Apply to all while the video keeps playing; the fade and speed show on the timeline as you change them; a speed curve; transition squares between clips and + at the end; shape and quality (with file size) in the top bar. What doesn't: rows that scroll sideways and cut tools off, greyed-out tools, two names for one thing, clutter. Plan L1–L8 in `docs/plans/editor-learnings-plan.md`, including speed ramps from the ride's own marks (slow at the lean, at the downshift, fast straights). **Built:** L1 tabs Edit · Sound · Text · Look · Add (tab tools act on the clip under the playhead); L2 selection rows with the name and length and Done (a clip: Split · Speed · Sound · Look · More); L3 Cancel / Apply to all / Done under Speed, Colour and Crop, with the clip looping while Speed or Colour is open; L4 sound bars draw their level (volume points and fades) as they change, Speed shows "0:09 → 0:04"; L5 Speed › Ramp: a curve from 0.25× to 4× (tap, drag, hold) with Slow at the lean, Slow at the downshift, Fast straights and Punch in from the ride's marks; L6 a seconds and frames ruler (the transition squares, + and undo/redo by Play were there already); L7 a quality chip (720p / 1080p / 4K, 30 / 60 fps, Smaller / Recommended / Best, with the file size) used when the video is made and shared; L8 the Sound tab (Mixer, Voice-over, Duck, Sound, More). **Not built:** other shapes (4:5, 1:1, 16:9): the renderer and every graphic are made for 9:16, so that's a bigger job of its own; Ideas stays in the top bar. Speed below 0.5× keeps the voice (pitch kept) rather than switching to the engine |
| 36 | Bug/Idea | Edit menu round 2: a tab looked pre-selected; More instead of swiping; Add should be leftmost; free crop didn't work, crop needs frame shapes and sizing; the picked transition was cut off at the top; words on the Voice row covered its name | Fixed | Tabs: none is picked until tapped (tap again to hide its tools); Add is first. Tools come in pages of five: swipe sideways for the next five (dots show the page); every "More" is folded into those pages. Crop: why it seemed not to work: the video could only be zoomed in (1–3×) and moved within that zoom, so dragging it at 1× did nothing and it snapped back. Now: frame shapes Full / Free / 9:16 / 4:5 / 1:1 / 16:9 (dark outside the frame, in the preview and the made video via a new `FrameMaskEffect`), drag a frame corner to size it (shape kept unless Free), pinch the video smaller than the picture too (down to 0.3×) and move it anywhere; Rotate, Mirror, Slow push-in, Punch in, Reset stay. Transition circles no longer clipped by their slot. Voice row: clips with words show the words, not the name, and row names are solid |
| 37 | Bug | The title ("Downshift and sound on") and the series label ("ALL DOWNSHIFTS") show on the video but not on the timeline, so they can't be moved, lengthened, reworded or restyled | Fixed | They were drawn by the style itself on the first clip (the "opener"), not as texts. Now, when the editor opens, they become two texts on the Text row over the first clip (`TimelineEdits.liftOpener`, once per edit, saved as `openerLifted`), and the style stops drawing its own opener. They take every text tool: edit the words, look, colour, font, size, animation, move, start and length, hide, delete. Videos made without opening the editor keep the style's opener as before |
| 38 | Bug | Opening a made Reel to edit: "Keppo Moto isn't responding", and nothing in the error log | Fixed (needs a try) | It was a freeze, not a crash, and only crashes were logged. Likely cause: the preview's sound mix got its sounds ready on the screen's thread, and for each engine track that means decoding the whole engine-mic file to find horns and bumps; since #34 almost every clip has one, so a made Reel with many clips froze the screen, and again after edits. Now that work (and the silence track, and the export's own setup) runs off the screen's thread. Freezes are now logged: a watchdog writes what the screen was stuck on after 4 s, and on the next start Android's own record of a freeze or native crash (with its trace) goes into the error log once |
| 39 | Bug | Voice not caught in the fast middle of the 18 Oct ride | Checked with the 20 Oct clip and export; waiting on mic placement | The log (5 s readings, no sound) shows the fast stretch, 10:12:56 to 10:18:08 at 64–94 km/h, with every signal failing at once. Wind background is about -15 dB, nothing gets more than 1–3 dB above it, the voice detector reads 0.00, and the two-mic lift is at most +4 dB. Up to about 65 km/h it worked (a shout at 66 km/h was caught at 10:20). That isn't enough to fix with confidence: it's unknown whether the voice is audible under that wind, or when exactly the rider spoke. Plan, the rider's idea A: a voice test ride that records both mics and every chunk's measurements, with marks of where the rider spoke (proposed by Gemini from the sound, corrected on a review screen, or by the media button). Then a replay test on the computer to design and tune the fix against the real recording. `docs/plans/voice-at-speed-plan.md` › "V0, redone". **Update (19 Oct):** instead, the rider films an in-app video across speeds and sends it with the ride export and the times they talked; the test mode is built only if that isn't enough 20 Oct: the rider asked to focus on it using the 18 Oct ride; that export is the one already analysed and has no sound, so the choice is the in-app video (A) or building the voice test mode T1–T3 (B), see `tags-and-more-plan.md` §7 20 Oct test (clip-1792475094296, 05:44:44–05:45:04, 16–34 km/h): the phone's voice detector, the same detector replayed here, and a separate speech-to-text model (Whisper) all find no speech in the voice channel, even after noise removal; it is broadband wind with no voice pitch. Over the whole ride, voice was detected only when stopped (one weak reading at 28 km/h). At speed the wind on the voice mic (-26 to -17 dB) is as loud as speech at a stop (-22 to -14 dB), so the speech is masked before the app gets it. Not a software threshold: the voice transmitter needs to be out of the wind (inside the helmet, with its windscreen). Asked the rider where it sits, and offered a test ride with the helmet headset (HS1_5DC7) as the voice mic. |
| 40 | Idea | Storyboard (was Template Chat): describe a Reel idea in a chat (text or voice), Gemini writes a storyboard, the app builds a Reel template with placeholder clips to film or fill later; gear and style memory; saved chats | Built (final plan, Part D, steps 13–22) | Storyboard button on Studio; chats (text or voice, 3 free a month, Pro unlimited) with gear and style memory; shot cards with versions and a To film tab; templates open in the editor with `ph:` placeholder pictures; fill a shot by Record (3-2-1), a moment, a phone video or Replace in the editor, auto-trimmed to the slot; suggestions from every ride (after each ride too), badged until Keep; to-film lines on the shot list; read all voice-over lines in one go; chats, gear, style memory and templates in the Drive backup. Found and fixed on the way: templates (no video yet) were dropped from Your Reels on restart; a shot naming words was offered any road clip. Plan in `docs/plans/template-chat-plan.md`. Checked against the code. Corrections: no music library, so a template gets a music note plus an optional own song; the shot list is plain text and must grow; Drive backup doesn't carry Reels or `studio-style.json` today; Studio is built around a ride, so templates open like a phone-videos Reel; downshifts aren't an event type; every clip segment needs a clip, so placeholders get a `ph:` stand-in, the main refactor risk. Questions inside |
| 41 | Idea | Keywords on every moment: ride data (Lean 26° right, Acceleration 0.6 g, Top 94 km/h, Downshift ×3), sounds (talking, shouted, scream, laugh, sneeze, horn), b-roll and scene words, your own keywords, search by keyword | Built (in the next APK) | Plan in `docs/plans/tags-and-more-plan.md` §1. Ride keywords on the phone from samples; sounds from the transcript request Gemini already makes (no extra request); b-roll by rule (no talking, steady, road camera, moving, 5 s+); scene words from ML Kit on 3 frames; new keywords table (Room migration), older moments tagged once while charging. Questions: g or m/s², English only, limits 20 Oct: acceleration and braking as speed runs ("20–85 km/h in 6.1 s", "0–60 km/h in 4.8 s", "80–20 km/h in 2.6 s"), with personal bests Built: keywords column (db 14); ride keywords after each ride (lean from Moments › Lean, speed runs and 0–60/0–100 and braking 60–0/100–0 with peak g, top speed, rev, hairpins/turns, sunrise/day/sunset/night by the sun's position, place, camera, b-roll, downshifts); sounds from the transcript request (talking + words, scream, laugh, sneeze, cough, singing, whistle, horn, siren, rain, dog) and a shout measured on the phone; scene words from ML Kit on 3 frames; chips in the moment viewer (remove for good, add your own); Studio search by keyword and number with quick filters; Gemini suggestions see keywords; older clips tagged while charging; in backups and ride.json (documented). Tests: MomentKeywordsTest, BackupTest |
| 42 | Idea | Auto ride start; then (20 Oct) start a ride by voice ("Hey Google, start a ride on Keppo Moto") and a home-screen widget to quick start | Voice start, widget, shortcut and tile built (in the next APK); auto start parked | `tags-and-more-plan.md` §2. App Actions (start exercise "ride") with a routine as the fallback, since Gemini may not follow App Actions; Glance widget (Start ride, readiness, last ride; time, distance, Pause and End while riding); app-icon shortcut and Quick Settings tile; all open the app and start after a 3 s countdown with Cancel Built: widgets as plain RemoteViews (no new library): small (Start ride in the bike's colour, bike name; time and distance while riding) and large (bike, mic/GPS readiness, Start ride, last ride; time, distance, Pause/Resume and End while riding), updated on ride changes and every 15 s while riding. App-icon shortcut, Quick Settings tile, App Actions START_EXERCISE ("ride") opening keppomoto://ride/start. All open a 3 s countdown with Cancel (asks for location or a bike if missing). Settings › Riding explains them, with the routine fallback. Test: QuickStartTest |
| 43 | Idea | Add friends | Parked (rider, 20 Oct) | §3 |
| 44 | Idea | App accent colour follows the bike's colour; (20 Oct) a full colour wheel with hex code | Built (in the next APK) | §4. Colour wheel + brightness + hex field + quick picks + live preview; a readable shade is used for buttons and text when needed (your exact colour stays for routes); recording red and data colours fixed; "Match the app to my bike" setting Built: Bike › Edit › Bike colour opens a wheel (hue and strength), brightness, hex code, the 8 quick picks plus recent picks, and a dark/light preview with notes when a readable shade is used or the colour is close to the recording red. The app theme, riding screens and the pop-up follow the selected bike; Settings › Appearance › Match the app to my bike. Test: AccentTest |
| 45 | Bug | (20 Oct, clarified) The phone mic was used a few times although the DJI mic was connected; not reproducible | Fixed (in the next APK; can't be reproduced, so watch the log) | §5. Likely cause: Android routes to the phone mic despite the app asking for the receiver (another app grabbing the mic), and the app only logs it and carries on, its 10 s check comparing what it asked for. Plan: check the real routed mic and reopen on the receiver (at most every 30 s), pop-up shows the real mic, each clip logs its mic, an error-log entry per ride. The 18 Oct log only shows the receiver unplugged 23 s before the end Built: the recorder checks which mic Android really records from (every 10 s and on each routing change) and reopens on the receiver while it's connected (at most every 30 s); the pop-up shows the real mic; each saved clip logs "mic: …"; one error-log warning per ride when it stepped in. Test: MicRoutingTest |
| 46 | Idea | Send WhatsApp instead of SMS (crash alerts) | Skipped (rider, 20 Oct) | §6 |
| 47 | Idea | Achievements need a full redesign: they look very basic | Built (in the next APK) | Plan in `docs/plans/achievements-plan.md`. Today: 22 achievements in two plain lists. Plan: rider level, next-up row, riding-year calendar, badge grid with drawn badges and Bronze to Diamond tiers, badge detail with share card, records, new badges (exploring, seasons, time with the bike, moments, secret ones from #41 keywords), celebration after the ride only. Keeps the code's no-speed/lean-goals rule, so #41's 0–60 "Best" and HUD note are dropped. Questions inside Built: tiered badges (Bronze→Diamond) with drawn medallions and tilt shine; rider level ring (Rookie→Legend, from badge points); Next up; riding-year calendar and weekend streak; badge groups incl. Exploring (new places, new roads after the first ride, highest point, hairpins, states), Seasons, Time with the bike (a year of riding, per-bike km), Moments and Studio, Secret (sneeze, scream, midnight on New Year's Eve, same date a year later, three fill-ups in a day); records without speed or lean; setup checklist; badge sheet with Open ride and a Stories card to share; per-ride facts cached in files/achievements; celebration with confetti on the ride summary, a quiet note at app start for off-bike badges, each shown once. Test: AchievementsTest |
| 48 | Bug | The ride crashed 3 times on 20 Oct (05:53, 05:58, 06:43); Android restarted it each time after ~4 s without recording | Fixed (in the next APK) | Unscrambled with the build's mapping file: the Moments log stamps each line by cutting the time text at a fixed place, and on an exact whole second Java leaves out the milliseconds, so the cut fails. The mic line every 5 s hits that about once in 17 minutes. Now a fixed time pattern; test added. |
| 49 | Bug | Storyboard: version pills copy the version, every version looks the same, "remove a clip" did nothing | Fixed (in the next APK) | Pills now only switch which version you look at; a Versions list in the chat; the card lists every shot and what changed (worked out by the app). Gemini returns the whole updated storyboard instead of loose patches (which were silently dropped when they said "remove" or used the shot number on screen); a claimed change that didn't happen is retried once, then reported honestly. The storyboard's shape now follows the idea (no fixed shot count) and it asks up to 3 focused questions before drafting when the idea is vague. |
