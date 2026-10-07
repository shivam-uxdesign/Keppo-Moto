package com.ridetrack.app.transcribe

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.io.IOException

/**
 * Speech to text with Gemini through Firebase AI Logic (the Gemini Developer API, free tier, on
 * the app's own Firebase project, guarded by App Check): riders need no key. Only the clip's
 * sound is sent. Hinglish comes back in English letters, as it's texted.
 */
class FirebaseTranscriber {
    /** Too many requests for the free tier right now: try again later. */
    class Busy(message: String?) : IOException(message)

    /** Transcript of [audio] ([mime], e.g. audio/mp4) with [modelName]; "" when there's no clear speech. */
    suspend fun transcribe(modelName: String, audio: ByteArray, mime: String): String {
        val model = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(modelName = modelName, generationConfig = generationConfig { temperature = 0f })
        val response = try {
            model.generateContent(
                content {
                    inlineData(audio, mime)
                    text(TranscriptText.PROMPT)
                },
            )
        } catch (e: QuotaExceededException) {
            throw Busy(e.message)
        }
        return TranscriptText.clean(response.text.orEmpty())
    }

    companion object {
        /** Newest first; a name Google has retired is skipped (its error says so) and the next tried. */
        // Names as on Google's pricing page (Oct 2026); a name Google drops is skipped, and Remote
        // Config's `gemini_model` is the last resort.
        val MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3-flash-preview", "gemini-3.5-flash-lite", "gemini-2.5-flash")

        /** The error means this model name can't be used (gone, retired, or closed to new projects). */
        fun isMissingModel(e: Throwable): Boolean {
            val m = text(e).lowercase()
            return listOf("not found", "404", "is not supported", "unknown model", "no longer available", "deprecated", "retired", "update your code")
                .any { it in m }
        }

        /** The model Google's error says to use instead ("… use models/gemini-3.8-flash …"), if any. */
        fun suggestedModel(e: Throwable, current: String): String? =
            Regex("models/(gemini-[a-z0-9.\\-]+)").findAll(text(e)).map { it.groupValues[1].trimEnd('.', '-') }.firstOrNull { it != current }

        /** The free tier's daily allowance is used up (it resets at midnight Pacific, about 12:30 PM in India). */
        fun isDailyLimit(e: Throwable): Boolean = isDailyText(text(e))

        fun isDailyText(t: String): Boolean =
            Regex("per ?day|PerDay|daily|free_tier_requests", RegexOption.IGNORE_CASE).containsMatchIn(t) || (GeminiQuota.retryAfterMs(t) ?: 0) > 3_600_000

        /** What to tell the rider when every model is busy, with when it frees up. */
        fun busyMessage(e: Throwable): String {
            val wait = GeminiQuota.retryAfterMs(text(e))?.let { GeminiQuota.waitText(System.currentTimeMillis() + it) }
            return if (isDailyLimit(e)) "Gemini's free daily limit is used up${wait?.let { "; it frees up in $it" } ?: " (it resets around 12:30 PM India time)"}. Clips wait until then."
            else "Gemini is busy (free-tier limit per minute). Trying again${wait?.let { " in $it" } ?: " in a few minutes"}."
        }

        /** Models with a bigger free allowance, tried first for captions (names that don't exist are skipped). */
        val LITE = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

        private fun text(e: Throwable) = "${e.message} ${e.cause?.message}"
    }
}

/** The instructions sent with each clip, and how the reply is stored. */
object TranscriptText {
    const val NO_SPEECH = "[no speech]"

    val PROMPT = """
        Transcribe the rider's speech in this motorcycle helmet recording, exactly as spoken.
        The rider speaks English and Hinglish (Hindi mixed with English). Write everything in English letters (Latin script):
        write Hindi words the way people text them, for example "bhai ye road mast hai, aage traffic hai". Do not translate, and never use Devanagari.
        Ignore wind, engine, horn and traffic noise. If there is no clear speech, reply exactly: $NO_SPEECH
        Reply with only the transcript.
    """.trimIndent()

    /** The model's reply as stored: "" for no speech; no quotes or "Transcript:" around it. */
    fun clean(text: String): String {
        val t = text.trim().removePrefix("Transcript:").trim().trim('"', '“', '”').trim()
        return if (t.isEmpty() || t.contains(NO_SPEECH, ignoreCase = true) && t.length < 30) "" else t
    }
}
