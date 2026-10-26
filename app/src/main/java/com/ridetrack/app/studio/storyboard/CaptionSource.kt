package com.ridetrack.app.studio.storyboard

/** Whose words a voice-over's captions show: the script's, or what the app heard in the take. */
enum class CaptionSource(val label: String) {
    /** The line exactly as written in the storyboard (spelling and Hinglish as typed), timed to the take. */
    WRITTEN("Written"),
    /** What Gemini heard in the take; the written line when it can't be heard. */
    SAID("What I said"),
    ;

    companion object {
        fun of(name: String?): CaptionSource? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}
