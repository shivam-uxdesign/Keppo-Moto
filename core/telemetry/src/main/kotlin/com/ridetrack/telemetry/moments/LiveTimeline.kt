package com.ridetrack.telemetry.moments

/**
 * Timestamps for a long recording written as it's filmed: the file starts at its first
 * keyframe, and pausing leaves no gap. After a resume, video waits for the next keyframe
 * (so it decodes) and audio waits for the video, keeping the two in step.
 * Times are wall-clock µs in, presentation µs out; null = leave the sample out.
 */
class LiveTimeline {
    private var base = -1L
    /** Total paused time cut out so far. */
    private var shift = 0L
    private var pausedAt = -1L
    private var awaitingKey = false
    /** Audio before this belongs to the paused stretch. */
    private var audioFrom = Long.MIN_VALUE
    private var lastVideo = -1L
    private var lastAudio = -1L
    private var lastWall = -1L

    val paused: Boolean get() = pausedAt >= 0

    /** Length written so far (µs of video). */
    val lengthMicros: Long get() = lastVideo.coerceAtLeast(0)

    fun pause() {
        if (pausedAt < 0 && base >= 0) pausedAt = lastWall.coerceAtLeast(base)
    }

    fun resume() {
        if (pausedAt >= 0) awaitingKey = true
    }

    fun video(wallMicros: Long, keyFrame: Boolean): Long? {
        if (base < 0) {
            if (!keyFrame) return null
            base = wallMicros
            audioFrom = wallMicros
        }
        if (pausedAt >= 0) {
            if (!awaitingKey || !keyFrame || wallMicros <= pausedAt) return null
            shift += wallMicros - pausedAt
            pausedAt = -1
            awaitingKey = false
            audioFrom = wallMicros
        }
        lastWall = wallMicros
        val pts = maxOf(wallMicros - base - shift, lastVideo + 1)
        lastVideo = pts
        return pts
    }

    fun audio(wallMicros: Long): Long? {
        if (base < 0 || pausedAt >= 0 || wallMicros < audioFrom) return null
        val pts = maxOf(wallMicros - base - shift, lastAudio + 1)
        lastAudio = pts
        return pts
    }
}
