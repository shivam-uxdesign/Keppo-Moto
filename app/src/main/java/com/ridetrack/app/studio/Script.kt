package com.ridetrack.app.studio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** What a piece is made for: each has its own length range and is exported the same way (9:16). */
enum class PieceFormat(val label: String, val minSec: Int, val maxSec: Int, val hint: String) {
    REEL("Reel", 6, 90, "Instagram Reel"),
    SHORT("Short", 6, 60, "YouTube Short"),
    STORY("Story", 5, 60, "Instagram Story: casual, of the day"),
    LONG("Long video", 120, 300, "YouTube video, 2–5 min, chapters"),
    CAPTION("Caption clip", 12, 25, "ONE uncut riding shot of 15–20 s (accelerating, leaning, a fast or pretty stretch) with funny or relatable text on screen; no talking needed");

    companion object {
        fun of(name: String?): PieceFormat = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: REEL
    }
}

/** The parts a piece is told in; each has a job, and several forms Gemini picks from. */
enum class SectionKind(val label: String) {
    HOOK("Hook"), SETUP("Setup"), BUILD("Build"), PEAK("Peak"), TURN("Turn"), PAYOFF("Payoff"), ENDING("Ending");

    companion object {
        fun of(name: String?): SectionKind? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/** A part of one clip: [clip] is its moment id (or phone video id); times are ms into the clip. */
data class ScriptShot(val clip: String, val inMs: Long, val outMs: Long) {
    val durMs: Long get() = outMs - inMs
}

/** One section: its [form] ("sound", "monologue", "loop", "stats"…), shots, on-screen text and why. */
data class Section(val kind: SectionKind, val form: String, val shots: List<ScriptShot>, val text: String? = null, val why: String? = null)

/** A piece's script, as Gemini (or the app's own fallback) wrote it. */
data class Script(
    val format: PieceFormat,
    val title: String,
    val why: String?,
    val lengthSec: Int,
    val shape: String,
    val hookLine: String?,
    val postCaption: String?,
    val sections: List<Section>,
    /** The look Gemini chose for it; null = the rider's current one. */
    val vibe: Vibe? = null,
    /** One of the rider's styles Gemini chose for it (its id); null = the vibe only. */
    val style: String? = null,
    /** The story it tells, beginning to end, in a few sentences; null for older scripts. */
    val story: String? = null,
    /** Captions the rider changed: [captionKey] → new text ("" hides that caption). */
    val captions: Map<String, String> = emptyMap(),
    /** Text written on the video, shown in turn (a caption clip's 1–3 funny lines); empty for most pieces. */
    val onScreen: List<String> = emptyList(),
) {
    val totalMs: Long get() = sections.sumOf { s -> s.shots.sumOf { it.durMs } }
}

/** One clip as the script writer sees it. [key] is the short name used in prompts ("c3"). */
data class Footage(
    val key: String,
    val momentId: String,
    val durationMs: Long,
    val startMillis: Long,
    /** "selfie", "road" (back camera), "phone" (gallery video), "other ride". */
    val look: String,
    val lines: List<CaptionLine>,
    val kmhMax: Int,
    val events: String = "",
    val label: String = "",
) {
    val talkingMs: Long get() = lines.sumOf { it.endMs - it.startMs }
}

/** What the ride has, in words, so the rider (and Gemini) know how much there is to work with. */
data class ContentSummary(val clips: Int, val talkingSec: Int, val sounds: Int, val looks: Int, val talkingClips: Int = 0) {
    /** The longest Reel this footage fills well. */
    val goodLengthSec: Int get() = when {
        talkingSec >= 45 || (talkingSec >= 25 && looks >= 2) -> 60
        talkingSec >= 25 -> 30
        talkingSec >= 10 || sounds > 0 -> 15
        else -> 15
    }
    val text: String get() = buildString {
        append("$clips clip${if (clips == 1) "" else "s"}")
        // Minutes past a minute ("4 min 43 s"), and in how many clips you talk.
        val said = if (talkingSec >= 60) "${talkingSec / 60} min ${talkingSec % 60} s" else "$talkingSec s"
        append(" · $said of you talking")
        if (talkingClips > 0) append(" in $talkingClips")
        if (sounds > 0) append(" · $sounds reaction${if (sounds == 1) "" else "s"}")
        append(". Enough for ")
        append(
            when {
                talkingSec >= 180 -> "several Reels and Shorts"
                goodLengthSec >= 60 -> "a long Reel or several short ones"
                else -> "a ${goodLengthSec} s Reel"
            },
        )
        append(".")
    }
}

/** Gemini's script prompts and replies, and turning a script into a video plan safely. Pure, unit-tested. */
object ScriptWriter {
    /** A drawn-out sound or a one-word reaction: "Tooooo", "Aaaahh", "Fhit!", "No…". */
    private val SOUND = Regex("^\\W*(\\p{L})\\1{2,}|(\\p{L})\\2{3,}|^\\W*(fhit|fit|shit|no+|oh+|wow+|whoa+|arre+|ayy+|yay+|ha(ha)+|oops|damn|bhai+)\\W*$", RegexOption.IGNORE_CASE)

    fun isSound(text: String): Boolean = text.trim().split(Regex("\\s+")).size <= 2 && SOUND.containsMatchIn(text.trim())

    fun summary(footage: List<Footage>): ContentSummary = ContentSummary(
        clips = footage.size,
        talkingSec = (footage.sumOf { it.talkingMs } / 1000).toInt(),
        sounds = footage.sumOf { f -> f.lines.count { isSound(it.text) } },
        looks = footage.map { it.look }.distinct().size,
        talkingClips = footage.count { it.lines.isNotEmpty() },
    )

    // ---- prompts -----------------------------------------------------------------------------

    private val CRAFT = """
        HOW TO WRITE IT
        A piece is told in sections. Each section has a job and you pick its FORM and LENGTH from what was really filmed:
        - HOOK (0.5–8 s, stop the scroll): form "sound" (a reaction like "Tooooo", "Aaaahh", "Fhit", "No…", under 1.5 s),
          "line" (a line that raises a question), "monologue" (5–8 s when the opening is the story), "picture" (the fastest or
          prettiest shot with a text question), "flash" (1–2 s of the peak, then the story from the start), "number" (a speed on screen).
        - SETUP (0–8 s, where and why; often skipped in 15 s): "line", "text" (text over the road), "none".
        - BUILD (0–15 s): "montage" (3–5 quick shots, ONLY if they look different), "road" (one long calm shot 6–12 s),
          "conversation" (2–3 of the rider's lines in a row), "speed" (shots getting faster). Skip it when footage is thin. Never pad.
        - PEAK (2–12 s, what the piece is about): "take" (one long uncut clip: a story, a reaction), "reaction", "event" (with its numbers).
        - TURN (optional, 1–5 s): "contradiction", "surprise", "blooper". Only if it's really in the footage.
        - PAYOFF (1–6 s): "line" (the line that answers the hook), "reaction", "result" (text like "Made it · 2 h 40 min").
        - ENDING (0.5–3 s): "loop" (back into the hook), "stats", "signoff" (a closing line), "question" (text for comments), "cut".
        Sections are optional and their order can change. Pick an overall SHAPE: "story" (hook, setup, build, peak, payoff),
        "cold_open" (peak first, then from the start), "one_take" (hook + one long clip), "problem_solution", "reactions",
        "mood" (long road shots + 2–3 lines or text), "list" ("3 things…"), "chapters" (long videos).
        RULES
        - Mix shot lengths to the moment: quick 1–2 s, medium 3–6 s, long 8–12 s. One 10–12 s shot in a 30 s piece is good.
        - Never cut a sentence: a shot that has speech starts before the first word and ends after the last word of a sentence.
        - Clips that look the same (same "look") must not be cut fast: use fewer, longer shots of them.
        - Reaction sounds make great hooks; look for them even when the words are unclear.
        - Use the rider's exact words; on-screen text is max 6 words, in their language (Hinglish in English letters is fine).
        - The length is a budget, not a target: if the footage can't fill it well, make it shorter.
        - Don't use the same seconds of a clip twice (except a "flash" hook).
    """.trimIndent()

    private fun footageText(footage: List<Footage>): String = buildString {
        appendLine("FOOTAGE (each clip: key, length, look, top speed, events, then what was said with times in seconds from the clip's start)")
        footage.forEach { f ->
            append("- ${f.key} · ${"%.1f".format(java.util.Locale.US, f.durationMs / 1000.0)} s · ${f.look}")
            if (f.kmhMax > 0) append(" · up to ${f.kmhMax} km/h")
            if (f.events.isNotBlank()) append(" · ${f.events}")
            if (f.label.isNotBlank()) append(" · ${f.label}")
            appendLine()
            f.lines.forEach { l ->
                appendLine("    ${sec(l.startMs)}–${sec(l.endMs)} \"${l.text.replace("\"", "'")}\"${if (isSound(l.text)) " [sound]" else ""}")
            }
        }
    }

