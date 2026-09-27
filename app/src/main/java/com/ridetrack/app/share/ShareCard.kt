package com.ridetrack.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.ridetrack.app.R
import com.ridetrack.app.ui.format.Format
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.TelemetrySample
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ShareStyle(val label: String) {
    /** Full story card on a dark background. */
    SOLID("Card"),
    /** Transparent PNG: route and numbers only, to lay over your own photo. */
    OVERLAY("Overlay"),
}

/** A route point with the speed at that moment (null = unknown), for the heat-coloured line. */
data class SharePoint(val latitude: Double, val longitude: Double, val speedMps: Double?)

/** Everything the graphic shows; unknown values stay null and render as "--". */
data class ShareCardData(
    val title: String,
    val dateText: String,
    val bikeName: String?,
    val distanceM: Double,
    val durationMillis: Long?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val maxLeftLeanDeg: Double?,
    val maxRightLeanDeg: Double?,
    val route: List<SharePoint>,
    val demo: Boolean,
) {
    companion object {
        fun from(ride: Ride, bikeName: String?, samples: List<TelemetrySample>): ShareCardData {
            val pts = samples.mapNotNull { s ->
                val lat = s.latitude
                val lon = s.longitude
                if (lat != null && lon != null) SharePoint(lat, lon, s.speedMps) else null
            }
            return ShareCardData(
                title = ride.name,
                dateText = Format.rideDate(ride.startTimeMillis),
                bikeName = bikeName,
                distanceM = ride.stats.distanceM,
                durationMillis = ride.durationMillis,
                avgSpeedMps = ride.stats.avgSpeedMps,
                maxSpeedMps = ride.stats.maxSpeedMps,
                maxLeftLeanDeg = ride.stats.maxLeftLeanDeg,
                maxRightLeanDeg = ride.stats.maxRightLeanDeg,
                route = thin(pts, 600),
                demo = ride.source == DataSourceKind.DEMO,
            )
        }

        private fun thin(pts: List<SharePoint>, max: Int): List<SharePoint> {
            if (pts.size <= max) return pts
            val step = pts.size.toDouble() / (max - 1)
            return List(max - 1) { pts[(it * step).toInt()] } + pts.last()
        }
    }
}

/**
 * Draws the Instagram-story graphics (1080×1920) with plain Android Canvas so the output
 * is pixel-exact regardless of screen density.
 *
 * The route is a "heat line": teal when cruising, amber, then red toward the ride's top
 * speed. Max lean is shown as a small protractor with a needle each side.
 */
class ShareCardRenderer(context: Context) {
    private val light = font(context, R.font.geist_light)
    private val regular = font(context, R.font.geist_regular)
    private val medium = font(context, R.font.geist_medium)
    private val semibold = font(context, R.font.geist_semibold)

    private fun font(context: Context, id: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT

    fun render(data: ShareCardData, style: ShareStyle): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (style) {
            ShareStyle.SOLID -> drawSolid(c, data)
            ShareStyle.OVERLAY -> drawOverlay(c, data)
        }
        return bmp
    }

    // ---- Solid card ---------------------------------------------------------------------

