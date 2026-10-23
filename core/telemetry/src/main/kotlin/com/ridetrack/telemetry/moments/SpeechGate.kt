package com.ridetrack.telemetry.moments

/**
 * "The rider is speaking", from how far each audio chunk is above the background (dB) and how
 * voice-like it is (a horn or an engine can be as loud and as long as talking).
 *
 * Starts after [startMillis] of sustained sound: at least [startFraction] of that time at or
 * above the margin, with no gap longer than [maxGapMillis] (words have small gaps; a breath,
 * a cough or a horn tap is too short). Once started, it keeps going while at least
 * [keepFraction] of the last [startMillis] is loud, so slow talking isn't cut off.
 * Pure; fed with each chunk's level above background, and its length.
 */
class SpeechGate(
    private val startMillis: Long = 1_000L,
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

    /** Which rules counted the last chunk as talking (empty = none). */
    var lastRules: Set<Rule> = emptySet()
        private set

    /** How long the sound had been going when speaking last started (for the log). */
    var sustainedMillis: Long = 0
        private set

    /**
     * [voice]: how voice-like the chunk is (0..1, from Silero); 1 when there's no detector.
     * [voiceMin]: how voice-like it must be (lower in wind, see [VoiceInWind]).
     * [liftDb]: with two mics, how much louder the helmet mic is than usual next to the engine
     * mic; wind and traffic reach both, a voice mostly the helmet one ([TwoMicLift]).
     * [highLiftDb]: the same in the voice's crisp band over the last minute ([HighBandLift]): what
     * still hears the rider at speed, where the voice detector reads 0.
     * [liftMinDb], [highMinDb]: how much they must be (the rider's sensitivity).
     */
    fun onLevel(
        timeMillis: Long,
        aboveDb: Float,
        chunkMillis: Long,
        marginDb: Float,
        voice: Float = 1f,
        voiceMin: Float = VOICE_MIN,
        liftDb: Float? = null,
        highLiftDb: Float? = null,
        liftMinDb: Float = VoiceInWind.LIFT_DB,
        highMinDb: Float = HighBandLift.SPEECH_DB,
    ): Boolean {
        lastRules = rules(aboveDb, marginDb, voice, voiceMin, liftDb, highLiftDb, liftMinDb, highMinDb)
        val loud = lastRules.isNotEmpty()
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
            // Measured to the last loud chunk: the short pause after a sound doesn't make it longer.
            start != null && (lastLoud ?: timeMillis) - start >= startMillis && loudMillis >= startFraction * startMillis
        }
        if (speaking && !wasSpeaking && start != null) sustainedMillis = timeMillis - start
        // The silence timer runs from the last loud chunk, not from when the window empties.
        if (speaking && loud) lastSpeechMillis = timeMillis
        return speaking
    }

    /** The ways a chunk counts as talking; any one is enough. */
    enum class Rule(val key: String) {
        /** Loud enough over the background, and voice-like to the voice detector. */
        VOICE("voice"),
        /** Two mics: the helmet mic stands out from the engine mic ([TwoMicLift]). */
        LIFT("lift"),
        /** Two mics: the helmet mic's crisp band stands out over the last minute ([HighBandLift]). */
        HIGH("high"),
    }

    companion object {
        /** Silero's own default: at or above this, the chunk counts as a voice. */
        const val VOICE_MIN = 0.5f

        /**
         * Which rules count a chunk as talking: loud enough and voice-like; or, with two mics, the
         * helmet mic standing out from the engine mic on its own (wind and traffic reach both mics;
         * the voice detector gives up in wind), whole-band or in the crisp band. See [onLevel].
         */
        fun rules(
            aboveDb: Float,
            marginDb: Float,
            voice: Float,
            voiceMin: Float,
            liftDb: Float?,
            highLiftDb: Float?,
            liftMinDb: Float,
            highMinDb: Float,
        ): Set<Rule> = buildSet {
            if (aboveDb >= marginDb && voice >= voiceMin) add(Rule.VOICE)
            if (liftDb != null && liftDb >= liftMinDb) add(Rule.LIFT)
            if (highLiftDb != null && highLiftDb >= highMinDb) add(Rule.HIGH)
        }
    }

    fun reset() {
        recent.clear()
        runStart = null
        lastLoud = null
        speaking = false
        lastSpeechMillis = null
        lastRules = emptySet()
    }
}
