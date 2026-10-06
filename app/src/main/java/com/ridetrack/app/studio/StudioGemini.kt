package com.ridetrack.app.studio

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.ridetrack.app.transcribe.FirebaseTranscriber
import com.ridetrack.app.transcribe.RemoteModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

/** What Gemini decided as the Reel's director. */
data class Direction(val title: String?, val postCaption: String?, val hookId: String?, val punch: Map<String, Float>)

/**
 * Studio's Gemini calls, through the app's Firebase project (no key for riders):
 * timed captions for a clip (only its sound is sent), and the director (text only).
 */
class StudioGemini(private val preferred: () -> String?, private val onWorking: (String) -> Unit) {

    /** The clip's speech as timed lines; empty when nothing clear was said. */
    suspend fun captions(audio: File): List<CaptionLine> {
        val bytes = audio.readBytes()
        val text = call(json = true) { m ->
            m.generateContent(content { inlineData(bytes, "audio/mp4"); text(StudioText.CAPTIONS_PROMPT) }).text.orEmpty()
        }
        return StudioText.parseLines(text) ?: emptyList()
    }

    /** Picks the hook, scores each bit, names the Reel and writes the post caption. */
    suspend fun direct(rideName: String, stats: String, bits: List<Bit>): Direction? {
        val prompt = StudioText.directorPrompt(rideName, stats, bits)
        val text = call(json = true) { m -> m.generateContent(prompt).text.orEmpty() }
        return StudioText.parseDirection(text, bits.map { it.id }.toSet())
    }

    /** Tries the model that last worked, then the known names, then Remote Config's. */
    private suspend fun call(json: Boolean, block: suspend (GenerativeModel) -> String): String {
        val queue = ArrayDeque(listOfNotNull(preferred()) + FirebaseTranscriber.MODELS.filter { it != preferred() })
        val tried = HashSet<String>()
        var remoteTried = false
        var last: Exception? = null
        while (true) {
            val name = queue.removeFirstOrNull() ?: if (!remoteTried) {
                remoteTried = true
                RemoteModel.get()?.takeIf { it !in tried } ?: continue
            } else {
                break
            }
            if (!tried.add(name)) continue
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = name,
                generationConfig = generationConfig {
                    temperature = 0.2f
                    if (json) responseMimeType = "application/json"
                },
            )
            try {
                return block(model).also { onWorking(name) }
            } catch (e: QuotaExceededException) {
                throw FirebaseTranscriber.Busy(e.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!FirebaseTranscriber.isMissingModel(e)) throw e
                last = e
                FirebaseTranscriber.suggestedModel(e, name)?.takeIf { it !in tried }?.let { queue.addFirst(it) }
            }
        }
        throw last ?: IllegalStateException("no Gemini model")
    }
}

/** Prompts, and reading Gemini's replies (pure, unit-tested). */
object StudioText {
    val CAPTIONS_PROMPT = """
        This is the sound of a motorcycle helmet camera. Transcribe what the rider says, as timed caption lines.
        The rider speaks English and Hinglish (Hindi mixed with English). Write everything in English letters (Latin script),
        Hindi words the way people text them (for example "bhai ye road mast hai"). Do not translate, never use Devanagari.
        Ignore wind, engine, horn and traffic noise. Split speech into short lines of at most 8 words, at natural pauses.
        Reply with JSON only: {"lines":[{"start":1.2,"end":3.4,"text":"..."}]} with start and end in seconds from the
        beginning of the recording. If there is no clear speech, reply {"lines":[]}.
    """.trimIndent()

    fun directorPrompt(rideName: String, stats: String, bits: List<Bit>): String = buildString {
        appendLine("You are editing a 30–60 second Instagram Reel of a motorcycle ride: \"$rideName\" ($stats).")
        appendLine("These are the usable moments, in ride order. Each has an id, the time, the speed, and what the rider said (may be empty):")
        bits.forEach { b ->
            val said = b.lines.joinToString(" ") { it.text }.replace("\"", "'")
            appendLine("- id ${b.id} · ${b.speedKmh.roundToLong()} km/h · ${(b.outMs - b.inMs) / 1000} s · \"$said\"")
        }
        appendLine("Score every moment 0–10 for how well it would hold a viewer (funny, energetic, interesting or a good line scores high).")
        appendLine("Pick the best opening moment (the hook). Give the Reel a short title (max 4 words) and write a short post caption")
        appendLine("(1–2 lines in the rider's voice, Hinglish in English letters is fine, then 3–5 hashtags). No emojis other than at most one.")
        append("Reply with JSON only: {\"title\":\"...\",\"caption\":\"...\",\"hook\":\"id\",\"scores\":{\"id\":7}}")
    }

    /** Timed lines from Gemini's reply (ms), or null if it isn't the expected JSON. */
    fun parseLines(reply: String): List<CaptionLine>? = runCatching {
        val arr = JSONObject(jsonPart(reply)).optJSONArray("lines") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").trim().trim('"')
            val a = seconds(o.opt("start"))
            val b = seconds(o.opt("end"))
            if (text.isEmpty() || a == null || b == null || b <= a) null else CaptionLine((a * 1000).roundToLong(), (b * 1000).roundToLong(), text)
        }.sortedBy { it.startMs }
    }.getOrNull()

    fun parseDirection(reply: String, ids: Set<String>): Direction? = runCatching {
        val o = JSONObject(jsonPart(reply))
        val scores = o.optJSONObject("scores")
        val punch = HashMap<String, Float>()
        scores?.keys()?.forEach { k -> if (k in ids) punch[k] = scores.optDouble(k, Double.NaN).toFloat().takeIf { !it.isNaN() }?.coerceIn(0f, 10f) ?: return@forEach }
        Direction(
            title = o.optString("title").trim().takeIf { it.isNotEmpty() }?.take(40),
            postCaption = o.optString("caption").trim().takeIf { it.isNotEmpty() },
            hookId = o.optString("hook").trim().takeIf { it in ids },
            punch = punch,
        )
    }.getOrNull()

    /** "12.5", 12.5 or "0:12.5" → seconds. */
    private fun seconds(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().let { s ->
            if (':' in s) s.split(':').fold(0.0) { acc, p -> acc * 60 + (p.toDoubleOrNull() ?: return null) } else s.toDoubleOrNull()
        }
        else -> null
    }

    /** The JSON object inside a reply that may be wrapped in ``` fences. */
    private fun jsonPart(reply: String): String {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        return if (a >= 0 && b > a) reply.substring(a, b + 1) else reply
    }

    // ---- saved captions --------------------------------------------------------------------

    /** Captions saved next to the clip; a saved empty list means "no speech", so it isn't asked again. */
    fun linesFile(clip: File) = File(clip.parentFile, "${clip.name}.lines.json")

    fun save(clip: File, lines: List<CaptionLine>) {
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().put("start", it.startMs / 1000.0).put("end", it.endMs / 1000.0).put("text", it.text)) }
        linesFile(clip).writeText(JSONObject().put("lines", arr).toString())
    }

    fun load(clip: File): List<CaptionLine>? = linesFile(clip).takeIf { it.isFile }?.let { parseLines(it.readText()) }
}
