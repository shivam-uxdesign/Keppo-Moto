package com.ridetrack.app.ui.profile

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sick
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.ui.theme.RtColors

/** Each badge's drawing. */
fun BadgeArt.icon(): ImageVector = when (this) {
    BadgeArt.ROAD -> Icons.Rounded.Route
    BadgeArt.DAY -> Icons.Rounded.WbSunny
    BadgeArt.CLOCK -> Icons.Rounded.Schedule
    BadgeArt.WEEKEND -> Icons.Rounded.Weekend
    BadgeArt.CALENDAR -> Icons.Rounded.CalendarMonth
    BadgeArt.SUNRISE -> Icons.Rounded.WbTwilight
    BadgeArt.MOON -> Icons.Rounded.Bedtime
    BadgeArt.FUEL -> Icons.Rounded.LocalGasStation
    BadgeArt.WRENCH -> Icons.Rounded.Build
    BadgeArt.CLAPPER -> Icons.Rounded.Movie
    BadgeArt.SERIES -> Icons.Rounded.VideoLibrary
    BadgeArt.PIN -> Icons.Rounded.Place
    BadgeArt.NEW_ROAD -> Icons.Rounded.AltRoute
    BadgeArt.MOUNTAIN -> Icons.Rounded.Terrain
    BadgeArt.HAIRPIN -> Icons.Rounded.UTurnLeft
    BadgeArt.MAP -> Icons.Rounded.Map
    BadgeArt.RAIN -> Icons.Rounded.Umbrella
    BadgeArt.SNOW -> Icons.Rounded.AcUnit
    BadgeArt.MONTHS -> Icons.Rounded.DateRange
    BadgeArt.CAKE -> Icons.Rounded.Cake
    BadgeArt.ODOMETER -> Icons.Rounded.TwoWheeler
    BadgeArt.CAMERA -> Icons.Rounded.Videocam
    BadgeArt.STORYBOARD -> Icons.Rounded.Dashboard
    BadgeArt.SNEEZE -> Icons.Rounded.Sick
    BadgeArt.SCREAM -> Icons.Rounded.RecordVoiceOver
    BadgeArt.FIREWORK -> Icons.Rounded.Celebration
    BadgeArt.REPEAT -> Icons.Rounded.Repeat
    BadgeArt.TANK -> Icons.Rounded.WaterDrop
}

/** The metal a badge is drawn in: its tier, the app's colour for one-level badges, grey when locked. */
@Composable
fun badgeMetal(a: Achievement): Color = when {
    !a.earned -> Color(0xFF3A3A40)
    a.tier != null -> Color(a.tier!!.argb)
    a.secret -> Color(0xFFFF8FB1)
    else -> RtColors.Primary
}

/**
 * A badge as a medallion: a metal disc with its drawing, a rim, and a shine at [shine] (−1…1 on
 * each axis; it follows the phone's tilt on the badge page). Locked badges are grey with their
 * progress drawn round the rim; a secret one shows "?".
 */
@Composable
fun BadgeMedallion(a: Achievement, size: Dp, modifier: Modifier = Modifier, shine: Offset = Offset(-0.4f, -0.5f)) {
    val metal = badgeMetal(a)
    val locked = !a.earned
    val primary = RtColors.Primary
    Box(modifier.size(size).semantics { contentDescription = if (locked) "${a.title}, not earned yet" else "${a.title}${a.tier?.let { ", ${it.label}" } ?: ""}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(metal.copy(alpha = if (locked) 0.55f else 1f), lerp(metal, Color.Black, 0.45f)), center, r), r)
            drawCircle(lerp(metal, Color.White, 0.35f).copy(alpha = if (locked) 0.25f else 0.8f), r * 0.86f, style = Stroke(r * 0.05f))
            if (!locked) {
                val at = Offset(center.x + shine.x * r * 0.6f, center.y + shine.y * r * 0.6f)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), at, r * 0.7f), r, center)
            } else if (a.fraction > 0f && !a.secret) {
                drawArc(primary, -90f, 360f * a.fraction, false, style = Stroke(r * 0.09f, cap = StrokeCap.Round), topLeft = Offset(r * 0.05f, r * 0.05f), size = androidx.compose.ui.geometry.Size(r * 1.9f, r * 1.9f))
            }
        }
        if (a.secret && locked) {
            Text("?", color = Color.White.copy(alpha = 0.6f), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
        } else {
            Icon(a.art.icon(), null, tint = if (locked) Color.White.copy(alpha = 0.35f) else lerp(metal, Color.Black, 0.72f), modifier = Modifier.size(size * 0.46f))
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** The phone's tilt as −1…1 on each axis, for the badge's shine. */
@Composable
fun rememberTilt(): Offset {
    val context = LocalContext.current
    var tilt by remember { mutableStateOf(Offset(-0.4f, -0.5f)) }
    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val gravity = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                tilt = Offset((-e.values[0] / 9.81f).coerceIn(-1f, 1f), ((e.values[1] / 9.81f) - 0.7f).coerceIn(-1f, 1f))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (gravity != null) sm.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}
