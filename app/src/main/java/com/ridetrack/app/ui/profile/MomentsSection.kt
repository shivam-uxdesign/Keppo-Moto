package com.ridetrack.app.ui.profile

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
                m.enabled -> "Saves 10 s before and after hard braking, strong acceleration and leans past 20°, from the selfie camera with sound."
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
            ToggleRow("Hard braking", "0.5 G or more.", m.braking, { vm.setMoments(m.copy(braking = it)) }, enabled = !s.rideActive)
            ToggleRow("Strong acceleration", "0.3 G or more.", m.acceleration, { vm.setMoments(m.copy(acceleration = it)) }, enabled = !s.rideActive)
            ToggleRow("Lean past 20°", "Needs a calibrated mount.", m.lean, { vm.setMoments(m.copy(lean = it)) }, enabled = !s.rideActive)
            Spacer(Modifier.height(RtDimens.sm))
            Label("Photos")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                PhotoInterval.entries.forEach { p -> Pick(p.label, m.photos == p, !s.rideActive) { vm.setMoments(m.copy(photos = p)) } }
            }
            Text(
                "Plus one photo at each stop longer than a minute.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Spacer(Modifier.height(RtDimens.sm))
            Label("Video quality")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                VideoQuality.entries.forEach { q -> Pick(q.label, m.quality == q, !s.rideActive) { vm.setMoments(m.copy(quality = q)) } }
            }
            Spacer(Modifier.height(RtDimens.sm))
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
            Bullet("On hard braking, strong acceleration or a lean past 20°, the 10 s before and after are saved as a clip. A photo is taken every 10–15 minutes.")
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

private fun formatBytes(b: Long?): String = when {
    b == null -> "…"
    b < 1_000_000 -> "none"
    b < 1_000_000_000 -> "${b / 1_000_000} MB"
    else -> String.format(Locale.US, "%.1f GB", b / 1e9)
}
