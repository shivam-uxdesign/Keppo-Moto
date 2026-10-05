package com.ridetrack.telemetry.processing

import com.ridetrack.telemetry.model.CalibrationInfo
import com.ridetrack.telemetry.moments.MomentTrigger
import com.ridetrack.telemetry.moments.MomentTriggers
import com.ridetrack.telemetry.model.CalibrationStatus
import com.ridetrack.telemetry.model.CaptureOutcome
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.GpsQuality
import com.ridetrack.telemetry.model.LeanConfidence
import com.ridetrack.telemetry.model.MountCalibration
import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.model.TelemetryFrame
import com.ridetrack.telemetry.model.TelemetrySample
import com.ridetrack.telemetry.source.AccelReading
import com.ridetrack.telemetry.source.EngineReading
import com.ridetrack.telemetry.source.GyroReading
import com.ridetrack.telemetry.source.LocationReading
import com.ridetrack.telemetry.source.RawReading
import com.ridetrack.telemetry.source.SourceSignal
import com.ridetrack.telemetry.source.SourceStatusReading
import kotlin.math.sqrt
import com.ridetrack.telemetry.math.Units

/**
 * Raw readings in, derived telemetry out. Not thread-safe: feed it from one coroutine.
 *
 * High-rate readings go to [process]; the UI polls [frame] at a low rate and persistence
 * polls [sample] at ~1 Hz, so the UI never sees sensor-rate updates.
 */
