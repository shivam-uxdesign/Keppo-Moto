package com.ridetrack.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.ridetrack.app.R
import com.ridetrack.app.ui.format.Format
import com.ridetrack.telemetry.model.RideEventType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Draws the moment overlay (event, time, map, speed, lean, G) on top of a photo or video
 * frame of any size. Everything is laid out on a 1080-wide grid and scaled to the frame,
 * so photos (1080×1920) and clips (e.g. 720×1280) look the same.
 */
class MomentShareRenderer(context: Context) {
    private val light = font(context, R.font.geist_light)
    private val regular = font(context, R.font.geist_regular)
    private val medium = font(context, R.font.geist_medium)
    private val semibold = font(context, R.font.geist_semibold)

    private fun font(context: Context, id: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT

    /** Draws onto [c] covering [w]×[h] pixels. Only switched-on rows with real data are drawn. */
    fun draw(c: Canvas, w: Int, h: Int, o: MomentOverlay, on: Set<MomentField>, layout: MomentLayout) {
        val u = w / 1080f
        c.save()
        c.scale(u, u)
        val hl = h / u
        when (layout) {
            MomentLayout.MINIMAL -> minimal(c, hl, o, on)
            MomentLayout.BAR -> bar(c, hl, o, on)
            MomentLayout.HUD -> hud(c, hl, o, on)
        }
        c.restore()
    }

    /** Photo (center-cropped to [w]×[h]) with the overlay on top. */
    fun composePhoto(photo: Bitmap, w: Int, h: Int, o: MomentOverlay, on: Set<MomentField>, layout: MomentLayout): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = maxOf(w.toFloat() / photo.width, h.toFloat() / photo.height)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - photo.width * scale) / 2f, (h - photo.height * scale) / 2f)
        }
        c.drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))
        draw(c, w, h, o, on, layout)
        return out
    }

    fun overlayOnly(w: Int, h: Int, o: MomentOverlay, on: Set<MomentField>, layout: MomentLayout): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), w, h, o, on, layout) }

    // ---- Minimal: a corner cluster ------------------------------------------------------

    private fun minimal(c: Canvas, hl: Float, o: MomentOverlay, on: Set<MomentField>) {
        val x = 64f
        var y = hl - 70f
        val mapShown = o.shows(MomentField.MAP, on)
        if (mapShown) miniMap(c, W - 64f - 120f, hl - 64f - 120f, 120f, o, circle = true)
        if (o.shows(MomentField.BRAND, on)) {
            brand(c, o, x, y, 22f, 210)
            y -= 46f
        }
        if (o.shows(MomentField.TIME, on)) {
            text(c, "${o.timeText}  ·  ${o.dateText}", x, y, regular, 30f, WHITE)
            y -= 70f
        }
        val values = valueChips(o, on)
        if (values.isNotEmpty()) {
            var cx = x
            values.forEach { (value, unit, color) ->
                val vw = text(c, value, cx, y, medium, 72f, color, tracking = -0.02f)
                val uw = if (unit.isNotEmpty()) text(c, unit, cx + vw + 8f, y, regular, 30f, WHITE, alpha = 220) + 8f else 0f
                cx += vw + uw + 44f
            }
            y -= 96f
        }
        if (o.shows(MomentField.EVENT, on)) eventChip(c, x, y, o)
    }

    // ---- Bar: a band along the bottom ---------------------------------------------------

    private fun bar(c: Canvas, hl: Float, o: MomentOverlay, on: Set<MomentField>) {
        val top = hl - 390f
        c.drawRect(0f, top - 140f, W, hl, Paint().apply {
            shader = LinearGradient(0f, top - 140f, 0f, hl, intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, 150), withAlpha(Color.BLACK, 210)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        })
        val mapShown = o.shows(MomentField.MAP, on)
        val right = if (mapShown) W - 64f - 260f - 40f else W - 64f
        if (mapShown) miniMap(c, W - 64f - 130f, top + 170f, 130f, o, circle = false)
        // Time rides along the event line, so the number columns never get squeezed.
        val chipEnd = if (o.shows(MomentField.EVENT, on)) eventChip(c, 64f, top + 60f, o) + 24f else 64f
        if (o.shows(MomentField.TIME, on)) {
            // Stop short of the map; drop the date if the full line wouldn't fit.
            val room = (if (mapShown) W - 64f - 260f - 24f else W - 64f) - chipEnd
            val full = "${o.timeText}  ·  ${o.dateText}"
            val line = if (measure(full, regular, 30f) <= room) full else o.timeText
            text(c, line, chipEnd, top + 57f, regular, 30f, WHITE, alpha = 230, maxWidth = room)
        }
        val cols = buildList {
            if (o.shows(MomentField.SPEED, on)) add(Triple("SPEED", Format.speedKmh(o.speedMps), "km/h"))
            if (o.shows(MomentField.LEAN, on)) add(Triple("LEAN", leanText(o.lean), ""))
            if (o.shows(MomentField.G_FORCE, on)) add(Triple(if ((o.gForce ?: 0.0) < 0) "BRAKING" else "ACCEL", gText(o.gForce), "G"))
        }
        if (cols.isNotEmpty()) {
            val colW = (right - 64f) / cols.size
            cols.forEachIndexed { i, (label, value, unit) ->
                val x = 64f + i * colW
                text(c, label, x, top + 190f, semibold, 22f, WHITE, tracking = 0.2f, alpha = 190)
                val vw = text(c, value, x, top + 262f, medium, 58f, colorFor(label, o), maxWidth = colW - 16f)
                if (unit.isNotEmpty()) text(c, unit, x + vw + 6f, top + 262f, regular, 24f, WHITE, alpha = 200)
            }
        }
        if (o.shows(MomentField.BRAND, on)) brand(c, o, 64f, hl - 56f, 20f, 170)
    }

    // ---- HUD: dash-style gauges ---------------------------------------------------------

    private fun hud(c: Canvas, hl: Float, o: MomentOverlay, on: Set<MomentField>) {
        // Soft vignette at the bottom so the gauges read on bright footage.
        c.drawRect(0f, hl - 700f, W, hl, Paint().apply {
            shader = LinearGradient(0f, hl - 700f, 0f, hl, Color.TRANSPARENT, withAlpha(Color.BLACK, 170), Shader.TileMode.CLAMP)
        })
        var ty = 150f
        if (o.shows(MomentField.EVENT, on)) {
            eventChip(c, 64f, ty, o)
            ty += 70f
        }
        if (o.shows(MomentField.TIME, on)) text(c, "${o.timeText}  ·  ${o.dateText}", 64f, ty, regular, 30f, WHITE)
        if (o.shows(MomentField.MAP, on)) miniMap(c, W - 64f - 130f, 64f + 130f, 130f, o, circle = true)

        val cy = hl - 330f
        if (o.shows(MomentField.SPEED, on)) speedGauge(c, W / 2f, cy, 190f, o.speedMps)
        if (o.shows(MomentField.LEAN, on)) leanGauge(c, 175f, cy + 40f, 95f, o.lean!!)
        if (o.shows(MomentField.G_FORCE, on)) gGauge(c, W - 175f, cy + 40f, 95f, o)
        if (o.shows(MomentField.BRAND, on)) brand(c, o, 0f, hl - 60f, 20f, 180, centered = true)
    }

    private fun speedGauge(c: Canvas, cx: Float, cy: Float, r: Float, speedMps: Double?) {
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val track = stroke(14f, withAlpha(WHITE, 60))
        c.drawArc(oval, 150f, 240f, false, track)
        val kmh = (speedMps ?: 0.0) * 3.6
        val f = (kmh / 160.0).toFloat().coerceIn(0f, 1f)
        if (f > 0f) c.drawArc(oval, 150f, 240f * f, false, stroke(14f, ACCENT).apply { setShadowLayer(12f, 0f, 0f, withAlpha(ACCENT, 160)) })
        val v = Format.speedKmh(speedMps)
        val vw = measure(v, light, 150f, -0.03f)
        text(c, v, cx - vw / 2f, cy + 40f, light, 150f, WHITE, tracking = -0.03f)
        centered(c, "km/h", cy + 96f, regular, 30f, WHITE, x = cx, alpha = 210)
    }

    /** A little horizon tilted as the bike was, with the angle under it. */
    private fun leanGauge(c: Canvas, cx: Float, cy: Float, r: Float, lean: Double) {
        c.drawCircle(cx, cy, r, stroke(5f, withAlpha(WHITE, 90)))
        val col = if (lean < 0) LEFT else RIGHT
        c.save()
        c.rotate(-lean.toFloat(), cx, cy)
        c.drawLine(cx - r + 14f, cy, cx + r - 14f, cy, stroke(7f, col))
        c.restore()
        c.drawLine(cx, cy - r + 10f, cx, cy - r + 30f, stroke(4f, WHITE))
        centered(c, leanText(lean), cy + r + 62f, medium, 44f, col, x = cx)
        centered(c, "LEAN", cy + r + 100f, semibold, 20f, WHITE, x = cx, tracking = 0.2f, alpha = 180)
    }

    /** Friction-circle style: a dot pushed by braking/acceleration (and cornering, if known). */
    private fun gGauge(c: Canvas, cx: Float, cy: Float, r: Float, o: MomentOverlay) {
        val g = o.gForce ?: return
        c.drawCircle(cx, cy, r, stroke(5f, withAlpha(WHITE, 90)))
        c.drawCircle(cx, cy, r / 2f, stroke(3f, withAlpha(WHITE, 50)))
        c.drawLine(cx - r, cy, cx + r, cy, stroke(2f, withAlpha(WHITE, 50)))
        c.drawLine(cx, cy - r, cx, cy + r, stroke(2f, withAlpha(WHITE, 50)))
        val lat = o.point?.lateralG ?: 0.0
        val dx = (lat.coerceIn(-1.0, 1.0) * r).toFloat()
        val dy = (-g.coerceIn(-1.0, 1.0) * r).toFloat() // accel up, braking down
        val col = if (g < 0) BRAKE else ACCEL
        c.drawCircle(cx + dx, cy + dy, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; setShadowLayer(10f, 0f, 0f, col) })
        centered(c, gText(g) + " G", cy + r + 62f, medium, 44f, col, x = cx)
        centered(c, if (g < 0) "BRAKING" else "ACCEL", cy + r + 100f, semibold, 20f, WHITE, x = cx, tracking = 0.2f, alpha = 180)
    }

    // ---- Pieces -------------------------------------------------------------------------

    /** Returns the chip's right edge. */
    private fun eventChip(c: Canvas, x: Float, baseline: Float, o: MomentOverlay): Float {
        val label = o.eventLabel ?: return x
        val col = eventColor(o)
        val tw = measure(label, semibold, 32f)
        val r = RectF(x, baseline - 46f, x + tw + 80f, baseline + 16f)
        c.drawRoundRect(r, 31f, 31f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.BLACK, 140) })
        c.drawCircle(x + 32f, baseline - 15f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        text(c, label, x + 56f, baseline - 3f, semibold, 32f, WHITE, shadow = false)
        return r.right
    }

    /** Ride route with the moment's position; a circle or a rounded square, dark behind. */
    private fun miniMap(c: Canvas, cx: Float, cy: Float, r: Float, o: MomentOverlay, circle: Boolean) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0xFF0A0A0B.toInt(), 175) }
        val border = stroke(3f, withAlpha(WHITE, 110))
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        if (circle) {
            c.drawCircle(cx, cy, r, bg)
            c.drawCircle(cx, cy, r, border)
        } else {
            c.drawRoundRect(box, 32f, 32f, bg)
            c.drawRoundRect(box, 32f, 32f, border)
        }
        val pts = o.route
        val lat = o.point?.latitude ?: return
        val lon = o.point.longitude ?: return
        if (pts.size < 2) return
        val inner = r * if (circle) 0.62f else 0.78f
        val minLat = pts.minOf { it.first }
        val maxLat = pts.maxOf { it.first }
        val minLon = pts.minOf { it.second }
        val maxLon = pts.maxOf { it.second }
        val latScale = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * latScale).coerceAtLeast(1e-9)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
        val s = (2 * inner) / maxOf(spanX, spanY)
        fun x(la: Double, lo: Double) = (cx + ((lo - (minLon + maxLon) / 2) * latScale * s)).toFloat()
        fun y(la: Double) = (cy - ((la - (minLat + maxLat) / 2) * s)).toFloat()
        val path = Path().apply {
            moveTo(x(pts[0].first, pts[0].second), y(pts[0].first))
            for (i in 1 until pts.size) lineTo(x(pts[i].first, pts[i].second), y(pts[i].first))
        }
        c.drawPath(path, stroke(5f, withAlpha(WHITE, 210)).apply { strokeJoin = Paint.Join.ROUND })
        val px = x(lat, lon)
        val py = y(lat)
        c.drawCircle(px, py, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(ACCENT, 90) })
        c.drawCircle(px, py, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        c.drawCircle(px, py, 12f, stroke(4f, WHITE))
    }

    private fun valueChips(o: MomentOverlay, on: Set<MomentField>): List<Triple<String, String, Int>> = buildList {
        if (o.shows(MomentField.SPEED, on)) add(Triple(Format.speedKmh(o.speedMps), "km/h", WHITE))
        if (o.shows(MomentField.LEAN, on)) add(Triple(leanText(o.lean), "", if ((o.lean ?: 0.0) < 0) LEFT else RIGHT))
        if (o.shows(MomentField.G_FORCE, on)) add(Triple(gText(o.gForce), "G", if ((o.gForce ?: 0.0) < 0) BRAKE else ACCEL))
    }

    private fun colorFor(label: String, o: MomentOverlay): Int = when (label) {
        "LEAN" -> if ((o.lean ?: 0.0) < 0) LEFT else RIGHT
        "BRAKING" -> BRAKE
        "ACCEL" -> ACCEL
        else -> WHITE
    }

    private fun eventColor(o: MomentOverlay): Int = when {
        RideEventType.HARD_BRAKE in o.eventTypes -> BRAKE
        RideEventType.SIGNIFICANT_LEAN in o.eventTypes -> if ((o.eventValue ?: o.leanDeg ?: 0.0) < 0) LEFT else RIGHT
        RideEventType.STRONG_ACCELERATION in o.eventTypes -> ACCEL
        else -> ACCENT
    }

    /** The drawn keppo moto wordmark, then the ride name (and DEMO) as text. */
    private fun brand(c: Canvas, o: MomentOverlay, x: Float, y: Float, size: Float, alpha: Int, centered: Boolean = false) {
        val rest = listOfNotNull(o.rideName?.uppercase(), "DEMO".takeIf { o.demo }).joinToString("  ·  ").let { if (it.isEmpty()) it else "   ·   $it" }
        val markH = size * 1.15f
        val markW = KeppoWordmark.width(markH)
        val total = markW + if (rest.isEmpty()) 0f else measure(rest, semibold, size, 0.22f)
        val left = if (centered) (W - total) / 2f else x
        KeppoWordmark.draw(c, left, y, markH, withAlpha(WHITE, alpha), ACCENT, shadow = SHADOW)
        if (rest.isNotEmpty()) text(c, rest, left + markW, y, semibold, size, WHITE, tracking = 0.22f, alpha = alpha)
    }

    private fun leanText(lean: Double?) = lean?.let { "${abs(it).roundToInt()}° ${if (it < 0) "L" else "R"}" } ?: Format.DASH

    private fun gText(g: Double?) = g?.let { String.format(Locale.US, "%.2f", abs(it)) } ?: Format.DASH

    private fun stroke(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    private fun paint(face: Typeface, size: Float, color: Int, tracking: Float, shadow: Boolean, alpha: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            this.color = color
            this.alpha = alpha
            letterSpacing = tracking
            if (shadow) setShadowLayer(size * 0.08f + 4f, 0f, 2f, SHADOW)
        }

    private fun measure(s: String, face: Typeface, size: Float, tracking: Float = 0f) =
        paint(face, size, WHITE, tracking, false, 255).measureText(s)

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, face: Typeface, size: Float, color: Int,
        tracking: Float = 0f, shadow: Boolean = true, alpha: Int = 255, maxWidth: Float? = null,
    ): Float {
        val p = paint(face, size, color, tracking, shadow, alpha)
        var out = s
        if (maxWidth != null && p.measureText(out) > maxWidth) {
            while (out.isNotEmpty() && p.measureText("$out…") > maxWidth) out = out.dropLast(1)
            out = "$out…"
        }
        c.drawText(out, x, y, p)
        return p.measureText(out)
    }

    private fun centered(
        c: Canvas, s: String, y: Float, face: Typeface, size: Float, color: Int,
        x: Float = W / 2f, tracking: Float = 0f, alpha: Int = 255,
    ) {
        val w = measure(s, face, size, tracking)
        text(c, s, x - w / 2f, y, face, size, color, tracking, alpha = alpha)
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val W = 1080f
        private val WHITE = 0xFFF5F5F7.toInt()
        private val ACCENT = 0xFF69C8CB.toInt()
        private val LEFT = 0xFFA5A1FF.toInt()
        private val RIGHT = 0xFFFB7185.toInt()
        private val ACCEL = 0xFF4ADE80.toInt()
        private val BRAKE = 0xFFFB7185.toInt()
        private val SHADOW = 0x99000000.toInt()
    }
}
