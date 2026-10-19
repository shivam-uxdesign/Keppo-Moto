package com.ridetrack.app.ui.bike

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.common.BikeColors
import com.ridetrack.app.ui.theme.Accent
import com.ridetrack.app.ui.theme.DarkPalette
import com.ridetrack.app.ui.theme.LightPalette
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Any colour for a bike: a hue and strength wheel, a brightness slider, a hex code, quick picks
 * (the 8 route colours and recent picks) and a preview of the app in that colour, dark and light.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BikeColorPicker(argb: Int, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val hsv = remember(argb) { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var hex by remember { mutableStateOf(Accent.hex(argb)) }
    LaunchedEffect(argb) { if (Accent.parseHex(hex) != (argb or (0xFF shl 24))) hex = Accent.hex(argb) }
    fun pick(h: Float, s: Float, v: Float) = onPick(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HueWheel(hsv[0], hsv[1], hsv[2], Modifier.size(168.dp)) { h, s -> pick(h, s, maxOf(hsv[2], 0.2f)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { t ->
                        hex = t.take(7)
                        Accent.parseHex(t)?.let(onPick)
                    },
                    label = { Text("Hex code") },
                    singleLine = true,
                    isError = Accent.parseHex(hex) == null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Outline, cursorColor = RtColors.Primary),
                )
                Text("Brightness", style = RtType.caption, color = RtColors.TextSecondary)
                Slider(
                    value = hsv[2],
                    onValueChange = { v -> pick(hsv[0], hsv[1], v) },
                    valueRange = 0.15f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color(argb), activeTrackColor = Color(argb)),
                    modifier = Modifier.semantics { contentDescription = "Brightness" },
                )
            }
        }
        val quick = remember(argb) { BikeColors.PALETTE.map { it.toArgb() } + RecentColors.load(context).filter { r -> BikeColors.PALETTE.none { it.toArgb() == r } } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.forEach { c ->
                val on = (c or (0xFF shl 24)) == (argb or (0xFF shl 24))
                Box(
                    Modifier
                        .size(32.dp)
                        .border(2.dp, if (on) RtColors.TextPrimary else Color.Transparent, CircleShape)
                        .padding(4.dp)
                        .background(Color(c), CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = Accent.hex(c)) { onPick(c) },
                )
            }
        }
        ColorPreview(argb)
    }
}

/** Hue around the wheel, strength from the centre out; the dot is the current colour. */
@Composable
private fun HueWheel(hue: Float, sat: Float, value: Float, modifier: Modifier, onPick: (Float, Float) -> Unit) {
    val pickNow by androidx.compose.runtime.rememberUpdatedState(onPick)
    fun at(o: Offset, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(cx, cy)
        val dx = o.x - cx
        val dy = o.y - cy
        val angle = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
        pickNow(angle, (hypot(dx, dy) / r).coerceIn(0f, 1f))
    }
    Canvas(
        modifier
            .semantics { contentDescription = "Colour wheel" }
            .pointerInput(Unit) { detectTapGestures { at(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position, size.width.toFloat(), size.height.toFloat()) } },
    ) {
        val r = size.minDimension / 2f
        val hues = (0..12).map { i -> Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 30f % 360f, 1f, value))) }
        drawCircle(Brush.sweepGradient(hues, center), r)
        drawCircle(Brush.radialGradient(listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(0f, 0f, value))), Color.Transparent), center, r), r)
        val rad = Math.toRadians(hue.toDouble())
        val dot = Offset(center.x + (cos(rad) * sat * r).toFloat(), center.y + (sin(rad) * sat * r).toFloat())
        drawCircle(Color.White, 11.dp.toPx(), dot, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.4f), 13.dp.toPx(), dot, style = Stroke(1.dp.toPx()))
    }
}

/** A Start button, a selected tab and a stretch of route, dark and light, plus notes when the colour was adjusted. */
@Composable
private fun ColorPreview(argb: Int) {
    val darkBg = DarkPalette.background.toArgb()
    val lightBg = LightPalette.background.toArgb()
    val onDark = Accent.readable(argb, darkBg)
    val onLight = Accent.readable(argb, lightBg)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(darkBg to onDark, lightBg to onLight).forEach { (bg, accent) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(bg)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(accent)), contentAlignment = Alignment.Center) {
                    Text("Start ride", style = RtType.bodyStrong, color = Color(Accent.onAccent(accent)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rides", style = RtType.caption, color = Color(accent))
                    Box(Modifier.width(24.dp).height(3.dp).background(Color(accent), RoundedCornerShape(2.dp)))
                }
                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                    val w = size.width
                    val h = size.height
                    drawLine(Color(argb), Offset(0f, h * 0.8f), Offset(w * 0.4f, h * 0.3f), 4.dp.toPx(), StrokeCap.Round)
                    drawLine(Color(argb), Offset(w * 0.4f, h * 0.3f), Offset(w, h * 0.6f), 4.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
    val picked = argb or (0xFF shl 24)
    val notes = buildList {
        if (onDark != picked) add("Dark mode uses ${Accent.hex(onDark)} so text stays readable.")
        if (onLight != picked) add("Light mode uses ${Accent.hex(onLight)} so text stays readable.")
        if (closeToRecording(picked)) add("Close to the recording colour. The recording dot still blinks, so it stays easy to tell apart.")
    }
    notes.forEach { Text(it, style = RtType.caption, color = RtColors.TextTertiary) }
}

/** Near the REC red: a strong red hue. */
internal fun closeToRecording(argb: Int): Boolean {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
    return (hsv[0] < 12f || hsv[0] > 348f) && hsv[1] > 0.5f && hsv[2] > 0.5f
}

/** The last few colours picked for any bike, newest first. */
object RecentColors {
    private const val KEY = "recent"
    private fun prefs(c: Context) = c.getSharedPreferences("bike_colors", Context.MODE_PRIVATE)

    fun load(c: Context): List<Int> = prefs(c).getString(KEY, null).orEmpty().split(',').mapNotNull { it.toLongOrNull()?.toInt() }

    fun add(c: Context, argb: Int) {
        val list = (listOf(argb) + load(c).filter { it != argb }).take(6)
        prefs(c).edit().putString(KEY, list.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
    }
}
