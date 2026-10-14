package com.ridetrack.app.ui.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ridetrack.app.pro.AiReports
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

/**
 * Before Studio first uses Gemini: what goes to Google, and that the rider can say no (Studio
 * then stays closed; rides and moments don't need it). Covers the screen and takes the touches.
 */
@Composable
fun AiConsentCard(onNotNow: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = appContainer()
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(RtDimens.screenPadding).fillMaxWidth().background(RtColors.Surface, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Studio uses Google's Gemini", style = RtType.headline, color = RtColors.TextPrimary)
            Text(
                "To find ideas, write scripts and captions, Studio sends Google's Gemini the sound of your talking clips, a few frames from your videos, and your ride's name and numbers. " +
                    "Google uses them to give the answer. Keppo Moto doesn't keep them on any server of its own.",
                style = RtType.body,
                color = RtColors.TextSecondary,
            )
            Text(
                "Gemini can get things wrong. If something it writes is offensive or harmful, hold the idea and tap Report.",
                style = RtType.caption,
                color = RtColors.TextTertiary,
            )
            PrimaryButton("Agree and continue", onClick = { c.aiConsent.set(true) }, large = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            onNotNow?.let {
                Text(
                    "Not now",
                    style = RtType.button,
                    color = RtColors.TextSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable(role = Role.Button, onClick = it).padding(8.dp),
                )
            }
        }
    }
}

/** Reports a Gemini result ([text], what it is: [what]) without leaving the app. */
@Composable
fun AiReportDialog(what: String, text: String, onDone: (sent: Boolean) -> Unit) {
    val c = appContainer()
    var reason by remember { mutableStateOf(AiReports.Reason.OFFENSIVE) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Report this ${what.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (text.isNotBlank()) Text("“${text.take(160)}”", style = RtType.caption, color = RtColors.TextSecondary)
                AiReports.Reason.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { reason = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r.label, style = RtType.body, color = RtColors.TextPrimary)
                    }
                }
                OutlinedTextField(note, { note = it }, placeholder = { Text("What's wrong? (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { c.aiReports.add(what, text, reason, note); onDone(true) }) { Text("Report") } },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } },
    )
}
