package com.ridetrack.telemetry.processing

import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.RideStats
import kotlin.math.abs
import kotlin.math.hypot

/** Constant-memory running ride statistics. */
class RideStatsAccumulator(initial: RideStats = RideStats()) {
    var stats: RideStats = initial
        private set

    private var leanSum = 0.0
    private var leanCount = 0L

    fun addDistance(meters: Double) {
        if (meters > 0) stats = stats.copy(distanceM = stats.distanceM + meters)
    }

    fun addTime(millis: Long, stopped: Boolean, onBreak: Boolean = false) {
        if (millis <= 0) return
        stats = if (onBreak) {
            stats.copy(breakMillis = stats.breakMillis + millis)
        } else if (stopped) {
            stats.copy(stoppedMillis = stats.stoppedMillis + millis)
        } else {
            stats.copy(movingMillis = stats.movingMillis + millis)
        }
    }

    /** A stop turned out to be a break: its time so far moves from stopped to break. */
    fun moveStoppedToBreak(millis: Long) {
        val m = millis.coerceIn(0, stats.stoppedMillis)
        if (m > 0) stats = stats.copy(stoppedMillis = stats.stoppedMillis - m, breakMillis = stats.breakMillis + m)
    }

    /** Only speeds from trustworthy fixes should be passed here. */
    fun onReliableSpeed(speedMps: Double) {
        if (speedMps > (stats.maxSpeedMps ?: -1.0)) stats = stats.copy(maxSpeedMps = speedMps)
    }

    fun onDynamics(longitudinalG: Double?, lateralG: Double?) {
        var s = stats
        if (longitudinalG != null && abs(longitudinalG) <= MAX_PLAUSIBLE_G) {
            if (longitudinalG > (s.maxAccelG ?: 0.0)) s = s.copy(maxAccelG = longitudinalG)
            if (longitudinalG < (s.maxBrakeG ?: 0.0)) s = s.copy(maxBrakeG = longitudinalG)
        }
        val combined = when {
            longitudinalG != null && lateralG != null -> hypot(longitudinalG, lateralG)
            else -> null
        }
        if (combined != null && combined <= MAX_PLAUSIBLE_G && combined > (s.peakG ?: -1.0)) {
            s = s.copy(peakG = combined)
        }
        stats = s
    }

    fun onLean(leanDeg: Double) {
        var s = stats
        if (leanDeg < 0 && -leanDeg > (s.maxLeftLeanDeg ?: -1.0)) s = s.copy(maxLeftLeanDeg = -leanDeg)
        if (leanDeg > 0 && leanDeg > (s.maxRightLeanDeg ?: -1.0)) s = s.copy(maxRightLeanDeg = leanDeg)
        leanSum += abs(leanDeg)
        leanCount++
        stats = s.copy(avgLeanDeg = leanSum / leanCount)
    }

    fun onEvent(e: RideEvent) {
        stats = when (e.type) {
            RideEventType.STOP -> stats.copy(stopCount = stats.stopCount + 1)
            RideEventType.LEFT_TURN -> stats.copy(leftTurns = stats.leftTurns + 1)
            RideEventType.RIGHT_TURN -> stats.copy(rightTurns = stats.rightTurns + 1)
            RideEventType.HARD_BRAKE -> stats.copy(brakeEvents = stats.brakeEvents + 1)
            RideEventType.STRONG_ACCELERATION -> stats.copy(accelEvents = stats.accelEvents + 1)
            RideEventType.SIGNIFICANT_LEAN -> stats.copy(leanEvents = stats.leanEvents + 1)
            else -> stats
        }
    }

    companion object {
        /** Anything above this from a phone on a motorcycle is a knock or glitch, not riding. */
        const val MAX_PLAUSIBLE_G = 1.6
    }
}
