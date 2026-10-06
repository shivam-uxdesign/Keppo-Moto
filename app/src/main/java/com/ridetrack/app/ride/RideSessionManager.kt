package com.ridetrack.app.ride

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ridetrack.app.data.MomentSettings
import com.ridetrack.app.data.VoiceSensitivity
import com.ridetrack.app.moments.VoiceChunk
import java.util.Locale
import kotlin.math.roundToInt
import com.ridetrack.app.data.MedicalInfo
import com.ridetrack.app.moments.LiveAction
import com.ridetrack.app.safety.CrashReport
import com.ridetrack.app.moments.MomentRequest
import com.ridetrack.app.moments.MomentSource
import com.ridetrack.app.moments.MomentsHub
import com.ridetrack.telemetry.model.MountOrientation
import com.ridetrack.telemetry.moments.MomentPlanner
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.moments.SpeechGate
import com.ridetrack.telemetry.moments.MomentWindow
import com.ridetrack.telemetry.moments.MomentTriggers
import com.ridetrack.telemetry.moments.PhotoScheduler
import android.os.SystemClock
import android.util.Log
import com.ridetrack.app.data.BikeRepository
import com.ridetrack.app.data.RideRepository
import com.ridetrack.app.sensors.PhoneTelemetrySource
import com.ridetrack.telemetry.demo.DemoRideModel
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.telemetry.demo.DemoTelemetrySource
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.MountCalibration
import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.model.GpsQuality
import com.ridetrack.telemetry.model.TelemetryFrame
import com.ridetrack.telemetry.model.TelemetrySample
import com.ridetrack.telemetry.processing.TelemetryPipeline
import com.ridetrack.telemetry.source.TelemetrySource
import com.ridetrack.telemetry.state.RideAction
import com.ridetrack.telemetry.state.RideError
import com.ridetrack.telemetry.state.RideState
import com.ridetrack.telemetry.state.reduce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** The ride currently being recorded. */
data class ActiveRide(
    val rideId: String,
    val bikeId: String,
    val bikeName: String,
    val source: DataSourceKind,
    val startWallMillis: Long,
    val calibrated: Boolean,
    val sensors: SensorAvailability,
    /** Moments capture was requested for this ride (enabled + camera permission). */
    val moments: MomentSettings? = null,
    /** Phone mounted sideways (landscape): orients clips and photos. */
    val landscapeMount: Boolean = false,
    /** Engine redline for the rev meter; null = unknown. */
    val redlineRpm: Int? = null,
    /** Set when recent writes failed; data is being held in memory and retried. */
    val storageProblem: Boolean = false,
)

