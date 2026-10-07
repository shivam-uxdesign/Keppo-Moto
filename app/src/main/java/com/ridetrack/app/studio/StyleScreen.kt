package com.ridetrack.app.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

/**
 * Studio › Your style: the rules Studio follows when it writes scripts (from your notes, which you
 * can change), and the log of every change you made, to send to the developer.
 */
@Composable
fun StyleScreen(onBack: () -> Unit) {
    val store = appContainer().style
    val rules by store.rules.collectAsStateWithLifecycle()
    val log by store.log.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Your style", onBack = onBack, subtitle = "How Studio writes your scripts")
        Text(
            "Studio follows these rules, and looks at your last few changes, every time it writes a script. Notes you give in a Reel's Script view are added here.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        if (rules.isEmpty()) Text("No rules yet. Change a script, or add one below.", style = RtType.body, color = RtColors.TextTertiary)
        rules.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                Text("Remove", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { store.setRules(rules.filterIndexed { k, _ -> k != i }) }.padding(6.dp))
            }
        }
        OutlinedTextField(
            value = adding,
            onValueChange = { adding = it },
            placeholder = { Text("Add a rule, e.g. short Hinglish lines, no slow-mo", style = RtType.body, color = RtColors.TextTertiary) },
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (adding.isNotBlank()) {
            Text("Add", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { store.setRules(rules + adding); adding = "" }.padding(vertical = 6.dp))
        }
        Text("${log.size} change${if (log.size == 1) "" else "s"} recorded", style = RtType.caption, color = RtColors.TextSecondary)
        if (log.isNotEmpty() || rules.isNotEmpty()) {
            Text("Send the log (before, after, your notes)", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { store.share(context) }.padding(vertical = 6.dp))
        }
        log.takeLast(5).reversed().forEach { e ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                e.note?.let { Text("“$it”", style = RtType.bodyStrong, color = RtColors.TextPrimary) }
                Text("Before: ${e.before}", style = RtType.caption, color = RtColors.TextTertiary, maxLines = 3)
                Text("After: ${e.after}", style = RtType.caption, color = RtColors.TextSecondary, maxLines = 3)
            }
        }
    }
}
