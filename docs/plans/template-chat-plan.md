# Template Chat: plan (Phase 0)

You describe a Reel idea in a chat, by text or voice note. Gemini turns it into a storyboard script. Once you approve it, the app builds a **Reel template**: the full Reel with titles, captions and transitions, with **placeholder clips** where footage goes. Each placeholder is a picture saying exactly what to film. You fill the placeholders with real footage later.

Items are marked **Decided** (from your brief or your answers), **Claude's idea**, or **Correction** (where the brief and the app differ). Answer the **Question:** lines. Nothing is built until you approve this plan.

Answers received 19 Oct: music note yes; back up made Reels; on-screen text can be anything; a template works exactly like the editor; record or upload (Claude decides how recording works); Pro limits as proposed. Entry: a floating button on the Studio page. Still open: the name (§1.1).

Log row #40.

---

## 0. What already exists: checked in the code

### Correct as written
- **Scripts:** `Script` has `PieceFormat`, `Section`s (`SectionKind` Hook … Ending) with `form`, `text`, `why` and `ScriptShot`s. Saved by `ScriptJson`, edited by `ScriptEdits` (setText, resize, move, remove, swap, hook, sync, fromPlan).
- **Script → Reel:** `StudioEngine`, `StudioPlan` (`ClipSegment`), `ReelMaker`, `StudioRenderer`, `Timeline` / `TimelineEditor`, `Tracks`. `ClipSegment.still` is a picture file held for `durMs` (the freeze frame uses it).
- **Saved Reels:** `ReelProject` (has `script`) and `ReelStore`.
- **Gemini:**
  - `StudioGemini` via Firebase AI Logic;
  - `FirebaseTranscriber`'s model fallback list (newest Flash first, Flash-Lite for light jobs);
  - `RemoteModel` (Remote Config);
  - `GeminiQuota`;
  - `AiConsent`.
- **Style memory:** `StyleStore` (`studio-style.json`: rules plus an edit log, sent as `StyleContext`) and the notes on the Style screen.
- **Shot list:** `StudioPrefs.shots`, `ShotListCard` on Home, and the HUD pop-up at the ride's start.
- **Events and matching:** `RideEventType`, `ClipDownshifts`, `ClipSearch`, `topSpeedMps`, `CaptionLine`, `Footage.look`.
- **Captions:** Hinglish and English subtitles.
- **Pro:** `Entitlements` and `FreeReels` (one free Reel a week).
- **Backup:** `backup/` with Drive sync.
- **Demo mode** exists.

