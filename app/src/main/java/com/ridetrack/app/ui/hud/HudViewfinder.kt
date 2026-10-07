package com.ridetrack.app.ui.hud

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.hud.HudData
import com.ridetrack.app.hud.HudStatus
import com.ridetrack.app.hud.HudVideo
import com.ridetrack.app.moments.MomentSource
import com.ridetrack.app.ui.components.leanColor
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import kotlin.math.abs

private val Shade = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 4f)
private val Label = TextStyle(fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, shadow = Shade)
private val Mono = TextStyle(fontFamily = FontFamily.Monospace, shadow = Shade)

/**
 * The pop-up while a video is being filmed: the camera, with the ride's numbers in a very
 * light layer over it. Red edge while filming; amber while paused or filming for lost GPS.
 */
@Composable
fun ViewfinderCard(data: HudData, video: HudVideo, frame: ImageBitmap?, modifier: Modifier = Modifier) {
    val safety = video.source == MomentSource.GPS_LOST
    val edge = if (video.paused || safety) RtColors.Warning else RtColors.Recording
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .size(216.dp, 288.dp)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(Color(0xFF111114))
            .border(1.5.dp, edge.copy(alpha = 0.85f), shape)
            .semantics { contentDescription = "Filming a video, ${Format.clock(video.elapsedMillis)}" },
    ) {
        if (frame != null) {
            Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                when {
                    video.starting -> "Starting camera…"
                    safety -> "Filming while GPS is lost"
                    else -> "Filming · no preview on this phone"
                },
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        // Soft shade only at the edges, so the picture stays clear.
        Box(Modifier.fillMaxWidth().height(44.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
        if (video.paused) {
            Text("PAUSED", style = Label.copy(fontSize = 11.sp, letterSpacing = 2.sp), color = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val gpsLost = data.status == HudStatus.GPS_LOST
            Box(Modifier.size(6.dp).background(if (gpsLost) RtColors.Warning else RtColors.Ok, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (gpsLost) "GPS LOST" else "GPS ON", style = Label, color = if (gpsLost) RtColors.Warning else Color.White.copy(alpha = 0.85f), maxLines = 1)
            Spacer(Modifier.weight(1f))
            RecDot(edge, blink = !video.paused && !video.starting)
            Spacer(Modifier.width(5.dp))
            val clock = Format.clock(video.elapsedMillis) + if (video.back) " · BACK" else ""
            Text(
                when {
                    video.starting -> "STARTING"
                    video.paused -> "PAUSED $clock"
                    safety -> "SAFETY REC $clock"
                    else -> "REC $clock"
                },
                style = Mono.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                color = if (video.paused || safety) RtColors.Warning else Color.White,
                maxLines = 1,
                softWrap = false,
            )
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.speedKmh(data.speedMps), style = Mono.copy(fontSize = 38.sp, lineHeight = 38.sp, letterSpacing = (-1).sp), color = Color.White)
                Text("km/h", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), style = TextStyle(shadow = Shade), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(Format.g(data.combinedG), style = Mono.copy(fontSize = 13.sp), color = Color(0xFFFFD166))
                    Text(Format.lean(data.leanDeg), style = Mono.copy(fontSize = 12.sp), color = if (data.leanDeg == null) Color.White.copy(alpha = 0.6f) else leanColor(data.leanDeg))
                }
            }
            Spacer(Modifier.height(8.dp))
            ThinLeanBar(data.leanDeg)
        }
    }
}

@Composable
private fun RecDot(color: Color, blink: Boolean) {
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "recDot")
    Box(Modifier.size(7.dp).alpha(if (blink) pulse else 1f).background(color, CircleShape))
}

/** 3 dp bar: centre tick, fill toward the lean side (full = 45°). */
@Composable
private fun ThinLeanBar(leanDeg: Double?) {
    val color = leanColor(leanDeg)
    Canvas(Modifier.fillMaxWidth().height(7.dp)) {
        val y = size.height / 2
        val h = 3.dp.toPx()
        val r = CornerRadius(h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y - h / 2), Size(size.width, h), r)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
        val lean = leanDeg ?: return@Canvas
        val w = (abs(lean) / 45.0).coerceAtMost(1.0).toFloat() * size.width / 2
        val left = if (lean < 0) size.width / 2 - w else size.width / 2
        drawRoundRect(color, Offset(left, y - h / 2), Size(w, h), r)
    }
}
