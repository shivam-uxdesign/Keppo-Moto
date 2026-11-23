package com.ridetrack.app.studio

import com.ridetrack.telemetry.model.RideEventType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ask across all rides (#12): "all my downshifts", "every time I said Tooooo", "the rainy bits
 * from October". Gemini turns the ask into an [AskFilter] (and at most two questions back); the
 * phone finds the clips itself ([AskSearch]), from what was said, what was seen, ride events and
 * downshifts. Only the ask and a short menu of what can be searched go to Gemini. Pure, unit-tested.
 */

/** Ride events an ask can look for, and the moment types each one means. */
enum class AskEvent(val key: String, val one: String, val many: String, val types: Set<RideEventType>) {
    DOWNSHIFT("downshift", "downshift", "downshifts", emptySet()),
    HARD_BRAKE("hard_brake", "hard brake", "hard brakes", setOf(RideEventType.HARD_BRAKE)),
    ACCELERATION("acceleration", "pull", "pulls", setOf(RideEventType.STRONG_ACCELERATION)),
    LEAN("lean", "lean", "leans", setOf(RideEventType.SIGNIFICANT_LEAN)),
    TURN("turn", "turn", "turns", setOf(RideEventType.LEFT_TURN, RideEventType.RIGHT_TURN, RideEventType.SHARP_DIRECTION_CHANGE)),
    REV("rev", "rev", "revs", setOf(RideEventType.ENGINE_REV));

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** What an ask looks for. Empty lists mean "any"; categories that are set must all match a clip. */
data class AskFilter(
    /** A short name for the Reel ("All my downshifts"). */
    val title: String = "",
    /** Words or phrases the rider said (any of them). */
    val said: List<String> = emptyList(),
    /** Things seen in the clip (any of them). */
    val seen: List<String> = emptyList(),
    val events: Set<AskEvent> = emptySet(),
    /** Only clips where the rider talks. */
    val voice: Boolean = false,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    /** Rides by name (a part of the name is enough; any of them). */
    val rides: List<String> = emptyList(),
    /** How long the Reel should be; null = as long as it takes (up to a Reel's longest). */
    val lengthSec: Int? = null,
) {
    /** Nothing to look for but dates, rides or the voice: every clip they leave. */
    val plain: Boolean get() = said.isEmpty() && seen.isEmpty() && events.isEmpty()
}

/** A change an answer makes to the filter ([clearDates]: all rides, any time). */
data class AskPatch(
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val clearDates: Boolean = false,
    val lengthSec: Int? = null,
    val anyLength: Boolean = false,
    val voice: Boolean? = null,
) {
    fun apply(f: AskFilter): AskFilter = f.copy(
        fromMillis = if (clearDates) null else fromMillis ?: f.fromMillis,
        toMillis = if (clearDates) null else toMillis ?: f.toMillis,
        lengthSec = if (anyLength) null else lengthSec ?: f.lengthSec,
        voice = voice ?: f.voice,
    )
}

data class AskAnswer(val label: String, val patch: AskPatch)

/** A question asked back, with 2–4 answers to tap. */
data class AskQuestion(val text: String, val why: String?, val answers: List<AskAnswer>)

/** Gemini's reading of an ask: what to look for, and what it wants to know first (at most two). */
data class AskPlan(val filter: AskFilter, val questions: List<AskQuestion>)

/** One clip as the search sees it. */
data class AskClip(
    val momentId: String,
    val rideId: String,
    val rideName: String,
    /** When the clip's video starts (wall clock). */
    val startMillis: Long,
    val durationMs: Long,
    /** The moment's own time in the clip (the event that started it); null when unknown. */
    val eventAtMs: Long?,
    val types: Set<RideEventType>,
    val lines: List<CaptionLine>,
    /** What Gemini saw in it; null when it hasn't been looked at. */
    val seen: List<String>?,
    /** Downshifts found in its engine sound (ms into the clip); null when not looked for yet. */
    val downshifts: List<Long>?,
    val thumb: File? = null,
) {
    /** The rider's voice is in it: words, or a shout ("Tooooo!") counts too. */
    val talking: Boolean get() = lines.isNotEmpty() || RideEventType.VOICE in types
}

/** A part of a clip that answers the ask: [inMs]–[outMs] into it, and why it's in. */
data class AskHit(
    val momentId: String,
    val rideId: String,
    val rideName: String,
    val inMs: Long,
    val outMs: Long,
    val atMillis: Long,
    val why: String,
    val thumb: File? = null,
) {
    val key: String get() = "$momentId@$inMs"
    val lengthMs: Long get() = outMs - inMs
}

object AskSearch {
    /** Around a downshift or an event: this much before and after. */
    private const val AROUND_MS = 1_500L
    /** A clip matched by what's seen in it, or by nothing in particular: its first this long. */
    private const val PLAIN_MS = 4_000L
    /** A Reel is at most this long, and has at most this many parts. */
    const val MAX_SEC = 90
    const val MAX_PARTS = 40

    /** Every part of every clip that answers [f], in the order they were filmed. */
    fun find(f: AskFilter, clips: List<AskClip>): List<AskHit> {
        val out = ArrayList<AskHit>()
        clips.forEach { c ->
            if (f.fromMillis != null && c.startMillis < f.fromMillis) return@forEach
            if (f.toMillis != null && c.startMillis > f.toMillis) return@forEach
            if (f.rides.isNotEmpty() && f.rides.none { r -> c.rideName.contains(r, ignoreCase = true) }) return@forEach
            if (f.voice && !c.talking) return@forEach
            fun around(t: Long) = (t - AROUND_MS).coerceAtLeast(0) to (t + AROUND_MS).coerceAtMost(c.durationMs)
            // Each category set in the ask must match; the most precise one says where in the clip.
            val byEvent = f.events.flatMap { e ->
                if (e == AskEvent.DOWNSHIFT) c.downshifts.orEmpty().map { Triple(around(it).first, around(it).second, "Downshift") }
                else if (c.types.any { it in e.types } && c.eventAtMs != null) listOf(Triple(around(c.eventAtMs).first, around(c.eventAtMs).second, e.one.replaceFirstChar { it.uppercase() }))
                else emptyList()
            }
            val bySaid = f.said.flatMap { w ->
                c.lines.filter { ClipSearch.matches(w, it.text) }.map { l -> Triple((l.startMs - 300).coerceAtLeast(0), (l.endMs + 400).coerceAtMost(c.durationMs), "You said “${l.text.take(40)}”") }
            }
            val seenWord = f.seen.firstOrNull { w -> c.seen.orEmpty().any { s -> ClipSearch.matches(w, s) } }
            if (f.events.isNotEmpty() && byEvent.isEmpty()) return@forEach
            if (f.said.isNotEmpty() && bySaid.isEmpty()) return@forEach
            if (f.seen.isNotEmpty() && seenWord == null) return@forEach
            val parts = when {
                byEvent.isNotEmpty() -> byEvent.map { if (seenWord != null) it.copy(third = it.third + " · seen: $seenWord") else it }
                bySaid.isNotEmpty() -> bySaid
                seenWord != null -> listOf(Triple(0L, minOf(PLAIN_MS, c.durationMs), "Seen: $seenWord"))
                // Only the voice asked for: where the talking starts.
                f.voice -> c.lines.firstOrNull()?.let { l -> listOf(Triple((l.startMs - 300).coerceAtLeast(0), minOf(l.startMs + PLAIN_MS, c.durationMs), "You talk")) }.orEmpty()
                else -> listOf(Triple(0L, minOf(PLAIN_MS, c.durationMs), "Clip"))
            }
            merge(parts).forEach { (a, b, why) ->
                if (b - a >= 500) out += AskHit(c.momentId, c.rideId, c.rideName, a, b, c.startMillis + a, why, c.thumb)
            }
        }
        return out.sortedBy { it.atMillis }
    }

    /** Overlapping parts of one clip become one. */
    private fun merge(parts: List<Triple<Long, Long, String>>): List<Triple<Long, Long, String>> {
        val out = ArrayList<Triple<Long, Long, String>>()
        parts.sortedBy { it.first }.forEach { p ->
            val last = out.lastOrNull()
            if (last != null && p.first <= last.second) out[out.size - 1] = last.copy(second = maxOf(last.second, p.second)) else out += p
        }
        return out
    }

    /**
     * The parts that fit [lengthSec] (null: a Reel's longest): the newest first, one from each
     * ride before a second from any, then back in filming order. True when some were left out.
     */
    fun fit(hits: List<AskHit>, lengthSec: Int?): Pair<List<AskHit>, Boolean> {
        val budget = (lengthSec ?: MAX_SEC).coerceIn(5, MAX_SEC) * 1000L
        val byRide = hits.groupBy { it.rideId }.values.map { it.sortedByDescending { h -> h.atMillis }.toMutableList() }
            .sortedByDescending { it.first().atMillis }
        val order = ArrayList<AskHit>()
        var round = 0
        while (order.size < hits.size) {
            byRide.forEach { l -> l.getOrNull(round)?.let { order += it } }
            round++
        }
        val kept = ArrayList<AskHit>()
        var total = 0L
        order.forEach { h -> if (kept.size < MAX_PARTS && total + h.lengthMs <= budget) { kept += h; total += h.lengthMs } }
        return kept.sortedBy { it.atMillis } to (kept.size < hits.size)
    }

    fun rides(hits: List<AskHit>): Int = hits.map { it.rideId }.distinct().size

    /** What was found, counted: "14 downshifts", "“Tooooo” × 8", "5 moments". */
    fun count(f: AskFilter, n: Int): String {
        val e = f.events.singleOrNull()
        return when {
            e != null -> "$n " + if (n == 1) e.one else e.many
            f.said.size == 1 && f.seen.isEmpty() && f.events.isEmpty() -> "“${f.said[0]}” × $n"
            else -> "$n " + if (n == 1) "moment" else "moments"
        }
    }

    /** Clips in the asked dates and rides that haven't been looked at (so what's seen in them can't be found). */
    fun unseen(f: AskFilter, clips: List<AskClip>): List<AskClip> =
        if (f.seen.isEmpty()) emptyList() else clips.filter { c -> c.seen == null && inScope(f, c) }

    /** Clips whose downshifts haven't been looked for yet (rides recorded before tagging). */
    fun untagged(f: AskFilter, clips: List<AskClip>): List<AskClip> =
        if (AskEvent.DOWNSHIFT !in f.events) emptyList() else clips.filter { c -> c.downshifts == null && inScope(f, c) }

    private fun inScope(f: AskFilter, c: AskClip) =
        (f.fromMillis == null || c.startMillis >= f.fromMillis) && (f.toMillis == null || c.startMillis <= f.toMillis) &&
            (f.rides.isEmpty() || f.rides.any { r -> c.rideName.contains(r, ignoreCase = true) })

    /**
     * What Gemini may use, so it never asks for what isn't there: the events, the things seen most
     * often, the ride names and the dates the rides span. Nothing else about the rider goes.
     */
    fun menu(clips: List<AskClip>, zone: ZoneId = ZoneId.systemDefault()): String {
        val seen = clips.flatMap { it.seen.orEmpty().map { s -> s.lowercase().trim() } }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(60).joinToString(", ") { it.key }
        val rides = clips.sortedByDescending { it.startMillis }.map { it.rideName }.distinct().take(40).joinToString(" | ")
        val day = DateTimeFormatter.ISO_LOCAL_DATE
        val first = clips.minOfOrNull { it.startMillis }?.let { day.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "none"
        val last = clips.maxOfOrNull { it.startMillis }?.let { day.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "none"
        return "EVENTS: " + AskEvent.entries.joinToString(", ") { it.key } + "\n" +
            "THINGS SEEN IN CLIPS: " + seen.ifEmpty { "(none looked at yet)" } + "\n" +
            "RIDES (newest first): " + rides + "\n" +
            "CLIPS FROM $first TO $last, ${clips.size} clips in ${clips.map { it.rideId }.distinct().size} rides"
    }

    /** The Reel's script: the parts in filming order, the first as the hook. */
    fun script(title: String, hits: List<AskHit>): Script {
        val sec = ((hits.sumOf { it.lengthMs } + 999) / 1000).toInt()
        return Script(
            format = PieceFormat.REEL,
            title = title.ifBlank { "From ${rides(hits)} rides" },
            why = "Asked across all rides: ${hits.size} parts from ${rides(hits)} rides",
            lengthSec = sec.coerceIn(PieceFormat.REEL.minSec, PieceFormat.REEL.maxSec),
            shape = "list",
            hookLine = null,
            postCaption = "${title.ifBlank { "From my rides" }}\n#motovlog #bikelife #motorcycle",
            sections = hits.mapIndexed { i, h -> Section(if (i == 0) SectionKind.HOOK else SectionKind.PEAK, "moment", listOf(ScriptShot(h.momentId, h.inMs, h.outMs))) },
            vibe = Vibe.HYPE,
        )
    }
}

/** What Gemini is asked, and reading its answer. */
object AskPrompt {
    fun prompt(ask: String, menu: String, today: LocalDate): String = """
        A motorcycle rider asks the Keppo Moto app to find clips across ALL their rides and make a short video. Today is $today.
        Turn the ask into a search the phone runs itself. Use only what is in the menu below for events and things seen.

        MENU
        $menu

        THE ASK: "${ask.replace("\"", "'").take(300)}"

        Answer with JSON only:
        {"title": "a short name for the video, in the rider's words (max 40 letters)",
         "said": ["words or a phrase the rider said, as texted (Hinglish in English letters)"],
         "seen": ["words from THINGS SEEN only"],
         "events": ["keys from EVENTS only"],
         "voice": false, "from": "YYYY-MM-DD or null", "to": "YYYY-MM-DD or null",
         "rides": ["parts of ride names from RIDES only"], "lengthSec": null,
         "questions": [{"text": "a short question", "why": "one short line", "answers": [{"label": "short", "from": "YYYY-MM-DD or null", "to": "...", "lengthSec": 15, "voice": true}]}]}
        Rules: leave a list empty when the ask doesn't need it. "from": null in an answer means all rides.
        Ask at most 2 questions, only when the ask leaves open something that changes the result a lot (how far back, how long);
        never ask what the ask already says; each question has 2 to 4 answers; no questions when the ask is clear.
        Answers set only from, to, lengthSec or voice. Plain words, no emoji.
    """.trimIndent()

    /** Gemini's JSON → a plan; null when it can't be read or asks for nothing at all. */
    fun parse(json: String, zone: ZoneId = ZoneId.systemDefault()): AskPlan? = runCatching {
        val o = JSONObject(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } } }.orEmpty()
        val filter = AskFilter(
            title = o.optString("title").trim().take(40),
            said = list("said").take(6),
            seen = list("seen").take(6),
            events = list("events").mapNotNull { AskEvent.of(it) }.toSet(),
            voice = o.optBoolean("voice", false),
            fromMillis = date(o, "from", zone, end = false),
            toMillis = date(o, "to", zone, end = true),
            rides = list("rides").take(6),
            lengthSec = o.optInt("lengthSec", 0).takeIf { it in 5..AskSearch.MAX_SEC },
        )
        val questions = (o.optJSONArray("questions") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { i ->
                val q = a.optJSONObject(i) ?: return@mapNotNull null
                val answers = (q.optJSONArray("answers") ?: JSONArray()).let { aa ->
                    (0 until aa.length()).mapNotNull { j ->
                        val x = aa.optJSONObject(j) ?: return@mapNotNull null
                        val label = x.optString("label").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        AskAnswer(label.take(30), AskPatch(
                            fromMillis = date(x, "from", zone, end = false),
                            toMillis = date(x, "to", zone, end = true),
                            clearDates = x.has("from") && x.isNull("from") && !x.has("to"),
                            lengthSec = if (x.has("lengthSec") && !x.isNull("lengthSec")) x.optInt("lengthSec").takeIf { it in 5..AskSearch.MAX_SEC } else null,
                            anyLength = x.has("lengthSec") && x.isNull("lengthSec"),
                            voice = if (x.has("voice") && !x.isNull("voice")) x.optBoolean("voice") else null,
                        ))
                    }.take(4)
                }
                val text = q.optString("text").trim()
                if (text.isEmpty() || answers.size < 2) null else AskQuestion(text.take(80), q.optString("why").trim().takeIf { it.isNotEmpty() }?.take(120), answers)
            }.take(2)
        }
        AskPlan(filter, questions)
    }.getOrNull()

    private fun date(o: JSONObject, k: String, zone: ZoneId, end: Boolean): Long? {
        if (!o.has(k) || o.isNull(k)) return null
        val d = runCatching { LocalDate.parse(o.getString(k).trim().take(10)) }.getOrNull() ?: return null
        return if (end) d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 else d.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
