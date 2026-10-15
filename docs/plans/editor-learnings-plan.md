# Edit menu: what we can learn from another editor

Log row #35. **Plan only. A prototype comes next, after approval; the build after that, together with #34.**

The rider sent 13 screens of another phone video editor. We are not copying it. This is what it does well, what it does badly, and what that means for Studio's editor.

## What that app does

1. **Two fixed rows at the bottom.**
   - A tab bar that never moves: Edit, Music, Filter, Effect, Text, Sticker.
   - Above it, a row of tools for the chosen tab.
   - Switching area is one tap, with no Back.
2. **Selecting something swaps the tool row for that thing's tools.**
   - Video clip: Cut, Volume, Audio Highlight, Speed, Replace, Crop, Delete.
   - Music: Volume, Fade, Replace, Split, Copy, Trim, Rhythm, Delete.
   - Intro clip: only Replace, Delete, Volume.
   - Only what applies is shown.
3. **Each tool opens a panel in place of the rows, with ✕ (cancel) and ✓ (done).**
   - Fade: two sliders with their values.
   - Speed: a 0.25×–8× slider plus Apply to all.
   - The video keeps playing while you adjust, so you hear and see the change at once.
4. **The timeline shows the edit as you make it.**
   - The fade draws as a slope on the music's waveform.
   - A selected clip gets a red outline, trim handles and its length inside it ("00:09").
   - The preview outlines the selected clip too.
5. **Variable speed: a speed curve.**
   - Points on a line from 0.25× to 8×, with Add Marker, Reset and presets (Custom ▾).
   - One clip can go slow, then fast, then slow (a speed ramp).
6. **The timeline itself.**
   - A small square button sits between clips for the transition.
   - A + sits at the end of the track to add a clip.
   - The ruler counts frames when zoomed in.
   - Undo and redo sit next to Play.
7. **Top bar.**
   - Back.
   - The shape (9:16), which opens a row of shapes: Original, 16:9, 4:3, 1:1, 3:4, 9:16, 21:9.
   - The quality ("1080P ▾"), which opens Resolution, Frame rate and Bitrate with the file size it will make ("about 49.2 MB").
   - Export.
8. **Music tab:** add music, Sound effects, Extract (sound from a video), Record (voice-over).

## What it does badly (we avoid these)

