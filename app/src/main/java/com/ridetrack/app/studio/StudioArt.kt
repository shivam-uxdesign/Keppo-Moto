package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.ridetrack.app.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What the title and stats show about the ride. Route points are -1..1 (north up), with speed 0..1. */
data class RideCard(
    val title: String,
    val subtitle: String,
    val route: List<Triple<Float, Float, Float>>,
    val stats: List<Pair<String, String>>,
)

/** Where a frame is: the segment, ms into it, and its neighbours (for the transition over each cut). */
data class FrameAt(
    val vibe: Vibe,
    val localMs: Long,
    val durMs: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    /** What the transition graphic says about the clip it lands on ("62 KM/H", "6:21 PM"). */
    val nextLabel: String,
    val nextTime: String,
    /** The transitions on the cut into this segment and out of it, and how long each takes. */
    val inKind: TransitionKind = TransitionKind.STYLE,
    val inMs: Long = vibe.transitionMs,
    val outKind: TransitionKind = TransitionKind.STYLE,
    val outMs: Long = vibe.transitionMs,
)

/** Which style's look a transition uses (its camera move and graphic); null for the plain ones (fade, flash, zoom, whip, glitch, cut). */
fun lookOf(kind: TransitionKind, vibe: Vibe): Vibe? = when (kind) {
    TransitionKind.STYLE -> vibe
    TransitionKind.SLASH -> Vibe.HYPE
    TransitionKind.SHUTTER -> Vibe.CINE
    TransitionKind.SUN -> Vibe.CHILL
    TransitionKind.CARD -> Vibe.VLOG
    else -> null
}

/** A camera move on the footage: zoom [k] about the centre, then a shift of [x], [y] px, a tilt of [deg]. */
data class Cam(val k: Float, val x: Float, val y: Float, val deg: Float)

/**
 * Keppo Studio's graphics, drawn per frame on a transparent 1080×1920 overlay: captions,
 * speed, the title and stats, and each vibe's transition over the cut. Nothing here mixes
 * two clips; each transition is a camera move plus graphics that cover the cut. Sizes are
 * written for a 540-wide frame and scaled.
 */
class StudioArt(context: Context, val w: Int = 1080, val h: Int = 1920) {
    private val s = w / 540f
    private val anton = font(context, R.font.anton)
    private val serif = font(context, R.font.instrument_serif_italic)
    private val marker = font(context, R.font.permanent_marker)
    private val geist = font(context, R.font.geist_semibold)
    private val geistMed = font(context, R.font.geist_medium)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val W = w.toFloat()
    private val H = h.toFloat()

    private fun font(c: Context, id: Int) = runCatching { ResourcesCompat.getFont(c, id) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    // ---- timing ------------------------------------------------------------------------------

    /** 0..1 across the cut (0.5 = the cut) when this frame is inside a transition, else null. */
    fun edge(f: FrameAt): Float? {
        val t = f.localMs.toFloat()
        if (f.hasPrev && f.inKind != TransitionKind.CUT) {
            val d = f.inMs / 2f
            if (t < d) return 0.5f + t / (2 * d)
        }
        if (f.hasNext && f.outKind != TransitionKind.CUT) {
            val d = f.outMs / 2f
            if (t > f.durMs - d) return (t - (f.durMs - d)) / (2 * d)
        }
        return null
    }

    /** The camera on a clip: a slow push, a kick on the beat (Hype), and each vibe's move into and out of the cut. */
    fun camera(f: FrameAt): Cam {
        val t = f.localMs.toFloat()
        var k = 1.03f + 0.05f * (t / f.durMs)
        var x = 0f
        var y = 0f
        var r = 0f
        if (f.vibe == Vibe.HYPE) {
            val b = (t % f.vibe.beatMs) / f.vibe.beatMs
            k += 0.035f * max(0f, 1 - b * 5)
        }
        val dOut = f.outMs / 2f
        val dIn = f.inMs / 2f
        val qo = if (f.hasNext && f.outKind != TransitionKind.CUT) cl((t - (f.durMs - dOut)) / dOut) else 0f
        val qi = if (f.hasPrev && f.inKind != TransitionKind.CUT) 1 - cl(t / dIn) else 0f
        // Out of this clip, then into it: each cut's own move.
        for ((kind, o, i) in listOf(Triple(f.outKind, qo, 0f), Triple(f.inKind, 0f, qi))) {
            if (o == 0f && i == 0f) continue
            when (lookOf(kind, f.vibe)) {
                Vibe.HYPE -> {
                    val eo = eIn(o)
                    val ei = 1 - eOut(1 - i) // remaining punch as it lands
                    k += 0.32f * eo + 0.28f * ei
                    x += -W * 0.22f * eo + W * 0.22f * ei
                    r += -2.9f * eo + 2.9f * ei
                }
                Vibe.CINE -> k += 0.05f * eIn(o) + 0.07f * eOut(i)
                Vibe.CHILL -> { k += 0.07f * eInOut(o) + 0.05f * i; y -= H * 0.02f * o; r += 0.86f * o }
                Vibe.VLOG -> { y -= H * 0.18f * eIn(o); y += H * 0.18f * eIn(i); k += 0.04f * i }
                null -> when (kind) {
                    TransitionKind.ZOOM -> k += 0.45f * eIn(o) + 0.45f * eOut(i)
                    TransitionKind.WHIP -> x += -W * 0.75f * eIn(o) + W * 0.75f * eIn(i)
                    TransitionKind.GLITCH -> x += W * 0.03f * (if (((t / 40).toInt() and 1) == 0) 1 else -1) * max(o, i)
                    else -> {}
                }
            }
        }
        return Cam(k, x, y, r)
    }

    /** The camera as Media3 wants it: a matrix in normalised device coordinates (-1..1, y up). */
    fun cameraMatrix(c: Cam): Matrix = Matrix().apply {
        // Never zoom less than it takes to keep the frame filled after the shift and tilt
        // (black edges showed during Hype's punch).
        val r = Math.toRadians(abs(c.deg).toDouble())
        val fill = (kotlin.math.cos(r) + (H / W) * kotlin.math.sin(r)).toFloat() + 2 * abs(c.x) / W + 2 * abs(c.y) / H
        val k = max(c.k, fill)
        // Rotate in pixel space so a tilt doesn't shear the 9:16 frame.
        setScale(W / 2, H / 2)
        postRotate(-c.deg)
        postScale(2 / W, 2 / H)
        postScale(k, k)
        postTranslate(2 * c.x / W, -2 * c.y / H)
    }

    // ---- a clip's overlay ---------------------------------------------------------------------

    /**
     * A clip's graphics. [lines] are the captions in ms from the segment start (the clip's words and
     * any voice-over); [opener] shows on the Reel's first clip: the title label and the hook line.
     */
    fun drawClip(c: Canvas, f: FrameAt, lines: List<CaptionLine>, kmh: Int, clock: String, captions: Boolean, opener: Opener? = null, look: CaptionLook = CaptionLook()) {
        vignette(c)
        if (f.vibe == Vibe.CINE) {
            p.reset(); p.color = Color.BLACK
            c.drawRect(0f, 0f, W, H * 0.09f, p); c.drawRect(0f, H * 0.91f, W, H, p)
        }
        speedBadge(c, f.vibe, kmh, clock)
        opener?.let { opening(c, f.vibe, it, f.localMs / 1000f) }
        if (captions) caption(c, f.vibe, lines, f.localMs / 1000f, look)
        edge(f)?.let { transition(c, f, it) }
    }

    /** What the first seconds say: a small title label and a hook line, readable with the sound off. */
    data class Opener(val label: String?, val hookLine: String?)

    /** The opening text: in over a quarter second, held, out by 2.8 s (before most first lines finish). */
    private fun opening(c: Canvas, vibe: Vibe, o: Opener, t: Float) {
        val a = cl(t / 0.25f) * cl((2.8f - t) / 0.35f)
        if (a <= 0f) return
        val rise = (1 - eOut(cl(t / 0.3f))) * 18 * s
        o.label?.takeIf { it.isNotBlank() }?.let { label ->
            text(geistMed, 15 * s, Color.WHITE, Paint.Align.CENTER, 0.25f)
            p.setShadowLayer(8 * s, 0f, 0f, Color.argb(160, 0, 0, 0)); p.alpha = (a * 230).toInt()
            c.drawText(label.uppercase(), W / 2, H * (if (vibe == Vibe.CINE) 0.13f else 0.165f) + rise, p)
            p.clearShadowLayer()
        }
        val line = o.hookLine?.takeIf { it.isNotBlank() } ?: return
        val y = H * (if (vibe == Vibe.CINE) 0.2f else 0.225f) + rise
        when (vibe) {
            Vibe.HYPE -> {
                val txt = line.uppercase()
                text(anton, fit(anton, txt, 58 * s, W * 0.86f), Color.WHITE, Paint.Align.CENTER)
                p.alpha = (a * 255).toInt(); p.color = INK; p.alpha = (a * 255).toInt(); c.drawText(txt, W / 2 + 4 * s, mid(y + 5 * s), p)
                p.color = Color.WHITE; p.alpha = (a * 255).toInt(); c.drawText(txt, W / 2, mid(y), p)
            }
            Vibe.CINE -> {
                text(serif, fit(serif, line, 50 * s, W * 0.84f), CREAM_TEXT, Paint.Align.CENTER)
                p.setShadowLayer(16 * s, 0f, 0f, Color.argb(150, 0, 0, 0)); p.alpha = (a * 255).toInt()
                c.drawText(line, W / 2, mid(y), p); p.clearShadowLayer()
            }
            Vibe.CHILL -> {
                val size = fit(marker, line, 40 * s, W * 0.78f)
                text(marker, size, INK, Paint.Align.CENTER)
                val bw = p.measureText(line) + 48 * s
                val bh = 78 * s
                c.save(); c.translate(W / 2, y); c.rotate(-2.3f)
                p.color = CREAM; p.alpha = (a * 255).toInt(); c.drawRoundRect(RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), 10 * s, 10 * s, p)
                text(marker, size, INK, Paint.Align.CENTER); p.alpha = (a * 255).toInt(); c.drawText(line, 0f, mid(0f), p)
                c.restore()
            }
            Vibe.VLOG -> {
                val size = fit(geist, line, 36 * s, W * 0.76f)
                text(geist, size, INK, Paint.Align.CENTER)
                val bw = p.measureText(line) + 52 * s
                val bh = 76 * s
                p.color = Color.WHITE; p.alpha = (a * 245).toInt(); c.drawRoundRect(RectF(W / 2 - bw / 2, y - bh / 2, W / 2 + bw / 2, y + bh / 2), 38 * s, 38 * s, p)
                text(geist, size, INK, Paint.Align.CENTER); p.alpha = (a * 255).toInt(); c.drawText(line, W / 2, mid(y), p)
            }
        }
    }

