# Voice start: audit and plan

"Film when I speak" (Moments): talking starts a video, and it keeps filming while you talk.
This audit covers how it works today, what goes wrong (with the evidence), what "perfect"
should mean, and a plan to get there. Plan only; nothing here is built yet.

Evidence used:
- **20 Oct ride:** 4 min 24 s at 16–102 km/h, the rider's own marks of when they talked, helmet and jacket tracks separately.
- **22 Oct ride:** 49 min, no talking at all; export log only. The shared audio was mono, so the two channels couldn't be checked.
- The code as of b6b74db.

---

## 1. How it works today

**Listening (about 20 times a second).** Every ~46 ms the app takes a slice of sound from the helmet mic (and, with a two-transmitter receiver, the jacket mic). For each slice it works out:

| Signal | What it is | Compared against |
|---|---|---|
| Loudness | The helmet mic's level in the voice range (300 Hz–3.4 kHz) | "Background": the quietest 10% of the last 8 s; not updated while you're "speaking" |
| Voice-like | Silero voice detector, 0–1 | 0.5 in calm air, down to 0.35 in heavy wind |
| Two-mic lift | How much louder the helmet mic is than the jacket mic, overall | The usual difference over the last 8 s |
| Voice at speed | The same, but only in 1–4 kHz (the crisp part of a voice) | The usual difference over the last 60 s |

**Deciding (every 0.2 s).** A slice counts as "loud" if **any one** of these holds:
- loud enough **and** voice-like
- the two-mic lift is big enough
- the voice-at-speed lift is big enough

"Speaking" starts after **1 s** in which at least 60% of the slices are loud, with no gap over 0.25 s. It continues while at least **15%** of the last second is loud.

**Filming.**
- Speaking starts a video that includes the **11 s before**.
- It keeps filming until **5 s after** the last loud slice, up to 10 minutes per video.
- After the ride, videos less than **5 s** apart are joined into one.

**Wind.** The app estimates wind from the background level. In wind it lowers both the loudness margin and the voice-like bar, so a voice still counts.

**Safety net.** If the *two-mic lift* keeps "speaking" going for 30 s with nothing voice-like in it, that rule is switched off for 5 minutes.

**Settings.**
- **On/off.**
- **Sensitivity:** a new 5-step slider (b6b74db) that moves all thresholds together.
- **Two mics:** stereo, swap.

---

## 2. What goes wrong

Ranked by how much each hurts.

### A. Any single rule can start a video, and one noisy rule is enough
The three rules are **or**-ed. If one is unreliable on a given helmet or receiver setup, it alone decides.

**22 Oct:** 243 false starts in 49 min, every one from the voice-at-speed rule, while the voice detector read 0.00 all ride. Partly fixed in b6b74db: the two-mic rules now need two *different* mics confirmed. That covers the receiver sending one mic twice (Safety Track). It doesn't protect against a helmet that simply lets that rule's signal jump around.

### B. Once on, it is very hard to switch off
Four things stack up:
1. **Staying on is easy.** "Speaking" continues while only 15% of the last second is loud.
2. **Every blip adds time.** Each loud slice pushes filming 5 s further.
3. **Separate videos get joined.** Videos under 5 s apart are merged, and each new video already starts with 11 s of look-back.
4. **The background stops updating while "speaking".** If wind rises during a false start, the wind itself looks like talking, so a false start can keep itself going.

**22 Oct:** 33 saved videos were joined into a handful of long ones; the longest was ~24 min.

### C. The safety net has a hole
The 30-second "no voice in it" check only covers the overall two-mic lift. The voice-at-speed rule counts *as* voice, so it can never trip the check. That was deliberate: the voice detector reads 0 at speed even when you talk. But it means nothing stops that rule when it goes wrong.

### D. Wind is estimated late
Wind comes from "the quietest 10% of the last 8 s". When you speed up, that only catches up once nearly the whole 8 s is windy: **5–7 s late**. Meanwhile:
- the threshold mark lags
- the new wind is measured against the older, calmer background, so it can look like talking

