package com.ridetrack.app.ui.live

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.ridetrack.app.ui.theme.rememberHaptics
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.data.LiveMetric
import com.ridetrack.app.hud.CameraIndicator
import com.ridetrack.app.hud.OverlayPermission
import com.ridetrack.app.moments.MomentStatus
import com.ridetrack.app.moments.PauseReason
import com.ridetrack.app.ui.hud.CameraBadge
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.Chip
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.GForceIndicator
import com.ridetrack.app.ui.components.HairlineDivider
import com.ridetrack.app.ui.components.HoldToConfirmButton
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.LeanArc
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.StatBlock
import com.ridetrack.app.ui.components.leanColor
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberReduceMotion
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.GpsQuality
import com.ridetrack.telemetry.model.LeanConfidence
import com.ridetrack.telemetry.model.TelemetryFrame
import com.ridetrack.telemetry.state.RideError
import com.ridetrack.telemetry.state.RideState

@Composable
fun LiveRideScreen(onRideSaved: (String) -> Unit, onExit: () -> Unit) {
    val vm = appViewModel { LiveRideViewModel(it) }
    val chrome by vm.chrome.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val state = chrome.rideState

    KeepScreenOn()
    // Back leaves the screen but never ends the ride.
    BackHandler { onExit() }

    LaunchedEffect(state) {
        when (state) {
            is RideState.RideComplete -> {
                vm.acknowledge()
                onRideSaved(state.rideId)
            }
            RideState.Idle, RideState.PreRideCheck, RideState.Ready -> onExit()
            else -> Unit
        }
    }

    if (state is RideState.Error) {
        ErrorContent(state.error, onDone = { vm.acknowledge(); onExit() })
        return
    }

    val canEnd = state is RideState.Recording || state is RideState.Paused
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(RtColors.LiveBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = RtDimens.screenPaddingWide),
    ) {
        val landscape = maxWidth > maxHeight && maxWidth > 560.dp
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f).fillMaxSize()) {
                    TopBar(chrome, frame)
                    Banners(chrome, frame, vm)
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) { SpeedAndLean(frame, chrome) }
                }
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    MetricGrid(frame, chrome)
                    Spacer(Modifier.height(20.dp))
                    EndControl(canEnd, vm)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(chrome, frame)
                Banners(chrome, frame, vm)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.height(24.dp))
                    SpeedAndLean(frame, chrome)
                    Spacer(Modifier.height(24.dp))
                }
                MetricGrid(frame, chrome)
                Spacer(Modifier.height(24.dp))
                EndControl(canEnd, vm)
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Reached via the accessibility action of the hold button; a normal confirmation.
    if (state is RideState.EndingRide) {
        AlertDialog(
            onDismissRequest = vm::cancelEnd,
            title = { Text("End ride?", style = RtType.headline) },
            text = { Text("Recording will stop and the ride will be saved.", style = RtType.body) },
            confirmButton = { TextButton(onClick = vm::confirmEnd) { Text("End & save", color = RtColors.Error) } },
            dismissButton = { TextButton(onClick = vm::cancelEnd) { Text("Cancel", color = RtColors.TextPrimary) } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
    if (state is RideState.Saving) SavingOverlay()

    // Explain the pop-up once, the first time a ride runs without the overlay permission.
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(OverlayPermission.isGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { overlayGranted = OverlayPermission.isGranted(context) }
    if ((state is RideState.Recording || state is RideState.Paused) && chrome.hudEnabled && !chrome.hudPromptDismissed && !overlayGranted) {
        HudPermissionSheet(onContinue = { OverlayPermission.request(context) }, onNotNow = vm::dismissHudPrompt)
    }
}

@Composable
private fun Banners(chrome: LiveChrome, frame: TelemetryFrame?, vm: LiveRideViewModel) {
    GpsWarning(chrome, frame, onRetry = vm::retryGps)
    val canCalibrate = chrome.active?.sensors?.canEstimateLean == true &&
        (chrome.rideState is RideState.Recording || chrome.rideState is RideState.Paused)
    if (canCalibrate) CalibrateOffer(frame, onCalibrate = vm::calibrateNow)
}

/** Pause / resume next to hold-to-end. */
@Composable
private fun EndControl(canEnd: Boolean, vm: LiveRideViewModel) {
    val paused by vm.manuallyPaused.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(RtDimens.primaryButtonHeight)
                .clip(CircleShape)
                .background(if (paused) RtColors.Primary else RtColors.Surface)
                .border(1.dp, RtColors.Hairline, CircleShape)
                .clickable(enabled = canEnd, role = Role.Button) { haptics.tick(); vm.togglePause() }
                .semantics { contentDescription = if (paused) "Resume ride" else "Pause ride" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                tint = if (paused) RtColors.OnPrimary else RtColors.TextPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
        HoldToConfirmButton(
            label = "Hold to end ride",
            holdingLabel = "Keep holding…",
            onConfirmed = vm::endNow,
            onAccessibleClick = vm::requestEnd,
            enabled = canEnd,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun KeepScreenOn() {
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
private fun TopBar(chrome: LiveChrome, frame: TelemetryFrame?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val paused = chrome.rideState is RideState.Paused || (chrome.rideState as? RideState.EndingRide)?.wasPaused == true
        RecordingPill(paused, frame?.elapsedMillis ?: 0)
        Spacer(Modifier.weight(1f))
        if (chrome.active?.source == DataSourceKind.DEMO) DemoBadge()
        if (chrome.active?.source == DataSourceKind.PHONE) {
            val gps = frame?.gpsQuality
            val (text, color) = when (gps) {
                GpsQuality.LOST -> "GPS lost" to RtColors.Warning
                GpsQuality.UNAVAILABLE, null -> "GPS searching" to RtColors.Warning
                GpsQuality.LOW_ACCURACY -> "GPS weak" to RtColors.Warning
                else -> ("GPS " + (frame.gpsAccuracyM?.let { "±${it.toInt()} m" } ?: "ok")) to RtColors.TextSecondary
            }
            Text(text, style = RtType.caption, color = color)
        }
        CameraIndicator.from(chrome.moments).takeIf { it != CameraIndicator.OFF }?.let { cam ->
            CameraBadge(cam, RtColors.TextSecondary)
        }
        chrome.battery?.let { b ->
            Text("${b.percent}%", style = RtType.caption, color = if (b.isLow) RtColors.Warning else RtColors.TextSecondary)
        }
    }
    // Moments were requested but can't film right now: say why, once, quietly.
    val m = chrome.moments
    if (chrome.active?.moments != null && m.status == MomentStatus.PAUSED && m.reason != null && m.reason != PauseReason.RIDE_PAUSED) {
        Text(
            "Moments: ${m.reason.message.replaceFirstChar { it.lowercase() }}",
            style = RtType.caption,
            color = RtColors.TextSecondary,
            modifier = Modifier.padding(top = RtDimens.xs),
        )
    }
    if (chrome.active?.storageProblem == true) {
        Text(
            "Storage is having trouble. Your ride is kept in memory and will be saved when possible.",
            style = RtType.caption,
            color = RtColors.Warning,
            modifier = Modifier.padding(top = RtDimens.xs),
        )
    }
}

/** Pulsing red dot + ride time; purple and still while auto-paused. */
@Composable
private fun RecordingPill(paused: Boolean, elapsedMillis: Long) {
    val reduce = rememberReduceMotion()
    val t = rememberInfiniteTransition(label = "rec")
    val pulse by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "rec")
    Row(
        Modifier
            .background(RtColors.Surface, RoundedCornerShape(50))
            .border(1.dp, RtColors.Hairline, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = if (paused) "Paused, stopped" else "Recording, ${Format.clock(elapsedMillis)}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (paused || reduce) 1f else pulse)
                .background(if (paused) RtColors.Paused else RtColors.Error, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (paused) "Paused · ${Format.clock(elapsedMillis)}" else Format.clock(elapsedMillis),
            style = RtType.caption.copy(fontFeatureSettings = "tnum"),
            color = RtColors.TextPrimary,
        )
    }
}

@Composable
private fun SpeedAndLean(frame: TelemetryFrame?, chrome: LiveChrome) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val speed = frame?.speedMps
        if (frame?.rpm != null) {
            SpeedWithRevs(frame, chrome.active?.redlineRpm)
        } else {
            Text(
                Format.speedKmh(speed),
                style = RtType.speedHero,
                color = if (speed == null) RtColors.TextTertiary else RtColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = "Speed ${Format.speedWithUnit(speed)}" },
            )
            Text("km/h", style = RtType.body, color = RtColors.TextSecondary)
        }
        if (speed == null) {
            val msg = when (frame?.gpsQuality) {
                GpsQuality.LOST -> "GPS signal lost"
                GpsQuality.UNAVAILABLE, null -> "Waiting for GPS"
                else -> "Speed unavailable"
            }
            Text(msg, style = RtType.caption, color = RtColors.Warning, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(36.dp))
        val lean = frame?.leanDeg
        LeanArc(lean, Modifier.widthIn(max = 260.dp), maxLeft = frame?.stats?.maxLeftLeanDeg, maxRight = frame?.stats?.maxRightLeanDeg)
        Text(
            Format.lean(lean),
            style = RtType.metricXL,
            color = if (lean == null) RtColors.TextTertiary else leanColor(lean),
            modifier = Modifier.padding(top = 4.dp),
        )
        Label("Lean")
        val note = when {
            chrome.active?.sensors?.canEstimateLean == false -> "Unavailable · no gyroscope"
            frame?.leanDeg == null -> calibrationNote(frame, chrome.active?.source)
            frame?.leanConfidence == LeanConfidence.LOW -> "Estimate may be inaccurate"
            else -> null
        }
        if (note != null) Text(note, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

private data class Cell(val label: String, val value: String, val unit: String?, val color: Color = RtColors.TextPrimary)

@Composable
private fun MetricGrid(frame: TelemetryFrame?, chrome: LiveChrome) {
    val stats = frame?.stats
    val cells = buildList {
        add(Cell("Distance", stats?.let { Format.distanceValue(it.distanceM) } ?: Format.DASH, "km"))
        add(Cell("Avg speed", Format.speedKmh(stats?.avgSpeedMps), "km/h"))
        chrome.metrics.forEach { add(metricCell(it, frame)) }
    }
    Column {
        HairlineDivider()
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { c -> StatBlock(c.label, c.value, Modifier.weight(1f), unit = c.unit, style = RtType.metricL, color = c.color) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (chrome.showGIndicator) {
            GForceIndicator(frame?.longitudinalG, frame?.lateralG, Modifier.width(140.dp).align(Alignment.CenterHorizontally).padding(top = 12.dp))
        }
    }
}

private fun metricCell(metric: LiveMetric, frame: TelemetryFrame?): Cell {
    val long = frame?.longitudinalG
    val s = frame?.stats
    return when (metric) {
        LiveMetric.ACCELERATION -> Cell("Acceleration", Format.gSigned(long?.coerceAtLeast(0.0)), null, RtColors.Accel)
        LiveMetric.BRAKING -> Cell("Braking", Format.gSigned(long?.coerceAtMost(0.0)), null, RtColors.Brake)
        LiveMetric.G_FORCE -> Cell("G-force", Format.g(frame?.combinedG), null, RtColors.GForce)
        LiveMetric.MAX_LEAN -> {
            val l = s?.maxLeftLeanDeg
            val r = s?.maxRightLeanDeg
            val v = when {
                l == null && r == null -> Format.DASH
                (r ?: 0.0) >= (l ?: 0.0) -> Format.lean(r)
                else -> Format.lean(l?.let { -it })
            }
            Cell("Max lean", v, null)
        }
        LiveMetric.MAX_SPEED -> Cell("Max speed", Format.speedKmh(s?.maxSpeedMps), "km/h")
        LiveMetric.HEADING -> Cell("Heading", Format.heading(frame?.headingDeg), null)
        LiveMetric.ALTITUDE -> Cell("Altitude", Format.altitude(frame?.altitudeM), null)
    }
}

@Composable
private fun SavingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(RtColors.LiveBackground.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .background(RtColors.SurfaceRaised, RoundedCornerShape(50))
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text("Saving ride…", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        }
    }
}

@Composable
private fun ErrorContent(error: RideError, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(RtColors.LiveBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(RtDimens.screenPaddingWide),
        verticalArrangement = Arrangement.Center,
    ) {
        Chip("Problem", RtColors.Error)
        Spacer(Modifier.height(RtDimens.md))
        val (title, message) = when (error) {
            RideError.STORAGE_FAILURE -> "Ride not saved yet" to
                "The ride couldn't be written to storage. Anything already recorded is kept and will be offered for recovery on the Home screen."
            RideError.LOCATION_PERMISSION_REVOKED -> "Location access removed" to
                "Recording stopped because location permission was revoked. Recorded data is kept."
            RideError.SOURCE_FAILURE -> "Sensors stopped" to
                "The phone stopped delivering sensor data. Recorded data is kept."
        }
        Text(title, style = RtType.title, color = RtColors.TextPrimary)
        Spacer(Modifier.height(RtDimens.xs))
        Text(message, style = RtType.body, color = RtColors.TextSecondary)
        Spacer(Modifier.height(RtDimens.xl))
        PrimaryButton("Back to home", onDone, large = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HudPermissionSheet(onContinue: () -> Unit, onNotNow: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onNotNow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RtColors.SurfaceRaised,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(RtDimens.md),
        ) {
            Text("Keep your ride data visible in other apps", style = RtType.headline, color = RtColors.TextPrimary)
            Text(
                "When you switch to navigation or music during a ride, a small pop-up shows your speed and lean. " +
                    "It hides again when you come back to Keppo Moto.",
                style = RtType.body,
                color = RtColors.TextSecondary,
            )
            Text(
                "Android calls this \"Display over other apps\". Turn it on in the next screen, then press Back.",
                style = RtType.body,
                color = RtColors.TextSecondary,
            )
            PrimaryButton("Continue", onContinue, large = true)
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().padding(bottom = RtDimens.md)) {
                Text("Not now", style = RtType.button, color = RtColors.TextSecondary)
            }
        }
    }
}