    private fun vignette(c: Canvas) {
        p.reset()
        p.shader = RadialGradient(W / 2, H / 2, H * 0.78f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(130, 0, 0, 0)), floatArrayOf(0f, 0.32f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
    }

    // ---- transitions -----------------------------------------------------------------------

    fun transition(c: Canvas, f: FrameAt, t: Float) {
        // t ≥ 0.5: landing in this clip (its cut in); below: leaving it (the next cut).
        val kind = if (t >= 0.5f) f.inKind else f.outKind
        when (lookOf(kind, f.vibe)) {
            Vibe.HYPE -> slash(c, t)
            Vibe.CINE -> shutter(c, t, f.nextTime)
            Vibe.CHILL -> sun(c, t)
            Vibe.VLOG -> card(c, t, f.nextLabel, f.nextTime)
            null -> when (kind) {
                TransitionKind.FADE -> veil(c, t, Color.BLACK)
                TransitionKind.FLASH -> veil(c, t, Color.WHITE, sharp = true)
                TransitionKind.WHIP -> streaks(c, t)
                TransitionKind.GLITCH -> glitch(c, t)
                else -> {}
            }
        }
    }

    /** Through [color]: full at the cut. [sharp] = a quick flash rather than a fade. */
    private fun veil(c: Canvas, t: Float, color: Int, sharp: Boolean = false) {
        val cov = 1 - abs(t - 0.5f) * 2
        val a = if (sharp) cov.pow(3f) else eInOut(cov)
        p.reset(); p.color = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        c.drawRect(0f, 0f, W, H, p)
    }

    /** Motion streaks across the frame as the camera whips. */
    private fun streaks(c: Canvas, t: Float) {
        val cov = 1 - abs(t - 0.5f) * 2
        if (cov <= 0f) return
        p.reset()
        val rnd = java.util.Random(7)
        repeat(18) {
            val y = rnd.nextFloat() * H
            val h = (6 + rnd.nextFloat() * 30) * s
            p.color = Color.argb((cov * (60 + rnd.nextInt(90))).toInt(), 255, 255, 255)
            c.drawRect(0f, y, W, y + h, p)
        }
        p.color = Color.argb((cov * 0.35f * 255).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, W, H, p)
    }

    /** Coloured bands that jump about for a moment across the cut. */
    private fun glitch(c: Canvas, t: Float) {
        val cov = 1 - abs(t - 0.5f) * 2
        if (cov <= 0.15f) return
        val rnd = java.util.Random((t * 40).toLong())
        p.reset()
        repeat(7) {
            val y = rnd.nextFloat() * H
            val h = (8 + rnd.nextFloat() * 70) * s
            val dx = (rnd.nextFloat() - 0.5f) * 80 * s
            p.color = if (it % 2 == 0) Color.argb((cov * 150).toInt(), 0, 255, 230) else Color.argb((cov * 150).toInt(), 255, 0, 120)
            c.drawRect(dx, y, W + dx, y + h, p)
        }
    }

    /** A black band with a yellow edge sweeps across on a diagonal; a short flash as the new clip lands. */
    private fun slash(c: Canvas, t: Float) {
        val d = hypot(W, H) * 1.15f
        val e = eInOut(t)
        val cov = 1 - abs(t - 0.5f) * 2
        val cx = (e - 0.5f) * 2.2f * d
        // A narrow band sweeping across, not a full black wipe (that read as the video dropping out).
        val bw = d * 0.16f * eOut(cov) + 18 * s
        c.save(); c.translate(W / 2, H / 2); c.rotate(-21.8f)
        p.reset(); p.color = YELLOW; c.drawRect(cx + bw / 2 - 4 * s, -d, cx + bw / 2 + 26 * s, d, p)
        p.color = Color.argb(215, Color.red(INK), Color.green(INK), Color.blue(INK)); c.drawRect(cx - bw / 2, -d, cx + bw / 2, d, p)
        c.restore()
        val fl = cl(1 - abs(t - 0.55f) / 0.04f)
        if (fl > 0) { p.reset(); p.color = Color.argb((0.5f * fl * 255).toInt(), 255, 255, 255); c.drawRect(0f, 0f, W, H, p) }
    }

    /** The letterbox closes to black on a thin light line (with the time), then opens on the next clip. */
    private fun shutter(c: Canvas, t: Float, time: String) {
        val cov = 1 - (abs(t - 0.5f) * 2).pow(0.7f)
        val bh = (H / 2 + 4) * cov
        p.reset(); p.color = Color.BLACK
        c.drawRect(0f, 0f, W, bh, p); c.drawRect(0f, H - bh, W, H, p)
        if (cov > 0.55f) {
            val a = cl((cov - 0.55f) / 0.45f)
            val y = H / 2
            p.shader = LinearGradient(0f, 0f, W, 0f, intArrayOf(Color.argb(0, 255, 190, 120), Color.argb((a * 255).toInt(), 255, 236, 210), Color.argb(0, 255, 190, 120)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, y - 1.5f * s, W, y + 1.5f * s, p)
            p.shader = RadialGradient(W / 2, y, W * 0.45f, Color.argb((0.4f * a * 255).toInt(), 255, 200, 140), Color.argb(0, 255, 200, 140), Shader.TileMode.CLAMP)
            c.drawRect(0f, y - 60 * s, W, y + 60 * s, p)
            p.shader = null
            if (time.isNotEmpty()) {
                text(geistMed, 15 * s, Color.argb((a * 230).toInt(), 255, 240, 225), Paint.Align.CENTER, 0.35f)
                c.drawText(time.uppercase(), W / 2, y - 22 * s, p)
            }
        }
    }

    /** A warm circle rises from one corner, fills the frame and sets into the other. */
    private fun sun(c: Canvas, t: Float) {
        val cov = eOut(1 - abs(t - 0.5f) * 2)
        val r = hypot(W, H) * 1.03f * cov
        if (r < 1) return
        val ox = if (t < 0.5f) W * 0.1f else W * 0.9f
        val oy = if (t < 0.5f) H * 0.95f else H * 0.08f
        p.reset()
        p.shader = RadialGradient(ox, oy, r, intArrayOf(CREAM, Color.rgb(255, 210, 161), ORANGE), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(ox, oy, r, p)
        p.shader = null
    }

    /** A white card slides up over the cut, showing how fast and when the next clip was. */
    private fun card(c: Canvas, t: Float, label: String, time: String) {
        val q = if (t < 0.5f) eOut(cl(t * 2)) else 1 + eInOut(cl((t - 0.5f) * 2))
        val y = H * (1 - q)
        if (y >= H || y <= -H) return
        p.reset(); p.color = Color.WHITE
        c.drawRoundRect(RectF(-10f, y, W + 10, y + H + 80 * s), 48 * s, 48 * s, p)
        if (label.isNotEmpty()) {
            text(geist, 64 * s, INK, Paint.Align.CENTER)
            c.drawText(label, W / 2, mid(y + H * 0.46f), p)
            text(geistMed, 24 * s, Color.argb(150, 11, 11, 13), Paint.Align.CENTER)
            c.drawText(time, W / 2, mid(y + H * 0.46f + 54 * s), p)
        }
    }

    // ---- captions ----------------------------------------------------------------------------

    private class Line(val words: List<String>, val times: List<Float>, val end: Float)

    /** The line being said at [t] (held a moment after it ends), with each word's start time. */
    private fun current(lines: List<CaptionLine>, t: Float): Line? {
        val i = lines.indices.firstOrNull { k ->
            val l = lines[k]
            val next = lines.getOrNull(k + 1)
            val until = if (next != null) min(next.startMs / 1000f - 0.05f, l.endMs / 1000f + 0.8f) else l.endMs / 1000f + 0.8f
            t >= l.startMs / 1000f - 0.05f && t < until
        } ?: return null
        val l = lines[i]
        val words = l.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val per = max(0.12f, (l.endMs - l.startMs) / 1000f / words.size)
        return Line(words, words.indices.map { l.startMs / 1000f + it * per }, l.endMs / 1000f)
    }

    fun caption(c: Canvas, vibe: Vibe, lines: List<CaptionLine>, t: Float, look: CaptionLook = CaptionLook()) {
        if (lines.isEmpty()) return
        val kind = when (look.kind) {
            CaptionKind.STYLE -> when (vibe) { Vibe.HYPE -> CaptionKind.PUNCH; Vibe.CINE -> CaptionKind.SERIF; Vibe.CHILL -> CaptionKind.LABEL; Vibe.VLOG -> CaptionKind.CHAT }
            else -> look.kind
        }
        // Each look's own height; the rider's size and height on top.
        val baseY = when (kind) { CaptionKind.PUNCH -> 0.64f; CaptionKind.SERIF -> 0.735f; CaptionKind.LABEL -> 0.68f; CaptionKind.CHAT -> 0.71f; else -> 0.72f }
        c.save()
        look.y?.let { c.translate(0f, (it - baseY) * H) }
        if (look.size != 1f) c.scale(look.size, look.size, W / 2, baseY * H)
        when (kind) {
            CaptionKind.PUNCH -> punch(c, lines, t)
            CaptionKind.SERIF -> serifRise(c, lines, t)
            CaptionKind.LABEL -> label(c, lines, t)
            CaptionKind.CHAT -> chat(c, lines, t)
            else -> plainCaption(c, lines, t, look.karaoke, baseY)
        }
        c.restore()
    }

    /** Plain captions: white words with a dark edge, two rows at most; the word being said lights up yellow. */
    private fun plainCaption(c: Canvas, lines: List<CaptionLine>, t: Float, karaoke: Boolean, y: Float) {
        val cur = current(lines, t) ?: return
        text(geist, 46 * s, Color.WHITE, Paint.Align.LEFT)
        val rows = wrap(cur.words, W * 0.82f).takeLast(2)
        val skipped = cur.words.size - rows.sumOf { it.size }
        val lh = 58 * s
        val sp = p.measureText(" ")
        var wi = skipped
        rows.forEachIndexed { ri, row ->
            val total = p.measureText(row.joinToString(" "))
            var x = W / 2 - total / 2
            val by = H * y + (ri - (rows.size - 1) / 2f) * lh
            row.forEach { w ->
                val said = cur.times[wi] <= t
                val active = karaoke && said && (wi == cur.words.lastIndex || t < cur.times[wi + 1])
                // A dark edge so it reads on any frame.
                p.style = Paint.Style.STROKE; p.strokeWidth = 7 * s; p.color = Color.argb(200, 0, 0, 0)
                c.drawText(w, x, mid(by), p)
                p.style = Paint.Style.FILL
                p.color = when { active -> YELLOW; karaoke && !said -> Color.argb(150, 255, 255, 255); else -> Color.WHITE }
                c.drawText(w, x, mid(by), p)
                x += p.measureText(w) + sp
                wi++
            }
        }
        p.style = Paint.Style.FILL
    }

    /** Up to 3 bold words at a time; the one being said sits on yellow; numbers and big words are yellow. */
    private fun punch(c: Canvas, lines: List<CaptionLine>, t: Float) {
        val cur = current(lines, t) ?: return
        val groups = chunks(cur.words, 3)
        var idx = 0
        var gi = 0
        groups.forEachIndexed { k, g -> if (cur.times[idx] <= t) gi = k; idx += g.size }
        val first = groups.take(gi).sumOf { it.size }
        val g = groups[gi]
        if (t < cur.times[first]) return
        val words = g.map { it.uppercase() }
        val size = fit(anton, words.joinToString(" "), 84 * s, W * 0.8f)
        text(anton, size, Color.WHITE, Paint.Align.CENTER)
        val space = p.measureText(" ")
        val widths = words.map { p.measureText(it) }
        var x = W / 2 - (widths.sum() + space * (words.size - 1)) / 2
        val y0 = H * 0.64f
        val hh = size * 1.08f
        words.forEachIndexed { k, w ->
            val wt = cur.times[first + k]
            val q = cl((t - wt) / 0.18f)
            if (q > 0) {
                val active = t >= wt && (k == words.size - 1 || t < cur.times[first + k + 1]) && t < cur.end + 0.1f
                val emph = EMPH.containsMatchIn(w)
                val scale = 1.7f - 0.7f * eBack(q)
                c.save(); c.translate(x + widths[k] / 2, y0); c.scale(scale, scale); if (active) c.rotate(-1.7f)
                text(anton, size, Color.WHITE, Paint.Align.CENTER)
                if (active) {
                    p.color = INK; c.drawRect(-widths[k] / 2 - 14 * s + 7 * s, -hh / 2 + 7 * s, widths[k] / 2 + 14 * s + 7 * s, hh / 2 + 7 * s, p)
                    p.color = YELLOW; c.drawRect(-widths[k] / 2 - 14 * s, -hh / 2, widths[k] / 2 + 14 * s, hh / 2, p)
                } else {
                    p.color = INK; c.drawText(w, 5 * s, mid(7 * s), p)
                }
                p.color = if (active) INK else if (emph) YELLOW else Color.WHITE
                c.drawText(w, 0f, mid(3 * s), p)
                c.restore()
            }
            x += widths[k] + space
        }
    }

    /** A lower-third serif line: words rise out of a mask under a hairline, then the line fades. */
    private fun serifRise(c: Canvas, lines: List<CaptionLine>, t: Float) {
        val cur = current(lines, t) ?: return
        text(serif, 48 * s, CREAM_TEXT, Paint.Align.LEFT)
        val rows = wrap(cur.words, W * 0.74f)
        val y0 = H * 0.735f - (rows.size - 1) * 30 * s
        val fade = cl((cur.end + 0.8f - t) / 0.3f)
        val lp = eInOut(cl((t - cur.times[0]) / 0.7f))
        p.reset(); p.isAntiAlias = true; p.strokeWidth = 1.5f * s; p.color = Color.argb((0.7f * fade * 255).toInt(), 255, 236, 210)
        c.drawLine(W / 2 - 70 * s * lp, y0 - 50 * s, W / 2 + 70 * s * lp, y0 - 50 * s, p)
        text(serif, 48 * s, CREAM_TEXT, Paint.Align.LEFT)
        p.setShadowLayer(16 * s, 0f, 0f, Color.argb(140, 0, 0, 0))
        var wi = 0
        val sp = p.measureText(" ")
        rows.forEachIndexed { ri, row ->
            val total = p.measureText(row.joinToString(" "))
            var x = W / 2 - total / 2
            val by = y0 + ri * 60 * s
            c.save(); c.clipRect(0f, by - 40 * s, W, by + 22 * s)
            row.forEach { w ->
                val q = eOut(cl((t - cur.times[wi++]) / 0.5f))
                if (q > 0) { p.alpha = (q * fade * 255).toInt(); c.drawText(w, x, by + (1 - q) * 46 * s, p) }
                x += p.measureText(w) + sp
            }
            c.restore()
        }
        p.clearShadowLayer()
    }

    /** Each phrase is a paper label that pops on, slightly tilted. */
    private fun label(c: Canvas, lines: List<CaptionLine>, t: Float) {
        val cur = current(lines, t) ?: return
        val groups = chunks(cur.words, 4)
        var idx = 0
        var gi = 0
        groups.forEachIndexed { k, g -> if (cur.times[idx] <= t) gi = k; idx += g.size }
        val first = groups.take(gi).sumOf { it.size }
        val g = groups[gi]
        val t0 = cur.times[first]
        if (t < t0) return
        val phrase = g.joinToString(" ")
        val size = fit(marker, phrase, 46 * s, W * 0.78f)
        text(marker, size, INK, Paint.Align.LEFT)
        val pop = eBack(cl((t - t0) / 0.32f))
        val bw = p.measureText(phrase) + 56 * s
        val bh = 92 * s
        c.save(); c.translate(W / 2, H * 0.68f); c.rotate((if (gi % 2 == 1) 1 else -1) * 2.9f * pop); c.scale(0.6f + 0.4f * pop, 0.6f + 0.4f * pop)
        p.color = Color.argb(90, 0, 0, 0); c.drawRoundRect(RectF(-bw / 2 + 8 * s, -bh / 2 + 10 * s, bw / 2 + 8 * s, bh / 2 + 10 * s), 10 * s, 10 * s, p)
        p.color = CREAM; c.drawRoundRect(RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), 10 * s, 10 * s, p)
        text(marker, size, INK, Paint.Align.LEFT)
        var x = -p.measureText(phrase) / 2
        val sp = p.measureText(" ")
        g.forEachIndexed { k, w ->
            val q = cl((t - cur.times[first + k]) / 0.12f)
            if (q > 0) { p.alpha = (q * 255).toInt(); c.drawText(w, x, mid(2 * s), p) }
            x += p.measureText(w) + sp
        }
        c.restore()
    }

    /** Each sentence is a chat bubble that types itself in; earlier ones float up and fade. */
    private fun chat(c: Canvas, lines: List<CaptionLine>, t: Float) {
        val shown = lines.filter { t >= it.startMs / 1000f - 0.45f && t < it.endMs / 1000f + 2.4f }.takeLast(3)
        if (shown.isEmpty()) return
        text(geist, 30 * s, INK, Paint.Align.LEFT)
        val lh = 40 * s
        var y = H * 0.71f
        for (k in shown.indices.reversed()) {
            val l = shown[k]
            val words = l.text.trim().split(Regex("\\s+"))
            val per = max(0.12f, (l.endMs - l.startMs) / 1000f / words.size)
            val n = ((t - l.startMs / 1000f) / per).toInt() + 1
            text(geist, 30 * s, INK, Paint.Align.LEFT)
            val rows = wrap(words, W * 0.62f)
            val bh = rows.size * lh + 32 * s
            val bw = min(W * 0.7f, rows.maxOf { p.measureText(it.joinToString(" ")) } + 44 * s)
            val age = shown.size - 1 - k
            val enter = eBack(cl((t - (l.startMs / 1000f - 0.45f)) / 0.3f))
            val alpha = if (age >= 2) 0.45f else 1f
            val x = 34 * s
            val by = y - bh
            c.save(); c.scale(0.8f + 0.2f * enter, 0.8f + 0.2f * enter, x, by + bh)
            p.color = Color.argb((alpha * 64).toInt(), 0, 0, 0); c.drawRoundRect(RectF(x + 4 * s, by + 6 * s, x + bw + 4 * s, by + bh + 6 * s), 26 * s, 26 * s, p)
            p.color = Color.WHITE; p.alpha = (alpha * 255).toInt(); c.drawRoundRect(RectF(x, by, x + bw, by + bh), 26 * s, 26 * s, p)
            c.drawPath(Path().apply { moveTo(x + 18 * s, by + bh - 6 * s); lineTo(x - 6 * s, by + bh + 12 * s); lineTo(x + 40 * s, by + bh - 2 * s); close() }, p)
            p.color = INK; p.alpha = (alpha * 255).toInt()
            if (t < l.startMs / 1000f) {
                for (dot in 0 until 3) {
                    p.alpha = (alpha * (0.35f + 0.65f * max(0f, sin(t * 12 - dot))) * 255).toInt()
                    c.drawCircle(x + 30 * s + dot * 18 * s, by + bh / 2, 6 * s, p)
                }
            } else {
                var wi = 0
                rows.forEachIndexed { ri, row ->
                    val take = (n - wi).coerceIn(0, row.size)
                    wi += row.size
                    if (take > 0) c.drawText(row.take(take).joinToString(" "), x + 22 * s, by + 16 * s + lh / 2 + ri * lh + mid(2 * s), p)
                }
            }
            c.restore()
            y = by - 14 * s
        }
    }

    // ---- cover ------------------------------------------------------------------------------

    /**
     * A Reel cover's text, still, in the vibe's caption style. Kept inside the middle 3:4 so
     * Instagram's profile grid doesn't crop it.
     */
    fun drawCoverText(c: Canvas, vibe: Vibe, line: String, cy: Float = H * 0.64f, shade: Boolean = true) {
        val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        // A soft shade under the text so it reads on any frame.
        if (shade) {
            p.reset(); p.shader = LinearGradient(0f, cy - H * 0.22f, 0f, cy + H * 0.18f, Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP)
            c.drawRect(0f, cy - H * 0.22f, W, cy + H * 0.18f, p)
            p.shader = null
        }
        when (vibe) {
            Vibe.HYPE -> {
                text(anton, 96 * s, Color.WHITE, Paint.Align.CENTER)
                val rows = wrap(words.map { it.uppercase() }, W * 0.8f).take(3)
                val lh = p.textSize * 1.05f
                rows.forEachIndexed { i, row ->
                    val y = cy + (i - (rows.size - 1) / 2f) * lh
                    val t = row.joinToString(" ")
                    val last = i == rows.lastIndex
                    if (last) {
                        val tw = p.measureText(t)
                        p.color = INK; c.drawRect(W / 2 - tw / 2 - 7 * s, y - lh / 2 + 7 * s, W / 2 + tw / 2 + 21 * s, y + lh / 2 + 7 * s, p)
                        p.color = YELLOW; c.drawRect(W / 2 - tw / 2 - 14 * s, y - lh / 2, W / 2 + tw / 2 + 14 * s, y + lh / 2, p)
                        p.color = INK; c.drawText(t, W / 2, mid(y + 3 * s), p)
                    } else {
                        p.color = INK; c.drawText(t, W / 2 + 5 * s, mid(y + 7 * s), p)
                        p.color = Color.WHITE; c.drawText(t, W / 2, mid(y + 3 * s), p)
                    }
                }
            }
            Vibe.CINE -> {
                text(serif, 58 * s, CREAM_TEXT, Paint.Align.CENTER)
                val rows = wrap(words, W * 0.74f).take(4)
                val lh = 70 * s
                val top = cy - (rows.size - 1) * lh / 2
                p.strokeWidth = 1.5f * s; p.color = Color.argb(180, 255, 236, 210)
                c.drawLine(W / 2 - 70 * s, top - 58 * s, W / 2 + 70 * s, top - 58 * s, p)
                text(serif, 58 * s, CREAM_TEXT, Paint.Align.CENTER)
                p.setShadowLayer(16 * s, 0f, 0f, Color.argb(140, 0, 0, 0))
                rows.forEachIndexed { i, row -> c.drawText(row.joinToString(" "), W / 2, top + i * lh + 18 * s, p) }
                p.clearShadowLayer()
            }
            Vibe.CHILL -> {
                text(marker, 54 * s, INK, Paint.Align.CENTER)
                val rows = wrap(words, W * 0.72f).take(3)
                val lh = 70 * s
                val bw = rows.maxOf { p.measureText(it.joinToString(" ")) } + 64 * s
                val bh = rows.size * lh + 36 * s
                c.save(); c.translate(W / 2, cy); c.rotate(-2.9f)
                p.color = Color.argb(90, 0, 0, 0); c.drawRoundRect(RectF(-bw / 2 + 8 * s, -bh / 2 + 10 * s, bw / 2 + 8 * s, bh / 2 + 10 * s), 10 * s, 10 * s, p)
                p.color = CREAM; c.drawRoundRect(RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), 10 * s, 10 * s, p)
                p.color = INK
                rows.forEachIndexed { i, row -> c.drawText(row.joinToString(" "), 0f, mid((i - (rows.size - 1) / 2f) * lh), p) }
                c.restore()
            }
            Vibe.VLOG -> {
                text(geist, 38 * s, INK, Paint.Align.LEFT)
                val rows = wrap(words, W * 0.66f).take(4)
                val lh = 50 * s
                val bw = rows.maxOf { p.measureText(it.joinToString(" ")) } + 52 * s
                val bh = rows.size * lh + 36 * s
                val x = (W - bw) / 2
                val by = cy - bh / 2
                p.color = Color.argb(64, 0, 0, 0); c.drawRoundRect(RectF(x + 4 * s, by + 6 * s, x + bw + 4 * s, by + bh + 6 * s), 30 * s, 30 * s, p)
                p.color = Color.WHITE; c.drawRoundRect(RectF(x, by, x + bw, by + bh), 30 * s, 30 * s, p)
                c.drawPath(Path().apply { moveTo(x + 22 * s, by + bh - 6 * s); lineTo(x - 6 * s, by + bh + 14 * s); lineTo(x + 46 * s, by + bh - 2 * s); close() }, p)
                p.color = INK
                rows.forEachIndexed { i, row -> c.drawText(row.joinToString(" "), x + 26 * s, by + 18 * s + lh / 2 + i * lh + mid(2 * s), p) }
            }
        }
    }

    /** A section's on-screen text, in the top third (clear of captions), for its first 2.6 s. */
    fun drawSectionText(c: Canvas, vibe: Vibe, text: String, localMs: Long) {
        val t = localMs / 1000f
        if (t > 2.6f) return
        val a = (cl(t / 0.25f) * cl((2.6f - t) / 0.3f) * 255).toInt()
        if (a <= 0) return
        c.saveLayerAlpha(0f, 0f, W, H, a)
        drawCoverText(c, vibe, text, cy = H * 0.3f, shade = false)
        c.restore()
    }

    /** Timeline text: in its look, place, size and angle, with its animations in and out. */
    fun drawTextItem(c: Canvas, vibe: Vibe, t: TextItem, localMs: Long, durMs: Long) {
        val f = TextTools.animate(t, localMs, durMs, t.text.length)
        val a = (f.alpha * 255).toInt()
        if (a <= 0 || f.chars == 0) return
        val shown = t.text.take(f.chars)
        val px = W * t.x
        val py = H * (t.y + f.dy)
        c.saveLayerAlpha(0f, 0f, W, H, a)
        c.rotate(t.rotation, px, py)
        val k = t.size * f.scale
        c.scale(k, k, px, py)
        when (t.look) {
            TextLook.STYLE -> { c.translate(px - W / 2, 0f); drawCoverText(c, vibe, shown, cy = py, shade = false) }
            TextLook.PLAIN -> styledText(c, shown, px, py, t.align, geist, 52 * s, t.color ?: Color.WHITE, shadow = true)
            TextLook.OUTLINE -> styledText(c, shown.uppercase(), px, py, t.align, anton, 72 * s, t.color ?: Color.WHITE, outline = true)
            TextLook.HAND -> styledText(c, shown, px, py, t.align, marker, 58 * s, t.color ?: Color.WHITE, shadow = true)
            TextLook.BOX -> {
                val bg = t.color ?: Color.WHITE
                val ink = if (Color.luminance(bg) > 0.5f) INK else Color.WHITE
                text(geist, 44 * s, ink, Paint.Align.CENTER)
                val rows = wrap(shown.split(Regex("\\s+")), W * 0.8f)
                val lh = 58 * s
                val bw = rows.maxOf { p.measureText(it.joinToString(" ")) } + 44 * s
                val bh = rows.size * lh + 28 * s
                val left = when (t.align) { TextAlignment.LEFT -> px; TextAlignment.CENTER -> px - bw / 2; TextAlignment.RIGHT -> px - bw }
                p.color = bg; c.drawRoundRect(RectF(left, py - bh / 2, left + bw, py + bh / 2), 22 * s, 22 * s, p)
                p.color = ink
                rows.forEachIndexed { i, row -> c.drawText(row.joinToString(" "), left + bw / 2, mid(py + (i - (rows.size - 1) / 2f) * lh), p) }
            }
        }
        c.restore()
    }

    /** Text in rows around ([x], [y]), aligned, with a shadow or a dark outline. */
    private fun styledText(c: Canvas, line: String, x: Float, y: Float, align: TextAlignment, face: Typeface, size: Float, color: Int, shadow: Boolean = false, outline: Boolean = false) {
        val pAlign = when (align) { TextAlignment.LEFT -> Paint.Align.LEFT; TextAlignment.CENTER -> Paint.Align.CENTER; TextAlignment.RIGHT -> Paint.Align.RIGHT }
        text(face, size, color, pAlign)
        val rows = wrap(line.split(Regex("\\s+")).filter { it.isNotEmpty() }, W * 0.84f)
        val lh = size * 1.12f
        rows.forEachIndexed { i, row ->
            val by = mid(y + (i - (rows.size - 1) / 2f) * lh)
            val txt = row.joinToString(" ")
            if (outline) {
                p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.12f; p.color = INK
                c.drawText(txt, x, by, p)
                p.style = Paint.Style.FILL; p.color = color
            }
            if (shadow) p.setShadowLayer(10 * s, 0f, 2 * s, Color.argb(170, 0, 0, 0))
            c.drawText(txt, x, by, p)
            p.clearShadowLayer()
        }
    }

    /** A sticker: live speed or lean, an emoji, an arrow or a circle; in and out over 0.2 s. */
    fun drawSticker(c: Canvas, st: StickerItem, localMs: Long, kmh: Int, lean: Int?) {
        val dur = st.endMs - st.startMs
        val a = cl(localMs / 200f) * cl((dur - localMs) / 200f)
        if (a <= 0f) return
        val px = W * st.x
        val py = H * st.y
        c.saveLayerAlpha(0f, 0f, W, H, (a * 255).toInt())
        c.rotate(st.rotation, px, py)
        c.scale(st.size, st.size, px, py)
        when (st.kind) {
            StickerKind.SPEED -> {
                val r = 92 * s
                p.reset(); p.isAntiAlias = true
                p.color = Color.argb(150, 0, 0, 0); c.drawCircle(px, py, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 12 * s; p.strokeCap = Paint.Cap.ROUND
                p.color = Color.argb(70, 255, 255, 255); c.drawArc(RectF(px - r + 14 * s, py - r + 14 * s, px + r - 14 * s, py + r - 14 * s), 135f, 270f, false, p)
                p.color = YELLOW
                val sweep = 270f * (kmh.coerceAtLeast(0) / 160f).coerceIn(0f, 1f)
                c.drawArc(RectF(px - r + 14 * s, py - r + 14 * s, px + r - 14 * s, py + r - 14 * s), 135f, sweep, false, p)
                p.style = Paint.Style.FILL
                text(anton, 64 * s, Color.WHITE, Paint.Align.CENTER)
                c.drawText(if (kmh >= 0) "$kmh" else "–", px, mid(py - 4 * s), p)
                text(geistMed, 16 * s, Color.WHITE, Paint.Align.CENTER, 0.2f)
                c.drawText("KM/H", px, py + 46 * s, p)
            }
            StickerKind.LEAN -> {
                val r = 92 * s
                p.reset(); p.isAntiAlias = true
                p.color = Color.argb(150, 0, 0, 0); c.drawCircle(px, py, r, p)
                val deg = (lean ?: 0).coerceIn(-60, 60)
                // A little bike leaning with the ride.
                c.save(); c.rotate(deg.toFloat(), px, py + 40 * s)
                p.color = Color.WHITE; p.strokeWidth = 10 * s; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(px, py + 40 * s, px, py - 46 * s, p)
                c.drawCircle(px, py - 54 * s, 10 * s, p)
                c.restore()
                text(anton, 34 * s, YELLOW, Paint.Align.CENTER)
                c.drawText(if (lean != null) "${kotlin.math.abs(deg)}°" else "–", px, py + 72 * s, p)
            }
            StickerKind.EMOJI -> {
                text(Typeface.DEFAULT, 150 * s, Color.WHITE, Paint.Align.CENTER)
                c.drawText(st.text.ifEmpty { "🔥" }, px, mid(py), p)
            }
            StickerKind.ARROW -> {
                p.reset(); p.isAntiAlias = true; p.color = Color.WHITE
                p.setShadowLayer(10 * s, 0f, 2 * s, Color.argb(150, 0, 0, 0))
                val path = Path().apply {
                    moveTo(px - 110 * s, py - 18 * s); lineTo(px + 30 * s, py - 18 * s); lineTo(px + 30 * s, py - 52 * s)
                    lineTo(px + 110 * s, py); lineTo(px + 30 * s, py + 52 * s); lineTo(px + 30 * s, py + 18 * s); lineTo(px - 110 * s, py + 18 * s); close()
                }
                c.drawPath(path, p)
                p.clearShadowLayer()
            }
            StickerKind.CIRCLE -> {
                // A ring that pulses, to point at something.
                val pulse = 1f + 0.06f * kotlin.math.sin(localMs / 160f)
                p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 10 * s; p.color = YELLOW
                c.drawCircle(px, py, 120 * s * pulse, p)
                p.style = Paint.Style.FILL
            }
        }
        c.restore()
    }

    // ---- speed badge -------------------------------------------------------------------------

    private fun speedBadge(c: Canvas, vibe: Vibe, kmh: Int, clock: String) {
        if (kmh < 0) return
        when (vibe) {
            Vibe.HYPE -> {
                text(anton, 64 * s, Color.WHITE, Paint.Align.LEFT)
                p.setShadowLayer(12 * s, 0f, 0f, Color.argb(128, 0, 0, 0))
                c.drawText("$kmh", 36 * s, 120 * s, p)
                val kw = p.measureText("$kmh")
                text(anton, 22 * s, YELLOW, Paint.Align.LEFT)
                p.setShadowLayer(12 * s, 0f, 0f, Color.argb(128, 0, 0, 0))
                c.drawText("KM/H", 46 * s + kw, 120 * s, p)
                p.clearShadowLayer()
            }
            Vibe.CINE -> {
                text(geistMed, 15 * s, Color.argb(220, 255, 240, 225), Paint.Align.CENTER, 0.35f)
                c.drawText("$clock  ·  $kmh KM/H".uppercase(), W / 2, H * 0.06f, p)
            }
            Vibe.CHILL -> {
                c.save(); c.translate(96 * s, 112 * s); c.rotate(-6.9f)
                p.reset(); p.isAntiAlias = true
                p.color = Color.argb(77, 0, 0, 0); c.drawCircle(4 * s, 6 * s, 58 * s, p)
                p.color = YELLOW; c.drawCircle(0f, 0f, 58 * s, p)
                text(marker, 40 * s, INK, Paint.Align.CENTER); c.drawText("$kmh", 0f, 10 * s, p)
                text(marker, 15 * s, INK, Paint.Align.CENTER); c.drawText("km/h", 0f, 34 * s, p)
                c.restore()
            }
            Vibe.VLOG -> {
                text(geist, 22 * s, INK, Paint.Align.LEFT)
                val label = "$kmh km/h"
                val bw = p.measureText(label) + 56 * s
                p.color = Color.argb(235, 255, 255, 255); c.drawRoundRect(RectF(30 * s, 62 * s, 30 * s + bw, 108 * s), 23 * s, 23 * s, p)
                p.color = PINK; c.drawCircle(54 * s, 85 * s, 7 * s, p)
                p.color = INK; c.drawText(label, 70 * s, 93 * s, p)
            }
        }
    }

    // ---- title and stats ---------------------------------------------------------------------

    /** The opening: the route draws itself under the title (or the title sits over the first clip). */
    fun drawTitle(c: Canvas, f: FrameAt, card: RideCard, map: Boolean) {
        val t = f.localMs / 1000f
        val dur = f.durMs / 1000f
        if (map) {
            mapBackground(c)
            val q = eInOut(min(1f, t / (dur * 0.85f)))
            val upTo = max(2, (card.route.size * q).toInt())
            c.save(); c.translate(0f, H * 0.1f)
            val pt = drawRoute(c, card.route, upTo, 1.05f + 0.1f * (1 - q), 8 * s, 0.12f)
            if (pt != null) {
                p.reset(); p.isAntiAlias = true
                p.shader = RadialGradient(pt.first, pt.second, 60 * s, Color.argb(230, 255, 214, 10), Color.argb(0, 255, 214, 10), Shader.TileMode.CLAMP)
                c.drawCircle(pt.first, pt.second, 60 * s, p); p.shader = null
                p.color = Color.WHITE; c.drawCircle(pt.first, pt.second, 9 * s, p)
            }
            c.restore()
            title(c, f.vibe, t, dur + 0.5f, H * 0.2f, card)
        } else {
            p.reset(); p.color = Color.argb(128, 0, 0, 0); c.drawRect(0f, 0f, W, H, p)
            title(c, f.vibe, t, dur, H * 0.46f, card)
        }
        edge(f)?.let { transition(c, f, it) }
    }

    private fun title(c: Canvas, vibe: Vibe, t: Float, dur: Float, cy: Float, card: RideCard) {
        val out = cl((dur - t) / 0.25f)
        val alpha = (out * 255).toInt()
        when (vibe) {
            Vibe.HYPE -> {
                val words = card.title.uppercase().split(" ")
                val top = words.firstOrNull().orEmpty()
                val rest = words.drop(1).joinToString(" ")
                val a = eOut(cl(t / 0.35f))
                val b = eOut(cl((t - 0.12f) / 0.35f))
                c.save(); c.clipRect(0f, cy - 130 * s, W, cy - 2 * s)
                text(anton, fit(anton, top, 130 * s, W * 0.8f), Color.WHITE, Paint.Align.CENTER); p.alpha = alpha
                c.drawText(top, W / 2, cy - 4 * s + (1 - a) * 130 * s, p); c.restore()
                if (rest.isNotEmpty()) {
                    c.save(); c.clipRect(0f, cy, W, cy + 120 * s)
                    text(anton, fit(anton, rest, 96 * s, W * 0.84f), YELLOW, Paint.Align.CENTER); p.alpha = alpha
                    c.drawText(rest, W / 2, cy + 100 * s - (1 - b) * 110 * s, p); c.restore()
                }
                text(geistMed, 22 * s, Color.WHITE, Paint.Align.CENTER); p.alpha = (alpha * 0.8f * cl((t - 0.45f) / 0.3f)).toInt()
                c.drawText(card.subtitle, W / 2, cy + 160 * s, p)
            }
            Vibe.CINE -> {
                val a = eInOut(cl(t / 1.1f))
                val spacing = 0.2f * (1 - a)
                text(serif, fit(serif, card.title, 76 * s, W * 0.8f - 20 * s * (1 - a)), CREAM_TEXT, Paint.Align.CENTER, spacing)
                p.alpha = (alpha * a).toInt(); c.drawText(card.title, W / 2, cy + 20 * s, p)
                p.reset(); p.isAntiAlias = true; p.strokeWidth = 1.5f * s; p.color = Color.argb((alpha * a * 0.7f).toInt(), 255, 236, 210)
                c.drawLine(W / 2 - 150 * s * a, cy + 52 * s, W / 2 + 150 * s * a, cy + 52 * s, p)
                text(geistMed, 15 * s, Color.argb(200, 255, 240, 225), Paint.Align.CENTER, 0.35f); p.alpha = (alpha * a * 0.8f).toInt()
                c.drawText(card.subtitle.uppercase(), W / 2, cy + 86 * s, p)
            }
            Vibe.CHILL -> {
                val pop = eBack(cl(t / 0.45f))
                val size = fit(marker, card.title, 62 * s, W * 0.74f)
                text(marker, size, INK, Paint.Align.CENTER)
                val bw = p.measureText(card.title) + 70 * s
                c.save(); c.translate(W / 2, cy); c.rotate(-3.4f * pop); c.scale(0.5f + 0.5f * pop, 0.5f + 0.5f * pop)
                p.color = Color.argb((alpha * 0.35f).toInt(), 0, 0, 0); c.drawRoundRect(RectF(-bw / 2 + 10 * s, -64 * s, bw / 2 + 10 * s, 56 * s), 12 * s, 12 * s, p)
                p.color = CREAM; p.alpha = alpha; c.drawRoundRect(RectF(-bw / 2, -74 * s, bw / 2, 46 * s), 12 * s, 12 * s, p)
                text(marker, size, INK, Paint.Align.CENTER); p.alpha = alpha; c.drawText(card.title, 0f, 6 * s, p)
                text(geistMed, 22 * s, Color.WHITE, Paint.Align.CENTER); p.alpha = (alpha * 0.85f).toInt(); c.drawText(card.subtitle, 0f, 104 * s, p)
                c.restore()
            }
            Vibe.VLOG -> {
                val words = card.title.split(" ")
                val n = ceil(cl((t - 0.35f) / 0.9f) * words.size).toInt()
                text(geist, 40 * s, INK, Paint.Align.LEFT)
                val bw = min(W * 0.9f, p.measureText(card.title) + 64 * s)
                val bh = 92 * s
                val x = W / 2 - bw / 2
                val y = cy - bh / 2
                val enter = eBack(cl(t / 0.3f))
                c.save(); c.scale(0.8f + 0.2f * enter, 0.8f + 0.2f * enter, W / 2, cy)
                p.color = Color.WHITE; p.alpha = alpha; c.drawRoundRect(RectF(x, y, x + bw, y + bh), 30 * s, 30 * s, p)
                p.color = INK
                if (n <= 0) {
                    for (dot in 0 until 3) { p.alpha = (alpha * (0.35f + 0.65f * max(0f, sin(t * 12 - dot)))).toInt(); c.drawCircle(x + 34 * s + dot * 20 * s, cy, 7 * s, p) }
                } else {
                    p.alpha = alpha; c.drawText(words.take(n).joinToString(" "), x + 30 * s, cy + 14 * s, p)
                }
                c.restore()
                text(geistMed, 22 * s, Color.WHITE, Paint.Align.CENTER); p.alpha = (alpha * 0.85f).toInt()
                c.drawText(card.subtitle, W / 2, y + bh + 44 * s, p)
            }
        }
    }

    /** The ending: the ride's numbers counting up, the route faint behind them, the optional mark. */
    fun drawStats(c: Canvas, f: FrameAt, card: RideCard, map: Boolean, watermark: Boolean) {
        val t = f.localMs / 1000f
        p.reset()
        p.color = when (f.vibe) { Vibe.CHILL -> Color.rgb(29, 23, 20); Vibe.VLOG -> Color.rgb(15, 20, 22); else -> Color.rgb(11, 11, 13) }
        c.drawRect(0f, 0f, W, H, p)
        if (map) {
            c.saveLayerAlpha(0f, 0f, W, H, 72)
            c.translate(0f, H * 0.04f)
            drawRoute(c, card.route, card.route.size, 1f, 5 * s, 0.12f)
            c.restore()
        }
        card.stats.take(4).forEachIndexed { i, (value, unit) ->
            val q = cl((t - 0.15f - i * 0.12f) / 0.45f)
            val e = if (f.vibe == Vibe.HYPE) eBack(q) else eOut(q)
            val shown = countUp(value, cl((t - 0.15f - i * 0.12f) / 0.9f))
            val cx = W * (if (i % 2 == 1) 0.73f else 0.27f)
            val cy = H * (if (i < 2) 0.37f else 0.56f)
            val a = (q * 255).toInt()
            c.save(); c.translate(cx, cy + (1 - e) * 60 * s)
            when (f.vibe) {
                Vibe.HYPE -> {
                    text(anton, 92 * s, Color.WHITE, Paint.Align.CENTER); p.alpha = a; c.drawText(shown, 0f, 8 * s, p)
                    text(anton, 24 * s, YELLOW, Paint.Align.CENTER); p.alpha = a; c.drawText(unit.uppercase(), 0f, 46 * s, p)
                }
                Vibe.CINE -> {
                    text(serif, 92 * s, CREAM_TEXT, Paint.Align.CENTER); p.alpha = a; c.drawText(shown, 0f, 0f, p)
                    text(geistMed, 14 * s, Color.argb(180, 255, 240, 225), Paint.Align.CENTER, 0.35f); p.alpha = (a * 0.7f).toInt(); c.drawText(unit.uppercase(), 0f, 40 * s, p)
                }
                Vibe.CHILL -> {
                    p.reset(); p.isAntiAlias = true; p.color = CREAM; p.alpha = a
                    c.drawRoundRect(RectF(-140 * s, -100 * s, 140 * s, 70 * s), 14 * s, 14 * s, p)
                    text(marker, 72 * s, INK, Paint.Align.CENTER); p.alpha = a; c.drawText(shown, 0f, 4 * s, p)
                    text(marker, 24 * s, INK, Paint.Align.CENTER); p.alpha = a; c.drawText(unit, 0f, 46 * s, p)
                }
                Vibe.VLOG -> {
                    p.reset(); p.isAntiAlias = true; p.color = Color.WHITE; p.alpha = a
                    c.drawRoundRect(RectF(-150 * s, -100 * s, 150 * s, 70 * s), 28 * s, 28 * s, p)
                    text(geist, 76 * s, INK, Paint.Align.CENTER); p.alpha = a; c.drawText(shown, 0f, 4 * s, p)
                    text(geistMed, 22 * s, Color.argb(150, 11, 11, 13), Paint.Align.CENTER); p.alpha = (a * 0.6f).toInt(); c.drawText(unit, 0f, 44 * s, p)
                }
            }
            c.restore()
        }
        val ta = (cl(t / 0.4f) * 255).toInt()
        val (face, size) = when (f.vibe) { Vibe.HYPE -> anton to 54f; Vibe.CINE -> serif to 60f; Vibe.CHILL -> marker to 48f; Vibe.VLOG -> geist to 44f }
        val name = if (f.vibe == Vibe.HYPE) card.title.uppercase() else card.title
        text(face, fit(face, name, size * s, W * 0.88f), Color.WHITE, Paint.Align.CENTER); p.alpha = ta
        c.drawText(name, W / 2, H * 0.19f, p)
        if (watermark) {
            text(geistMed, 18 * s, Color.WHITE, Paint.Align.CENTER); p.alpha = (cl((t - 0.8f) / 0.4f) * 180).toInt()
            c.drawText("made with Keppo Moto", W / 2, H * 0.79f, p)
        }
        edge(f)?.let { transition(c, f, it) }
    }

    private fun mapBackground(c: Canvas) {
        p.reset(); p.color = Color.rgb(13, 15, 19); c.drawRect(0f, 0f, W, H, p)
        p.color = Color.argb(13, 255, 255, 255); p.strokeWidth = 1f * s
        for (i in 0 until 14) {
            c.drawLine(i / 13f * W, 0f, i / 13f * W + W * 0.15f, H, p)
            c.drawLine(0f, i / 13f * H, W, i / 13f * H - H * 0.08f, p)
        }
    }

    /** The route in speed colours; returns where its last drawn point is. */
    private fun drawRoute(c: Canvas, route: List<Triple<Float, Float, Float>>, upTo: Int, zoom: Float, width: Float, pad: Float): Pair<Float, Float>? {
        if (route.size < 2) return null
        val sc = min(W, H) * (0.5f - pad) * zoom
        fun px(i: Int) = W / 2 + route[i].first * sc
        fun py(i: Int) = H / 2 + route[i].second * sc
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND
        val n = min(upTo, route.size)
        for (i in 1 until n) {
            p.color = heat(route[i].third)
            c.drawLine(px(i - 1), py(i - 1), px(i), py(i), p)
        }
        p.style = Paint.Style.FILL
        return px(n - 1) to py(n - 1)
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun text(face: Typeface, size: Float, color: Int, align: Paint.Align, spacing: Float = 0f) {
        p.reset(); p.isAntiAlias = true; p.typeface = face; p.textSize = size; p.color = color; p.textAlign = align; p.letterSpacing = spacing
    }

    /** The baseline that centres the current font on [y]. */
    private fun mid(y: Float) = y - (p.ascent() + p.descent()) / 2

    private fun fit(face: Typeface, text: String, size: Float, maxW: Float): Float {
        val m = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
        var px = size
        while (px > 12 * s) { m.textSize = px; if (m.measureText(text) <= maxW) break; px -= 2 * s }
        return px
    }

    private fun wrap(words: List<String>, maxW: Float): List<List<String>> {
        val rows = ArrayList<MutableList<String>>()
        for (w in words) {
            val row = rows.lastOrNull()
            if (row != null && p.measureText((row + w).joinToString(" ")) <= maxW) row += w else rows += mutableListOf(w)
        }
        return rows
    }

    private fun chunks(words: List<String>, n: Int): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (w in words) {
            cur += w
            if (cur.size == n || w.last() in ",.!?") { out += cur; cur = ArrayList() }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** "3.6" counting up from 0 with the same decimals. */
    private fun countUp(value: String, q: Float): String {
        val v = value.toDoubleOrNull() ?: return value
        val dp = value.substringAfter('.', "").length.takeIf { '.' in value } ?: 0
        return String.format(java.util.Locale.US, "%.${dp}f", v * q)
    }

    companion object {
        val INK = Color.rgb(11, 11, 13)
        val YELLOW = Color.rgb(255, 214, 10)
        val CREAM = Color.rgb(255, 243, 223)
        val CREAM_TEXT = Color.rgb(255, 246, 234)
        val ORANGE = Color.rgb(255, 138, 61)
        val PINK = Color.rgb(255, 93, 143)
        private val EMPH = Regex("(\\d|!|SPEED|SIXTY|SEVENTY|BREAK|FAST|MAST|FULL)", RegexOption.IGNORE_CASE)

        fun cl(x: Float) = x.coerceIn(0f, 1f)
        fun eIn(t: Float) = t * t * t
        fun eOut(t: Float) = 1 - (1 - t).pow(3)
        fun eInOut(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
        fun eBack(t: Float): Float { val c = 1.7f; return 1 + (c + 1) * (t - 1).pow(3) + c * (t - 1).pow(2) }

        /** Teal when slow, through amber to red at the ride's top speed. */
        fun heat(f: Float): Int {
            val t = f.coerceIn(0f, 1f)
            fun lerp(a: Int, b: Int, k: Float) = Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
            )
            return if (t < 0.5f) lerp(Color.rgb(105, 200, 203), Color.rgb(251, 191, 36), t * 2) else lerp(Color.rgb(251, 191, 36), Color.rgb(244, 63, 94), (t - 0.5f) * 2)
        }

    }
}
