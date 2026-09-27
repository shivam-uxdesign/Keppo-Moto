package com.ridetrack.app.ui.live

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsOff
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.RevMeter
import com.ridetrack.app.ui.components.rpmColor
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.pressScale
import com.ridetrack.app.ui.theme.rememberHaptics
import com.ridetrack.telemetry.model.CalibrationStatus
import com.ridetrack.telemetry.model.CaptureOutcome
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.GpsQuality
import com.ridetrack.telemetry.model.TelemetryFrame
import kotlinx.coroutines.delay

/** No fix after this long reads as a problem rather than "still warming up". */
private const val NO_FIX_WARNING_MILLIS = 20_000L

/** How long the "Calibrate now" offer stays up at the start of a ride. */
private const val CALIBRATE_OFFER_MILLIS = 7_000

private enum class GpsProblem { NO_PERMISSION, LOCATION_OFF, LOST, NO_FIX }

private fun gpsProblem(chrome: LiveChrome, frame: TelemetryFrame?): GpsProblem? {
    if (chrome.active?.source != DataSourceKind.PHONE) return null
    return when {
        !chrome.location.permission -> GpsProblem.NO_PERMISSION
        !chrome.location.enabled -> GpsProblem.LOCATION_OFF
        frame?.gpsQuality == GpsQuality.LOST -> GpsProblem.LOST
        frame?.gpsQuality == GpsQuality.UNAVAILABLE && frame.elapsedMillis > NO_FIX_WARNING_MILLIS -> GpsProblem.NO_FIX
        else -> null
    }
}

