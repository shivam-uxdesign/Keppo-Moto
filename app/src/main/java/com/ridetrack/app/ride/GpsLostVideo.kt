package com.ridetrack.app.ride

/**
 * When GPS drops out mid-ride, film until it's back: a safety record of a stretch the
 * track can't show. Starts after [startAfterMillis] without a fix (the video includes that
 * lead-in), stops once the fix has been back for [stopAfterMillis], and never runs longer
 * than [maxMillis]. A video you're already filming takes priority; one you stop by hand
 * isn't restarted until GPS has come back.
 */
class GpsLostVideo(
    private val startAfterMillis: Long = 10_000L,
    private val stopAfterMillis: Long = 5_000L,
    private val maxMillis: Long = 10 * 60_000L,
) {
    enum class Action { NONE, START, STOP }

    private var lostSince: Long? = null
    private var fixSince: Long? = null
    private var startedAt: Long? = null
    /** Done for this outage (ran out, or stopped by hand). */
    private var spent = false

    val filming: Boolean get() = startedAt != null

    /**
     * One tick. [ourVideoRunning]: our safety video is still being filmed (false if it was
     * stopped by hand); [otherVideoRunning]: you're filming a video yourself.
     */
    fun onTick(now: Long, gpsLost: Boolean, ourVideoRunning: Boolean, otherVideoRunning: Boolean): Action {
        if (startedAt != null && !ourVideoRunning && now - startedAt!! > GRACE_MILLIS) {
            // Stopped from the HUD (or the camera gave up): leave it for this outage.
            startedAt = null
            spent = true
        }
        if (gpsLost) {
            fixSince = null
            val since = lostSince ?: now.also { lostSince = it }
            val started = startedAt
            if (started != null && now - started >= maxMillis) {
                startedAt = null
                spent = true
                return Action.STOP
            }
            if (started == null && !spent && !otherVideoRunning && now - since >= startAfterMillis) {
                startedAt = now
                return Action.START
            }
        } else {
            lostSince = null
            val since = fixSince ?: now.also { fixSince = it }
            if (now - since >= stopAfterMillis) {
                spent = false
                if (startedAt != null) {
                    startedAt = null
                    return Action.STOP
                }
            }
        }
        return Action.NONE
    }

    /** The lead-in to include when starting: the time GPS has already been gone. */
    fun leadInMillis(now: Long): Long = lostSince?.let { now - it } ?: 0L

    private companion object {
        /** The video takes a moment to start; don't read "not running yet" as stopped. */
        const val GRACE_MILLIS = 8_000L
    }
}
