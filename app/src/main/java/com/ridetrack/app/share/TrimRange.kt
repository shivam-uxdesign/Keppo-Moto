package com.ridetrack.app.share

/**
 * The part of a clip to share, in ms from its first frame. Always at least [MIN_MILLIS]
 * long (or the whole clip, if shorter) and inside the clip. Pure, so it is unit-tested.
 */
data class TrimRange(val startMillis: Long, val endMillis: Long, val lengthMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
    val isWhole: Boolean get() = startMillis <= 0 && endMillis >= lengthMillis

    fun withStart(ms: Long): TrimRange = copy(startMillis = ms.coerceIn(0, (endMillis - MIN_MILLIS).coerceAtLeast(0)))
    fun withEnd(ms: Long): TrimRange = copy(endMillis = ms.coerceIn((startMillis + MIN_MILLIS).coerceAtMost(lengthMillis), lengthMillis))
    fun reset(): TrimRange = whole(lengthMillis)

    /** The overlay's clock for a frame [ptsMillis] into the trimmed video. */
    fun wallTime(videoStartMillis: Long, ptsMillis: Long): Long = videoStartMillis + startMillis + ptsMillis

    companion object {
        const val MIN_MILLIS = 1_000L

        fun whole(lengthMillis: Long) = TrimRange(0, lengthMillis.coerceAtLeast(0), lengthMillis.coerceAtLeast(0))

        /** From what's stored (nulls = the whole clip), made valid for a clip of [lengthMillis]. */
        fun of(lengthMillis: Long, start: Long?, end: Long?): TrimRange {
            val whole = whole(lengthMillis)
            if (start == null && end == null) return whole
            return whole.withEnd(end ?: lengthMillis).withStart(start ?: 0).withEnd(end ?: lengthMillis)
        }
    }
}
