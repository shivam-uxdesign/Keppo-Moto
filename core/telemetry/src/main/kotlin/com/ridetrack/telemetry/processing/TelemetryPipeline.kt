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
) {
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
    val isStopped: Boolean get() = autoPause.isStopped
    /** Stopped after moving; the ride can be shown as paused. */
    val isPausedStop: Boolean get() = autoPause.isCountedStop

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
        accumulator.addDistance(gps.onLocation(r))
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
        if (speed != null && goodFix) {
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
                    out += RideEvent(RideEventType.STOP, ctx.timeMillis, ctx.latitude, ctx.longitude, speed)
                }
            }
            AutoPauseDetector.Transition.RESUMED, null -> Unit
        }
        if (!autoPause.isStopped) out += detector.onHeading(ctx, gps.headingDeg)
        return out.map(::record)
    }

    private fun onAccel(r: AccelReading): List<RideEvent> {
        onCaptureAccel(r)
        onAutoCalAccel(r)
        lean.onAccel(r)
        dynamics.onAccel(r)
        val speed = gps.speedMps
        val moving = !autoPause.isStopped && speed != null && speed >= 2.0
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
            isStopped = autoPause.isStopped,
            latitude = gps.latitude.takeIf { gps.quality.hasFix },
            longitude = gps.longitude.takeIf { gps.quality.hasFix },
            source = sourceKind,
            rpm = engineRpm(nowNanos),
            gear = engineGear(nowNanos),
            calibration = CalibrationInfo(calibrationStatus, capture?.progress, lastCapture),
        )
        return frame to events
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
            accumulator.addTime(dtMillis, autoPause.isStopped)
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
    }
}
