# Keppo Moto: release audit

Phase 1: inventory and launch-blocker scan (13 Oct 2026). Nothing was changed in this phase. The plan and decisions are in `studio-feature-plan.md`, under "Production readiness".

## Size of the app
- **Code:** 61,000 lines of Kotlin in 446 files.
- **Areas:** ride (recording, sensors), moments (camera, mics, voice detection), hud (pop-up), safety (crash alert), fuel, journal (Keppo Journal link), backup (Google Drive), transcribe, studio (Reels), share, trash, diag, ui.
- **Tests:** 67 test files, 359 tests; all pass. No on-device (instrumented) tests.
- **Database:** Room, 5 tables, schemas 7 to 9 exported.
- **Lint:** 0 errors, 57 warnings.
- **Screens (navigation routes):**
  - **Main:** Home, Rides, Studio, Bike, Profile.
  - **Riding:** Live ride, Summary, Ride detail, Rides map.
  - **Bike:** Bike edit, Calibrate, HUD settings.
  - **Profile:** Profile pages, Recently deleted.
  - **Sharing:** Share.
  - **Studio:** Ride Studio and editor, Reels, Studio style, Styles, Studio settings, Review, Series and New series, Studio search, Phone videos.
  - **Moments:** Moment viewer, Moment share.
  - **Outside the navigation:** Crash alert, Share rides (Journal), Delete ride (Journal).

## Permissions (all declared in the manifest)
| Permission | Why | Play status |
|---|---|---|
| Fine and coarse location | Ride recording | Foreground only (no background location). Foreground service declaration and video needed. |
| Camera, Record audio, Modify audio settings, Bluetooth connect | Moments, DJI mic | Foreground service declarations (camera, microphone) and video. |
| Foreground service types: location, camera, microphone, dataSync, mediaProcessing | Recording service; Studio making Reels | One declaration each, with a video. dataSync and mediaProcessing have 6-hour limits on Android 15+. |
| Post notifications, Wake lock, Vibrate | Ride and alert notifications | Fine. |
| System alert window | Pop-up HUD | Fine; explained in the app. Also lets the crash alert open as a modal (agreed fallback). |
| **SEND_SMS** | Crash texts to contacts | **Restricted.** Permissions Declaration Form: "Physical safety/emergency alerts". |
| **READ_SMS** | Fuel log from card SMS | **Restricted.** Declaration: "SMS-based money management"; payment SMS only, read on the phone. |
| Read phone state | Picking a SIM on dual-SIM phones | Fine; data safety: device info. |
| Call phone | The alert's Call button | Not restricted. |
| **USE_FULL_SCREEN_INTENT** | Crash alert over the lock screen | Only automatic for alarm and calling apps; declare it. The app already checks `canUseFullScreenIntent` (Safety settings); the agreed modal and notification fallbacks are still to build. |
| Internet, Network state | Maps, Gemini, Drive, Overpass | Fine. |

No photo or video library permissions (the system picker is used). Good.

## Parts open to other apps (exported)
- **MainActivity:** launcher, plus `com.keppo.action.OPEN_RIDE` with a `rideId` extra. Check it handles bad IDs safely.
- **RideDocumentsProvider:** protected by `MANAGE_DOCUMENTS` (only the system file picker can use it). Good.
- **ShareRidesActivity, DeleteRideActivity:** any app can open them; both ask the rider first. Audit that nothing happens without the rider's confirmation.
- **CrashAlertActivity, services, receiver, FileProvider:** not exported. Good.
- `allowBackup="false"`: rides aren't in Android's cloud backup; the app has its own Drive backup.