    private fun sec(ms: Long) = "%.1f".format(java.util.Locale.US, ms / 1000.0)

    private fun styleText(style: StyleContext?): String = buildString {
        if (style == null || (style.rules.isEmpty() && style.examples.isEmpty())) return@buildString
        appendLine("THE RIDER'S STYLE (follow it)")
        style.rules.forEach { appendLine("- $it") }
        style.examples.forEach { e ->
            appendLine("Before they changed it: ${e.before}")
            appendLine("After: ${e.after}${e.note?.let { n -> "  (their note: \"$n\")" } ?: ""}")
        }
    }

    /** The caption clip: one riding shot with text written on it (a meme-style Reel). */
    private const val CAPTION_RULE =
        "Caption clips: when there are riding shots with speed, acceleration or lean (see kmh and events), include at least one piece with format \"caption\": " +
            "ONE section (kind \"peak\", form \"take\") with ONE shot of 15–20 s from a single clip, hookLine empty, and in onScreen 1–3 short funny or " +
            "relatable lines about riding (falling, loving the bike, fuel prices, mom asking where you are; Hinglish is fine), shown one after another. " +
            "The text is the joke: don't just describe the shot."

    private const val SCHEMA = "{\"pieces\":[{\"format\":\"reel|short|story|long|caption\",\"vibe\":\"hype|cine|chill|vlog\",\"style\":\"a style id from the list, or empty\",\"title\":\"max 5 words\",\"why\":\"one line: why this piece works\",\"story\":\"2–4 sentences: the story it tells, start to end\",\"onScreen\":[\"caption clips only: 1–3 short lines shown in turn, else empty\"]," +
        "\"lengthSec\":30,\"shape\":\"story\",\"hookLine\":\"max 6 words for the first frame or empty\",\"caption\":\"post caption, 1–2 lines + 3–5 hashtags\"," +
        "\"sections\":[{\"kind\":\"hook\",\"form\":\"sound\",\"text\":\"on-screen text or empty\",\"why\":\"short\",\"shots\":[{\"clip\":\"c3\",\"in\":12.4,\"out\":13.2}]}]}]}"

