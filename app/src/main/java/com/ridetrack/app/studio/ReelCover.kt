package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max

/**
 * A Reel's cover: a sharp frame of the footage (not the Reel itself, so no captions are baked
 * in), with the hook line in the vibe's style. 1080×1920; the text stays in the middle 3:4.
 */
object ReelCover {
    const val W = 1080
    const val H = 1920

    /** Where the Reel at [atMs] comes from: a clip segment and ms into its source video. */
    data class Spot(val segment: ClipSegment, val sourceMs: Long)

    /** The clip on screen at [atMs] (the nearest clip when it's the title or stats). Pure. */
    fun spotAt(plan: StudioPlan, atMs: Long): Spot? {
        var start = 0L
        var best: Pair<ClipSegment, Long>? = null
        var bestGap = Long.MAX_VALUE
        plan.segments.forEach { seg ->
            if (seg is ClipSegment && !seg.tail) {
                val local = (atMs - start).coerceIn(0, seg.durMs - 1)
                val gap = if (atMs in start until start + seg.durMs) 0 else minOf(abs(atMs - start), abs(atMs - (start + seg.durMs)))
                if (gap < bestGap) { bestGap = gap; best = seg to local }
            }
            start += seg.durMs
        }
        // Sped up or slowed: the clip's own time runs at its speed; a freeze holds its frame.
        return best?.let { (seg, local) -> Spot(seg, seg.inMs + if (seg.still != null) 0 else (local / Speed.outPerSource(seg.speed, seg.ramp)).toLong()) }
    }

    /** Reel times worth trying for the cover: spread over the hook clip (or the first clip). Pure. */
    fun candidates(plan: StudioPlan, n: Int = 6): List<Long> {
        var start = 0L
        var first: Pair<Long, ClipSegment>? = null
        plan.segments.forEach { seg ->
            if (seg is ClipSegment && !seg.tail && !seg.teaser) {
                if (seg.hook) return spread(start, seg.durMs, n)
                if (first == null) first = start to seg
            }
            start += seg.durMs
        }
        return first?.let { (s, seg) -> spread(s, seg.durMs, n) }.orEmpty()
    }

    private fun spread(start: Long, dur: Long, n: Int) = (1..n).map { start + dur * it / (n + 1) }

    /** The upright frame of a clip (a moment's file or a gallery video) at [ms], or null. */
    fun frame(context: Context, uri: Uri, ms: Long): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            r.release()
        }
    }.getOrNull()

    /** How good a frame is as a cover: sharp, not too dark, a face counts for a lot. */
    fun score(b: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(b, 90, 160, true)
        val px = IntArray(90 * 160).also { small.getPixels(it, 0, 90, 0, 0, 90, 160) }
        fun lum(i: Int) = (Color.red(px[i]) * 3 + Color.green(px[i]) * 6 + Color.blue(px[i])) / 10f
        var lap = 0.0
        var sum = 0.0
        for (y in 1 until 159) for (x in 1 until 89) {
            val i = y * 90 + x
            val l = lum(i)
            sum += l
            val d = 4 * l - lum(i - 1) - lum(i + 1) - lum(i - 90) - lum(i + 90)
            lap += d * d
        }
        val count = 88 * 158
        val sharp = (lap / count).toFloat()
        val bright = (sum / count).toFloat()
        val face = runCatching {
            val f = small.copy(Bitmap.Config.RGB_565, false)
            FaceDetector(f.width, f.height, 1).findFaces(f, arrayOfNulls(1))
        }.getOrDefault(0)
        if (small != b) small.recycle()
        val light = if (bright < 40) 0.4f else if (bright > 225) 0.6f else 1f
        return (sharp.coerceAtMost(2_000f) / 2_000f + if (face > 0) 0.6f else 0f) * light
    }

    /**
     * Draws the cover: [frame] centre-cropped to 9:16 (or [route] on dark when the rider chose the
     * route card), then [line] in the [vibe]'s style.
     */
    fun render(context: Context, frame: Bitmap?, route: Bitmap?, vibe: Vibe, line: String?): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(10, 10, 11))
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when {
            route != null -> {
                // The square route picture in the top part of the safe 3:4.
                val side = W.toFloat()
                c.drawBitmap(route, null, RectF(0f, H * 0.14f, side, H * 0.14f + side), p)
            }
            frame != null -> c.drawBitmap(frame, crop(frame.width, frame.height), Rect(0, 0, W, H), p)
        }
        if (!line.isNullOrBlank()) StudioArt(context, W, H).drawCoverText(c, vibe, line)
        return out
    }

    /** The middle 9:16 of a [w]×[h] frame. */
    fun crop(w: Int, h: Int): Rect {
        val target = 9f / 16f
        return if (w.toFloat() / h > target) {
            val cw = (h * target).toInt()
            Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
        } else {
            val ch = max(1, (w / target).toInt())
            Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
        }
    }
}