class TelemetryPipeline(
    private val sourceKind: DataSourceKind,
    calibration: MountCalibration?,
    sensors: SensorAvailability,
    private val startNanos: Long,
    private val startWallMillis: Long,
    thresholds: EventThresholds = EventThresholds(),
    /** Enables moment detection (clips); null = off. */
    momentTriggers: MomentTriggers? = null,
    /** Impact (g) that starts crash detection; null = off. */
    crashImpactG: Double? = null,
    /** A stop this long becomes a break; 0 = only when the phone leaves the mount or the engine stops; null = no breaks. */
    breakAfterMillis: Long? = 5 * 60_000L,
) {
    private val breaks = breakAfterMillis?.let { BreakDetector(afterMillis = it) }
    /** When the current counted stop began; null while moving or before the bike first moved. */
    private var stoppedSinceMillis: Long? = null

    private val crash = crashImpactG?.let { CrashDetector(impactG = it) }
    private var pendingCrash: Pair<CrashSuspected, EventContext>? = null

    /** A crash detected since the last call, with where and when it happened. */
    fun takeCrash(): Pair<CrashSuspected, EventContext>? = pendingCrash.also { pendingCrash = null }

    private val moments = momentTriggers?.let { MomentTrigger(it) }
    private val momentEvents = ArrayList<Pair<RideEvent, Long>>()

    /** Moment events detected since the last call, each with the wall time it was reported. */
    fun takeMomentEvents(): List<Pair<RideEvent, Long>> = momentEvents.toList().also { momentEvents.clear() }

    private val gps = GpsProcessor()
    private val lean = LeanEstimator(calibration, sensors)
    private val dynamics = DynamicsProcessor(calibration, sensors)
    private val autoPause = AutoPauseDetector()
    private val detector = EventDetector(thresholds)
    private val accumulator = RideStatsAccumulator()
    private val autoCal = AutoCalibrator()

    private var calibrationStatus = if (calibration != null) CalibrationStatus.SAVED else CalibrationStatus.NONE
    private var capture: CalibrationCollector? = null
    private var lastCapture: CaptureOutcome? = null
    private var pendingCalibration: MountCalibration? = null

    private var rpm: Double? = null
    private var gear: Int? = null
    private var lastEngineNanos: Long? = null

    private var lastAccountedNanos = startNanos
    private var lastGpsNanos: Long? = null
    private var lastDegradedEventMillis: Long? = null
    private var gpsLostReported = false

    val stats: RideStats get() = accumulator.stats
    val isStopped: Boolean get() = autoPause.isStopped || onBreak
    /** Stopped after moving, or on a break; the ride can be shown as paused. */
    val isPausedStop: Boolean get() = autoPause.isCountedStop || onBreak

    /** On a break: a long stop off the bike, timed from the start of the stop. */
    val onBreak: Boolean get() = breaks?.onBreak == true
    val breakStartMillis: Long? get() = breaks?.breakStartMillis
    /** Why the current break started ("stopped 5 min", "phone off the mount", "engine off"). */
    val breakReason: String? get() = breaks?.reason

    /** Not counting: paused by hand or on a break. */
    private val notCounting: Boolean get() = manuallyPaused || onBreak

    /**
     * The rider paused the ride: samples keep being recorded, but distance, moving time,
     * dynamics and events don't count until it's resumed.
     */
    @Volatile
    var manuallyPaused: Boolean = false

    fun wallMillis(nanos: Long): Long = startWallMillis + (nanos - startNanos) / 1_000_000L

    fun start(): RideEvent = record(RideEvent(RideEventType.START, startWallMillis, gps.latitude, gps.longitude, null))

    fun end(nowNanos: Long): List<RideEvent> {
        accountTime(nowNanos)
        moments?.flush()?.forEach { momentEvents += it to wallMillis(nowNanos) }
        val out = detector.flush().map(::record).toMutableList()
        out += record(RideEvent(RideEventType.END, wallMillis(nowNanos), gps.latitude, gps.longitude, currentSpeed()))
        return out
    }

    fun process(reading: RawReading): List<RideEvent> = when (reading) {
        is LocationReading -> onLocation(reading)
        is AccelReading -> onAccel(reading)
        is GyroReading -> {
            lean.onGyro(reading)
            autoCal.onGyro(reading)
            capture?.onGyro(reading)
            emptyList()
        }
        is SourceStatusReading -> onStatus(reading)
        is EngineReading -> {
            rpm = reading.rpm
            gear = reading.gear
            lastEngineNanos = reading.timeNanos
            emptyList()
        }
    }

    /**
     * Starts a "Calibrate now" capture: the bike must be upright and still for ~3 s.
     * A captured mount takes priority over auto-calibration for the rest of the ride.
     */
    fun beginCalibrationCapture() {
        capture = CalibrationCollector()
        lastCapture = null
    }

    fun cancelCalibrationCapture() {
        capture = null
    }

    /**
     * A calibration learned or captured during the ride that hasn't been handed out yet
     * (so the caller can save it to the bike). Returns each one once.
     */
    fun takeNewCalibration(): MountCalibration? = pendingCalibration.also { pendingCalibration = null }

    private fun applyCalibration(cal: MountCalibration, status: CalibrationStatus) {
        lean.calibration = cal
        dynamics.calibration = cal
        calibrationStatus = status
        pendingCalibration = cal
    }

    private fun onCaptureAccel(r: AccelReading) {
        val c = capture ?: return
        c.onAccel(r)
        if (!c.isComplete) return
        capture = null
        lastCapture = when (val result = c.result(wallMillis(r.timeNanos))) {
            is CalibrationResult.Success -> {
                applyCalibration(result.calibration, CalibrationStatus.MANUAL)
                CaptureOutcome.SUCCESS
            }
            CalibrationResult.TooMuchMotion, CalibrationResult.NotEnoughData -> CaptureOutcome.TOO_MUCH_MOTION
            CalibrationResult.ImplausibleGravity -> CaptureOutcome.IMPLAUSIBLE
        }
    }

    private fun onAutoCalAccel(r: AccelReading) {
        if (calibrationStatus == CalibrationStatus.MANUAL) return
        val learned = autoCal.onAccel(r, wallMillis(r.timeNanos)) ?: return
        val current = lean.calibration
        // Only swap axes when the mount really differs; tiny refinements aren't worth a lean reset.
        if (current == null || calibrationStatus != CalibrationStatus.AUTO ||
            AutoCalibrator.differenceDeg(current, learned) > 1.5
        ) {
            applyCalibration(learned, CalibrationStatus.AUTO)
        }
    }

    private fun onLocation(r: LocationReading): List<RideEvent> {
        val out = ArrayList<RideEvent>()
        val wasLost = gpsLostReported
        val travelled = gps.onLocation(r)
        if (!notCounting) accumulator.addDistance(travelled)
        val ctx = context(r.timeNanos)
        if (wasLost && gps.quality.hasFix) {
            gpsLostReported = false
            out += RideEvent(RideEventType.GPS_SIGNAL_RESTORED, ctx.timeMillis, ctx.latitude, ctx.longitude, ctx.speedMps)
        }

        val speed = gps.speedMps
        lean.speedMps = speed
        val dt = lastGpsNanos?.let { (r.timeNanos - it) / 1e9 } ?: 1.0
        lastGpsNanos = r.timeNanos
        dynamics.onGpsAccel(gps.accelMps2, dt)
        val goodFix = gps.quality == GpsQuality.GOOD || gps.quality == GpsQuality.EXCELLENT
        if (speed != null && goodFix && !notCounting) {
            accumulator.onReliableSpeed(speed)
        }
        val accel = gps.accelMps2
        val headingRate = gps.headingRateDegPerSec
        autoCal.steady = goodFix && speed != null && speed >= AUTO_CAL_MIN_SPEED_MPS &&
            accel != null && kotlin.math.abs(accel) < 0.5 &&
            headingRate != null && kotlin.math.abs(headingRate) < 2.5

        when (autoPause.update(r.timeNanos, speed)) {
            AutoPauseDetector.Transition.STOPPED -> {
                out += detector.flush()
                moments?.flush()?.forEach { momentEvents += it to ctx.timeMillis }
                if (autoPause.isCountedStop) {
                    stoppedSinceMillis = ctx.timeMillis
                    // Walking about on a break isn't a new stop.
                    if (!onBreak) out += RideEvent(RideEventType.STOP, ctx.timeMillis, ctx.latitude, ctx.longitude, speed)
                }
            }
            AutoPauseDetector.Transition.RESUMED -> stoppedSinceMillis = null
            null -> Unit
        }
        if (!autoPause.isStopped && !notCounting) out += detector.onHeading(ctx, gps.headingDeg)
        return out.map(::record)
    }

    private fun onAccel(r: AccelReading): List<RideEvent> {
        onCaptureAccel(r)
        onAutoCalAccel(r)
        lean.onAccel(r)
        dynamics.onAccel(r)
        crash?.onAccel(r.timeNanos, Units.mps2ToG(sqrt(r.x * r.x + r.y * r.y + r.z * r.z)), gps.speedMps, lean.leanDeg)
            ?.let { pendingCrash = it to context(it.impactNanos) }
        val speed = gps.speedMps
        val moving = !autoPause.isStopped && !notCounting && speed != null && speed >= 2.0
        if (!moving) return emptyList()

        val leanDeg = lean.leanDeg?.takeIf { lean.confidence == LeanConfidence.GOOD }
        val longG = dynamics.longitudinalG
        accumulator.onDynamics(longG, lateralG())
        leanDeg?.let(accumulator::onLean)
        val ctx = context(r.timeNanos)
        moments?.onDynamics(ctx, longG, leanDeg)?.forEach { momentEvents += it to ctx.timeMillis }
        return detector.onDynamics(ctx, longG, leanDeg).map(::record)
    }

    private fun onStatus(r: SourceStatusReading): List<RideEvent> {
        val ctx = context(r.timeNanos)
        return when (r.signal) {
            SourceSignal.GPS_PROVIDER_DISABLED -> {
                gps.onProviderDisabled()
                lean.speedMps = null
                autoCal.steady = false
                reportGpsLost(ctx)
            }
            SourceSignal.GPS_PROVIDER_ENABLED -> emptyList()
            SourceSignal.MOTION_SENSOR_UNRELIABLE -> {
                lean.sensorUnreliable = true
                val last = lastDegradedEventMillis
                if (last == null || ctx.timeMillis - last > 60_000) {
                    lastDegradedEventMillis = ctx.timeMillis
                    listOf(RideEvent(RideEventType.SENSOR_DEGRADED, ctx.timeMillis, ctx.latitude, ctx.longitude, ctx.speedMps))
                } else {
                    emptyList()
                }
            }
            SourceSignal.MOTION_SENSOR_RELIABLE -> {
                lean.sensorUnreliable = false
                emptyList()
            }
        }.map(::record)
    }

    private fun reportGpsLost(ctx: EventContext): List<RideEvent> {
        if (gpsLostReported || !gps.hasEverHadFix) return emptyList()
        gpsLostReported = true
        return listOf(RideEvent(RideEventType.GPS_SIGNAL_LOST, ctx.timeMillis, ctx.latitude, ctx.longitude, null))
    }

    /** Housekeeping (time accounting, GPS timeout) + a UI snapshot. Call at a low rate. */
    fun frame(nowNanos: Long): Pair<TelemetryFrame, List<RideEvent>> {
        val events = ArrayList<RideEvent>()
        if (gps.checkTimeout(nowNanos)) {
            lean.speedMps = null
            autoCal.steady = false
            events += reportGpsLost(context(nowNanos)).map(::record)
        }
        events += updateBreak(nowNanos).map(::record)
        accountTime(nowNanos)
        val frame = TelemetryFrame(
            timeMillis = wallMillis(nowNanos),
            elapsedMillis = (nowNanos - startNanos) / 1_000_000L,
            stats = accumulator.stats,
            speedMps = currentSpeed(),
            leanDeg = lean.leanDeg,
            leanConfidence = lean.confidence,
            longitudinalG = dynamics.longitudinalG,
            lateralG = lateralG(),
            headingDeg = gps.headingDeg.takeIf { gps.quality.hasFix },
            altitudeM = gps.altitudeM.takeIf { gps.quality.hasFix },
            gpsQuality = gps.quality,
            gpsAccuracyM = gps.accuracyM.takeIf { gps.quality.hasFix },
            isStopped = isStopped,
            latitude = gps.latitude.takeIf { gps.quality.hasFix },
            longitude = gps.longitude.takeIf { gps.quality.hasFix },
            source = sourceKind,
            rpm = engineRpm(nowNanos),
            gear = engineGear(nowNanos),
            calibration = CalibrationInfo(calibrationStatus, capture?.progress, lastCapture),
            onBreak = onBreak,
            breakStartMillis = breakStartMillis,
            offMount = offMount(),
        )
        return frame to events
    }

    /**
     * The phone is tilted far from its mount (pocket, hand). Only judged when slow: braking
     * and accelerating tilt the felt gravity too. Unknown = on the mount.
     */
    fun offMount(): Boolean = (currentSpeed() ?: 0.0) < 2.0 && (lean.mountTiltDeg ?: 0.0) >= OFF_MOUNT_TILT_DEG

    private fun updateBreak(nowNanos: Long): List<RideEvent> {
        val b = breaks ?: return emptyList()
        val now = wallMillis(nowNanos)
        val fix = gps.quality.hasFix
        val input = BreakDetector.Input(
            nowMillis = now,
            stoppedSinceMillis = stoppedSinceMillis.takeIf { autoPause.isCountedStop && !manuallyPaused },
            mountTiltDeg = lean.mountTiltDeg,
            engineRunning = engineRpm(nowNanos)?.let { it >= ENGINE_RUNNING_RPM },
            speedMps = currentSpeed(),
            accuracyM = gps.accuracyM.takeIf { fix },
            latitude = gps.latitude.takeIf { fix },
            longitude = gps.longitude.takeIf { fix },
            crashSuspected = crash?.impactPending == true,
        )
        return when (b.update(input)) {
            BreakDetector.Transition.STARTED -> {
                val start = b.breakStartMillis ?: now
                accumulator.moveStoppedToBreak(now - start)
                listOf(RideEvent(RideEventType.BREAK_START, start, gps.latitude, gps.longitude, 0.0))
            }
            BreakDetector.Transition.ENDED -> {
                stoppedSinceMillis = null
                listOf(RideEvent(RideEventType.BREAK_END, now, gps.latitude, gps.longitude, currentSpeed()))
            }
            null -> emptyList()
        }
    }

    /** "Resume" pressed while on a break. */
    fun endBreak(nowNanos: Long): RideEvent? {
        val b = breaks ?: return null
        if (!b.onBreak) return null
        accountTime(nowNanos)
        b.endNow()
        stoppedSinceMillis = null
        return record(RideEvent(RideEventType.BREAK_END, wallMillis(nowNanos), gps.latitude, gps.longitude, currentSpeed()))
    }

    /** The rider paused or resumed by hand; the change is kept as an event for the export. */
    fun setManualPause(paused: Boolean, nowNanos: Long): RideEvent? {
        if (paused == manuallyPaused) return null
        accountTime(nowNanos)
        manuallyPaused = paused
        val type = if (paused) RideEventType.MANUAL_PAUSE else RideEventType.MANUAL_RESUME
        return record(RideEvent(type, wallMillis(nowNanos), gps.latitude, gps.longitude, currentSpeed()))
    }

    fun sample(nowNanos: Long): TelemetrySample {
        val fix = gps.quality.hasFix
        val leanDeg = lean.leanDeg?.takeIf { lean.confidence != LeanConfidence.UNAVAILABLE }
        return TelemetrySample(
            timeMillis = wallMillis(nowNanos),
            latitude = gps.latitude.takeIf { fix },
            longitude = gps.longitude.takeIf { fix },
            speedMps = currentSpeed(),
            altitudeM = gps.altitudeM.takeIf { fix },
            headingDeg = gps.headingDeg.takeIf { fix },
            longitudinalG = dynamics.longitudinalG,
            lateralG = lateralG(),
            leanDeg = leanDeg,
            gpsAccuracyM = gps.accuracyM.takeIf { fix },
            rpm = engineRpm(nowNanos),
            gear = engineGear(nowNanos),
        )
    }

    private fun engineFresh(nowNanos: Long): Boolean =
        lastEngineNanos?.let { nowNanos - it <= ENGINE_STALE_NANOS } == true

    private fun engineRpm(nowNanos: Long): Double? = rpm.takeIf { engineFresh(nowNanos) }

    private fun engineGear(nowNanos: Long): Int? = gear.takeIf { engineFresh(nowNanos) }

    private fun accountTime(nowNanos: Long) {
        val dtMillis = (nowNanos - lastAccountedNanos) / 1_000_000L
        if (dtMillis > 0) {
            accumulator.addTime(dtMillis, autoPause.isStopped || manuallyPaused, onBreak)
            lastAccountedNanos += dtMillis * 1_000_000L
        }
    }

    /** Speed with sub-walking-pace GPS noise shown as a true standstill. */
    private fun currentSpeed(): Double? {
        if (!gps.quality.hasFix) return null
        val s = gps.speedMps ?: return null
        return if (s < 0.5) 0.0 else s
    }

    private fun lateralG(): Double? =
        dynamics.lateralG(currentSpeed(), lean.yawRateRadPerSec, gps.headingRateDegPerSec)

    private fun context(nanos: Long) = EventContext(
        timeMillis = wallMillis(nanos),
        latitude = gps.latitude,
        longitude = gps.longitude,
        speedMps = gps.speedMps,
    )

    private fun record(e: RideEvent): RideEvent {
        accumulator.onEvent(e)
        return e
    }

    companion object {
        /** ~22 km/h: slow enough for town riding, fast enough that the bike is self-upright. */
        const val AUTO_CAL_MIN_SPEED_MPS = 6.0
        private const val ENGINE_STALE_NANOS = 3_000_000_000L
        private const val ENGINE_RUNNING_RPM = 300.0
        /** Further than this from the mounted position = off the mount (side stand is ~10–15°). */
        const val OFF_MOUNT_TILT_DEG = 35.0
    }
}
