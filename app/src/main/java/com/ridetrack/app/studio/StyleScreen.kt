package com.ridetrack.app.studio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Studio › What Studio learned: the habits Studio follows when it writes (from your notes and the
 * changes you make), one card each; swipe one left to forget it, or tell it something new. Gemini
 * reads these before it suggests. The log of changes can be sent to the developer.
 */
@Composable
fun StyleScreen(onBack: () -> Unit) {
    val store = appContainer().style
    val rules by store.rules.collectAsStateWithLifecycle()
    val log by store.log.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by remember { mutableStateOf("") }
    var forgot by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var fresh by remember { mutableStateOf(false) }
    LaunchedEffect(forgot) { if (forgot != null) { delay(4_000); forgot = null } }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("‹ Back", style = RtType.button, color = RtColors.Primary, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp))
        Text("What Studio learned", style = RtType.title, color = RtColors.TextPrimary)
        Text(
            "From the notes you give and the changes you make. Gemini reads these before it suggests or writes.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        if (rules.isEmpty()) Text("Nothing yet. Change a Reel, or tell Studio something below.", style = RtType.body, color = RtColors.TextTertiary)
        rules.forEachIndexed { i, r ->
            key(r) {
                Habit(r, onForget = { forgot = i to r; store.setRules(rules.filterIndexed { k, _ -> k != i }) })
            }
        }
        forgot?.let { (i, r) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.SurfaceRaised).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Forgot “${r.take(40)}”", style = RtType.caption, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                Text("Undo", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                    store.setRules(store.rules.value.toMutableList().also { it.add(i.coerceAtMost(it.size), r) })
                    forgot = null
                }.padding(start = 8.dp))
            }
        }
        OutlinedTextField(
            value = adding,
            onValueChange = { adding = it },
            placeholder = { Text("Tell Studio something, e.g. short Hinglish lines, no slow-mo", style = RtType.body, color = RtColors.TextTertiary) },
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (adding.isNotBlank()) {
            Text("Add", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { store.setRules(rules + adding.trim()); adding = "" }.padding(vertical = 6.dp))
        }
        Text("Swipe a habit left to forget it", style = RtType.caption, color = RtColors.TextTertiary)
        Row(Modifier.padding(top = 8.dp)) {
            if (rules.isNotEmpty()) Text("Start fresh", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { fresh = true }.padding(vertical = 6.dp))
            if (log.isNotEmpty() || rules.isNotEmpty()) {
                Text("Send the log", style = RtType.button, color = RtColors.Primary, modifier = Modifier.padding(start = 20.dp).clickable(role = Role.Button) { store.share(context) }.padding(vertical = 6.dp))
            }
        }
        Text("${log.size} change${if (log.size == 1) "" else "s"} recorded", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(bottom = 16.dp))
    }
    if (fresh) {
        AlertDialog(
            onDismissRequest = { fresh = false },
            title = { Text("Start fresh?") },
            text = { Text("Studio forgets every habit here. Your Reels and styles stay.") },
            confirmButton = { TextButton(onClick = { store.setRules(emptyList()); fresh = false }) { Text("Forget all", color = RtColors.Error) } },
            dismissButton = { TextButton(onClick = { fresh = false }) { Text("Keep") } },
        )
    }
}

/** One habit as a card: drag it left past a third of its width to forget it. */
@Composable
private fun Habit(text: String, onForget: () -> Unit) {
    val x = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
        Box(Modifier.matchParentSize().background(RtColors.Error.copy(alpha = 0.85f)), contentAlignment = Alignment.CenterEnd) {
            Text("Forget", style = RtType.button, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(end = 16.dp))
        }
        Text(
            text,
            style = RtType.body,
            color = RtColors.TextPrimary,
            modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = x.value }.background(RtColors.Surface).padding(14.dp)
                .semantics { customActions = listOf(CustomAccessibilityAction("Forget") { onForget(); true }) }
                .pointerInput(text) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (x.value < -size.width / 3f) { x.animateTo(-size.width.toFloat(), tween(160)); onForget() } else x.animateTo(0f, tween(180))
                            }
                        },
                    ) { ch, d -> ch.consume(); scope.launch { x.snapTo((x.value + d).coerceAtMost(0f)) } }
                },
        )
    }
}