The app already knows your GPS speed every second and doesn't use it for this.

### E. The voice detector is deaf at speed
On 20 Oct, Silero read **0.00** throughout 16–100 km/h, including while you were clearly talking. It gets the raw helmet audio, wind roar included. Wind is mostly low-pitched; the detector is never given a version with the low rumble removed. That's untested, and may recover a lot.

### F. If the voice detector fails, everything counts as voice
On an error, `SileroVad.probability` returns **1**. A detector failure would quietly turn the voice check off and let loudness alone decide.

### G. We tune on one 4-minute clip
Every threshold so far comes from the 20 Oct clip, one helmet on one day. The 22 Oct ride proved that isn't enough. There's no quick way to replay a real ride through the rules and score them: talk caught, false starts, minutes filmed in silence.

### H. Setup problems are only found mid-ride
- **Receiver mode:** Mono or Safety Track only shows up after 10 s of sound, as a log line or a small HUD note.
- **Swapped mics:** found only after 4 s of talking.

Neither is checked before the ride, when it's easy to fix.

### I. No sure way to force or stop a clip by hand
If voice detection fails you either way, there's no physical control. A helmet intercom or headset button can send "play/pause", but the app doesn't listen for it during a ride.

---

## 3. What "perfect" means (targets)

On a labelled set of real rides (several helmets, city and highway, with and without talking):

| Measure | Target |
|---|---|
| Talking stretches of 2 s or more that get filmed | **100%** (your rule: never miss talk) |
| Delay from first word to "speaking" | **≤ 1 s** (nothing is lost: the video includes the 11 s before) |
| False starts with no talking | **≤ 1 per 10 min** of riding |
| Filming while silent | **≤ 5%** of silent riding time; never a runaway (no voice-only video longer than talking + 10 s) |
| Threshold mark after speeding up | within **1 s** |
| Setup problems (receiver mode, swapped mics) | found **before** the ride |

---

## 4. The plan

### Phase 0: Measure first (nothing else is trustworthy without it)
1. **Voice trace in the ride export** (tester builds).
   - **What:** for every 0.2 s tick, a CSV row with each signal: loudness, background, voice-like, both lifts, wind, speed, speaking.
   - **Cost:** about 1 MB per hour.
   - **Payoff:** every ride you export becomes a test case. The current log only keeps one reading every 5 s.
2. **Replay harness.** A test that runs a trace through the rules and prints a scorecard against the section 3 targets.
3. **Labelled set.**
   - **What's in it:** 20 Oct (talk marked) and 22 Oct (no talk), re-exported with the trace once it exists, plus 3–5 more rides: city, highway, visor up and down, another helmet if possible.
   - **Labels:** your marks, or "I didn't talk at all".
   - **Rule:** every change below has to beat the current rules on this set before it ships.

### Phase 1: Never run away (safety, small)
1. **Background keeps learning.** If "speaking" goes on for more than ~5 s, the background resumes learning (slowly), so rising wind can't keep a false start alive.
2. **One safety net for all rules.** Stops runaways while still allowing long real talk at speed:
   - if a rule has kept "speaking" going for 30 s, and
   - the voice detector, run on de-winded audio (Phase 3), heard nothing voice-like in that time,
   - that rule pauses for 5 minutes.
3. **Harder to stay on.** Raise the "stay on" share from 15% to ~35%, and shorten the after-hold to ~3 s when only a lift rule (not the voice detector) is holding it. Real talk keeps it on; scattered blips don't.
4. **Stricter joining for voice-only videos.** Only join when the gap is under 5 s *and* at least one of the two had the voice detector confirm talking.
5. **Detector failure fails safe.** On an error the voice detector reads 0 and the log says so.