    /** Asks for a content plan: the pieces worth making from this ride, each with its script. */
    /** The rider's own styles, to choose from per piece (favourites and the most used first). */
    private fun stylesText(styles: List<StudioStyle>): String = if (styles.isEmpty()) "" else
        "The rider's styles (prefer the first ones; pick one per piece by its id when it suits, else just a vibe): " +
            styles.joinToString("; ") { "${it.id} = ${it.describe()}" } + ".\n"

    fun planPrompt(ride: String, stats: String, footage: List<Footage>, style: StyleContext?, formats: List<PieceFormat>, styles: List<StudioStyle> = emptyList(), performance: String = "", all: Boolean = false): String = buildString {
        appendLine("You are the editor and social media manager for a motorcycle rider who posts motovlogs (often Hinglish).")
        appendLine("Ride: \"$ride\" ($stats). ${summary(footage).text}")
        appendLine(footageText(footage))
        if (all) {
            appendLine("List EVERY piece worth making from THIS ride, smaller ones too: each story, reaction, funny or useful line, tip and fast stretch. As many as the footage supports, at most 15.")
            appendLine(CAPTION_RULE)
        } else {
            val least = leastPieces(footage)
            appendLine("Suggest one piece for every strong moment in THIS ride: a story, a reaction, a funny or useful line, a fast stretch. At least $least, at most 6.")
            appendLine("Each reaction sound and each separate story or funny or useful line can be its own short piece; don't put everything into one.")
            appendLine(CAPTION_RULE)
        }
        appendLine("List them best first: the first one is made automatically. Each piece must fill at least 80% of its own length with real content.")
        appendLine("Formats allowed: ${formats.joinToString { "${it.name.lowercase()} (${it.hint}, ${it.minSec}–${it.maxSec} s)" }}.")
        appendLine("Make them different from each other: different hooks, shapes and lengths; no two pieces open on the same moment or share a shape. Clips may be reused across pieces.")
        appendLine("Suggest a long video only if there's enough talking for 2+ minutes. When the footage is thin, suggest fewer, shorter pieces.")
        appendLine("Pick a vibe for each piece: hype (fast cuts, bold words), cine (slow, wide, film look), chill (easy, warm) or vlog (the voice leads).")
        append(stylesText(styles))
        append(performance)
        appendLine(CRAFT)
        append(styleText(style))
        append("Reply with JSON only: $SCHEMA")
    }

    /** Asks for one piece of [format] and about [lengthSec]; [avoid] is a script to differ from (Remix). */
    fun piecePrompt(ride: String, stats: String, footage: List<Footage>, style: StyleContext?, format: PieceFormat, lengthSec: Int, avoid: Script?, keys: Map<String, String>): String = buildString {
        appendLine("You are the editor of a motorcycle rider's motovlog (often Hinglish).")
        appendLine("Ride: \"$ride\" ($stats). ${summary(footage).text}")
        appendLine(footageText(footage))
        appendLine("Write ONE ${format.hint}, at most $lengthSec s long.")
        avoid?.let { appendLine("Make it clearly different from this one (another hook, shape or angle): ${ScriptJson.toPromptJson(it, keys)}") }
        appendLine(CRAFT)
        append(styleText(style))
        append("Reply with JSON only (one piece): $SCHEMA")
    }

