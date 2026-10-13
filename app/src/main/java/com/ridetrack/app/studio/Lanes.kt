package com.ridetrack.app.studio

/**
 * Rows on the timeline: things that overlap in time go on rows of their own, so none hides
 * another. The list is top first (the top row's things are drawn over the rows below). Pure,
 * unit-tested.
 */
object Lanes {
    data class Span(val startMs: Long, val endMs: Long)

    /** Each span's row: the first row where it overlaps nothing already put there, in list order. */
    fun pack(spans: List<Span>): IntArray {
        val rows = ArrayList<MutableList<Span>>()
        return IntArray(spans.size) { i ->
            val s = spans[i]
            val r = rows.indexOfFirst { row -> row.none { it.startMs < s.endMs && s.startMs < it.endMs } }
            if (r >= 0) { rows[r] += s; r } else { rows += mutableListOf(s); rows.lastIndex }
        }
    }

    /** How many rows [rows] use. */
    fun count(rows: IntArray): Int = (rows.maxOrNull() ?: -1) + 1

    /**
     * A new order (indices into [spans], top first) with span [index] moved so it lands as near
     * row [target] as it can, moving past as few others as it can. The rest keep their order.
     */
    fun moveTo(spans: List<Span>, index: Int, target: Int): List<Int> {
        val rest = spans.indices.filter { it != index }
        var best = spans.indices.toList()
        var bestScore = Int.MAX_VALUE
        for (at in 0..rest.size) {
            val order = rest.toMutableList().apply { add(at, index) }
            val row = pack(order.map { spans[it] })[at]
            // Nearest row first, then the smallest change of order.
            val score = Math.abs(row - target) * 1_000 + Math.abs(at - index)
            if (score < bestScore) { bestScore = score; best = order }
        }
        return best
    }

    /**
     * [items] (drawn in order, so the last is on top) with the one called [id] moved [rows] rows
     * down the timeline (negative: up, towards the top, drawn over more of the others).
     */
    fun <T> restack(items: List<T>, id: String, rows: Int, idOf: (T) -> String, span: (T) -> Span): List<T> {
        if (rows == 0) return items
        val top = items.asReversed()
        val i = top.indexOfFirst { idOf(it) == id }
        if (i < 0) return items
        val spans = top.map(span)
        val target = (pack(spans)[i] + rows).coerceAtLeast(0)
        return moveTo(spans, i, target).map { top[it] }.asReversed()
    }
}
