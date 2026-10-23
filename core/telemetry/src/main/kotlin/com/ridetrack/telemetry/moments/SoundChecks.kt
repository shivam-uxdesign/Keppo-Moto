package com.ridetrack.telemetry.moments

import java.util.Locale

/**
 * How well a video's sound covers its picture: where the sound starts and ends against the
 * video, and the holes in it (a stretch with no sound; players and joins fill it with silence).
 * Fed each written sample's time in the file (µs). Pure.
 */
class SoundCoverage(private val holeMicros: Long = 200_000L) {
    private var videoLast = -1L
    private var soundFirst = -1L
    private var soundLast = -1L
    var holes = 0
        private set
    var holesMicros = 0L
        private set

    fun onVideo(micros: Long) {
        videoLast = maxOf(videoLast, micros)
    }

    fun onSound(micros: Long) {
        if (soundFirst < 0) soundFirst = micros
        if (soundLast >= 0 && micros - soundLast > holeMicros) {
            holes++
            holesMicros += micros - soundLast
        }
        soundLast = maxOf(soundLast, micros)
    }

    /** "sound 0.0–41.2 s of 45.1 s, 1 hole (5.1 s)", for the log. */
    fun summary(): String {
        if (soundFirst < 0) return "no sound"
        fun s(us: Long) = String.format(Locale.US, "%.1f", us / 1_000_000.0)
        val hole = if (holes == 0) "no holes" else "$holes hole${if (holes > 1) "s" else ""} (${s(holesMicros)} s)"
        return "sound ${s(soundFirst)}–${s(soundLast)} s of ${s(videoLast.coerceAtLeast(0))} s, $hole"
    }
}

/**
 * Sound the mic delivered against the time that passed: when the app falls behind, Android drops
 * what didn't fit in its buffer, and the recording comes out short. Reports, for each [windowMicros],
 * how much went missing when it's more than [minLostMicros]. Pure; fed every read.
 */
class SoundShortfall(
    private val sampleRate: Int,
    private val windowMicros: Long = 10_000_000L,
    private val minLostMicros: Long = 100_000L,
) {
    private var windowStart = -1L
    private var frames = 0L

    /** [nowMicros] = when the read returned, [read] = frames it gave. The µs lost when a window closes short; else null. */
    fun onRead(nowMicros: Long, read: Int): Long? {
        if (windowStart < 0) {
            // The first read waits for the mic to start: count from when it returned.
            windowStart = nowMicros
            return null
        }
        frames += read.coerceAtLeast(0)
        val elapsed = nowMicros - windowStart
        if (elapsed < windowMicros) return null
        val lost = elapsed - frames * 1_000_000L / sampleRate
        windowStart = nowMicros
        frames = 0
        return lost.takeIf { it > minLostMicros }
    }
}