### Corrections
- **C1 Music (Correction):** Studio has **no music library**, by your choice (#1: Instagram first). A Reel can carry one song you pick from your phone (`musicUri`), or the music is added in Instagram.
  - So a template can't come "with music". It gets a **music note**: mood, tempo and where the beat should drop ("hard hit at STEP 3"), plus your own song if you pick one.
  - **Decided (rider):** a music note plus an optional song of your own.
- **C2 `GeminiWait`:** this is the on-screen "Gemini is free again at 4:30" countdown, not a request queue. The chat reuses it for its "wait" state.
- **C3 Shot list:**
  - `StudioPrefs.shots` is only a list of text with a done tick, saved in a small settings file. It has to grow: the template it belongs to, its shot id, mount and position.
  - The HUD pop-up shows it for the first 30 s of a ride.
- **C4 Backup:**
  - The Drive backup carries rides, moments, bikes, settings, Studio styles, series and the brand kit.
  - It does **not** carry made Reels (`ReelStore`) or `studio-style.json`.
  - The plan adds chats, templates, gear and the style memory to it (§9).
  - **Decided (rider):** made Reels are backed up too (see §11).
- **C5 Studio works per ride:**
  - `StudioViewModel` / `StudioEngine` open either one ride's clips or "phone videos only" (`PHONE_STUDIO`).
  - A template belongs to no ride: it opens like a phone-videos Reel, and can borrow clips from any ride (the editor already does this for "other rides").
- **C6 No "downshift" event type:** downshifts come from the engine sound per clip (`ClipDownshifts`), not from `RideEventType`. Matching uses both.
- **C7 Every clip segment needs a `Bit`** (a clip id, length, words). A placeholder has no clip, so it gets a stand-in `Bit` (id `ph:<shotId>`, no file) plus `still` = the placeholder picture. The renderer and editor must then never try to open a file for a `ph:` id. That's the main refactor risk (§11).

---

## 1. Where it lives

### 1.1 Entry (Decided, rider: a floating button on the Studio page)
- **A round floating button** at the bottom right of the Studio tab, above the tab bar: a chat bubble with a sparkle, in Studio's accent colour.
  - It doesn't cover the idea cards' swipe area or the "Ask for something" box: it sits just above that box, at the right.
  - For free riders, the "1 free Reel this week" chip moves to the left, so the two never overlap.
  - When the button first appears, a one-time hint says "Plan a Reel with Studio".
- Tapping it opens the chat list, newest first, with "New chat" at the top. Tap a chat to reopen it.
- **Question:** Is "Plan a Reel" the right name? Alternatives: "Template chat", "Storyboard".

### 1.2 The chat screen (Claude's idea)
- **Top:** the chat's title and ⋯ (Rename, Delete chat, Gear & style).
- **Middle:** the conversation. Gemini's replies can carry:
  - a **storyboard card** (sections with shot cards, §4);
  - **confirmation chips** for memory changes ("Added: tripod · Undo");
  - **one clarifying question** at most, with tap-to-answer choices.
- **Bottom:** a text box, a **hold-to-talk voice note** (transcribed, shown as text before sending so you can fix it), and Send.
- The storyboard card's buttons are **Review** (the full shot list, §4) and **Make template** (§5).
- Each template made appears in the chat as a tile (cover, length, "6 of 11 shots filled"). Tap it to open the template.

---

## 2. The script model: shots with no clip yet

### 2.1 Placeholder shots inside the existing model (Decided)
`ScriptShot` gains optional fields. Existing shots don't change, and old scripts load as before.

```
ScriptShot(
  clip: String,            // "" for a placeholder (was always a clip id)
  inMs, outMs,             // for a placeholder: 0 and its planned length
  id: String? = null,      // stable shot id ("s7"), for patches and replacing
  board: Storyboard? = null,
  locked: Set<String> = {} // fields you edited by hand
)
```

`Storyboard`:
- `action`: director style, specific.
- `framing`: extreme close-up, close-up, medium or wide.
- `angle` and `movement`.
- `mustShow`: the key visual detail.
- `camera` and `mount`: from your gear.
- `gearFallback`: the ideal gear, the reason, and what is used instead.
- `durationMs`, `caption`, `audio` (engine, music or voice first), `transition`.
- `match`: event types, context words, minimum length, needs engine sound, must-record or can-match.

How it stays safe:
- **JSON:** the new fields are optional. `ScriptJson.read` keeps reading `clip` as before, and an empty clip is allowed. Old scripts have no `board` and load unchanged.
- **The Reel pipeline:** a placeholder becomes a `ClipSegment` with a stand-in `Bit` (`ph:<shotId>`) and `still` = its picture (C7). When filled, the same segment gets the real `Bit`, with the shot id kept for replacing again.
- **Sections:** stay `SectionKind`.
- **On-screen text (Decided, rider):** "STEP 1: THE LOOK" was only an example. Any text can appear on the Reel: titles, step names, jokes, captions.
  - Gemini writes them as a list of **on-screen texts**, each with its words, which shot it starts on, and how long it stays.
  - In the template they become ordinary **texts on the Text row** (as the title became in #37). You can move, lengthen, reword and restyle them like any other text.
  - The spoken-word captions stay the existing caption system.

### 2.2 Versions (Decided)
- Each template keeps its script history: version 1, 2, 3 and so on, each with what changed.
- One version is current, and that is the source of truth.
- Shot cards show "edited by you" on locked fields.

---

## 3. Gemini: what is asked and what comes back

### 3.1 Reply format (Decided)
Every reply is JSON:
- `reply`: the chat message;
- `script`: the full script on the first draft, or `patches` after that (update, insert or delete specific shots by id, so approved parts stay put);
- `memoryUpdates`: gear or style add, update or remove, each with a confirmation line;
- optional `styleSuggestion`;
- optional `needsClarification` (one question, with up to 4 choices).

How replies are checked:
- Validated against the schema.
- Durations are kept within the format's length.
- Shot ids must exist.
- Invalid JSON gets **one** retry, with the error included. If that fails too: "Couldn't read Gemini's answer · Try again", and the full answer goes to the error log.

### 3.2 Rules in the prompt (Decided)
- Only shots your gear can capture. Missing gear: the closest alternative, plus `gearFallback`. Never silently drop a shot.
- Recordable solo, unless gear memory says a helper is available.
- Shots filmed while riding use fixed mounts, never handheld.
- Specific enough that two people would film nearly the same shot.
- `StyleContext` is the default. A request in the chat wins, without changing memory.
- Fill gaps sensibly: "Step one: the ___" becomes "The Look", and the reply says so.
- Never patch a locked field unless you ask in so many words.

### 3.3 Models and requests (Claude's idea)
| Task | Model | Requests |
|---|---|---|
| First draft from your idea | Main Flash (the fallback list, `RemoteModel` override) | 1 |
| Each chat change | Main Flash | 1 per message |
| Voice note to text | Flash-Lite (as transcripts do today) | 1 per note |
| Auto-suggest (§7) | None: matching runs on the phone | 0 |
| Gear onboarding | Part of the first chat's first reply | 0 extra |

- A typical chat (draft, 3–5 changes, 1–2 voice notes) is about **5–8 requests**.
- They count against a new quota bucket, `GeminiQuota.CHAT`, so the chat can never use up the allowance for transcripts or ride suggestions.
- When it's out, the chat says when it's back (`GeminiWait`) and keeps your message to send then.

---

## 4. Review and editing (Decided)

- **Shot cards grouped by section**, showing:
  - shot number;
  - the action in bold;
  - framing, camera/mount and duration as chips;
  - a small framing icon;
  - the fallback note;
  - "Must record" or "Can use footage";
  - the matched clip, if any.
- **Edit directly:** tap a field to change it. That field becomes locked. New pure edits go into `ScriptEdits` (setAction, setDuration, setFraming, moveShot, deleteShot, addShot), with unit tests.
- **Edit by chat:** "make step 3 faster, 3 cuts of 1 s" → patches to those shots only.
- **Your direct edits go into `StyleStore`'s edit log** (before, after, your note), the same as script edits today, so Studio learns from them (§9.2).
- **Claude's default:** a shot can be moved anywhere, including into another section. It's an ordinary clip in the editor (§5.2).

---

## 5. Making the template

### 5.1 Placeholder pictures (Decided)
- One **PNG per placeholder at the Reel's size (1080×1920)**, drawn by the app (no Gemini). Stored as `templates/<id>/ph-<shotId>.png`.
- It shows:
  - the shot number;
  - the action in large text;
  - framing, camera/mount and duration in smaller text;
  - a simple framing icon (eye for an extreme close-up, head for a close-up, rider for medium, road for wide);
  - the fallback note, if any.
- **Look:** the Studio style (`Tokens`), on a dark slate with a dashed frame, so a placeholder can never be mistaken for real footage.
- It's on the **video track** as a `still` clip for the shot's exact length, **not** a text layer.
- Each placeholder segment carries its **shot id**, so it can be found and replaced.

### 5.2 Building (Decided, with Claude's ideas)
- **Approve → Make template:** script → `StudioPlan` through the existing planner, with placeholders as stills. Captions, step titles, transitions and the music note sit on their own tracks, so replacing footage keeps them.
- **A template works exactly like today's editor (Decided, rider).** It *is* a Reel in the editor:
  - every tool works: trim, speed, crop, transitions, text, sound, layers, Share;
  - placeholders are clips like any other, showing their picture;
  - it can be made into a video at any time, as it stands, with no watermark;
  - what's different: the "Template" filter in Your Reels, "6 of 11 shots filled", and the Replace options on a placeholder.
- **Saved as a `ReelProject`** with `kind = TEMPLATE`, its chat id and its script versions (§10).
- **In Your Reels**, it shows under a new filter, "Templates", with "6 of 11 shots filled".


---

## 6. Filling placeholders, and auto-trim

### 6.1 Replace (Decided)
Tap a placeholder in the editor or on its shot card, and choose:
- **Record now** (§8.2);
- **Pick a moment clip** (from any ride, suggestions first);
- **Pick a phone video**.

### 6.2 Auto-trim (Decided)
- **Longer clip:**
  - Footage with an event is centred on the event (the downshift, the lean, the loudest rev).
  - Footage with words centres on the words that fit the `action`.
  - Otherwise the middle.
  - The trim isn't destructive: the editor's handles show the rest of the recording.
- **Shorter clip (Claude's idea):**
  1. Within 15% short: slow it down to fit, to 0.85× at most. Not noticeable.
  2. Up to 30% short: slow to 0.7×. A small "slowed" badge shows on the clip.
  3. More than that: use it at 0.7× and **shrink the slot**. The next shots move up; the music note and titles move with them.
  4. A shot marked "engine sound first" is never slowed (the pitch would drop). It goes straight to shrinking the slot.

All of it is pure code with unit tests.

---

## 7. Auto-suggest (Decided)

- **Matching runs on the phone**, against every ride's moments. It uses:
  - event types;
  - downshifts;
  - words said (`ClipSearch` on transcripts);
  - `look` (selfie, road or phone);
  - top speed;
  - length;
  - engine sound present.
- **Scoring** against the shot's `match` hints. The top 2–3 suggestions per placeholder.
- **The best suggestion fills the slot**, with a "Suggested" badge, until you confirm it or pick another. Must-record shots are never filled by a suggestion.
- **Shots with no good match** go on the to-film list (§8).

---

## 8. Shot list and recording

### 8.1 To film (Decided, with Claude's ideas)
- **Unfilled shots** go into the existing shot list. It grows from plain text to:
  - shot id;
  - template;
  - text;
  - mount;
  - position;
  - done.
- **Grouping** is by mount and place ("Tripod at the tunnel entrance: shots 2, 5"), so one setup films several shots.
- **On Home** (`ShotListCard`) and in the HUD pop-up for the first 30 s of a ride, as today, with the template's name.
- **Done** when the slot is filled.

### 8.2 Recording or upload (Decided, rider: "recording, you choose")
Each placeholder offers **Record** or **Upload**: a phone video or a moment.

How Record works, decided per shot from its storyboard:
- **Shots off the bike** (tripod, handheld, a helper): a **full-screen camera page**. It shows:
  - the shot's instruction on screen;
  - the right camera, front or back;
  - a guide frame for the framing;
  - a 3-2-1 countdown.
  - It records the shot's length plus 1 s each side; the clip drops into its slot, trimmed (§6.2). Retake or keep.
- **Shots while riding** (fixed mount): recording on the bike happens with the ride camera, so these go on the **to-film list** (§8.1). On your next ride, the HUD pop-up shows them, and one tap there starts your own video for that shot. After the ride, the clip is offered for its slot ("Shot 4 filmed · Put it in?").

---

## 9. Memory

### 9.1 Gear (Decided)
- **New, small store** (`studio-gear.json`), global across bikes:
  - cameras (phone front and back, action cam…);
  - mounts (where, and which way they face);
  - audio (for example DJI Mic with its two transmitters, helmet and back seat);
  - extras (tripod, selfie stick…);
  - whether a helper is available.
- **Each item records where it came from:** said in a chat, or set in Settings.
- **First chat:** the bot asks about cameras, mounts and audio in one friendly question, reads back what it understood, and asks one follow-up only if it matters. If you skip it, it assumes phone plus handlebar mount.
- **Gear mentioned in any chat** comes back as `memoryUpdates`. A confirmation chip appears: "Added: tripod · Undo". Nothing changes silently.
- **Two-mic setup:** the app already knows it (Two mics on, voice and engine). It's offered as a starting point: "DJI Mic, 2 transmitters, helmet and back seat?".

### 9.2 Style (Decided)
- **`StyleStore` grows; there's no second store.** New fields:
  - pacing;
  - music;
  - caption tone;
  - transitions;
  - structure habits.
- **Each has its source:** said, or learned from edits.
- **Learned from repeated edits** (the same kind of change 3 times) only after a confirmation chip: "You keep shortening the hook to 2 s. Remember that?"

### 9.3 Settings page (Decided)
Studio › Settings › **Gear & style**:
- every item and where it came from;
- edit it, delete it, add one.

---

## 10. Saved chats and templates (Decided)

### 10.1 Storage
- **Chats:** `filesDir/studio-chats/<chatId>.json`. Holds:
  - the messages;
  - each reply's raw JSON (for debugging);
  - the ids of the templates it made.
- **Templates:** `ReelProject` (`kind = TEMPLATE`) in the existing `ReelStore`. Its placeholder PNGs sit in its folder. No Room tables, no migration.

### 10.2 Rules
- **Reopening a chat** restores it.
- **When you ask for a change, the bot always asks: "Update this template, or save as a new version?"**
- **Once a template has any real footage, chat edits never change it.** They make a new template, with the footage already matched copied over as suggestions.
- **One chat can make several templates** ("now a long version").

---

## 11. Backup (Decided, with C4)

- **Drive backup gains small JSON files:** chats, templates (their `project.json` and script versions), gear, and the style memory (`studio-style.json`, missing today).
- **Placeholder PNGs aren't uploaded.** They're redrawn from the storyboard after a restore, so they're quick to regenerate and save space.
- **Older backups restore as before:** the new files are optional.
- **Made Reels (Decided, rider), Claude's approach:**
  - back up each Reel's `project.json`, cover, voice-over takes and the song you picked (small);
  - don't back up the made video itself (large). After a restore it's made again from the project and the backed-up moments, the first time you open or share it.
  - Templates and their footage come back the same way. Placeholder pictures are redrawn.

---

## 12. Pro and the free Reel (Claude's idea)

- **The chat and templates are free to try:** 3 chats a month, each up to about 10 Gemini requests.
- **Pro:** unlimited chats (within the Gemini allowance).
- **The free weekly Reel is used when a filled template is made into a video,** as with any Reel. Making the template (placeholders) doesn't use it.

**Decided (rider):** yes, these limits.

---

## 13. Risks and refactoring

- **R1 Placeholder clips in the pipeline (largest):**
  - The renderer, the preview, transcripts, ride traces, engine tracks, Search and Remove pauses all expect a clip file behind every `Bit`.
  - Every one needs a "no file for `ph:`" guard. I'll list each place, then add a test that makes and previews a template with only placeholders.
- **R2 Studio is built around a ride (C5):** templates open the phone-videos way, with clips borrowed from all rides. Some ride-only features (the ride row, revs) will be empty until clips are filled. That's expected.
- **R3 Gemini JSON reliability:** long storyboards with patches. Mitigated by a strict schema in the request, the one retry, validation, and refusing bad patches without breaking the script.
- **R4 Prompt size:** gear, style, the script so far and the chat history. Old chat turns are summarised after about 10 messages.
- **R5 Shot list model change:** `StudioPrefs.shots` moves to a small JSON file; old plain-text shots carry over.
- **R6 Big new UI:**
  - the chat;
  - shot cards;
  - the gear page;
  - the to-film grouping;
  - the record page.
  - A **prototype** of the chat and shot cards comes first, as for the other UI.
- **R7 Quota:** the separate CHAT bucket keeps transcripts and suggestions safe.

---

## 14. Build order (one commit each, then one APK)

1. **Prototype** (an HTML page): the chat, the storyboard card, shot cards, and a placeholder picture. Your approval first.
2. **Model:** `ScriptShot` id, board and locked fields; `Storyboard`; `ScriptJson` old and new; script versions. Tests.
3. **Gear store + chat engine:**
   - prompt, JSON schema and validation, the one retry;
   - patches and locking;
   - `memoryUpdates`;
   - the CHAT quota.
   - Tests, using recorded Gemini answers.
4. **Chat screen + saved chats + voice notes + gear onboarding.**
5. **Shot cards, direct edits, versions;** edits into `StyleStore`.
6. **Placeholder PNGs, template building, `ph:` guards (R1), Templates in Your Reels.**
7. **Replace + auto-trim** (with the shorter-clip rules).
8. **Auto-suggest** from all rides.
9. **To-film list + HUD + record into a slot.**
10. **Style memory additions + Gear & style settings page.**
11. **Backup, Pro limits, polish.**

### Checks
- Unit tests:
  - scripts old and new;
  - patches;
  - locking;
  - memory updates;
  - auto-trim;
  - matching;
  - the tunnel example's storyboard checked against your gear.
- Demo mode: a demo ride's clips feed auto-suggest.
- Before each APK: `:core:telemetry:test :app:testDebugUnitTest :app:lintDebug assembleDebug -PabiFilter=arm64-v8a -PshrinkDebug=true`, expecting 0 lint errors.
- Existing Studio suggestions, Reels and the editor are left working.

---

## 15. The tunnel example, as it would come out (Claude's sketch)

Your gear: phone, DJI Mic, handlebar phone mount, tripod.

**How to enter a tunnel 101**, Reel, about 28 s.
- **Hook:** "How to enter a tunnel 101" over shot 1.
  - Shot 1: the tunnel mouth from the road, wide, phone on the tripod at the roadside, 2 s. Can use footage.
- **STEP 1: THE LOOK** (filled in for "the ___"):
  - Shot 2: your eyes through the visor, locking on, extreme close-up.
    - Ideal: a helmet chin cam.
    - Fallback: phone on the handlebar mount, front camera, tilted up.
    - 1.5 s, must record.
- **STEP 2: THE APPROACH:**
  - Shot 3: left boot clicks the shifter down twice, close-up, phone on the tripod low at the roadside as you pass, 1 s.
  - Shot 4: hand blips the throttle (rev match), close-up, handlebar mount, back camera facing the right grip, 1 s, engine sound first.
  - Shot 5: the tunnel growing in frame, medium, handlebar mount, back camera, 2 s.
- **STEP 3: THE ENTRY:**
  - Shots 6–8: three cuts of 0.7 s, full throttle, handlebar mount, back camera, matched from moments with a strong acceleration.
  - Shot 9: the long engine shot, 6 s, the tunnel echo, engine sound first, DJI back-seat mic.
- **STEP 4: THE EXIT:**
  - Shot 10: you screaming with excitement, selfie, medium, handlebar mount, front camera, 2 s, voice first. Can use a moment with a VOICE event at high speed.
  - Shot 11: wide of the bike leaving the tunnel, phone on the tripod at the exit, 3 s.
- **Music note:** a dark build from STEP 2, the drop on shot 6. The music stays quiet under shot 9 (engine first).
- **To film, grouped by setup:**
  - Tripod at the entrance: 1, 3.
  - Tripod at the exit: 11.
  - Handlebar back camera: 4, 5.
  - Handlebar front camera: 2.
  - Shots 6–8, 9 and 10 can likely come from moments.
