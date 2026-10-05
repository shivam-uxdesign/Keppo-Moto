package com.ridetrack.telemetry.processing

import com.ridetrack.telemetry.math.Geo

/**
 * Tells a break (the rider got off the bike) from waiting at a signal. A counted stop
 * becomes a break, timed from the start of the stop, when any of these holds:
 *  - it has lasted [afterMillis] (0 = only the faster signals below);
 *  - the phone has been tilted far from its mount for [offMountMillis] (pocket, hand);
 *  - the engine has been off for [engineOffMillis] (OBD rpm 0, or OBD gone quiet).
 *
 * Ending a break needs real riding, not walking or GPS drift: [resumeSpeedMps] for
 * [resumeHoldMillis] on a good fix, and (when a position is known) [resumeDistanceM]
 * from where the break began. Pure; fed at a low rate with wall-clock times.
 */
class BreakDetector(
    var afterMillis: Long = 5 * 60_000L,
    private val offMountTiltDeg: Double = 35.0,
    private val offMountMillis: Long = 20_000L,
    private val engineOffMillis: Long = 60_000L,
    private val resumeSpeedMps: Double = 4.0,
    private val resumeHoldMillis: Long = 3_000L,
    private val resumeDistanceM: Double = 100.0,
    private val resumeMaxAccuracyM: Double = 20.0,
) {
    enum class Transition { STARTED, ENDED }

    /** What one tick knows. [stoppedSinceMillis] is null unless in a counted stop (not a manual pause). */
    data class Input(
        val nowMillis: Long,
        val stoppedSinceMillis: Long?,
        val mountTiltDeg: Double?,
        /** Null = no engine data. */
        val engineRunning: Boolean?,
        val speedMps: Double?,
        val accuracyM: Double?,
        val latitude: Double?,
        val longitude: Double?,
        /** A hard impact just happened: a possible crash is never shown as a break. */
        val crashSuspected: Boolean = false,
    )

    var onBreak: Boolean = false
        private set

    /** When the break (its stop) began. */
    var breakStartMillis: Long? = null
        private set

    /** Why the current break started, for the log. */
    var reason: String? = null
        private set

    private var offMountSince: Long? = null
    private var engineOffSince: Long? = null
    private var engineSeenThisStop = false
    private var anchorLat: Double? = null
    private var anchorLon: Double? = null
    private var fastSince: Long? = null

    fun update(i: Input): Transition? = if (onBreak) whileOnBreak(i) else whileStopped(i)

    private fun whileStopped(i: Input): Transition? {
        val since = i.stoppedSinceMillis
        if (since == null || i.crashSuspected) {
            offMountSince = null
            engineOffSince = null
            engineSeenThisStop = false
            return null
        }
        val offMount = i.mountTiltDeg != null && i.mountTiltDeg >= offMountTiltDeg
        offMountSince = if (offMount) offMountSince ?: i.nowMillis else null

        // Engine off: rpm reads 0, or the OBD link goes quiet after we saw it running this stop.
        if (i.engineRunning == true) engineSeenThisStop = true
        val engineOff = i.engineRunning == false || (i.engineRunning == null && engineSeenThisStop)
        engineOffSince = if (engineOff) engineOffSince ?: i.nowMillis else null

        val why = when {
            afterMillis > 0 && i.nowMillis - since >= afterMillis -> "stopped ${afterMillis / 60_000} min"
            offMountSince?.let { i.nowMillis - it >= offMountMillis } == true -> "phone off the mount"
            engineOffSince?.let { i.nowMillis - it >= engineOffMillis } == true -> "engine off"
            else -> return null
        }
        onBreak = true
        breakStartMillis = since
        reason = why
        anchorLat = i.latitude
        anchorLon = i.longitude
        fastSince = null
        offMountSince = null
        engineOffSince = null
        engineSeenThisStop = false
        return Transition.STARTED
    }

    private fun whileOnBreak(i: Input): Transition? {
        if (anchorLat == null && i.latitude != null) {
            anchorLat = i.latitude
            anchorLon = i.longitude
        }
        val goodFix = i.accuracyM != null && i.accuracyM <= resumeMaxAccuracyM
        val fast = goodFix && i.speedMps != null && i.speedMps >= resumeSpeedMps
        fastSince = if (fast) fastSince ?: i.nowMillis else null
        val held = fastSince?.let { i.nowMillis - it >= resumeHoldMillis } == true
        if (!held) return null
        val lat0 = anchorLat
        val lon0 = anchorLon
        val far = lat0 == null || lon0 == null || i.latitude == null || i.longitude == null ||
            Geo.distanceM(lat0, lon0, i.latitude, i.longitude) >= resumeDistanceM
        if (!far) return null
        onBreak = false
        breakStartMillis = null
        reason = null
        fastSince = null
        anchorLat = null
        anchorLon = null
        return Transition.ENDED
    }

    /** The rider ended the break by hand ("Resume"). */
    fun endNow() {
        onBreak = false
        breakStartMillis = null
        reason = null
        fastSince = null
        anchorLat = null
        anchorLon = null
    }
}
