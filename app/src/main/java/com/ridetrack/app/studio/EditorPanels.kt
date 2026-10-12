package com.ridetrack.app.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlin.math.roundToInt

/*
 * The editor's tool panels (they take the place of the tool bar) and the quick sliders on the
 * video's edge. A slider shows its change straight away and keeps it as one undo step on letting go.
 */

/** A panel's head: back to the tools, its name and its value. */
@Composable
private fun PanelHead(title: String, value: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "Back to the clip's tools", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Back", tint = RtColors.TextPrimary) }
        Text(title, style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = RtType.bodyStrong, color = RtColors.Primary) }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = RtColors.Primary, inactiveTrackColor = RtColors.SurfaceRaised)

/** A round choice with a word under it (presets, adjustments, curves). */
@Composable
private fun RoundChoice(label: String, on: Boolean, onClick: () -> Unit, face: @Composable () -> Unit) {
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).then(if (on) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { face() }
        Text(label, style = RtType.caption, color = if (on) RtColors.Primary else RtColors.TextPrimary, maxLines = 1, textAlign = TextAlign.Center)
    }
}

// ---- quick sliders on the video's edge ---------------------------------------------------------

/**
 * A tall thin slider (Procreate's): drag up or down anywhere on it. [value] 0..1; [onChange]
 * while dragging, [onEnd] on letting go.
 */
@Composable
internal fun EdgeSlider(value: Float, label: String, modifier: Modifier, onChange: (Float) -> Unit, onEnd: () -> Unit) {
    val now by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    var start by remember { mutableFloatStateOf(0f) }
    var pulled by remember { mutableFloatStateOf(0f) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.width(30.dp).weight(1f).clip(RoundedCornerShape(15.dp)).background(Color.Black.copy(alpha = 0.45f))
                .semantics { contentDescription = label }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { start = now; pulled = 0f },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                    ) { ch, dy ->
                        ch.consume()
                        pulled -= dy
                        change((start + pulled / size.height).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0.04f, 1f)).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.85f)))
        }
        Text(label, style = RtType.caption, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp))
    }
}

/** Speed on the edge slider: 0..1 across [Speed.CHOICES] and back. */
internal fun speedToSlider(v: Float): Float = Speed.CHOICES.indexOfFirst { it >= v - 0.01f }.coerceAtLeast(0).toFloat() / (Speed.CHOICES.size - 1)
internal fun sliderToSpeed(f: Float): Float = Speed.CHOICES[(f * (Speed.CHOICES.size - 1)).roundToInt().coerceIn(0, Speed.CHOICES.size - 1)]

internal fun speedLabel(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}×" else "${v}×"

// ---- speed ---------------------------------------------------------------------------------------

/** The clip's speed: a slider over the steps, its curve (drawn), Reverse, Freeze here and Mute. */
@Composable
internal fun SpeedPanel(
    seg: ClipSegment,
    onSpeed: (Float) -> Unit,
    onSpeedEnd: () -> Unit,
    onRamp: (SpeedRamp) -> Unit,
    onReverse: () -> Unit,
    onFreeze: () -> Unit,
    onMute: () -> Unit,
    onBack: () -> Unit,
) {
    val n = Speed.CHOICES.size
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelHead("Speed", speedLabel(seg.speed), onBack)
        Slider(
            value = Speed.CHOICES.indexOf(seg.speed).coerceAtLeast(0).toFloat(),
            onValueChange = { onSpeed(Speed.CHOICES[it.roundToInt().coerceIn(0, n - 1)]) },
            onValueChangeFinished = onSpeedEnd,
            valueRange = 0f..(n - 1).toFloat(),
            steps = n - 2,
            colors = sliderColors(),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SpeedRamp.entries.forEach { r ->
                RoundChoice(RAMP_NAMES[r] ?: r.label, seg.ramp == r, { onRamp(r) }) {
                    Box(Modifier.size(48.dp).background(RtColors.SurfaceRaised)) { RampCurve(r, Modifier.padding(10.dp).size(28.dp)) }
                }
            }
            Spacer(Modifier.width(8.dp))
            PanelChip(if (seg.reverse != null) "Forwards" else "Reverse", seg.reverse != null, onReverse)
            PanelChip("Freeze here", false, onFreeze)
            PanelChip(if (seg.volume == 0f) "Unmute" else "Mute", seg.volume == 0f, onMute)
        }
    }
}

private val RAMP_NAMES = mapOf(SpeedRamp.NONE to "Steady", SpeedRamp.SPEED_UP to "Speed up", SpeedRamp.EASE_OUT to "Slow down", SpeedRamp.MIDDLE to "Bullet")

/** The ramp as a line: higher is faster. */
@Composable
private fun RampCurve(r: SpeedRamp, modifier: Modifier) {
    Canvas(modifier) {
        val top = r.steps.maxOf { it.second }.coerceAtLeast(1f)
        val path = Path()
        var x0 = 0f
        r.steps.forEachIndexed { k, (end, mult) ->
            val y = size.height * (1f - mult / top * 0.8f)
            if (k == 0) path.moveTo(0f, y) else path.lineTo(x0 * size.width, y)
            path.lineTo(end * size.width, y)
            x0 = end
        }
        drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color.White, 2.dp.toPx(), Offset(size.width, size.height * (1f - r.steps.last().second / top * 0.8f)))
    }
}