/** Warning with a way out when GPS isn't delivering: retry, or jump to the right setting. */
@Composable
fun GpsWarning(chrome: LiveChrome, frame: TelemetryFrame?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val problem = gpsProblem(chrome, frame)
    val context = LocalContext.current
    var retrying by remember { mutableStateOf(false) }
    LaunchedEffect(retrying) {
        if (retrying) {
            delay(4_000)
            retrying = false
        }
    }
    AnimatedVisibility(problem != null, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val p = problem ?: GpsProblem.NO_FIX
        val (title, message) = when (p) {
            GpsProblem.NO_PERMISSION -> "Location permission is off" to "Speed, distance and route aren't being recorded."
            GpsProblem.LOCATION_OFF -> "Location is turned off" to "Turn it on to record speed, distance and route."
            GpsProblem.LOST -> "GPS signal lost" to "Speed shows -- until the signal is back. Distance pauses."
            GpsProblem.NO_FIX -> "GPS not connected" to "No fix yet. Open sky helps; retrying restarts the GPS."
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(RtColors.Warning.copy(alpha = 0.10f))
                .border(1.dp, RtColors.Warning.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                .padding(14.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GpsOff, contentDescription = null, tint = RtColors.Warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            }
            Text(message, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, start = 28.dp))
            Row(Modifier.padding(top = 10.dp, start = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (p) {
                    GpsProblem.NO_PERMISSION -> BannerButton("Allow in settings", primary = true) { openAppSettings(context) }
                    GpsProblem.LOCATION_OFF -> BannerButton("Turn on", primary = true) { openLocationSettings(context) }
                    else -> Unit
                }
                if (p != GpsProblem.NO_PERMISSION) {
                    BannerButton(if (retrying) "Retrying…" else "Retry", primary = p != GpsProblem.LOCATION_OFF, enabled = !retrying) {
                        retrying = true
                        onRetry()
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerButton(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(50))
            .background(if (primary) RtColors.Warning.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, RtColors.Warning.copy(alpha = if (primary) 0f else 0.35f), RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = RtType.caption, color = if (enabled) RtColors.TextPrimary else RtColors.TextSecondary)
    }
}

private fun openLocationSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
}

/**
 * Offered for the first few seconds of a ride, when the phone is finally in its riding
 * position: a 3 s capture with the bike upright and still. If the rider ignores it, the
 * mount is learned automatically on the first straight, steady stretch.
 */
@Composable
fun CalibrateOffer(frame: TelemetryFrame?, onCalibrate: () -> Unit, modifier: Modifier = Modifier) {
    val info = frame?.calibration
    val haptics = rememberHaptics()
    var tapped by remember { mutableStateOf(false) }
    var offerOver by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    val countdown = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        countdown.animateTo(0f, tween(CALIBRATE_OFFER_MILLIS, easing = LinearEasing))
        offerOver = true
    }
    val capturing = info?.captureProgress != null
    val outcome = info?.lastCapture
    LaunchedEffect(outcome) {
        if (tapped && outcome != null) {
            if (outcome == CaptureOutcome.SUCCESS) haptics.confirm() else haptics.tick()
            showResult = true
            delay(3_500)
            showResult = false
        }
    }
    val visible = (!offerOver && !tapped) || capturing || showResult
    AnimatedVisibility(visible, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val interaction = remember { MutableInteractionSource() }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(RtColors.Surface)
                .border(1.dp, RtColors.Hairline, RoundedCornerShape(18.dp))
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressScale(interaction)
                    .clickable(interactionSource = interaction, indication = null, enabled = !tapped, role = Role.Button) {
                        tapped = true
                        haptics.tick()
                        onCalibrate()
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(RtColors.Primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    if (capturing) {
                        CircularProgressIndicator(
                            progress = { (info?.captureProgress ?: 0.0).toFloat() },
                            color = RtColors.Primary,
                            trackColor = Color.Transparent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp),
                        )
                    } else {
                        Icon(Icons.Outlined.ScreenRotationAlt, contentDescription = null, tint = RtColors.Primary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val (title, sub) = when {
                        capturing -> "Hold still…" to "Bike upright, feet down. ${((info?.captureProgress ?: 0.0) * 100).toInt()}%"
                        showResult && outcome == CaptureOutcome.SUCCESS -> "Mount calibrated" to "Lean angle is live."
                        showResult -> "Couldn't calibrate" to "Too much movement. It'll calibrate itself on a straight road."
                        else -> "Calibrate now" to "On the bike and upright? Tap, then hold still for 3 s."
                    }
                    Text(title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text(sub, style = RtType.caption, color = RtColors.TextSecondary)
                }
                if (!tapped) Text("Tap", style = RtType.caption, color = RtColors.Primary)
            }
            if (!tapped && !offerOver) {
                // Shrinking line = how long the offer stays.
                Box(
                    Modifier
                        .fillMaxWidth(countdown.value)
                        .height(2.dp)
                        .background(RtColors.Primary.copy(alpha = 0.6f)),
                )
            }
        }
    }
}

/** Short status of the mount calibration under the lean readout, or null when all is well. */
fun calibrationNote(frame: TelemetryFrame?, source: DataSourceKind?): String? = when (frame?.calibration?.status) {
    CalibrationStatus.NONE, null -> if (source == null) null else "Learning mount · ride straight for a few seconds"
    else -> null
}

/** Speed wrapped in the tach arc, with gear and RPM beneath. Only used when RPM exists. */
@Composable
fun SpeedWithRevs(frame: TelemetryFrame, redlineRpm: Int?, modifier: Modifier = Modifier) {
    val speed = frame.speedMps
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        RevMeter(frame.rpm, redlineRpm, Modifier.fillMaxWidth(0.94f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 18.dp)) {
                Text(
                    Format.speedKmh(speed),
                    style = RtType.speedHero.copy(fontSize = 112.sp, lineHeight = 112.sp),
                    color = if (speed == null) RtColors.TextTertiary else RtColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Text("km/h", style = RtType.body, color = RtColors.TextSecondary)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (frame.gear != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                Format.gear(frame.gear),
                                style = RtType.metricL,
                                color = if (frame.gear == 0) RtColors.Ok else RtColors.TextPrimary,
                            )
                            Label("Gear")
                        }
                        Spacer(Modifier.width(26.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Format.rpm(frame.rpm), style = RtType.metricL, color = rpmColor(frame.rpm, redlineRpm))
                        Label("RPM")
                    }
                }
            }
        }
    }
}
