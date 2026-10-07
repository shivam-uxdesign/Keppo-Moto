package com.ridetrack.telemetry.moments

import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType

/**
 * Revs and exhaust pops on the engine mic: the engine's level jumps well above its own recent
 * level (the drone of riding) and holds for a moment. One event per burst, then a rest, so a
 * long blat is one moment, not ten.
 */
class EngineRevs(
    /** How far above the engine's usual level counts (dB). */
    private val marginDb: Float = 12f,
    /** How long it has to hold (ms). */
    private val holdMillis: Long = 250,
    /** Quiet time after an event before the next can start (ms). */
    private val restMillis: Long = 15_000,
) {
    private var background: Float? = null
    private var aboveSince: Long? = null
    private var peak = 0f
    private var lastEvent = Long.MIN_VALUE / 2

    /** One chunk of the engine mic: its level (dBFS) at [timeMillis]. Returns a rev when one has just held long enough. */
    fun onLevel(timeMillis: Long, levelDb: Float, chunkMillis: Long): RideEvent? {
        val bg = background ?: levelDb.also { background = it }
        val above = levelDb - bg
        // The engine's usual level follows slowly (about 5 s), and not up into a rev.
        val k = (chunkMillis / 5_000f).coerceIn(0.001f, 0.2f)
        background = if (above > marginDb / 2) bg + (levelDb - bg) * k * 0.1f else bg + (levelDb - bg) * k
        if (above < marginDb) {
            aboveSince = null
            peak = 0f
            return null
        }
        val since = aboveSince ?: timeMillis.also { aboveSince = it }
        peak = maxOf(peak, above)
        if (timeMillis - since + chunkMillis < holdMillis || timeMillis - lastEvent < restMillis) return null
        lastEvent = timeMillis
        aboveSince = null
        return RideEvent(RideEventType.ENGINE_REV, since, null, null, null, peak.toDouble())
    }
}
