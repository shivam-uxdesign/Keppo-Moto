package com.ridetrack.app.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.diag.ErrorLog
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType

/** Profile › Error log: every failure with its full detail; share it to send it to the developer. */
@Composable
fun ErrorLogPage() {
    val log = appContainer().errors
    val entries by log.entries.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "When something fails (making a Reel, Gemini, transcripts), the full technical details are kept here. " +
                "Tap Share to send them (WhatsApp, email, or paste them into the chat).",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        if (entries.isEmpty()) {
            RtCard { Text("No errors recorded.", style = RtType.body, color = RtColors.TextSecondary) }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Share all", { log.share() }, Modifier.weight(1f), icon = Icons.Outlined.IosShare)
            SecondaryButton(if (copied) "Copied" else "Copy all", { copied = log.copy() }, Modifier.weight(1f), icon = Icons.Outlined.ContentCopy)
        }
        entries.forEach { e ->
            var open by rememberSaveable(e.timeMillis) { mutableStateOf(false) }
            RtCard(onClick = { open = !open }) {
                Text("${ErrorLog.stamp(e.timeMillis)} · ${e.area}", style = RtType.caption, color = RtColors.TextSecondary)
                Text(e.summary, style = RtType.body, color = RtColors.TextPrimary)
                AnimatedVisibility(open) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                e.details.ifBlank { "No more detail." },
                                style = RtType.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                                color = RtColors.TextPrimary,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                        Text(
                            "Share this one",
                            style = RtType.button,
                            color = RtColors.Primary,
                            modifier = Modifier.clickable(role = Role.Button) { log.share(listOf(e)) }.padding(vertical = 8.dp),
                        )
                    }
                }
                if (!open) Text("Tap for details", style = RtType.caption, color = RtColors.TextTertiary)
            }
        }
        SecondaryButton("Clear the log", { log.clear() }, Modifier.fillMaxWidth())
    }
}
