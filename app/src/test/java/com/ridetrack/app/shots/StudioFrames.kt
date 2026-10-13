package com.ridetrack.app.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.ridetrack.app.studio.CaptionKind
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.CaptionLook
import com.ridetrack.app.studio.FrameAt
import com.ridetrack.app.studio.RideCard
import com.ridetrack.app.studio.StickerItem
import com.ridetrack.app.studio.StickerKind
import com.ridetrack.app.studio.StudioArt
import com.ridetrack.app.studio.TextItem
import com.ridetrack.app.studio.TextLook
import com.ridetrack.app.studio.Transition
import com.ridetrack.app.studio.TransitionKind
import com.ridetrack.app.studio.Vibe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Still frames of the made video's graphics for every vibe, to look at and compare before and
 * after a design change (build/frames/<vibe>/…png, 540×960). Not a pass/fail test: it draws.
 * The "video" under the graphics is a drawn road scene, moved by the vibe's camera.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class StudioFrames {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val w = 1080
    private val h = 1920
    private val out = File(System.getProperty("frames.dir") ?: "build/frames")

    private val lines = listOf(
        CaptionLine(0, 1_400, "Bhai ye road toh ekdum mast hai", "Bro, this road is amazing", key = "mast"),
        CaptionLine(1_500, 2_600, "62 pe chal rahe hain!", "Doing 62!"),
    )
    private val card = RideCard(
        title = "Palam to Sector 62",
        subtitle = "Mon 13 Oct · 29.8 km",
        route = (0..60).map { i -> val a = i / 60f; Triple(-0.6f + 1.2f * a, 0.4f * kotlin.math.sin(a * 6f), a) },
        stats = listOf("29.8" to "km", "92" to "top km/h", "44" to "minutes", "12" to "moments"),
    )

    /** A drawn road at dusk: sky, hills, road, lane marks, a sign. */
    private val scene: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, Color.rgb(70, 110, 160), Color.rgb(235, 170, 120), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.55f, p); p.shader = null
        p.color = Color.rgb(60, 80, 70); c.drawPath(Path().apply { moveTo(0f, h * 0.55f); lineTo(w * 0.3f, h * 0.45f); lineTo(w * 0.6f, h * 0.52f); lineTo(w * 0.85f, h * 0.44f); lineTo(w.toFloat(), h * 0.5f); lineTo(w.toFloat(), h * 0.56f); lineTo(0f, h * 0.56f); close() }, p)
        p.color = Color.rgb(90, 110, 80); c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), p)
        p.color = Color.rgb(55, 55, 60); c.drawPath(Path().apply { moveTo(w * 0.46f, h * 0.55f); lineTo(w * 0.54f, h * 0.55f); lineTo(w * 1.05f, h.toFloat()); lineTo(-w * 0.05f, h.toFloat()); close() }, p)
        p.color = Color.rgb(240, 230, 200)
        for (i in 0 until 8) { val y0 = h * (0.58f + i * 0.055f); val y1 = y0 + h * 0.03f * (1 + i * 0.2f); c.drawRect(w * 0.497f - i * 3f, y0, w * 0.503f + i * 3f, y1, p) }
        p.color = Color.rgb(30, 120, 70); c.drawRect(w * 0.78f, h * 0.38f, w * 0.95f, h * 0.46f, p)
        p.color = Color.WHITE; p.textSize = 34f; c.drawText("NH 48", w * 0.8f, h * 0.425f, p)
    }

    private fun frame(vibe: Vibe, localMs: Long, durMs: Long, prev: Boolean = false, next: Boolean = false, inKind: TransitionKind = TransitionKind.STYLE, outKind: TransitionKind = TransitionKind.STYLE) =
        FrameAt(vibe, localMs, durMs, prev, next, inKind, Transition(inKind).ms(vibe), outKind, Transition(outKind).ms(vibe), cut = 1)

    /** The scene through the vibe's camera, then [draw] on top; saved at half size. */
    private fun shot(name: String, art: StudioArt, f: FrameAt?, filter: android.graphics.Paint? = null, draw: (Canvas) -> Unit) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        val cam = f?.let { art.camera(it) }
        c.save()
        if (cam != null) {
            // As the made video: never zoomed less than it takes to keep the frame filled.
            val r = Math.toRadians(kotlin.math.abs(cam.deg).toDouble())
            val fill = (kotlin.math.cos(r) + (h.toFloat() / w) * kotlin.math.sin(r)).toFloat() + 2 * kotlin.math.abs(cam.x) / w + 2 * kotlin.math.abs(cam.y) / h
            val k = maxOf(cam.k, fill)
            c.translate(w / 2f + cam.x, h / 2f + cam.y); c.rotate(cam.deg); c.scale(k, k); c.translate(-w / 2f, -h / 2f)
        }
        c.drawBitmap(scene, 0f, 0f, filter ?: Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        draw(c)
        val small = Bitmap.createScaledBitmap(b, w / 2, h / 2, true)
        val file = File(out, "$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun frames() {
        for (vibe in Vibe.entries) {
            val v = vibe.name.lowercase()
            val art = StudioArt(context)
            // The vibe's own captions, mid-word, with the opening text and badge; then every caption style.
            shot("$v/caption-style", art, frame(vibe, 900, 4_000)) { c -> art.drawClip(c, frame(vibe, 900, 4_000), lines, 62, "6:21 PM", true) }
            shot("$v/opening", art, frame(vibe, 1_000, 4_000)) { c -> art.drawClip(c, frame(vibe, 1_000, 4_000), lines, 62, "6:21 PM", true, StudioArt.Opener("Commute Diaries · Ep 4", "3 hours. No water.", "13 Oct 2026")) }
            for (k in CaptionKind.entries) {
                shot("$v/caption-${k.name.lowercase()}", art, frame(vibe, 900, 4_000)) { c -> art.drawClip(c, frame(vibe, 900, 4_000), lines, 62, "6:21 PM", true, look = CaptionLook(kind = k, english = true)) }
            }
            shot("$v/caption-band", art, frame(vibe, 900, 4_000)) { c -> art.drawClip(c, frame(vibe, 900, 4_000), lines, 62, "6:21 PM", true, look = CaptionLook(box = true, english = true)) }
            // The vibe's own transition, either side of the cut.
            for (q in listOf(0.25f, 0.5f, 0.75f)) {
                val d = Transition().ms(vibe)
                val f = if (q < 0.5f) frame(vibe, (4_000 - d / 2 + (q * d)).toLong(), 4_000, next = true) else frame(vibe, ((q - 0.5f) * d).toLong(), 4_000, prev = true)
                shot("$v/transition-style-${(q * 100).toInt()}", art, f) { c -> art.drawClip(c, f, emptyList(), 62, "6:21 PM", false) }
            }
            // The same opening and stats in the rider's own colour (red), to check every accent element follows it.
            val red = StudioArt(context, accentOverride = com.ridetrack.app.studio.Tok.RED)
            shot("$v/accent-opening", red, frame(vibe, 1_000, 4_000)) { c -> red.drawClip(c, frame(vibe, 1_000, 4_000), lines, 62, "6:21 PM", true, StudioArt.Opener("Commute Diaries · Ep 4", "3 hours. No water.", "13 Oct 2026")) }
            shot("$v/accent-stats", red, null) { c -> red.drawStats(c, frame(vibe, 1_200, 1_500), card, true, true) }
            shot("$v/title", art, null) { c -> art.drawTitle(c, frame(vibe, 1_000, 2_000), card, true) }
            shot("$v/stats", art, null) { c -> art.drawStats(c, frame(vibe, 1_200, 1_500), card, true, true) }
            shot("$v/text-looks", art, frame(vibe, 500, 4_000)) { c ->
                TextLook.entries.forEachIndexed { i, look ->
                    art.drawTextItem(c, vibe, TextItem("t$i", 0, 3_000, look.label + " text", y = 0.14f + i * 0.17f, look = look), 800, 3_000)
                }
            }
            shot("$v/stickers", art, frame(vibe, 500, 4_000)) { c ->
                listOf(StickerKind.SPEED to (0.22f to 0.74f), StickerKind.LEAN to (0.64f to 0.74f), StickerKind.EMOJI to (0.3f to 0.45f), StickerKind.ARROW to (0.65f to 0.35f), StickerKind.CIRCLE to (0.6f to 0.6f)).forEach { (k, xy) ->
                    art.drawSticker(c, StickerItem(k.name, k, 0, 3_000, xy.first, xy.second), 1_000, 62, 24, vibe)
                }
            }
        }
        // Every filter on the same shot, under the Clean vibe's captions (filters sit under the graphics).
        com.ridetrack.app.studio.RideFilter.entries.forEach { rf ->
            val clean = StudioArt(context)
            val paint = if (rf == com.ridetrack.app.studio.RideFilter.NONE) null else com.ridetrack.app.studio.filterPaint(rf, 1f)
            shot("filters/${rf.ordinal}-${rf.name.lowercase()}", clean, frame(Vibe.VLOG, 900, 4_000), paint) { c -> clean.drawClip(c, frame(Vibe.VLOG, 900, 4_000), lines, 62, "6:21 PM", true) }
        }
        // Every transition kind on the first vibe's clip, at the cut.
        val art = StudioArt(context)
        for (k in TransitionKind.entries) {
            for (q in listOf(0.25f, 0.5f, 0.75f)) {
                val vibe = Vibe.entries.first()
                val d = Transition(k).ms(vibe)
                val f = if (q < 0.5f) frame(vibe, (4_000 - d / 2 + (q * d)).toLong(), 4_000, next = true, outKind = k) else frame(vibe, ((q - 0.5f) * d).toLong(), 4_000, prev = true, inKind = k)
                shot("transitions/${k.name.lowercase()}-${(q * 100).toInt()}", art, f) { c -> art.drawClip(c, f, emptyList(), -1, "", false) }
            }
        }
    }
}