    /** Asks for one piece the rider described in their words ("a 15 s funny one about the water"). */
    fun askPrompt(ride: String, stats: String, footage: List<Footage>, style: StyleContext?, ask: String, styles: List<StudioStyle> = emptyList()): String = buildString {
        appendLine("You are the editor of a motorcycle rider's motovlog (often Hinglish).")
        appendLine("Ride: \"$ride\" ($stats). ${summary(footage).text}")
        appendLine(footageText(footage))
        appendLine("The rider asks for: \"${ask.replace("\"", "'")}\". Write ONE piece that does that, from this footage (format, length and vibe as they asked, or what suits it).")
        appendLine("Vibes: hype (fast cuts, bold words), cine (slow, wide, film look), chill (easy, warm), vlog (the voice leads).")
        appendLine("If they ask for a caption clip: $CAPTION_RULE")
        append(stylesText(styles))
        appendLine(CRAFT)
        append(styleText(style))
        append("Reply with JSON only (one piece): $SCHEMA")
    }

    /** Asks to rewrite [script] following the rider's [note]. */
    fun revisePrompt(ride: String, footage: List<Footage>, style: StyleContext?, script: Script, note: String, keys: Map<String, String>): String = buildString {
        appendLine("You are the editor of a motorcycle rider's motovlog (often Hinglish). Ride: \"$ride\".")
        appendLine(footageText(footage))
        appendLine("This is the current script: ${ScriptJson.toPromptJson(script, keys)}")
        appendLine("The rider wants this changed: \"${note.replace("\"", "'")}\". Rewrite the script to do that; keep what they didn't ask to change.")
        appendLine(CRAFT)
        append(styleText(style))
        append("Reply with JSON only (one piece): $SCHEMA")
    }

    /** The pieces in Gemini's reply; clip keys become moment ids. Unknown clips are dropped. */
    fun parsePieces(reply: String, footage: List<Footage>): List<Script> = runCatching {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        val o = JSONObject(if (a >= 0 && b > a) reply.substring(a, b + 1) else reply)
        val arr = o.optJSONArray("pieces") ?: JSONArray().put(o)
        val byKey = footage.associateBy { it.key }
        (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val format = PieceFormat.of(p.optString("format"))
            val sections = p.optJSONArray("sections")?.let { sa ->
                (0 until sa.length()).mapNotNull { k ->
                    val so = sa.optJSONObject(k) ?: return@mapNotNull null
                    val kind = SectionKind.of(so.optString("kind")) ?: return@mapNotNull null
                    val shots = so.optJSONArray("shots")?.let { sh ->
                        (0 until sh.length()).mapNotNull { j ->
                            val x = sh.optJSONObject(j) ?: return@mapNotNull null
                            val f = byKey[x.optString("clip").trim()] ?: return@mapNotNull null
                            val inS = x.optDouble("in", Double.NaN)
                            val outS = x.optDouble("out", Double.NaN)
                            if (inS.isNaN() || outS.isNaN()) return@mapNotNull null
                            ScriptShot(f.momentId, (inS * 1000).roundToLong(), (outS * 1000).roundToLong())
                        }
                    }.orEmpty()
                    Section(kind, so.optString("form").trim().lowercase().ifEmpty { "line" }, shots, so.optString("text").trim().takeIf { it.isNotEmpty() }?.take(60), so.optString("why").trim().takeIf { it.isNotEmpty() }?.take(140))
                }
            }.orEmpty()
            if (sections.none { it.shots.isNotEmpty() }) return@mapNotNull null
            Script(
                format = format,
                title = p.optString("title").trim().ifEmpty { format.label }.take(40),
                why = p.optString("why").trim().takeIf { it.isNotEmpty() }?.take(160),
                lengthSec = p.optInt("lengthSec", 30).coerceIn(format.minSec, format.maxSec),
                shape = p.optString("shape").trim().lowercase().ifEmpty { "story" },
                hookLine = p.optString("hookLine").trim().trim('"').takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.take(8)?.joinToString(" "),
                postCaption = p.optString("caption").trim().takeIf { it.isNotEmpty() },
                sections = sections,
                vibe = p.optString("vibe").trim().uppercase().let { v -> Vibe.entries.firstOrNull { it.name == v || (v == "CINEMATIC" && it == Vibe.CINE) } },
                style = p.optString("style").trim().takeIf { it.startsWith("st-") || it in setOf("hype", "cine", "chill", "vlog") },
                story = p.optString("story").trim().takeIf { it.isNotEmpty() }?.take(600),
                onScreen = p.optJSONArray("onScreen")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { t -> t.isNotEmpty() && !t.startsWith("caption clips only") }?.take(80) } }.orEmpty().take(3),
            ).let { sc -> if (sc.format == PieceFormat.CAPTION) sc.copy(hookLine = null) else sc }
        }
    }.getOrDefault(emptyList())

    // ---- the app's own script (no Gemini) -----------------------------------------------------

    /** A run of speech in one clip: lines less than 1.5 s apart. */
    private data class Utterance(val f: Footage, val startMs: Long, val endMs: Long, val text: String) {
        val sound: Boolean get() = isSound(text)
        val durMs: Long get() = endMs - startMs
    }

    private fun utterances(footage: List<Footage>): List<Utterance> = footage.flatMap { f ->
        val out = ArrayList<Utterance>()
        var cur: MutableList<CaptionLine>? = null
        for (l in f.lines.sortedBy { it.startMs }) {
            val c = cur
            if (c != null && l.startMs - c.last().endMs < 1_500 && l.endMs - c.first().startMs <= 12_000) c += l
            else { c?.let { out += Utterance(f, it.first().startMs, it.last().endMs, it.joinToString(" ") { x -> x.text }) }; cur = mutableListOf(l) }
        }
        cur?.let { out += Utterance(f, it.first().startMs, it.last().endMs, it.joinToString(" ") { x -> x.text }) }
        out
    }

    /**
     * A calm script without Gemini: a reaction or short line as the hook, the longest thing the
     * rider said as the peak, one long road shot, more lines while there's room, a loop. Fewer,
     * longer shots, never a fast montage of look-alike clips.
     */
    fun local(footage: List<Footage>, format: PieceFormat, lengthSec: Int, title: String, reason: String = "Made by the app, without Gemini"): Script? {
        if (footage.isEmpty()) return null
        val budget = lengthSec * 1000L
        val pad = 250L
        val us = utterances(footage).filter { it.durMs in 300..14_000 }
        val hookU = us.filter { it.sound }.minByOrNull { it.durMs } ?: us.filter { it.durMs <= 3_500 }.maxByOrNull { it.text.length }
        val peakU = us.filter { it !== hookU }.maxByOrNull { it.durMs }
        fun shotOf(u: Utterance) = ScriptShot(u.f.momentId, (u.startMs - pad).coerceAtLeast(0), (u.endMs + pad).coerceAtMost(u.f.durationMs))
        val sections = ArrayList<Section>()
        var used = 0L
        fun add(s: Section) { sections += s; used += s.shots.sumOf { it.durMs } }
        hookU?.let { add(Section(SectionKind.HOOK, if (it.sound) "sound" else "line", listOf(shotOf(it)))) }
        if (hookU == null) {
            // No words at all: open on the fastest moment.
            val f = footage.maxBy { it.kmhMax }
            val len = minOf(3_000L, f.durationMs)
            add(Section(SectionKind.HOOK, "picture", listOf(ScriptShot(f.momentId, (f.durationMs - len) / 2, (f.durationMs - len) / 2 + len)), text = title.take(30)))
        }
        // A riding shot from a part where nothing was said (the road, or riding between lines).
        fun quiet(f: Footage, len: Long): ScriptShot? {
            val l = minOf(len, f.durationMs)
            var a = (f.durationMs - l) / 2
            if (f.lines.any { it.endMs > a && it.startMs < a + l }) {
                // Try the stretch after the last word.
                val after = (f.lines.maxOfOrNull { it.endMs } ?: 0) + 300
                if (f.durationMs - after >= l) a = after else return null
            }
            return ScriptShot(f.momentId, a, a + l)
        }
        val roadLen = if (budget >= 25_000) 6_000L else 3_000L
        val roads = (footage.filter { it.look == "road" } + footage.sortedByDescending { it.kmhMax }).distinct().mapNotNull { quiet(it, roadLen) }
        val roadShot = roads.firstOrNull() ?: run {
            val f = footage.maxBy { it.durationMs - it.talkingMs }
            val l = minOf(roadLen, f.durationMs)
            ScriptShot(f.momentId, (f.durationMs - l) / 2, (f.durationMs - l) / 2 + l)
        }
        if (used + roadShot.durMs < budget - 4_000) add(Section(SectionKind.BUILD, "road", listOf(roadShot)))
        peakU?.let { if (used + it.durMs + 2 * pad <= budget) add(Section(SectionKind.PEAK, "take", listOf(shotOf(it)))) }
        // More of what was said, in filming order, while there's room.
        us.filter { it !== hookU && it !== peakU && !it.sound }.sortedBy { it.f.startMillis + it.startMs }.forEach { u ->
            if (used + u.durMs + 2 * pad <= budget - 1_500) add(Section(SectionKind.PAYOFF, "line", listOf(shotOf(u))))
        }
        // At least three shots: another riding shot from a different clip while there's room.
        roads.drop(1).filter { r -> sections.none { s -> s.shots.any { it.clip == r.clip } } }.forEach { r ->
            if (sections.size < 3 && used + r.durMs <= budget - 1_000) add(Section(SectionKind.BUILD, "road", listOf(r)))
        }
        add(Section(SectionKind.ENDING, "loop", emptyList()))
        return Script(format, title, reason, lengthSec, "story", null, null, sections)
    }

    // ---- script → video plan, with the guardrails -----------------------------------------------

    /** The plan, what had to be fixed on the way, and how long the script meant it to be. */
    data class Planned(val plan: StudioPlan, val fixes: List<String>, val plannedMs: Long = plan.totalMs)

    /**
     * Turns [script] into segments the renderer can make, enforcing what Gemini might get wrong:
     * shots inside their clip, no sentence cut in half, no seconds used twice (but a flash hook),
     * silent shots long enough to read, and the total within the length. Gentle: a shot Gemini
     * chose is lengthened or merged, never dropped for looking like the one before, and a piece
     * that comes out under 70% of its length is filled with more of its own clips.
     */
    fun toPlan(script: Script, footage: List<Footage>, bits: List<Bit>, vibe: Vibe, options: StudioOptions): Planned {
        val fixes = ArrayList<String>()
        val byId = footage.associateBy { it.momentId }
        val used = HashMap<String, MutableList<LongRange>>()
        data class Placed(val section: Int, val kind: SectionKind, val form: String, val f: Footage, var inMs: Long, var outMs: Long, val text: String?)
        val placed = ArrayList<Placed>()
        script.sections.forEachIndexed { si, sec ->
            sec.shots.forEachIndexed { k, shot ->
                val f = byId[shot.clip] ?: run { fixes += "unknown clip ${shot.clip}"; return@forEachIndexed }
                var a = shot.inMs.coerceIn(0, f.durationMs)
                var b = shot.outMs.coerceIn(0, f.durationMs)
                if (b - a < 300) { fixes += "too short ${f.key}"; return@forEachIndexed }
                // Whole sentences: widen to the line's edges.
                f.lines.forEach { l ->
                    if (a in (l.startMs + 1) until l.endMs) a = (l.startMs - 150).coerceAtLeast(0)
                    if (b in (l.startMs + 1) until l.endMs) b = (l.endMs + 250).coerceAtMost(f.durationMs)
                }
                val flash = sec.kind == SectionKind.HOOK && b - a <= 2_500
                val ranges = used.getOrPut(f.momentId) { mutableListOf() }
                if (!flash) {
                    val clash = ranges.firstOrNull { it.first < b && a < it.last }
                    if (clash != null) {
                        // Keep the part that's new, if it's worth a shot.
                        if (clash.first <= a) a = clash.last else b = clash.first
                        if (b - a < 1_200) { fixes += "reused seconds ${f.key}"; return@forEachIndexed }
                    }
                }
                val talking = f.lines.any { it.endMs > a && it.startMs < b }
                // Silent shots get room to breathe: at least 2 s, 3 s when the shot before looks the same.
                val prev = placed.lastOrNull()
                val minSilent = if (prev != null && prev.f.look == f.look) 3_000L else 2_000L
                if (!talking && !flash && b - a < minSilent) {
                    val need = minSilent - (b - a)
                    b = (b + need).coerceAtMost(f.durationMs)
                    if (b - a < minSilent) a = (b - minSilent).coerceAtLeast(0)
                    fixes += "lengthened ${f.key}"
                }
                // The same clip continuing right after the shot before: one longer shot, not a jump cut.
                if (prev != null && !flash && prev.f.momentId == f.momentId && a - prev.outMs in -500..1_500) {
                    ranges.remove(prev.inMs..prev.outMs)
                    prev.outMs = maxOf(prev.outMs, b)
                    ranges += prev.inMs..prev.outMs
                    fixes += "merged ${f.key}"; return@forEachIndexed
                }
                ranges += a..b
                placed += Placed(si, sec.kind, sec.form, f, a, b, sec.text.takeIf { k == 0 })
            }
        }
        if (placed.isEmpty()) return Planned(StudioPlan(emptyList(), vibe), fixes)
        val ending = script.sections.lastOrNull { it.kind == SectionKind.ENDING }?.form
        val stats = options.outro && (ending == "stats")
        val loop = options.loopEnd && (ending == null || ending == "loop")
        // Fit the length: shorten long silent shots, then drop build/setup/turn shots, then the last shots.
        val budget = script.lengthSec * 1000L + 1_000 - (if (stats) StudioPlanner.OUTRO_MS else 0) - (if (loop) StudioPlanner.TAIL_MS else 0)
        fun total() = placed.sumOf { it.outMs - it.inMs }
        fun silent(p: Placed) = p.f.lines.none { it.endMs > p.inMs && it.startMs < p.outMs }
        while (total() > budget) {
            val over = total() - budget
            val long = placed.filter { silent(it) && it.outMs - it.inMs > 3_000 }.maxByOrNull { it.outMs - it.inMs }
            if (long != null) { long.outMs -= minOf(over, long.outMs - long.inMs - 3_000); fixes += "trimmed ${long.f.key}"; continue }
            val drop = placed.lastOrNull { it.kind in setOf(SectionKind.BUILD, SectionKind.SETUP, SectionKind.TURN) }
                ?: placed.lastOrNull { it.kind != SectionKind.HOOK && it.kind != SectionKind.PEAK }
                ?: placed.takeIf { it.size > 1 }?.last()
                ?: break
            placed.remove(drop); fixes += "dropped ${drop.f.key} for length"
        }
        // Too short for what it was meant to be (e.g. 11 s of a 30 s piece): more of the same clips,
        // the shots with the most room first, never cutting into a sentence or reused seconds.
        val floor = (script.lengthSec * 1000L * 7 / 10).coerceAtMost(budget)
        var guard = 0
        while (total() < floor && guard++ < 40) {
            val need = floor - total()
            val grow = placed.mapNotNull { p ->
                val others = used[p.f.momentId].orEmpty().filter { it != p.inMs..p.outMs }
                val limit = (others.filter { it.first >= p.outMs }.minOfOrNull { it.first } ?: p.f.durationMs).coerceAtMost(p.f.durationMs)
                (limit - p.outMs).takeIf { it >= 500 }?.let { p to it }
            }.maxByOrNull { it.second } ?: break
            val (p, room) = grow
            var out = p.outMs + minOf(need, room, 6_000)
            // Don't stop in the middle of a sentence: take it to its end (within the room there is).
            p.f.lines.firstOrNull { out in (it.startMs + 1) until it.endMs }?.let { l -> out = minOf(l.endMs + 250, p.outMs + room) }
            used[p.f.momentId]?.let { r -> r.removeAll { it.first == p.inMs && it.last == p.outMs }; r += p.inMs..out }
            p.outMs = out
            fixes += "filled ${p.f.key}"
        }
        val segs = ArrayList<Segment>()
        placed.forEachIndexed { i, p ->
            val bit = bits.firstOrNull { it.momentId == p.f.momentId }
            val lines = p.f.lines.filter { it.endMs > p.inMs && it.startMs < p.outMs }
                .mapNotNull { l ->
                    // The rider's words for it; blank = hidden.
                    val t = script.captions[captionKey(p.f.momentId, l.startMs)]
                    when {
                        t == null -> l
                        t.isBlank() -> null
                        else -> l.copy(text = t)
                    }
                }
                .map { it.copy(startMs = it.startMs - p.inMs, endMs = it.endMs - p.inMs) }
            val b = Bit(
                id = "${p.f.momentId}@${p.inMs}",
                momentId = p.f.momentId,
                clipDurationMs = p.f.durationMs,
                inMs = p.inMs,
                outMs = p.outMs,
                atMillis = p.f.startMillis + p.inMs,
                lines = lines,
                speedKmh = bit?.speedKmh ?: 0.0,
                punch = 5f,
                fromRide = bit?.fromRide,
                source = bit?.source,
                camera = bit?.camera,
            )
            segs += ClipSegment(b, p.inMs, p.outMs - p.inMs, lines, hook = i == 0, section = p.section, text = p.text.takeIf { i > 0 })
        }
        if (options.intro) segs.add(0, TitleSegment(StudioPlanner.INTRO_MS))
        if (stats) segs += StatsSegment(StudioPlanner.OUTRO_MS)
        if (loop) {
            val first = segs.filterIsInstance<ClipSegment>().first()
            segs += ClipSegment(first.bit, first.inMs, StudioPlanner.TAIL_MS.coerceAtMost(first.durMs), emptyList(), hook = false, tail = true)
        }
        return Planned(withOnScreen(StudioPlan(segs, vibe), script.onScreen), fixes, script.lengthSec * 1000L)
    }

    /** The lines written on the video, one after another across its clips (top of the frame, on a box). */
    fun withOnScreen(plan: StudioPlan, lines: List<String>): StudioPlan {
        if (lines.isEmpty()) return plan
        val clips = plan.segments.withIndex().filter { (_, s) -> s is ClipSegment && !s.tail }
        if (clips.isEmpty()) return plan
        val from = plan.startOf(clips.first().index)
        val to = plan.startOf(clips.last().index) + clips.last().value.durMs
        val each = (to - from) / lines.size
        val items = lines.mapIndexed { i, t ->
            TextItem("os-$i", from + i * each, if (i == lines.lastIndex) to else from + (i + 1) * each, t, y = 0.2f, look = TextLook.BOX, animIn = TextAnim.POP)
        }
        return plan.copy(texts = plan.texts + items)
    }

    /** Which caption a rider's change is for: the clip and where the line starts in it. */
    fun captionKey(momentId: String, startMs: Long): String = "$momentId@$startMs"

    /**
     * The story, for scripts that don't have one written out: what each section says or shows,
     * in order ("Hook: “Paani hi paani”. Then riding at 62 km/h…").
     */
    fun storyOf(script: Script, footage: List<Footage>): String {
        val byId = footage.associateBy { it.momentId }
        return script.sections.joinToString(" ") { sec ->
            val said = sec.shots.flatMap { sh -> byId[sh.clip]?.lines.orEmpty().filter { it.endMs > sh.inMs && it.startMs < sh.outMs } }.joinToString(" ") { it.text }
            val shown = sec.shots.mapNotNull { byId[it.clip] }.let { fs -> if (fs.any { it.look == "road" }) "the road" else if (fs.isNotEmpty()) "riding" else "" }
            "${sec.kind.label}: " + (if (said.isNotBlank()) "\u201c${said.take(140)}\u201d" else shown.ifEmpty { sec.form }) + "."
        }
    }

    /**
     * The fewest pieces to ask for: rides with lots of talking have several stories in them
     * (31 clips with talking → 4), a quiet ride may have one.
     */
    fun leastPieces(footage: List<Footage>): Int {
        val talking = footage.count { it.lines.isNotEmpty() }
        val sounds = footage.sumOf { f -> f.lines.count { isSound(it.text) } }
        return when {
            talking >= 24 -> 4
            talking >= 12 -> 3
            talking >= 5 || sounds >= 2 -> 2
            else -> 1
        }
    }

    /** How many pieces Gemini's answer has, readable or not (to say when some couldn't be used). */
    fun piecesAsked(reply: String?): Int = runCatching {
        val a = reply!!.indexOf('{')
        val b = reply.lastIndexOf('}')
        JSONObject(reply.substring(a, b + 1)).optJSONArray("pieces")?.length() ?: 1
    }.getOrDefault(0)

    /** Footage keys ("c1"…) in filming order. */
    fun keys(footage: List<Footage>): Map<String, String> = footage.associate { it.momentId to it.key }
}

