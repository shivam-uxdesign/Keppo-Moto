package com.ridetrack.app.studio

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.ridetrack.app.transcribe.AppCheckSetup
import com.ridetrack.app.transcribe.FirebaseTranscriber
import com.ridetrack.app.transcribe.GeminiQuota
import com.ridetrack.app.transcribe.RemoteModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

/** A story Gemini found in the ride: one idea, told by these bits. */
data class Story(val name: String, val ids: List<String>)

/** What Gemini decided as the Reel's director. */
data class Direction(
    val title: String?,
    val postCaption: String?,
    val hookId: String?,
    val punch: Map<String, Float>,
    /** A few words for the first frame, readable with the sound off. */
    val hookLine: String? = null,
    /** The rider's sign-off, to close the Reel; null if they didn't say one. */
    val endingId: String? = null,
    val stories: List<Story> = emptyList(),
)

/** What a coach tip lets the rider do with one tap. */
enum class TipAction { TITLE_ON_HOOK, SHORTER, VOICE_OVER, OTHER_RIDES, STORY }

/** One "make the next one better" tip; [nextRide] tips go on the shot list for the next ride. */
data class Tip(val text: String, val action: TipAction? = null, val nextRide: Boolean = false)

/**
 * Studio's Gemini calls, through the app's Firebase project (no key for riders):
 * timed captions for a clip (only its sound is sent), and the director (text only).
 */
class StudioGemini(private val preferred: () -> String?, private val onWorking: (String) -> Unit) {

    /** The clip's speech as timed lines; empty when nothing clear was said. */
    suspend fun captions(audio: File, mime: String = "audio/mp4"): List<CaptionLine> {
        val bytes = audio.readBytes()
        val text = call(json = true) { m ->
            m.generateContent(content { inlineData(bytes, mime); text(StudioText.CAPTIONS_PROMPT) }).text.orEmpty()
        }
        return StudioText.parseLines(text) ?: emptyList()
    }

    /** Picks the hook, scores each bit, names the Reel and writes the post caption. */
    suspend fun direct(rideName: String, stats: String, bits: List<Bit>): Direction? {
        val prompt = StudioText.directorPrompt(rideName, stats, bits)
        val text = call(json = true) { m -> m.generateContent(prompt).text.orEmpty() }
        return StudioText.parseDirection(text, bits.map { it.id }.toSet())
    }

    /**
     * Captions for several clips in one request (the free tier allows few requests a day): each
     * clip's lines, in the same order; null for a clip Gemini skipped, empty for no speech.
     */
    suspend fun captionsBatch(audios: List<File>): List<List<CaptionLine>?> {
        if (audios.isEmpty()) return emptyList()
        val parts = audios.map { it.readBytes() }
        val text = call(json = true, models = FirebaseTranscriber.LITE + FirebaseTranscriber.MODELS) { m ->
            m.generateContent(
                content {
                    parts.forEachIndexed { i, bytes ->
                        text("Clip ${i + 1}:")
                        inlineData(bytes, "audio/mp4")
                    }
                    text(StudioText.batchPrompt(parts.size))
                },
            ).text.orEmpty()
        }
        return StudioText.parseBatch(text, audios.size)
    }

    /** Scripts for [prompt] (a content plan, one piece, or a rewrite); text only, clips by their keys. */
    suspend fun scripts(prompt: String, footage: List<Footage>, onAnswer: (String) -> Unit = {}, onUnreadable: (String) -> Unit = {}): List<Script> {
        // Several scripts are a long answer (up to 15 with Show all): give it three minutes.
        val text = call(json = true, temperature = 0.7f, timeoutMs = 180_000) { m -> m.generateContent(prompt).text.orEmpty() }
        onAnswer(text)
        return ScriptWriter.parsePieces(text, footage).also { if (it.isEmpty()) onUnreadable(text) }
    }

    /** A style from the rider's words ("night ride, neon, punchy, big yellow captions"); null if the answer couldn't be read. */
    suspend fun styleFromWords(words: String, id: String): StudioStyle? {
        val text = call(json = true, temperature = 0.6f) { m -> m.generateContent(StyleJson.wordsPrompt(words)).text.orEmpty() }
        return StyleJson.fromGemini(text, id)
    }

