package com.ridetrack.app.moments

/**
 * Whether a mic that dropped out is back for good. Each switch restarts audio (a gap in
 * clips), so a mic that comes and goes, e.g. a USB receiver renegotiating power on a bike,
 * is only switched back to once it has stayed connected for [holdMillis], or [flappingHoldMillis]
 * after [flapCount] drops within [flapWindowMillis]. Pure; times are any monotonic millis.
 */
class MicStability(
    private val holdMillis: Long = 10_000L,
    private val flappingHoldMillis: Long = 60_000L,
    private val flapCount: Int = 3,
    private val flapWindowMillis: Long = 120_000L,
) {
    private val drops = ArrayDeque<Long>()
    private var presentSince: Long? = null
    private var forced = false

    /** What's connected now: is the wanted mic there? */
    fun onSeen(nowMillis: Long, present: Boolean) {
        if (present) {
            if (presentSince == null) presentSince = nowMillis
        } else {
            if (presentSince != null) drops.addLast(nowMillis)
            presentSince = null
            forced = false
        }
        while (drops.isNotEmpty() && nowMillis - drops.first() > flapWindowMillis) drops.removeFirst()
    }

    /** True once the mic has been connected long enough to switch back to it. */
    fun stable(nowMillis: Long): Boolean {
        val since = presentSince ?: return false
        return forced || nowMillis - since >= hold()
    }

    /** "Reconnect mic": switch at once if it's connected. */
    fun force() {
        forced = true
    }

    /** It's been dropping repeatedly; the HUD can say so. */
    val flapping: Boolean get() = drops.size >= flapCount

    private fun hold() = if (flapping) flappingHoldMillis else holdMillis
}
