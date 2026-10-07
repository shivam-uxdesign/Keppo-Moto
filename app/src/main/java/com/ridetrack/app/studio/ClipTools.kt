package com.ridetrack.app.studio

import kotlin.math.abs

/**
 * The editor's clip tools: speed and ramps, duplicate, replace, slip, freeze, reverse, rotate,
 * flip, reframe, colour and the transition on each cut. Every change returns a new plan. Pure,
 * unit-tested.
 */
object ClipTools {
    private fun clip(plan: StudioPlan, i: Int): ClipSegment? = (plan.segments.getOrNull(i) as? ClipSegment)?.takeIf { !it.tail }

    private fun with(plan: StudioPlan, i: Int, seg: Segment) = plan.copy(segments = plan.segments.toMutableList().also { it[i] = seg })

    /** Words' times scaled from [from] ms long to [to] ms long. */
    private fun scaleLines(lines: List<CaptionLine>, from: Long, to: Long): List<CaptionLine> {
        if (from <= 0 || from == to) return lines
        val k = to.toDouble() / from
        return lines.map { it.copy(startMs = (it.startMs * k).toLong(), endMs = (it.endMs * k).toLong()) }
    }

    /** A new speed: the same part of the clip plays faster or slower (the Reel gets shorter or longer). */
    fun speed(plan: StudioPlan, i: Int, speed: Float): StudioPlan {
        val s = clip(plan, i) ?: return plan
        if (s.still != null) return plan
        val v = speed.coerceIn(0.25f, 4f)
        val dur = Speed.outMs(s.sourceMs, v, s.ramp).coerceAtLeast(TimelineEdits.MIN_MS)
        return TimelineEdits.normalize(with(plan, i, s.copy(speed = v, durMs = dur, lines = scaleLines(s.lines, s.durMs, dur))))
    }

    fun ramp(plan: StudioPlan, i: Int, ramp: SpeedRamp): StudioPlan {
        val s = clip(plan, i) ?: return plan
        if (s.still != null) return plan
        val dur = Speed.outMs(s.sourceMs, s.speed, ramp).coerceAtLeast(TimelineEdits.MIN_MS)
        return TimelineEdits.normalize(with(plan, i, s.copy(ramp = ramp, durMs = dur, lines = scaleLines(s.lines, s.durMs, dur))))
    }

    /** The same clip again, right after it. */
    fun duplicate(plan: StudioPlan, i: Int): StudioPlan {
        val s = clip(plan, i) ?: return plan
        return TimelineEdits.normalize(plan.copy(segments = plan.segments.toMutableList().also { it.add(i + 1, s.copy(hook = false, transition = null)) }))
    }

    /** Another clip in its place, as long as this one was (if it's long enough). */
    fun replace(plan: StudioPlan, i: Int, bit: Bit): StudioPlan {
        val s = clip(plan, i) ?: return plan
        val f = Speed.outPerSource(s.speed, s.ramp)
        val max = ((bit.clipDurationMs - bit.inMs) * f).toLong()
        val dur = s.durMs.coerceAtMost(max).coerceAtLeast(TimelineEdits.MIN_MS)
        val seg = s.copy(bit = bit, inMs = bit.inMs, durMs = dur, lines = bit.lines.filter { it.startMs < dur }, reverse = null, still = null, frame = emptyList())
        return TimelineEdits.normalize(with(plan, i, seg))
    }

    /** Uses a different part of the same clip, keeping the length: [deltaMs] of the clip's own time. */
    fun slip(plan: StudioPlan, i: Int, deltaMs: Long): StudioPlan {
        val s = clip(plan, i) ?: return plan
        if (s.still != null) return plan
        val newIn = (s.inMs + deltaMs).coerceIn(0, (s.bit.clipDurationMs - s.sourceMs).coerceAtLeast(0))
        val shift = ((newIn - s.inMs) * Speed.outPerSource(s.speed, s.ramp)).toLong()
        if (shift == 0L) return plan
        val lines = s.lines.map { it.copy(startMs = it.startMs - shift, endMs = it.endMs - shift) }.filter { it.endMs > 0 && it.startMs < s.durMs }
        return TimelineEdits.normalize(with(plan, i, s.copy(inMs = newIn, lines = lines, reverse = null)))
    }

    /**
     * Holds the frame at [atMs] (Reel time) for [holdMs]: the clip under it is cut there and the
     * picture [file] goes in between.
     */
    fun freeze(plan: StudioPlan, atMs: Long, file: String, holdMs: Long = 2_000): StudioPlan {
        val (i, local) = TimelineEdits.at(plan, atMs)
        val s = clip(plan, i) ?: return plan
        val split = if (local >= TimelineEdits.MIN_MS && local <= s.durMs - TimelineEdits.MIN_MS) TimelineEdits.split(plan, atMs) else plan
        val at = if (split === plan) (if (local < s.durMs / 2) i else i + 1) else i + 1
        val srcAt = s.inMs + (local / Speed.outPerSource(s.speed, s.ramp)).toLong()
        val hold = s.copy(
            inMs = srcAt.coerceAtMost(s.bit.clipDurationMs - 1), durMs = holdMs, lines = emptyList(), hook = false, text = null, volume = 0f,
            speed = 1f, ramp = SpeedRamp.NONE, reverse = null, still = file, frame = emptyList(), transition = Transition(TransitionKind.CUT),
        )
        return TimelineEdits.normalize(split.copy(segments = split.segments.toMutableList().also { it.add(at, hold) }))
    }