/**
 * Owns the ride lifecycle ([RideState]) and the recording loop. Lives for the whole
 * process so a ride survives activity recreation, rotation and backgrounding.
 *
 * Threading: all pipeline access happens on [recordingDispatcher] (single thread), so the
 * high-rate reading collector and the low-rate ticker never race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideSessionManager(
    private val context: Context,
    private val rides: RideRepository,
    private val bikes: BikeRepository,
    private val momentsHub: MomentsHub,
    private val settings: SettingsRepository,
    private val phoneSource: () -> TelemetrySource,
    private val scope: CoroutineScope,
    /** Called once a ride is saved (e.g. to back it up). */
    private val onRideSaved: (rideId: String) -> Unit = {},
) {
    private val _state = MutableStateFlow<RideState>(RideState.Idle)
    val state: StateFlow<RideState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<TelemetryFrame?>(null)
    /** Low-rate (5 Hz) UI snapshot. */
    val frame: StateFlow<TelemetryFrame?> = _frame.asStateFlow()

    private val _crashes = MutableSharedFlow<CrashReport>(extraBufferCapacity = 1)
    /** Suspected crashes during the ride; the safety alert listens. */
    val crashes: SharedFlow<CrashReport> = _crashes.asSharedFlow()

    private val _active = MutableStateFlow<ActiveRide?>(null)
    val active: StateFlow<ActiveRide?> = _active.asStateFlow()

    private val autoPauseEnabled = settings.settings.map { it.autoPause }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Read live, so the threshold can be tuned during a ride against the HUD's meter. */
    private val voiceSettings = settings.settings.map { it.moments.voice to it.moments.voiceSensitivity }
        .stateIn(scope, SharingStarted.Eagerly, false to VoiceSensitivity.STRICT)

    private val recordingDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lifecycleMutex = Mutex()
    private var recordingJob: Job? = null
    private var recorder: Recorder? = null
    private val _manuallyPaused = MutableStateFlow(false)
    /** The rider paused the ride (from the HUD or the live screen), as opposed to an auto-pause at a stop. */
    val manuallyPaused: StateFlow<Boolean> = _manuallyPaused.asStateFlow()
    private var gpsJob: Job? = null
    private var phone: PhoneTelemetrySource? = null

    private fun dispatch(action: RideAction): RideState = _state.updateAndGet { reduce(it, action) }

    /**
     * Starts a ride straight from Home: the pre-ride checks happen on the live screen
     * (GPS status/warnings) instead of a separate screen. Returns the ride id.
     */
    suspend fun startNow(bike: Bike): String? {
        if (_state.value.isActive) return null
        dispatch(RideAction.BeginCheck)
        dispatch(RideAction.ChecksPassed)
        val id = start(bike)
        if (id == null && _state.value == RideState.Ready) dispatch(RideAction.CancelCheck)
        return id
    }

    /** Starts recording. Only valid from [RideState.Ready]. Returns the ride id. */
    suspend fun start(bike: Bike): String? = lifecycleMutex.withLock {
        if (_state.value != RideState.Ready) return null
        val prefs = settings.settings.first()
        val demo = prefs.demoMode
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val startWall = System.currentTimeMillis()
        val rideId = UUID.randomUUID().toString()

        val source: TelemetrySource
        val calibration: MountCalibration?
        if (demo) {
            val demoSource = DemoTelemetrySource(clockNanos = SystemClock::elapsedRealtimeNanos, simulateEngine = prefs.demoObd)
            source = demoSource
            // Demo rides start uncalibrated so the in-ride auto-calibration can be tried out.
            calibration = null
        } else {
            source = phoneSource()
            calibration = bike.calibration
        }

        try {
            rides.create(rideId, bike.id, source.kind, startWall)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create ride", e)
            dispatch(RideAction.Fail(RideError.STORAGE_FAILURE))
            return null
        }

        val m = prefs.moments.takeIf { it.enabled && hasCamera() }
        val triggers = m?.takeIf { it.anyTrigger }?.let {
            MomentTriggers(it.braking, it.acceleration, it.lean, brakeG = it.brakeG, accelG = it.accelG, leanDeg = it.leanDeg.toDouble())
        }
        // Crash detection only on real rides: a simulated one must never text anybody.
        val crashG = prefs.safety.takeIf { it.crashDetection && !demo }?.sensitivity?.impactG
        val pipeline = TelemetryPipeline(
            source.kind, calibration, source.sensors, startNanos, startWall,
            momentTriggers = triggers,
            crashImpactG = crashG,
            breakAfterMillis = prefs.breakMinutes.takeIf { it > 0 }?.let { it * 60_000L },
        )
        val rec = Recorder(
            rideId,
            pipeline,
            planner = m?.takeIf { triggers != null }?.let { MomentPlanner(beforeMillis = it.clipSeconds * 1_000L, afterMillis = it.clipSeconds * 1_000L) },
            photos = m?.photos?.takeIf { it.minutes > 0 }?.let { PhotoScheduler(it.minutes * 60_000L) },
            gpsVideo = if (m != null && !demo && prefs.safety.gpsLostVideo) GpsLostVideo() else null,
            moments = m != null,
        ).also { it.pendingEvents += pipeline.start() }
        momentsHub.reset()
        recorder = rec
        _manuallyPaused.value = false
        _active.value = ActiveRide(
            rideId = rideId,
            bikeId = bike.id,
            bikeName = bike.displayName,
            source = source.kind,
            startWallMillis = startWall,
            calibrated = calibration != null,
            sensors = source.sensors,
            redlineRpm = if (demo && prefs.demoObd) DemoRideModel.DEMO_REDLINE_RPM else bike.redlineRpm,
            moments = m,
            landscapeMount = bike.mountOrientation == MountOrientation.LANDSCAPE,
        )
        _frame.value = pipeline.frame(startNanos).first
        dispatch(RideAction.Start(rideId))

        recordingJob = scope.launch(recordingDispatcher) {
            val phoneSource = source as? PhoneTelemetrySource
            phone = phoneSource
            launch {
                try {
                    val readings = phoneSource?.motionReadings() ?: source.readings()
                    readings.collect { reading -> rec.pendingEvents += rec.pipeline.process(reading) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Telemetry source failed", e)
                }
            }
            // GPS runs as its own job so the rider can retry it without touching the sensors.
            if (phoneSource != null) gpsJob = launchGps(phoneSource, rec)
            tickLoop(rec, bike.id, persistCalibration = !demo)
        }
        // Demo rides need the service only to film Moments.
        if (source.kind == DataSourceKind.PHONE || m != null) RideRecordingService.start(context)
        rideId
    }

    private fun CoroutineScope.launchGps(source: PhoneTelemetrySource, rec: Recorder): Job = launch {
        try {
            source.locationReadings().collect { reading -> rec.pendingEvents += rec.pipeline.process(reading) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "GPS failed", e)
        }
    }

    /** Restarts the location request (e.g. after turning location back on). */
    fun retryGps() {
        val rec = recorder ?: return
        val source = phone ?: return
        scope.launch(recordingDispatcher) {
            gpsJob?.cancelAndJoin()
            if (recorder === rec) gpsJob = launchGps(source, rec)
        }
    }

    /** "Calibrate now": capture the mount over the next ~3 s while the bike is upright and still. */
    fun calibrateNow() {
        val rec = recorder ?: return
        scope.launch(recordingDispatcher) { rec.pipeline.beginCalibrationCapture() }
    }

    fun cancelCalibration() {
        val rec = recorder ?: return
        scope.launch(recordingDispatcher) { rec.pipeline.cancelCalibrationCapture() }
    }

    private suspend fun CoroutineScope.tickLoop(rec: Recorder, bikeId: String, persistCalibration: Boolean) {
        var tick = 0L
        while (isActive) {
            delay(TICK_MILLIS)
            tick++
            val now = SystemClock.elapsedRealtimeNanos()
            val (frame, events) = rec.pipeline.frame(now)
            rec.pendingEvents += events
            _frame.value = frame
            syncAutoPause(rec)
            captureMoments(rec, frame)
            rec.pipeline.takeCrash()?.let { (crash, at) ->
                Log.w(TAG, "Possible crash: ${"%.1f".format(java.util.Locale.US, crash.peakG)} g impact")
                _crashes.tryEmit(
                    CrashReport(
                        timeMillis = at.timeMillis,
                        latitude = at.latitude ?: frame.latitude,
                        longitude = at.longitude ?: frame.longitude,
                        accuracyM = frame.gpsAccuracyM,
                        speedBeforeMps = crash.speedBeforeMps ?: at.speedMps,
                        bikeName = _active.value?.bikeName.orEmpty(),
                        riderName = "",
                        batteryPercent = null,
                        medical = MedicalInfo(),
                    ),
                )
            }
            rec.pipeline.takeNewCalibration()?.let { cal ->
                _active.update { it?.copy(calibrated = true) }
                // Next ride starts with this mount; it is re-learned every ride anyway.
                if (persistCalibration) runCatching { bikes.setCalibration(bikeId, cal) }
            }
            if (tick % SAMPLE_EVERY_TICKS == 0L) rec.pendingSamples += rec.pipeline.sample(now)
            if (tick % FLUSH_EVERY_TICKS == 0L) flush(rec)
        }
    }

    /** Decides when to film; the recorder in the service does the filming. */
    private fun captureMoments(rec: Recorder, frame: TelemetryFrame) {
        val planner = rec.planner
        val chain = rec.chain
        planner?.let { p ->
            rec.pipeline.takeMomentEvents().forEach { (event, at) ->
                if (chain.filming) {
                    // Another event while the chain is filming: keep going 10 s past it.
                    chain.extend(event.timeMillis + CHAIN_AFTER_MILLIS)
                    videoControlEvents(rec, setOf(event.type), event.value)
                    return@forEach
                }
                // The GPS-lost video is filming: the event joins it, and it keeps going past it.
                if (momentsHub.live.value?.source == MomentSource.GPS_LOST) {
                    chain.start(frame.timeMillis, holdUntil = event.timeMillis + CHAIN_AFTER_MILLIS)
                    videoControlEvents(rec, setOf(event.type), event.value)
                    return@forEach
                }
                val merged = p.add(event, at)
                // A second event inside the clip being filmed: film it as one longer video,
                // which can run past what the buffer holds.
                if (merged && momentsHub.live.value == null) startChain(rec, p, frame)
            }
        }
        listenForSpeech(rec, frame)
        val wasFilming = chain.filming
        val liveSource = momentsHub.live.value?.source
        when (chain.onTick(frame.timeMillis, running = liveSource == MomentSource.EVENT || liveSource == MomentSource.GPS_LOST)) {
            TriggeredVideo.Action.STOP -> {
                planner?.markFilmed(frame.timeMillis)
                // GPS still lost: the same video carries on as the GPS-lost video.
                if (rec.gpsVideo?.filming != true) stopVideo()
            }
            TriggeredVideo.Action.FAILED -> rec.chainWindow?.let { w ->
                // The camera wasn't ready: cut what the buffer still has instead.
                planner?.restore(w.copy(endMillis = maxOf(w.endMillis, chain.holdUntil)))
            }
            // Stopped by hand from the pop-up: what it filmed is on film.
            TriggeredVideo.Action.NONE -> if (wasFilming && !chain.filming) planner?.markFilmed(frame.timeMillis)
        }
        if (!chain.filming) {
            rec.chainWindow = null
            rec.chainHasVoice = false
            rec.chainHasGps = false
        }
        val liveNow = momentsHub.live.value != null
        if (rec.liveWasRunning && !liveNow) rec.lastVideoEndMillis = frame.timeMillis
        rec.liveWasRunning = liveNow
        planner?.let { p ->
            p.due(frame.timeMillis).forEach { w ->
                // Never save again what the last video already has.
                val start = maxOf(w.startMillis, rec.lastVideoEndMillis)
                if (start < w.endMillis - MIN_CLIP_MILLIS) momentsHub.submit(MomentRequest.Clip(rec.rideId, w.copy(startMillis = start)))
            }
            momentsHub.setEventPending(p.hasPending, p.pendingStartMillis)
        }
        rec.stoppedAt = if (frame.isStopped) rec.stoppedAt ?: frame.timeMillis else null
        // A phone in a pocket, or on a break: losing GPS is expected, and nothing worth filming.
        val camUseless = frame.onBreak || frame.offMount
        rec.gpsVideo?.let { g ->
            // LOST = had a fix and it went away (not the wait for a first fix at the start).
            val longStop = rec.stoppedAt?.let { frame.timeMillis - it >= GPS_LOST_MAX_STOP_MILLIS } == true
            val lost = frame.gpsQuality == GpsQuality.LOST && !camUseless && !longStop
            val live = momentsHub.live.value
            val ours = live?.source == MomentSource.GPS_LOST
            // GPS gone while another video films: that video keeps going until it's back.
            if (lost && rec.chain.filming) {
                rec.chain.extend(frame.timeMillis + GPS_BACK_MILLIS)
                if (!rec.chainHasGps) {
                    rec.chainHasGps = true
                    videoControlEvents(rec, setOf(RideEventType.GPS_SIGNAL_LOST), null)
                }
            }
            when (g.onTick(frame.timeMillis, lost, ourVideoRunning = ours, otherVideoRunning = live != null && !ours)) {
                GpsLostVideo.Action.START -> startVideo(
                    MomentSource.GPS_LOST,
                    leadIn(rec, frame.timeMillis, g.leadInMillis(frame.timeMillis).coerceAtMost(MAX_LEAD_IN_MILLIS)),
                )
                // An event or speech joined it: the chain decides when it ends.
                GpsLostVideo.Action.STOP -> if (ours && !rec.chain.filming) stopVideo()
                GpsLostVideo.Action.NONE -> Unit
            }
        }
        rec.photos?.let { photos ->
            // A photo is announced 3 · 2 · 1 on the HUD, then taken.
            val at = rec.photoAt
            if (camUseless && at != null) {
                // Off the mount mid-countdown: a pocket photo is just black.
                rec.photoAt = null
                momentsHub.setPhotoCountdown(null)
            } else if (at == null && photos.onTick(frame.timeMillis, frame.stats.movingMillis, frame.isStopped) && !camUseless) {
                rec.photoAt = frame.timeMillis + PHOTO_COUNTDOWN_MILLIS
                momentsHub.setPhotoCountdown(rec.photoAt)
            } else if (at != null && frame.timeMillis >= at) {
                rec.photoAt = null
                momentsHub.submit(MomentRequest.Photo(rec.rideId, frame.timeMillis, frame.latitude, frame.longitude, frame.speedMps))
                momentsHub.onPhotoTaken(frame.timeMillis)
            }
        }
    }

    private fun startChain(rec: Recorder, planner: MomentPlanner, frame: TelemetryFrame) {
        val w = planner.promotePending() ?: return
        val now = frame.timeMillis
        val preRoll = leadIn(rec, now, (now - w.startMillis).coerceIn(0L, MAX_LEAD_IN_MILLIS))
        val ok = momentsHub.submitLive(
            MomentRequest.StartLive(
                rec.rideId, MomentSource.EVENT, preRoll, w.latitude, w.longitude, w.speedMps,
                types = w.types, peakValue = w.peakValue, anchorMillis = w.anchorMillis,
            ),
        )
        if (!ok) {
            planner.restore(w)
            return
        }
        rec.chainWindow = w
        rec.chainHasVoice = false
        rec.chain.start(now, holdUntil = w.endMillis)
    }

    /**
     * "Start filming when I speak": speech starts a video with a 10 s look-back (taking over
     * an event clip being filmed), and keeps it going until 5 s after the rider stops talking.
     * Speech during an event video extends it the same way. Works riding, stopped, paused or on a
     * break; off only when the phone is off the mount. Speaking = 1.5 s of sound in the voice
     * range, clearly above the background noise (see [SpeechGate]).
     */
    private fun listenForSpeech(rec: Recorder, frame: TelemetryFrame) {
        val levels = momentsHub.drainLevels()
        val (on, sensitivity) = voiceSettings.value
        if (!rec.moments || !on || frame.offMount) {
            if (rec.speech.speaking) rec.speech.reset()
            momentsHub.setSpeaking(false)
            return
        }
        val margin = sensitivity.marginDb
        val wasSpeaking = rec.speech.speaking
        levels.forEach { c -> rec.speech.onLevel(c.timeMillis, c.aboveDb, c.chunkMillis, margin, c.voice) }
        momentsHub.setSpeaking(rec.speech.speaking)
        logVoice(rec, frame, levels, wasSpeaking, margin)
        val last = rec.speech.lastSpeechMillis ?: return
        if (last <= rec.lastSpeechHandled) return
        rec.lastSpeechHandled = last
        val until = last + VOICE_AFTER_MILLIS
        val chain = rec.chain
        if (chain.filming) {
            chain.extend(until)
            if (!rec.chainHasVoice) {
                rec.chainHasVoice = true
                videoControlEvents(rec, setOf(RideEventType.VOICE), null)
            }
            return
        }
        // The GPS-lost video is filming: speech joins it and keeps it going.
        if (momentsHub.live.value?.source == MomentSource.GPS_LOST) {
            chain.start(frame.timeMillis, holdUntil = until)
            rec.chainHasVoice = true
            videoControlEvents(rec, setOf(RideEventType.VOICE), null)
            return
        }
        // Your own video already has the camera.
        if (momentsHub.live.value != null) return
        val now = frame.timeMillis
        val pending = rec.planner?.promotePending()
        // Stopped or paused, the camera rests: it wakes on speech, with nothing to look back on.
        val resting = _state.value is RideState.Paused
        val lookBack = if (resting) 0L else leadIn(rec, now, maxOf(VOICE_BEFORE_MILLIS, pending?.let { now - it.startMillis } ?: 0L))
        val ok = momentsHub.submitLive(
            MomentRequest.StartLive(
                rec.rideId, MomentSource.EVENT, lookBack.coerceAtMost(MAX_LEAD_IN_MILLIS),
                pending?.latitude ?: frame.latitude, pending?.longitude ?: frame.longitude, pending?.speedMps ?: frame.speedMps,
                types = (pending?.types ?: emptySet()) + RideEventType.VOICE,
                peakValue = pending?.peakValue,
                anchorMillis = pending?.anchorMillis ?: last,
            ),
        )
        if (!ok) {
            pending?.let { rec.planner?.restore(it) }
            return
        }
        rec.chainWindow = pending
        rec.chainHasVoice = true
        chain.start(now, holdUntil = maxOf(until, pending?.endMillis ?: 0L))
    }

    /** A video's look-back, cut so it never reaches back into the previous video. */
    private fun leadIn(rec: Recorder, now: Long, wanted: Long): Long =
        wanted.coerceAtMost((now - rec.lastVideoEndMillis).coerceAtLeast(0L))

    /**
     * The mic in the moments log: every 5 s the level, the background and the margin; and a
     * line each time speaking starts or stops, so a ride's export shows why a video began.
     */
    private fun logVoice(rec: Recorder, frame: TelemetryFrame, levels: List<VoiceChunk>, wasSpeaking: Boolean, margin: Float) {
        val speed = frame.speedMps?.let { "${(it * 3.6).roundToInt()} km/h" } ?: "no speed"
        val latest = levels.lastOrNull()
        val speaking = rec.speech.speaking
        if (speaking && !wasSpeaking && latest != null) {
            momentsHub.log(
                "voice start: level ${db(latest.levelDb)} · background ${db(latest.backgroundDb)} (+${db(latest.aboveDb, false)}) · " +
                    "${"%.1f".format(Locale.US, rec.speech.sustainedMillis / 1000.0)} s sustained · voice ${"%.2f".format(Locale.US, latest.voice)} · " +
                    "margin +${margin.roundToInt()} dB · $speed",
            )
        } else if (!speaking && wasSpeaking) {
            momentsHub.log("voice stop: quiet · $speed")
        }
        if (latest != null && frame.timeMillis - rec.lastMicLog >= MIC_LOG_MILLIS) {
            rec.lastMicLog = frame.timeMillis
            val peak = levels.maxOf { it.aboveDb }
            momentsHub.log(
                "mic: level ${db(latest.levelDb)} · background ${db(latest.backgroundDb)} (+${db(latest.aboveDb, false)}, peak +${db(peak, false)}) · " +
                    "voice ${"%.2f".format(Locale.US, levels.maxOf { it.voice })} · margin +${margin.roundToInt()} dB · ${if (speaking) "speaking" else "quiet"} · $speed",
            )
        }
    }

    private fun db(v: Float, unit: Boolean = true) = "${v.roundToInt()}${if (unit) " dB" else ""}"

    private fun videoControlEvents(rec: Recorder, types: Set<com.ridetrack.telemetry.model.RideEventType>, peak: Double?) {
        momentsHub.submitLive(MomentRequest.LiveEvents(rec.rideId, types, peak))
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun syncAutoPause(rec: Recorder) {
        // A break pauses even with auto pause off: the rider is off the bike.
        val shouldPause = _manuallyPaused.value || rec.pipeline.onBreak || (autoPauseEnabled.value && rec.pipeline.isPausedStop)
        val s = _state.value
        if (shouldPause && s is RideState.Recording) dispatch(RideAction.AutoPause)
        if (!shouldPause && (s is RideState.Paused || (s is RideState.EndingRide && s.wasPaused))) {
            dispatch(RideAction.AutoResume)
        }
    }

    /** Writes buffered samples/events and the stats snapshot. Keeps data in memory on failure. */
    private suspend fun flush(rec: Recorder): Boolean {
        val samples = rec.pendingSamples.toList()
        val events = rec.pendingEvents.toList()
        return try {
            rides.append(rec.rideId, samples, events)
            rec.pendingSamples.subList(0, samples.size).clear()
            rec.pendingEvents.subList(0, events.size).clear()
            rides.snapshot(rec.rideId, rec.pipeline.stats, System.currentTimeMillis())
            if (_active.value?.storageProblem == true) _active.update { it?.copy(storageProblem = false) }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Flush failed; will retry", e)
            _active.update { it?.copy(storageProblem = true) }
            false
        }
    }

    /** Pauses the ride: distance and moving time stop counting until [resume]. */
    fun pause() {
        val rec = recorder ?: return
        if (!_state.value.isActive) return
        _manuallyPaused.value = true
        setManualPause(rec, true)
    }

    /** Resumes a manual pause, or ends a break early (the notification's Resume). */
    fun resume() {
        val rec = recorder ?: return
        _manuallyPaused.value = false
        setManualPause(rec, false)
    }

    // The pipeline is fed on the recording thread only.
    private fun setManualPause(rec: Recorder, paused: Boolean) {
        scope.launch(recordingDispatcher) {
            val now = SystemClock.elapsedRealtimeNanos()
            rec.pipeline.setManualPause(paused, now)?.let { rec.pendingEvents += it }
            if (!paused) rec.pipeline.endBreak(now)?.let { rec.pendingEvents += it }
            syncAutoPause(rec)
        }
    }

    /** Films a video now (the HUD's record button); it's saved as a moment when stopped. */
    fun startVideo(source: MomentSource = MomentSource.MANUAL, preRollMillis: Long = 0L): Boolean {
        val id = _active.value?.rideId ?: return false
        val f = _frame.value
        return momentsHub.submitLive(MomentRequest.StartLive(id, source, preRollMillis, f?.latitude, f?.longitude, f?.speedMps))
    }

    fun pauseVideo() = videoControl(LiveAction.PAUSE)

    fun resumeVideo() = videoControl(LiveAction.RESUME)

    fun stopVideo() = videoControl(LiveAction.STOP)

    private fun videoControl(action: LiveAction) {
        val id = _active.value?.rideId ?: return
        momentsHub.submitLive(MomentRequest.LiveControl(id, action))
    }

    fun requestEnd() {
        dispatch(RideAction.RequestEnd)
    }

    fun cancelEnd() {
        dispatch(RideAction.CancelEnd)
    }

    /** Stops recording and saves. Safe to call repeatedly; only the first call saves. */
    fun confirmEnd() {
        if (_state.value !is RideState.EndingRide) return
        dispatch(RideAction.ConfirmEnd)
        scope.launch {
            lifecycleMutex.withLock { finish() }
        }
    }

    private suspend fun finish() {
        val rec = recorder ?: return
        val saving = _state.value as? RideState.Saving ?: return
        withContext(NonCancellable) {
            recordingJob?.cancelAndJoin()
            recordingJob = null
            gpsJob?.cancelAndJoin() // a retried GPS job isn't a child of the recording job
            gpsJob = null
            phone = null
            val ok = withContext(recordingDispatcher) {
                val now = SystemClock.elapsedRealtimeNanos()
                rec.pendingEvents += rec.pipeline.end(now)
                rec.pendingSamples += rec.pipeline.sample(now)
                rec.planner?.let { planner ->
                    val end = rec.pipeline.wallMillis(now)
                    rec.pipeline.takeMomentEvents().forEach { (event, at) -> planner.add(event, at) }
                    planner.flush(end).forEach { momentsHub.submit(MomentRequest.Clip(rec.rideId, it)) }
                    momentsHub.setEventPending(false)
                }
                var saved = false
                for (attempt in 1..3) {
                    if (flush(rec)) {
                        saved = true
                        break
                    }
                    delay(300L * attempt)
                }
                if (saved) {
                    val active = _active.value
                    val end = rec.pipeline.wallMillis(now)
                    val name = RideNames.forStart(active?.startWallMillis ?: end)
                    runCatching { rides.complete(rec.rideId, end, name) }.isSuccess
                } else {
                    false
                }
            }
            RideRecordingService.stop(context)
            recorder = null
            _manuallyPaused.value = false
            if (ok) {
                _active.value = null
                _frame.value = null
                dispatch(RideAction.Saved)
                onRideSaved(rec.rideId)
            } else {
                // The ride stays IN_PROGRESS in the database and is offered for recovery.
                _active.value = null
                _frame.value = null
                dispatch(RideAction.Fail(RideError.STORAGE_FAILURE))
            }
            if (saving.rideId != rec.rideId) Log.w(TAG, "State/recorder mismatch")
        }
    }

    /** Returns to idle after the summary or an error has been shown. */
    fun acknowledge() {
        dispatch(RideAction.Acknowledge)
    }

    private class Recorder(
        val rideId: String,
        val pipeline: TelemetryPipeline,
        val planner: MomentPlanner? = null,
        val photos: PhotoScheduler? = null,
        val gpsVideo: GpsLostVideo? = null,
        /** Moments are on (the camera and mic run): "film when I speak" can work. */
        val moments: Boolean = false,
    ) {
        val pendingSamples = ArrayList<TelemetrySample>()
        val pendingEvents = ArrayList<RideEvent>()
        var photoAt: Long? = null
        /** When the current stop (or break) began, by the ride's clock. */
        var stoppedAt: Long? = null
        /** Events chained into one longer video. */
        val chain = TriggeredVideo()
        /** The clip that became [chain]'s video, kept in case filming never starts. */
        var chainWindow: MomentWindow? = null
        var chainHasVoice = false
        /** "Start filming when I speak". */
        val speech = SpeechGate()
        var lastSpeechHandled = Long.MIN_VALUE
        var lastMicLog = 0L
        var chainHasGps = false
        var liveWasRunning = false
        /** When the last video ended (ride clock); new videos and clips start after it. */
        var lastVideoEndMillis = 0L
    }

    companion object {
        private const val TAG = "RideSession"
        private const val TICK_MILLIS = 200L
        private const val SAMPLE_EVERY_TICKS = 5L // 1 Hz
        private const val FLUSH_EVERY_TICKS = 10L // every 2 s
        /** The clip buffer holds ~45 s; a GPS-lost video starts at most this far back. */
        private const val MAX_LEAD_IN_MILLIS = 30_000L
        private const val PHOTO_COUNTDOWN_MILLIS = 3_000L
        /** A GPS-lost video only starts while riding or in a short stop. */
        private const val GPS_LOST_MAX_STOP_MILLIS = 30_000L
        /** A chained video keeps filming this long after its last event. */
        private const val CHAIN_AFTER_MILLIS = 10_000L
        /** Speech: film from 10 s before (plus the moment it takes to hear it), until 5 s of quiet. */
        private const val VOICE_BEFORE_MILLIS = 11_000L
        private const val VOICE_AFTER_MILLIS = 5_000L
        private const val MIC_LOG_MILLIS = 5_000L
        /** GPS back: a video kept going for it stops this long after. */
        private const val GPS_BACK_MILLIS = 5_000L
        /** Shorter than this after trimming the overlap: not worth a clip. */
        private const val MIN_CLIP_MILLIS = 2_000L
    }
}
