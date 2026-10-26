package com.ridetrack.app.studio.storyboard

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType

/**
 * Your gear: everything Storyboard plans with (cameras, mounts and which way they face, audio,
 * extras, whether someone can film), and where each came from. Add, change or remove any of it.
 */
@Composable
fun GearScreen(onBack: () -> Unit) {
    val c = appContainer()
    val gear by c.storyboard.gear.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GearItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = RtColors.TextPrimary) }
            Text("Your gear", style = RtType.title, color = RtColors.TextPrimary)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("YOUR GEAR", style = RtType.label, color = RtColors.TextTertiary)
            Text("Storyboard only plans shots this gear can film. Shared across all your bikes.", style = RtType.caption, color = RtColors.TextSecondary)
            if (gear.items.isEmpty()) Text("Nothing yet: Storyboard assumes a phone on a handlebar mount.", style = RtType.body, color = RtColors.TextSecondary)
            gear.items.forEach { g ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).clickable(role = Role.Button) { editing = g }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.name, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                        Text(listOf(g.kind.lowercase().replaceFirstChar { it.uppercase() }, g.detail, if (g.source == "chat") "said in a chat" else "added here").filter { it.isNotBlank() }.joinToString(" · "), style = RtType.caption, color = RtColors.TextSecondary)
                    }
                    IconButton(onClick = { c.storyboard.setGear(gear.copy(items = gear.items - g)) }) { Icon(Icons.Rounded.Delete, "Remove ${g.name}", tint = RtColors.TextTertiary) }
                }
            }
            Text("Add gear", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { adding = true }.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Someone can film me", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text("Off: every shot is planned to film on your own.", style = RtType.caption, color = RtColors.TextSecondary)
                }
                Switch(gear.helper, { c.storyboard.setGear(gear.copy(helper = it)) })
            }
        }
    }
    if (adding || editing != null) {
        val g = editing
        var name by remember { mutableStateOf(g?.name.orEmpty()) }
        var detail by remember { mutableStateOf(g?.detail.orEmpty()) }
        var kind by remember { mutableStateOf(g?.kind ?: "CAMERA") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (g == null) "Add gear" else "Change ${g.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CAMERA", "MOUNT", "AUDIO", "EXTRA").forEach { k ->
                            Text(k.lowercase().replaceFirstChar { it.uppercase() }, style = RtType.caption, color = if (kind == k) RtColors.OnPrimary else RtColors.TextPrimary,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (kind == k) RtColors.Primary else RtColors.SurfaceRaised).clickable(role = Role.RadioButton) { kind = k }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. GoPro, tripod, DJI Mic") }, singleLine = true)
                    OutlinedTextField(detail, { detail = it }, label = { Text("Details, e.g. on the helmet chin, facing me") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val item = GearItem(kind, name.trim(), detail.trim(), "settings")
                        c.storyboard.setGear(gear.copy(items = gear.items.filter { it != g } + item, asked = true))
                    }
                    adding = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}
