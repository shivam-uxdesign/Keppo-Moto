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
    /** From another ride: its date ("Mon 5 Oct"), shown in Edit; null for this ride. */
    val fromRide: String? = null,
    /** A video from the phone's gallery: its content URI (the clip isn't a moment); null for moments. */
    val source: String? = null,
    /** Which camera filmed it: "back" for road shots; null = the selfie camera. */
    val camera: String? = null,
) {
    val talking: Boolean get() = lines.isNotEmpty()
}

sealed interface Segment {
    val durMs: Long
}

/**
 * A clip part on screen from [inMs] (ms into the clip) for [durMs]; [lines] in ms from the segment start.
 * The [tail] is a split second of the hook at the very end, so the Reel loops back into its start.
 */
data class ClipSegment(
    val bit: Bit,
    val inMs: Long,
    override val durMs: Long,
    val lines: List<CaptionLine>,
    val hook: Boolean,
    val tail: Boolean = false,
    /** A 1–2 s flash-forward of a later moment at the very start (the one case a clip shows twice). */
    val teaser: Boolean = false,
    /** The script section it belongs to: cuts inside a section are plain, the vibe's transition plays between sections. -1 = its own. */
    val section: Int = -1,
    /** On-screen text for the start of its section (from the script); null = none. */
    val text: String? = null,
    /** The clip's own sound: 1 = as recorded, 0 = muted, up to 1.5 = louder. */
    val volume: Float = 1f,
    /** Playback speed (0.25–4) and how it changes across the clip. */
    val speed: Float = 1f,
    val ramp: SpeedRamp = SpeedRamp.NONE,
    /** The part played backwards: a file made once when Reverse was tapped; null = forwards. */
    val reverse: String? = null,
    /** A freeze frame: this picture held for [durMs]; null = the moving clip. */
    val still: String? = null,
    /** Turned (0, 90, 180, 270) and mirrored. */
    val rotation: Int = 0,
    val flip: Boolean = false,
    /** Zoom and pan inside the clip over time (a crop, or a move); empty = the style's camera only. */
    val frame: List<FrameKey> = emptyList(),
    val color: ClipColor = ClipColor(),
    /** The transition into this clip; null = the style's own. */
    val transition: Transition? = null,
) : Segment {
    /** How much of the clip this plays, in its own ms (more than [durMs] when sped up). */
    val sourceMs: Long get() = if (still != null) 0 else Speed.sourceMs(durMs, speed, ramp)
}

/** How the speed changes across a clip: (end as a fraction of the clip's part, speed multiplier) steps. */
enum class SpeedRamp(val label: String, val steps: List<Pair<Float, Float>>) {
    NONE("Even", listOf(1f to 1f)),
    MIDDLE("Slow in the middle", listOf(0.3f to 1.6f, 0.7f to 0.4f, 1f to 1.6f)),
    EASE_OUT("Fast, then slow", listOf(0.5f to 2f, 1f to 0.5f)),
    SPEED_UP("Slow, then fast", listOf(0.5f to 0.5f, 1f to 2f)),
}

/** Speed maths: output length from the clip's part and back. Pure. */
object Speed {
    val CHOICES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

    /** Output ms per ms of the clip. */
    fun outPerSource(speed: Float, ramp: SpeedRamp): Double {
        var prev = 0f
        var sum = 0.0
        ramp.steps.forEach { (end, mult) ->
            sum += (end - prev) / (speed * mult).toDouble()
            prev = end
        }
        return sum
    }

    fun sourceMs(outMs: Long, speed: Float, ramp: SpeedRamp): Long =
        if (speed == 1f && ramp == SpeedRamp.NONE) outMs else (outMs / outPerSource(speed, ramp)).toLong()

    fun outMs(sourceMs: Long, speed: Float, ramp: SpeedRamp): Long =
        if (speed == 1f && ramp == SpeedRamp.NONE) sourceMs else (sourceMs * outPerSource(speed, ramp)).toLong()

    /** The speed at [fraction] (0..1) through the clip's part. */
    fun at(speed: Float, ramp: SpeedRamp, fraction: Float): Float =
        speed * (ramp.steps.firstOrNull { fraction < it.first }?.second ?: ramp.steps.last().second)
}

/** Zoom ([zoom] ≥ 1) and pan ([x], [y] in -1..1 of the room the zoom gives) at [atMs] into the segment. */
data class FrameKey(val atMs: Long, val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f)

/** A clip's colour: each -1..1 around 0 (as filmed); [look] keeps the style's own grade on top. */
data class ClipColor(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val look: Boolean = true,
) {
    val plain: Boolean get() = exposure == 0f && contrast == 0f && saturation == 0f && warmth == 0f
}