/** Saved with a Reel, and how a script is shown to Gemini. */
object ScriptJson {
    fun write(s: Script): JSONObject = JSONObject()
        .put("format", s.format.name).put("title", s.title).put("why", s.why ?: JSONObject.NULL).put("lengthSec", s.lengthSec)
        .put("shape", s.shape).put("hookLine", s.hookLine ?: JSONObject.NULL).put("caption", s.postCaption ?: JSONObject.NULL)
        .put("vibe", s.vibe?.name ?: JSONObject.NULL)
        .put("style", s.style ?: JSONObject.NULL)
        .put("story", s.story ?: JSONObject.NULL)
        .put("onScreen", JSONArray(s.onScreen))
        .put("captions", JSONObject().apply { s.captions.forEach { (k, v) -> put(k, v) } })
        .put("sections", JSONArray().apply {
            s.sections.forEach { sec ->
                put(
                    JSONObject().put("kind", sec.kind.name).put("form", sec.form).put("text", sec.text ?: JSONObject.NULL).put("why", sec.why ?: JSONObject.NULL)
                        .put("shots", JSONArray().apply { sec.shots.forEach { put(JSONObject().put("clip", it.clip).put("in", it.inMs).put("out", it.outMs)) } }),
                )
            }
        })

    fun read(o: JSONObject?): Script? = o?.let {
        runCatching {
            Script(
                format = PieceFormat.of(o.optString("format")),
                title = o.optString("title"),
                why = o.optStringOrNull("why"),
                lengthSec = o.optInt("lengthSec", 30),
                shape = o.optString("shape", "story"),
                hookLine = o.optStringOrNull("hookLine"),
                postCaption = o.optStringOrNull("caption"),
                vibe = o.optStringOrNull("vibe")?.let { v -> Vibe.entries.firstOrNull { it.name == v } },
                style = o.optStringOrNull("style"),
                story = o.optStringOrNull("story"),
                onScreen = o.optJSONArray("onScreen")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                captions = o.optJSONObject("captions")?.let { c -> c.keys().asSequence().associateWith { k -> c.optString(k) } }.orEmpty(),
                sections = o.getJSONArray("sections").let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val so = a.getJSONObject(i)
                        val kind = SectionKind.of(so.optString("kind")) ?: return@mapNotNull null
                        Section(
                            kind, so.optString("form"),
                            so.optJSONArray("shots")?.let { sh -> (0 until sh.length()).map { j -> sh.getJSONObject(j).let { x -> ScriptShot(x.getString("clip"), x.getLong("in"), x.getLong("out")) } } }.orEmpty(),
                            so.optStringOrNull("text"), so.optStringOrNull("why"),
                        )
                    }
                },
            )
        }.getOrNull()
    }

    /** The script in the prompt's own shape (clip keys, seconds), for Remix and "tell it". */
    fun toPromptJson(s: Script, keys: Map<String, String>): String = JSONObject()
        .put("title", s.title).put("lengthSec", s.lengthSec).put("shape", s.shape).put("hookLine", s.hookLine ?: "")
        .put("sections", JSONArray().apply {
            s.sections.forEach { sec ->
                put(
                    JSONObject().put("kind", sec.kind.name.lowercase()).put("form", sec.form).put("text", sec.text ?: "")
                        .put("shots", JSONArray().apply { sec.shots.forEach { put(JSONObject().put("clip", keys[it.clip] ?: it.clip).put("in", it.inMs / 1000.0).put("out", it.outMs / 1000.0)) } }),
                )
            }
        }).toString()

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotEmpty() }
}

