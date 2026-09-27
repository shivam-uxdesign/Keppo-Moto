package com.ridetrack.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.data.HudLayout
import com.ridetrack.app.data.HudSettings
import com.ridetrack.app.data.HudTheme
import com.ridetrack.app.hud.HudData
import com.ridetrack.app.hud.HudStatus
import com.ridetrack.app.ui.components.RevBar
import com.ridetrack.app.ui.components.leanColor
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import kotlin.math.abs

private val hudNumber = TextStyle(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)
private val hudLabel = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.2.sp)

private data class HudColors(val bg: Color, val border: Color, val text: Color, val muted: Color, val borderWidth: Dp)

private fun hudColors(s: HudSettings, demo: Boolean): HudColors = when (s.theme) {
    HudTheme.HIGH_CONTRAST -> HudColors(Color.Black, Color.White, Color.White, Color.White, 2.dp)
    HudTheme.DARK -> HudColors(
        bg = Color(0xFF141317).copy(alpha = s.opacity / 100f),
        border = if (demo) RtColors.Warning.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.09f),
        text = RtColors.TextPrimary,
        muted = RtColors.TextSecondary,
        borderWidth = 1.dp,
    )
}

private fun statusColor(status: HudStatus) = when (status) {
    HudStatus.RECORDING -> RtColors.Ok
    HudStatus.STOPPED -> RtColors.Paused
    HudStatus.GPS_LOST -> RtColors.Warning
}

/** The floating pop-up card. Size scaling is applied by the caller via density. */
@Composable
fun HudCard(data: HudData, settings: HudSettings, modifier: Modifier = Modifier) {
    val c = hudColors(settings, data.demo)
    val width = if (settings.layout == HudLayout.MINIMAL) 168.dp else 196.dp
    Column(
        modifier
            .width(width)
            .background(c.bg, RoundedCornerShape(22.dp))
            .border(c.borderWidth, c.border, RoundedCornerShape(22.dp))
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Ride pop-up. Speed ${Format.speedWithUnit(data.speedMps)}, lean ${Format.lean(data.leanDeg)}"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusRow(data, c)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Format.speedKmh(data.speedMps),
                style = hudNumber.copy(fontSize = 60.sp, lineHeight = 56.sp),
                color = if (data.speedMps == null) RtColors.TextTertiary else c.text,
            )
            Spacer(Modifier.width(4.dp))
            Text("km/h", fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
            if (data.gear != null) {
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("GEAR", style = hudLabel, color = c.muted)
                    Text(
                        Format.gear(data.gear),
                        style = hudNumber.copy(fontSize = 30.sp, lineHeight = 32.sp),
                        color = if (data.gear == 0) RtColors.Ok else c.text,
                    )
                }
            }
        }
        if (data.rpm != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RevBar(data.rpm, data.redlineRpm)
                Text(Format.rpm(data.rpm) + " rpm", fontSize = 11.sp, color = c.muted, style = hudNumber)
            }
        }
        if (settings.layout != HudLayout.TOURING) LeanBlock(data, c, settings.theme)
        when (settings.layout) {
            HudLayout.MINIMAL -> Unit
            HudLayout.TOURING -> Cells(
                c,
                "Distance" to (data.distanceM?.let { Format.distanceValue(it) + " km" } ?: Format.DASH),
                "Time" to (data.elapsedMillis?.let(Format::clock) ?: Format.DASH),
                "Avg" to Format.speedWithUnit(data.avgSpeedMps),
                "Max" to Format.speedWithUnit(data.maxSpeedMps),
            )
            HudLayout.SPORT -> Cells(
                c,
                "G-force" to Format.g(data.combinedG),
                "Max lean" to Format.lean(data.maxLeanDeg),
                valueColors = listOf(RtColors.GForce, null),
            )
            HudLayout.TELEMETRY -> Cells(
                c,
                "Accel" to Format.gSigned(data.longitudinalG),
                "G-force" to Format.g(data.combinedG),
                "Heading" to Format.heading(data.headingDeg),
                "Time" to (data.elapsedMillis?.let(Format::clock) ?: Format.DASH),
                valueColors = listOf(
                    data.longitudinalG?.let { if (it < 0) RtColors.Brake else RtColors.Accel },
                    RtColors.GForce,
                    null,
                    null,
                ),
            )
        }
    }
}