### Phase 2: Wind from speed (fixes the lag)
1. **Wind estimate from GPS speed**, learned per ride. Each second the app pairs speed with the background it measures, and the fitted curve predicts the wind for the current speed.
2. **Faster threshold.** The mark uses the louder of measured and predicted wind, so it moves the moment you accelerate. The measured one still corrects it, e.g. when a truck passes or you're stopped in wind.
3. **Faster correction.** A 3 s window instead of 8 s on the measured side.

### Phase 3: A better voice signal
1. **De-winded voice detector input.** Remove the low rumble (below ~250 Hz) before Silero, and test on 20 Oct. If it stops reading 0.00 at speed, the voice detector can veto false lifts again. This is the single biggest possible gain.
2. **Combine the signals instead of "any one".** Each signal gives a 0–1 score, normalised by how it usually behaves *on this ride* (its own quiet level and spread). Talking = the combined score over a threshold. A signal that's noisy on your helmet automatically counts for less. The slider moves that one threshold.
3. **Self-calibration.** Stretches where every signal reads quiet are used to learn each signal's quiet behaviour for this ride. This adapts to the helmet, the visor and the weather without you doing anything.

### Phase 4: Setup and calibration (UX)
1. **Pre-ride mic check** in the readiness line and widget:
   - receiver mode (Stereo, or one mic sent twice)
   - mics swapped
   - transmitter battery or connection lost

   Shown before you start: "Mic: set receiver to Stereo".
2. **Helmet check** (about 30 s, optional, in Settings › Moments):
   - stay quiet for 10 s, then talk for 10 s, with the engine running or with road noise playing
   - the app sets the slider for this helmet and shows how clearly it heard you: "Voice stands out by +9 dB: good"
   - one saved calibration per helmet if you have more than one
3. **Live readout while tuning:** the settings meter shows each signal and which one would trigger, so "why did it film?" is visible.

### Phase 5: After the ride, a second opinion
1. **Re-check saved voice videos** after the ride (phone charging or idle) with the slower, better analysis: de-winded voice detector, plus the transcript if transcription is on.
   - Voice videos with no talking get marked **"No talking found"**: dimmed, with one tap to delete them all.
   - Long videos get trimmed to the talking parts plus a margin.
2. **Same rule as now:** live detection still leans towards false starts. Cleanup happens later, so missing talk stays rare and false recordings stop costing storage and review time.

### Phase 6: A button that always works (optional)
- **Helmet intercom or headset play/pause** during a ride = start a video now (or keep this one going). A second press stops it.
- **Off by default:** it would take that button away from music while riding.

---

## 5. Decisions for the rider
1. **Phase 0 trace in exports:** OK to add (tester builds only, ~1 MB per hour of riding)?
2. **Phase 5 cleanup:** mark and offer to delete (my pick), or delete videos with no talking automatically?
3. **Phase 6 button:** want it? Which button do you have (DJI transmitter button, intercom, wired headset)?
4. **Test rides:** can you do 3–5 more rides with the trace on, telling me roughly when you talked (or that you didn't)? Each one becomes part of the test set.

## 6. Order and size
| Step | Size | Depends on |
|---|---|---|
| Phase 0 (trace, harness, set) | small–medium | — |
| Phase 1 (no runaways) | small | Phase 0 to prove it |
| Phase 2 (wind from speed) | small | Phase 0 |
| Phase 3.1 (de-winded detector) | small to try, measured on 20 Oct | — |
| Phase 3.2–3.3 (combined score, self-calibration) | medium | Phases 0, 3.1 |
| Phase 4 (pre-ride check, helmet check) | medium | Phase 3 for the helmet check |
| Phase 5 (after-ride second opinion) | medium | Phase 3.1 |
| Phase 6 (button) | small | — |

Suggested first build: Phase 0 + Phase 1 + Phase 3.1 tried on 20 Oct, then Phase 2. That alone should stop runaways and fix the lag. The rest gets measured against your real rides, not guessed.