/** What Studio has learnt about the rider's style, sent with every script request. */
data class StyleContext(val rules: List<String>, val examples: List<StyleExample>)

/** One change the rider made: the script before and after (as short text), and their note. */
data class StyleExample(val before: String, val after: String, val note: String?)

/** A piece Gemini suggests for a ride: its script and the plan the app made of it. */
data class ContentPiece(
    val key: String,
    val script: Script,
    val plan: StudioPlan,
    /** How long the script asked for, and what the app's checks changed in it. */
    val plannedMs: Long = plan.totalMs,
    val fixes: List<String> = emptyList(),
) {
    val opening: ClipSegment? get() = plan.clips.firstOrNull()

    /** The checks changed the length by more than 15%: worth showing "planned vs. made". */
    val lengthChanged: Boolean get() = plannedMs > 0 && kotlin.math.abs(plan.totalMs - plannedMs) * 100 > plannedMs * 15
}

/** The rider's direct changes to a script, in the Script view. Pure, unit-tested. */
object ScriptEdits {
    fun setText(s: Script, i: Int, text: String): Script =
        s.copy(sections = s.sections.mapIndexed { k, sec -> if (k == i) sec.copy(text = text.trim().takeIf { it.isNotEmpty() }) else sec })

    /** Makes section [i] longer or shorter by [deltaMs] (its last shot), inside its clip. */
    fun resize(s: Script, i: Int, deltaMs: Long, footage: List<Footage>): Script {
        val sec = s.sections.getOrNull(i) ?: return s
        val last = sec.shots.lastOrNull() ?: return s
        val dur = footage.firstOrNull { it.momentId == last.clip }?.durationMs ?: return s
        val out = (last.outMs + deltaMs).coerceIn(last.inMs + 500, dur)
        val shots = sec.shots.dropLast(1) + last.copy(outMs = out)
        return s.copy(sections = s.sections.toMutableList().also { it[i] = sec.copy(shots = shots) })
    }

