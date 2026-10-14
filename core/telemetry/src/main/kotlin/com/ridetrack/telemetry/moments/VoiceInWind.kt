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
