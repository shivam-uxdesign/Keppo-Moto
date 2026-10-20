package com.ridetrack.app.studio.storyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.util.Locale

/**
 * The picture a shot shows until it's filmed: a dark slate with a dashed frame (so it can never
 * be mistaken for footage), the shot number, the action in large text, framing, camera and mount,
 * the length, a framing icon, the voice-over line and the gear fallback. Drawn by the app at the
 * Reel's size; no Gemini.
 */
object PlaceholderArt {
    const val W = 1080
    const val H = 1920
    private const val SLATE = 0xFF16181B.toInt()
    private const val DASH = 0xFF3E4248.toInt()

    fun draw(number: String, section: String, b: Storyboard, accent: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(SLATE)
        val m = 56f
        val frame = RectF(m, m, W - m, H - m)
        c.drawRoundRect(frame, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; color = DASH; pathEffect = DashPathEffect(floatArrayOf(28f, 22f), 0f)
        })
        val left = frame.left + 64f
        val width = (frame.width() - 128f).toInt()
        var y = frame.top + 96f
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 40f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.12f }
        c.drawText("SHOT $number", left, y, label)
        val chip = if (b.riding) "TO FILM" else "TO FILM · OFF THE BIKE"
        val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A0A12.toInt(); textSize = 32f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val cw = chipPaint.measureText(chip) + 44f
        c.drawRoundRect(RectF(frame.right - 64f - cw, y - 44f, frame.right - 64f, y + 14f), 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFB7185.toInt() })
        c.drawText(chip, frame.right - 64f - cw + 22f, y, chipPaint)
        y += 70f
        val sec = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B8B93.toInt(); textSize = 36f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.08f }
        c.drawText(section.uppercase(Locale.US), left, y, sec)
        y += 90f
        drawIcon(c, b.framingKind, W / 2f, y + 150f, accent)
        y += 360f
        val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 84f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        y = text(c, b.action, big, left, y, width, maxLines = 6) + 40f
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9C9CF.toInt(); textSize = 44f }
        listOf(
            "${b.framingKind.label}" + listOf(b.angle, b.movement).filter { it.isNotBlank() }.joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" },
            "${b.camera.replaceFirstChar { it.uppercase() }} · ${b.mount}",
            String.format(Locale.US, "%.1f s", b.durationMs / 1000.0) + when (b.audio) { "engine" -> " · engine sound first"; "voice" -> " · your voice first"; else -> "" },
        ).forEach { line -> y = text(c, line, small, left, y, width, maxLines = 2) + 14f }
        if (b.mustShow.isNotBlank()) y = text(c, "Must show: ${b.mustShow}", small, left, y, width, maxLines = 2) + 14f
        // Bottom: the line to say, then the gear note.
        var bottom = frame.bottom - 120f
        b.fallback?.let { f ->
            val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFBD27A.toInt(); textSize = 40f }
            val l = layout("Best with ${f.ideal}. Using ${f.using}.", note, width - 56)
            val top = bottom - l.height - 56f
            c.drawRoundRect(RectF(left, top, left + width, bottom), 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2310.toInt() })
            c.save(); c.translate(left + 28f, top + 28f); l.draw(c); c.restore()
            bottom = top - 28f
        }
        b.say?.let { s ->
            val say = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD6F1F2.toInt(); textSize = 44f }
            val l = layout("Say: “$s”", say, width - 56)
            val top = bottom - l.height - 56f
            if (top > y) {
                c.drawRoundRect(RectF(left, top, left + width, bottom), 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E3E40.toInt() })
                c.save(); c.translate(left + 28f, top + 28f); l.draw(c); c.restore()
            }
        }
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6E7178.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER }
        c.drawText("Placeholder · fill it in Storyboard or the editor", W / 2f, frame.bottom - 50f, foot)
        return bmp
    }

    fun save(bmp: Bitmap, out: File) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(out)
    }

    private fun layout(s: String, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()

    private fun text(c: Canvas, s: String, p: TextPaint, x: Float, y: Float, width: Int, maxLines: Int): Float {
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.08f).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return y + l.height
    }

    /** Eye (extreme close-up), head (close-up), rider (medium), road (wide), in a dashed frame. */
    private fun drawIcon(c: Canvas, f: Framing, cx: Float, cy: Float, accent: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = accent; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val box = RectF(cx - 220f, cy - 140f, cx + 220f, cy + 140f)
        c.drawRoundRect(box, 30f, 30f, Paint(p).apply { color = DASH; strokeWidth = 6f; pathEffect = DashPathEffect(floatArrayOf(20f, 16f), 0f) })
        when (f) {
            Framing.EXTREME -> {
                val eye = Path().apply { moveTo(cx - 150f, cy); quadTo(cx, cy - 110f, cx + 150f, cy); quadTo(cx, cy + 110f, cx - 150f, cy) }
                c.drawPath(eye, p)
                c.drawCircle(cx, cy, 46f, p)
                c.drawCircle(cx, cy, 16f, Paint(p).apply { style = Paint.Style.FILL })
            }
            Framing.CLOSE -> {
                c.drawCircle(cx, cy - 20f, 70f, p)
                c.drawArc(RectF(cx - 130f, cy + 60f, cx + 130f, cy + 230f), 200f, 140f, false, p)
            }
            Framing.MEDIUM -> {
                c.drawCircle(cx, cy - 60f, 44f, p)
                c.drawArc(RectF(cx - 110f, cy, cx + 110f, cy + 220f), 180f, 180f, false, p)
            }
            Framing.WIDE -> {
                c.drawLine(cx - 60f, cy - 100f, cx - 190f, cy + 120f, p)
                c.drawLine(cx + 60f, cy - 100f, cx + 190f, cy + 120f, p)
                c.drawLine(cx, cy - 60f, cx, cy - 20f, p)
                c.drawLine(cx, cy + 20f, cx, cy + 70f, p)
            }
        }
    }
}
