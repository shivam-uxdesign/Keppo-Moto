# Handover (24 Nov, evening)

Read this first in a new session. The full history of every question, bug and build is in
`studio-feature-plan.md` (one row each, #1–#101; keep logging from the next number). This file is
the short version: how we work, what's open, and what's next.

## How we work

- The rider is a UX designer: explain plainly, step by step when asked. No jargon without a gloss.
- Repo: GitHub `shivam-uxdesign/Keppo-Moto` (was Ride-Track; the code still says `com.ridetrack.app`
  inside, the app installs as `com.keppo.moto`). Work on branch `claude/dazzling-feynman-slyf5k`.
  Commit and push freely; no pull requests unless asked. No model names in commits.
- Log every question, bug, idea and build as a new row in `studio-feature-plan.md`.
- "Plan only", "don't build", "don't make changes" mean exactly that.
- Before every commit: `./gradlew :core:telemetry:test :app:testDebugUnitTest :app:lintDebug`
  (0 errors; 141 warnings is the baseline). It takes about 10 minutes: run it in the background.
- Maven Central sometimes answers 429 (Too Many Requests): stop the Gradle daemon (`./gradlew --stop`)
  and retry with `--max-workers=2`. Robolectric's Android jars download on the first test run.

## Builds

- `git fetch --unshallow` first: the version code counts commits (last sent: 431, c1e6c72).
- `./gradlew :app:assembleReleaseTest -PabiFilter=arm64-v8a`
- Verify before sending: versionCode above the last; signing cert SHA-256 starts `140f5eab`
  (test builds use the committed `app/signing/debug.keystore`; its SHA-1 is
  `D5:7C:D7:F0:05:CA:19:90:BA:87:09:76:03:E0:4E:AC:94:EB:F5:0E`); `google_app_id` in the resources (Gemini on).
- Send as `keppo-moto-<short sha>.zip`.

## Keys (environment variables)

`GOOGLE_SERVICES_JSON` (one line; build.gradle.kts writes `app/google-services.json` from it),
`APPCHECK_DEBUG_TOKEN`, `MAPTILER_KEY`, and **`GOOGLE_ROUTES_KEY`** (being added 24 Nov, for Keep moving;
test builds only, never a Play build). Check them without printing values.
- Never commit `app/google-services.json`, `local.properties` or `keystore.properties`. Never print key values.
- Never ask the rider to paste keys in chat.
- The Routes key: Routes API only, restricted to Android app `com.keppo.moto` + the SHA-1 above,
  ComputeRoutes capped at 2,000 a day. Step 1 (Google's map) will need **Maps SDK for Android** added to it.
- Gemini runs through Firebase AI Logic (Gemini Developer API) on project keppo-moto, **prepaid**:
  if every model answers "Your prepayment credits are depleted", the rider tops up at
  aistudio.google.com/billing (Blaze credits don't cover it). Topped up ₹500 on 24 Nov; confirmed working.

## Next (in this order)

1. **Keep moving step 0 APK** (#100): code is done. With `GOOGLE_ROUTES_KEY` set, build, verify, send.
   The rider then picks a destination in Profile › Keep moving: traffic log, rides 2–3 usual routes
   2–3 times at different hours, and sends the ride exports. Then write the report: Google's slow and
   jammed stretches (`traffic-log.jsonl`) against the rider's actual speed on the same roads. Plan in
   `continuous-ride.md`; Keep moving is the rider's focus.
2. **Reel from your videos on the feed** (task in progress): phone videos not from a ride still open the
   old Studio setup page (`Setup` in `StudioScreen.kt`, `PhoneStudioScreen`). Do as #96 did for rides:
   picked videos open straight in the editor with the app's plain cut (own way), then delete `Setup` and
   its helpers (Suggestions, IdeaCard, AskFor, ClipStrip, PhoneReels, StudioSettings…; keep what the
   compiler says is still used). Gemini's ideas stay in the editor's Ideas sheet.
3. **#12 Ask across all rides**: plan first, then build.
4. OBD auto-start (#61, #62, #64): last, by the rider's choice.

## Open: waiting on the rider

| # | What |
|---|---|
| 96, 97 | Build c1e6c72 (one Studio): the rider is testing it |
| 21 | 3D ride video per-clip mix: needs decode/mix/re-encode in `RideVideoRenderer`; asked whether to build it now (risk to 3D exports, can't be heard from the cloud) or later |
| 68 | Voice test ride: DJI noise cancellation off, 10 min silent, then ~20 s talk every 2 min; send the export |
| 66, 67 | Marks of "when I was really talking" for the 23 Oct ride, to compare with the voice trace |
| 86 | One more ride to confirm the pop-up no longer disappears (battery-saving check added) |
| 33, 34, 56 | Voice/engine swap, missing engine sound, voice video running too long: fixed, unconfirmed |

Closed 24 Nov: #25 (voice at high speed: "not a concern anymore", reopen if it comes back), #32 (one
Studio for rides), #87/#92/#94 (keys and Gemini working).

## Parked

- #61, #62, #64 OBD auto-start: last
- #42 auto ride start (voice start, widget, shortcut and tile are built)
- #43 add friends
- #46 WhatsApp instead of SMS for crash alerts (skipped)

## Built since the last APK (c1e6c72), not yet in a build

- #99: Reel captions on the cheaper lite Gemini models; scripts stay on gemini-3.8-flash (a lite model
  remembered from transcripts no longer takes over script writing).
- #100: Keep moving step 0, the traffic logger (needs the Routes key in the build).
- #101: a volume line on each clip's own sound, in the clip's Sound menu.
