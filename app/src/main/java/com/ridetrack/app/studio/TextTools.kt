package com.ridetrack.app.studio

/** Text styles and animations, the captions' look, and stickers. Every change returns a new plan. Pure, unit-tested. */
object TextTools {
    private fun text(plan: StudioPlan, id: String, f: (TextItem) -> TextItem?) = plan.copy(texts = plan.texts.mapNotNull { if (it.id == id) f(it) else it })

    /** Puts the text's centre at [x], [y] (kept on screen). */
    fun place(plan: StudioPlan, id: String, x: Float, y: Float) = text(plan, id) { it.copy(x = x.coerceIn(0.05f, 0.95f), y = y.coerceIn(0.05f, 0.95f)) }

    fun scale(plan: StudioPlan, id: String, factor: Float) = text(plan, id) { it.copy(size = (it.size * factor).coerceIn(0.4f, 3f)) }

    fun rotate(plan: StudioPlan, id: String, degrees: Float) = text(plan, id) { it.copy(rotation = ((it.rotation + degrees + 180f) % 360f + 360f) % 360f - 180f) }

    fun look(plan: StudioPlan, id: String, look: TextLook) = text(plan, id) { it.copy(look = look) }

    fun color(plan: StudioPlan, id: String, color: Int?) = text(plan, id) { it.copy(color = color) }

    fun align(plan: StudioPlan, id: String, a: TextAlignment) = text(plan, id) { it.copy(align = a) }

    fun anim(plan: StudioPlan, id: String, animIn: TextAnim? = null, animOut: TextAnim? = null) =
        text(plan, id) { it.copy(animIn = animIn ?: it.animIn, animOut = animOut ?: it.animOut) }

    /** A text's look, colour and animations onto new text (so the rider's last style carries on). */
    fun styled(t: TextItem, like: TextItem?): TextItem = like?.let { t.copy(look = it.look, color = it.color, size = it.size, align = it.align, animIn = it.animIn, animOut = it.animOut) } ?: t

    /**
     * How a text is drawn [localMs] after it starts (of [durMs]): (alpha 0..1, scale, dy as a
     * fraction of the frame, characters shown out of [length]).
     */
    fun animate(t: TextItem, localMs: Long, durMs: Long, length: Int): AnimFrame {
        val inT = (localMs / 300f).coerceIn(0f, 1f)
        val outT = ((durMs - localMs) / 300f).coerceIn(0f, 1f)
        var alpha = 1f
        var scale = 1f
        var dy = 0f
        var chars = length
        fun apply(a: TextAnim, q: Float, entering: Boolean) {
            when (a) {
                TextAnim.FADE -> alpha *= q
                TextAnim.POP -> { scale *= 0.6f + 0.4f * backOut(q); alpha *= minOf(1f, q * 2) }
                TextAnim.TYPE -> if (entering) chars = (length * (localMs / 600f).coerceIn(0f, 1f)).toInt().coerceAtLeast(if (localMs > 0) 1 else 0) else alpha *= q
                TextAnim.SLIDE -> { dy += (1 - q) * (if (entering) 0.04f else -0.04f); alpha *= q }
                TextAnim.BOUNCE -> { dy -= (if (q < 1f) kotlin.math.abs(kotlin.math.sin(q * Math.PI * 2)).toFloat() * 0.03f * (1 - q) else 0f); alpha *= minOf(1f, q * 3) }
                TextAnim.NONE -> {}
            }
        }
        apply(t.animIn, inT, true)
        apply(t.animOut, outT, false)
        return AnimFrame(alpha.coerceIn(0f, 1f), scale, dy, chars.coerceIn(0, length))
    }

    data class AnimFrame(val alpha: Float, val scale: Float, val dy: Float, val chars: Int)

    private fun backOut(q: Float): Float {
        val c = 1.70158f
        val x = q - 1
        return 1 + (c + 1) * x * x * x + c * x * x
    }

    // ---- captions ----------------------------------------------------------------------------

    fun captionLook(plan: StudioPlan, f: (CaptionLook) -> CaptionLook) = plan.copy(captionLook = f(plan.captionLook).let { it.copy(size = it.size.coerceIn(0.5f, 2f), y = it.y?.coerceIn(0.1f, 0.9f)) })

    /** Lines read again for a clip: the segments of that clip ([momentId]) get the new words for their part. */
    fun relines(plan: StudioPlan, momentId: String, lines: List<CaptionLine>): StudioPlan = plan.copy(
        segments = plan.segments.map { seg ->
            if (seg !is ClipSegment || seg.bit.momentId != momentId || seg.tail || seg.still != null) return@map seg
            val f = Speed.outPerSource(seg.speed, seg.ramp)
            val from = seg.inMs
            val to = seg.inMs + seg.sourceMs
            val mine = lines.filter { it.endMs > from && it.startMs < to }.map {
                it.copy(startMs = ((it.startMs - from).coerceAtLeast(0) * f).toLong(), endMs = ((minOf(it.endMs, to) - from) * f).toLong())
            }
            seg.copy(lines = mine, bit = seg.bit.copy(lines = lines))
        },
    )

    // ---- stickers ----------------------------------------------------------------------------

    fun addSticker(plan: StudioPlan, kind: StickerKind, atMs: Long, id: String, text: String = ""): StudioPlan {
        val start = atMs.coerceIn(0, (plan.totalMs - 500).coerceAtLeast(0))
        // Live numbers sit high and left by default; emoji and callouts in the middle.
        val (x, y) = when (kind) {
            StickerKind.SPEED -> 0.22f to 0.82f
            StickerKind.LEAN -> 0.78f to 0.82f
            else -> 0.5f to 0.45f
        }
        val len = if (kind == StickerKind.SPEED || kind == StickerKind.LEAN) plan.totalMs - start else 2_500
        return plan.copy(stickers = plan.stickers + StickerItem(id, kind, start, minOf(start + len, plan.totalMs), x, y, text = text))
    }

    private fun sticker(plan: StudioPlan, id: String, f: (StickerItem) -> StickerItem?) = plan.copy(stickers = plan.stickers.mapNotNull { if (it.id == id) f(it) else it })

    fun placeSticker(plan: StudioPlan, id: String, x: Float, y: Float) = sticker(plan, id) { it.copy(x = x.coerceIn(0.05f, 0.95f), y = y.coerceIn(0.05f, 0.95f)) }
    fun scaleSticker(plan: StudioPlan, id: String, factor: Float) = sticker(plan, id) { it.copy(size = (it.size * factor).coerceIn(0.3f, 4f)) }
    fun rotateSticker(plan: StudioPlan, id: String, degrees: Float) = sticker(plan, id) { it.copy(rotation = ((it.rotation + degrees + 180f) % 360f + 360f) % 360f - 180f) }
    fun deleteSticker(plan: StudioPlan, id: String) = sticker(plan, id) { null }

    fun shiftSticker(plan: StudioPlan, id: String, deltaMs: Long) = sticker(plan, id) { s ->
        val len = s.endMs - s.startMs
        val start = (s.startMs + deltaMs).coerceIn(0, (plan.totalMs - len).coerceAtLeast(0))
        s.copy(startMs = start, endMs = start + len)
    }

    fun resizeStickerTime(plan: StudioPlan, id: String, deltaMs: Long) = sticker(plan, id) { s -> s.copy(endMs = (s.endMs + deltaMs).coerceIn(s.startMs + 500, plan.totalMs)) }
}
