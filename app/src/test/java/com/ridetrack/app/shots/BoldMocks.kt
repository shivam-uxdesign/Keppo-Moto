package com.ridetrack.app.shots

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.ridetrack.app.R
import com.ridetrack.app.studio.Tok
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Throwaway mockups of four directions for the Bold vibe (not the app's code). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class BoldMocks {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val w = 1080
    private val h = 1920
    private val W = w.toFloat()
    private val H = h.toFloat()
    private val s = 2f
    private val dir = System.getProperty("bold.fonts") ?: "/tmp/claude-0/-home-user-Ride-Track/7976f56a-3884-5d9a-9844-1e21c5779ff7/scratchpad/boldfonts"
    // The candidate fonts aren't in the app: the mockups only draw when they've been downloaded there.
    private val have = File("$dir/archivo_black.ttf").isFile
    private val archivo by lazy { Typeface.createFromFile("$dir/archivo_black.ttf") }
    private val barlow by lazy { Typeface.createFromFile("$dir/barlow_cond_black_italic.ttf") }
    private val inter by lazy { Typeface.createFromFile("$dir/inter_tight_black.ttf") }
    private val anton = ResourcesCompat.getFont(ctx, R.font.anton)!!
    private val geistMed = ResourcesCompat.getFont(ctx, R.font.geist_medium)!!
    private val out = File("build/boldmocks")
    private val Y = Tok.YELLOW
    private val INK = Tok.INK

    private val scene: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, Color.rgb(70, 110, 160), Color.rgb(235, 170, 120), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H * 0.55f, p); p.shader = null
        p.color = Color.rgb(60, 80, 70); c.drawPath(Path().apply { moveTo(0f, H * 0.55f); lineTo(W * 0.3f, H * 0.45f); lineTo(W * 0.6f, H * 0.52f); lineTo(W * 0.85f, H * 0.44f); lineTo(W, H * 0.5f); lineTo(W, H * 0.56f); lineTo(0f, H * 0.56f); close() }, p)
        p.color = Color.rgb(90, 110, 80); c.drawRect(0f, H * 0.55f, W, H, p)
        p.color = Color.rgb(55, 55, 60); c.drawPath(Path().apply { moveTo(W * 0.46f, H * 0.55f); lineTo(W * 0.54f, H * 0.55f); lineTo(W * 1.05f, H); lineTo(-W * 0.05f, H); close() }, p)
        p.color = Color.rgb(240, 230, 200)
        for (i in 0 until 8) { val y0 = H * (0.58f + i * 0.055f); val y1 = y0 + H * 0.03f * (1 + i * 0.2f); c.drawRect(W * 0.497f - i * 3f, y0, W * 0.503f + i * 3f, y1, p) }
        p.color = Color.rgb(30, 120, 70); c.drawRect(W * 0.78f, H * 0.38f, W * 0.95f, H * 0.46f, p)
        p.color = Color.WHITE; p.textSize = 34f; c.drawText("NH 48", W * 0.8f, H * 0.425f, p)
    }

    private fun shot(name: String, zoom: Float = 1f, ground: Int? = null, draw: (Canvas) -> Unit) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (ground != null) c.drawColor(ground) else {
            c.save(); c.scale(zoom, zoom, W / 2, H / 2); c.drawBitmap(scene, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
            // the vibe's vignette
            c.drawRect(0f, 0f, W, H, Paint().apply { shader = android.graphics.RadialGradient(W / 2, H / 2, H * 0.78f, intArrayOf(0, 0, Color.argb(64, 0, 0, 0)), floatArrayOf(0f, 0.32f, 1f), Shader.TileMode.CLAMP) })
        }
        draw(c)
        val f = File(out, "$name.png").apply { parentFile?.mkdirs() }
        f.outputStream().use { Bitmap.createScaledBitmap(b, w / 2, h / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun paint(face: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, track: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size * s; this.color = color; textAlign = align; letterSpacing = track }

    private fun Paint.soft() = apply { setShadowLayer(12 * s, 0f, 2 * s, Color.argb(115, 0, 0, 0)) }
    private fun mid(p: Paint, y: Float) = y - (p.ascent() + p.descent()) / 2
    private fun a(c: Int, f: Float) = Tok.alpha(c, f)

    private fun label(c: Canvas, t: String, x: Float, y: Float, color: Int = Color.WHITE, align: Paint.Align = Paint.Align.LEFT) {
        val p = paint(geistMed, Tok.LABEL, color, align, Tok.TRACK_CAPS).soft()
        c.drawText(t.uppercase(), x, mid(p, y), p)
    }

    @Test
    fun mocks() {
        org.junit.Assume.assumeTrue("Bold mockup fonts not downloaded", have)
        poster(); broadcast(); brutal(); kinetic()
    }

    // ---- A. Poster ---------------------------------------------------------------------------

    private fun poster() {
        val x0 = W * Tok.INFO_X
        fun badge(c: Canvas) {
            val p = paint(archivo, Tok.TITLE, Color.WHITE).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * s }
            c.drawText("62", x0, H * 0.155f, p)
            val u = paint(geistMed, Tok.LABEL, Y, track = Tok.TRACK_CAPS).soft()
            c.drawText("KM/H", x0 + p.measureText("62") + 8 * s, H * 0.155f, u)
        }
        shot("a-poster/caption") { c ->
            badge(c)
            val p = paint(archivo, Tok.HERO, Color.WHITE, Paint.Align.CENTER, -0.02f).soft()
            c.drawText("EKDUM", W / 2, mid(p, H * 0.66f - Tok.HERO * s * 1.0f), p)
            p.color = Y; c.drawText("MAST", W / 2, mid(p, H * 0.66f), p)
        }
        shot("a-poster/opening") { c ->
            badge(c)
            label(c, "Commute Diaries · Ep 4", x0, H * 0.2f)
            val p = paint(archivo, Tok.HERO, Color.WHITE, track = -0.02f).soft()
            c.drawText("3 HOURS.", x0, mid(p, H * 0.255f), p)
            val q = paint(archivo, Tok.CAP_L, Y, track = -0.01f).soft()
            c.drawText("NO WATER.", x0 + 4 * s, mid(q, H * 0.315f), q)
        }
        // The section change: the camera punches in, then one frame of yellow at the cut.
        shot("a-poster/section-45", zoom = 1.35f) { c -> c.drawColor(a(Y, 0.15f)) }
        shot("a-poster/section-50") { c -> c.drawColor(Y) }
        shot("a-poster/stats", ground = INK) { c ->
            label(c, "Top speed", W / 2, H * 0.3f, Color.WHITE, Paint.Align.CENTER)
            val p = paint(archivo, 230f, Color.WHITE, Paint.Align.CENTER, -0.04f)
            c.drawText("92", W / 2, mid(p, H * 0.44f), p)
            val u = paint(archivo, Tok.CAP, Y, Paint.Align.CENTER)
            c.drawText("KM/H", W / 2, mid(u, H * 0.56f), u)
            label(c, "1 / 4", W / 2, H * 0.74f, a(Color.WHITE, 0.6f), Paint.Align.CENTER)
        }
    }

    // ---- B. Sport broadcast ------------------------------------------------------------------

    private fun bar(c: Canvas, left: Float, cy: Float, w: Float, hh: Float, fill: Int, stripe: Boolean = true) {
        val skew = hh * 0.25f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        c.drawPath(Path().apply { moveTo(left + skew, cy - hh / 2); lineTo(left + w + skew, cy - hh / 2); lineTo(left + w - skew, cy + hh / 2); lineTo(left - skew, cy + hh / 2); close() }, p)
        if (stripe) {
            p.color = Y
            val sw = 7 * s
            c.drawPath(Path().apply { moveTo(left + skew - sw - 4 * s, cy - hh / 2); lineTo(left + skew - 4 * s, cy - hh / 2); lineTo(left - skew - 4 * s, cy + hh / 2); lineTo(left - skew - sw - 4 * s, cy + hh / 2); close() }, p)
        }
    }

    private fun broadcast() {
        val x0 = W * Tok.INFO_X
        fun badge(c: Canvas) {
            bar(c, x0 + 10 * s, H * 0.14f, 150 * s, 64 * s, a(INK, 0.85f))
            val n = paint(barlow, Tok.CAP_L, Color.WHITE)
            c.drawText("62", x0 + 26 * s, mid(n, H * 0.14f), n)
            val u = paint(barlow, Tok.LABEL_L, Y)
            c.drawText("KM/H", x0 + 26 * s + n.measureText("62") + 6 * s, mid(n, H * 0.14f), u)
            // lean bar
            val lp = Paint().apply { color = a(Color.WHITE, 0.3f) }
            c.drawRect(x0 + 10 * s, H * 0.14f + 40 * s, x0 + 150 * s, H * 0.14f + 44 * s, lp)
            lp.color = Y; c.drawRect(x0 + 80 * s, H * 0.14f + 40 * s, x0 + 112 * s, H * 0.14f + 44 * s, lp)
        }
        shot("b-broadcast/caption") { c ->
            badge(c)
            val p = paint(barlow, Tok.CAP_L, Color.WHITE)
            val words = listOf("YE", "ROAD", "EKDUM", "MAST")
            val rows = listOf(words.take(2), words.drop(2))
            rows.forEachIndexed { i, r ->
                val cy = H * 0.66f - (1 - i) * 66 * s
                val tw = p.measureText(r.joinToString(" "))
                bar(c, x0 + 14 * s, cy, tw + 40 * s, 60 * s, a(INK, 0.88f), stripe = i == 0)
                var x = x0 + 30 * s
                r.forEach { word -> p.color = if (word == "MAST") Y else Color.WHITE; c.drawText(word, x, mid(p, cy), p); x += p.measureText("$word ") }
            }
        }
        shot("b-broadcast/opening") { c ->
            badge(c)
            val tag = paint(barlow, Tok.LABEL_L, INK)
            bar(c, x0 + 14 * s, H * 0.215f, tag.measureText("COMMUTE DIARIES · EP 4") + 30 * s, 34 * s, Y, stripe = false)
            c.drawText("COMMUTE DIARIES · EP 4", x0 + 26 * s, mid(tag, H * 0.215f), tag)
            val p = paint(barlow, Tok.CAP_L, Color.WHITE)
            bar(c, x0 + 14 * s, H * 0.265f, p.measureText("3 HOURS. NO WATER.") + 40 * s, 70 * s, a(INK, 0.9f))
            c.drawText("3 HOURS. NO WATER.", x0 + 30 * s, mid(p, H * 0.265f), p)
        }
        // The replay sting: slanted bars sweeping across.
        shot("b-broadcast/section-45") { c ->
            listOf(0.1f to INK, 0.32f to Y, 0.5f to INK).forEach { (o, col) -> bar(c, W * (0.05f + o), H / 2, W * 0.28f, H * 1.2f, col, stripe = false) }
        }
        shot("b-broadcast/section-50") { c ->
            bar(c, -W * 0.3f, H / 2, W * 1.6f, H * 1.2f, INK, stripe = false)
            bar(c, W * 0.62f, H / 2, W * 0.06f, H * 1.2f, Y, stripe = false)
        }
        shot("b-broadcast/stats", ground = INK) { c ->
            val t = paint(barlow, Tok.CAP_L, Color.WHITE)
            c.drawText("PALAM TO SECTOR 62", x0, mid(t, H * 0.2f), t)
            label(c, "Mon 13 Oct · Ride report", x0, H * 0.25f, Y)
            listOf("DISTANCE" to "29.8 KM", "TOP SPEED" to "92 KM/H", "RIDE TIME" to "44 MIN", "MOMENTS" to "12").forEachIndexed { i, (k, v) ->
                val cy = H * (0.36f + i * 0.085f)
                bar(c, x0 + 10 * s, cy, W * 0.8f, 64 * s, if (i % 2 == 0) Color.rgb(28, 28, 32) else Color.rgb(20, 20, 24), stripe = false)
                val n = paint(barlow, Tok.BODY, Y); c.drawText("${i + 1}", x0 + 28 * s, mid(n, cy), n)
                val kp = paint(barlow, Tok.SUB, a(Color.WHITE, 0.7f)); c.drawText(k, x0 + 70 * s, mid(kp, cy), kp)
                val vp = paint(barlow, Tok.BODY, Color.WHITE, Paint.Align.RIGHT); c.drawText(v, x0 + W * 0.8f - 10 * s, mid(vp, cy), vp)
            }
        }
    }

    // ---- C. Brutal mono ----------------------------------------------------------------------

    private fun block(c: Canvas, text: String, x: Float, cy: Float, p: Paint, fill: Int, padX: Float = 12 * s, padY: Float = 6 * s): Float {
        val tw = p.measureText(text)
        val th = p.descent() - p.ascent()
        c.drawRect(x, cy - th / 2 - padY, x + tw + 2 * padX, cy + th / 2 + padY, Paint().apply { color = fill })
        c.drawText(text, x + padX, mid(p, cy), p)
        return tw + 2 * padX
    }

    private fun brutal() {
        val x0 = W * Tok.INFO_X
        fun badge(c: Canvas) = block(c, "62 KM/H", x0, H * 0.14f, paint(inter, Tok.SUB, Color.WHITE), INK)
        shot("c-brutal/caption") { c ->
            badge(c)
            val p = paint(inter, Tok.CAP_L, Color.WHITE, track = -0.02f)
            val y1 = H * 0.66f - 82 * s
            val wa = block(c, "YE ROAD", W / 2 - p.measureText("YE ROAD") / 2 - 12 * s, y1, p, INK)
            val r2 = "EKDUM MAST"
            var x = W / 2 - p.measureText(r2) / 2 - 12 * s
            x += block(c, "EKDUM", x, H * 0.66f, p, INK)
            p.color = INK
            block(c, "MAST", x, H * 0.66f, p, Y)
            check(wa > 0)
        }
        shot("c-brutal/opening") { c ->
            badge(c)
            val l = paint(inter, Tok.LABEL_L, INK, track = 0.05f)
            block(c, "COMMUTE DIARIES — EP 4", x0, H * 0.205f, l, Y, 10 * s, 4 * s)
            val p = paint(inter, Tok.TITLE, Color.WHITE, track = -0.03f)
            block(c, "3 HOURS.", x0, H * 0.26f, p, INK)
            block(c, "NO WATER.", x0, H * 0.26f + 92 * s, p, INK)
        }
        shot("c-brutal/section-45") { c -> c.drawColor(Y) }
        shot("c-brutal/section-50", ground = Y) { c ->
            val p = paint(inter, 150f, INK, track = -0.05f)
            c.drawText("PART", x0, mid(p, H * 0.42f), p)
            c.drawText("TWO", x0, mid(p, H * 0.42f + 150 * s), p)
            label(c, "The flyover", x0, H * 0.7f, INK)
        }
        shot("c-brutal/stats", ground = Color.WHITE) { c ->
            val p = paint(inter, Tok.CAP_L, INK, track = -0.03f)
            c.drawText("PALAM →", x0, mid(p, H * 0.18f), p)
            c.drawText("SECTOR 62", x0, mid(p, H * 0.18f + 60 * s), p)
            val line = Paint().apply { color = INK }
            listOf("29.8" to "KM", "92" to "TOP KM/H", "44" to "MIN", "12" to "MOMENTS").forEachIndexed { i, (v, k) ->
                val top = H * (0.32f + i * 0.12f)
                c.drawRect(x0, top, W - x0, top + 4 * s, line)
                val vp = paint(inter, Tok.HERO, INK, track = -0.04f); c.drawText(v, x0, top + 98 * s, vp)
                val kp = paint(inter, Tok.LABEL_L, INK, Paint.Align.RIGHT); c.drawText(k, W - x0, top + 30 * s, kp)
            }
            block(c, "MADE WITH KEPPO", x0, H * 0.82f, paint(inter, Tok.LABEL, INK), Y, 8 * s, 4 * s)
        }
    }

    // ---- D. Kinetic --------------------------------------------------------------------------

    /** Text with a motion trail below it (it's arriving from below). */
    private fun slam(c: Canvas, text: String, x: Float, y: Float, p: Paint, trail: Float) {
        val color = p.color
        for (k in 5 downTo 1) {
            p.color = a(color, 0.12f * (6 - k) / 5f)
            p.maskFilter = BlurMaskFilter(4f * s, BlurMaskFilter.Blur.NORMAL)
            c.drawText(text, x, y + k * trail, p)
        }
        p.maskFilter = null
        p.color = color
        c.drawText(text, x, y, p)
    }

    private fun streaks(c: Canvas, alpha: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        val r = java.util.Random(4)
        repeat(40) {
            val ang = r.nextFloat() * Math.PI.toFloat() * 2
            val r0 = W * (0.25f + r.nextFloat() * 0.2f)
            val r1 = r0 + W * (0.2f + r.nextFloat() * 0.4f)
            p.strokeWidth = (2 + r.nextFloat() * 4) * s
            p.color = a(Color.WHITE, alpha * (0.3f + r.nextFloat() * 0.5f))
            val cx = W / 2; val cy = H / 2
            c.drawLine(cx + r0 * kotlin.math.cos(ang), cy + r0 * kotlin.math.sin(ang), cx + r1 * kotlin.math.cos(ang), cy + r1 * kotlin.math.sin(ang), p)
        }
    }

    private fun kinetic() {
        val x0 = W * Tok.INFO_X
        fun badge(c: Canvas) {
            val p = paint(anton, Tok.CAP_L, Color.WHITE).soft()
            c.drawText("62", x0, mid(p, H * 0.14f), p)
            val u = paint(anton, Tok.LABEL_L, Y)
            c.drawText("KM/H", x0 + p.measureText("62") + 6 * s, mid(p, H * 0.14f), u)
            val sp = Paint().apply { color = a(Color.WHITE, 0.5f); strokeWidth = 2 * s }
            for (i in 0 until 3) c.drawLine(x0 - 4 * s - (i * 6) * s, H * 0.13f + i * 8 * s, x0 - 24 * s - i * 6 * s, H * 0.13f + i * 8 * s, sp)
        }
        shot("d-kinetic/caption", zoom = 1.06f) { c ->
            badge(c)
            val p = paint(anton, Tok.TITLE, Color.WHITE, Paint.Align.CENTER).soft()
            c.drawText("EKDUM", W / 2, mid(p, H * 0.66f - 80 * s), p)
            c.save(); c.translate(W / 2, H * 0.66f); c.rotate(-3f); c.scale(1.15f, 1.15f)
            val q = paint(anton, Tok.TITLE, Y, Paint.Align.CENTER)
            slam(c, "MAST", 0f, mid(q, 0f), q, 14 * s)
            c.restore()
        }
        shot("d-kinetic/opening", zoom = 1.04f) { c ->
            badge(c)
            label(c, "Commute Diaries · Ep 4", W / 2, H * 0.2f, Color.WHITE, Paint.Align.CENTER)
            val p = paint(anton, Tok.HERO, Color.WHITE, Paint.Align.CENTER)
            slam(c, "3 HOURS.", W / 2, mid(p, H * 0.26f), p, 18 * s)
            val q = paint(anton, Tok.CAP_L, Y, Paint.Align.CENTER).soft()
            c.drawText("NO WATER.", W / 2, mid(q, H * 0.33f), q)
        }
        shot("d-kinetic/section-45", zoom = 1.25f) { c -> streaks(c, 0.6f) }
        shot("d-kinetic/section-50", zoom = 1.5f) { c -> streaks(c, 1f); c.drawColor(a(Color.WHITE, 0.35f)) }
        shot("d-kinetic/stats", ground = INK) { c ->
            val t = paint(anton, Tok.CAP_L, Color.WHITE, Paint.Align.CENTER)
            c.drawText("PALAM TO SECTOR 62", W / 2, mid(t, H * 0.2f), t)
            listOf("29.8" to "KM", "92" to "TOP KM/H", "44" to "MINUTES", "12" to "MOMENTS").forEachIndexed { i, (v, k) ->
                val cx = W * (if (i % 2 == 1) 0.73f else 0.27f)
                val cy = H * (if (i < 2) 0.4f else 0.58f)
                val p = paint(anton, Tok.HERO, if (i == 1) Y else Color.WHITE, Paint.Align.CENTER)
                if (i == 1) slam(c, v, cx, mid(p, cy), p, 12 * s) else c.drawText(v, cx, mid(p, cy), p)
                label(c, k, cx, cy + 66 * s, a(Color.WHITE, 0.7f), Paint.Align.CENTER)
            }
        }
    }
}
