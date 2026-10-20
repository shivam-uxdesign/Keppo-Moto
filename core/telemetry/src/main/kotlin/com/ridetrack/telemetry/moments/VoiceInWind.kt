package com.ridetrack.telemetry.moments

/**
 * Talking at speed. Wind fills the voice range, so the background rises close to the voice and
 * even a shout is only a few dB above it. As the background rises the margin needed comes
 * down (to a third, at least 3 dB), and the voice detector, whose confidence drops in wind,
 * is asked for a little less. Wind alone still doesn't count: it isn't voice-like. Pure.
 */
object VoiceInWind {
    /** At or below this background (dBFS, voice range), no wind: settings apply as they are. */
    const val CALM_DB = -45f
    /** At or above this, heavy wind: the lowest margin and voice bar. */
    const val WINDY_DB = -25f
    /** Two mics: the helmet mic this much louder than usual next to the engine mic is a voice. */
    const val LIFT_DB = 6f

    /** 0 (calm) .. 1 (heavy wind), from the background level. */
    fun wind(backgroundDb: Float): Float = ((backgroundDb - CALM_DB) / (WINDY_DB - CALM_DB)).coerceIn(0f, 1f)

    /** The margin above the background needed in this wind. */
    fun margin(baseDb: Float, wind: Float): Float {
        val lowest = maxOf(3f, baseDb * 0.35f).coerceAtMost(baseDb)
        return baseDb - (baseDb - lowest) * wind.coerceIn(0f, 1f)
    }

    /** How voice-like a chunk must be in this wind (0.5 calm, 0.35 in heavy wind). */
    fun voiceMin(wind: Float): Float = SpeechGate.VOICE_MIN - 0.15f * wind.coerceIn(0f, 1f)
}

/**
 * Two mics: how much louder the helmet (voice) mic is than usual next to the engine mic.
 * "Usual" is a low percentile of the difference over the last few seconds, so wind, engine and
 * horns, which reach both mics, cancel out; talking lifts the helmet mic alone. Pure.
 */
class TwoMicLift(windowMillis: Long = 8_000L) {
    private val usual = BackgroundLevel(windowMillis, percentile = 0.2)

    /** One chunk's levels (dBFS); returns the lift above usual (dB). */
    fun onLevels(timeMillis: Long, voiceDb: Float, engineDb: Float): Float {
        val diff = voiceDb - engineDb
        return diff - usual.onLevel(timeMillis, diff)
    }

    fun reset() = usual.reset()
}

/**
 * Two mics, talking at speed: how much louder the helmet mic is than usual next to the engine
 * mic in the voice's crisp band (1–4 kHz). Wind and engine reach both mics there about equally;
 * a voice reaches the helmet mic alone. "Usual" is the 10th percentile of the last minute, so a
 * rider who talks most of the time doesn't teach it their voice; the difference is averaged over
 * [smoothMillis] first. Measured on the 20 Oct ride (16–100 km/h, the rider's own marks): talking
 * +8 dB (median), silence +2; where the voice detector read 0.00 throughout. At 5 dB it caught
 * all 10 talking stretches with one false start in 4 minutes; [SPEECH_DB] is lower because the
 * rider would rather have a few false starts than miss talking (about 5 in those 4 minutes,
 * and talk counted sooner and held longer). Pure.
 */
class HighBandLift(windowMillis: Long = 60_000L, private val smoothMillis: Long = 100L) {
    private val usual = BackgroundLevel(windowMillis, percentile = 0.1)
    private val recent = ArrayDeque<Pair<Long, Float>>()

    /** One chunk's 1–4 kHz levels (dBFS); returns the lift above usual (dB). */
    fun onLevels(timeMillis: Long, voiceDb: Float, engineDb: Float): Float {
        recent.addLast(timeMillis to (voiceDb - engineDb))
        while (timeMillis - recent.first().first > smoothMillis) recent.removeFirst()
        val diff = recent.map { it.second }.average().toFloat()
        return diff - usual.onLevel(timeMillis, diff)
    }

    fun reset() {
        recent.clear()
        usual.reset()
    }

    companion object {
        /** At or above this lift the chunk counts as talking (missing talk is worse than a false start). */
        const val SPEECH_DB = 3f
    }
}
