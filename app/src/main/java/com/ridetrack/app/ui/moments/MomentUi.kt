package com.ridetrack.app.ui.moments

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.telemetry.model.RideEventType
import java.util.Locale
import kotlin.math.abs

/** Dot colour for a moment: what kind of event it was. */
fun momentColor(m: Moment): Color = when (m.primaryType) {
    RideEventType.HARD_BRAKE -> RtColors.Brake
    RideEventType.STRONG_ACCELERATION -> RtColors.Accel
    RideEventType.SIGNIFICANT_LEAN -> if ((m.peakValue ?: 0.0) < 0) RtColors.Left else RtColors.Right
    else -> if (m.kind == MomentKind.PHOTO) Color.White else RtColors.Primary
}

fun momentTitle(m: Moment): String {
    if (m.kind == MomentKind.PHOTO) return "Photo"
    val v = m.peakValue
    val parts = m.types.sortedBy { it.ordinal }.map {
        when (it) {
            RideEventType.HARD_BRAKE -> "Hard braking"
            RideEventType.STRONG_ACCELERATION -> "Acceleration"
            RideEventType.SIGNIFICANT_LEAN -> "Lean"
            else -> it.name.lowercase().replace('_', ' ')
        }
    }
    val value = when (m.primaryType) {
        RideEventType.SIGNIFICANT_LEAN -> v?.let { "${abs(it).toInt()}° ${if (it < 0) "L" else "R"}" }
        RideEventType.HARD_BRAKE, RideEventType.STRONG_ACCELERATION -> v?.let { String.format(Locale.US, "%.2f G", abs(it)) }
        else -> null
    }
    if (parts.isEmpty()) return "Clip"
    return listOfNotNull(parts.joinToString(" + "), value).joinToString(" · ")
}

fun momentSubtitle(m: Moment): String =
    listOfNotNull(Format.timeOfDay(m.timeMillis), m.speedMps?.let { Format.speedWithUnit(it) }).joinToString(" · ")

@Composable
fun rememberMoments(rideId: String): List<Moment> {
    val c = appContainer()
    val flow = remember(rideId) { c.moments.observe(rideId) }
    val list by flow.collectAsState(initial = emptyList())
    return list
}

/** Round map pin: the thumbnail cropped to a circle inside a coloured ring. */
fun circlePin(thumb: Bitmap?, ring: Color, sizePx: Int, ringPx: Float): Bitmap {
    val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val r = sizePx / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = ring.toArgb()
    c.drawCircle(r, r, r, paint)
    val inner = r - ringPx
    if (thumb != null) {
        val scale = (inner * 2) / minOf(thumb.width, thumb.height)
        val m = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(r - thumb.width * scale / 2, r - thumb.height * scale / 2)
        }
        paint.shader = BitmapShader(thumb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
    } else {
        paint.color = RtColors.Surface.toArgb()
    }
    c.drawCircle(r, r, inner, paint)
    return out
}
