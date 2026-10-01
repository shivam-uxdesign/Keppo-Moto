package com.ridetrack.app.ui.components

import com.ridetrack.app.ui.theme.LocalRtPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtMotion
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberHaptics
import com.ridetrack.app.ui.theme.rememberReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hold-to-confirm pill: the fill tracks the finger for [RtMotion.HOLD_TO_END] ms, haptic
 * ticks at 50% and on completion; releasing early drains it and nothing happens.
 * Screen readers get a normal click action ([onAccessibleClick]) instead of a hold.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    holdingLabel: String,
    onConfirmed: () -> Unit,
    onAccessibleClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillColor: Color = RtColors.Error.copy(alpha = 0.24f),
) {
    val haptics = rememberHaptics()
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .height(RtDimens.primaryButtonHeight)
            .graphicsLayer {
                val s = 1f - 0.03f * progress.value
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(RtColors.Surface)
            .border(1.dp, RtColors.Hairline, shape)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) {
                    onAccessibleClick()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    holding = true
                    var tickedHalf = false
                    job?.cancel()
                    job = scope.launch {
                        val remaining = ((1f - progress.value) * RtMotion.HOLD_TO_END).toInt()
                        progress.animateTo(1f, tween(remaining.coerceAtLeast(1), easing = LinearEasing)) {
                            if (!tickedHalf && value >= 0.5f) {
                                tickedHalf = true
                                haptics.tick()
                            }
                        }
                        haptics.confirm()
                        onConfirmed()
                    }
                    waitForUpOrCancellation()
                    holding = false
                    if (progress.value < 1f) {
                        job?.cancel()
                        scope.launch { progress.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                .background(fillColor),
        )
        Text(if (holding) holdingLabel else label, style = RtType.button, color = if (enabled) RtColors.TextPrimary else RtColors.TextTertiary)
    }
}

/** Counts a number up from zero once (summary headline only). */
@Composable
fun CountUpText(target: Double, decimals: Int, style: TextStyle, color: Color = RtColors.TextPrimary, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val anim = remember(target) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(target) {
        if (!reduce) anim.animateTo(1f, tween(RtMotion.COUNT_UP, easing = FastOutSlowInEasing))
    }
    val value = target * anim.value
    Text(String.format(java.util.Locale.US, "%.${decimals}f", value), style = style, color = color, modifier = modifier)
}

/** Fades and lifts content in, staggered by [index]. */
fun Modifier.riseIn(index: Int, enabled: Boolean = true): Modifier = composed {
    val reduce = rememberReduceMotion()
    if (!enabled || reduce) return@composed this
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80L * index)
        anim.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * 10.dp.toPx()
    }
}

/** Slow "breathing" glow behind an idle primary action. Off with reduced motion. */
fun Modifier.breathingGlow(color: Color, radius: Dp = 32.dp, enabled: Boolean = true): Modifier = composed {
    val reduce = rememberReduceMotion()
    if (!enabled || reduce) return@composed this
    val t = rememberInfiniteTransition(label = "breathe")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    drawBehind {
        val spread = 8.dp.toPx() * a
        drawRoundRect(
            color = color.copy(alpha = 0.12f * a),
            topLeft = Offset(-spread, -spread),
            size = androidx.compose.ui.geometry.Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx() + spread),
        )
    }
}

/** Loading placeholder with a moving sheen. */
@Composable
fun Shimmer(modifier: Modifier, radius: Dp = 12.dp) {
    val rt = LocalRtPalette.current
    val reduce = rememberReduceMotion()
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sheen")
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .drawBehind {
                val base = rt.surface
                if (reduce) {
                    drawRect(base)
                } else {
                    val w = size.width
                    drawRect(
                        Brush.linearGradient(
                            listOf(base, rt.surfaceRaised, base),
                            start = Offset(w * (x - 0.5f), 0f),
                            end = Offset(w * (x + 0.5f), 0f),
                        ),
                    )
                }
            },
    )
}

/** Check mark that draws itself inside a tinted circle. */
@Composable
fun AnimatedCheck(modifier: Modifier = Modifier, color: Color = RtColors.Primary) {
    val reduce = rememberReduceMotion()
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            kotlinx.coroutines.delay(150)
            p.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
    }
    Canvas(modifier.size(36.dp)) {
        drawCircle(color.copy(alpha = 0.14f))
        val s = size.width / 36f
        val path = Path().apply {
            moveTo(11f * s, 18.5f * s)
            lineTo(15.5f * s, 23f * s)
            lineTo(25f * s, 13.5f * s)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * p.value, partial, true)
        drawPath(partial, color, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Tiny route sketch for list rows, from real GPS points (normalised to fit). */
@Composable
fun RouteThumbnail(points: List<GeoPoint>, modifier: Modifier = Modifier, color: Color = RtColors.Primary) {
    val rt = LocalRtPalette.current
    Canvas(modifier) {
        if (points.size < 2) {
            drawLine(rt.textTertiary, Offset(size.width * 0.3f, size.height / 2), Offset(size.width * 0.7f, size.height / 2), 2.dp.toPx(), cap = StrokeCap.Round)
            return@Canvas
        }
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latScale = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * latScale).coerceAtLeast(1e-9)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
        val pad = 6.dp.toPx()
        val scale = minOf((size.width - pad * 2) / spanX, (size.height - pad * 2) / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = (offX + (p.longitude - minLon) * latScale * scale).toFloat()
            val y = (size.height - offY - (p.latitude - minLat) * scale).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun cos(x: Double) = kotlin.math.cos(x)
