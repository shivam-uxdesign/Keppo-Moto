package com.ridetrack.app.ui.hud

import com.ridetrack.app.ui.theme.DarkPalette
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
import com.ridetrack.app.hud.CameraIndicator
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

// The HUD is a riding surface: always the dark palette.
private fun hudColors(s: HudSettings, demo: Boolean): HudColors = when (s.theme) {
    HudTheme.HIGH_CONTRAST -> HudColors(Color.Black, Color.White, Color.White, Color.White, 2.dp)
    HudTheme.DARK -> HudColors(
        bg = Color(0xFF141317).copy(alpha = s.opacity / 100f),
        border = if (demo) DarkPalette.warning.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.09f),
        text = DarkPalette.textPrimary,
        muted = DarkPalette.textSecondary,
        borderWidth = 1.dp,
    )
}


private fun statusColor(status: HudStatus) = when (status) {
    HudStatus.RECORDING -> DarkPalette.ok
    HudStatus.STOPPED -> DarkPalette.paused
    HudStatus.PAUSED, HudStatus.GPS_LOST -> DarkPalette.warning
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
        StatusRow(data, c, compact = settings.layout == HudLayout.MINIMAL)
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

/**
 * Left: GPS, which never changes with the ride state. Right: the ride state itself:
 * RECORDING with the ride time, PAUSED (by the rider) or STOPPED (auto-pause).
 */
@Composable
private fun StatusRow(data: HudData, c: HudColors, compact: Boolean) {
    val label = hudLabel.copy(letterSpacing = 0.6.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val gpsLost = data.status == HudStatus.GPS_LOST
        val gpsColor = when {
            data.demo -> RtColors.Warning
            gpsLost -> RtColors.Warning
            else -> RtColors.Ok
        }
        if (gpsLost) {
            Icon(Icons.Outlined.GpsOff, contentDescription = null, tint = gpsColor, modifier = Modifier.size(12.dp))
        } else {
            Box(Modifier.size(6.dp).background(gpsColor, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                data.demo -> "SIMULATED"
                gpsLost -> "GPS LOST"
                else -> "GPS ON"
            },
            style = label,
            color = if (gpsLost || data.demo) gpsColor else c.muted,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        val time = data.elapsedMillis?.let { " · " + Format.clock(it) } ?: ""
        val (text, color) = when (data.status) {
            HudStatus.RECORDING, HudStatus.GPS_LOST -> (if (compact) "REC$time" else "RECORDING$time") to RtColors.Error
            HudStatus.PAUSED -> "PAUSED$time" to RtColors.Warning
            HudStatus.STOPPED -> "STOPPED" + (data.stoppedForMillis?.let { " · " + Format.clock(it) } ?: "") to RtColors.Paused
        }
        if (data.status == HudStatus.RECORDING || data.status == HudStatus.GPS_LOST) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = label, color = color, maxLines = 1, softWrap = false)
    }
}

/** Tiny camera state: a dim dot + CAM while buffering, red dot + REC while saving. */
@Composable
fun CameraBadge(state: CameraIndicator, muted: Color, modifier: Modifier = Modifier) {
    val rec = state == CameraIndicator.REC
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (rec) RtColors.Error else muted.copy(alpha = 0.6f), CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(if (rec) "REC" else "CAM", style = hudLabel, color = if (rec) RtColors.Error else muted.copy(alpha = 0.8f), maxLines = 1)
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
