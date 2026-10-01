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

/**
 * Share layouts, from a full card to a small badge. [coverage] is roughly how much of the
 * story frame the graphic occupies; transparent layouts go on top of your own photo.
 */
enum class ShareStyle(val label: String, val coverage: String, val transparent: Boolean) {
    /** Full story card on a dark background. */
    SOLID("Card", "Full", false),
    /** Route and numbers, centred. */
    OVERLAY("Overlay", "~70%", true),
    /** A small ticket in the corner. */
    CORNER("Badge", "~10%", true),
    /** A band along the bottom. */
    BAR("Bottom bar", "~20%", true),
    /** Giant distance across the top. */
    HEADLINE("Headline", "~30%", true),
    /** Just the route as a heat line. */
    TRACE("Trace", "~50%", true),
    /** A boarding-pass card: from → to, perforation, stats. */
    PASS("Boarding pass", "~40%", true),
    /** Camera viewfinder frame with a tilted horizon at max lean. */
    VIEWFINDER("Viewfinder", "Frame", true),
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
    val startMillis: Long? = null,
    val endMillis: Long? = null,
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
                startMillis = ride.startTimeMillis,
                endMillis = ride.endTimeMillis,
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
            ShareStyle.CORNER -> drawCorner(c, data)
            ShareStyle.BAR -> drawBar(c, data)
            ShareStyle.HEADLINE -> drawHeadline(c, data)
            ShareStyle.TRACE -> drawTrace(c, data)
            ShareStyle.PASS -> drawPass(c, data)
            ShareStyle.VIEWFINDER -> drawViewfinder(c, data)
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

    // ---- Badge (~10%) --------------------------------------------------------------------

