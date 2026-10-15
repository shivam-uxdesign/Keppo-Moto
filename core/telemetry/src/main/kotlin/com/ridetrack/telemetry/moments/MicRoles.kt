package com.ridetrack.telemetry.moments

/**
 * Which receiver side really carries the voice, from the voice detector run on both sides.
 * Talking shows up as voice-like on the helmet mic and hardly on the back-seat mic, so when the
 * "Engine" side keeps hearing a voice the "Voice" side doesn't, the two are set the wrong way
 * round (the helmet transmitter is on the other channel). The app never swaps them by itself
 * (#22); it says so, and the rider swaps them in Settings. Pure; fed with each chunk.
 */
class MicRoleCheck(
    private val needMillis: Long = 4_000L,
    private val ratio: Double = 3.0,
) {
    enum class Verdict { UNKNOWN, RIGHT, SWAPPED }

    /** Time a voice was heard on the Voice side only, and on the Engine side only. */
    var onVoiceMillis: Long = 0
        private set
    var onEngineMillis: Long = 0
        private set

    /** [voice] and [engine]: each side's voice probability (0..1) for a chunk [chunkMillis] long. */
    fun onChunk(chunkMillis: Long, voice: Float, engine: Float): Verdict {
        if (voice >= SURE && engine < UNSURE) onVoiceMillis += chunkMillis
        if (engine >= SURE && voice < UNSURE) onEngineMillis += chunkMillis
        return verdict
    }

    val verdict: Verdict
        get() = when {
            onEngineMillis >= needMillis && onEngineMillis >= ratio * onVoiceMillis -> Verdict.SWAPPED
            onVoiceMillis >= needMillis && onVoiceMillis >= ratio * onEngineMillis -> Verdict.RIGHT
            else -> Verdict.UNKNOWN
        }

    fun reset() {
        onVoiceMillis = 0
        onEngineMillis = 0
    }

    private companion object {
        const val SURE = 0.6f
        const val UNSURE = 0.3f
    }
}

/**
 * A safety net for "the helmet mic stands out from the engine mic counts as speaking" (V1):
 * that needs no voice detector, so with the mics set the wrong way round every throttle opening
 * looks like talking, and a clip can run on and on. When speech has gone on for [maxMillis]
 * without a single voice-like chunk, the lift is distrusted for [offMillis]; speech then needs
 * the voice detector as usual. Pure.
 */
class LiftGuard(
    private val maxMillis: Long = 30_000L,
    private val offMillis: Long = 5 * 60_000L,
) {
    private var speechStart: Long? = null
    private var lastSpeaking: Long? = null
    private var lastVoiceLike: Long? = null
    private var offUntil: Long = Long.MIN_VALUE

    /** True when the lift may count at [timeMillis]. */
    fun allowed(timeMillis: Long): Boolean = timeMillis >= offUntil

    /**
     * Each chunk, after the speech gate: [speaking] now, and [voice] (the detector's 0..1).
     * Returns true when this chunk trips the guard (for the log and the pop-up).
     */
    fun onChunk(timeMillis: Long, voice: Float, speaking: Boolean): Boolean {
        if (voice >= VOICE_LIKE) lastVoiceLike = timeMillis
        if (!speaking) {
            // Short lulls between revs don't end it: the clip runs on through them anyway.
            val last = lastSpeaking
            if (last != null && timeMillis - last > GAP_MILLIS) {
                speechStart = null
                lastSpeaking = null
            }
            return false
        }
        if (speechStart == null) speechStart = timeMillis
        lastSpeaking = timeMillis
        val since = maxOf(speechStart ?: timeMillis, lastVoiceLike ?: Long.MIN_VALUE)
        if (allowed(timeMillis) && timeMillis - since >= maxMillis) {
            offUntil = timeMillis + offMillis
            return true
        }
        return false
    }

    fun reset() {
        speechStart = null
        lastSpeaking = null
        lastVoiceLike = null
        offUntil = Long.MIN_VALUE
    }

    private companion object {
        /** The detector's bar in heavy wind (VoiceInWind): anything above it is a voice somewhere in there. */
        const val VOICE_LIKE = 0.35f
        /** Longer than a clip runs on after the last speech (5 s): a pause this long ends it. */
        const val GAP_MILLIS = 10_000L
    }
}