    private fun drawSolid(c: Canvas, d: ShareCardData) {
        // Deep background with a faint teal lift toward the bottom.
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(BG, BG, 0xFF0C1718.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        val routeBox = RectF(110f, 240f, W - 110f, 940f)
        // Soft glow pooled behind the route.
        c.drawCircle(routeBox.centerX(), routeBox.centerY(), 560f, Paint().apply {
            shader = RadialGradient(routeBox.centerX(), routeBox.centerY(), 560f, withAlpha(ACCENT, 46), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        drawDotGrid(c)

        header(c, d, shadow = false)
        drawRoute(c, d.route, routeBox, overlay = false)

        var y = 1060f
        text(c, d.title, 90f, y, semibold, 54f, WHITE, maxWidth = W - 180f)
        y += 52f
        text(c, listOfNotNull(d.dateText, d.bikeName).joinToString("  ·  "), 90f, y, regular, 32f, MUTED, maxWidth = W - 180f)

        y += 240f
        val km = String.format(java.util.Locale.US, "%.1f", d.distanceM / 1000.0)
        val kmWidth = text(c, km, 82f, y, light, 250f, WHITE, tracking = -0.03f)
        text(c, "km", 82f + kmWidth + 18f, y, regular, 64f, MUTED)

        y += 106f
        hairline(c, 90f, y - 44f, W - 90f)
        stat(c, 90f, y, "TIME", Format.duration(d.durationMillis), null, shadow = false)
        stat(c, 420f, y, "AVG", Format.speedKmh(d.avgSpeedMps), "km/h", shadow = false)
        stat(c, 720f, y, "TOP", Format.speedKmh(d.maxSpeedMps), "km/h", shadow = false)

        leanGauge(c, W / 2f, 1770f, d, shadow = false)
        footer(c, d, shadow = false)
    }

    private fun drawDotGrid(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(WHITE, 14) }
        var y = 40f
        while (y < H) {
            var x = 40f
            while (x < W) {
                c.drawCircle(x, y, 1.6f, p)
                x += 40f
            }
            y += 40f
        }
    }

    // ---- Transparent overlay ------------------------------------------------------------

    private fun drawOverlay(c: Canvas, d: ShareCardData) {
        header(c, d, shadow = true)
        drawRoute(c, d.route, RectF(190f, 330f, W - 190f, 1010f), overlay = true)

        var y = 1300f
        val km = String.format(java.util.Locale.US, "%.1f", d.distanceM / 1000.0)
        val kmSize = 230f
        val unitSize = 60f
        val kmW = measure(km, light, kmSize, -0.03f)
        val unitW = measure("km", regular, unitSize)
        val x0 = (W - (kmW + 16f + unitW)) / 2f
        text(c, km, x0, y, light, kmSize, WHITE, tracking = -0.03f, shadow = true)
        text(c, "km", x0 + kmW + 16f, y, regular, unitSize, WHITE, shadow = true)

        y += 64f
        centered(c, d.title, y, medium, 40f, WHITE, shadow = true)

        y += 150f
        stat(c, 120f, y, "TIME", Format.duration(d.durationMillis), null, shadow = true)
        stat(c, 430f, y, "AVG", Format.speedKmh(d.avgSpeedMps), "km/h", shadow = true)
        stat(c, 730f, y, "TOP", Format.speedKmh(d.maxSpeedMps), "km/h", shadow = true)

        leanGauge(c, W / 2f, 1780f, d, shadow = true)
    }

    // ---- Shared pieces ------------------------------------------------------------------

    private fun header(c: Canvas, d: ShareCardData, shadow: Boolean) {
        val y = 150f
        c.drawCircle(98f, y - 13f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; if (shadow) setShadowLayer(8f, 0f, 2f, SHADOW) })
        text(c, "RIDE TRACK", 122f, y, semibold, 30f, WHITE, tracking = 0.28f, shadow = shadow)
        if (d.demo) {
            val w = measure("DEMO", semibold, 26f, 0.2f)
            val r = RectF(W - 90f - w - 36f, y - 38f, W - 90f, y + 10f)
            c.drawRoundRect(r, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(WARNING, 50) })
            text(c, "DEMO", r.left + 18f, y - 3f, semibold, 26f, WARNING, tracking = 0.2f)
        }
    }

    private fun footer(c: Canvas, d: ShareCardData, shadow: Boolean) {
        val msg = if (d.demo) "Simulated ride · recorded with Ride Track" else "Recorded with Ride Track"
        centered(c, msg, H - 44f, regular, 26f, MUTED, shadow = shadow)
    }

    private fun stat(c: Canvas, x: Float, y: Float, label: String, value: String, unit: String?, shadow: Boolean) {
        text(c, label, x, y, semibold, 24f, if (shadow) WHITE else MUTED, tracking = 0.2f, shadow = shadow, alpha = if (shadow) 210 else 255)
        val vw = text(c, value, x, y + 76f, medium, 68f, WHITE, tracking = -0.01f, shadow = shadow)
        if (unit != null && value != Format.DASH) text(c, unit, x + vw + 10f, y + 76f, regular, 28f, if (shadow) WHITE else MUTED, shadow = shadow)
    }

    /**
     * Upper semicircle, upright at the top; a needle each side at the max lean reached.
     * Unknown lean (no calibration / no gyro) draws the dial only, with "--".
     */
    private fun leanGauge(c: Canvas, cx: Float, cy: Float, d: ShareCardData, shadow: Boolean) {
        val r = 120f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            color = if (shadow) withAlpha(WHITE, 150) else withAlpha(WHITE, 40)
            if (shadow) setShadowLayer(6f, 0f, 2f, SHADOW)
        }
        c.drawArc(oval, 180f, 180f, false, track)
        // 15° ticks.
        val tick = Paint(track).apply { strokeWidth = 3f }
        for (deg in -60..60 step 15) {
            val a = Math.toRadians(270.0 + deg)
            val r1 = r - 14f
            val r2 = if (deg == 0) r - 34f else r - 24f
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), tick)
        }
        fun needle(deg: Double?, color: Int) {
            if (deg == null) return
            val a = Math.toRadians(270.0 + deg.coerceIn(-70.0, 70.0))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 7f
                strokeCap = Paint.Cap.ROUND
                this.color = color
                if (shadow) setShadowLayer(6f, 0f, 2f, SHADOW)
            }
            c.drawLine(cx, cy, cx + (r - 6f) * cos(a).toFloat(), cy + (r - 6f) * sin(a).toFloat(), p)
        }
        needle(d.maxLeftLeanDeg?.let { -abs(it) }, if (shadow) WHITE else LEFT)
        needle(d.maxRightLeanDeg?.let { abs(it) }, if (shadow) WHITE else RIGHT)
        c.drawCircle(cx, cy, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; if (shadow) setShadowLayer(6f, 0f, 2f, SHADOW) })

        val left = d.maxLeftLeanDeg?.let { "${abs(it).roundToInt()}°" } ?: Format.DASH
        val right = d.maxRightLeanDeg?.let { "${abs(it).roundToInt()}°" } ?: Format.DASH
        val lw = measure(left, medium, 56f)
        text(c, left, cx - r - 40f - lw, cy, medium, 56f, if (shadow) WHITE else LEFT, shadow = shadow)
        text(c, right, cx + r + 40f, cy, medium, 56f, if (shadow) WHITE else RIGHT, shadow = shadow)
        text(c, "L", cx - r - 40f - lw, cy - 64f, semibold, 22f, if (shadow) WHITE else MUTED, tracking = 0.2f, shadow = shadow)
        text(c, "R", cx + r + 40f, cy - 64f, semibold, 22f, if (shadow) WHITE else MUTED, tracking = 0.2f, shadow = shadow)
        centered(c, "MAX LEAN", cy + 50f, semibold, 22f, if (shadow) WHITE else MUTED, tracking = 0.2f, shadow = shadow)
    }

    private fun drawRoute(c: Canvas, route: List<SharePoint>, box: RectF, overlay: Boolean) {
        if (route.size < 2) {
            centered(c, "No GPS route recorded", box.centerY(), regular, 34f, MUTED, shadow = overlay)
            return
        }
        val minLat = route.minOf { it.latitude }
        val maxLat = route.maxOf { it.latitude }
        val minLon = route.minOf { it.longitude }
        val maxLon = route.maxOf { it.longitude }
        val latScale = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * latScale).coerceAtLeast(1e-9)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
        val scale = minOf(box.width() / spanX, box.height() / spanY)
        val offX = box.left + (box.width() - spanX * scale) / 2
        val offY = box.top + (box.height() - spanY * scale) / 2
        fun x(p: SharePoint) = (offX + (p.longitude - minLon) * latScale * scale).toFloat()
        fun y(p: SharePoint) = (offY + (maxLat - p.latitude) * scale).toFloat()

        val path = Path().apply {
            moveTo(x(route[0]), y(route[0]))
            for (i in 1 until route.size) lineTo(x(route[i]), y(route[i]))
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        if (overlay) {
            // Dark halo so a white line reads on any photo.
            c.drawPath(path, Paint(stroke).apply { strokeWidth = 26f; color = withAlpha(Color.BLACK, 70); maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) })
            c.drawPath(path, Paint(stroke).apply { strokeWidth = 11f; color = WHITE })
        } else {
            c.drawPath(path, Paint(stroke).apply { strokeWidth = 34f; color = withAlpha(ACCENT, 40); maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL) })
            c.drawPath(path, Paint(stroke).apply { strokeWidth = 16f; color = withAlpha(Color.BLACK, 160) })
            // Heat line, segment by segment.
            val top = route.mapNotNull { it.speedMps }.maxOrNull()?.takeIf { it > 1.0 }
            val seg = Paint(stroke).apply { strokeWidth = 10f }
            for (i in 1 until route.size) {
                val a = route[i - 1]
                val b = route[i]
                val v = b.speedMps
                seg.color = if (top == null || v == null) ACCENT else heat((v / top).toFloat())
                c.drawLine(x(a), y(a), x(b), y(b), seg)
            }
        }
        // Start (hollow) and finish (filled).
        val s = route.first()
        val e = route.last()
        c.drawCircle(x(s), y(s), 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (overlay) WHITE else BG })
        c.drawCircle(x(s), y(s), 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = if (overlay) Color.BLACK else WHITE })
        c.drawCircle(x(e), y(e), 17f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; if (overlay) setShadowLayer(8f, 0f, 2f, SHADOW) })
    }

    /** Teal → amber → red. */
    private fun heat(f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        return if (t < 0.6f) lerp(ACCENT, WARM, t / 0.6f) else lerp(WARM, HOT, (t - 0.6f) / 0.4f)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )

    private fun hairline(c: Canvas, x0: Float, y: Float, x1: Float) {
        c.drawLine(x0, y, x1, y, Paint().apply { color = withAlpha(WHITE, 26); strokeWidth = 2f })
    }

    private fun paint(face: Typeface, size: Float, color: Int, tracking: Float, shadow: Boolean, alpha: Int = 255) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            this.color = color
            this.alpha = alpha
            letterSpacing = tracking
            if (shadow) setShadowLayer(size * 0.08f + 4f, 0f, 2f, SHADOW)
        }

    private fun measure(s: String, face: Typeface, size: Float, tracking: Float = 0f): Float =
        paint(face, size, WHITE, tracking, false).measureText(s)

    /** Draws at a baseline; returns the drawn width. Ellipsizes past [maxWidth]. */
    private fun text(
        c: Canvas,
        s: String,
        x: Float,
        y: Float,
        face: Typeface,
        size: Float,
        color: Int,
        tracking: Float = 0f,
        shadow: Boolean = false,
        maxWidth: Float? = null,
        alpha: Int = 255,
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

    private fun centered(c: Canvas, s: String, y: Float, face: Typeface, size: Float, color: Int, tracking: Float = 0f, shadow: Boolean = false) {
        val w = measure(s, face, size, tracking)
        text(c, s, (W - w) / 2f, y, face, size, color, tracking, shadow)
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        const val W = 1080
        const val H = 1920
        private val BG = 0xFF0A0A0B.toInt()
        private val WHITE = 0xFFF5F5F7.toInt()
        private val MUTED = 0xFF8E8E96.toInt()
        private val ACCENT = 0xFF69C8CB.toInt()
        private val WARM = 0xFFF5B94A.toInt()
        private val HOT = 0xFFFF5A4E.toInt()
        private val WARNING = 0xFFFBBF24.toInt()
        private val LEFT = 0xFFA5A1FF.toInt()
        private val RIGHT = 0xFFFB7185.toInt()
        private val SHADOW = 0x80000000.toInt()
    }
}
