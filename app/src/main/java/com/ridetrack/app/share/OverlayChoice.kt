package com.ridetrack.app.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * One list of share graphics used by both ride and moment sharing: the eight ride layouts
 * (whole-ride numbers) and the three moment layouts (numbers at one instant, live on video).
 */
sealed interface OverlayChoice {
    val key: String
    val label: String
    /** One line under the preview: what it is and how much it covers. */
    val note: String
    /** Drawn on top of a photo (false = a full card of its own). */
    val transparent: Boolean

    data class Ride(val style: ShareStyle) : OverlayChoice {
        override val key get() = "ride-${style.name}"
        override val label get() = style.label
        override val note get() = if (style.transparent) "Whole ride · covers ${style.coverage.lowercase()}" else "Whole ride · a full story card"
        override val transparent get() = style.transparent
    }

    data class Moment(val layout: MomentLayout) : OverlayChoice {
        override val key get() = "moment-${layout.name}"
        override val label get() = layout.label
        override val note get() = "One instant · ${layout.hint.replaceFirstChar { it.lowercase() }}"
        override val transparent get() = true
    }

    companion object {
        val all: List<OverlayChoice> = ShareStyle.entries.map(::Ride) + MomentLayout.entries.map(::Moment)
    }
}

/** Draws a full-frame (1080×1920) graphic onto [c], scaled to [w] wide and centred vertically. */
fun drawFrameGraphic(c: Canvas, w: Int, h: Int, graphic: Bitmap) {
    val scale = w.toFloat() / graphic.width
    val m = Matrix().apply {
        setScale(scale, scale)
        postTranslate(0f, (h - graphic.height * scale) / 2f)
    }
    c.drawBitmap(graphic, m, Paint(Paint.FILTER_BITMAP_FLAG))
}

/** [photo] centre-cropped to [w]×[h]. */
fun cropPhoto(photo: Bitmap, w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val scale = maxOf(w.toFloat() / photo.width, h.toFloat() / photo.height)
    val m = Matrix().apply {
        setScale(scale, scale)
        postTranslate((w - photo.width * scale) / 2f, (h - photo.height * scale) / 2f)
    }
    Canvas(out).drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}
