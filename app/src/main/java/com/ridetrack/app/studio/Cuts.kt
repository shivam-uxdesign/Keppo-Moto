package com.ridetrack.app.studio

/** Where transitions play: the same rule for the made video, the editor's preview and the timeline's marks. Pure. */
object Cuts {
    /**
     * A cut: where it is (ms into the Reel), which transition, how long, whether the rider chose
     * it, and whether it [plays] one (false: a plain cut).
     */
    data class Mark(val segment: Int, val atMs: Long, val kind: TransitionKind, val ms: Long, val chosen: Boolean, val plays: Boolean = true)

    /** A transition between [a] and [b]: always, unless both are clips of the same script section. */
    fun boundary(a: Segment, b: Segment): Boolean =
        !(a is ClipSegment && b is ClipSegment && a.section >= 0 && a.section == b.section && !b.tail)

    /** Whether the cut into segment [i] plays a transition: one the rider chose always does (a Cut never does). */
    fun plays(plan: StudioPlan, i: Int): Boolean {
        val segs = plan.segments
        if (i <= 0 || i > segs.lastIndex) return false
        return (segs[i] as? ClipSegment)?.takeIf { !it.tail }?.transition?.let { it.kind != TransitionKind.CUT } ?: boundary(segs[i - 1], segs[i])
    }

    /**
     * The frame [localMs] into segment [i] (shown for [durMs]), with its neighbours' transitions;
     * [beatMs] is the song's beat when the rider set a tempo.
     */
    fun frameAt(plan: StudioPlan, i: Int, localMs: Long, durMs: Long, beatMs: Long? = null): FrameAt {
        val segs = plan.segments
        val own = (segs.getOrNull(i) as? ClipSegment)?.takeIf { !it.tail }?.transition
        val next = (segs.getOrNull(i + 1) as? ClipSegment)?.transition
        val inMs = own?.ms(plan.vibe) ?: plan.vibe.transitionMs
        val outMs = next?.ms(plan.vibe) ?: plan.vibe.transitionMs
        return FrameAt(
            vibe = plan.vibe,
            localMs = localMs,
            durMs = durMs,
            hasPrev = plays(plan, i),
            hasNext = plays(plan, i + 1),
            inKind = own?.kind ?: TransitionKind.STYLE,
            inMs = fitted(inMs, segs.getOrNull(i - 1)?.durMs, durMs),
            outKind = next?.kind ?: TransitionKind.STYLE,
            outMs = fitted(outMs, durMs, segs.getOrNull(i + 1)?.durMs),
            cut = i,
            beatMs = beatMs,
        )
    }

    /**
     * A transition's length across a cut, shortened so neither half takes more than 40% of the
     * clip it falls on (both halves shrink alike, so short clips still get their transition).
     */
    fun fitted(ms: Long, before: Long?, after: Long?): Long {
        val half = listOfNotNull(ms / 2, before?.let { it * 2 / 5 }, after?.let { it * 2 / 5 }).min()
        return half * 2
    }

    /**
     * Every cut, in order, so each can be tapped to choose its transition. One that plays nothing
     * (inside a script section, or set to None) has [Mark.plays] false and kind [TransitionKind.CUT].
     */
    fun marks(plan: StudioPlan): List<Mark> = (1..plan.segments.lastIndex).map { i ->
        val t = (plan.segments[i] as? ClipSegment)?.transition
        val on = plays(plan, i)
        Mark(i, plan.startOf(i), if (on) t?.kind ?: TransitionKind.STYLE else TransitionKind.CUT, t?.ms(plan.vibe) ?: plan.vibe.transitionMs, chosen = t != null, plays = on)
    }
}