    fun move(s: Script, i: Int, by: Int): Script {
        val j = i + by
        if (i !in s.sections.indices || j !in s.sections.indices) return s
        val list = s.sections.toMutableList()
        val a = list[i]; list[i] = list[j]; list[j] = a
        return s.copy(sections = list)
    }

    fun remove(s: Script, i: Int): Script =
        if (s.sections.count { it.shots.isNotEmpty() } <= 1 && s.sections.getOrNull(i)?.shots?.isNotEmpty() == true) s
        else s.copy(sections = s.sections.filterIndexed { k, _ -> k != i })

    /** Section [i] shows [f] instead: what was said in it, or 4 s from its middle. */
    fun swap(s: Script, i: Int, f: Footage): Script {
        val sec = s.sections.getOrNull(i) ?: return s
        return s.copy(sections = s.sections.toMutableList().also { it[i] = sec.copy(shots = listOf(bestPart(f))) })
    }

    /** Puts [shot] first as the hook (replacing the hook there was). */
    fun hook(s: Script, shot: ScriptShot, sound: Boolean): Script {
        val rest = s.sections.filter { it.kind != SectionKind.HOOK }
        val old = s.sections.firstOrNull { it.kind == SectionKind.HOOK }
        return s.copy(sections = listOf(Section(SectionKind.HOOK, if (sound) "sound" else "line", listOf(shot), old?.text, "Your pick")) + rest)
    }

