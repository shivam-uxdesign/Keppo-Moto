package com.ridetrack.app.studio

import kotlin.math.ceil
import kotlin.math.roundToLong

/** The look and pace of a Reel. Each has one transition, one caption style and a colour grade. */
enum class Vibe(val label: String, val blurb: String, val bpm: Int, val beats: Int, val transitionMs: Long) {
    HYPE("Hype", "Fast cuts, bold words", 128, 4, 400),
    CINE("Cinematic", "Slow, wide, film look", 88, 4, 700),
    CHILL("Chill", "Easy pace, warm", 84, 6, 700),
    VLOG("Vlog", "Your voice leads", 100, 6, 600);

    val beatMs: Long get() = 60_000L / bpm
}

/** A line the rider said, in ms from the start of the clip (or of a segment, once planned). */
data class CaptionLine(val startMs: Long, val endMs: Long, val text: String)

/**
 * A usable part of a clip: a sentence or two the rider said, or a few seconds of riding.
 * Times are ms into the clip; [atMillis] is the wall time of [inMs].
 */
data class Bit(
    val id: String,
    val momentId: String,
    val clipDurationMs: Long,
    val inMs: Long,
    val outMs: Long,
    val atMillis: Long,
    val lines: List<CaptionLine>,
    val speedKmh: Double,
    /** How good this bit is, 0–10 (Gemini's pick, or the app's guess). */
    val punch: Float,
) {
    val talking: Boolean get() = lines.isNotEmpty()
}

sealed interface Segment {
    val durMs: Long
}

/** A clip part on screen from [inMs] (ms into the clip) for [durMs]; [lines] in ms from the segment start. */
data class ClipSegment(val bit: Bit, val inMs: Long, override val durMs: Long, val lines: List<CaptionLine>, val hook: Boolean) : Segment

/** The title over the first clip (or the route); the stats over the last. Both play muted. */
data class TitleSegment(override val durMs: Long) : Segment
data class StatsSegment(override val durMs: Long) : Segment

data class StudioOptions(
    val vibe: Vibe = Vibe.HYPE,
    val lengthSec: Int = 30,
    val intro: Boolean = true,
    val outro: Boolean = true,
    val map: Boolean = true,
    val captions: Boolean = true,
    val watermark: Boolean = true,
    val seed: Int = 1,
)

data class StudioPlan(val segments: List<Segment>, val vibe: Vibe) {
    val totalMs: Long = segments.sumOf { it.durMs }
    val clips: List<ClipSegment> get() = segments.filterIsInstance<ClipSegment>()

    fun startOf(i: Int): Long = segments.take(i).sumOf { it.durMs }

    /** Composition-time ranges where the rider is talking (the music dips there). */
    fun talkingRanges(): List<LongRange> {
        val out = ArrayList<LongRange>()
        segments.forEachIndexed { i, s ->
            if (s is ClipSegment) {
                val t0 = startOf(i)
                s.lines.forEach { l -> out += (t0 + l.startMs - 200).coerceAtLeast(t0)..(t0 + l.endMs + 300).coerceAtMost(t0 + s.durMs) }
            }
        }
        return out
    }
}

/** Picks and orders bits into a Reel. Pure: no Android, so it's unit-tested. */
object StudioPlanner {
    const val INTRO_MS = 2_000L
    const val OUTRO_MS = 3_000L
    /** A talking bit is at most this long; longer speech is split into more bits. */
    const val MAX_BIT_MS = 8_000L
    private const val PAD_BEFORE = 300L
    private const val PAD_AFTER = 350L
    val LENGTHS = listOf(30, 45, 60)

