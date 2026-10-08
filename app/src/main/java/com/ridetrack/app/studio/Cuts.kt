package com.ridetrack.app.studio

/** Where transitions play: the same rule for the made video, the editor's preview and the timeline's marks. Pure. */
object Cuts {
    /** A cut that shows a transition: where it is (ms into the Reel), which one, how long, and whether the rider chose it. */
    data class Mark(val segment: Int, val atMs: Long, val kind: TransitionKind, val ms: Long, val chosen: Boolean)

    /** A transition between [a] and [b]: always, unless both are clips of the same script section. */
    fun boundary(a: Segment, b: Segment): Boolean =
        !(a is ClipSegment && b is ClipSegment && a.section >= 0 && a.section == b.section && !b.tail)

    /** Whether the cut into segment [i] plays a transition: one the rider chose always does (a Cut never does). */
    fun plays(plan: StudioPlan, i: Int): Boolean {
        val segs = plan.segments
        if (i <= 0 || i > segs.lastIndex) return false
        return (segs[i] as? ClipSegment)?.takeIf { !it.tail }?.transition?.let { it.kind != TransitionKind.CUT } ?: boundary(segs[i - 1], segs[i])
    }

    /** The frame [localMs] into segment [i] (shown for [durMs]), with its neighbours' transitions; [labels] say what the graphic shows. */
    fun frameAt(plan: StudioPlan, i: Int, localMs: Long, durMs: Long, labels: (Int) -> Pair<String, String> = { "" to "" }): FrameAt {
        val segs = plan.segments
        val own = (segs.getOrNull(i) as? ClipSegment)?.takeIf { !it.tail }?.transition
        val next = (segs.getOrNull(i + 1) as? ClipSegment)?.transition
        val (label, time) = labels(if (localMs < durMs / 2) i else i + 1)
        return FrameAt(
            vibe = plan.vibe,
            localMs = localMs,
            durMs = durMs,
            hasPrev = plays(plan, i),
            hasNext = plays(plan, i + 1),
            inKind = own?.kind ?: TransitionKind.STYLE,
            inMs = own?.ms(plan.vibe) ?: plan.vibe.transitionMs,
            outKind = next?.kind ?: TransitionKind.STYLE,
            outMs = next?.ms(plan.vibe) ?: plan.vibe.transitionMs,
            nextLabel = label,
            nextTime = time,
        )
    }

    /** Every cut that plays a transition, in order. */
    fun marks(plan: StudioPlan): List<Mark> = (1..plan.segments.lastIndex).filter { plays(plan, it) }.map { i ->
        val t = (plan.segments[i] as? ClipSegment)?.transition
        Mark(i, plan.startOf(i), t?.kind ?: TransitionKind.STYLE, t?.ms(plan.vibe) ?: plan.vibe.transitionMs, chosen = t != null)
    }
}
