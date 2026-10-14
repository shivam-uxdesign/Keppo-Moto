# Two mic layers and volume control everywhere: plan

Planned with the rider. **Not built yet.** Log rows #19–#21 in `studio-feature-plan.md`.

## What the rider saw

1. **Only one sound layer.** The editor's Sound sheet shows only "Your voice", for a clip recorded with two DJI transmitters with the receiver set to **Mono**.
2. **An empty gap** in the timeline between the video row and the bars below it.
3. **Bars with no names.** The two coloured bars under the gap were the speed sticker (purple) and the clip's sound (blue). Their names weren't visible.
4. **Wanted:** control over how loud each sound is over time, with points ("markers") and ease in / ease out. It should work in Studio and on ride moments before sharing.

## Why

### 1. One layer: the receiver's Mono setting mixes the two mics

The engine sound is saved as its own file (`<clip>.engine.m4a`) only when all four of these hold:
- Settings › Two mics is on;
- the mic is a USB-C receiver;
- the receiver gives two channels;
- the two channels sound different.

In **Mono**, the DJI receiver mixes both transmitters into one sound and sends that same sound on both channels. The app hears "same on both sides" and keeps no second track.

That mix happens inside the receiver. Clips already recorded in Mono can't be split afterwards. Separate layers need the receiver in **Stereo** (TX1 left, TX2 right).

### 2. The gap: empty rows

The timeline gives captions one empty row and text another, even when the video has none. Two empty rows make the gap. (`Rows()` uses at least one row: `count.coerceAtLeast(1)`.)

### 3. No names: labels scroll off screen

Each bar's name is drawn at its left edge. When the timeline scrolls past the start, the name is off screen, so a bar that started at 0:00 shows no name at 0:05.

### 4. Volume points already exist, but are hidden

- Detached sounds, the engine and music already have volume points, fades and a renderer that applies them (`TrackEdits.levelAt`, `GainProcessor`).
- They're only reachable as buttons: "Dip here", "Full here", and a fixed 0.4 s / 0.6 s fade.
- There is no line to see or drag.
- The clip's own sound (your voice) has a single volume per clip and no points.
- Moment sharing has no sound controls at all: the exporter keeps the audio as it is.

## Plan

### A. Recording: always two layers, and say when the receiver is on Mono

- **A1. Keep mic 2 whenever two channels are recorded**, even when it sounds the same as mic 1.
  - It's marked `same` in the clip's sidecar file.
  - So two-mic clips always have two layers.
  - Cost: about 1 MB per minute of clips.
- **A2. Detect Mono and say how to fix it:**
  - HUD two-mic meter: amber "Receiver on Mono · set it to Stereo".
  - Settings › Two mics › mic test: the same message, with a picture of the receiver's Stereo setting.
  - Ride log: "two mics: receiver on Mono (both sides identical)".
- **A3. Name layers by mic**, following the swap: **Mic 1 · Voice** and **Mic 2 · Engine**. When mic 2 is the same, its layer reads "Mic 2 · same as Mic 1 (receiver on Mono)".

### B. Timeline: no gap, every row named

- **B1. Remove the gap:** no empty rows for captions, text or layers.
- **B2. Row names:**
  - A fixed column on the left of the timeline, with an icon, a name and Mute / Solo. Rows: Video, Layers, Captions, Text, Stickers, Mic 1 · Voice, Mic 2 · Engine, Voice-over, Music.
  - Each bar's name also sticks to the left edge of the screen while the bar is visible.
- **B3. The voice row becomes a real sound:** tap it to select it, with the same tools as other sounds (volume, points, fades, earlier/later).
- **B4. Both mic rows show** for every two-mic clip, one under the other.

### C. Volume control: the volume line

All sounds share one control:
- **C1. Volume line on each bar.**
  - Select a sound and tap **Volume**: the bar grows taller and shows the clip's waveform with the volume as a line over it.
  - Tap to add a point. Drag a point up or down (0–150%) and sideways.
  - Hold a point to delete it.
  - The level shows while you drag ("-6 dB · 50%").
- **C2. Ease between points.** Each point sets how the line reaches the next one:
  - Linear
  - Ease in
  - Ease out
  - Ease in-out (the default)
  - Hold (a step)
- **C3. Fade handles** at the bar's top corners. Drag them to any length, up to half the sound. Each fade has a shape: Linear, Smooth (S-curve) or Fast.
- **C4. Markers and presets:**
  - **Volume at markers:** markers you drop while watching can turn into volume points for a chosen track ("Dip to 30% at each marker", "Full at each marker").
  - **Lower under my voice:** automatic points on the engine and music wherever you talk (from speech detection and captions). They're editable afterwards, unlike today's fixed ducking.
  - **Swell at downshifts:** engine points up around tagged downshifts.
- **C5. One model for every sound:**
  - Each clip's own sound (mic 1) becomes a sound item linked to its clip, like the engine.
  - Volume points gain an `ease` field. Fades gain a `shape`.
  - Saved projects open as before: no points, linear fades.
  - All of this is pure code with tests.
- **C6. The preview plays it exactly:** the preview player applies the same levels, so what you hear is what gets shared.

### D. Ride moments before sharing

- **D1. Moment share screen › Sound:**
  - Mic 1 · Voice and Mic 2 · Engine lanes under the trim bar.
  - Each lane has volume, Mute, fades and the same volume line (C1–C3).
  - Defaults: voice 100%, engine 70%.
- **D2. Moment viewer:**
  - A quick switch: Voice / Engine / Both.
  - A volume for each mic, to listen before sharing.
- **D3. The exporter mixes the two mics** with their levels, reusing Studio's mixing (Media3 composition with two audio sequences and `GainProcessor`), instead of keeping the clip's audio as is.
- **D4. 3D ride video share:** each moment clip gets the same per-clip mix (voice and engine volume, fades into and out of each clip).

### E. One shared sound editor

- One composable, `SoundLanes`: rows, the volume line, fades and markers.
- Used in four places: the Studio timeline, the Studio Sound sheet (Mic 1, Mic 2, Voice-over and Music bars, each with fades), moment share, and ride video share.

## Build order (one commit each, then one APK)

1. **A (recording):** mic 2 always kept; Mono detected and shown in the HUD, mic test and ride log.
2. **B1–B2:** gap removed; row names (left column and sticky labels).
3. **C5 (model):** sound items for clip sound, `ease` and fade `shape`, JSON compatibility, renderer and preview. Tests: level at each ease, fade shapes, old projects loading.
4. **C1–C4 and C6:** the volume line editor (`SoundLanes`), waveform cache (`.wave.json` sidecar), the marker and voice presets.
5. **B3–B4 and E:** the editor and Sound sheet wired to `SoundLanes`.
6. **D1–D3:** moment share, the viewer, and the exporter mix.
7. **D4:** clips in the 3D ride video.

## Checks

- `:core:telemetry:test :app:testDebugUnitTest :app:lintDebug`, expecting 0 lint errors.
- Contact sheets for the timeline (with and without two mics, with large text) and the moment share Sound section.
- On a phone:
  - record with the receiver in **Stereo**: both layers appear in the HUD, the viewer, moment share and the editor;
  - record in **Mono**: the amber message shows, and Mic 2 reads "same as Mic 1";
  - shape a dip with ease in-out, share, and listen.
