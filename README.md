# Keppo Moto

A motorcycle ride telemetry tracker for Android. It records rides with the phone's GPS
and motion sensors — speed, distance, lean angle, acceleration/braking, G-force, stops
and ride events — and presents them as a glanceable live screen and a detailed
post-ride analysis with charts, a route map and replay.

## Features (Phase 1 + 2)

- **Home** — current bike, READY TO RIDE card, sensor status, totals, recent rides, recovery of interrupted rides.
- **Pre-ride check** — contextual location/notification permission request, GPS fix quality, motion sensors, compass, calibration, battery. Recording only starts on explicit confirmation.
- **Live ride** — speed, lean (with direction text and colour), distance, duration, avg/max speed, configurable secondary metrics, optional G-force indicator, auto-pause, GPS/battery warnings, END RIDE confirmation. Works in portrait and landscape; keeps the screen on.
- **Background recording** — a location foreground service keeps GPS and sensors running with the screen off. Samples/events are written to the database every 2 s; a ride interrupted by a crash or a killed process is offered for recovery on Home.
- **Lean angle** — gyroscope/accelerometer complementary filter using a per-bike phone-mount calibration (3 s, upright and stationary). Lean is shown as *unavailable* without a gyroscope or calibration.
- **Ride summary & detail** — headline stats, ride dynamics, maneuvers, stop analysis, route map, speed/lean/G/elevation charts with a shared scrub cursor that moves the map marker, and an event list.
- **Replay** — animated route playback with play/pause and 0.5×/1×/2×.
- **History** — rides grouped Today / Yesterday / This week / Earlier, with date, bike, distance and duration filters.
- **Bikes** — multiple motorcycles, calibration per bike, sensor inventory, OBD shown as *Not connected* (planned).
- **Pop-up HUD** — a floating overlay (Minimal / Touring / Sport / Telemetry) that appears over other apps, e.g. navigation, while a ride is recorded, and hides when you return to Keppo Moto. Drag to move (position remembered), drag onto the ✕ to hide for the ride, long-press for quick controls (layout, size, opacity), double-tap for a speed bubble. Needs Android's "Display over other apps" permission, requested with an explanation. Settings: Profile → Pop-up HUD.
- **Demo mode** (Profile → Developer) — a simulated ride fed through the *real* processing pipeline so the whole UI can be tested without riding. Demo rides are labelled DEMO everywhere and excluded from statistics.

## Data honesty

Unknown values render as `--` or "Unavailable", never as 0. GPS fixes worse than 25 m
don't add distance; speeds from low-accuracy fixes never become max speed; lean and G
maxima only come from good-confidence estimates while moving. Lean and G-force are
labelled as estimates, not certified measurements.

## Architecture

```
core/telemetry   Pure Kotlin (no Android). Unit-tested.
  source/        TelemetrySource abstraction + raw readings (GPS, accel, gyro, status)
  processing/    GpsProcessor, LeanEstimator, DynamicsProcessor, AutoPauseDetector,
                 EventDetector, RideStatsAccumulator, CalibrationCollector,
                 TelemetryPipeline (raw → derived; UI at 5 Hz, persistence at 1 Hz)
  state/         RideState sealed state machine + pure reduce()
  demo/          DemoRideModel / DemoTelemetrySource (DEMO-tagged only)
app              Android app
  sensors/       PhoneTelemetrySource (SensorManager + LocationManager, no Play Services)
  ride/          RideSessionManager (lifecycle + recording loop), RideRecordingService
  data/          Room (bikes, rides, samples, events) + DataStore settings
  ui/            Jetpack Compose screens, theme, components (charts, gauges, MapLibre map)
```

The UI never talks to sensors: sources emit raw readings → `TelemetryPipeline` derives
telemetry → `RideSessionManager` exposes state flows → ViewModels → Compose. A future
Bluetooth OBD / external GPS / IMU source implements `TelemetrySource` and feeds the same
pipeline.

## Building

Requirements: JDK 17+, Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:telemetry:test testDebugUnitTest lintDebug
./gradlew -PcoreOnly=true :core:telemetry:test   # core only; no Android SDK needed
```

CI (`.github/workflows/android.yml`) builds, tests and lints every push and uploads the
debug APK as a workflow artifact.

## Maps

Route maps use MapLibre with MapTiler styles — **Dark** (`streets-v2-dark`) and
**Satellite** (`hybrid`), switchable on any map. Map data © MapTiler © OpenStreetMap
contributors (shown via the map's ⓘ attribution button).

The MapTiler key is never committed:

- Local builds: add `MAPTILER_KEY=<your key>` to `local.properties`.
- CI: add a repository secret named `MAPTILER_KEY` (Settings → Secrets and variables → Actions).
- Restrict the key in MapTiler to the Android package `com.keppo.moto` (the app id; the code namespace stays `com.ridetrack.app`).

Without a key the app falls back to a keyless dark basemap (CARTO tiles). Map backgrounds
need a connection; routes are local data and are always recorded and drawn offline.