    /**
     * A clip's bits: its timed lines grouped into sentences of up to [MAX_BIT_MS] (a pause of
     * 1.5 s starts a new one), or, with no speech, [silentMs] of riding around [focusMs].
     */
    fun bitsOf(
        momentId: String,
        clipStartMillis: Long,
        clipDurationMs: Long,
        lines: List<CaptionLine>,
        speedAt: (Long) -> Double,
        focusMs: Long = clipDurationMs / 2,
        silentMs: Long = 4_000,
    ): List<Bit> {
        val said = lines.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
        if (said.isEmpty()) {
            if (clipDurationMs < 1_500) return emptyList()
            val len = silentMs.coerceAtMost(clipDurationMs)
            val a = (focusMs - len / 2).coerceIn(0, clipDurationMs - len)
            return listOf(Bit("$momentId#0", momentId, clipDurationMs, a, a + len, clipStartMillis + a, emptyList(), speedAt(clipStartMillis + a), 1f))
        }
        val groups = ArrayList<MutableList<CaptionLine>>()
        for (l in said) {
            val g = groups.lastOrNull()
            if (g != null && l.startMs - g.last().endMs < 1_500 && l.endMs - g.first().startMs <= MAX_BIT_MS) g += l else groups += mutableListOf(l)
        }
        return groups.mapIndexed { i, g ->
            val a = (g.first().startMs - PAD_BEFORE).coerceAtLeast(0)
            val b = (g.last().endMs + PAD_AFTER).coerceAtMost(clipDurationMs)
            Bit(
                id = "$momentId#$i",
                momentId = momentId,
                clipDurationMs = clipDurationMs,
                inMs = a,
                outMs = b,
                atMillis = clipStartMillis + a,
                lines = g.map { it.copy(startMs = it.startMs - a, endMs = it.endMs - a) },
                speedKmh = speedAt(clipStartMillis + a),
                punch = guessPunch(g),
            )
        }
    }

    /** The app's own guess when Gemini isn't there: short, loud, numbers, exclamations. */
    fun guessPunch(lines: List<CaptionLine>): Float {
        val text = lines.joinToString(" ") { it.text }
        var p = 3f
        if ('!' in text) p += 1.5f
        if (text.any { it.isDigit() }) p += 1f
        if (Regex("speed|sixty|seventy|eighty|fast|mast|full|bhai|yaar|wow", RegexOption.IGNORE_CASE).containsMatchIn(text)) p += 1f
        val words = text.split(Regex("\\s+")).size
        if (words in 3..10) p += 1f
        return p.coerceAtMost(9f)
    }

    /** How long a bit plays: speech to the end of its sentence on the beat; riding for a few beats. */
    fun durationOf(b: Bit, vibe: Vibe, shake: Double): Long {
        val beat = vibe.beatMs
        if (b.talking) {
            val need = b.lines.last().endMs + PAD_AFTER
            val d = ceil(need.toDouble() / beat).toLong() * beat
            return d.coerceAtMost(b.clipDurationMs - b.inMs).coerceAtLeast(minOf(beat * 2, b.clipDurationMs - b.inMs))
        }
        val beats = (vibe.beats * (0.75 + shake * 0.5)).roundToLong().coerceAtLeast(2)
        return (beats * beat).coerceAtMost(b.clipDurationMs - b.inMs)
    }

    /** Lengths worth offering: the ones the bits can mostly fill (always at least the shortest). */
    fun lengthsFor(bits: List<Bit>, vibe: Vibe): List<Int> {
        val footage = bits.sumOf { durationOf(it, vibe, 0.5) }
        val ok = LENGTHS.filter { L -> footage >= (L * 1000 - INTRO_MS - OUTRO_MS) * 0.75 }
        return ok.ifEmpty { LENGTHS.take(1) }
    }