@Composable
private fun PanelChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = RtType.button,
        color = if (on) RtColors.Primary else RtColors.TextPrimary,
        modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

// ---- look -----------------------------------------------------------------------------------------

/** One-tap looks for a clip (on top of, or instead of, the style's own). */
internal val LOOK_PRESETS: List<Pair<String, ClipColor>> = listOf(
    "Style's" to ClipColor(),
    "Punchy" to ClipColor(contrast = 0.3f, saturation = 0.3f),
    "Warm" to ClipColor(exposure = 0.05f, warmth = 0.4f),
    "Cool" to ClipColor(warmth = -0.4f, saturation = -0.1f),
    "Mono" to ClipColor(contrast = 0.15f, saturation = -1f),
    "Dusk" to ClipColor(exposure = -0.15f, contrast = 0.1f, saturation = -0.1f, warmth = 0.25f),
)

private val PRESET_TINT = listOf(Color(0xFF3A3A40), Color(0xFFE0533D), Color(0xFFE8A048), Color(0xFF4F8FD8), Color(0xFF9A9AA0), Color(0xFF8B5A7A))

/** Which adjustment the dial is on. */
internal enum class Adjust(val label: String, val get: (ClipColor) -> Float, val set: (ClipColor, Float) -> ClipColor) {
    LIGHT("Light", { it.exposure }, { c, v -> c.copy(exposure = v) }),
    CONTRAST("Contrast", { it.contrast }, { c, v -> c.copy(contrast = v) }),
    SATURATION("Saturation", { it.saturation }, { c, v -> c.copy(saturation = v) }),
    WARMTH("Warmth", { it.warmth }, { c, v -> c.copy(warmth = v) }),
}

/**
 * The clip's look, like Photos: presets in a row, then one adjustment at a time on a dial (the
 * others as round buttons), the style's grade on or off, and Reset.
 */
@Composable
internal fun LookPanel(
    c: ClipColor,
    onColor: (ClipColor) -> Unit,
    onColorEnd: (String) -> Unit,
    onPreset: (String, ClipColor) -> Unit,
    onBack: () -> Unit,
) {
    var adj by remember { mutableStateOf(Adjust.SATURATION) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelHead("Look", "${adj.label.uppercase()} ${signed(adj.get(c))}", onBack)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            LOOK_PRESETS.forEachIndexed { k, (name, preset) ->
                val on = c.copy(look = true) == preset.copy(look = true)
                RoundChoice(name, on, { onPreset(name, preset.copy(look = c.look)) }) { Box(Modifier.size(48.dp).background(PRESET_TINT[k])) }
            }
            RoundChoice(if (c.look) "Style on" else "Style off", c.look, { onPreset(if (c.look) "Style look off" else "Style look on", c.copy(look = !c.look)) }) {
                Box(Modifier.size(48.dp).background(if (c.look) RtColors.Primary.copy(alpha = 0.35f) else RtColors.SurfaceRaised), contentAlignment = Alignment.Center) {
                    Text("✦", style = RtType.bodyStrong, color = Color.White)
                }
            }
        }
        Slider(
            value = adj.get(c),
            onValueChange = { v -> onColor(adj.set(c, (v * 20).roundToInt() / 20f)) },
            onValueChangeFinished = { onColorEnd(adj.label) },
            valueRange = -1f..1f,
            colors = sliderColors(),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Adjust.entries.forEach { a ->
                RoundChoice(a.label, a == adj, { adj = a }) {
                    Box(Modifier.size(48.dp).background(RtColors.SurfaceRaised), contentAlignment = Alignment.Center) {
                        Text(signed(a.get(c)), style = RtType.caption, color = if (a.get(c) == 0f) RtColors.TextSecondary else Color.White)
                    }
                }
            }
        }
    }
}

private fun signed(v: Float): String = (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }

// ---- crop -----------------------------------------------------------------------------------------

/** Cropping happens on the video (pinch, drag, twist); here: turn, mirror, slow push-in, words punch-in and Reset. */
@Composable
internal fun CropPanel(
    seg: ClipSegment,
    onRotate: () -> Unit,
    onMirror: () -> Unit,
    onPushIn: () -> Unit,
    onPunch: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val zoom = seg.frame.maxOfOrNull { it.zoom } ?: 1f
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelHead("Crop", if (zoom > 1.001f) String.format(java.util.Locale.US, "%.2f×", zoom) else null, onBack)
        Text("Pinch the video to crop in, drag to move it, twist to turn it.", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelChip("Rotate", seg.rotation != 0, onRotate)
            PanelChip("Mirror", seg.flip, onMirror)
            PanelChip("Slow push-in", seg.frame.size > 1, onPushIn)
            if (seg.lines.isNotEmpty()) PanelChip("Punch in on words", false, onPunch)
            PanelChip("Reset", false, onReset)
        }
    }
}
