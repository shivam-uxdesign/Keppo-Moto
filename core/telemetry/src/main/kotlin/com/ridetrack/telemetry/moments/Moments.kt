package com.ridetrack.telemetry.moments

import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.processing.EventContext
import com.ridetrack.telemetry.processing.EventDetector
import com.ridetrack.telemetry.processing.EventThresholds

/** Which events may start a moment clip, and how strong they must be. */
data class MomentTriggers(
    val braking: Boolean = true,
    val acceleration: Boolean = true,
    val lean: Boolean = true,
    /** Braking at least this hard (G). */
    val brakeG: Double = 0.5,
    /** Accelerating at least this hard (G). */
    val accelG: Double = 0.3,
    /** Leaning past this angle (degrees, either side). */
    val leanDeg: Double = 20.0,
) {
    /** Detector thresholds for these settings; each episode ends a little below its trigger. */
    fun thresholds(): EventThresholds = EventThresholds(
        hardBrakeG = brakeG,
        hardBrakeReleaseG = brakeG / 2,
        strongAccelG = accelG,
        strongAccelReleaseG = accelG * 0.4,
        significantLeanDeg = leanDeg,
        significantLeanReleaseDeg = leanDeg * 0.75,
    )

    fun allows(type: RideEventType) = when (type) {
        RideEventType.HARD_BRAKE -> braking
        RideEventType.STRONG_ACCELERATION -> acceleration
        RideEventType.SIGNIFICANT_LEAN -> lean
        else -> false
    }
}

/**
 * Detects moment-worthy episodes. A second [EventDetector] with the rider's own thresholds,
 * so moments can trigger differently from the ride's statistics (e.g. a lower lean).
 */
class MomentTrigger(
    private val triggers: MomentTriggers = MomentTriggers(),
    thresholds: EventThresholds = triggers.thresholds(),
) {
    private val detector = EventDetector(thresholds)

    /** Same inputs as the ride's detector; only call while moving. */
    fun onDynamics(ctx: EventContext, longitudinalG: Double?, leanDeg: Double?): List<RideEvent> =
        detector.onDynamics(ctx, longitudinalG, leanDeg).filter { triggers.allows(it.type) }

    fun flush(): List<RideEvent> = detector.flush().filter { triggers.allows(it.type) }
}

/** A stretch of the ride to save as one clip. Times are wall-clock milliseconds. */
data class MomentWindow(
    val startMillis: Long,
    val endMillis: Long,
    /** When the (first) event began; the clip's thumbnail frame. */
    val anchorMillis: Long,
    val types: Set<RideEventType>,
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
    /** Strongest value among merged events (G or signed degrees). */
    val peakValue: Double?,
)

/**
 * Turns moment events into clip windows: [start − before, start + after], stretched to
 * cover long episodes (up to [maxMillis]). Overlapping events merge into one clip; an
 * event already inside a saved clip is skipped. [due] hands out windows once their end
 * has passed, so the footage exists.
 */
class MomentPlanner(
    private val beforeMillis: Long = 10_000,
    private val afterMillis: Long = 10_000,
    private val tailAfterEpisodeMillis: Long = 2_000,
    private val maxMillis: Long = 40_000,
) {
    private var pending: MomentWindow? = null
    private var lastSavedEnd = Long.MIN_VALUE

    val hasPending: Boolean get() = pending != null

    /** Where the clip being filmed starts (including its look-back), or null. */
    val pendingStartMillis: Long? get() = pending?.startMillis

    /**
     * [episodeEndMillis] = when the detector reported the event (the episode's end).
     * Returns true when the event landed inside the clip already being filmed (a chain).
     */
    fun add(event: RideEvent, episodeEndMillis: Long): Boolean {
        val start = event.timeMillis
        if (start < lastSavedEnd) return false // already on film
        val clipStart = maxOf(start - beforeMillis, lastSavedEnd)
        val clipEnd = maxOf(start + afterMillis, episodeEndMillis + tailAfterEpisodeMillis)
        val p = pending
        val merged = p != null && clipStart <= p.endMillis
        pending = if (p != null && merged) {
            val end = minOf(maxOf(p.endMillis, clipEnd), p.startMillis + maxMillis)
            p.copy(endMillis = end, types = p.types + event.type, peakValue = strongest(p.peakValue, event.value))
        } else {
            // A separate clip; anything still pending is handed out first by due().
            if (p != null) queued += p
            MomentWindow(
                startMillis = clipStart,
                endMillis = minOf(clipEnd, clipStart + maxMillis),
                anchorMillis = start,
                types = setOf(event.type),
                latitude = event.latitude,
                longitude = event.longitude,
                speedMps = event.speedMps,
                peakValue = event.value,
            )
        }
        return merged
    }

    /**
     * Hands over the clip being filmed, to be filmed as a longer video instead (a chain of
     * events). It's no longer cut from the buffer.
     */
    fun promotePending(): MomentWindow? = pending.also { pending = null }

    /** A video covered the ride until [endMillis]: events before then are already on film. */
    fun markFilmed(endMillis: Long) {
        lastSavedEnd = maxOf(lastSavedEnd, endMillis)
    }

    /** The longer video never started: cut [w] from the buffer after all. */
    fun restore(w: MomentWindow) {
        val p = pending
        pending = if (p == null) w else p.copy(
            startMillis = minOf(p.startMillis, w.startMillis),
            endMillis = maxOf(p.endMillis, w.endMillis),
            types = p.types + w.types,
            peakValue = strongest(p.peakValue, w.peakValue),
        )
    }

    private val queued = ArrayList<MomentWindow>()

    /** Windows whose end has passed at [nowMillis]. */
    fun due(nowMillis: Long): List<MomentWindow> {
        val out = ArrayList<MomentWindow>(queued)
        queued.clear()
        pending?.takeIf { it.endMillis <= nowMillis }?.let {
            out += it
            pending = null
        }
        out.forEach { lastSavedEnd = maxOf(lastSavedEnd, it.endMillis) }
        return out
    }

    /** Ride ending: everything pending is cut short at [nowMillis]. */
    fun flush(nowMillis: Long): List<MomentWindow> {
        pending?.let { queued += it.copy(endMillis = minOf(it.endMillis, nowMillis)) }
        pending = null
        return due(nowMillis)
    }

    private fun strongest(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        kotlin.math.abs(b) > kotlin.math.abs(a) -> b
        else -> a
    }
}

/**
 * When to take the periodic photo: every [intervalMillis] of moving time, plus once per
 * stop that lasts longer than [stopMillis] (a view worth keeping, usually).
 */
class PhotoScheduler(
    private val intervalMillis: Long,
    private val stopMillis: Long = 60_000,
) {
    private var nextMovingAt = intervalMillis
    private var stoppedSince: Long? = null
    private var tookThisStop = false

    /** Call at a low rate; returns true when a photo should be taken now. */
    fun onTick(nowMillis: Long, movingMillis: Long, isStopped: Boolean): Boolean {
        if (isStopped) {
            val since = stoppedSince ?: nowMillis.also { stoppedSince = it }
            if (!tookThisStop && nowMillis - since >= stopMillis) {
                tookThisStop = true
                return true
            }
            return false
        }
        stoppedSince = null
        tookThisStop = false
        if (intervalMillis > 0 && movingMillis >= nextMovingAt) {
            nextMovingAt = movingMillis + intervalMillis
            return true
        }
        return false
    }
}