/** The transitions that can go on a cut. */
enum class TransitionKind(val label: String) {
    STYLE("Style"), CUT("Cut"), FADE("Fade"), FLASH("Flash"), ZOOM("Zoom punch"), WHIP("Whip"), GLITCH("Glitch"),
    SLASH("Slash"), SHUTTER("Shutter"), SUN("Sun"), CARD("Card"),
}

enum class TransitionLength(val label: String, val factor: Float) { SHORT("Short", 0.5f), NORMAL("Normal", 1f), LONG("Long", 1.6f) }

data class Transition(val kind: TransitionKind = TransitionKind.STYLE, val length: TransitionLength = TransitionLength.NORMAL) {
    /** How long it takes across the cut with [vibe]'s timing. */
    fun ms(vibe: Vibe): Long = (vibe.transitionMs * length.factor).toLong()
}

/** The route-sketch opening (optional); the stats over the last clip. Both play muted. */
data class TitleSegment(override val durMs: Long) : Segment
data class StatsSegment(override val durMs: Long) : Segment

data class StudioOptions(
    val vibe: Vibe = Vibe.HYPE,
    val lengthSec: Int = 30,
    /** A 2 s route sketch before the first clip. Off: the Reel opens on its best moment (better for reach). */
    val intro: Boolean = false,
    val outro: Boolean = true,
    /** End on a split second of the opening shot, so the Reel loops. */
    val loopEnd: Boolean = true,
    /** The song's tempo, so cuts land on its beats; null = the vibe's own pace. */
    val bpm: Int? = null,
    val map: Boolean = true,
    val captions: Boolean = true,
    val watermark: Boolean = true,
    val seed: Int = 1,
    /** Open with a 1–2 s flash-forward of a later moment, before the hook. */
    val teaser: Boolean = false,
)

/** Text the rider placed on the timeline: shown from [startMs] to [endMs] (Reel time), [y] = 0 top … 1 bottom. */
data class TextItem(val id: String, val startMs: Long, val endMs: Long, val text: String, val y: Float = 0.3f)

/** How a layer's corners are cut. */
enum class LayerShape(val label: String) { RECT("Square"), ROUNDED("Rounded"), CIRCLE("Circle") }

/** Where a layer is at [atMs] (ms from the layer's start): its centre (0..1 of the frame) and width (0..1). */
data class LayerKey(val atMs: Long, val cx: Float, val cy: Float, val w: Float)

/**
 * A video over the main clips (picture-in-picture, a split half, a stack): [inMs] into its clip,
 * shown from [startMs] (Reel time) for [durMs]. Centre [cx], [cy] and width [w] are fractions of
 * the frame; [aspect] is its width over height. [keys] move or zoom it over time.
 */
data class LayerItem(
    val id: String,
    val bit: Bit,
    val inMs: Long,
    val startMs: Long,
    val durMs: Long,
    val cx: Float = 0.72f,
    val cy: Float = 0.22f,
    val w: Float = 0.42f,
    val aspect: Float = 9f / 16f,
    val rotation: Float = 0f,
    val shape: LayerShape = LayerShape.ROUNDED,
    val opacity: Float = 1f,
    val border: Boolean = true,
    /** Its own sound, 0 = muted (the default: the main clip's sound plays). */
    val volume: Float = 0f,
    val keys: List<LayerKey> = emptyList(),
) {
    val endMs: Long get() = startMs + durMs
}

/** The sound tracks of the edit. */
enum class TrackKind(val label: String) {
    CLIPS("Clip sound"), DETACHED("Detached sound"), ENGINE("Engine"), VOICE_OVER("Voice-over"), MUSIC("Music"), LAYERS("Layer sound"),
}

/** A volume point on a sound: [atMs] from its start, [level] 0..1.5. */
data class VolumePoint(val atMs: Long, val level: Float)

/**
 * A sound on its own track: [inMs] into [bit]'s clip (or its engine file), played from [startMs]
 * (Reel time) for [durMs]. A detached clip sound can run past its cut (J and L cuts).
 */
data class AudioItem(
    val id: String,
    val kind: TrackKind,
    val bit: Bit,
    val inMs: Long,
    val startMs: Long,
    val durMs: Long,
    val volume: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    /** The engine mic's file for the clip (two-mic recording); null = the clip's own sound. */
    val file: String? = null,
    val curve: List<VolumePoint> = emptyList(),
) {
    val endMs: Long get() = startMs + durMs
}