    fun plan(bits: List<Bit>, o: StudioOptions): StudioPlan {
        val r = java.util.Random(o.seed.toLong() * 7919 + o.vibe.ordinal)
        val shakes = bits.associate { it.id to r.nextDouble() }
        var room = o.lengthSec * 1000L - (if (o.intro) INTRO_MS else 0) - (if (o.outro) OUTRO_MS else 0)
        val ranked = bits.map { it to it.punch + it.speedKmh / 40 + shakes.getValue(it.id) * (if (o.seed > 1) 2.5 else 1.0) }.sortedByDescending { it.second }
        data class Pick(val b: Bit, val d: Long)
        val picked = ArrayList<Pick>()
        for ((b, _) in ranked) {
            val d = durationOf(b, o.vibe, shakes.getValue(b.id))
            if (d <= 0 || d > room) continue
            // Never the same seconds twice (clips filmed back to back overlap).
            if (picked.any { p -> b.atMillis < p.b.atMillis + p.d && b.atMillis + d > p.b.atMillis }) continue
            picked += Pick(b, d)
            room -= d
            if (room < o.vibe.beatMs * 2) break
        }
        if (picked.isEmpty()) return StudioPlan(emptyList(), o.vibe)
        val hook = picked.first()
        val order = if (o.vibe == Vibe.VLOG) picked.sortedBy { it.b.atMillis } else listOf(hook) + picked.drop(1).sortedBy { it.b.atMillis }
        val segs = ArrayList<Segment>()
        if (o.intro) segs += TitleSegment(INTRO_MS)
        order.forEach { p -> segs += ClipSegment(p.b, p.b.inMs, p.d, p.b.lines, hook = p === hook && o.vibe != Vibe.VLOG) }
        if (o.outro) segs += StatsSegment(OUTRO_MS)
        return StudioPlan(segs, o.vibe)
    }

    // ---- edits ------------------------------------------------------------------------------

    /** Moves a clip's start or end by [deltaMs], keeping its captions where they were said. */
    fun nudge(plan: StudioPlan, index: Int, startDelta: Long, endDelta: Long): StudioPlan {
        val s = plan.segments.getOrNull(index) as? ClipSegment ?: return plan
        val newIn = (s.inMs + startDelta).coerceIn(0, s.inMs + s.durMs - 1_000)
        val shift = newIn - s.inMs
        val newDur = (s.durMs - shift + endDelta).coerceIn(1_000, s.bit.clipDurationMs - newIn)
        val lines = s.lines.map { it.copy(startMs = it.startMs - shift, endMs = it.endMs - shift) }.filter { it.endMs > 0 && it.startMs < newDur }
        return plan.copy(segments = plan.segments.toMutableList().also { it[index] = s.copy(inMs = newIn, durMs = newDur, lines = lines) })
    }

    /** Replaces a clip's words with [text], spread over the time the old words took. */
    fun setText(plan: StudioPlan, index: Int, text: String): StudioPlan {
        val s = plan.segments.getOrNull(index) as? ClipSegment ?: return plan
        val t = text.trim()
        val a = s.lines.firstOrNull()?.startMs ?: 300
        val b = s.lines.lastOrNull()?.endMs ?: (s.durMs - 300).coerceAtLeast(a + 800).coerceAtMost(a + 2_500)
        val lines = if (t.isEmpty()) emptyList() else listOf(CaptionLine(a, b, t))
        return plan.copy(segments = plan.segments.toMutableList().also { it[index] = s.copy(lines = lines) })
    }

    fun move(plan: StudioPlan, index: Int, by: Int): StudioPlan {
        val j = index + by
        val segs = plan.segments.toMutableList()
        if (segs.getOrNull(index) !is ClipSegment || segs.getOrNull(j) !is ClipSegment) return plan
        segs[index] = segs[j].also { segs[j] = segs[index] }
        return plan.copy(segments = segs)
    }

    fun remove(plan: StudioPlan, index: Int): StudioPlan {
        if (plan.clips.size <= 1 || plan.segments.getOrNull(index) !is ClipSegment) return plan
        return plan.copy(segments = plan.segments.toMutableList().also { it.removeAt(index) })
    }

    /** Swaps a clip for the unused bit filmed nearest to it. */
    fun swap(plan: StudioPlan, index: Int, bits: List<Bit>): StudioPlan {
        val s = plan.segments.getOrNull(index) as? ClipSegment ?: return plan
        val used = plan.clips.map { it.bit.id }.toSet()
        val alt = bits.filter { it.id !in used }.minByOrNull { kotlin.math.abs(it.atMillis - s.bit.atMillis) } ?: return plan
        val seg = ClipSegment(alt, alt.inMs, durationOf(alt, plan.vibe, 0.5), alt.lines, s.hook)
        return plan.copy(segments = plan.segments.toMutableList().also { it[index] = seg })
    }
}
