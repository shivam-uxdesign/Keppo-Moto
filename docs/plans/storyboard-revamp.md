# Storyboard revamp

Status: plan only, nothing built. Asked for on 25 Oct: "very smart, functional and easy to use… a UX beast".

## What's wrong today

The rider's own reports (plan log #70–#72), plus a walk through the code:

| Problem | Why it happens |
|---|---|
| Brings up ideas the rider never asked for (the tunnel) | The instructions sent to Gemini carried example text and old edits. Partly fixed in a23c7c9. |
| Ignores the rider's own script | A new script was blended shot by shot into the old one. Partly fixed (`fresh`). There is still no way to say "use my words exactly". |
| "Don't use my previous clips" is ignored | Gemini never picks clips. Make template auto-fills shots from every ride's clips, whatever the chat said. |
| My text never becomes captions | Captions only come from speech the app hears in a clip. The storyboard has no caption field, so text lands as titles. |
| Something said for one Reel applies to every Reel | Notes on shot edits are saved as global style rules. |
| Feels scattered | Chat → card → Review shots (separate screen, edit by pop-up form) → Make template → per-shot Fill screen → separate Read lines → editor. Versions sit in a pop-up, and gear and style are on another screen. |
| A chat is the only way in | Pasting a finished script, or starting from ride clips, goes through the same open-ended chat. |
| Hard to trust the AI's changes | Changes show as a list. There is no undo, and no way to point at specific shots. |

## The idea in one line

**A Reel is a project with one board.** Keppo is a co-pilot that edits the board in the open. It doesn't hide the work inside a chat. The rider can always see, touch, undo and lock everything.

## Structure

One project screen with three stages along the top: **Write · Shoot · Edit**. A stage chip shows progress, e.g. "Shoot 5/9". The project list replaces the chat list.

```
Projects
└─ Project "Chai stop at Lonavala"
   ├─ Write   the board: shots, words, timing; Ask Keppo (bottom sheet); Play animatic
   ├─ Shoot   shots grouped by setup; Shoot mode (teleprompter + camera); ride shots
   └─ Edit    opens the Studio editor with everything placed (clips, voice-over, captions, text)
```

### Starting a project: four doors

1. **I have an idea:** type or talk. Keppo asks at most 2–3 quick tap-to-answer questions (length, talking on camera or voice-over, feel), then drafts the board.
2. **I have a script:** paste or dictate.
   - Keppo splits it into shots and keeps every word exactly as written: lines become voice-over, and lines in quotes or marked TEXT become on-screen text.
   - It only fills in the filming details (camera, mount, framing, length).
   - Nothing is rewritten without asking.
   - A banner says "Your words kept exactly · 14 lines → 9 shots".