- **Rows that scroll sideways.** The clip and music rows have 7–8 tools and cut off at the edge ("Dele…"), so tools hide off screen. Studio keeps at most 5 per row with no scrolling (#29).
- **Greyed-out tools that look disabled** ("Audio Highlight" with a red dot). A tool is either there or it isn't.
- **Different names for the same thing:** Cut and Split, Trim and Crop, Copy on one row only.
- **Clutter:** NEW badges, a music library (Studio stays Instagram-first: no library), sticker packs.

## What Studio already has

- The 5-icon tool bar tree with Back on the left (#29).
- Tools for the selected element.
- Sliders, the volume line with ease, fade shapes.
- Speed, crop, reverse, freeze, colour, transitions.
- Snapping, the rest of the recording, words and ride marks on the timeline (#28, #30).
- Undo, redo and history, plus export settings.

So most tools exist. What we learn is mainly about how they **feel**: fewer steps, edits you can see, and safe ways to try things.

## Plan

### L1. Category tabs at the bottom, always visible

- When nothing is selected, the bottom row is 5 fixed tabs: **Edit · Sound · Text · Look · Add**.
- The tools above change with the tab, at most 5, no scrolling.
- One tap moves between areas, with no Back.
- Deeper levels still use the tree, with Back fixed at the left (#29).

### L2. Selecting something shows its tools at once

- The tabs give way to that thing's own row, with its name on the left ("Clip 2 · 0:09", "Engine", "Caption").
- Only tools that apply, at most 5. More is the 5th when needed.
- Clip: Split · Speed · Sound · Look · More.
- Sound bar: Volume · Fade · Split · Duck · More.
- Text or caption: Edit · Style · Timing · Animate · More.
- Tapping empty timeline, or ✕, returns to the tabs.

### L3. Panels with ✕ and ✓ that you can try safely

- Every tool with settings (Speed, Fade, Volume, Colour, Crop, Transition) opens a panel that replaces the bars.
- The panel has ✕ (undo everything since opening), ✓ (keep), and **Apply to all** where it makes sense (speed, colour, transition, fades on every clip).
- The video keeps playing in a loop over the selected clip while the panel is open, so changes are heard and seen live.
- The rest of the timeline is dimmed but stays visible.

### L4. Show the edit on the timeline while editing

- **Fade:** the slope is drawn on the bar's waveform while dragging, using the fade's shape (Linear / Smooth / Fast).
- **Speed:** the clip's length updates live in its label ("0:09 → 0:04"), and the speed line is drawn over the clip.
- **Volume:** the line is drawn over the waveform (as in #21).
- The selected clip shows its length inside it. The preview outlines the selected element (text, sticker, layer).

### L5. Speed ramps, made for riding (better than a hand-drawn curve)

- In Speed › **Ramp**: a curve with points from 0.25× to 4× (8× is too fast for ride footage), with Add point and Reset.
- Presets come from the ride data we already have (`EditorRide` marks):
  - **Slow at the lean:** 0.5× through the deepest lean, normal before and after.
  - **Slow at the downshift:** 0.5× for 1 s around each downshift mark.
  - **Fast straights:** 2× where speed is steady and nothing happens, 1× at events.
  - **Punch in:** 1× → 0.3× on the moment → 1× (the classic Reel ramp).
- Points snap to the ride's marks. The voice track keeps its pitch, or goes silent below 0.5× with the engine kept instead (a choice in the panel).

### L6. The timeline, small upgrades

- A small square between clips that opens the transition picker (shows the transition's icon once set).
- A **+** at the end of the video row to add a clip from the ride or the phone.
- The ruler counts frames (1 f) when zoomed in far, and steps by frame there.
- Undo and redo move next to Play (history stays under a long press on Undo).

### L7. Top bar: shape and quality where you can see them

- Top bar: ⌄ · **9:16** · **1080p ▾** · Share.
  - 9:16 opens the shapes: 9:16, 4:5, 1:1, 16:9, Original. These are the ones Instagram, YouTube and Stories use; 21:9 and 3:4 are left out.
  - 1080p opens Quality: Resolution (720p / 1080p / 4K if the clips allow), Frame rate (30 / 60, only 60 if filmed at 60), and Quality (Smaller / Recommended / Best). It shows the size it will make ("about 38 MB") and the time it takes.
- Ideas moves into the Add tab, and the top bar keeps only what's needed.

### L8. Sound tab: everything about sound in one place

- **Voice · Engine · Voice-over · Duck · More.**
  - Duck: lower the engine under my voice.
  - More: Detach, Extract the sound of a phone video, Mic test tips.
- No music library (Instagram-first stays). Music added on Instagram is mentioned under More.

## Not taken

- Rows that scroll sideways, NEW badges, a music library, sticker packs, 21:9 and 3:4 shapes, and 8× speed.

## Prototype (after approval)

One phone-sized page:

- the fixed tabs (L1);
- selecting a clip, a sound bar and a caption (L2);
- Speed with a live clip length and the Ramp presets on a fake ride (L3–L5);
- Fade drawing on the waveform (L4);
- the junction square, + and the frame ruler (L6);
- the shape and quality sheets with size (L7).

## Build (after the prototype is approved, together with #34)

Each step is one commit.

1. L1 + L2: tabs and selection rows (`ToolBar` / `toolTree`).
2. L3: panel frame with ✕ / ✓ / Apply to all, a loop over the selected clip, a snapshot for ✕.
3. L4: live drawing of fade, speed and length.
4. L5: speed ramps.
   - Model: `SpeedPoint` on a clip, a pure time map, tests.
   - Renderer and preview.
   - Ride presets from `EditorRide`.
5. L6: junction buttons, + at the end, frame ruler, undo/redo by Play.
6. L7: top bar, shape and quality sheets with the size estimate.
7. L8: Sound tab.

Then one APK.

Checks:
- unit tests for the speed ramp time map and the presets;
- lint 0 errors;
- contact sheets of the bars at each level.