## Where data goes
| Data | Goes to | Notes |
|---|---|---|
| Rides, sensors, location | On the phone (Room, files) | Exported to Keppo Journal only when the rider allows it. |
| Moment clips, voice audio | On the phone; audio to **Gemini** for captions | **Moments transcription is a free feature and runs automatically** (it can be set to Wi-Fi only). It uses Gemini, so it **costs money for free riders** and needs the free cap and consent. |
| Studio: audio, frames, ride text | **Gemini** (captions, scripts, frames seen, styles, hooks, translation, coaching tips) | 15 kinds of Gemini call (`StudioGemini`). Paid, apart from the free weekly Reel. |
| Fuel: where you filled up | **Overpass API** (OpenStreetMap) to find the petrol station; **Gemini with Google Search** to look up the day's petrol price | A free feature that uses Gemini, so it's capped. Google Search grounding has display rules (showing Google's search suggestions); check them. Overpass's public server is for light use only. |
| Backup | **Google Drive**, the rider's own (`drive.file` scope) | Needs the Google sign-in consent screen set up for production (a non-sensitive scope, so brand verification only). |
| Map tiles | **CARTO** dark tiles; **MapTiler** if a key is set | **Blocker: CARTO's basemaps need a paid Enterprise licence for commercial use.** |
| Emergency contacts, SMS | The rider's own SIM | Not uploaded. |
| Errors | Local error log (`diag/ErrorLog`), sent by the rider by hand | Crashlytics to add. Check logs for personal data (34 `Log.*` calls). |
| App Check | Firebase: Play Integrity in release, a debug token in debug | Good. The debug token comes from `local.properties` and only goes into debug builds; confirm. |

## Launch blockers found
1. **Target Android version (fixed 14 Oct):** now compiles and targets Android 16 (API 36); the SDK and build tools 36 are installed here. AGP 8.7.3 builds against it with `android.suppressUnsupportedCompileSdk=36`; move to a newer AGP in the release step. Checked: no orientation lock, no edge-to-edge opt-out, no `onBackPressed` overrides (predictive back is fine). Still to see on a phone: insets on every screen, and tablets and foldables in landscape.
2. **App ID: not a blocker (corrected 14 Oct).** The app is already built as `com.keppo.moto` (`applicationId`), and the Firebase file is registered for it. `com.ridetrack.app` is only the code's internal namespace, which riders and Play never see.
   - **Still to do at release:** the Drive backup sign-in needs an OAuth client for `com.keppo.moto` with the release key's SHA-1, and Play App Signing's SHA-1 added in Firebase and Google Cloud (the Firebase file lists no OAuth clients today).
3. **Release build (set up 14 Oct):**
   - **Release:** R8 shrinks, optimises and renames (`isMinifyEnabled`, `isShrinkResources`). Signed with the upload key from `keystore.properties` or `KEPPO_UPLOAD_*` env vars; unsigned without them. `bundleRelease` makes the Play bundle.
   - **releaseTest:** the same build, signed with the shared debug key and carrying App Check's debug token, so the real release can be tested by hand with Gemini working. It's the same key as the debug APKs, so it installs over them and keeps data.
   - **Security fix:** the App Check debug token was built into release too, where anyone could extract it and use the Gemini allowance. Now only debug and releaseTest carry it (checked: absent from the release bundle). The switch is `BuildConfig.APPCHECK_DEBUG`.
   - **versionCode** = the git commit count, so every upload is newer.
   - **Size:** releaseTest for arm64 is 24.9 MB (debug was 42 MB).
   - **Still to do:** the rider makes the upload key; Play App Signing; a newer AGP; the R8 build needs testing on a phone.
4. **Map tiles licence** (fixed 14 Oct: OpenFreeMap's Dark style in `assets/map/dark.json`, the credit line updated, the licence in `assets/licenses`): CARTO dark tiles need a commercial licence (reportedly about $20K a year as part of their platform). Options:
   - **OpenFreeMap:** free, commercial use allowed, OpenStreetMap data.
   - **Protomaps:** a self-hosted file on cheap storage.
   - **MapTiler or Stadia:** paid plans.
   - Any choice needs a dark style and the "© OpenStreetMap contributors" credit.
5. **Free riders cost money today (fixed 14 Oct):**
   - **Pro switch:** `pro/Entitlements` holds Pro. Until billing everyone is Pro (`BETA_ALL_ACCESS`); test builds have "Act as a free rider" in Profile.
   - **Free riders get no Gemini:**
     - Moments captions are off (the setting shows "· Pro"); the worker and `transcribeSoon` check Pro.
     - The petrol price lookup returns nothing, so the price is asked.
     - Studio's automatic ideas and top 3 don't run (`AfterRideWorker`), and their settings say Pro.
   - **Typed captions:** anyone can type or fix what they said in the moment viewer ("Write what you said").
   - **Still to come:** Studio's own Gemini use for free riders, limited to the weekly Reel, arrives with the paywall.
6. **Restricted permissions:** the SMS and full-screen-alert declarations are for the Play Console.
   - **Crash alert fallbacks (built 14 Oct):**
     - Full screen where allowed.
     - Otherwise, with "Display over other apps" on, the alert screen as a window over any app (`CrashAlertOverlay`, the same screen), which also lets the app open the alert screen on Android 15+.
     - Always a notification with a live countdown and I'm OK / Send now buttons (`CrashAlertActionReceiver`), visible on the lock screen, alerting again every 10 s while it can't take over the screen.
   - **Safety settings:** suggest allowing "Display over other apps" when full screen isn't allowed.
   - **Debug builds:** "Simulate crash in 10 s" to try the alert in the background.

## Built in step 9
- **Crash reports (9a, 14 Oct):**
  - **What:** Firebase Crashlytics, plus the error log's caught errors. It never includes rides, places, videos or messages.
  - **Consent:** off in the manifest until `diag/CrashReports` decides. Riders in the EU, EEA, UK, Switzerland, or an unknown country are asked once on Home; elsewhere it's on. There's a switch in About & privacy.
  - **Release builds:** the Crashlytics plugin uploads R8's mapping.

- **Gemini consent and AI reports (9b, 14 Oct):**
  - **Consent:** `pro/AiConsent`. Studio home and Studio pages show "Studio uses Google's Gemini" (what's sent, Agree / Not now) until the rider agrees.
  - **Background jobs:** the automatic ideas, the Moments captions worker and the petrol price lookup also need it. Turning on automatic captions counts as agreeing (that switch explains it).
  - **Reporting:** `pro/AiReports`, through "Report this idea" in an idea's options and "Report a Gemini result" in Studio's menu (offensive or harmful, wrong or made up, something else, plus a note). Reports are kept on the phone and sent by the server (step 8).

- **Legal (9c, 14 Oct):** About & privacy says what goes to Gemini and Crashlytics, and gains a Legal section (`LegalSection`):
  - the privacy policy and terms links (`LegalLinks`, filled in with the store pack; hidden while empty)
  - open-source licences (the asset licence files and a library notice)
  - "Delete all my data": two steps (type DELETE), then `clearApplicationUserData()`; it says that Drive and Journal copies and the subscription are handled elsewhere

## Already fine
- **16 KB pages:** all native libraries (MapLibre, ONNX Runtime, AndroidX) are 16 KB aligned. The APK passes `zipalign -P 16`.
- **Edge-to-edge:** already on (`enableEdgeToEdge`).
- **Background location:** none needed.
- **Photo and video permissions:** none (the system picker is used).
- **Bundled media:** no music; fonts carry their OFL licences in `assets/licenses`.
- **Backups:** Android's own backup is off.
- **App Check:** Play Integrity in release.

## Notes for later phases
- **Size:**
  - **Today:** the debug APK for arm64 is 42 MB (about 28 MB zipped).
  - **ONNX Runtime alone is 33 MB,** for Silero voice detection. Check whether a smaller build of it, or Android's own voice detection, would do.
  - **App bundle:** Play's bundle will serve each phone only its own chip's files.
- **Translation:** only 5 strings in `strings.xml`; about 530 pieces of on-screen text are written straight into the code. Fine for an English launch, but they should move into string resources before any translation.
- **Accessibility:**
  - 62 images have no description (`contentDescription = null`; many are decorative, but check each).
  - 46 have a description.
  - TalkBack, touch targets and font-scaling checks are still to do.
- **Large files to audit carefully:** `TimelineEditor.kt` (about 1,900 lines), `StudioViewModel.kt` (about 2,060), `StudioArt.kt` (about 1,780).
- **Unused code:** `StudioRenderer.previewComposition` (the old exact preview); the Glitch transition (kept for old edits).
- **Overpass:** with a global launch, consider caching the station lookups, or another source.
- **Gemini consent and AI-output reporting:** none yet.
- **Data deletion:** "Recently deleted" exists for rides; full "delete all my data" (rides, moments, Reels, the server's usage record) is still needed.

## Sources
- Google Play target API: <https://developer.android.com/google/play/requirements/target-sdk>
- CARTO basemaps FAQ: <https://docs.carto.com/faqs/carto-basemaps>
- SMS and Call Log permissions: <https://support.google.com/googleplay/android-developer/answer/10208820>

## Code audit 1: ride recording and safety (14 Oct)
**Solid:**
- **RideSessionManager:** one recording thread; flush every 2 s; 3 tries at the end; unsaved rides kept for recovery.
- **RideRecordingService:** Android 12–16 foreground rules handled (fails safe, START_STICKY carries the ride on). Sensors and GPS register and unregister cleanly.
- **CrashDetector:** 10 tests; demo rides never text.
- **SmsSender:** real per-text results, dual-SIM handling, ASCII text.
- **Warnings shown:** storage problem; no contacts.

**Findings:**
1. **High, fixed 14 Oct.** Failed crash texts (no signal) are never retried. Fix: retry every 30 s for up to 15 min, show it on the alert screen, and the same for location updates.
2. **Medium, fixed 14 Oct.** With no GPS fix yet, the text says "Location unknown". Fix: the last known location from any provider, with its age.
3. **Medium, fixed 14 Oct.** Phone numbers in the system log on SMS failure (`SmsSender`). Fix: drop them.
4. **Dropped by the rider.** The phone flying off the mount while riding looks like a crash; contacts are texted while the rider rides on. Suggestion: free-fall before the impact gives a 60 s countdown and "the phone may have fallen off the bike" in the text.
5. **Low, accepted.** A pothole, then a still stop with the engine off within 15 s, may alarm; the countdown covers it.
6. **Low, accepted.** Unsaved data stays in memory while storage is full (small).


## Code audit 2: Studio (14 Oct, part 1)

### Solid
- Every media reader, encoder, muxer and player is released, including on errors.
- No `GlobalScope`; `runBlocking` only in the documents provider (normal there).
- StudioService handles Android 15's foreground-service time limit (`onTimeout`).
- Reels keep at most 5 versions; Recently deleted is emptied after 30 days.
- Undo history is capped at 100 steps.
- Temporary files (caption audio, cover frames, export copies, reverse work folders) are deleted after use.

### Found and fixed
- **Storage leak (medium):** still frames (`studio-stills`), reversed clips (`studio-reversed`) and raw voice-over recordings (`studio/voice-*.pcm`) were never deleted, even after the edit using them was gone.
  - Fix: `StudioSweeper`, once a day at app start (after the Reel purge).
  - It deletes such a file when no edit's JSON names it and it's over 3 days old.

## Code audit 2: Studio (14 Oct, part 2)

### Solid
- Studio itself is gated: it's locked for free riders after the weekly Reel and asks for Gemini consent the first time.
- Background Gemini work (after-ride ideas, transcripts, petrol prices) checks Pro and consent.
- StudioService uses mediaProcessing on Android 15+ and dataSync before that, and stops on Android's time limit.
- FileProvider is not exported; it shares only `shares/`, `moments/`, `reels/` and `exports/`, one file at a time with read permission.
- Every JSON read (Reels, drafts, queue, Gemini answers) is wrapped, so a damaged file is skipped instead of crashing.
- Forced unwraps (`!!`) were checked one by one: each has a check right before it.
  - Send to Journal relied on its callers, so it now skips Reels without a ride itself.

### Found and fixed
- **Gemini for free riders (high, against the paywall decision):** four side screens called Gemini with no Pro or consent check. A free rider could reach them through "Your Reels".
  - The screens: Styles › Describe it in words / Like a video I love, Search › Look now, a series' "Make next episode", and finding series episodes in a ride.
  - Fix: one shared check, `AppContainer.proAi()` (Gemini set up, Pro, consent given).
  - It's used in the low-level functions (`ClipSeen.look`, `SeriesWork.find` and `makeNext`), in the UI (the rows are hidden), and by the petrol price and transcript gates.
- **Saves could be cut short (medium):** Reels, versions, drafts, ride plans, series, styles, saved clips and the making queue were written in place. If the app was killed mid-save, the file was left half-written.
  - Series and styles keep everything in one file, so all of them would be lost.
  - Fix: `writeAtomically` writes to a temporary file, syncs it, then renames it over the old one. The file is always either the old or the new version.

### Noted for the store pack
- The FGS declaration needs both types explained: "dataSync" on Android 14 and older and "mediaProcessing" on 15+, both for rendering a Reel the rider asked for. Record a short video of it.

## Code audit 3: the rest of the app (14 Oct)

### Solid
- **Delete a ride (Journal):** you confirm in Keppo Moto's own dialog, and the ride goes to Recently deleted for 30 days.
- **Ride documents provider:** read-only (any write is refused). It's gone the moment sharing is off, and it serves only files that exist in a ride's folder.
- **Messages to Journal** are addressed to Journal only.
- **SMS reading:** it reads only the text and time of SMS from 15 min before to 30 min after a petrol stop. They're parsed on the phone into an amount; message text is never stored or sent.
- **Logs:** no phone numbers, places or speech in logcat. Crashlytics gets the error and its stack only (with consent); the detail notes stay on the phone.
- **Drive backup:** uses the `drive.file` permission (only files Keppo makes, no Google verification needed), and the token isn't logged. Android's own auto-backup is off.

### Found and fixed
- **Fake Journal could connect (high):** "Share rides" and "Delete ride" trusted the caller's package name alone. When the real Keppo Journal isn't installed, any app installed outside Play can take that name. One tap on Allow would then give it every ride, video and location.
  - Fix: `JournalSource.isGenuine` also requires that Journal was installed from the Play Store.
  - Debug and tester builds skip this check, so a hand-installed Journal still connects while testing.
- **A crafted "open ride" request could crash the app (low):** IDs from other apps and notifications went straight into page addresses, and a "/" or "?" made navigation throw.
  - Fix: only plain IDs are accepted (`OPEN_RIDE_SHAPE`).
- **Restore trusted file names from the backup (medium):** a tampered backup with "../" in a name could write outside its folder.
  - Fix: `BackupNames.safe` checks the bike photo names, ride IDs and moment names before downloading.

### For the privacy policy and Data safety form
Where data leaves the phone:
- **Gemini (Firebase AI):** clips' sound, frames, captions and ride titles (Pro, with consent).
- **Crashlytics:** errors (with consent).
- **Google Play Billing.**
- **Google Drive:** backup, which the rider turns on.
- **Android's geocoder (Google):** place names for rides and the petrol price city.
- **OpenStreetMap Overpass:** a petrol stop's position, to find the pump's name. This is a third party, so it counts as shared location.
- **OpenFreeMap:** map tiles (IP address only).
- **SMS:** alert texts go to the rider's emergency contacts.

## UX audit (14 Oct)

### Solid
- **Permissions are asked in context, never upfront:** location and notifications when you first slide to ride, camera and mic when Moments is turned on, SMS in Safety. A denial explains itself and offers Settings.
- **Ending a ride:** hold to end. Screen readers get a normal "End ride?" dialog instead.
- **Accessibility:**
  - Every icon-only button has a screen-reader label.
  - Text contrast passes WCAG AA in both themes (lowest 4.9:1).
  - Small tappable things (text links, the 18 dp close on the last-ride card) are fine: Compose stretches their touch area to 48 dp and reports that to screen readers. Play's pre-launch report will confirm.
- **Empty states:** no bike (Add your bike, or restore a backup), no rides, ride not found.

### Found and fixed
- **Crash alerts invisible before riding (high):** they're off by default, and Home's readiness check didn't mention them. A rider could ride for weeks thinking they were protected.
  - **Off:** a quiet "Crash alerts off · Set up" line under "Ready to ride", which opens Profile › Safety.
  - **On with no contacts, or SMS not allowed:** a problem card with "Add contacts" or "Allow SMS".
  - **On and working:** a "Crash alerts · 2 contacts" chip.
- **Paywall promised "in Hinglish" to every rider:** now "written out (English or Hinglish)", which is what Gemini does today.

### Decisions needed (global launch)
1. **Units:** speed and distance are km/h and km only, in about 50 places (HUD, ride pages, share cards, Studio overlays). The UK and US use mph and miles.
2. **Fuel outside India:** prices are in ₹, card SMS parsing looks for Rs/INR, and the petrol price lookup searches "…in <city>, India".
3. **Captions language:** the Gemini prompt assumes English and Hinglish. Riders elsewhere speak other languages.
4. **No welcome screens:** the app opens straight onto Home. That's fine given the in-context asks, as long as the crash alerts line above is there.

## UI audit, part 1: rendered parts (14 Oct)
`UiAuditShots` draws contact sheets (build/shots/ui-*.png). Each part is shown dark, light, on a small phone (320 dp, text 130%) and with the largest text (200%). Covered: Home's cards, the crash alert (countdown and sent), the paywall, and every HUD layout and theme, plus the bubble.

### Solid
- Both themes read well.
- The crash alert stays clear and usable at 200% text (it's always dark, by design). Its sent screen scrolls above the "I'm OK" button.
- The paywall wraps cleanly at every size.
- The new readiness checks and the two-mic meter fit in.

### Found and fixed
- **HUD at large text:** labels and values were cut ("VOIC", "DISTA", "1:02:") because the text grew inside a fixed-width card. Now the phone's text size scales the whole card, up to 30% (`ScaledBy`); the HUD's own Size setting still applies on top.
- **HUD Touring:** "MAX 112 km/h" didn't fit half a card. The units moved into the labels ("MAX KM/H"), so the value is just the number.
- **Home fuel card:** "Add fill-up" / "Not a fill-up" were cut to "Add" / "Not a" on small phones and with large text. The buttons now stack when the row is too narrow.
- **Home Bike care header:** the bike's name pushed the label off ("BIKE CAREDuke…"). The label keeps its room; the name shortens instead.
- **Paywall:** the radio button touched the price at 200%, so it now has a gap. "Petrol price looked up" became "Fuel price looked up".

### Part 2
The screens that need the real app (map, data) are checked from the rider's phone screenshots.
