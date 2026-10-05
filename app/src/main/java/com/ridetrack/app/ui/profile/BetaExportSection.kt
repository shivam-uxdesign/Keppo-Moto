package com.ridetrack.app.ui.profile

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ridetrack.app.data.export.ExportShare
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch

/** BETA TOOL — shown only in debug/beta builds. Delete with `data/export`. */
@Composable
fun BetaExportSection(showHeader: Boolean = true) {
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    if (showHeader) SectionHeader("Beta tools")
    RtCard {
        Text("Export ride data", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        Text(
            "Creates a ZIP with every saved ride (JSON, CSV and GPX) and opens the share sheet. For beta testing — this tool will be removed.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        SecondaryButton(
            if (busy) "Preparing…" else "Export all rides",
            onClick = {
                if (busy) return@SecondaryButton
                busy = true
                message = null
                scope.launch {
                    val n = runCatching { ExportShare.exportAndShare(context, container) }
                    busy = false
                    message = n.fold(
                        onSuccess = { if (it == 0) "No saved rides to export yet." else null },
                        onFailure = { "Export failed: ${it.message ?: "unknown error"}" },
                    )
                }
            },
            icon = Icons.Outlined.IosShare,
            enabled = !busy,
        )
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = RtType.caption, color = RtColors.Warning)
        }
    }
}