3. **From a ride:** pick a ride. Keppo builds the board around its best clips (today's Reel ideas, in the same board).
4. **Blank board:** add shots by hand. Keppo is still one tap away.

## Write: the board

The heart of the revamp. It's a vertical list of shot cards in sections (hook, build, payoff…), with a time ruler and the total length against the format's limit (e.g. "38 s / 90 s").

**A shot card.** Collapsed, it reads at a glance; tap to expand. Each part is edited in place, with no pop-up forms:

- **Picture:** a placeholder sketch, or the filled clip's frame.
- **What to film:** one line, e.g. "Kettle steaming, close-up".
- **Setup chips:** camera · mount · framing · riding/still · length. Tap a chip to change it.
- **Three word lanes,** which settle "captions vs text" for good:
  - **Say:** the voice-over line.
  - **Captions:** *From Say* (default) / *Custom* / *Off*. With From Say, the captions show the Say words as written; the Reel-level choice can switch them to what was actually said once recorded.
  - **On-screen text:** titles and labels, in the title look, with a start and length.
- **Status pill:** To write / To film / Suggested / Filmed / Recorded.
- **Lock** on any field: Keppo can't change a locked field unless the rider names it.

**Gestures:** drag to reorder; swipe left to delete (with Undo); long-press to multi-select; a "+" between cards inserts a shot.

**Health strip** (a thin bar above the board, tap for details). These are checks the app works out itself, not Gemini:
- "Line in #4 needs ~4.2 s, shot is 3 s → Stretch shot / Trim line"
- "#6 needs a tripod, which isn't in your gear → Use handlebar instead"
- "#2 is filmed while riding but planned handheld"
- "38 s over the Story limit"
- "3 shots have no captions while you're talking"

**Play animatic.** Plays the board in real time with:
- placeholder frames, on-screen text and captions in the chosen style
- the recorded voice-over when there is one, otherwise a timed beat
- music from the music note, if one's chosen

You see the Reel before filming anything. It's built on the existing simple preview player.

### Ask Keppo: the bottom sheet

The chat becomes a sheet over the board. It's half-height by default, swipes down to a single line, and keeps its history per project.

- **Scoped asks.** With shots selected, the input shows "Changing #3–#5". Quick chips: *Punchier · Shorter · Funnier · More Hinglish · Split this · Add a hook · New take*.
- **Changes happen on the board, visibly.**
  - Changed cards glow; added ones slide in green; removed ones fade out red.
  - A snackbar reads "Keppo changed 3 shots · Undo · See changes".
  - "See changes" shows a before/after per shot.
- **Undo is one tap,** right after the change and later from Versions.
- **Questions come as tappable choices,** as today, but at most one at a time and only when the answer changes the plan.
- **Voice input everywhere:** hold the mic in the sheet, as today.
- **"Keppo is writing…"** with a hint of what it's doing ("Splitting your script into shots…").

### Pinned rules for this Reel

These replace the global "My style" for Storyboard. They show as chips under the project title.

- When the rider says something that sounds like a rule ("don't use my old clips", "keep it under 30 s", "all voice-over in Hinglish", "no music"), Keppo pins it. The rider sees "Pinned: no old clips" with Undo.
- Rules live in this project only. They go to Gemini on every turn.
- Where the app can, it enforces them itself rather than trusting Gemini:
  - **No old clips:** shots are never auto-filled, and suggestions are hidden.
  - **Length:** the health strip warns.
  - **Captions source:** applied when the Reel is built.
- Tap a chip to edit or remove it.
- "Save as my default" can promote a rule to every new project (an explicit choice, never automatic). This is the only global style left, and it's visible under Settings.

### Versions

- A slim row at the top of Write: v1 · v2 · v3 ●.
- Every Keppo change and every manual session makes a version, named by what changed ("Shorter hook", "Your edits").
- Tap a version to view it; then Compare (side by side, changes highlighted) or Restore (makes it current; nothing is lost).
- "New take" makes a parallel version the rider can switch between (A/B).

## Shoot

**Clip source (per project, set once and visible on the Shoot tab).** *Fresh only* / *Suggest from my rides* (default) / *Auto-fill best matches*.
- With Suggest, matches appear on the card as "Suggested" and need a tap to use.
- With Fresh only, the app never shows old clips.
- The chat can set this ("don't use previous clips" → Fresh only, pinned).

**Shot list grouped by setup** (already worked out today: where the phone goes). For example:
- "Tripod at the roadside · #1, #3, #7"
- "Handlebar, back camera, riding · #2, #5"

Each group has a Shoot button. Riding shots also show on the ride pop-up and Home, as now.

**Shoot mode** is a full-screen camera with the shot card on top:
- what to film, plus a framing guide overlay (close-up/medium/wide box)
- the length, with a countdown and a progress ring
- a teleprompter line when the shot has a Say line
- after each take: *Keep · Retake · Next shot*; kept takes drop straight onto the board
- it walks through a whole setup group in one go

**Voice-over booth.** Merges today's Read lines into Shoot:
- it reads every Say line in order, Next between lines (as today)
- or one line at a time, with retakes per line
- each take shows its length against the shot ("4.1 s / 3 s → stretch shot?")

**Phone video / moments pickers:** kept, inside the shot card's Fill menu.

## Edit

"Open in editor" builds the Reel from the board:
- clips or placeholders on the video track
- voice-over takes on the voice track
- **captions made from the voice-over**, per the project's caption choice:
  - **Written:** the Say words, timed to the take (word timing from the take's transcription, words from the script)
  - **What I said:** what the app heard
- on-screen text on the Text row
- the music note in the Sound tab

The board and the Reel stay linked. Changing a shot's Say line on the board offers "Update the Reel?" instead of silently diverging.

## Smart, and kept in check

- **Message intent first.** Each rider message is sorted, before Gemini drafts anything, into: new idea · own script · change selected/named shots · rule · question · gear. The app then picks the right prompt and checks:
  - an own script is kept word for word (the app compares the returned Say and text lines with the pasted words and asks again if they drifted)
  - a change touches only the named shots, otherwise it's flagged
- **Only this project's context.** Nothing from other projects, past edits or examples. Gear and pinned rules are the only standing context.
- **Changes as operations.** Gemini returns operations on the board (update/insert/delete/move, with shot ids). The app applies them, refuses anything that touches locks, and says when nothing changed (as the honest message does today).
- **The app's own checks** (the health strip) never depend on Gemini.
- **Language.** Say lines in the rider's way of writing (Hinglish in English letters). Captions copy Say exactly; the English subtitle option stays.

## Small things that make it feel good

- Empty states that teach: "Paste a script" with a sample, "Hold to talk", and the four doors.
- Every destructive action can be undone or asks first (deleting a project, a version or a shot).
- Duplicate a project ("make another like this").
- Projects show a thumbnail strip, stage and progress ("Shoot 5/9 · 2 days ago").
- Gear: asked once, inline, in the first project; editable later in Settings › Storyboard gear.
- Free tier: "2 of 3 projects this month" on the list. A paywall only when starting a new project, never mid-edit.
- Works offline for everything except asking Keppo; the board, animatic and Shoot mode don't need the internet.

## Data changes (for the build)

- **`StoryboardProject`** replaces chat + template linkage: id, title, createdAt, updatedAt, stage, messages, versions, current, pinned rules, `clipSource`, `captionSource`, the template/Reel id, and `parentVersion` for takes.
- **`Storyboard` shot additions:** `captionMode` (FROM_SAY/CUSTOM/OFF), `captionText`, per-shot `onScreen` texts (today's `texts` move onto shots), `status`, kept takes.
- **Migration:** each chat becomes a project. Its newest version is current, and existing templates stay linked. The global style rules stay for ride Reels; Storyboard stops reading them.

## Build phases

| Phase | What | Why first |
|---|---|---|
| **P0 quick fixes** | Storyboard stops using global style; clip-source switch (Fresh only / Suggest / Auto) honoured by Make template; voice-over takes get captions from the written words; captions choice Written / What I said | Fixes everything the rider hit, without the redesign |
| **P1 board** | Projects; Write board with inline editing, gestures, locks; Ask Keppo sheet with scoped asks; visible changes with Undo; versions row; pinned rules; four doors (own-script path kept word for word) | The new core |
| **P2 words and preview** | Three word lanes per shot; health strip; Play animatic | Makes captions vs text obvious; see it before filming |
| **P3 shoot** | Setup groups with Shoot buttons; Shoot mode (framing guide, teleprompter, keep/retake/next); voice-over booth with per-line retakes | Filming gets easy |
| **P4 polish** | Message-intent routing and word-for-word checks; board ↔ Reel sync prompts; duplicate; empty states; migration clean-up | Smartness and finish |

Each phase ships as one build with tests (the board operations, intent routing, word-for-word check, caption timing, migration).

## Questions for the rider

1. Should the regular Reel Studio (Reels from rides) also stop learning rules from what you type, and should existing rules be cleared?
2. Clip source default: Suggest (tap to use) or Fresh only?
3. Captions default for new projects: Written or What I said?
4. Shoot mode: should it use the app's own camera screen (framing guide and teleprompter), or is the phone's camera plus picking the video afterwards enough for now?
5. Should Play animatic read missing voice-over lines aloud with a synthetic voice, or just show them as captions until recorded?
6. A prototype of the Write board and the Ask Keppo sheet first, to try before building?
