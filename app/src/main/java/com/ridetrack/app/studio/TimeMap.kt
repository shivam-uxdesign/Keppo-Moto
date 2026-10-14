package com.ridetrack.app.studio

/**
 * Reel time ↔ timeline pixels. While a clip is selected, the rest of its recording shows beside
 * it ("make room"): [leftPx] of unused footage before [atMs] (its start in the Reel) and
 * [rightPx] after [endMs], so everything after the clip's start moves right by [leftPx], and
 * after its end by [rightPx] more. A position in the unused parts maps to the clip's edge. Pure.
 */
data class TimeMap(
    val pxPerMs: Float,
    val atMs: Long = 0,
    val endMs: Long = 0,
    val leftPx: Float = 0f,
    val rightPx: Float = 0f,
) {
    /** Where a block starting at [ms] begins. */
    fun x(ms: Long): Float = ms * pxPerMs + (if (leftPx > 0f && ms >= atMs) leftPx else 0f) + (if (rightPx > 0f && ms >= endMs) rightPx else 0f)

    /** Where a block ending at [ms] ends (one that ends where the selected clip starts doesn't stretch). */
    fun xEnd(ms: Long): Float = ms * pxPerMs + (if (leftPx > 0f && ms > atMs) leftPx else 0f) + (if (rightPx > 0f && ms > endMs) rightPx else 0f)

    /** The Reel time at [x] (in the unused parts: the selected clip's nearer edge). */
    fun ms(x: Float): Long {
        val a = atMs * pxPerMs
        val e = endMs * pxPerMs + leftPx
        return when {
            x < a -> (x / pxPerMs).toLong()
            x < a + leftPx -> atMs
            x < e -> ((x - leftPx) / pxPerMs).toLong()
            x < e + rightPx -> endMs
            else -> ((x - leftPx - rightPx) / pxPerMs).toLong()
        }
    }

    /** Whether [x] is in the unused parts of the selected clip. */
    fun unused(x: Float): Boolean {
        val a = atMs * pxPerMs
        val e = endMs * pxPerMs + leftPx
        return (x >= a && x < a + leftPx) || (x >= e && x < e + rightPx)
    }

    companion object {
        /** The map for [plan] with [selected] (a clip's index) showing the rest of its recording. */
        fun of(plan: StudioPlan, selected: Int?, pxPerMs: Float): TimeMap {
            val seg = selected?.let { plan.segments.getOrNull(it) as? ClipSegment }
            if (seg == null || seg.tail || seg.still != null) return TimeMap(pxPerMs)
            val f = Speed.outPerSource(seg.speed, seg.ramp)
            val at = plan.startOf(selected)
            val before = seg.inMs * f
            val after = ((seg.bit.clipDurationMs - seg.inMs - seg.sourceMs).coerceAtLeast(0)) * f
            return TimeMap(pxPerMs, at, at + seg.durMs, (before * pxPerMs).toFloat(), (after * pxPerMs).toFloat())
        }
    }
}
