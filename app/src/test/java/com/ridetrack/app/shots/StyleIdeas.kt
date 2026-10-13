package com.ridetrack.app.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Throwaway mockups: three bike-themed style ideas, ride filters, and Bold in other colours (not the app's code). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class StyleIdeas {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val w = 1080
    private val h = 1920
    private val W = w.toFloat()
    private val H = h.toFloat()
    private val s = 2f
    private val dir = System.getProperty("bold.fonts") ?: "/tmp/claude-0/-home-user-Ride-Track/7976f56a-3884-5d9a-9844-1e21c5779ff7/scratchpad/boldfonts"
    private fun f(n: String) = Typeface.createFromFile("$dir/$n.ttf")
    private val have = File("$dir/orbitron_black.ttf").isFile
    private val orbitron by lazy { f("orbitron_black") }
    private val chakra by lazy { f("chakra_petch_semibold") }
    private val teko by lazy { f("teko_semibold") }
    private val racing by lazy { f("racing_sans_one") }
    private val techMono by lazy { f("share_tech_mono") }
    private val archivo by lazy { f("archivo_black") }
    private val geistMed by lazy { ResourcesCompat.getFont(ctx, R.font.geist_medium)!! }
    private val geist by lazy { ResourcesCompat.getFont(ctx, R.font.geist_semibold)!! }
    private val out = File("build/styleideas")
    private val RED = Color.rgb(230, 30, 36)
    private val INK = Tok.INK

    /** The road at dusk, with a rider in red ahead. */
    private val scene: Bitmap by lazy {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, H * 0.55f, Color.rgb(70, 110, 160), Color.rgb(235, 170, 120), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H * 0.55f, p); p.shader = null
            p.color = Color.rgb(60, 80, 70); c.drawPath(Path().apply { moveTo(0f, H * 0.55f); lineTo(W * 0.3f, H * 0.45f); lineTo(W * 0.6f, H * 0.52f); lineTo(W * 0.85f, H * 0.44f); lineTo(W, H * 0.5f); lineTo(W, H * 0.56f); lineTo(0f, H * 0.56f); close() }, p)
            p.color = Color.rgb(90, 110, 80); c.drawRect(0f, H * 0.55f, W, H, p)
            p.color = Color.rgb(55, 55, 60); c.drawPath(Path().apply { moveTo(W * 0.46f, H * 0.55f); lineTo(W * 0.54f, H * 0.55f); lineTo(W * 1.05f, H); lineTo(-W * 0.05f, H); close() }, p)
            p.color = Color.rgb(240, 230, 200)
            for (i in 0 until 8) { val y0 = H * (0.58f + i * 0.055f); val y1 = y0 + H * 0.03f * (1 + i * 0.2f); c.drawRect(W * 0.497f - i * 3f, y0, W * 0.503f + i * 3f, y1, p) }
            p.color = Color.rgb(30, 120, 70); c.drawRect(W * 0.78f, H * 0.38f, W * 0.95f, H * 0.46f, p)
            p.color = Color.WHITE; p.textSize = 34f; c.drawText("NH 48", W * 0.8f, H * 0.425f, p)
            // A rider ahead, in a red jacket on a red bike.
            val bx = W * 0.56f; val by = H * 0.6f
            p.color = Color.rgb(20, 20, 22); c.drawCircle(bx - 34f, by + 40f, 22f, p); c.drawCircle(bx + 34f, by + 40f, 22f, p)
            p.color = Color.rgb(200, 25, 30); c.drawRoundRect(RectF(bx - 46f, by, bx + 46f, by + 34f), 10f, 10f, p)
            p.color = Color.rgb(215, 35, 40); c.drawRoundRect(RectF(bx - 20f, by - 70f, bx + 18f, by + 4f), 14f, 14f, p)
            p.color = Color.rgb(25, 25, 28); c.drawCircle(bx, by - 86f, 20f, p)
        }
    }

    private fun shot(name: String, zoom: Float = 1f, ground: Int? = null, filter: ColorMatrix? = null, src: Bitmap? = null, draw: (Canvas) -> Unit = {}) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (ground != null) c.drawColor(ground) else {
            c.save(); c.scale(zoom, zoom, W / 2, H / 2)
            c.drawBitmap(src ?: scene, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { filter?.let { colorFilter = ColorMatrixColorFilter(it) } })
            c.restore()
        }
        draw(c)
        val file = File(out, "$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { Bitmap.createScaledBitmap(b, w / 2, h / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun paint(face: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, track: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size * s; this.color = color; textAlign = align; letterSpacing = track }
    private fun Paint.soft() = apply { setShadowLayer(12 * s, 0f, 2 * s, Color.argb(115, 0, 0, 0)) }
    private fun mid(p: Paint, y: Float) = y - (p.ascent() + p.descent()) / 2
    private fun a(c: Int, f: Float) = Tok.alpha(c, f)
    private val x0 get() = W * Tok.INFO_X

    @Test
    fun ideas() {
        Assume.assumeTrue("mockup fonts not downloaded", have)
        redline(); visor(); livery(); filters(); boldColours()
        // A plain frame, for the redline effect mockup (drawn over it frame by frame).
        shot("speedtrap/base")
    }

    // ---- 1. Redline: the bike's own dashboard -------------------------------------------------

    /** A dash panel: chamfered corners, a red line on top. */
    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float, fill: Int = a(INK, 0.82f), edge: Int = RED) {
        val k = 10 * s
        val path = Path().apply { moveTo(l + k, t); lineTo(r - k, t); lineTo(r, t + k); lineTo(r, b - k); lineTo(r - k, b); lineTo(l + k, b); lineTo(l, b - k); lineTo(l, t + k); close() }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        c.drawRect(l + k, t, r - k, t + 3 * s, Paint().apply { color = edge })
    }

    private fun revBar(c: Canvas, l: Float, t: Float, lit: Int, n: Int = 14) {
        val segW = 9 * s; val gap = 3 * s
        for (i in 0 until n) {
            val hgt = (8 + i * 1.2f) * s
            val col = when { i >= lit -> a(Color.WHITE, 0.15f); i >= n - 3 -> RED; else -> Color.WHITE }
            c.drawRect(l + i * (segW + gap), t + 26 * s - hgt, l + i * (segW + gap) + segW, t + 26 * s, Paint().apply { color = col })
        }
    }

    private fun redline() {
        fun badge(c: Canvas) {
            panel(c, x0, H * 0.115f, x0 + 210 * s, H * 0.175f)
            revBar(c, x0 + 12 * s, H * 0.12f, 9)
            val n = paint(orbitron, Tok.CAP, Color.WHITE)
            c.drawText("62", x0 + 12 * s, H * 0.168f, n)
            val u = paint(orbitron, Tok.LABEL, a(Color.WHITE, 0.7f)); c.drawText("KM/H", x0 + 12 * s + n.measureText("62") + 6 * s, H * 0.168f, u)
            // gear
            c.drawRect(x0 + 170 * s, H * 0.135f, x0 + 200 * s, H * 0.168f, Paint().apply { color = RED })
            val g = paint(orbitron, Tok.BODY, Color.WHITE, Paint.Align.CENTER); c.drawText("4", x0 + 185 * s, mid(g, H * 0.1515f), g)
        }
        shot("1-redline/caption") { c ->
            badge(c)
            val p = paint(orbitron, Tok.BODY, Color.WHITE, Paint.Align.LEFT, 0.04f)
            val l1 = "BHAI YE ROAD"; val l2a = "EKDUM "; val l2b = "MAST"
            val tw = maxOf(p.measureText(l1), p.measureText(l2a + l2b))
            val l = W / 2 - tw / 2 - 18 * s
            panel(c, l, H * 0.66f - 70 * s, W / 2 + tw / 2 + 18 * s, H * 0.66f + 28 * s)
            c.drawText(l1, l + 18 * s, mid(p, H * 0.66f - 34 * s), p)
            c.drawText(l2a, l + 18 * s, mid(p, H * 0.66f + 4 * s), p)
            p.color = RED; c.drawText(l2b, l + 18 * s + p.measureText(l2a), mid(p, H * 0.66f + 4 * s), p)
        }
        shot("1-redline/opening") { c ->
            badge(c)
            val tag = paint(orbitron, Tok.LABEL, Color.WHITE, track = 0.15f)
            panel(c, x0, H * 0.195f, x0 + tag.measureText("COMMUTE DIARIES · EP 4") + 24 * s, H * 0.215f, RED, RED)
            c.drawText("COMMUTE DIARIES · EP 4", x0 + 12 * s, mid(tag, H * 0.205f), tag)
            val p = paint(orbitron, Tok.CAP_L, Color.WHITE, track = 0.02f).soft()
            c.drawText("3 HOURS.", x0, mid(p, H * 0.255f), p)
            c.drawText("NO WATER.", x0, mid(p, H * 0.255f + 64 * s), p)
        }
        // The section change: the tach sweeps to the redline over a darkened frame.
        shot("1-redline/section-45") { c -> c.drawColor(a(INK, 0.6f)); tach(c, 0.75f) }
        shot("1-redline/section-50") { c -> c.drawColor(a(INK, 0.85f)); tach(c, 1f); c.drawColor(a(RED, 0.25f)) }
        shot("1-redline/stats", ground = INK) { c ->
            val t = paint(orbitron, Tok.SUB, a(Color.WHITE, 0.6f), track = 0.2f); c.drawText("TRIP A", x0, H * 0.16f, t)
            val title = paint(orbitron, Tok.BODY, Color.WHITE); c.drawText("PALAM → SECTOR 62", x0, H * 0.2f, title)
            tach(c, 0.92f, cy = H * 0.42f, r = W * 0.36f)
            val big = paint(orbitron, Tok.HERO, Color.WHITE, Paint.Align.CENTER); c.drawText("92", W / 2, mid(big, H * 0.43f), big)
            val u = paint(orbitron, Tok.LABEL_L, RED, Paint.Align.CENTER, 0.2f); c.drawText("TOP KM/H", W / 2, H * 0.49f, u)
            listOf("29.8" to "KM", "44" to "MIN", "12" to "CLIPS").forEachIndexed { i, (v, k) ->
                val l = x0 + i * (W * 0.88f / 3); panel(c, l + 4 * s, H * 0.6f, l + W * 0.88f / 3 - 4 * s, H * 0.68f)
                val vp = paint(orbitron, Tok.CAP, Color.WHITE); c.drawText(v, l + 16 * s, H * 0.645f, vp)
                val kp = paint(orbitron, Tok.LABEL, a(Color.WHITE, 0.6f), track = 0.2f); c.drawText(k, l + 16 * s, H * 0.667f, kp)
            }
        }
    }

    /** A rev counter arc, lit to [q] of the way (the last fifth red). */
    private fun tach(c: Canvas, q: Float, cy: Float = H * 0.45f, r: Float = W * 0.38f) {
        val cx = W / 2
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
        val n = 40
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val ang = Math.toRadians((150 + 240 * f).toDouble())
            val long = i % 5 == 0
            val r0 = r - (if (long) 26 else 14) * s
            p.strokeWidth = (if (long) 5 else 3) * s
            p.color = when { f > q -> a(Color.WHITE, 0.15f); f > 0.8f -> RED; else -> Color.WHITE }
            c.drawLine(cx + r0 * cos(ang).toFloat(), cy + r0 * sin(ang).toFloat(), cx + r * cos(ang).toFloat(), cy + r * sin(ang).toFloat(), p)
        }
        val na = Math.toRadians((150 + 240 * q).toDouble())
        p.color = RED; p.strokeWidth = 4 * s; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy, cx + (r - 30 * s) * cos(na).toFloat(), cy + (r - 30 * s) * sin(na).toFloat(), p)
        c.drawCircle(cx, cy, 8 * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED })
    }

    // ---- 2. Visor: a fighter-jet HUD in red, driven by the ride's numbers -----------------------

    private val hud = Color.rgb(255, 59, 48)

    private fun hudPaint(width: Float = 2f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hud; style = Paint.Style.STROKE; strokeWidth = width * s; setShadowLayer(6 * s, 0f, 0f, a(hud, 0.6f)) }

    private fun hudFrame(c: Canvas, lean: Float = 24f) {
        val p = hudPaint()
        val t = paint(chakra, Tok.LABEL_L, hud).apply { setShadowLayer(6 * s, 0f, 0f, a(hud, 0.6f)) }
        // speed tape, left
        val tx = x0; val ty = H * 0.3f
        c.drawLine(tx + 50 * s, ty - 90 * s, tx + 50 * s, ty + 90 * s, p)
        for (k in -4..4) { val y = ty + k * 20 * s; c.drawLine(tx + 50 * s, y, tx + (if (k % 2 == 0) 38 else 44) * s, y, p) }
        c.drawRect(tx, ty - 16 * s, tx + 46 * s, ty + 16 * s, Paint().apply { color = a(INK, 0.5f) }); c.drawRect(tx, ty - 16 * s, tx + 46 * s, ty + 16 * s, p)
        c.drawText("62", tx + 6 * s, mid(t, ty), t)
        val small = paint(chakra, Tok.LABEL, hud); c.drawText("KM/H", tx, ty + 34 * s, small)
        // heading tape, top
        val hy = H * 0.145f
        c.drawLine(W * 0.3f, hy + 14 * s, W * 0.7f, hy + 14 * s, p)
        listOf("330", "340", "N", "010", "020").forEachIndexed { i, l -> val x = W * (0.32f + i * 0.09f); c.drawText(l, x - t.measureText(l) / 2, hy, t); c.drawLine(x, hy + 8 * s, x, hy + 14 * s, p) }
        c.drawPath(Path().apply { moveTo(W / 2, hy + 16 * s); lineTo(W / 2 - 6 * s, hy + 26 * s); lineTo(W / 2 + 6 * s, hy + 26 * s); close() }, p)
        // horizon and lean, centre
        c.save(); c.rotate(-lean, W / 2, H * 0.45f)
        c.drawLine(W * 0.22f, H * 0.45f, W * 0.42f, H * 0.45f, p); c.drawLine(W * 0.58f, H * 0.45f, W * 0.78f, H * 0.45f, p)
        c.drawLine(W * 0.22f, H * 0.45f, W * 0.22f, H * 0.45f + 10 * s, p); c.drawLine(W * 0.78f, H * 0.45f, W * 0.78f, H * 0.45f + 10 * s, p)
        c.restore()
        c.drawCircle(W / 2, H * 0.45f, 6 * s, p)
        c.drawText("LEAN ${lean.toInt()}°", W / 2 - t.measureText("LEAN 24°") / 2, H * 0.45f + 56 * s, t)
    }

    private fun brackets(c: Canvas, r: RectF, len: Float = 14 * s) {
        val p = hudPaint(2.5f)
        listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).forEach { (x, y) ->
            val dx = if (x == r.left) len else -len; val dy = if (y == r.top) len else -len
            c.drawLine(x, y, x + dx, y, p); c.drawLine(x, y, x, y + dy, p)
        }
    }

    private fun visor() {
        shot("2-visor/caption") { c ->
            hudFrame(c)
            val p = paint(chakra, Tok.CAP, Color.WHITE, Paint.Align.LEFT, 0.04f).soft()
            val text = listOf("BHAI", "YE", "ROAD", "EKDUM", "MAST")
            val rows = listOf(text.take(3), text.drop(3))
            rows.forEachIndexed { ri, r ->
                val y = H * 0.66f - (1 - ri) * 54 * s
                val tw = p.measureText(r.joinToString(" "))
                var x = W / 2 - tw / 2
                r.forEach { word ->
                    if (word == "EKDUM") brackets(c, RectF(x - 8 * s, y - 26 * s, x + p.measureText(word) + 8 * s, y + 26 * s))
                    p.color = if (word == "MAST") hud else Color.WHITE
                    c.drawText(word, x, mid(p, y), p); x += p.measureText("$word ")
                }
            }
        }
        shot("2-visor/opening") { c ->
            hudFrame(c, 0f)
            val t = paint(chakra, Tok.LABEL_L, hud, Paint.Align.CENTER, 0.2f).apply { setShadowLayer(6 * s, 0f, 0f, a(hud, 0.6f)) }
            c.drawText("// TARGET  COMMUTE DIARIES · EP 4", W / 2, H * 0.2f, t)
            val p = paint(chakra, Tok.CAP, Color.WHITE, Paint.Align.CENTER, 0.02f).soft()
            c.drawText("3 HOURS. NO WATER.", W / 2, mid(p, H * 0.245f), p)
            brackets(c, RectF(W / 2 - p.measureText("3 HOURS. NO WATER.") / 2 - 16 * s, H * 0.245f - 40 * s, W / 2 + p.measureText("3 HOURS. NO WATER.") / 2 + 16 * s, H * 0.245f + 40 * s), 20 * s)
        }
        // The section change: a red scan sweeps down over interlaced lines.
        shot("2-visor/section-45") { c ->
            val lp = Paint().apply { color = a(INK, 0.35f) }
            for (y in 0 until h step 6) c.drawRect(0f, y.toFloat(), W, y + 3f, lp)
            val y = H * 0.55f
            c.drawRect(0f, 0f, W, y, Paint().apply { color = a(INK, 0.55f) })
            c.drawRect(0f, y - 2 * s, W, y + 2 * s, Paint().apply { color = hud; setShadowLayer(18 * s, 0f, 0f, hud) })
        }
        shot("2-visor/section-50") { c ->
            c.drawColor(a(INK, 0.85f))
            val lp = Paint().apply { color = a(hud, 0.25f) }
            for (y in 0 until h step 8) c.drawRect(0f, y.toFloat(), W, y + 2f, lp)
            brackets(c, RectF(W * 0.2f, H * 0.38f, W * 0.8f, H * 0.52f), 30 * s)
        }
        shot("2-visor/stats", ground = INK) { c ->
            val t = paint(chakra, Tok.LABEL_L, hud, track = 0.2f).apply { setShadowLayer(6 * s, 0f, 0f, a(hud, 0.6f)) }
            c.drawText("// MISSION LOG", x0, H * 0.15f, t)
            val title = paint(chakra, Tok.CAP, Color.WHITE); c.drawText("PALAM → SECTOR 62", x0, H * 0.2f, title)
            val p = hudPaint(1.5f)
            listOf("DIST" to "29.8 KM", "VMAX" to "92 KM/H", "TIME" to "44 MIN", "CLIPS" to "12", "MAX LEAN" to "31°").forEachIndexed { i, (k, v) ->
                val y = H * (0.3f + i * 0.08f)
                c.drawLine(x0, y + 22 * s, W - x0, y + 22 * s, p)
                c.drawText(k, x0, y, t)
                val vp = paint(chakra, Tok.CAP, Color.WHITE, Paint.Align.RIGHT); c.drawText(v, W - x0, y + 4 * s, vp)
            }
        }
    }

    // ---- 3. Livery: race paddock, number boards and kerbs ---------------------------------------

    /** A race number board: a white oval with a black number. */
    private fun plate(c: Canvas, text: String, cx: Float, cy: Float, size: Float, fill: Int = Color.WHITE, ink: Int = INK, face: Typeface = teko) {
        val p = paint(face, size, ink, Paint.Align.CENTER)
        val tw = p.measureText(text); val th = size * s * 0.9f
        val r = RectF(cx - tw / 2 - th * 0.45f, cy - th * 0.62f, cx + tw / 2 + th * 0.45f, cy + th * 0.62f)
        c.drawOval(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; setShadowLayer(10 * s, 0f, 3 * s, a(INK, 0.4f)) })
        c.drawOval(RectF(r.left + 4 * s, r.top + 4 * s, r.right - 4 * s, r.bottom - 4 * s), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 2 * s })
        c.drawText(text, cx, mid(p, cy) + 2 * s, p)
    }

    /** Red and white kerb blocks along a band. */
    private fun kerb(c: Canvas, l: Float, t: Float, r: Float, b: Float, block: Float = 24 * s, skew: Float = 10 * s) {
        var x = l - skew; var i = 0
        while (x < r) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (i % 2 == 0) RED else Color.WHITE }
            c.drawPath(Path().apply { moveTo(x + skew, t); lineTo(x + skew + block, t); lineTo(x + block, b); lineTo(x, b); close() }, p)
            x += block; i++
        }
    }

    private fun livery() {
        fun badge(c: Canvas) {
            plate(c, "62", x0 + 50 * s, H * 0.14f, Tok.CAP_L)
            val u = paint(teko, Tok.SUB, Color.WHITE).soft(); c.drawText("KM/H", x0 + 104 * s, mid(u, H * 0.14f), u)
        }
        shot("3-livery/caption") { c ->
            badge(c)
            val p = paint(teko, Tok.CAP_L, Color.WHITE, Paint.Align.LEFT, 0.02f).soft()
            val l1 = "BHAI YE ROAD TOH"
            c.drawText(l1, W / 2 - p.measureText(l1) / 2, mid(p, H * 0.66f - 56 * s), p)
            val wa = p.measureText("EKDUM ")
            val total = wa + 150 * s
            c.drawText("EKDUM", W / 2 - total / 2, mid(p, H * 0.66f), p)
            plate(c, "MAST", W / 2 - total / 2 + wa + 70 * s, H * 0.66f, Tok.CAP_L, RED, Color.WHITE)
        }
        shot("3-livery/opening") { c ->
            badge(c)
            val tag = paint(teko, Tok.SUB, Color.WHITE, track = 0.1f)
            c.drawRect(x0, H * 0.19f, x0 + tag.measureText("COMMUTE DIARIES · ROUND 4") + 20 * s, H * 0.215f, Paint().apply { color = RED })
            c.drawText("COMMUTE DIARIES · ROUND 4", x0 + 10 * s, mid(tag, H * 0.2025f), tag)
            val p = paint(racing, Tok.TITLE, Color.WHITE).soft()
            c.drawText("3 hours.", x0, mid(p, H * 0.25f), p)
            c.drawText("No water.", x0, mid(p, H * 0.25f + 70 * s), p)
            kerb(c, x0, H * 0.25f + 112 * s, x0 + p.measureText("No water.") + 20 * s, H * 0.25f + 124 * s, 16 * s, 6 * s)
        }
        // The section change: a kerb sweeps across on a diagonal.
        shot("3-livery/section-45") { c ->
            c.save(); c.rotate(-18f, W / 2, H / 2); kerb(c, -W * 0.2f, H * 0.42f, W * 1.2f, H * 0.52f, 60 * s, 20 * s); c.restore()
        }
        shot("3-livery/section-50") { c ->
            c.save(); c.rotate(-18f, W / 2, H / 2); kerb(c, -W * 0.6f, -H * 0.2f, W * 1.6f, H * 1.2f, 90 * s, 30 * s); c.restore()
        }
        shot("3-livery/stats", ground = INK) { c ->
            val t = paint(teko, Tok.CAP, Color.WHITE, track = 0.05f); c.drawText("RACE RESULT", x0, H * 0.16f, t)
            val sub = paint(teko, Tok.SUB, a(Color.WHITE, 0.6f), track = 0.1f); c.drawText("PALAM → SECTOR 62 · MON 13 OCT", x0, H * 0.19f, sub)
            kerb(c, x0, H * 0.205f, W - x0, H * 0.213f, 16 * s, 6 * s)
            listOf("TOP SPEED" to "92 km/h", "DISTANCE" to "29.8 km", "RIDE TIME" to "44 min", "MOMENTS" to "12").forEachIndexed { i, (k, v) ->
                val y = H * (0.28f + i * 0.09f)
                c.drawRect(x0, y - 30 * s, W - x0, y + 30 * s, Paint().apply { color = if (i == 0) Color.WHITE else Color.rgb(26, 26, 30) })
                c.drawRect(x0, y - 30 * s, x0 + 56 * s, y + 30 * s, Paint().apply { color = RED })
                val pp = paint(teko, Tok.CAP, Color.WHITE, Paint.Align.CENTER); c.drawText("P${i + 1}", x0 + 28 * s, mid(pp, y), pp)
                val kp = paint(teko, Tok.SUB, if (i == 0) INK else a(Color.WHITE, 0.7f), track = 0.08f); c.drawText(k, x0 + 72 * s, mid(kp, y), kp)
                val vp = paint(teko, Tok.CAP, if (i == 0) INK else Color.WHITE, Paint.Align.RIGHT); c.drawText(v, W - x0 - 14 * s, mid(vp, y), vp)
            }
        }
    }

    // ---- ride filters ------------------------------------------------------------------------

    private fun contrast(m: ColorMatrix, k: Float, lift: Float = 0f) {
        val o = 128 * (1 - k) + lift
        m.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)))
    }

    private fun rgb(m: ColorMatrix, r: Float, g: Float, b: Float, or: Float = 0f, og: Float = 0f, ob: Float = 0f) =
        m.postConcat(ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, or, 0f, g, 0f, 0f, og, 0f, 0f, b, 0f, ob, 0f, 0f, 0f, 1f, 0f)))

    private fun sat(m: ColorMatrix, v: Float) = m.postConcat(ColorMatrix().apply { setSaturation(v) })

    /** Black and white, except the reds (a red jacket, a red tank). */
    private fun redOnly(src: Bitmap): Bitmap {
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            Color.colorToHSV(c, hsv)
            val red = (hsv[0] < 15 || hsv[0] > 345) && hsv[1] > 0.45f
            if (!red) {
                val l = (0.3f * Color.red(c) + 0.59f * Color.green(c) + 0.11f * Color.blue(c))
                val v = ((l - 128) * 1.25f + 128).toInt().coerceIn(0, 255)
                px[i] = Color.rgb(v, v, v)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun filters() {
        fun name(c: Canvas, t: String) {
            val p = paint(geist, Tok.CAP, Color.WHITE).soft(); c.drawText(t, x0, H * 0.17f, p)
        }
        shot("filters/0-none") { name(it, "No filter") }
        shot("filters/1-asphalt", filter = ColorMatrix().apply { sat(this, 0.75f); contrast(this, 1.18f); rgb(this, 0.96f, 1f, 1.06f, 0f, 0f, 6f) }) { name(it, "Asphalt") }
        shot("filters/2-golden", filter = ColorMatrix().apply { sat(this, 1.12f); rgb(this, 1.12f, 1.03f, 0.84f, 8f, 2f, 0f); contrast(this, 1.05f) }) { name(it, "Golden hour") }
        shot("filters/3-monsoon", filter = ColorMatrix().apply { sat(this, 0.7f); rgb(this, 0.88f, 1.04f, 1.04f, 0f, 6f, 8f); contrast(this, 0.94f, 6f) }) { name(it, "Monsoon") }
        shot("filters/4-night", filter = ColorMatrix().apply { sat(this, 1.15f); rgb(this, 0.92f, 0.95f, 1.18f, -10f, -8f, 4f); contrast(this, 1.22f, -14f) }) { name(it, "Night ride") }
        shot("filters/5-dust", filter = ColorMatrix().apply { sat(this, 0.62f); rgb(this, 1.08f, 1.0f, 0.86f, 6f, 2f, 0f); contrast(this, 0.84f, 16f) }) { name(it, "Dust") }
        shot("filters/6-redonly", src = redOnly(scene)) { name(it, "Red only") }
    }

    // ---- Bold in other colours ---------------------------------------------------------------

    private fun boldColours() {
        listOf("yellow" to Tok.YELLOW, "red" to Color.rgb(239, 51, 64), "orange" to Color.rgb(255, 106, 19), "green" to Color.rgb(106, 196, 64), "blue" to Color.rgb(47, 128, 255), "white" to Color.WHITE)
            .forEachIndexed { i, (n, col) ->
                shot("bold/$i-$n") { c ->
                    val badge = paint(archivo, Tok.CAP_L, Color.WHITE, track = -0.02f).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * s }
                    c.drawText("62", x0, mid(badge, H * 0.14f), badge)
                    val u = paint(geistMed, Tok.LABEL, col, track = Tok.TRACK_CAPS).soft(); c.drawText("KM/H", x0 + badge.measureText("62") + 8 * s, mid(badge, H * 0.14f), u)
                    val p = paint(archivo, Tok.HERO, Color.WHITE, track = -0.02f).soft()
                    c.drawText("3 HOURS.", x0, mid(p, H * 0.235f), p)
                    val q = paint(archivo, Tok.CAP_L, col, track = -0.02f).soft(); c.drawText("NO WATER.", x0, mid(q, H * 0.235f + 70 * s), q)
                    val cp = paint(archivo, Tok.HERO, Color.WHITE, Paint.Align.CENTER, -0.02f).soft()
                    c.drawText("EKDUM", W / 2, mid(cp, H * 0.66f - 90 * s), cp)
                    cp.color = col; c.drawText("MAST", W / 2, mid(cp, H * 0.66f), cp)
                    // The yellow-cut frame, in this colour.
                    c.drawRect(W - x0 - 90 * s, H * 0.74f, W - x0, H * 0.79f, Paint().apply { color = col })
                    val l = paint(geistMed, Tok.LABEL, Color.WHITE, Paint.Align.RIGHT, Tok.TRACK_CAPS).soft(); c.drawText("THE CUT", W - x0, H * 0.73f, l)
                }
            }
    }
}
