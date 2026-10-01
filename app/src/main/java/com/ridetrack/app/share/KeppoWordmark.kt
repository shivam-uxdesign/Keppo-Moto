package com.ridetrack.app.share

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The drawn "keppo moto" wordmark from the Keppo brand: monoline strokes with round caps, the
 * k's leg replaced by a dot (the moment kept). "moto" is the same construction, lighter and
 * smaller, on the same baseline. Never set in a font. Geometry is in the brand's units: the
 * k is 160 tall (10..170, baseline 170).
 */
object KeppoWordmark {
    private const val K_TOP = 10f
    private const val BASELINE = 170f
    private const val K_HEIGHT = BASELINE - K_TOP
    /** keppo, a gap, then moto at [MOTO_SCALE]. */
    private const val UNITS_WIDE = 885f
    private const val MOTO_X = 612f
    private const val MOTO_SCALE = 0.714f
    private const val KEPPO_STROKE = 24f
    private const val MOTO_STROKE = 14f

    private val keppo = Path().apply {
        moveTo(12f, 10f); lineTo(12f, 170f) // k stem
        moveTo(34f, 126f); lineTo(90f, 70f) // k arm
        moveTo(124f, 120f); lineTo(200f, 120f) // e bar
        arcTo(RectF(124f, 82f, 200f, 158f), 0f, -315f, false) // e bowl
        moveTo(244f, 82f); lineTo(244f, 232f); addCircle(282f, 120f, 38f, Path.Direction.CW) // p
        moveTo(352f, 82f); lineTo(352f, 232f); addCircle(390f, 120f, 38f, Path.Direction.CW) // p
        addCircle(510f, 120f, 38f, Path.Direction.CW) // o
    }

    private val moto = Path().apply {
        moveTo(6f, 170f); lineTo(6f, 100f); arcTo(RectF(6f, 72f, 62f, 128f), 180f, 180f, false); lineTo(62f, 170f) // m
        moveTo(62f, 100f); arcTo(RectF(62f, 72f, 118f, 128f), 180f, 180f, false); lineTo(118f, 170f)
        addCircle(178f, 120f, 44f, Path.Direction.CW) // o
        moveTo(250f, 44f); lineTo(250f, 146f); arcTo(RectF(250f, 122f, 298f, 170f), 180f, -90f, false); lineTo(282f, 170f) // t
        moveTo(232f, 82f); lineTo(280f, 82f)
        addCircle(340f, 120f, 44f, Path.Direction.CW) // o
    }

    /** Width of the wordmark when the k is [height] px tall. */
    fun width(height: Float): Float = UNITS_WIDE * height / K_HEIGHT

    /**
     * Draws the wordmark with its baseline at [baseline] and the k [height] px tall; returns
     * its width. [shadow] adds the same soft drop shadow the card text uses.
     */
    fun draw(c: Canvas, x: Float, baseline: Float, height: Float, keppoColor: Int, motoColor: Int, shadow: Int? = null): Float {
        val s = height / K_HEIGHT
        fun stroke(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            if (shadow != null) setShadowLayer(6f / s, 0f, 2f / s, shadow)
        }
        c.save()
        c.translate(x, baseline - BASELINE * s)
        c.scale(s, s)
        c.drawPath(keppo, stroke(KEPPO_STROKE, keppoColor))
        c.drawCircle(62f, 154f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = keppoColor
            if (shadow != null) setShadowLayer(6f / s, 0f, 2f / s, shadow)
        })
        c.translate(MOTO_X, BASELINE * (1 - MOTO_SCALE))
        c.scale(MOTO_SCALE, MOTO_SCALE)
        c.drawPath(moto, stroke(MOTO_STROKE, motoColor))
        c.restore()
        return width(height)
    }
}
