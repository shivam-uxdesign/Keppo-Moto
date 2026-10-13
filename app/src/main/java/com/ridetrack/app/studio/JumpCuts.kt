package com.ridetrack.app.studio

/**
 * Jump cuts for talking shots: long pauses between lines, "umm"s and false starts are cut out, so
 * the voice runs on. Riding before the first line and after the last stays. Pure, unit-tested.
 */
object JumpCuts {
    /** A pause longer than this between two lines is cut down to [KEEP_MS]. */
    const val PAUSE_MS = 900L

    /** What's kept of a cut pause: a breath after a line, and a little before the next. */
    private const val AFTER_MS = 200L
    private const val BEFORE_MS = 150L
    const val KEEP_MS = AFTER_MS + BEFORE_MS

    /** Pieces shorter than this aren't worth a cut. */
    private const val MIN_PIECE_MS = 600L

    private val FILLER = Regex("^\\W*(u+m+|u+h+|h+m+|e+r+m*|a+h+m+)\\W*$", RegexOption.IGNORE_CASE)

    fun isFiller(text: String): Boolean = FILLER.matches(text.trim())

    /** [a]'s words start the same way as [b]'s and stop short: the rider started again. */
    fun falseStart(a: String, b: String): Boolean {
        fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || x.size >= y.size) return false
        val same = x.zip(y).takeWhile { (p, q) -> p == q }.size
        return same == x.size || (same >= 2 && x.size <= 4)
    }

    /**
     * The parts of a shot from [a] to [b] (ms in its clip) to keep, given the clip's [lines]. One
     * range when nothing needs cutting.
     */
    fun ranges(a: Long, b: Long, lines: List<CaptionLine>): List<LongRange> {
        val inside = lines.filter { it.endMs > a && it.startMs < b }.sortedBy { it.startMs }
        if (inside.size < 2 && inside.none { isFiller(it.text) }) return listOf(a..b)
        // The lines that stay: no fillers, no line the next one starts over.
        val kept = inside.filterIndexed { i, l ->
            !isFiller(l.text) && !(i < inside.lastIndex && falseStart(l.text, inside[i + 1].text))
        }
        if (kept.isEmpty()) return listOf(a..b)
        val out = ArrayList<LongRange>()
        var start = a
        for (i in 0 until kept.lastIndex) {
            val gapFrom = kept[i].endMs
            val gapTo = kept[i + 1].startMs
            // Something was taken out between them, or they're far apart: cut.
            val removed = inside.any { it.startMs >= gapFrom && it.endMs <= gapTo }
            if (gapTo - gapFrom > PAUSE_MS || (removed && gapTo - gapFrom > KEEP_MS)) {
                val end = (gapFrom + AFTER_MS).coerceAtMost(b)
                if (end - start >= MIN_PIECE_MS) { out += start..end; start = (gapTo - BEFORE_MS).coerceAtLeast(end) }
            }
        }
        out += start..b
        return out.filter { it.last - it.first >= MIN_PIECE_MS }.ifEmpty { listOf(a..b) }
    }
}
