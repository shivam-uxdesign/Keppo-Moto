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
        val MODELS = listOf("gemini-3-flash", "gemini-2.5-flash", "gemini-2.0-flash")

        /** The error means this model name doesn't exist (any more) for this project. */
        fun isMissingModel(e: Throwable): Boolean {
            val m = (e.message + " " + e.cause?.message).lowercase()
            return "not found" in m || "404" in m || "is not supported" in m || "unknown model" in m
        }
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
