package com.ridetrack.telemetry.processing

import com.ridetrack.telemetry.math.Geo
import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType
import kotlin.math.abs
import kotlin.math.sign

/** Where/when an event happened. */
data class EventContext(
    val timeMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
)

data class EventThresholds(
    // 0.35 g flagged ordinary city braking (20–34 "hard" brakes per ride in real data).
    val hardBrakeG: Double = 0.50,
    val hardBrakeReleaseG: Double = 0.25,
    val strongAccelG: Double = 0.30,
    val strongAccelReleaseG: Double = 0.12,
    val minGDurationMillis: Long = 300,
    val significantLeanDeg: Double = 25.0,
    val significantLeanReleaseDeg: Double = 18.0,
    val minLeanDurationMillis: Long = 500,
    val turnMinHeadingChangeDeg: Double = 60.0,
    val sharpChangeDegPerFix: Double = 45.0,
    val minTurnSpeedMps: Double = 3.0,
    val minSharpChangeSpeedMps: Double = 5.0,
)

/**
 * Detects discrete events from derived signals. Episodes (a brake, a lean) are reported
 * once, when they end, with the peak value and the context from when they started.
 */
class EventDetector(private val t: EventThresholds = EventThresholds()) {

    private val brake = Episode(t.hardBrakeG, t.hardBrakeReleaseG, t.minGDurationMillis)
    private val accel = Episode(t.strongAccelG, t.strongAccelReleaseG, t.minGDurationMillis)
    private val lean = Episode(t.significantLeanDeg, t.significantLeanReleaseDeg, t.minLeanDurationMillis)

    private var lastHeading: Double? = null
    private var turnAccumDeg = 0.0
    private var turnStart: EventContext? = null
    private var quietFixes = 0

    /** Feed filtered dynamics at sensor rate. Only call while the bike is moving. */
    fun onDynamics(ctx: EventContext, longitudinalG: Double?, leanDeg: Double?): List<RideEvent> {
        val out = ArrayList<RideEvent>(1)
        brake.update(ctx, longitudinalG?.let { -it })?.let { (start, peak) ->
            out += event(RideEventType.HARD_BRAKE, start, -peak)
        }
        accel.update(ctx, longitudinalG)?.let { (start, peak) ->
            out += event(RideEventType.STRONG_ACCELERATION, start, peak)
        }
        lean.update(ctx, leanDeg, signed = true)?.let { (start, peak) ->
            out += event(RideEventType.SIGNIFICANT_LEAN, start, peak)
        }
        return out
    }

    /** Feed each GPS heading (degrees clockwise from north). */
    fun onHeading(ctx: EventContext, headingDeg: Double?): List<RideEvent> {
        val speed = ctx.speedMps
        if (headingDeg == null || speed == null || speed < t.minTurnSpeedMps) {
            lastHeading = null
            return finishTurn()
        }
        val prev = lastHeading
        lastHeading = headingDeg
        if (prev == null) return emptyList()

        val out = ArrayList<RideEvent>(2)
        val delta = Geo.headingDeltaDeg(prev, headingDeg)
        if (abs(delta) >= t.sharpChangeDegPerFix && speed >= t.minSharpChangeSpeedMps) {
            out += event(RideEventType.SHARP_DIRECTION_CHANGE, ctx, delta)
        }
        when {
            abs(delta) < 2.0 -> {
                quietFixes++
                if (quietFixes >= 3) out += finishTurn()
            }
            turnAccumDeg == 0.0 || sign(delta) == sign(turnAccumDeg) -> {
                if (turnAccumDeg == 0.0) turnStart = ctx
                turnAccumDeg += delta
                quietFixes = 0
            }
            else -> {
                out += finishTurn()
                turnStart = ctx
                turnAccumDeg = delta
                quietFixes = 0
            }
        }
        return out
    }

    /** Flush episodes in progress, e.g. when the ride ends or the bike stops. */
    fun flush(): List<RideEvent> {
        val out = ArrayList<RideEvent>()
        brake.forceEnd()?.let { (s, p) -> out += event(RideEventType.HARD_BRAKE, s, -p) }
        accel.forceEnd()?.let { (s, p) -> out += event(RideEventType.STRONG_ACCELERATION, s, p) }
        lean.forceEnd()?.let { (s, p) -> out += event(RideEventType.SIGNIFICANT_LEAN, s, p) }
        out += finishTurn()
        lastHeading = null
        return out
    }

    private fun finishTurn(): List<RideEvent> {
        val start = turnStart
        val accum = turnAccumDeg
        turnAccumDeg = 0.0
        turnStart = null
        quietFixes = 0
        if (start == null || abs(accum) < t.turnMinHeadingChangeDeg) return emptyList()
        val type = if (accum > 0) RideEventType.RIGHT_TURN else RideEventType.LEFT_TURN
        return listOf(event(type, start, accum))
    }

    private fun event(type: RideEventType, ctx: EventContext, value: Double?) = RideEvent(
        type = type,
        timeMillis = ctx.timeMillis,
        latitude = ctx.latitude,
        longitude = ctx.longitude,
        speedMps = ctx.speedMps,
        value = value,
    )

    /** Threshold-crossing episode with hysteresis and a minimum duration. */
    private class Episode(
        private val threshold: Double,
        private val release: Double,
        private val minDurationMillis: Long,
    ) {
        private var start: EventContext? = null
        private var peak = 0.0
        private var lastTime = 0L

        fun update(ctx: EventContext, value: Double?, signed: Boolean = false): Pair<EventContext, Double>? {
            val magnitude = if (value == null) null else if (signed) abs(value) else value
            val s = start
            if (s == null) {
                if (value != null && magnitude != null && magnitude >= threshold) {
                    start = ctx
                    peak = value
                    lastTime = ctx.timeMillis
                }
                return null
            }
            if (value == null || magnitude == null || magnitude < release) return end()
            if (magnitude > (if (signed) abs(peak) else peak)) peak = value
            lastTime = ctx.timeMillis
            return null
        }

        fun forceEnd(): Pair<EventContext, Double>? = end()

        private fun end(): Pair<EventContext, Double>? {
            val s = start ?: return null
            start = null
            return if (lastTime - s.timeMillis >= minDurationMillis) s to peak else null
        }
    }
}
