# Ask across all rides (#12): plan

Planned 24 Nov. **Built (#105).** Prototype with the recommended answers: https://claude.ai/artifact/DPJmjvu6N2iqzJRqZ6UQhn (#104).

## What the rider gets

Studio's **Ask for something** can reach every ride, not only the one picked:

- "A video of all my downshifts" → every downshift from every ride, cut together.
- "The rainy bits from October", "every time I said Tooooo", "hard braking near Gurugram".
- When the ask is unclear, Studio **asks back once or twice**, with answers to tap:
  "Since when?" · *All rides* / *Last month* / *Since October*; "How long?" · *15 s* / *30 s* / *As long as it takes*.
- Then the rider sees what was found ("14 downshifts from 9 rides · 0:42"), can trim the list, and
  opens it in the editor (or has it made in the background).

## What already exists (so the build is mostly glue)

| Piece | Where | State |
|---|---|---|
| What was said in each clip | captions and transcripts (`StudioText`, `Moment.transcript`) | Every talking clip, once read by Gemini |
| What's seen in each clip | `ClipSeen` ("police", "rain", "tunnel") | Clips looked at while Gemini's allowance lasted; older ones may have none |
| Search across all rides | `ClipSearch` (Studio › Search) | Works today, by words |
| Ride events on each moment | `RideEventType` (hard brake, strong acceleration, lean, top speed, voice…) | Every moment |
| Downshifts in clips | `ClipDownshifts` (engine rev blips while slowing) | Rides tagged since it was added; older rides need a one-time tagging pass |
| A Reel from clips of many rides | `Compilations` (Your Reels › themes) | Works today: hits → a Reel made in the background |
| Clips from other rides in the editor | `loadOtherRides`, `Bit.fromRide` | Works today |

## How it works

1. **The ask becomes a filter (one Gemini request).** Gemini gets the ask plus the *menu* of what can
   be searched: the event names, the things seen across the rider's clips, ride names and places,
   the date range of the rides. It answers with a small filter, or with one question and 2–4 answers:
   - *what*: words said, things seen, events (downshift, hard brake, lean, top speed, acceleration),
     "with my voice", "just the road"
   - *when*: a date range; *where*: rides or places
   - *how long* and *style* (optional; Studio picks otherwise)
2. **The phone finds the clips itself.** The filter runs on the phone over the clips' words, seen
   labels, events and downshift tags. No video or sound is sent for this, only the ask and the menu.
3. **Too many or none:** "Found 63, the best 20 are in" (ranked like today's suggestions); none found:
   it says what it looked for and offers the nearest ("No downshifts tagged before 12 Oct: tag the older
   rides?").
4. **Results sheet:** the count, rides and length, clip thumbnails in order (tap to watch, swipe one
   away), chips to refine (*Last month*, *Only with my voice*, *15 s*), then **Open in the editor** or
   **Make it now**.
5. **Making it** reuses today's compilation and editor: a Reel from many rides, like Your Reels' themes.

**Cost:** one Gemini text request per ask, plus one per question asked back (about 2–5 paise each on
gemini-3.8-flash). Pro only, like all of Studio's Gemini.

## Sketch

```
 Studio                      ＋ 🔍 🎞 ⋯
 [This ride ▾] [All rides]              ← new: where the ask looks
 ┌─────────────────────────────────┐
 │ ✨ all my downshifts            ➤ │
 └─────────────────────────────────┘

 Since when?                               ← asked back (only when unclear)
 ( All rides ) ( Last month ) ( Since Oct )

 14 downshifts · 9 rides · 0:42             ← results sheet
 ▢▢▢▢▢▢▢  (tap to watch · swipe to drop)
 ( Last month ) ( With my voice ) ( 15 s )
 [ Open in the editor ]   Make it now
```

## Build order (one commit each, then one APK)

1. **The filter and the search** (pure, unit-tested): `AskFilter` (what, when, where, length) and a
   search over every clip's words, seen labels, events and downshifts, ranked; the "menu" for Gemini.
2. **Gemini's part:** the prompt, reading its answer (a filter or a question with answers), at most two
   questions, then search with what's known. Tested with recorded answers.
3. **The screen:** the *This ride / All rides* switch on the Ask box, the question chips, the results
   sheet with refine chips.
4. **Making it:** Open in the editor (clips from many rides) and Make it now (background, like themes).
5. **Older rides:** a one-time background pass that tags downshifts on rides recorded before tagging
   existed (engine sound only, on the phone, no Gemini).

## Questions for the rider

1. **Where:** a *This ride / All rides* switch on the existing Ask box (recommended: one place to ask),
   or a separate chat screen in Studio?
2. **After the results:** open in the editor first (recommended, like everything since #96), or make it
   straight away and put it in Your Reels?
3. **Asking back:** at most two questions, then it does its best (recommended), or never ask, always guess?
