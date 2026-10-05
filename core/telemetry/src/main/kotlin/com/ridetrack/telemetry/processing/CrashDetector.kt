package com.ridetrack.telemetry.processing

import kotlin.math.abs

/** A likely crash: when the impact was and how fast the bike was going just before it. */
data class CrashSuspected(val impactNanos: Long, val speedBeforeMps: Double?, val peakG: Double)

/**
 * Looks for a crash in the accelerometer: a hard impact while riding, then the bike down
 * (lean held past [downLeanDeg]) or not moving at all. A pothole or kerb hit at speed is
 * followed by more riding, and a dropped phone at a standstill has no ride before it, so
 * neither counts. Fed at sensor rate; [onAccel] returns a report once per crash.
 */
class CrashDetector(
    /** Total acceleration (including gravity) that counts as an impact. */
    var impactG: Double = 4.0,
    private val movingMps: Double = 15.0 / 3.6,
    private val downLeanDeg: Double = 60.0,
) {
    /** An impact is being judged: a stop right now may be a crash, not a break. */
    val impactPending: Boolean get() = impactNanos != null

    private var lastMovingNanos: Long? = null
    private var lastMovingSpeed: Double? = null
    private var impactNanos: Long? = null
    private var impactSpeed: Double? = null
    private var peak = 0.0
    private var downSince: Long? = null
    private var stillSince: Long? = null

    /** [accelG]: magnitude of the accelerometer in g. */
    fun onAccel(nanos: Long, accelG: Double, speedMps: Double?, leanDeg: Double?): CrashSuspected? {
        if (speedMps != null && speedMps >= movingMps) {
            lastMovingNanos = nanos
            lastMovingSpeed = speedMps
        }
        val impact = impactNanos
        if (impact == null) {
            val moving = lastMovingNanos?.let { nanos - it <= MOVING_BEFORE_NS } == true
            if (accelG >= impactG && moving) {
                impactNanos = nanos
                impactSpeed = lastMovingSpeed
                peak = accelG
                downSince = null
                stillSince = null
            }
            return null
        }
        val since = nanos - impact
        if (accelG > peak && since < SETTLE_NS) peak = accelG
        // Riding on normally after the hit: a pothole or a kerb, not a crash.
        if (since > SETTLE_NS && speedMps != null && speedMps >= RIDING_ON_MPS) return reset()
        if (since > WINDOW_NS) return reset()
        if (since < SETTLE_NS) return null
        val slow = speedMps == null || speedMps < SLOW_MPS
        if (!slow) {
            downSince = null
            stillSince = null
            return null
        }
        val down = leanDeg != null && abs(leanDeg) >= downLeanDeg
        downSince = if (down) downSince ?: nanos else null
        val still = abs(accelG - 1.0) < STILL_TOLERANCE_G
        stillSince = if (still) stillSince ?: nanos else null
        val confirmed = (downSince?.let { nanos - it >= DOWN_HOLD_NS } == true) ||
            (stillSince?.let { nanos - it >= STILL_HOLD_NS } == true)
        if (!confirmed) return null
        val report = CrashSuspected(impact, impactSpeed, peak)
        reset()
        // Don't report the same crash again while the bike lies there.
        lastMovingNanos = null
        return report
    }

    private fun reset(): CrashSuspected? {
        impactNanos = null
        downSince = null
        stillSince = null
        return null
    }

    private companion object {
        const val MS = 1_000_000L
        /** The bike must have been moving within this long before the impact. */
        const val MOVING_BEFORE_NS = 3_000 * MS
        /** Let the crash itself play out before judging what follows. */
        const val SETTLE_NS = 2_000 * MS
        const val WINDOW_NS = 15_000 * MS
        const val DOWN_HOLD_NS = 3_000 * MS
        const val STILL_HOLD_NS = 5_000 * MS
        const val SLOW_MPS = 2.0
        const val RIDING_ON_MPS = 3.0
        const val STILL_TOLERANCE_G = 0.15
    }
}