    /** Sounds and short lines that would open well: sounds first. */
    fun hookChoices(footage: List<Footage>): List<Pair<ScriptShot, String>> = footage.flatMap { f ->
        f.lines.filter { it.endMs - it.startMs <= 4_000 }.map { l ->
            ScriptShot(f.momentId, (l.startMs - 150).coerceAtLeast(0), (l.endMs + 250).coerceAtMost(f.durationMs)) to l.text
        }
    }.sortedByDescending { ScriptWriter.isSound(it.second) }.take(12)

    fun bestPart(f: Footage): ScriptShot {
        val l = f.lines.firstOrNull()
        if (l != null) {
            val end = f.lines.takeWhile { it.endMs - l.startMs <= 12_000 }.last().endMs
            return ScriptShot(f.momentId, (l.startMs - 150).coerceAtLeast(0), (end + 250).coerceAtMost(f.durationMs))
        }
        val len = minOf(4_000L, f.durationMs)
        return ScriptShot(f.momentId, (f.durationMs - len) / 2, (f.durationMs - len) / 2 + len)
    }

    /**
     * The script after timeline edits: each section gets the clips that now carry its number
     * (in their new order); clips added on the timeline become their own sections.
     */
    fun sync(s: Script, plan: StudioPlan): Script {
        val clips = plan.clips
        val kept = s.sections.mapIndexedNotNull { k, sec ->
            val shots = clips.filter { it.section == k }.map { ScriptShot(it.bit.momentId, it.inMs, it.inMs + it.durMs) }
            if (shots.isEmpty() && sec.shots.isNotEmpty()) null else sec.copy(shots = shots)
        }
        val added = clips.filter { it.section !in s.sections.indices }.map { Section(SectionKind.PEAK, "take", listOf(ScriptShot(it.bit.momentId, it.inMs, it.inMs + it.durMs)), why = "Added on the timeline") }
        val ending = kept.filter { it.kind == SectionKind.ENDING }
        return s.copy(sections = kept.filter { it.kind != SectionKind.ENDING } + added + ending, lengthSec = (plan.totalMs / 1000).toInt().coerceAtLeast(1))
    }

    /** A script for a Reel made before scripts: each clip one section, as it was. */
    fun fromPlan(plan: StudioPlan, title: String): Script = Script(
        PieceFormat.REEL, title, null, (plan.totalMs / 1000).toInt().coerceAtLeast(6), "story", null, null,
        plan.clips.mapIndexed { i, c -> Section(if (i == 0) SectionKind.HOOK else SectionKind.PEAK, "take", listOf(ScriptShot(c.bit.momentId, c.inMs, c.inMs + c.durMs)), c.text) } +
            Section(SectionKind.ENDING, if (plan.segments.any { it is ClipSegment && it.tail }) "loop" else "cut", emptyList()),
    )
}
