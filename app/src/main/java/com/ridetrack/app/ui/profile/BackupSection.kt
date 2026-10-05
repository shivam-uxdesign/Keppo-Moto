package com.ridetrack.app.ui.profile

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.backup.DriveAuth
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch

/** Profile › Backup: Google Drive backup and restore. Free, no account; files go to the rider's own Drive. */
@Composable
internal fun BackupSection(s: ProfileUiState, vm: ProfileViewModel, showHeader: Boolean = true) {
    val b = s.settings.backup
    val status by vm.backupStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var connectError by remember { mutableStateOf<String?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        when (val o = vm.onDriveConsent(result.data)) {
            is DriveAuth.Outcome.Granted -> vm.onDriveGranted(o.email)
            else -> connectError = "Drive wasn't connected. Try again when you're ready."
        }
    }
    val connect: () -> Unit = {
        connectError = null
        scope.launch {
            when (val o = vm.authorizeDrive()) {
                is DriveAuth.Outcome.Granted -> vm.onDriveGranted(o.email)
                is DriveAuth.Outcome.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(o.intent).build())
                null -> connectError = "Couldn't reach Google. Check your connection and try again."
            }
        }
    }

    if (showHeader) SectionHeader("Backup")
    RtCard {
        if (!b.connected) {
            Text("Google Drive backup", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(
                "Keep your rides, moments, bikes and settings in your own Google Drive, in a Keppo folder. " +
                    "Keppo can only see the files it puts there.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Spacer(Modifier.height(RtDimens.md))
            PrimaryButton("Connect Google Drive", connect)
            connectError?.let { Text(it, style = RtType.caption, color = RtColors.Error) }
            return@RtCard
        }

        Text("Google Drive", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        Text(b.email.orEmpty(), style = RtType.caption, color = RtColors.TextSecondary)
        Spacer(Modifier.height(RtDimens.sm))
        val line = status.working ?: status.error ?: when {
            b.lastSuccessMillis == null -> "Not backed up yet"
            else -> "Backed up · ${DateUtils.getRelativeTimeSpanString(b.lastSuccessMillis)} · ${formatBytes(b.lastBytes)} in your Drive"
        }
        Text(line, style = RtType.body, color = if (status.error != null && status.working == null) RtColors.Warning else RtColors.TextPrimary)
        if (status.pending > 0) {
            Text("${status.pending} videos waiting for Wi-Fi or charging", style = RtType.caption, color = RtColors.TextSecondary)
        }
        Spacer(Modifier.height(RtDimens.md))

        val found = status.foundRides
        when {
            status.needsReconnect -> PrimaryButton("Reconnect Google Drive", connect)
            found != null -> {
                Text(
                    "Your Drive already has a Keppo Moto backup with $found ${if (found == 1) "ride" else "rides"} that aren't on this phone.",
                    style = RtType.body,
                    color = RtColors.TextPrimary,
                )
                Spacer(Modifier.height(RtDimens.sm))
                PrimaryButton("Restore them", vm::restoreFromDrive)
                Spacer(Modifier.height(RtDimens.xs))
                SecondaryButton("Keep both, use this phone's settings", vm::keepBothBackups)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                SecondaryButton("Back up now", vm::backUpNow, Modifier.weight(1f), enabled = status.working == null && b.adopted)
                SecondaryButton("Restore", vm::restoreFromDrive, Modifier.weight(1f), enabled = status.working == null)
            }
        }
        Spacer(Modifier.height(RtDimens.sm))
        HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))
        ToggleRow(
            "Use mobile data",
            "Off: backups wait for Wi-Fi. Videos can be large.",
            b.allowMobileData,
            vm::setBackupMobileData,
        )
        ToggleRow(
            "Back up videos only while charging",
            "Rides, photos and settings still back up any time.",
            b.videosOnlyWhileCharging,
            vm::setBackupVideosCharging,
        )
        TextButton(onClick = vm::disconnectDrive) {
            Text("Disconnect Google Drive", color = RtColors.TextSecondary)
        }
        Text(
            "Disconnecting stops new backups. What's already in your Drive stays there.",
            style = RtType.caption,
            color = RtColors.TextTertiary,
        )
    }
}
