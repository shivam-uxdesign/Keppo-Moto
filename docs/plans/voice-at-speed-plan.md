# Voice at speed: plan

Log row #25. **Not built yet.** This follows #6, which lowered the margin in wind and asked the voice detector for less.

## How detection works now

Each 46 ms chunk of the helmet mic is measured in the voice range (`VoiceBand`). It counts as "loud" when both of these hold:

1. **Loud enough:** its level is at least the margin above the background.
   - The background is the quietest 10% of the last 4 s (`BackgroundLevel`).
   - The margin comes down in wind, to a third of the setting, at least 3 dB.
   - Or, with two different mics, the helmet mic is 6 dB louder than usual compared with the engine mic (`TwoMicLift`).
2. **Voice-like enough:** the Silero voice detector is at least 0.5 sure it's a voice (0.35 in heavy wind).

Speech starts after 1 s of mostly loud chunks (`SpeechGate`).

## Why it still fails at speed

Four likely causes. The ride log (`mic:` lines every few seconds: level, background, margin, voice score, wind, lift) will show which one it is.

1. **The mic is full of wind.**
   - At speed the voice band is close to full scale, sometimes clipping.
   - A shout can't get much louder than the wind already is, so it's only a dB or two above the background, below even the lowered 3 dB margin.
   - This matches "the bar only moved 20%".
2. **The voice detector gives up in wind.**
   - Silero's score drops well below 0.35 in heavy wind, even for a shout.
   - The voice-like check is applied even when loudness says yes, so the chunk is thrown out.
3. **The second mic wasn't helping.**
   - The helmet-mic-versus-engine-mic comparison is the signal that works best in wind, because wind and traffic reach both mics but your voice mostly reaches the helmet one.
   - It's switched off unless the two mics sound different, and with the receiver on **Mono** they never do.
   - Your switch to Stereo turns it back on.
4. **Long shouting raises the background.**
   - The background is taken from the last 4 s.
   - About 3–4 s of continuous shouting starts to count as background, so the margin shrinks while you talk.

## Plan

### V0. First, measure (no code)

Ride with the receiver on **Stereo**, and shout at three speeds: about 40, 70 and 90+ km/h. Then export the ride (⋮ › Export ride) and send it.

The `mic:` lines show which of the four causes it is, at which speed. The thresholds below are then set from your real numbers, not guesses.

### V1. With two mics, trust the helmet-mic comparison

- When the helmet mic stands out from the engine mic (lift ≥ 6 dB), that counts as speaking without the voice detector's agreement.
  - Wind and engine reach both mics; only your voice lifts the helmet one.
  - (Removes cause 2 when two mics are used.)
