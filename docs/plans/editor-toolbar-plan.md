# Studio editor: timeline and tool bars

Log row #29, which builds on #28 (trim with the full clip). The prototype is https://claude.ai/artifact/BAwWR2Ye9ZztESKCFYFX9L. **Not built yet.**

## The timeline

- **Row names** in a fixed column on the left, in this order:
  - Video
  - Video on top
  - Filters and effects
  - Text
  - Stickers
  - Captions
  - Mic 1 · Voice
  - Mic 2 · Engine
  - Sound and voice-over

  Empty rows take no space. This replaces #20's gap.
- **Every block works the same way:**
  - tap to select it, and white edges appear;
  - drag an edge to change when it starts or ends, with a bubble showing the time and the change;
  - hold and drag a selected block to move it in time.
- **Clips, videos on top and sounds** show the rest of their recording beside them, faded (#28). The edge turns red at the recording's start or end.
- **Filters, effects, text and stickers** have no recording behind them, so they stretch freely.
- **The two mic rows** can be trimmed and split, but not moved, duplicated or deleted.
- **The preview** shows what's on at the playhead: filters and effects, text, captions, stickers, video on top, sound name. It plays through the unused parts too.
- **✂ next to the time**, under the preview, splits whatever is selected at the playhead.
- **Text and stickers** are placed by dragging them on the preview. A selected one has a dashed outline.

## Tool bar rules

- At most **5 icons**, spread across the width. They **never scroll**.
- An icon with a small corner mark opens its own set.
- **‹** stays fixed on the left to go back.
- A line above the icons says where you are: "Add › Sticker", or the name of the selected thing.
- **Values** open a slider in place of the icons: volume, speed, strength, size, opacity, fades, brightness, contrast, saturation.
- **Choices** open a strip of options, with thumbnails where they help: filters, stickers, fonts, colours, transitions, styles, animations, speed ramps. **The strip is the only thing that scrolls.**
- The current value shows under its icon.
- **Add** is always first and **More** always last. More holds Use all (for things with a recording), Replace (clips), Duplicate and Delete.

## Tool bars

| Selected | Icons | Inside |
|---|---|---|
| Nothing | Add · Style · Captions · Sound · Transitions | **Style:** strip (Clean, Cinematic, Bold, Playful, Native, Film, Editorial). **Captions:** Show, Look, Fix words. **Sound:** Mixer, Clean voice, Lower when I talk. **Transitions:** strip for all cuts (None, Fade, Slide, Zoom, Whip, Spin, Flash) |
| **Add** | Video · Text · Sticker · Sound · Filter | **Video:** Phone video, Photo, Ride clip. **Text:** adds at once. **Sticker:** Emoji, Speed, Lean, Map, Time. **Sound:** Music, Sound effect, Voice-over. **Filter:** Filter, Effect |
| Clip | Add · Edit · Colour · Transition · More | **Edit:** Speed (slider 0.25–4×), Speed ramp (strip: None, Slow into the corner, Fast–slow–fast, Speed up, Slow down), Crop, Reverse, Freeze. **Colour:** Filter (strip), Brightness, Contrast, Saturation (sliders), Reset. **Transition:** strip, for the cut after this clip. **More:** Use all, Replace, Duplicate, Delete |
| Filter or effect | Add · Change · Strength · More | **Change:** strip with previews. **Strength:** slider 0–100% (the preview follows) |
| Text | Add · Edit text · Style · Animate · More | **Style:** Font (strip), Colour (strip), Box (strip), Size (slider), Move. **Animate:** In, Out, Loop (strips) |
| Caption | Add · Fix words · Look · More | **Look:** Font, Colour, Box, Position |
| Sticker | Add · Change · Move · Animate · More | **Change:** strip |
| Video on top | Add · Edit · Colour · Volume · More | **Edit:** Crop, Speed, Shape (strip), Move. **Colour:** Filter, Opacity. **Volume:** slider |
| Mic 1 · Voice | Add · Volume · Fades · Clean · More | **Volume:** slider 0–150%, plus Points (the volume line, `audio-layers-plan.md` C1). **Fades:** Fade in, Fade out (sliders), Shape (Linear, Smooth, Fast). **Clean:** Clean voice, Wind (slider) |
| Mic 2 · Engine | Add · Volume · Fades · Horns · More | **Horns:** strip (Reduce all, Mute all, Keep all). Single horns and bumps stay tappable on the timeline |
| Sound | Add · Volume · Fades · More | |

**Removed:**
- **Format** moves to Share; Reels are 9:16.
- **The clip's own Sound tools:** the clip's sound lives on the Mic rows, so there's one place to control it.
- **Title as a separate kind of text:** it's a text style.
- **Captions under Add:** captions come from what's said, and live under Captions.

**Renamed:** the clip's Look is now **Colour**, so it doesn't clash with Style (the whole video) and Filter (a timed block).

**New:**
- **Speed ramp**
- **Move** for text, stickers and videos on top (drag on the preview)
- **Per-clip Transition**
- **Transitions for all cuts**

## Build order

1. Row names, no empty rows, ✂ by the time (with #20).
2. The same edge/move controls on every block, and the rest of the recording for clips, videos and sounds (#28).
3. The tool bar tree: 5 icons, ‹ fixed, the "you are here" line, sliders and strips.
4. The new tools: Speed ramp, Move on the preview, per-clip and all-cuts transitions.
5. Removals and renames: Format to Share; clip Sound to the Mic rows; Look to Colour.

**Checks:**
- Contact sheets of every tool bar at 320 dp and the largest text, to check that 5 icons never scroll or cut off labels.
- Unit tests for the tree: no level has more than 5 icons.
