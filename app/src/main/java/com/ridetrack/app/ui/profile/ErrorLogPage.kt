package com.ridetrack.app.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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
    // Entries ticked to send on their own (by time; times are unique enough for a session).
    var picked by remember { mutableStateOf(setOf<Long>()) }
    // Opening the log counts as seeing what's new.
    androidx.compose.runtime.LaunchedEffect(Unit) { log.markSeen() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "When something fails (making a Reel, Gemini, transcripts), or works only another way (a warning), the full technical details are kept here. " +
                "Tap Share to send them (WhatsApp, email, or paste them into the chat).",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        if (entries.isEmpty()) {
            RtCard { Text("No errors recorded.", style = RtType.body, color = RtColors.TextSecondary) }
            return@Column
        }
        val chosen = entries.filter { it.timeMillis in picked }
        Text(
            if (chosen.isEmpty()) "Tick entries to send only those, or send them all." else "${chosen.size} ticked",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chosen.isEmpty()) {
                PrimaryButton("Share all", { log.share() }, Modifier.weight(1f), icon = Icons.Outlined.IosShare)
                SecondaryButton(if (copied) "Copied" else "Copy all", { copied = log.copy() }, Modifier.weight(1f), icon = Icons.Outlined.ContentCopy)
            } else {
                PrimaryButton("Share ${chosen.size}", { log.share(chosen) }, Modifier.weight(1f), icon = Icons.Outlined.IosShare)
                SecondaryButton(if (copied) "Copied" else "Copy ${chosen.size}", { copied = log.copy(chosen) }, Modifier.weight(1f), icon = Icons.Outlined.ContentCopy)
            }
        }
        if (chosen.isNotEmpty()) Text("Clear ticks", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { picked = emptySet() }.padding(vertical = 4.dp))
        entries.forEach { e ->
            var open by rememberSaveable(e.timeMillis) { mutableStateOf(false) }
            RtCard(onClick = { open = !open }) {
                val on = e.timeMillis in picked
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { picked = if (it) picked + e.timeMillis else picked - e.timeMillis })
                    Text(if (on) "Ticked to send" else "Tick to send", style = RtType.caption, color = RtColors.TextTertiary)
                }
                Text(
                    "${ErrorLog.stamp(e.timeMillis)} · ${if (e.warning) "Warning · " else ""}${e.area}",
                    style = RtType.caption,
                    color = if (e.warning) RtColors.Warning else RtColors.TextSecondary,
                )
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