    /** Plays the part backwards from [file] (made once from the clip); null = forwards again. */
    fun reverse(plan: StudioPlan, i: Int, file: String?): StudioPlan {
        val s = clip(plan, i) ?: return plan
        return with(plan, i, s.copy(reverse = file, lines = if (file != null) emptyList() else s.lines))
    }

    fun rotate(plan: StudioPlan, i: Int): StudioPlan = clip(plan, i)?.let { with(plan, i, it.copy(rotation = (it.rotation + 90) % 360)) } ?: plan

    fun flip(plan: StudioPlan, i: Int): StudioPlan = clip(plan, i)?.let { with(plan, i, it.copy(flip = !it.flip)) } ?: plan

    // ---- reframe -----------------------------------------------------------------------------

    /** Zoom and pan [localMs] into the segment: between its keys (eased), or as filmed. */
    fun frameAt(s: ClipSegment, localMs: Long): FrameKey {
        val keys = s.frame
        if (keys.isEmpty()) return FrameKey(localMs)
        if (localMs <= keys.first().atMs) return keys.first().copy(atMs = localMs)
        if (localMs >= keys.last().atMs) return keys.last().copy(atMs = localMs)
        val b = keys.indexOfFirst { it.atMs >= localMs }
        val k1 = keys[b - 1]
        val k2 = keys[b]
        val f = (localMs - k1.atMs).toFloat() / (k2.atMs - k1.atMs).coerceAtLeast(1)
        val e = f * f * (3 - 2 * f)
        return FrameKey(localMs, k1.zoom + (k2.zoom - k1.zoom) * e, k1.x + (k2.x - k1.x) * e, k1.y + (k2.y - k1.y) * e)
    }

    /**
     * Zooms by [dZoom] and pans by [dx], [dy] at [localMs] (a key there; the first key change also
     * holds the start where it was, so it moves from there).
     */
    fun reframe(plan: StudioPlan, i: Int, localMs: Long, dZoom: Float = 0f, dx: Float = 0f, dy: Float = 0f, fixed: Boolean = false): StudioPlan {
        val s = clip(plan, i) ?: return plan
        val t = localMs.coerceIn(0, s.durMs)
        val here = frameAt(s, t)
        val k = FrameKey(t, (here.zoom + dZoom).coerceIn(1f, 3f), (here.x + dx).coerceIn(-1f, 1f), (here.y + dy).coerceIn(-1f, 1f))
        // A crop (no move): one key for the whole clip.
        if (fixed || s.frame.isEmpty() && t < 100) return with(plan, i, s.copy(frame = listOf(k.copy(atMs = 0))))
        val start = if (s.frame.isEmpty()) listOf(FrameKey(0)) else emptyList()
        val keys = (s.frame + start).filter { abs(it.atMs - t) > 100 } + k
        return with(plan, i, s.copy(frame = keys.sortedBy { it.atMs }))
    }

    fun clearFrame(plan: StudioPlan, i: Int): StudioPlan = clip(plan, i)?.let { with(plan, i, it.copy(frame = emptyList())) } ?: plan

    /** A quick push-in on each thing said (the emphasis), easing back after. */
    fun punchIn(plan: StudioPlan, i: Int, zoom: Float = 1.14f): StudioPlan {
        val s = clip(plan, i) ?: return plan
        if (s.lines.isEmpty()) return plan
        val keys = ArrayList<FrameKey>()
        keys += FrameKey(0)
        s.lines.forEach { l ->
            val a = (l.startMs - 60).coerceIn(0, s.durMs)
            val b = (l.startMs + 140).coerceIn(0, s.durMs)
            val c = minOf(l.endMs, l.startMs + 900).coerceIn(0, s.durMs)
            if (keys.isNotEmpty() && a <= keys.last().atMs) return@forEach
            keys += FrameKey(a)
            if (b > a) keys += FrameKey(b, zoom)
            if (c > b) keys += FrameKey(c)
        }
        return with(plan, i, s.copy(frame = keys.distinctBy { it.atMs }))
    }

    // ---- colour ------------------------------------------------------------------------------

    fun color(plan: StudioPlan, i: Int, f: (ClipColor) -> ClipColor): StudioPlan {
        val s = clip(plan, i) ?: return plan
        val c = f(s.color)
        return with(plan, i, s.copy(color = c.copy(exposure = c.exposure.coerceIn(-1f, 1f), contrast = c.contrast.coerceIn(-1f, 1f), saturation = c.saturation.coerceIn(-1f, 1f), warmth = c.warmth.coerceIn(-1f, 1f))))
    }

    // ---- transitions -------------------------------------------------------------------------

    /** The transition into the clip at [i] (the cut before it); null = the style's own. */
    fun transition(plan: StudioPlan, i: Int, t: Transition?): StudioPlan {
        val s = (plan.segments.getOrNull(i) as? ClipSegment) ?: return plan
        return with(plan, i, s.copy(transition = t))
    }

    /** The same transition on every cut. */
    fun transitionAll(plan: StudioPlan, t: Transition?): StudioPlan = plan.copy(
        segments = plan.segments.mapIndexed { k, seg -> if (k > 0 && seg is ClipSegment && !seg.tail && seg.still == null) seg.copy(transition = t) else seg },
    )
}
