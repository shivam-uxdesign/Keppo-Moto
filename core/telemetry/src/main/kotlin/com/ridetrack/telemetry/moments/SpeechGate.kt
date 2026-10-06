package com.ridetrack.telemetry.moments

/**
 * "The rider is speaking", from how far each audio chunk is above the background (dB).
 *
 * Starts after [startMillis] of sustained sound: at least [startFraction] of that time at or
 * above the margin, with no gap longer than [maxGapMillis] (words have small gaps; a breath,
 * a cough or a horn tap is too short). Once started, it keeps going while at least
 * [keepFraction] of the last [startMillis] is loud, so slow talking isn't cut off.
 * Pure; fed with each chunk's level above background, and its length.
 */
class SpeechGate(
    private val startMillis: Long = 1_500L,
    private val startFraction: Double = 0.6,
    private val maxGapMillis: Long = 250L,
    private val keepFraction: Double = 0.15,
) {
    private val recent = ArrayDeque<Triple<Long, Long, Boolean>>() // (end time, length, loud)
    private var runStart: Long? = null
    private var lastLoud: Long? = null

    /** When speech was last heard (the time of the chunk). */
    var lastSpeechMillis: Long? = null
        private set

    var speaking: Boolean = false
        private set

    /** How long the sound had been going when speaking last started (for the log). */
    var sustainedMillis: Long = 0
        private set

    fun onLevel(timeMillis: Long, aboveDb: Float, chunkMillis: Long, marginDb: Float): Boolean {
        val loud = aboveDb >= marginDb
        recent.addLast(Triple(timeMillis, chunkMillis, loud))
        while (recent.isNotEmpty() && timeMillis - recent.first().first >= startMillis) recent.removeFirst()
        val last = lastLoud
        if (loud) {
            if (runStart == null || (last != null && timeMillis - chunkMillis - last > maxGapMillis)) runStart = timeMillis - chunkMillis
            lastLoud = timeMillis
        } else if (last != null && timeMillis - last > maxGapMillis) {
            runStart = null
        }
        val loudMillis = recent.filter { it.third }.sumOf { it.second }
        val wasSpeaking = speaking
        val start = runStart
        speaking = if (wasSpeaking) {
            loudMillis >= keepFraction * startMillis
        } else {
            start != null && timeMillis - start >= startMillis && loudMillis >= startFraction * startMillis
        }
        if (speaking && !wasSpeaking && start != null) sustainedMillis = timeMillis - start
        // The silence timer runs from the last loud chunk, not from when the window empties.
        if (speaking && loud) lastSpeechMillis = timeMillis
        return speaking
    }

    fun reset() {
        recent.clear()
        runStart = null
        lastLoud = null
        speaking = false
        lastSpeechMillis = null
    }
}
