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
1. **Target Android version:** the app targets Android 15 (API 35). Since 31 Aug 2026, Play needs **Android 16 (API 36)** for new apps and updates. Only the API 35 platform is installed here; API 36 has to be downloaded (does this environment's network allow it?). Moving up may change behaviour: check edge-to-edge, foreground services and media.
2. **App ID:** `com.ridetrack.app` must become `com.keppo.moto`.
   - Also update the Firebase app registration (a new `google-services.json`, since the package name is part of it), App Check, and the Drive sign-in client.
   - Keppo Journal looks for this app's ID, so update it there too.
   - The "ridetrack" class names can stay.
3. **Release build isn't set up:**
   - **Signing:** no signing key for release; the rider creates the upload key.
   - **Shrinking:** `isMinifyEnabled = false`, so the release isn't shrunk; the size and the R8 rules for Media3, MapLibre, ONNX and Firebase need testing.
   - **Format:** Play needs an app bundle (`bundleRelease`).
   - **Version:** versionCode 1, versionName 0.1.0.
4. **Map tiles licence:** CARTO dark tiles need a commercial licence (reportedly about $20K a year as part of their platform). Options:
   - **OpenFreeMap:** free, commercial use allowed, OpenStreetMap data.
   - **Protomaps:** a self-hosted file on cheap storage.
   - **MapTiler or Stadia:** paid plans.
   - Any choice needs a dark style and the "© OpenStreetMap contributors" credit.
5. **Free riders cost money today:** Moments transcription and the petrol price lookup call Gemini for everyone. These need the free cap, a Gemini consent screen, and a decision on whether free riders keep automatic transcription.
6. **Restricted permissions:** the SMS and full-screen alert declarations; the crash alert modal and notification fallbacks to build.

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