@Composable
private fun StatusRow(data: HudData, c: HudColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (data.demo) {
            Text(
                "DEMO",
                style = hudLabel,
                color = RtColors.Warning,
                modifier = Modifier
                    .background(RtColors.Warning.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        val color = statusColor(data.status)
        when (data.status) {
            HudStatus.RECORDING -> Box(Modifier.size(6.dp).background(color, CircleShape))
            HudStatus.STOPPED -> Icon(Icons.Rounded.Pause, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            HudStatus.GPS_LOST -> Icon(Icons.Outlined.GpsOff, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(6.dp))
        val text = when (data.status) {
            HudStatus.RECORDING -> if (data.demo) "SIMULATED" else "RECORDING"
            HudStatus.STOPPED -> "STOPPED" + (data.stoppedForMillis?.let { " · " + Format.clock(it) } ?: "")
            HudStatus.GPS_LOST -> "GPS LOST"
        }
        Text(text, style = hudLabel, color = if (data.status == HudStatus.RECORDING) c.muted else color, maxLines = 1)
    }
}

@Composable
private fun LeanBlock(data: HudData, c: HudColors, theme: HudTheme) {
    val lean = data.leanDeg
    val color = if (lean == null) RtColors.TextTertiary else if (theme == HudTheme.HIGH_CONTRAST) Color.White else leanColor(lean)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("LEAN", style = hudLabel, color = c.muted, modifier = Modifier.weight(1f).padding(bottom = 4.dp))
            Text(Format.lean(lean), style = hudNumber.copy(fontSize = 26.sp, lineHeight = 28.sp), color = color)
        }
        if (lean == null) {
            data.leanNote?.let { Text(it, fontSize = 11.sp, color = c.muted) }
        } else {
            LeanBar(lean, color)
        }
    }
}

/** Thin bar: centre tick, fill toward the lean side (full = 60°). */
@Composable
private fun LeanBar(leanDeg: Double, color: Color) {
    Layout(
        content = {
            Box(Modifier.background(RtColors.Outline, RoundedCornerShape(2.dp)))
            Box(Modifier.background(RtColors.TextTertiary))
            Box(Modifier.background(color, RoundedCornerShape(2.dp)))
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val track = 4.dp.roundToPx()
        val tick = 2.dp.roundToPx()
        val fill = ((abs(leanDeg).coerceAtMost(60.0) / 60.0) * (w / 2)).toInt()
        val p0 = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(w, track))
        val p1 = measurables[1].measure(androidx.compose.ui.unit.Constraints.fixed(tick, h))
        val p2 = measurables[2].measure(androidx.compose.ui.unit.Constraints.fixed(fill.coerceAtLeast(0), track))
        layout(w, h) {
            val top = (h - track) / 2
            p0.place(0, top)
            p2.place(if (leanDeg >= 0) w / 2 else w / 2 - fill, top)
            p1.place(w / 2 - tick / 2, 0)
        }
    }
}

@Composable
private fun Cells(c: HudColors, vararg cells: Pair<String, String>, valueColors: List<Color?> = emptyList()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.toList().chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEachIndexed { i, (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label.uppercase(), style = hudLabel, color = c.muted, maxLines = 1)
                        Text(
                            value,
                            style = hudNumber.copy(fontSize = 19.sp, lineHeight = 22.sp),
                            color = valueColors.getOrNull(row * 2 + i) ?: c.text,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Collapsed speed bubble; ring colour and icon-free text reflect status. */
@Composable
fun HudBubble(data: HudData, settings: HudSettings, modifier: Modifier = Modifier) {
    val c = hudColors(settings, data.demo)
    Column(
        modifier
            .size(76.dp)
            .background(c.bg, CircleShape)
            .border(2.dp, if (settings.theme == HudTheme.HIGH_CONTRAST) Color.White else statusColor(data.status), CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = "Speed ${Format.speedWithUnit(data.speedMps)}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(Format.speedKmh(data.speedMps), style = hudNumber.copy(fontSize = 30.sp, lineHeight = 30.sp), color = c.text)
        Text("km/h", fontSize = 10.sp, color = c.muted, modifier = Modifier.offset(y = (-2).dp))
    }
}