/** The mixer: each track's volume, muted tracks, a soloed one, and the voice clean-up. */
data class TrackMix(
    val volumes: Map<TrackKind, Float> = emptyMap(),
    val muted: Set<TrackKind> = emptySet(),
    val solo: TrackKind? = null,
    /** Music and engine dip while you talk. */
    val duck: Boolean = true,
    /** Cuts wind rumble and engine drone under your voice. */
    val cleanVoice: Boolean = false,
) {
    fun volume(k: TrackKind): Float = volumes[k] ?: 1f

    /** What the track actually plays at, after mute and solo. */
    fun gain(k: TrackKind): Float = when {
        solo != null && solo != k -> 0f
        k in muted -> 0f
        else -> volume(k)
    }
}

/** A marker the rider dropped while watching, at [atMs] (Reel time). */
data class Marker(val id: String, val atMs: Long, val label: String = "")

data class StudioPlan(
    val segments: List<Segment>,
    val vibe: Vibe,
    val texts: List<TextItem> = emptyList(),
    val layers: List<LayerItem> = emptyList(),
    val audio: List<AudioItem> = emptyList(),
    val mix: TrackMix = TrackMix(),
    val markers: List<Marker> = emptyList(),
) {
    val totalMs: Long = segments.sumOf { it.durMs }
    /** The clips the rider chose (the loop tail and the flash-forward teaser are not among them). */
    val clips: List<ClipSegment> get() = segments.filterIsInstance<ClipSegment>().filter { !it.tail && !it.teaser }

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
    const val OUTRO_MS = 1_500L
    /** The loop tail: the hook's first split second, again. */
    const val TAIL_MS = 600L
    /** The flash-forward teaser at the start. */
    const val TEASER_MS = 1_200L
    /** A talking bit is at most this long; longer speech is split into more bits. */
    const val MAX_BIT_MS = 8_000L
    private const val PAD_BEFORE = 300L
    private const val PAD_AFTER = 350L
    val LENGTHS = listOf(15, 30, 45, 60)

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
    fun durationOf(b: Bit, vibe: Vibe, shake: Double, beatMs: Long = vibe.beatMs): Long {
        val beat = beatMs
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

    /**
     * The Reel: the best bits that fit, the hook first, then the ride in order. With a [story]
     * (bit ids Gemini grouped as one idea) those bits come first; an [ending] (a sign-off) goes last.
     */
    fun plan(bits: List<Bit>, o: StudioOptions, story: List<String> = emptyList(), ending: String? = null): StudioPlan {
        val r = java.util.Random(o.seed.toLong() * 7919 + o.vibe.ordinal)
        val beat = o.bpm?.takeIf { it in 50..200 }?.let { 60_000L / it } ?: o.vibe.beatMs
        val shakes = bits.associate { it.id to r.nextDouble() }
        var room = o.lengthSec * 1000L - (if (o.intro) INTRO_MS else 0) - (if (o.outro) OUTRO_MS else 0) - (if (o.loopEnd) TAIL_MS else 0) -
            (if (o.teaser) TEASER_MS else 0)
        val inStory = story.toSet()
        val ranked = bits.map {
            it to it.punch + it.speedKmh / 40 + shakes.getValue(it.id) * (if (o.seed > 1 && story.isEmpty()) 2.5 else 1.0) +
                (if (it.id in inStory) 100 else 0) + (if (it.id == ending) 50 else 0)
        }.sortedByDescending { it.second }
        data class Pick(val b: Bit, val d: Long)
        val picked = ArrayList<Pick>()
        for ((b, _) in ranked) {
            val d = durationOf(b, o.vibe, shakes.getValue(b.id), beat)
            if (d <= 0 || d > room) continue
            // Never the same seconds twice (clips filmed back to back overlap).
            if (picked.any { p -> b.atMillis < p.b.atMillis + p.d && b.atMillis + d > p.b.atMillis }) continue
            picked += Pick(b, d)
            room -= d
            if (room < beat * 2) break
        }
        if (picked.isEmpty()) return StudioPlan(emptyList(), o.vibe)
        val end = picked.firstOrNull { it.b.id == ending && picked.size > 1 }
        val hook = (picked - setOfNotNull(end)).maxBy { it.b.punch + (if (it.b.id in inStory) 0.01f else 0f) }
        val middle = picked.filter { it !== hook && it !== end }.sortedBy { it.b.atMillis }
        val order = if (o.vibe == Vibe.VLOG) (picked - setOfNotNull(end)).sortedBy { it.b.atMillis } + listOfNotNull(end) else listOf(hook) + middle + listOfNotNull(end)
        val segs = ArrayList<Segment>()
        if (o.intro) segs += TitleSegment(INTRO_MS)
        // A flash of the fastest later moment (the one case a clip shows twice); then it plays in its place.
        if (o.teaser && order.size >= 3) {
            val t = order.drop(1).maxBy { it.b.speedKmh + it.b.punch }
            val d = TEASER_MS.coerceAtMost(t.d)
            segs += ClipSegment(t.b, t.b.inMs + (t.d - d) / 2, d, emptyList(), hook = false, teaser = true)
        }
        order.forEach { p -> segs += ClipSegment(p.b, p.b.inMs, p.d, p.b.lines, hook = p === order.first()) }
        if (o.outro) segs += StatsSegment(OUTRO_MS)
        if (o.loopEnd) {
            val first = segs.filterIsInstance<ClipSegment>().first()
            segs += ClipSegment(first.bit, first.inMs, TAIL_MS.coerceAtMost(first.durMs), emptyList(), hook = false, tail = true)
        }
        return StudioPlan(segs, o.vibe)
    }

    // ---- edits ------------------------------------------------------------------------------

    /** Moves a clip's start or end by [deltaMs], keeping its captions where they were said. */
    fun nudge(plan: StudioPlan, index: Int, startDelta: Long, endDelta: Long): StudioPlan {
        val s = (plan.segments.getOrNull(index) as? ClipSegment)?.takeIf { !it.tail && !it.teaser } ?: return plan
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
        val a = segs.getOrNull(index) as? ClipSegment
        val b = segs.getOrNull(j) as? ClipSegment
        if (a == null || b == null || a.tail || b.tail || a.teaser || b.teaser) return plan
        segs[index] = b.copy(hook = a.hook)
        segs[j] = a.copy(hook = b.hook)
        return retail(plan.copy(segments = segs))
    }

    fun remove(plan: StudioPlan, index: Int): StudioPlan {
        val s = plan.segments.getOrNull(index) as? ClipSegment
        if (plan.clips.size <= 1 || s == null || s.tail) return plan
        val segs = plan.segments.toMutableList().also { it.removeAt(index) }
        // A teaser of a clip that's gone would show something the Reel never gets to.
        if (!s.teaser) segs.removeAll { it is ClipSegment && it.teaser && it.bit.id == s.bit.id }
        // The next clip opens the Reel if the hook went.
        if (s.hook) segs.indexOfFirst { it is ClipSegment }.takeIf { it >= 0 }?.let { segs[it] = (segs[it] as ClipSegment).copy(hook = true) }
        return retail(plan.copy(segments = segs))
    }

    /** Swaps a clip for the unused bit filmed nearest to it. */
    fun swap(plan: StudioPlan, index: Int, bits: List<Bit>): StudioPlan {
        val s = (plan.segments.getOrNull(index) as? ClipSegment)?.takeIf { !it.tail && !it.teaser } ?: return plan
        val used = plan.clips.map { it.bit.id }.toSet()
        val alt = bits.filter { it.id !in used && it.fromRide == null }.minByOrNull { kotlin.math.abs(it.atMillis - s.bit.atMillis) } ?: return plan
        val seg = ClipSegment(alt, alt.inMs, durationOf(alt, plan.vibe, 0.5), alt.lines, s.hook)
        return retail(plan.copy(segments = plan.segments.toMutableList().also { it[index] = seg }))
    }

    /** Adds a bit (e.g. from another ride) as the last clip, before the stats. */
    fun add(plan: StudioPlan, bit: Bit): StudioPlan {
        if (plan.clips.any { it.bit.id == bit.id }) return plan
        val segs = plan.segments.toMutableList()
        val at = segs.indexOfLast { it is ClipSegment && !it.tail } + 1
        segs.add(at, ClipSegment(bit, bit.inMs, durationOf(bit, plan.vibe, 0.5), bit.lines, hook = plan.clips.isEmpty()))
        return retail(plan.copy(segments = segs))
    }

    /** Keeps the loop tail showing whatever opens the Reel now. */
    private fun retail(plan: StudioPlan): StudioPlan {
        val i = plan.segments.indexOfFirst { it is ClipSegment && it.tail }
        if (i < 0) return plan
        val first = plan.clips.firstOrNull() ?: return plan
        val segs = plan.segments.toMutableList()
        segs[i] = ClipSegment(first.bit, first.inMs, TAIL_MS.coerceAtMost(first.durMs), emptyList(), hook = false, tail = true)
        return plan.copy(segments = segs)
    }
}
