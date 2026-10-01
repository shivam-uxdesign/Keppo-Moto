package com.ridetrack.app.ui.profile

import android.os.Build
import com.ridetrack.app.moments.MicChoice
import com.ridetrack.app.moments.MicType
import com.ridetrack.app.moments.Microphones
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.MomentSettings
import com.ridetrack.app.data.PhotoInterval
import com.ridetrack.app.data.VideoQuality
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import java.util.Locale

/** Opt-in background capture of clips and photos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MomentsSection(s: ProfileUiState, vm: ProfileViewModel) {
    val context = LocalContext.current
    val m = s.settings.moments
    var explain by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var testQueued by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshMomentsStorage() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val camera = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (camera) vm.setMoments(m.copy(enabled = true)) else denied = true
    }

    SectionHeader("Moments")
    RtCard {
        ToggleRow(
            "Capture moments",
            when {
                s.rideActive -> "Can't be changed during a ride."
                m.enabled -> "Saves ${m.clipSeconds} s before and after the events you pick below, from the selfie camera with sound."
                else -> "Automatically film the best bits of your ride and take a photo now and then. Off by default."
            },
            m.enabled,
            onChange = { on ->
                if (!on) vm.setMoments(m.copy(enabled = false)) else explain = true
            },
            enabled = !s.rideActive,
        )
        if (m.enabled) {
            HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))
            val editable = !s.rideActive
            ToggleRow("Hard braking", "Braking at ${gText(m.brakeG)} or more.", m.braking, { vm.setMoments(m.copy(braking = it)) }, enabled = editable)
            if (m.braking) {
                ChoiceRow(MomentSettings.BRAKE_CHOICES, m.brakeG, ::gText, editable) { vm.setMoments(m.copy(brakeG = it)) }
            }
            ToggleRow("Strong acceleration", "Accelerating at ${gText(m.accelG)} or more.", m.acceleration, { vm.setMoments(m.copy(acceleration = it)) }, enabled = editable)
            if (m.acceleration) {
                ChoiceRow(MomentSettings.ACCEL_CHOICES, m.accelG, ::gText, editable) { vm.setMoments(m.copy(accelG = it)) }
            }
            ToggleRow("Deep lean", "Leaning past ${m.leanDeg}°. Needs a calibrated mount.", m.lean, { vm.setMoments(m.copy(lean = it)) }, enabled = editable)
            if (m.lean) {
                ChoiceRow(MomentSettings.LEAN_CHOICES, m.leanDeg, { "$it°" }, editable) { vm.setMoments(m.copy(leanDeg = it)) }
            }
            Spacer(Modifier.height(RtDimens.sm))
            Label("Clip length")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                MomentSettings.CLIP_CHOICES.forEach { sec ->
                    Pick("$sec s before & after", m.clipSeconds == sec, editable) { vm.setMoments(m.copy(clipSeconds = sec)) }
                }
            }
            Spacer(Modifier.height(RtDimens.sm))
            Label("Photos")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                PhotoInterval.entries.forEach { p ->
                    Pick(if (p == PhotoInterval.OFF) p.label else "Every ${p.label}", m.photos == p, editable) { vm.setMoments(m.copy(photos = p)) }
                }
            }
            Text(
                "Plus one photo at each stop longer than a minute.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Spacer(Modifier.height(RtDimens.sm))
            MicPicker(m.mic, editable) { vm.setMoments(m.copy(mic = it)) }
            Spacer(Modifier.height(RtDimens.sm))
            Label("Video quality")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                VideoQuality.entries.forEach { q -> Pick(q.label, m.quality == q, !s.rideActive) { vm.setMoments(m.copy(quality = q)) } }
            }
            Spacer(Modifier.height(RtDimens.sm))
        }
        if (BuildConfig.DEBUG && s.rideActive && vm.canTestClip) {
            // Beta: checks the whole clip path in ~15 s (camera → buffer → MP4 → ride page).
            TextButton(onClick = {
                vm.testClip()
                testQueued = true
            }, enabled = !testQueued) {
                Text(if (testQueued) "Test clip queued: check the ride page after it ends" else "Save a test clip now (beta)", color = RtColors.Primary)
            }
        }
        HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))
        Text(
            "Stored on this phone: ${formatBytes(s.momentsBytes)}. Nothing is deleted automatically.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
            modifier = Modifier.padding(top = RtDimens.sm),
        )
        if ((s.momentsBytes ?: 0) > 0) {
            TextButton(onClick = { confirmDelete = true }, enabled = !s.rideActive) {
                Text("Delete all moments", color = RtColors.Error)
            }
        }
    }

    if (explain) {
        MomentsExplainer(
            onContinue = {
                explain = false
                permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            },
            onDismiss = { explain = false },
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text("Camera access is off") },
            text = { Text("Moments needs the camera. You can allow it in Settings; sound also needs the microphone.") },
            confirmButton = {
                TextButton(onClick = {
                    denied = false
                    Permissions.openAppSettings(context)
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { denied = false }) { Text("Not now") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all moments?") },
            text = { Text("Every saved clip and photo from all rides will be removed. Rides themselves are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteAllMoments()
                }) { Text("Delete", color = RtColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentsExplainer(onContinue: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RtColors.SurfaceRaised,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(RtDimens.md),
        ) {
            Text("How Moments works", style = RtType.headline, color = RtColors.TextPrimary)
            Bullet("During a ride, the selfie camera and microphone keep the last few seconds in memory. Nothing is saved unless something happens.")
            Bullet("On hard braking, strong acceleration or a deep lean, the seconds before and after are saved as a clip. You choose how strong each one must be, and how often a photo is taken.")
            Bullet("No camera screen opens. The ride screen and pop-up show a small CAM dot, and REC while a moment is saved. Android also shows its green camera dot.")
            Bullet("Clips stay on this phone (about 12 MB each at 720p) until you delete them. It uses more battery, and the phone may get warm on the mount; Moments pauses itself if it gets hot.")
            PrimaryButton("Continue", onContinue, large = true)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = RtDimens.md)) {
                Text("Not now", style = RtType.button, color = RtColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = RtType.body, color = RtColors.TextSecondary)
}

@Composable
private fun Pick(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f),
            selectedLabelColor = RtColors.Primary,
            labelColor = RtColors.TextSecondary,
        ),
    )
}

internal fun formatBytes(b: Long?): String = when {
    b == null -> "…"
    b < 1_000_000 -> "none"
    b < 1_000_000_000 -> "${b / 1_000_000} MB"
    else -> String.format(Locale.US, "%.1f GB", b / 1e9)
}

private fun gText(g: Double): String = String.format(java.util.Locale.US, "%.1f G", g)

/** A row of chips for a trigger's strength; the lower the value, the more often it fires. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(choices: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onPick: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RtDimens.xs),
        modifier = Modifier.padding(start = 4.dp, bottom = RtDimens.xs),
    ) {
        choices.forEach { c -> Pick(label(c), c == selected, enabled) { onPick(c) } }
    }
}

/**
 * Which microphone records clip audio. Automatic (the default) takes the best one connected:
 * a USB-C receiver (DJI Mic), then a wired mic, then the Bluetooth headset, then the phone.
 * A specific mic that isn't connected stays selected; rides use the phone mic until it's back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicPicker(saved: String?, enabled: Boolean, onPick: (String?) -> Unit) {
    val context = LocalContext.current
    var scan by remember { mutableIntStateOf(0) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scan++ }
    val available = remember(scan) { Microphones.available(context) }
    val current = MicChoice.decode(saved)
    val options = listOf(MicChoice.AUTO) + if (current in available || current.type == MicType.AUTO) available else available + current
    Label("Microphone")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
        options.forEach { choice ->
            val connected = choice.type == MicType.AUTO || choice in available
            Pick(if (connected) choice.label else "${choice.label} (not connected)", choice == current, enabled) {
                onPick(if (choice.type == MicType.AUTO) null else choice.encode())
            }
        }
        Pick("Look for mics", false, enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                scan++
            }
        }
    }
    Text(
        when (current.type) {
            MicType.AUTO -> "Uses a USB-C mic (like the DJI receiver) when it's plugged in, else your Bluetooth headset, else the phone. " +
                "A Bluetooth headset can't play music while its mic records, so plug in the USB-C mic to keep your music."
            MicType.BLUETOOTH -> "Music on this headset stops while its mic is recording (Bluetooth can't do both). For music and recording together, use a USB-C mic."
            MicType.PHONE -> "Records from the phone's own mic, even when other mics are connected."
            else -> "Clips record from this mic while it's connected; otherwise the phone mic (never your headset, so your music keeps playing)."
        },
        style = RtType.caption,
        color = RtColors.TextSecondary,
    )
}
