package com.ridetrack.app.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.ridetrack.app.share.MomentField
import com.ridetrack.app.share.MomentLayout
import com.ridetrack.app.share.MomentOverlay
import com.ridetrack.app.share.MomentShareRenderer
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.moments.TelemetryPoint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * The moment share overlays for the case study, drawn by the app's own renderer over a plain
 * dusk-road backdrop (no real photo). Sample values only. Saved in build/shots/case-study.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class CaseStudyShareShots {
    private val route = (0..80).map { i -> val t = i / 80.0 * 5.2; 28.60 + 0.02 * sin(t) + 0.004 * i / 80.0 to 77.20 + 0.03 * cos(t * 0.7) + 0.01 * t }

    private fun overlay(events: Set<RideEventType>, speedKmh: Double, lean: Double, g: Double) = MomentOverlay(
        eventTypes = events, eventValue = null, timeText = "6:42 AM", dateText = "Sun, 16 Nov", rideName = "Sunday at Nahan",
        point = TelemetryPoint(0L, speedKmh / 3.6, lean, g, 0.3, route[40].first, route[40].second),
        route = route, demo = false, topSpeedMps = 96.0 / 3.6,
    )

    private fun backdrop(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.55f, intArrayOf(0xFF1B2A4A.toInt(), 0xFFE07A4F.toInt(), 0xFFF2B36B.toInt()), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP) })
        c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), Paint().apply { color = 0xFF16171B.toInt() })
        val hills = Path().apply { moveTo(0f, h * 0.55f); lineTo(w * 0.2f, h * 0.47f); lineTo(w * 0.45f, h * 0.52f); lineTo(w * 0.7f, h * 0.44f); lineTo(w.toFloat(), h * 0.5f); lineTo(w.toFloat(), h * 0.56f); lineTo(0f, h * 0.56f); close() }
        c.drawPath(hills, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2230.toInt() })
        val road = Path().apply { moveTo(w * 0.47f, h * 0.55f); lineTo(w * 0.53f, h * 0.55f); lineTo(w * 1.1f, h.toFloat()); lineTo(-w * 0.1f, h.toFloat()); close() }
        c.drawPath(road, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2C31.toInt() })
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF4F4F5.toInt() }
        for (i in 0 until 7) { val t0 = i / 7f; val t1 = t0 + 0.06f; val y0 = h * (0.55f + 0.45f * t0 * t0); val y1 = h * (0.55f + 0.45f * t1 * t1); val wd = 3f + 18f * t0
            c.drawPath(Path().apply { moveTo(w / 2f - wd / 2, y0); lineTo(w / 2f + wd / 2, y0); lineTo(w / 2f + wd, y1); lineTo(w / 2f - wd, y1); close() }, dash) }
        return b
    }

    private fun save(name: String, layout: MomentLayout, o: MomentOverlay) {
        val r = MomentShareRenderer(ApplicationProvider.getApplicationContext())
        val out = r.composePhoto(backdrop(1080, 1920), 1080, 1920, o, MomentField.DEFAULT, layout)
        val f = File("build/shots/case-study/$name.png").apply { parentFile?.mkdirs() }
        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shareHud() = save("share-hud", MomentLayout.HUD, overlay(setOf(RideEventType.SIGNIFICANT_LEAN), 72.0, -38.0, 0.21))
    @Test fun shareBar() = save("share-strip", MomentLayout.BAR, overlay(setOf(RideEventType.HARD_BRAKE), 64.0, 4.0, -0.62))
    @Test fun shareMinimal() = save("share-minimal", MomentLayout.MINIMAL, overlay(setOf(RideEventType.STRONG_ACCELERATION), 88.0, 6.0, 0.41))
}
