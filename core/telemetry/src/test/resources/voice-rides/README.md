# Voice test rides

Real rides for scoring "Film when I speak" (docs/plans/voice-audit.md, Phase 0).

For each ride, add two files with the same name:
- `<name>.csv`: the ride export's `voice-trace.csv` (tester builds).
- `<name>.talk`: when the rider talked, in seconds from the trace's start, e.g. `10-15, 24-60`. Leave it empty for a ride with no talking.

`VoiceReplayTest` prints a scorecard for every ride here with today's rules; every change to the
speech rules has to beat it.
