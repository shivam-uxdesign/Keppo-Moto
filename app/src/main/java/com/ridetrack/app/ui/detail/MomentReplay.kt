package com.ridetrack.app.ui.detail

import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind

/** The stretch of ride time a moment covers during replay: the clip itself, or a few seconds for a photo. */
data class MomentWindow(val id: String, val startMillis: Long, val endMillis: Long) {
    companion object {
        fun of(m: Moment): MomentWindow =
            if (m.kind == MomentKind.PHOTO) MomentWindow(m.id, m.timeMillis, m.timeMillis + PHOTO_SHOW_MS)
            else MomentWindow(m.id, m.videoStartMillis, m.videoStartMillis + (m.durationMillis ?: DEFAULT_CLIP_MS))
    }
}

const val PHOTO_SHOW_MS = 4_000L
private const val DEFAULT_CLIP_MS = 10_000L

/**
 * Stops the replay at each moment: whatever the replay speed, crossing the start of a moment
 * lands exactly on it and plays its window at 1×, then carries on at the chosen speed. Each
 * moment shows once until the rider scrubs or jumps ([reset]).
 */
class MomentReplay(windows: List<MomentWindow> = emptyList()) {
    private var windows: List<MomentWindow> = windows.sortedBy { it.startMillis }
    private val done = mutableSetOf<String>()

    /** The moment playing right now, if any. */
    var active: MomentWindow? = null
        private set

    fun setWindows(list: List<MomentWindow>) {
        windows = list.sortedBy { it.startMillis }
        if (active != null && windows.none { it.id == active?.id }) active = null
    }

    fun reset() {
        done.clear()
        active = null
    }

    /** Ride time after [realMillis] of playback from [time] at [speed]×. */
    fun advance(time: Double, realMillis: Double, speed: Double): Double {
        active?.let { a ->
            val next = time + realMillis
            if (next < a.endMillis) return next
            done += a.id
            active = null
            return a.endMillis.toDouble()
        }
        val next = time + realMillis * speed
        val hit = windows.firstOrNull { it.id !in done && it.startMillis >= time && it.startMillis <= next }
        if (hit != null) {
            active = hit
            return hit.startMillis.toDouble()
        }
        return next
    }

    /** Skips the playing moment; returns where the replay continues from. */
    fun skip(): Double? {
        val a = active ?: return null
        done += a.id
        active = null
        return a.endMillis.toDouble()
    }
}
