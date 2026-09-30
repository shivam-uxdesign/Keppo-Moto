package com.ridetrack.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** A marker on the chart at [fraction] of the ride: events along the bottom, moments along the top. */
data class ChartMark(val fraction: Float, val color: Color, val top: Boolean = false)

/** Visible part of the ride, as fractions 0..1. */
data class ChartWindow(val start: Float = 0f, val end: Float = 1f) {
    val span: Float get() = end - start
    val isZoomed: Boolean get() = span < 0.999f
    fun contains(f: Float) = f in start..end

    /** Zoom by [factor] (>1 = in) keeping the point at [anchor] (0..1 across the view) still. */
    fun zoom(factor: Float, anchor: Float, minSpan: Float): ChartWindow {
        val at = start + anchor * span
        val newSpan = (span / factor).coerceIn(minSpan.coerceAtMost(1f), 1f)
        return of(at - anchor * newSpan, newSpan)
    }

    /** Shift by [delta] of the whole ride. */
    fun pan(delta: Float): ChartWindow = of(start + delta, span)

    /** The same span, moved just enough to include [f]. */
    fun follow(f: Float): ChartWindow = when {
        !isZoomed || contains(f) -> this
        else -> of(f - span * 0.2f, span)
    }

    companion object {
        val Full = ChartWindow()
        fun of(start: Float, span: Float): ChartWindow {
            val s = span.coerceIn(0f, 1f)
            val st = start.coerceIn(0f, 1f - s)
            return ChartWindow(st, st + s)
        }
        /** About [spanFraction] of the ride centred on [f]. */
        fun around(f: Float, spanFraction: Float): ChartWindow = of(f - spanFraction / 2, spanFraction)
    }
}

/**
 * Line chart you can zoom: pinch or double-tap to zoom, two fingers to pan, one finger to
 * scrub. Vertical swipes pass through to the page scroll.
 */
@Composable
fun ZoomChart(
    series: ChartSeries,
    color: Color,
    scrub: Float?,
    window: ChartWindow,
    onScrub: (Float) -> Unit,
    onWindow: (ChartWindow) -> Unit,
    minSpan: Float,
    modifier: Modifier = Modifier,
    negativeColor: Color? = null,
    marks: List<ChartMark> = emptyList(),
    unavailableText: String = "Unavailable for this ride",
    height: Dp = 120.dp,
) {
    if (!series.hasData) {
        Text(unavailableText, style = RtType.caption, color = RtColors.TextSecondary, modifier = modifier.height(height))
        return
    }
    val win by rememberUpdatedState(window)
    val scrubTo by rememberUpdatedState(onScrub)
    val setWindow by rememberUpdatedState(onWindow)
    val min by rememberUpdatedState(minSpan)
    val lastTap = remember { longArrayOf(0L) }
    val path = remember { Path() }
    val area = remember { Path() }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "Ride chart. Drag to scrub, pinch or double-tap to zoom." }
            .pointerInput(Unit) {
                fun fractionAt(x: Float) = (win.start + (x / size.width).coerceIn(0f, 1f) * win.span).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var scrubbing = false
                    var pinching = false
                    var abandoned = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            pinching = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val anchor = (centroid.x / size.width).coerceIn(0f, 1f)
                            val w = win.zoom(zoom, anchor, min).let { it.pan(-pan.x / size.width * it.span) }
                            setWindow(w)
                            event.changes.forEach { it.consume() }
                        } else if (!pinching && !abandoned) {
                            val c = pressed[0]
                            val d = c.position - start
                            if (!scrubbing && (abs(d.x) > viewConfiguration.touchSlop || abs(d.y) > viewConfiguration.touchSlop)) {
                                // Mostly vertical: let the page scroll instead.
                                if (abs(d.y) > abs(d.x)) abandoned = true else scrubbing = true
                            }
                            if (scrubbing) {
                                scrubTo(fractionAt(c.position.x))
                                c.consume()
                            }
                        }
                    }
                    if (!scrubbing && !pinching && !abandoned) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap[0] < 300) {
                            lastTap[0] = 0
                            val anchor = (start.x / size.width).coerceIn(0f, 1f)
                            setWindow(if (win.span <= min * 3) ChartWindow.Full else win.zoom(3f, anchor, min))
                        } else {
                            lastTap[0] = now
                            scrubTo(fractionAt(start.x))
                        }
                    }
                }
            },
    ) {
        val v = series.values
        if (v.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val top = 8.dp.toPx()
        val bottom = h - 9.dp.toPx()
        fun y(value: Float) = bottom - (value - series.min) / (series.max - series.min) * (bottom - top)
        fun x(i: Int) = ((i.toFloat() / (v.size - 1)) - window.start) / window.span * w
        val zeroY = if (series.min < 0f && series.max > 0f) y(0f) else bottom
        if (series.min < 0f && series.max > 0f) drawLine(RtColors.Outline, Offset(0f, zeroY), Offset(w, zeroY), 1.dp.toPx())

        val first = floor(window.start * (v.size - 1)).toInt().coerceIn(0, v.size - 1)
        val last = ceil(window.end * (v.size - 1)).toInt().coerceIn(0, v.size - 1)
        path.reset()
        area.reset()
        var pen = false
        var runStart = 0f
        var lastX = 0f
        for (i in first..last) {
            val value = v[i]
            if (value.isNaN()) {
                if (pen) { area.lineTo(lastX, zeroY); area.lineTo(runStart, zeroY); area.close() }
                pen = false
                continue
            }
            val px = x(i)
            val py = y(value)
            if (pen) {
                path.lineTo(px, py)
                area.lineTo(px, py)
            } else {
                path.moveTo(px, py)
                area.moveTo(px, zeroY)
                area.lineTo(px, py)
                runStart = px
            }
            lastX = px
            pen = true
        }
        if (pen) { area.lineTo(lastX, zeroY); area.lineTo(runStart, zeroY); area.close() }
        val split = (zeroY / h).coerceIn(0f, 1f)
        val lineBrush = if (negativeColor != null && series.min < 0f) {
            Brush.verticalGradient(0f to color, split to color, split to negativeColor, 1f to negativeColor)
        } else {
            Brush.linearGradient(listOf(color, color))
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f)), startY = top, endY = bottom))
        drawPath(path, lineBrush, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        marks.forEach { m ->
            if (!window.contains(m.fraction)) return@forEach
            val mx = (m.fraction - window.start) / window.span * w
            if (m.top) {
                drawCircle(m.color, 3.dp.toPx(), Offset(mx, 4.dp.toPx()))
            } else {
                val s = 4.dp.toPx()
                drawPath(Path().apply { moveTo(mx - s, h); lineTo(mx, h - 7.dp.toPx()); lineTo(mx + s, h); close() }, m.color)
            }
        }

        if (scrub != null && window.contains(scrub)) {
            val cx = (scrub - window.start) / window.span * w
            drawLine(RtColors.TextPrimary.copy(alpha = 0.7f), Offset(cx, 0f), Offset(cx, h), 1.dp.toPx())
            val idx = (scrub * (v.size - 1)).toInt().coerceIn(0, v.size - 1)
            if (!v[idx].isNaN()) {
                drawCircle(RtColors.Background, 6.dp.toPx(), Offset(cx, y(v[idx])))
                drawCircle(color, 4.dp.toPx(), Offset(cx, y(v[idx])))
            }
        }
    }
}