    private fun drawCorner(c: Canvas, d: ShareCardData) {
        val card = RectF(64f, H - 380f, 64f + 560f, H - 110f)
        c.drawRoundRect(card, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0xFF0A0A0B.toInt(), 170) })
        c.drawRoundRect(card, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = withAlpha(WHITE, 50) })
        val sketch = RectF(card.left + 32f, card.top + 40f, card.left + 212f, card.bottom - 40f)
        drawRoute(c, d.route, sketch, RouteInk.HEAT_ON_PHOTO, k = 0.42f)
        val x = sketch.right + 34f
        text(c, "KEPPO MOTO", x, card.top + 70f, semibold, 20f, withAlpha(WHITE, 190), tracking = 0.28f)
        val kmW = text(c, km(d), x, card.top + 168f, medium, 88f, WHITE, tracking = -0.02f)
        text(c, "km", x + kmW + 8f, card.top + 168f, regular, 30f, MUTED)
        text(c, "${Format.duration(d.durationMillis)} · ${Format.speedKmh(d.avgSpeedMps)} avg", x, card.top + 218f, regular, 28f, withAlpha(WHITE, 210), maxWidth = card.right - x - 24f)
    }

    // ---- Bottom bar (~20%) --------------------------------------------------------------

    private fun drawBar(c: Canvas, d: ShareCardData) {
        val top = H - 400f
        c.drawRect(0f, top - 120f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, top - 120f, 0f, H.toFloat(), intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, 150), withAlpha(Color.BLACK, 205)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        val sketch = RectF(70f, top + 40f, 300f, top + 270f)
        drawRoute(c, d.route, sketch, RouteInk.HEAT_ON_PHOTO, k = 0.5f)
        val x = 350f
        text(c, d.title.uppercase(), x, top + 70f, semibold, 24f, withAlpha(WHITE, 200), tracking = 0.18f, maxWidth = W - x - 70f)
        val kmW = text(c, km(d), x, top + 190f, light, 130f, WHITE, tracking = -0.03f)
        text(c, "km", x + kmW + 12f, top + 190f, regular, 40f, MUTED)
        val y = top + 262f
        var cx = x
        listOf(Format.duration(d.durationMillis) to "time", Format.speedKmh(d.avgSpeedMps) to "avg", Format.speedKmh(d.maxSpeedMps) to "top").forEach { (v, l) ->
            val vw = text(c, v, cx, y, medium, 40f, WHITE)
            val lw = text(c, " $l", cx + vw, y, regular, 26f, MUTED)
            cx += vw + lw + 34f
        }
        text(c, "KEPPO MOTO", W - 70f - measure("KEPPO MOTO", semibold, 20f, 0.28f), H - 60f, semibold, 20f, withAlpha(WHITE, 150), tracking = 0.28f)
    }

    // ---- Headline (~30%) ----------------------------------------------------------------

    private fun drawHeadline(c: Canvas, d: ShareCardData) {
        c.drawRect(0f, 0f, W.toFloat(), 720f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 720f, intArrayOf(withAlpha(Color.BLACK, 190), withAlpha(Color.BLACK, 110), Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        header(c, d, shadow = true)
        val kmW = text(c, km(d), 58f, 450f, light, 330f, WHITE, tracking = -0.05f, shadow = true)
        text(c, "KM", 58f + kmW + 14f, 450f, semibold, 70f, ACCENT, tracking = 0.08f, shadow = true)
        text(c, d.title.uppercase(), 70f, 520f, semibold, 30f, WHITE, tracking = 0.22f, shadow = true, maxWidth = W - 140f)
        val lean = listOfNotNull(d.maxLeftLeanDeg, d.maxRightLeanDeg).maxOfOrNull { abs(it) }
        val bits = listOfNotNull(
            Format.duration(d.durationMillis),
            d.avgSpeedMps?.let { "${Format.speedKmh(it)} avg" },
            d.maxSpeedMps?.let { "${Format.speedKmh(it)} top" },
            lean?.let { "${it.roundToInt()}° lean" },
        )
        text(c, bits.joinToString("   ·   "), 70f, 580f, regular, 34f, withAlpha(WHITE, 225), shadow = true, maxWidth = W - 140f)
    }

    // ---- Trace (~50%) -------------------------------------------------------------------

    private fun drawTrace(c: Canvas, d: ShareCardData) {
        drawRoute(c, d.route, RectF(150f, 520f, W - 150f, 1300f), RouteInk.HEAT_ON_PHOTO)
        centered(c, "${km(d)} km  ·  ${d.title}", 1400f, medium, 38f, WHITE, shadow = true)
        centered(c, "KEPPO MOTO", 1450f, semibold, 20f, withAlpha(WHITE, 190), tracking = 0.3f, shadow = true)
    }

    // ---- Boarding pass (~40%) -----------------------------------------------------------

    private fun drawPass(c: Canvas, d: ShareCardData) {
        val card = RectF(70f, 1060f, W - 70f, 1840f)
        val perfY = card.top + 430f
        c.drawRoundRect(card, 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER; setShadowLayer(30f, 0f, 10f, withAlpha(Color.BLACK, 110)) })
        // Notches at the perforation, cut through to transparency.
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
        c.drawCircle(card.left, perfY, 30f, clear)
        c.drawCircle(card.right, perfY, 30f, clear)
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(INK, 70)
            strokeWidth = 3f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 12f), 0f)
        }
        c.drawLine(card.left + 50f, perfY, card.right - 50f, perfY, dash)

        val l = card.left + 60f
        val r = card.right - 60f
        text(c, "KEPPO MOTO  ·  RIDE PASS", l, card.top + 78f, semibold, 22f, INK_MUTED, tracking = 0.24f)
        if (d.demo) text(c, "DEMO", r - measure("DEMO", semibold, 22f, 0.24f), card.top + 78f, semibold, 22f, WARNING, tracking = 0.24f)
        val from = d.startMillis?.let { Format.timeOfDay(it) } ?: Format.DASH
        val to = d.endMillis?.let { Format.timeOfDay(it) } ?: Format.DASH
        text(c, "START", l, card.top + 150f, semibold, 20f, INK_MUTED, tracking = 0.2f)
        text(c, from, l, card.top + 236f, medium, 64f, INK, tracking = -0.02f)
        text(c, "FINISH", r - measure("FINISH", semibold, 20f, 0.2f), card.top + 150f, semibold, 20f, INK_MUTED, tracking = 0.2f)
        text(c, to, r - measure(to, medium, 64f, -0.02f), card.top + 236f, medium, 64f, INK, tracking = -0.02f)
        // The route itself is the "flight path" between the two times.
        drawRoute(c, d.route, RectF(card.centerX() - 80f, card.top + 130f, card.centerX() + 80f, card.top + 250f), RouteInk.DARK, k = 0.4f)
        text(c, d.title, l, card.top + 330f, semibold, 40f, INK, maxWidth = r - l)
        text(c, listOfNotNull(d.dateText, d.bikeName?.let { "Bike: $it" }).joinToString("  ·  "), l, card.top + 380f, regular, 28f, INK_MUTED, maxWidth = r - l)

        val y = perfY + 90f
        val cols = listOf(
            "DISTANCE" to "${km(d)} km",
            "TIME" to Format.duration(d.durationMillis),
            "TOP" to "${Format.speedKmh(d.maxSpeedMps)} km/h",
            "MAX LEAN" to (listOfNotNull(d.maxLeftLeanDeg, d.maxRightLeanDeg).maxOfOrNull { abs(it) }?.let { "${it.roundToInt()}°" } ?: Format.DASH),
        )
        val colW = (r - l) / cols.size
        cols.forEachIndexed { i, (label, value) ->
            val x = l + i * colW
            text(c, label, x, y, semibold, 20f, INK_MUTED, tracking = 0.2f)
            text(c, value, x, y + 58f, medium, 44f, INK, maxWidth = colW - 12f)
        }
        // A "barcode" made from the ride's speed profile.
        val bars = d.route.mapNotNull { it.speedMps }
        if (bars.size > 8) {
            val n = 64
            val top = bars.maxOrNull()!!.coerceAtLeast(1.0)
            val bw = (r - l) / n
            val base = card.bottom - 50f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
            for (i in 0 until n) {
                val v = bars[(i * bars.size / n).coerceAtMost(bars.size - 1)]
                val h = 20f + 90f * (v / top).toFloat()
                c.drawRect(l + i * bw, base - h, l + i * bw + bw * 0.55f, base, p)
            }
        }
    }

    // ---- Viewfinder (frame) -------------------------------------------------------------

    private fun drawViewfinder(c: Canvas, d: ShareCardData) {
        val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(8f, 0f, 2f, SHADOW)
        }
        val m = 70f
        val len = 110f
        listOf(m to m, W - m to m, m to H - m, W - m to H - m).forEach { (x, y) ->
            val sx = if (x < W / 2) 1 else -1
            val sy = if (y < H / 2) 1 else -1
            c.drawLine(x, y, x + sx * len, y, bracket)
            c.drawLine(x, y, x, y + sy * len, bracket)
        }
        c.drawCircle(m + 50f, m + 72f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF43F5E.toInt() })
        text(c, "RIDE", m + 74f, m + 82f, semibold, 30f, WHITE, tracking = 0.2f, shadow = true)
        text(c, d.dateText, m + 50f, m + 130f, regular, 28f, WHITE, shadow = true)
        val lean = listOfNotNull(d.maxLeftLeanDeg?.let { -abs(it) }, d.maxRightLeanDeg?.let { abs(it) }).maxByOrNull { abs(it) }
        val leanText = lean?.let { "${abs(it).roundToInt()}° ${if (it < 0) "L" else "R"}" } ?: Format.DASH
        text(c, "MAX LEAN", W - m - 50f - measure("MAX LEAN", semibold, 22f, 0.2f), m + 82f, semibold, 22f, WHITE, tracking = 0.2f, shadow = true)
        text(c, leanText, W - m - 50f - measure(leanText, medium, 56f), m + 150f, medium, 56f, WHITE, shadow = true)

        // Artificial horizon: level marks stay put, the horizon tilts the way it looked from
        // the bike at max lean (opposite to the lean), with the angle between them.
        val cx = W / 2f
        val cy = H / 2f
        val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(WHITE, 200); strokeWidth = 4f; setShadowLayer(6f, 0f, 2f, SHADOW) }
        val level = Paint(horizon).apply { color = withAlpha(WHITE, 110); strokeWidth = 3f }
        c.drawLine(cx - 420f, cy, cx - 360f, cy, level)
        c.drawLine(cx + 360f, cy, cx + 420f, cy, level)
        lean?.let {
            val a = abs(it).toFloat()
            val arc = RectF(cx - 380f, cy - 380f, cx + 380f, cy + 380f)
            val side = if (it < 0) 0f else 180f
            c.drawArc(arc, side, if (it < 0) a else -a, false, Paint(level).apply { style = Paint.Style.STROKE })
        }
        c.save()
        c.rotate(-(lean ?: 0.0).toFloat(), cx, cy)
        c.drawLine(cx - 330f, cy, cx - 70f, cy, horizon)
        c.drawLine(cx + 70f, cy, cx + 330f, cy, horizon)
        for (t in listOf(-260f, -170f, 170f, 260f)) c.drawLine(cx + t, cy - 14f, cx + t, cy + 14f, horizon)
        c.restore()
        c.drawCircle(cx, cy, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = WHITE; setShadowLayer(6f, 0f, 2f, SHADOW) })
        c.drawCircle(cx, cy, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE })

        val by = H - m - 60f
        text(c, "TOP SPEED", m + 50f, by - 110f, semibold, 22f, WHITE, tracking = 0.2f, shadow = true)
        val sw = text(c, Format.speedKmh(d.maxSpeedMps), m + 50f, by, light, 120f, WHITE, shadow = true)
        text(c, "km/h", m + 50f + sw + 10f, by, regular, 34f, WHITE, shadow = true)
        val dist = km(d)
        text(c, "DISTANCE", W - m - 50f - measure("DISTANCE", semibold, 22f, 0.2f), by - 110f, semibold, 22f, WHITE, tracking = 0.2f, shadow = true)
        val uw = measure("km", regular, 34f)
        val dw = measure(dist, light, 120f)
        text(c, dist, W - m - 50f - uw - 10f - dw, by, light, 120f, WHITE, shadow = true)
        text(c, "km", W - m - 50f - uw, by, regular, 34f, WHITE, shadow = true)
        centered(c, "${Format.duration(d.durationMillis)}  ·  KEPPO MOTO", H - m - 20f, semibold, 22f, withAlpha(WHITE, 200), tracking = 0.2f, shadow = true)
    }

    private fun km(d: ShareCardData) = String.format(java.util.Locale.US, "%.1f", d.distanceM / 1000.0)

    // ---- Shared pieces ------------------------------------------------------------------

    private fun header(c: Canvas, d: ShareCardData, shadow: Boolean) {
        val y = 150f
        c.drawCircle(98f, y - 13f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; if (shadow) setShadowLayer(8f, 0f, 2f, SHADOW) })
        text(c, "KEPPO MOTO", 122f, y, semibold, 30f, WHITE, tracking = 0.28f, shadow = shadow)
        if (d.demo) {
            val w = measure("DEMO", semibold, 26f, 0.2f)
            val r = RectF(W - 90f - w - 36f, y - 38f, W - 90f, y + 10f)
            c.drawRoundRect(r, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(WARNING, 50) })
            text(c, "DEMO", r.left + 18f, y - 3f, semibold, 26f, WARNING, tracking = 0.2f)
        }
    }

    private fun footer(c: Canvas, d: ShareCardData, shadow: Boolean) {
        val msg = if (d.demo) "Simulated ride · recorded with Keppo Moto" else "Recorded with Keppo Moto"
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

    private enum class RouteInk { HEAT, WHITE, HEAT_ON_PHOTO, DARK }

    private fun drawRoute(c: Canvas, route: List<SharePoint>, box: RectF, overlay: Boolean) =
        drawRoute(c, route, box, if (overlay) RouteInk.WHITE else RouteInk.HEAT)

    /** [k] scales stroke widths and end dots for small sketches. */
    private fun drawRoute(c: Canvas, route: List<SharePoint>, box: RectF, ink: RouteInk, k: Float = 1f) {
        val overlay = ink != RouteInk.HEAT
        if (route.size < 2) {
            if (k >= 1f) centered(c, "No GPS route recorded", box.centerY(), regular, 34f, MUTED, shadow = overlay)
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
        fun heatSegments(width: Float) {
            val top = route.mapNotNull { it.speedMps }.maxOrNull()?.takeIf { it > 1.0 }
            val seg = Paint(stroke).apply { strokeWidth = width }
            for (i in 1 until route.size) {
                val a = route[i - 1]
                val b = route[i]
                val v = b.speedMps
                seg.color = if (top == null || v == null) ACCENT else heat((v / top).toFloat())
                c.drawLine(x(a), y(a), x(b), y(b), seg)
            }
        }
        when (ink) {
            RouteInk.WHITE -> {
                // Dark halo so a white line reads on any photo.
                c.drawPath(path, Paint(stroke).apply { strokeWidth = 26f * k; color = withAlpha(Color.BLACK, 70); maskFilter = BlurMaskFilter(14f * k, BlurMaskFilter.Blur.NORMAL) })
                c.drawPath(path, Paint(stroke).apply { strokeWidth = 11f * k; color = WHITE })
            }
            RouteInk.HEAT_ON_PHOTO -> {
                c.drawPath(path, Paint(stroke).apply { strokeWidth = 30f * k; color = withAlpha(Color.BLACK, 90); maskFilter = BlurMaskFilter(16f * k, BlurMaskFilter.Blur.NORMAL) })
                c.drawPath(path, Paint(stroke).apply { strokeWidth = 17f * k; color = withAlpha(Color.BLACK, 150) })
                heatSegments(11f * k)
            }
            RouteInk.DARK -> c.drawPath(path, Paint(stroke).apply { strokeWidth = 8f * k; color = INK })
            RouteInk.HEAT -> {
                c.drawPath(path, Paint(stroke).apply { strokeWidth = 34f * k; color = withAlpha(ACCENT, 40); maskFilter = BlurMaskFilter(22f * k, BlurMaskFilter.Blur.NORMAL) })
                c.drawPath(path, Paint(stroke).apply { strokeWidth = 16f * k; color = withAlpha(Color.BLACK, 160) })
                heatSegments(10f * k)
            }
        }
        // Start (hollow) and finish (filled).
        val s = route.first()
        val e = route.last()
        val fill = when (ink) {
            RouteInk.HEAT -> BG
            RouteInk.DARK -> PAPER
            else -> WHITE
        }
        val ring = when (ink) {
            RouteInk.HEAT -> WHITE
            RouteInk.DARK -> INK
            else -> Color.BLACK
        }
        c.drawCircle(x(s), y(s), 15f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        c.drawCircle(x(s), y(s), 15f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f * k; color = ring })
        c.drawCircle(x(e), y(e), 17f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ink == RouteInk.DARK) INK else WHITE
            if (overlay && ink != RouteInk.DARK) setShadowLayer(8f, 0f, 2f, SHADOW)
        })
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
        private val PAPER = 0xFFF3EFE6.toInt()
        private val INK = 0xFF16161A.toInt()
        private val INK_MUTED = 0xFF6E6A62.toInt()
    }
}