- Roles stay fixed (#22): the comparison runs whenever Two mics is on, not only after the app decides the mics differ.
- The engine mic's "usual" difference is measured over a longer, steadier window (8 s), so speaking doesn't move it.

### V2. A voice detector that works in wind

Wind is a roar with no pitch. A voice, even a shout, has pitch and comes in syllables (about 3–6 per second). Two new measurements, both pure and tested:

- **Pitch strength (harmonicity):**
  - Cut everything below 300 Hz (most wind energy is there).
  - Look for a repeating pitch between 80 and 400 Hz (autocorrelation).
  - Wind scores near 0; a voice scores high even when it isn't louder than the wind.
- **Syllable rhythm:** how much the level pulses at 3–6 Hz over the last second. Talking pulses; wind and engine drone don't.

In wind, these replace Silero's score:
- A chunk is voice-like when Silero says so, **or** when pitch strength and syllable rhythm both say so.
- (Removes cause 2 for one mic too.)

### V3. Subtract the wind, learned per speed

- While you aren't speaking, the app learns the wind's sound (its spectrum) for each speed band: 0–30, 30–60, 60–90 and 90+ km/h.
- It subtracts that before measuring the voice band (spectral subtraction).
- A shout then stands out from the wind instead of hiding under it. (Helps cause 1.)
- The background no longer creeps up while you talk:
  - it isn't learned while speaking is detected;
  - it's taken from a longer window (10 s).
  - (Removes cause 4.)

### V4. Say when the mic is the problem

- **Clipping:** count clipped samples. When the helmet mic clips often in wind:
  - the pop-up shows "Mic overloaded by wind";
  - the ride log says so.
- **Settings › Moments › Voice at speed:** a short guide:
  - helmet mic inside the helmet near the chin, behind the padding, not in the airflow;
  - the fur windscreen on;
  - the receiver's gain lowered one step;
  - DJI noise cancelling on for the helmet transmitter.

### V5. A voice test at speed

- A "Test my voice at speed" mode for one ride: say "test" a few times at different speeds.
- The app records the levels it sees and sets the margin, lift and pitch thresholds for your mic and helmet.
- Afterwards it shows a small chart: your voice versus the wind at each speed.

### V6. A button, for when your voice can't win

- A Bluetooth media button can start a clip. That's the play/pause button on your helmet intercom, or a handlebar remote.
  - It works at any speed.
  - It's off by default; turn it on in Settings › Moments.
- The pop-up's meter shows wind level next to the voice bar. You can see when it's too windy for your voice to count, and use the button instead.

## Build order

1. V0: needs your ride export (no code).
2. V1, then V4.
3. V2.
4. V3.
5. V5.
6. V6.

One commit each, then one APK.

## Checks

- Unit tests with recorded samples:
  - wind alone at 40/70/100 km/h (must not trigger);
  - a shout and normal talk mixed into each (must trigger within 1.5 s);
  - a horn and an engine rev (must not trigger).
- Each step replays your exported ride's levels and compares what triggers before and after.

## Ride of 18 Oct (Sector 79 → Sector 70): what the log shows (#39)

The mic line is logged every 5 s, and the export had no sound. Averages by speed:

| Speed | Readings | Wind background | Level above it | Voice detector ≥ 0.5 | Two-mic lift ≥ 6 dB |
|---|---|---|---|---|---|
| Stopped | 37 | -34 dB | +6 dB | 2 | 12 |
| 1–40 km/h | 77 | -25 dB | +5 dB | 21 | 11 |
| 40–65 km/h | 39 | -19 dB | +4 dB | 2 | 4 |
| 65–94 km/h | 64 | **-15 dB** | **+2 dB** | 3 | **0** |

- Up to about 65 km/h it works: at 10:20 a shout at 66 km/h was caught (voice detector 0.74–0.99).
- The fast stretch (10:12:56 to 10:18:08, 64–94 km/h, about 5 minutes) gave no start at all, and every signal failed at once:
  - **Loudness:** the wind fills the helmet mic to about -12 dB (near full volume). Nothing gets more than 1–3 dB above it.
  - **Voice detector:** 0.00 in every reading.
  - **Two-mic lift:** at most +4 dB, below the 6 dB rule. The wind is as loud on the helmet mic as on the back-seat mic, so the helmet mic doesn't stand out.
- At a standstill, engine revs still gave false starts through the lift: 9 starts at 0 km/h had voice 0.00. The 30 s safety net (#33) caught it at 10:09:45 as designed.

**What the log can't tell us:**
- whether the voice is audible at all under the wind at 90 km/h;
- exactly when the rider spoke, since the readings are 5 s apart.

So any threshold change made now would be a guess. A fix needs the real sound, with the moments the rider spoke marked.

## V0, redone: a voice test ride (the rider's idea A)

**Not built yet.**

### T1. Voice test mode

- Settings › Moments › Voice at speed › **Record a voice test ride** (one ride, then it turns itself off).
- **Records both mics all ride long**, besides the normal clips: the helmet mic and the engine mic as two sound files, about 1 MB per minute each (roughly 60 MB for 30 minutes).
- **Writes every chunk's measurements** (every 46 ms) to a file. These are:
  - level and background;
  - the voice detector's score, on both mics;
  - the lift and the clipping;
  - speed;
  - the decision ("loud", "speaking").
  - New candidates, measured but not used yet: pitch strength (does it have a voice's pitch?), syllable rhythm (does it pulse 3–6 times a second?), how noise-like the sound is, and the energy below 300 Hz (wind) against 300–3400 Hz (voice).
- The pop-up shows "Voice test · recording" so it's never on by surprise.
- The recording stays on the phone. It only leaves when you export it.

### T2. Marking where you spoke (the least effort first)

1. **Automatic:** after the ride, Gemini listens to the whole helmet track and returns where speech is, with the words. Those become proposed "you spoke here" marks.
2. **Review screen:**
   - the ride's timeline, with speed, the proposed marks, and where the app started a clip;
   - tap any point to hear it;
   - add a mark where you spoke and nothing caught it, or remove a wrong one.
3. **Optional, during the ride:** press the helmet's media button (V6) as you start talking. That press is also a mark.

### T3. Export voice test

One zip containing:
- both sound tracks;
- the measurements file;
- the marks.

### T4. Replay and fix (my part, with your recording)

1. A replay test (pure code, on the computer) runs the detector over your recording, chunk by chunk, exactly as on the phone.
2. It scores each speed band: marks caught within 1.5 s, marks missed, and false starts (revs, wind, horns).
3. First I check whether your voice is audible at 80–94 km/h at all.
   - If it is buried in the wind, no software can find it. The answer is then:
     - mic placement: inside the helmet by the chin, behind the padding;
     - the fur windscreen on;
     - lower receiver gain;
     - DJI noise cancelling on;
     - the media button as the start.
   - If it is audible, the detector changes (V2, V3 and better two-mic use) are designed and tuned on your recording until the misses and false starts are low in every band:
     - V2: pitch strength and syllable rhythm;
     - V3: subtracting the wind, learned for each speed;
     - comparing the two mics across frequencies, not only their loudness.
4. Your recording's measurements become a permanent test, so later changes can't break it.

### T5. Confirm

- One normal ride with the new detector.
- The log's mic lines stay as they are, plus a line each time a mark-worthy shout is missed while the test mode is on.

### Build order

- **T1–T3 first** (one APK). Then your test ride: talk at a few speeds, including 80–90 km/h, a few times each.
- **Then T4–T5** with that recording.