/**
 * The whole ride in miniature with the chart's zoom window on it: drag the window to move
 * along the ride, drag its edges to zoom, tap elsewhere to jump the window there.
 */
@Composable
fun OverviewStrip(
    series: ChartSeries,
    window: ChartWindow,
    scrub: Float?,
    onWindow: (ChartWindow) -> Unit,
    minSpan: Float,
    modifier: Modifier = Modifier,
    color: Color = RtColors.Primary,
) {
    val win by rememberUpdatedState(window)
    val setWindow by rememberUpdatedState(onWindow)
    val min by rememberUpdatedState(minSpan)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = "Whole ride overview. Drag the window to move the zoomed chart." }
            .pointerInput(Unit) {
                val edge = 14.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width
                    val f0 = down.position.x / w
                    val x0 = win.start * w
                    val x1 = win.end * w
                    val mode = when {
                        abs(down.position.x - x0) < edge -> 'l'
                        abs(down.position.x - x1) < edge -> 'r'
                        down.position.x in x0..x1 -> 'm'
                        else -> {
                            setWindow(ChartWindow.around(f0, win.span))
                            'm'
                        }
                    }
                    val offset = f0 - win.start
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val f = (c.position.x / w).coerceIn(0f, 1f)
                        setWindow(
                            when (mode) {
                                'l' -> ChartWindow(f.coerceAtMost(win.end - min), win.end)
                                'r' -> ChartWindow(win.start, f.coerceAtLeast(win.start + min))
                                else -> ChartWindow.of(f - offset, win.span)
                            },
                        )
                        c.consume()
                    }
                }
            },
    ) {
        drawRoundRect(RtColors.Surface, cornerRadius = CornerRadius(10.dp.toPx()))
        drawSpark(series, color.copy(alpha = 0.5f))
        val w = size.width
        val h = size.height
        val x0 = window.start * w
        val x1 = window.end * w
        drawRect(RtColors.Background.copy(alpha = 0.6f), Offset.Zero, Size(x0, h))
        drawRect(RtColors.Background.copy(alpha = 0.6f), Offset(x1, 0f), Size(w - x1, h))
        drawRoundRect(color, Offset(x0, 1f), Size(x1 - x0, h - 2f), CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        val hw = 5.dp.toPx()
        val hh = 16.dp.toPx()
        drawRoundRect(color, Offset(x0 - hw / 2, (h - hh) / 2), Size(hw, hh), CornerRadius(hw / 2))
        drawRoundRect(color, Offset(x1 - hw / 2, (h - hh) / 2), Size(hw, hh), CornerRadius(hw / 2))
        if (scrub != null) drawLine(RtColors.TextPrimary, Offset(scrub * w, 3f), Offset(scrub * w, h - 3f), 1.2.dp.toPx())
    }
}

private fun DrawScope.drawSpark(series: ChartSeries, color: Color) {
    val v = series.values
    if (v.size < 2) return
    val step = maxOf(1, v.size / 400)
    val p = Path()
    var pen = false
    val pad = 4.dp.toPx()
    for (i in v.indices step step) {
        val value = v[i]
        if (value.isNaN()) { pen = false; continue }
        val px = i.toFloat() / (v.size - 1) * size.width
        val py = size.height - pad - (value - series.min) / (series.max - series.min) * (size.height - 2 * pad)
        if (pen) p.lineTo(px, py) else p.moveTo(px, py)
        pen = true
    }
    drawPath(p, color, style = Stroke(1.2.dp.toPx()))
}