    /** A style that approximates a reference video's look and pace, from a few of its frames (JPEG). */
    suspend fun styleFromFrames(frames: List<ByteArray>, id: String): StudioStyle? {
        val text = call(json = true, temperature = 0.4f, timeoutMs = 120_000) { m ->
            m.generateContent(
                content {
                    frames.forEach { inlineData(it, "image/jpeg") }
                    text(StyleJson.REFERENCE_PROMPT)
                },
            ).text.orEmpty()
        }
        return StyleJson.fromGemini(text, id)
    }

    /** What can be seen in each clip, from two frames of each (one request for several clips). */
    suspend fun seen(frames: List<List<ByteArray>>): List<List<String>>? {
        val text = call(json = true, models = FirebaseTranscriber.LITE + FirebaseTranscriber.MODELS, timeoutMs = 90_000) { m ->
            m.generateContent(
                content {
                    frames.forEachIndexed { i, fs ->
                        text("Clip ${i + 1}:")
                        fs.forEach { inlineData(it, "image/jpeg") }
                    }
                    text(ClipSearch.seenPrompt(frames.size))
                },
            ).text.orEmpty()
        }
        return ClipSearch.parseSeen(text, frames.size)
    }

    /** Instagram caption and YouTube title and description for a made video. */
    suspend fun postTexts(title: String, about: String, caption: String): PostTexts? {
        val text = call(json = true, temperature = 0.6f) { m -> m.generateContent(Posting.postPrompt(title, about, caption)).text.orEmpty() }
        return Posting.parsePost(text)
    }

    /** The edit's caption lines in another language, same count and order; null if it couldn't be read. */
    suspend fun translate(lines: List<String>, lang: String): List<String>? {
        val text = call(json = true) { m -> m.generateContent(Posting.translatePrompt(lines, lang)).text.orEmpty() }
        return Posting.parseTranslation(text, lines.size)
    }

    /** Whether the first 3 seconds (three frames, JPEG) would stop the scroll. */
    suspend fun hookCheck(frames: List<ByteArray>, hookLine: String): HookCheck? {
        val text = call(json = true, timeoutMs = 90_000) { m ->
            m.generateContent(
                content {
                    frames.forEach { inlineData(it, "image/jpeg") }
                    text(Posting.hookPrompt(hookLine))
                },
            ).text.orEmpty()
        }
        return Posting.parseHook(text)
    }

    /** A plain answer that needs today's facts: Gemini searches Google first (e.g. the petrol price in a city). */
    suspend fun searched(prompt: String): String =
        call(json = false, temperature = 0f, tools = listOf(com.google.firebase.ai.type.Tool.googleSearch())) { m -> m.generateContent(prompt).text.orEmpty() }

    /** Funny lines for caption clips, by key (one request for many). */
    suspend fun jokes(prompt: String): Map<String, List<String>> {
        val text = call(json = true, temperature = 0.9f) { m -> m.generateContent(prompt).text.orEmpty() }
        return ScriptWriter.parseJokes(text)
    }

    /** Tips for the next Reel, from a plain description of this one (text only). */
    suspend fun coach(summary: String): List<Tip> {
        val text = call(json = true) { m -> m.generateContent(StudioText.coachPrompt(summary)).text.orEmpty() }
        return StudioText.parseTips(text)
    }

