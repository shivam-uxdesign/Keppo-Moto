package com.ridetrack.telemetry.moments

/**
 * "The rider is speaking", from mic levels: at least [minAboveMillis] of audio at or above
 * the threshold within the last [windowMillis]. A single knock or gust is too short to count.
 * Pure; fed with each audio chunk's level (dBFS) and length.
 */
class SpeechGate(
    private val windowMillis: Long = 1_000L,
    private val minAboveMillis: Long = 300L,
) {
    private val above = ArrayDeque<Pair<Long, Long>>() // (time, chunk length) of loud chunks

    /** When speech was last detected (the time of the chunk). */
    var lastSpeechMillis: Long? = null
        private set

    var speaking: Boolean = false
        private set

    fun onLevel(timeMillis: Long, levelDb: Float, chunkMillis: Long, thresholdDb: Float): Boolean {
        val loud = levelDb >= thresholdDb
        if (loud) above.addLast(timeMillis to chunkMillis)
        while (above.isNotEmpty() && timeMillis - above.first().first > windowMillis) above.removeFirst()
        speaking = above.sumOf { it.second } >= minAboveMillis
        // The silence timer runs from the last loud chunk, not from when the window empties.
        if (speaking && loud) lastSpeechMillis = timeMillis
        return speaking
    }

    fun reset() {
        above.clear()
        speaking = false
        lastSpeechMillis = null
    }
}
