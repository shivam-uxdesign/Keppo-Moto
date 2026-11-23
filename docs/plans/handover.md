# Handover (24 Nov)

Read this first in a new session. The full history of every question, bug and build is in
`studio-feature-plan.md` (one row each, #1–#90). This file is the short version: how we work,
what's open, and what's next.

## How we work

- The rider is a UX designer: explain plainly, step by step when asked. No jargon without a gloss.
- Repo: GitHub `shivam-uxdesign/Keppo-Moto` (was Ride-Track; the code still says `com.ridetrack.app`
  inside, the app installs as `com.keppo.moto`). Work on branch `claude/dazzling-feynman-slyf5k`.
  Commit and push freely; no pull requests unless asked. No model names in commits.
- Log every question, bug, idea and build as a new row in `studio-feature-plan.md`.
- "Plan only", "don't build", "don't make changes" mean exactly that.
- Before every commit: `./gradlew :core:telemetry:test :app:testDebugUnitTest :app:lintDebug`
  (0 errors; 141 warnings is the baseline).

## Builds

- `git fetch --unshallow` first: the version code counts commits (last sent: 420, ed9d8bd).
- `./gradlew :app:assembleReleaseTest -PabiFilter=arm64-v8a`
- Verify before sending: versionCode above the last; signing cert SHA-256 starts `140f5eab`
  (test builds use the committed `app/signing/debug.keystore`); `google_app_id` in the resources (Gemini on).
- Send as `keppo-moto-<short sha>.zip`.

## Keys (environment variables, set 24 Nov)

`GOOGLE_SERVICES_JSON` (one line; build.gradle.kts writes `app/google-services.json` from it),
`APPCHECK_DEBUG_TOKEN`, `MAPTILER_KEY`. Later: `GOOGLE_ROUTES_KEY` for Keep moving.
- Never commit `app/google-services.json`, `local.properties` or `keystore.properties`. Never print key values.
- Never ask the rider to paste keys in chat.
- 24 Nov: App Check showed the Android app as "Unregistered"; the rider registered Play Integrity
  (SHA-256 of the test key, TTL 1 hour) and added a new debug token. After the new build works,
  the rider deletes the old App Check token and the old MapTiler key (both were pasted in chat in October).
- Gemini runs through Firebase AI Logic (Gemini Developer API) on project keppo-moto; the rider
  is moving it to the Blaze plan with a spend cap.

## Open: waiting on the rider

| # | What |
|---|---|
| 87 | First build with the new keys: check Gemini answers (Studio / Storyboard) and maps load |
| 68 | Voice test ride: DJI noise cancellation off, 10 min silent, then ~20 s talk every 2 min; send the export |
| 66, 67 | Marks of "when I was really talking" for the 23 Oct ride, to compare with the voice trace |
| 86 | One more ride to confirm the pop-up no longer disappears (battery-saving check added) |
| 33, 34, 56 | Voice/engine swap, missing engine sound, voice video running too long: fixed, unconfirmed |
| 61, 62, 64 | OBD auto-start: waiting for the rider's choices (foundation built) |
| 80 | Keep moving (traffic-avoiding routes, plan in `continuous-ride.md`, prototype https://claude.ai/artifact/9KzecfPAjP3xf91RvjDNG7): waiting for `GOOGLE_ROUTES_KEY`, then step 0 (logger build) |

## Open: not finished

| # | What |
|---|---|
| 32 | Two Studio generations live side by side (Ride › Studio and Home's Make a Reel open the old page). Suggested: point everything at the new editor. Awaiting a "go" |
| 21 | Volume over time (points, fades, presets): partly built (`audio-layers-plan.md`) |
| 25 | Voice not recognised at high speed: partly built (`voice-at-speed-plan.md`); next step after the test ride |
| 12 | "Ask for something" as a chat across all rides: not started |

## Parked

- #42 auto ride start (voice start, widget, shortcut and tile are built)
- #43 add friends
- #46 WhatsApp instead of SMS for crash alerts (skipped)

## Built, not yet tried by the rider

About 30 rows marked "To test" (two-mic sound, fuel detection, editor, HUD notes, achievements…).
No need to walk through them; fix what the rider reports.

## Latest changes (since build ed9d8bd)

- Widget "not responding": route map pictures only made while the app is open (#88).
- Home order (#89, #90): alerts waiting for an answer, Before you ride, bike card, bike care,
  milestones; then last ride, today's post, shots to film, the week.