    /** Tries the model that last worked, then the known names, then Remote Config's. */
    private suspend fun call(
        json: Boolean,
        models: List<String> = FirebaseTranscriber.MODELS,
        temperature: Float = 0.2f,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        tools: List<com.google.firebase.ai.type.Tool>? = null,
        block: suspend (GenerativeModel) -> String,
    ): String {
        val order = (if (models === FirebaseTranscriber.MODELS) listOfNotNull(preferred()) else emptyList()) + models
        val queue = ArrayDeque(order.distinct())
        val tried = HashSet<String>()
        var remoteTried = false
        var last: Exception? = null
        var busy: FirebaseTranscriber.Busy? = null
        var refreshed = false
        while (true) {
            val name = queue.removeFirstOrNull() ?: if (!remoteTried) {
                remoteTried = true
                RemoteModel.get()?.takeIf { it !in tried } ?: continue
            } else {
                break
            }
            if (!tried.add(name)) continue
            // Over its free limit (Google said until when): don't spend a request finding out again.
            if (GeminiQuota.blocked(name)) {
                val left = ((GeminiQuota.freeAt(listOf(name)) ?: 0L) - System.currentTimeMillis()).coerceAtLeast(1_000) / 1000
                busy = busy ?: FirebaseTranscriber.Busy("Quota exceeded for metric: free_tier_requests, model: $name. Please retry in ${left}s.")
                continue
            }
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = name,
                generationConfig = generationConfig {
                    this.temperature = temperature
                    if (json) responseMimeType = "application/json"
                },
                tools = tools,
            )
            try {
                // A hung request would leave Studio waiting forever.
                val reply = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { block(model) }
                    ?: throw com.ridetrack.app.transcribe.GeminiUnreachable(timedOut = true, java.io.IOException("Gemini didn't answer within ${timeoutMs / 1000} s"))
                return reply.also { onWorking(name) }
            } catch (e: QuotaExceededException) {
                // Each model has its own free allowance: remember this one is spent, try the next.
                GeminiQuota.block(name, e.message)
                busy = FirebaseTranscriber.Busy(e.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not reachable at all (no internet, a blocked address): other models won't help.
                if (com.ridetrack.app.transcribe.GeminiNet.isUnreachable(e)) throw e as? com.ridetrack.app.transcribe.GeminiUnreachable ?: com.ridetrack.app.transcribe.GeminiUnreachable(false, e)
                // Rejected pass: get a fresh one and try this model again, once.
                if (AppCheckSetup.isRejected(e) && !refreshed) {
                    refreshed = true
                    if (AppCheckSetup.refresh()) { tried.remove(name); queue.addFirst(name); continue }
                }
                if (!FirebaseTranscriber.isMissingModel(e)) throw e
                last = e
                FirebaseTranscriber.suggestedModel(e, name)?.takeIf { it !in tried }?.let { queue.addFirst(it) }
            }
        }
        throw busy ?: last ?: IllegalStateException("no Gemini model")
    }
}

private const val REQUEST_TIMEOUT_MS = 60_000L

/** Prompts, and reading Gemini's replies (pure, unit-tested). */
object StudioText {
    val CAPTIONS_PROMPT = """
        This is the sound of a motorcycle helmet camera. Transcribe what the rider says, as timed caption lines.
        The rider speaks English and Hinglish (Hindi mixed with English). Write everything in English letters (Latin script),
        Hindi words the way people text them (for example "bhai ye road mast hai"). Do not translate, never use Devanagari.
        Ignore wind, engine, horn and traffic noise. Split speech into short lines of at most 8 words, at natural pauses.
        Also write the rider's reactions and drawn-out sounds as they sound ("Tooooo", "Aaaahh", "Fhit!", "Nooo"), each as its own line.
        Reply with JSON only: {"lines":[{"start":1.2,"end":3.4,"text":"..."}]} with start and end in seconds from the
        beginning of the recording. If there is no clear speech, reply {"lines":[]}.
    """.trimIndent()

    fun batchPrompt(n: Int): String = """
        These are $n clips of sound from a motorcycle helmet camera, labelled Clip 1 to Clip $n. For EACH clip, transcribe
        what the rider says as timed caption lines, with start and end in seconds from the beginning of THAT clip.
        The rider speaks English and Hinglish (Hindi mixed with English). Write everything in English letters (Latin script),
        Hindi words the way people text them (for example "bhai ye road mast hai"). Do not translate, never use Devanagari.
        Ignore wind, engine, horn and traffic noise. Split speech into short lines of at most 8 words, at natural pauses.
        Also write the rider's reactions and drawn-out sounds as they sound ("Tooooo", "Aaaahh", "Fhit!", "Nooo"), each as its own line.
        Reply with JSON only: {"clips":[{"clip":1,"lines":[{"start":1.2,"end":3.4,"text":"..."}]}]} with one entry per clip;
        a clip with no clear speech has "lines":[].
    """.trimIndent()

