package com.ridetrack.app.ui.home

import com.ridetrack.app.ui.theme.RideDark
import com.ridetrack.app.ui.theme.LocalRtPalette
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.ui.components.BikeImage
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberHaptics
import com.ridetrack.app.ui.theme.rememberReduceMotion
import com.ridetrack.telemetry.model.Bike
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Card colours for bikes without a photo, picked by position in the garage. */
private val CardTints = listOf(
    Color(0xFF7A1E2C) to Color(0xFF1A0D10),
    Color(0xFF8A4A0C) to Color(0xFF1B120A),
    Color(0xFF1E5A5C) to Color(0xFF0B1718),
    Color(0xFF3E3A7A) to Color(0xFF111020),
)

/** Card colours by the bike's position in the garage. */
internal fun bikeTint(index: Int) = CardTints[index.mod(CardTints.size)]

private val CardShape = RoundedCornerShape(26.dp)
private val CardHeight = 292.dp
private const val MAX_PEEKS = 2

/**
 * The garage as a wallet: the selected bike is a full card you slide to start a ride on,
 * the others peek out above it. Swipe the card up or down, or tap a tab, to change bikes;
 * tap the card to flip it over to the bike's lifetime stats.
 */
@Composable
fun GarageStack(
    bikes: List<Bike>,
    selected: Bike,
    odometers: Map<String, Double?>,
    lastRidden: Map<String, String>,
    stats: Map<String, BikeStats>,
    starting: Boolean,
    rideActive: Boolean,
    onSelect: (String) -> Unit,
    onStart: (Bike) -> Unit,
    onReturnToRide: () -> Unit,
    onEditBike: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RideDark {
        val index = bikes.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        val peeks = bikes.filter { it.id != selected.id }.take(MAX_PEEKS)
        Column(modifier) {
            peeks.forEachIndexed { i, bike ->
                PeekTab(
                    bike = bike,
                    tint = bikeTint(bikes.indexOf(bike)),
                    odometerKm = odometers[bike.id],
                    // The tab nearest the card is the widest, like cards fanned in a wallet.
                    inset = (MAX_PEEKS - 1 - i + (MAX_PEEKS - peeks.size)) * 8,
                    onClick = { onSelect(bike.id) },
                )
            }
            AnimatedContent(
                targetState = selected,
                contentKey = { it.id },
                transitionSpec = {
                    (fadeIn(tween(320)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it / 10 } +
                        scaleIn(tween(420), initialScale = 0.95f)) togetherWith fadeOut(tween(160))
                },
                label = "frontCard",
            ) { bike ->
                FrontCard(
                    bike = bike,
                    tint = bikeTint(bikes.indexOf(bike)),
                    odometerKm = odometers[bike.id],
                    lastRidden = lastRidden[bike.id],
                    stats = stats[bike.id],
                    starting = starting,
                    rideActive = rideActive,
                    onSwipe = if (bikes.size > 1) { dir -> onSelect(bikes[(index + dir).mod(bikes.size)].id) } else null,
                    onStart = { onStart(bike) },
                    onReturnToRide = onReturnToRide,
                    onEditBike = { onEditBike(bike.id) },
                )
            }
            if (bikes.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    bikes.forEach { b ->
                        val on = b.id == selected.id
                        val w by animateDpAsState(if (on) 18.dp else 6.dp, tween(300), label = "dot")
                        Box(
                            Modifier
                                .size(w, 6.dp)
                                .clip(CircleShape)
                                .background(if (on) RtColors.TextPrimary else Color.White.copy(alpha = 0.2f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeekTab(bike: Bike, tint: Pair<Color, Color>, odometerKm: Double?, inset: Int, onClick: () -> Unit) {
    val overlap = 18.dp
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Row(
        Modifier
            .padding(horizontal = inset.dp)
            .fillMaxWidth()
            // Tuck the bottom of the tab under the card below it.
            .layout { m, c ->
                val p = m.measure(c)
                layout(p.width, p.height - overlap.roundToPx()) { p.place(0, 0) }
            }
            .height(56.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(tint.first, tint.second)))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(role = Role.Button, onClickLabel = "Bring to front", onClick = onClick)
            .graphicsLayer { alpha = 0.85f }
            .padding(start = 20.dp, end = 20.dp, bottom = overlap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(bike.displayName, style = RtType.bodyStrong.copy(fontSize = 14.sp), color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            odometerKm?.let { "${odometerText(it)} km" } ?: "Set odometer",
            style = RtType.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = if (odometerKm != null) RtColors.TextPrimary.copy(alpha = 0.7f) else RtColors.Primary,
        )
    }
}

@Composable
private fun FrontCard(
    bike: Bike,
    tint: Pair<Color, Color>,
    odometerKm: Double?,
    lastRidden: String?,
    stats: BikeStats?,
    starting: Boolean,
    rideActive: Boolean,
    /** Next (+1) or previous (-1) bike; null with only one bike. */
    onSwipe: ((Int) -> Unit)?,
    onStart: () -> Unit,
    onReturnToRide: () -> Unit,
    onEditBike: () -> Unit,
) {
    val rt = LocalRtPalette.current
    val reduce = rememberReduceMotion()
    val motion = rememberInfiniteTransition(label = "card")
    // A slow drift over the photo; it zooms in as the ride starts.
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val startZoom by animateFloatAsState(if (starting) 1f else 0f, tween(1_400), label = "zoom")
    // An idling-engine shiver while starting.
    val idle by motion.animateFloat(-1f, 1f, infiniteRepeatable(tween(90, easing = LinearEasing), RepeatMode.Reverse), label = "idle")
    val glow by animateFloatAsState(if (starting) 1f else 0f, tween(300), label = "glow")
    // Each bike's card starts face up, so changing bike always shows the front.
    var flipped by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (flipped && !starting) 180f else 0f, tween(if (reduce) 0 else FLIP_MS, easing = FastOutSlowInEasing), label = "flip")
    val swipe = onSwipe?.let { f ->
        Modifier.pointerInput(bike.id) {
            var dragY = 0f
            detectVerticalDragGestures(
                onDragStart = { dragY = 0f },
                onVerticalDrag = { change, d -> dragY += d; change.consume() },
                onDragEnd = { if (abs(dragY) > 50.dp.toPx()) f(if (dragY < 0) 1 else -1) },
            )
        }
    } ?: Modifier
    val flip = Modifier.clickable(role = Role.Button, onClickLabel = if (flipped) "Show the card" else "Show bike stats") { flipped = !flipped }
    val cardFrame = Modifier
        .fillMaxWidth()
        .height(CardHeight)
        .graphicsLayer {
            rotationY = if (turn > 90f) turn - 180f else turn
            cameraDistance = 14f * density
        }

    if (turn > 90f && stats != null) {
        BikeStatsFace(bike, tint, stats, modifier = cardFrame.then(swipe).then(flip))
        return
    }
    BikeCardFace(
        bike = bike,
        tint = tint,
        odometerKm = odometerKm,
        lastRidden = lastRidden,
        onEditOdometer = onEditBike,
        drift = drift,
        startZoom = startZoom,
        border = BorderStroke(if (glow > 0f) 2.dp else 1.dp, if (glow > 0f) RtColors.Primary else Color.White.copy(alpha = 0.10f)),
        modifier = cardFrame
            .graphicsLayer { if (starting && !reduce) translationX = idle * 0.8f }
            .drawBehind {
                if (glow > 0f) {
                    drawRoundRect(
                        rt.primary.copy(alpha = 0.35f * glow),
                        topLeft = Offset(-6.dp.toPx(), -6.dp.toPx()),
                        size = size.copy(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(32.dp.toPx()),
                    )
                }
            },
        contentModifier = swipe.then(flip),
    ) {
        if (rideActive) {
            ReturnToRide(onReturnToRide)
        } else {
            SlideToRide(
                label = if (starting) "Starting on ${bike.displayName}…" else "Ride this bike",
                bikeName = bike.displayName,
                enabled = !starting,
                onComplete = onStart,
            )
        }
    }
}

/**
 * A bike as a card: its photo (slowly drifting) or a tinted card with a motorcycle, the
 * make, year and last-ridden tag, the name and the odometer; [footer] sits at the bottom.
 * Shared by the home garage and the Bikes tab.
 */
@Composable
internal fun BikeCardFace(
    bike: Bike,
    tint: Pair<Color, Color>,
    odometerKm: Double?,
    lastRidden: String?,
    onEditOdometer: () -> Unit,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    drift: Float = 0f,
    startZoom: Float = 0f,
    border: BorderStroke = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    RideDark {
        val reduce = rememberReduceMotion()
        Box(
            modifier
                .clip(CardShape)
                .background(Brush.radialGradient(listOf(tint.first, tint.second), center = Offset.Unspecified))
                .border(border, CardShape)
                .then(contentModifier),
        ) {
            if (bike.photoFile != null) {
                BikeImage(
                    bike.photoFile,
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val s = if (reduce) 1f else 1.02f + 0.08f * drift + 0.12f * startZoom
                            scaleX = s
                            scaleY = s
                            if (!reduce) translationX = -size.width * 0.02f * drift
                        },
                )
                // Darken top and bottom so the name, odometer and footer stay readable on any photo.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0xC70A0A0B),
                                0.32f to Color(0x400A0A0B),
                                0.5f to Color.Transparent,
                                0.68f to Color(0x260A0A0B),
                                1f to Color(0xCC0A0A0B),
                            ),
                        ),
                )
            } else {
                Icon(
                    Icons.Outlined.TwoWheeler,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(132.dp),
                )
            }

            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val sub = listOfNotNull(bike.make.takeIf { it.isNotBlank() && bike.model.isNotBlank() }, bike.year?.toString()).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = RtType.caption, color = RtColors.TextPrimary.copy(alpha = 0.7f), maxLines = 1)
                            if (lastRidden != null) {
                                Text(
                                    lastRidden,
                                    style = RtType.caption.copy(fontSize = 11.sp),
                                    color = RtColors.TextPrimary,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.14f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            bike.model.ifBlank { bike.displayName },
                            style = RtType.headline,
                            color = RtColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    OdometerReadout(odometerKm, onEditOdometer)
                }
                Spacer(Modifier.weight(1f))
                footer()
            }
        }
    }
}

/** The back of the card: the bike's lifetime numbers. */
@Composable
private fun BikeStatsFace(bike: Bike, tint: Pair<Color, Color>, stats: BikeStats, modifier: Modifier) {
    Column(
        modifier
            .clip(CardShape)
            .background(Brush.radialGradient(listOf(tint.first, tint.second), center = Offset.Unspecified))
            .background(Color(0x990A0A0B))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CardShape)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val sub = listOfNotNull(bike.make.takeIf { it.isNotBlank() && bike.model.isNotBlank() }, bike.year?.toString(), BikeStats.sinceLabel(stats.sinceMillis)).joinToString(" · ")
                Text(sub, style = RtType.caption, color = RtColors.TextPrimary.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(bike.model.ifBlank { bike.displayName }, style = RtType.headline, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("BIKE STATS", style = RtType.caption.copy(fontSize = 10.sp, letterSpacing = 1.sp), color = RtColors.TextSecondary, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.height(14.dp))
        val rows = listOf(
            listOf(
                Triple("Rides", "${stats.rides}", RtColors.TextPrimary),
                Triple("Riding time", Format.duration(stats.ridingMillis), RtColors.TextPrimary),
                Triple("Distance", Format.distance(stats.distanceM), RtColors.TextPrimary),
            ),
            listOf(
                Triple("Top speed", stats.topSpeedMps?.let { Format.speedWithUnit(it) } ?: Format.DASH, RtColors.GForce),
                Triple("Lean left", stats.maxLeftLeanDeg?.let { "${it.roundToInt()}°" } ?: Format.DASH, RtColors.Left),
                Triple("Lean right", stats.maxRightLeanDeg?.let { "${it.roundToInt()}°" } ?: Format.DASH, RtColors.Right),
            ),
            listOf(
                Triple("Hardest brake", stats.hardestBrakeG?.let { String.format(Locale.US, "%.2f G", it) } ?: Format.DASH, RtColors.Brake),
                Triple("Longest ride", stats.longestRideM?.let { Format.distance(it) } ?: Format.DASH, RtColors.TextPrimary),
                Triple("Moments", "${stats.moments}", RtColors.TextPrimary),
            ),
        )
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row {
                row.forEach { (label, value, color) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(label, style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextSecondary, maxLines = 1)
                        Text(value, style = RtType.bodyStrong.copy(fontSize = 18.sp, fontWeight = FontWeight.Normal), color = color, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Tap to flip back", style = RtType.caption.copy(fontSize = 11.sp), color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.End))
    }
}

private const val FLIP_MS = 650

private fun odometerText(km: Double): String = String.format(Locale.US, "%,d", km.roundToLong())

@Composable
private fun OdometerReadout(km: Double?, onEditBike: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = if (km == null) {
            Modifier.clickable(role = Role.Button, onClickLabel = "Set odometer", onClick = onEditBike)
        } else {
            Modifier
        },
    ) {
        Text("ODOMETER", style = RtType.label.copy(fontSize = 10.sp), color = RtColors.TextPrimary.copy(alpha = 0.6f))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                km?.let(::odometerText) ?: Format.DASH,
                style = RtType.metricM.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light),
                color = RtColors.TextPrimary,
            )
            Text(
                if (km != null) "km" else "tap to set",
                style = RtType.caption,
                color = if (km != null) RtColors.TextPrimary.copy(alpha = 0.7f) else RtColors.Primary,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

/**
 * Drag the thumb to the end to start. A deliberate gesture, so a pocket or a glove can't
 * start a ride; screen readers get a plain click action instead.
 */
@Composable
private fun SlideToRide(label: String, bikeName: String, enabled: Boolean, onComplete: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val reduce = rememberReduceMotion()
    val offset = remember { Animatable(0f) }
    LaunchedEffect(enabled) { if (enabled) offset.animateTo(0f, spring(dampingRatio = 0.55f)) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(Color(0x8C0A0A0B))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .semantics {
                contentDescription = "Slide to start a ride on $bikeName"
                onClick(label = "Start ride") {
                    if (enabled) onComplete()
                    true
                }
            },
    ) {
        val thumb = 48.dp
        val maxPx = with(LocalDensity.current) { (maxWidth - thumb - 8.dp).toPx() }
        val fraction = if (maxPx > 0f) offset.value / maxPx else 0f

        // A sheen runs across the label to invite the swipe.
        val sheen = rememberInfiniteTransition(label = "sheen")
        val x by sheen.animateFloat(-160f, 480f, infiniteRepeatable(tween(2_600, easing = LinearEasing)), label = "sheenX")
        val brush = if (reduce || !enabled) {
            Brush.linearGradient(listOf(RtColors.TextPrimary, RtColors.TextPrimary))
        } else {
            Brush.linearGradient(
                listOf(RtColors.TextPrimary.copy(alpha = 0.5f), Color.White, RtColors.TextPrimary.copy(alpha = 0.5f)),
                start = Offset(x, 0f),
                end = Offset(x + 160f, 0f),
            )
        }
        Text(
            label,
            style = RtType.body.copy(brush = brush),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = 60.dp, end = 16.dp)
                .graphicsLayer { alpha = if (enabled) (1f - fraction * 1.8f).coerceIn(0f, 1f) else 1f },
        )
        Box(
            Modifier
                .offset { IntOffset((if (enabled) offset.value else maxPx).roundToInt(), 0) }
                .padding(4.dp)
                .size(thumb)
                .clip(CircleShape)
                .background(RtColors.TextPrimary)
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, maxPx)) } },
                    onDragStopped = {
                        if (offset.value >= maxPx * 0.85f) {
                            haptics.confirm()
                            offset.animateTo(maxPx, tween(120))
                            onComplete()
                        } else {
                            offset.animateTo(0f, spring(dampingRatio = 0.5f))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = RtColors.OnInverse, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ReturnToRide(onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(RtColors.Primary)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(RtColors.Error))
        Spacer(Modifier.width(10.dp))
        Text("Ride in progress · Return", style = RtType.bodyStrong, color = RtColors.OnPrimary)
    }
}
