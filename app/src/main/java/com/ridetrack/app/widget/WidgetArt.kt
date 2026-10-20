package com.ridetrack.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.time.LocalDate
import kotlin.math.max

/**
 * The widgets' pictures, drawn as bitmaps (home-screen widgets can't clip, round or draw on
 * their own): bike photos, routes, page dots, the sun's arc and the riding calendar. Sizes are in
 * dp; drawn at up to 2.5× so they stay sharp without using much of the widget memory budget.
 */
class WidgetArt(context: Context) {
    private val density = context.resources.displayMetrics.density.coerceAtMost(2.5f)
    private fun px(dp: Float) = (dp * density).toInt().coerceAtLeast(1)
    private fun bmp(wDp: Float, hDp: Float) = Bitmap.createBitmap(px(wDp), px(hDp), Bitmap.Config.ARGB_8888)

    /** A picture file cropped to fill [wDp]×[hDp] with rounded corners; null when it can't be read. */
    fun photo(file: File?, wDp: Float, hDp: Float, radiusDp: Float): Bitmap? {
        if (file == null || !file.isFile) return null
        val w = px(wDp)
        val h = px(hDp)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= w && opts.outHeight / (sample * 2) >= h) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val m = Matrix().apply { setScale(scale, scale); postTranslate((w - src.width * scale) / 2, (h - src.height * scale) / 2) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) } }
        Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), px(radiusDp).toFloat(), px(radiusDp).toFloat(), p)
        src.recycle()
        return out
    }

    /** The bike: its photo, else a drawn bike in its colour on a tint of it. */
    fun bike(photo: File?, color: Int, wDp: Float, hDp: Float, radiusDp: Float): Bitmap =
        photo(photo, wDp, hDp, radiusDp) ?: bmp(wDp, hDp).also { b ->
            val c = Canvas(b)
            val r = px(radiusDp).toFloat()
            c.drawRoundRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), r, r, fill(SURFACE_RAISED))
            c.drawRoundRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), r, r, fill(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color))))
            // The outline is 120×72; fit it to 84% of the width.
            val s = minOf(b.width * 0.84f / 120f, b.height * 0.8f / 72f)
            c.save()
            c.translate((b.width - 120 * s) / 2, (b.height - 72 * s) / 2)
            c.scale(s, s)
            c.drawPath(BIKE, stroke(color, 3.4f))
            c.restore()
        }

    /** The ride's route picture (or a dark tile when it's missing). */
    fun route(file: File?, wDp: Float, hDp: Float, radiusDp: Float): Bitmap =
        photo(file, wDp, hDp, radiusDp) ?: bmp(wDp, hDp).also { b ->
            val r = px(radiusDp).toFloat()
            Canvas(b).drawRoundRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), r, r, fill(BG))
        }

    /** Page dots: the current one longer and bright. */
    fun dots(count: Int, current: Int, small: Boolean = false): Bitmap {
        val h = if (small) 5f else 6f
        val w = count * (h + 5f) + (if (small) 7f else 10f)
        val b = bmp(w, h)
        val c = Canvas(b)
        var x = 0f
        repeat(count) { k ->
            val len = if (k == current) h + (if (small) 7f else 10f) else h
            c.drawRoundRect(RectF(px(x).toFloat(), 0f, px(x + len).toFloat(), b.height.toFloat()), b.height / 2f, b.height / 2f, fill(if (k == current) TEXT else Color.argb(90, 244, 244, 245)))
            x += len + 5f
        }
        return b
    }

    /** Shots filmed out of all: one segment each. */
    fun progress(done: Int, total: Int, wDp: Float): Bitmap {
        val b = bmp(wDp, 4f)
        val c = Canvas(b)
        val gap = px(3f).toFloat()
        val seg = (b.width - gap * (total - 1)) / total.coerceAtLeast(1)
        repeat(total) { k ->
            val x = k * (seg + gap)
            c.drawRoundRect(RectF(x, 0f, x + seg, b.height.toFloat()), b.height / 2f, b.height / 2f, fill(if (k < done) BRAND else OUTLINE))
        }
        return b
    }

    /** The day as an arc, sunrise to sunset, the sun where it is now ([t] 0..1). */
    fun sunArc(t: Float, wDp: Float, hDp: Float): Bitmap {
        val b = bmp(wDp, hDp)
        val c = Canvas(b)
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val pad = px(4f).toFloat()
        val arc = Path().apply { moveTo(pad, h - pad); quadTo(w / 2, -h * 0.9f, w - pad, h - pad) }
        c.drawPath(arc, stroke(OUTLINE, 2f).apply { pathEffect = android.graphics.DashPathEffect(floatArrayOf(px(3f).toFloat(), px(4f).toFloat()), 0f) })
        c.drawLine(0f, h - pad, w, h - pad, stroke(OUTLINE, 1.5f))
        val u = t.coerceIn(0f, 1f)
        val x = (1 - u) * (1 - u) * pad + 2 * (1 - u) * u * (w / 2) + u * u * (w - pad)
        val y = (1 - u) * (1 - u) * (h - pad) + 2 * (1 - u) * u * (-h * 0.9f) + u * u * (h - pad)
        c.save()
        c.clipRect(0f, 0f, x, h)
        c.drawPath(arc, stroke(GFORCE, 2.5f))
        c.restore()
        c.drawCircle(x, y, px(5f).toFloat(), fill(GFORCE))
        return b
    }

    /** Distance bars for the last 7 days, today last. */
    fun weekBars(km: List<Double>, wDp: Float, hDp: Float): Bitmap {
        val b = bmp(wDp, hDp)
        val c = Canvas(b)
        val gap = px(5f).toFloat()
        val bw = (b.width - gap * 6) / 7
        val max = (km.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        km.forEachIndexed { k, v ->
            val x = k * (bw + gap)
            val bh = if (v > 0) max(px(6f).toFloat(), (v / max * b.height).toFloat()) else px(3f).toFloat()
            c.drawRoundRect(RectF(x, b.height - bh, x + bw, b.height.toFloat()), px(4f).toFloat(), px(4f).toFloat(), fill(if (v > 0) shadeColor(HomeWidgetData.shade(v)) else OUTLINE))
        }
        return b
    }

    /** One month as a Monday-first grid of rounded squares, [today] ringed. */
    fun month(days: Map<LocalDate, Double>, first: LocalDate, today: LocalDate, cellDp: Float, gapDp: Float): Bitmap {
        val lead = (first.dayOfWeek.value + 6) % 7
        val n = first.lengthOfMonth()
        val rows = (lead + n + 6) / 7
        val b = bmp(7 * cellDp + 6 * gapDp, rows * cellDp + (rows - 1) * gapDp)
        val c = Canvas(b)
        for (d in 1..n) {
            val date = first.withDayOfMonth(d)
            val k = lead + d - 1
            cell(c, k % 7, k / 7, cellDp, gapDp, date, days, today)
        }
        return b
    }

    /** The last 13 weeks, a column per week (Monday on top), ending this week. */
    fun weeks(days: Map<LocalDate, Double>, today: LocalDate, cellDp: Float, gapDp: Float): Bitmap {
        val start = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong()).minusWeeks(12)
        val b = bmp(13 * cellDp + 12 * gapDp, 7 * cellDp + 6 * gapDp)
        val c = Canvas(b)
        for (i in 0 until 91) cell(c, i / 7, i % 7, cellDp, gapDp, start.plusDays(i.toLong()), days, today)
        return b
    }

    /** The year: 12 small months, 4 across, the [selected] one on a teal tile. */
    fun year(days: Map<LocalDate, Double>, year: Int, today: LocalDate, selected: Int, wDp: Float, hDp: Float): Bitmap {
        val b = bmp(wDp, hDp)
        val c = Canvas(b)
        val colW = wDp / 4f
        val rowH = hDp / 3f
        val cell = minOf((colW - 16f) / 7.6f, (rowH - 22f) / 6.6f)
        val gap = cell * 0.3f
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px(10f).toFloat(); isFakeBoldText = true; letterSpacing = 0.08f }
        for (m in 0 until 12) {
            val x0 = (m % 4) * colW
            val y0 = (m / 4) * rowH
            if (m == selected) c.drawRoundRect(RectF(px(x0 + 1f).toFloat(), px(y0 + 1f).toFloat(), px(x0 + colW - 3f).toFloat(), px(y0 + rowH - 3f).toFloat()), px(10f).toFloat(), px(10f).toFloat(), fill(BRAND_CONTAINER))
            label.color = if (m == selected) BRAND else TEXT_TERTIARY
            c.drawText(MONTHS[m], px(x0 + 6f).toFloat(), px(y0 + 14f).toFloat(), label)
            val first = LocalDate.of(year, m + 1, 1)
            val lead = (first.dayOfWeek.value + 6) % 7
            for (d in 1..first.lengthOfMonth()) {
                val k = lead + d - 1
                val date = first.withDayOfMonth(d)
                val x = x0 + 6f + (k % 7) * (cell + gap)
                val y = y0 + 20f + (k / 7) * (cell + gap)
                val km = days[date] ?: 0.0
                if (date.isAfter(today)) continue
                c.drawRoundRect(RectF(px(x).toFloat(), px(y).toFloat(), px(x + cell).toFloat(), px(y + cell).toFloat()), px(1.5f).toFloat(), px(1.5f).toFloat(), fill(shadeColor(HomeWidgetData.shade(km))))
            }
        }
        return b
    }

    /** The calendar's legend: less to more. */
    fun legend(): Bitmap {
        val b = bmp(5 * 8f + 4 * 3f, 8f)
        val c = Canvas(b)
        for (k in 0..4) {
            val x = px(k * 11f).toFloat()
            c.drawRoundRect(RectF(x, 0f, x + px(8f), px(8f).toFloat()), px(2f).toFloat(), px(2f).toFloat(), fill(shadeColor(k)))
        }
        return b
    }

    /** Clip frames fanned out like cards ([files] up to 3). */
    fun fan(files: List<File?>, wDp: Float, hDp: Float): Bitmap {
        val b = bmp(wDp, hDp)
        val c = Canvas(b)
        val fw = wDp * 0.6f
        val fh = hDp * 0.84f
        files.take(3).forEachIndexed { k, f ->
            val tile = photo(f, fw, fh, 9f) ?: bmp(fw, fh).also { t -> Canvas(t).drawRoundRect(RectF(0f, 0f, t.width.toFloat(), t.height.toFloat()), px(9f).toFloat(), px(9f).toFloat(), fill(PLACEHOLDERS[k % 3])) }
            c.save()
            val cx = px(fw / 2 + k * (wDp - fw) / 2).toFloat()
            val cy = px(hDp / 2).toFloat()
            c.rotate((k - 1) * 8f, cx, cy)
            c.drawBitmap(tile, cx - tile.width / 2f, cy - tile.height / 2f, null)
            c.drawRoundRect(RectF(cx - tile.width / 2f, cy - tile.height / 2f, cx + tile.width / 2f, cy + tile.height / 2f), px(9f).toFloat(), px(9f).toFloat(), stroke(SURFACE, 2f))
            c.restore()
            tile.recycle()
        }
        return b
    }

    private fun cell(c: Canvas, col: Int, row: Int, cellDp: Float, gapDp: Float, date: LocalDate, days: Map<LocalDate, Double>, today: LocalDate) {
        if (date.isAfter(today)) return
        val x = col * (cellDp + gapDp)
        val y = row * (cellDp + gapDp)
        val r = RectF(px(x).toFloat(), px(y).toFloat(), px(x + cellDp).toFloat(), px(y + cellDp).toFloat())
        val rad = px(cellDp * 0.26f).toFloat()
        c.drawRoundRect(r, rad, rad, fill(shadeColor(HomeWidgetData.shade(days[date] ?: 0.0))))
        if (date == today) {
            val s = stroke(TEXT, 2f)
            r.inset(-s.strokeWidth / 2, -s.strokeWidth / 2)
            c.drawRoundRect(r, rad, rad, s)
        }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private fun stroke(color: Int, wDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = wDp * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    companion object {
        const val BG = 0xFF0A0A0B.toInt()
        const val SURFACE = 0xFF141416.toInt()
        const val SURFACE_RAISED = 0xFF1C1C1F.toInt()
        const val OUTLINE = 0xFF2A2A2E.toInt()
        const val TEXT = 0xFFF4F4F5.toInt()
        const val TEXT_TERTIARY = 0xFF85858E.toInt()
        const val BRAND = 0xFF69C8CB.toInt()
        const val BRAND_CONTAINER = 0xFF1E3E40.toInt()
        const val GFORCE = 0xFFFBBF24.toInt()
        private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val PLACEHOLDERS = listOf(0xFF2C4A4C.toInt(), 0xFF4A3A1E.toInt(), 0xFF3B2638.toInt())

        /** Calendar shades: none, then teal at 30%, 55%, 80% and full. */
        fun shadeColor(level: Int): Int = when (level) {
            0 -> SURFACE_RAISED
            1 -> Color.argb(77, 105, 200, 203)
            2 -> Color.argb(140, 105, 200, 203)
            3 -> Color.argb(204, 105, 200, 203)
            else -> BRAND
        }

        /** A bike in side view on a 120×72 grid: two wheels, the frame, tank, seat and bars. */
        private val BIKE = Path().apply {
            addCircle(28f, 52f, 15f, Path.Direction.CW)
            addCircle(94f, 52f, 15f, Path.Direction.CW)
            moveTo(28f, 52f); lineTo(48f, 32f); lineTo(72f, 32f); lineTo(94f, 52f)
            moveTo(48f, 32f); lineTo(58f, 50f); lineTo(72f, 32f)
            moveTo(50f, 31f); cubicTo(53f, 23f, 67f, 22f, 72f, 28f)
            moveTo(33f, 31f); lineTo(49f, 31f)
            moveTo(72f, 32f); lineTo(80f, 18f); lineTo(88f, 16f)
        }
    }
}