    /** Each clip's lines from a batch reply, by position; null where the clip is missing. */
    fun parseBatch(reply: String, n: Int): List<List<CaptionLine>?> {
        val out = MutableList<List<CaptionLine>?>(n) { null }
        runCatching {
            val arr = JSONObject(jsonPart(reply)).optJSONArray("clips") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = (o.optInt("clip", i + 1) - 1).takeIf { it in 0 until n } ?: continue
                out[k] = parseLines(o.toString())
            }
        }
        return out
    }

    fun directorPrompt(rideName: String, stats: String, bits: List<Bit>): String = buildString {
        appendLine("You are editing a 30–60 second Instagram Reel of a motorcycle ride: \"$rideName\" ($stats).")
        appendLine("These are the usable moments, in ride order. Each has an id, the time, the speed, and what the rider said (may be empty):")
        bits.forEach { b ->
            val said = b.lines.joinToString(" ") { it.text }.replace("\"", "'")
            appendLine("- id ${b.id} · ${b.speedKmh.roundToLong()} km/h · ${(b.outMs - b.inMs) / 1000} s · \"$said\"")
        }
        appendLine("Instagram rewards Reels that hold viewers in the first 3 seconds, are watched to the end, and get sent to friends.")
        appendLine("Score every moment 0–10 for how well it would hold a viewer (funny, energetic, relatable or a good line scores high; a blooper is great).")
        appendLine("Pick the best opening moment (the hook). Write a hook line for the first frame: max 6 words, readable with the sound off,")
        appendLine("in the rider's language (Hinglish in English letters is fine), a tease or a question, not a summary.")
        appendLine("Find up to 3 stories: each is ONE idea (a running joke, a problem, a feeling) told by 2–6 of the moments, in ride order.")
        appendLine("If the rider says a sign-off or closing line, give its id as the ending.")
        appendLine("Give the Reel a short title (max 4 words) and write a short post caption")
        appendLine("(1–2 lines in the rider's voice, Hinglish in English letters is fine, then 3–5 hashtags). At most one emoji.")
        append("Reply with JSON only: {\"title\":\"...\",\"caption\":\"...\",\"hook\":\"id\",\"hookLine\":\"...\",\"ending\":\"id or empty\",")
        append("\"stories\":[{\"name\":\"the hydration debate\",\"ids\":[\"id\"]}],\"scores\":{\"id\":7}}")
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
        val stories = o.optJSONArray("stories")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val st = arr.optJSONObject(i) ?: return@mapNotNull null
                val sid = st.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it in ids } }.orEmpty()
                val name = st.optString("name").trim()
                if (sid.size < 2 || name.isEmpty()) null else Story(name.take(60), sid)
            }
        }.orEmpty().take(3)
        Direction(
            title = o.optString("title").trim().takeIf { it.isNotEmpty() }?.take(40),
            postCaption = o.optString("caption").trim().takeIf { it.isNotEmpty() },
            hookId = o.optString("hook").trim().takeIf { it in ids },
            punch = punch,
            hookLine = o.optString("hookLine").trim().trim('"').takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.take(8)?.joinToString(" "),
            endingId = o.optString("ending").trim().takeIf { it in ids },
            stories = stories,
        )
    }.getOrNull()

    fun coachPrompt(summary: String): String = buildString {
        appendLine("You are a social media manager for a motorcycle rider who posts Instagram Reels (motovlogs, often Hinglish).")
        appendLine("Here is the Reel the app just made from their ride:")
        appendLine(summary)
        appendLine("What wins on Reels: a hook in the first 3 seconds; text on screen (most watch muted); one idea per Reel; finishing and")
        appendLine("rewatching (short, looping endings); moments people send to friends (funny, relatable); variety of shots every 2–3 s; raw and real.")
        appendLine("Give 3 to 5 short, specific tips (max 25 words each) on what would have made THIS Reel better or what to film on the next ride.")
        appendLine("Be concrete and friendly; quote their words when useful. Mark tips about filming on the next ride with nextRide true.")
        appendLine("When a tip can be done in the app now, set action to one of: title_on_hook, shorter, voice_over, other_rides, story; else none.")
        append("Reply with JSON only: {\"tips\":[{\"text\":\"...\",\"action\":\"none\",\"nextRide\":false}]}")
    }

    fun parseTips(reply: String): List<Tip> = runCatching {
        val arr = JSONObject(jsonPart(reply)).optJSONArray("tips") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            val action = when (o.optString("action").trim().lowercase()) {
                "title_on_hook" -> TipAction.TITLE_ON_HOOK
                "shorter" -> TipAction.SHORTER
                "voice_over" -> TipAction.VOICE_OVER
                "other_rides" -> TipAction.OTHER_RIDES
                "story" -> TipAction.STORY
                else -> null
            }
            Tip(text.take(200), action, o.optBoolean("nextRide", false))
        }.take(5)
    }.getOrDefault(emptyList())

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
