package com.ridetrack.app.studio.storyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.Ride

/** Section colours on the board and the animatic (the prototype's). */
fun sectionColor(i: Int): Color = listOf(0xFFA5A1FF, 0xFF69C8CB, 0xFFFBBF24, 0xFFFB7185, 0xFF4ADE80, 0xFFF4F4F5, 0xFF85858E).map { Color(it) }[i % 7]

/** The four ways to start a Reel. */
private enum class Door(val title: String, val sub: String, val icon: ImageVector) {
    IDEA("I have an idea", "Type or talk; Keppo asks a little, then lays out the shots", Icons.Rounded.Lightbulb),
    SCRIPT("I have a script", "Paste it: every word stays exactly as you wrote it", Icons.Rounded.Description),
    RIDE("From a ride", "Built around one ride's best clips", Icons.Rounded.Route),
    BLANK("Blank board", "Add the shots yourself", Icons.Rounded.Add),
}

/**
 * Storyboard: the four ways to start a Reel, then your Reel projects, newest first, each with
 * its stage and how much is filmed; duplicate or delete (after asking) any of them.
 */
@Composable
fun StoryboardListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onGear: () -> Unit) {
    val c = appContainer()
    val chats by c.storyboard.chats.collectAsStateWithLifecycle()
    val gear by c.storyboard.gear.collectAsStateWithLifecycle()
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val pro by c.entitlements.pro.collectAsStateWithLifecycle()
    val talk = remember { StoryboardTalk(c) }
    var note by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<StoryboardChat?>(null) }
    var door by remember { mutableStateOf<Door?>(null) }

    fun start(d: Door) {
        val why = talk.blocked(null)
        if (why != null) { note = why; return }
        when (d) {
            Door.IDEA -> onOpen(c.storyboard.newChat().id)
            Door.BLANK -> onOpen(c.storyboard.newChat().id.also { id -> c.storyboard.update(id) { it.copy(title = "New Reel").edited(Blank.script()) } })
            else -> door = d
        }
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = RtColors.TextPrimary) }
            Text("Storyboard", style = RtType.title, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            if (!pro) Text("${c.storyboard.startedThisMonth()} of ${StoryboardEngine.FREE_CHATS_A_MONTH} this month", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(end = 12.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("START A REEL", style = RtType.label, color = RtColors.TextTertiary) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Door.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { d ->
                                Column(
                                    Modifier.weight(1f).heightIn(min = 136.dp).clip(RoundedCornerShape(20.dp)).background(RtColors.Surface)
                                        .clickable(role = Role.Button) { start(d) }.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(RtColors.PrimaryContainer), contentAlignment = Alignment.Center) {
                                        Icon(d.icon, null, tint = RtColors.Primary, modifier = Modifier.size(20.dp))
                                    }
                                    Text(d.title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                                    Text(d.sub, style = RtType.caption, color = RtColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
                note?.let { Text(it, style = RtType.caption, color = RtColors.Warning, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (chats.isNotEmpty()) item { Text("YOUR REELS", style = RtType.label, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 8.dp)) }
            items(chats, key = { it.id }) { ch ->
                val latest = reels.filter { it.id in ch.templateIds && it.deletedAt == null }.maxByOrNull { it.updatedAt }
                val s = ch.script
                val shots = s?.let { Placeholders.shots(it).size } ?: 0
                val left = latest?.let { TemplateBuilder.placeholders(it.plan).size }
                val stage = when {
                    s == null -> "Write · no board yet"
                    latest == null -> "Write"
                    left == 0 -> "Edit · all filmed"
                    else -> "Shoot ${shots - (left ?: shots)}/$shots"
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(RtColors.Surface).clickable(role = Role.Button) { onOpen(ch.id) }.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The board at a glance: a block per section, sized by its length.
                    Row(Modifier.width(44.dp).height(56.dp).clip(RoundedCornerShape(8.dp)).background(RtColors.SurfaceRaised), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        s?.sections?.forEachIndexed { i, sec -> Box(Modifier.weight(sec.shots.sumOf { it.durMs }.toFloat().coerceAtLeast(1f)).fillMaxSize().background(sectionColor(i))) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ch.title, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(s?.let { "$shots shots · ${seconds(it.totalMs)} s" }, Format.rideDate(ch.updatedAt)).joinToString(" · "),
                            style = RtType.caption, color = RtColors.TextSecondary,
                        )
                        Text(stage, style = RtType.caption, fontWeight = FontWeight.SemiBold, color = when { stage.startsWith("Edit") -> RtColors.Ok; stage.startsWith("Shoot") -> RtColors.Warning; else -> RtColors.Primary })
                    }
                    IconButton(onClick = { val why = talk.blocked(null); if (why != null) note = why else c.storyboard.duplicate(ch.id)?.let { onOpen(it.id) } }) {
                        Icon(Icons.Rounded.ContentCopy, "Make another like ${ch.title}", tint = RtColors.TextTertiary)
                    }
                    IconButton(onClick = { deleting = ch }) { Icon(Icons.Rounded.Delete, "Delete ${ch.title}", tint = RtColors.TextTertiary) }
                }
            }
            if (chats.isEmpty()) item {
                Text("Your Reel plans show up here: the board, what's filmed, and the Reel they make.", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClick = onGear).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Gear: " + (gear.items.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: "not told yet (phone + handlebar mount)"), style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f), maxLines = 2)
            Text("Edit", style = RtType.button, color = RtColors.Primary)
        }
    }

    deleting?.let { ch ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${ch.title}”?") },
            text = { Text("Its board, versions and chat are deleted. Templates made from it stay in Your Reels.") },
            confirmButton = { TextButton(onClick = { c.storyboard.delete(ch.id); deleting = null }) { Text("Delete", color = RtColors.Error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
    when (door) {
        Door.SCRIPT -> ScriptDialog(onDismiss = { door = null }) { text ->
            door = null
            val id = c.storyboard.newChat().id
            StoryboardTalk.start(c, id, text, ownScript = true)
            onOpen(id)
        }
        Door.RIDE -> RideDialog(onDismiss = { door = null }) { ride, idea ->
            door = null
            val id = c.storyboard.newChat().id
            c.storyboard.update(id) { it.copy(rideId = ride.id, title = ride.name.ifBlank { "Ride Reel" }) }
            StoryboardTalk.start(c, id, idea.ifBlank { "Make a Reel from my ride “${ride.name}”: its best moments, in a story." }, rideId = ride.id)
            onOpen(id)
        }
        else -> Unit
    }
}

@Composable
private fun ScriptDialog(onDismiss: () -> Unit, onGo: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val lines = StoryboardEngine.scriptLines(text).size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your script") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, minLines = 6, maxLines = 14, modifier = Modifier.fillMaxWidth(), placeholder = { Text("One line per thing you say.\nTEXT: for on-screen text\nSHOT: to say what to film") })
                Text(
                    if (lines > 0) "Every word stays exactly as written · $lines line${if (lines > 1) "s" else ""}. Keppo only adds the camera, mount and length." else "Every word stays exactly as written.",
                    style = RtType.caption, color = RtColors.TextSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (lines > 0) onGo(text.trim()) }, enabled = lines > 0) { Text("Make the board") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RideDialog(onDismiss: () -> Unit, onGo: (Ride, String) -> Unit) {
    val c = appContainer()
    val rides by c.rides.observeCompleted().collectAsStateWithLifecycle(emptyList())
    var picked by remember { mutableStateOf<Ride?>(null) }
    var idea by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which ride?") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rides.isEmpty()) Text("Your rides show up here after your first ride.", style = RtType.body, color = RtColors.TextSecondary)
                rides.take(20).forEach { r ->
                    val on = picked?.id == r.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) RtColors.PrimaryContainer else RtColors.SurfaceRaised)
                            .clickable(role = Role.RadioButton) { picked = r }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.name.ifBlank { "Ride" }, style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Format.rideDate(r.startTimeMillis), style = RtType.caption, color = RtColors.TextSecondary)
                    }
                }
                if (picked != null) OutlinedTextField(idea, { idea = it }, minLines = 2, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Anything to aim for? (optional)") })
            }
        },
        confirmButton = { TextButton(onClick = { picked?.let { onGo(it, idea.trim()) } }, enabled = picked != null) { Text("Build the board") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun Bubble(rider: Boolean, text: String, voiceMs: Long? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (rider) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(if (rider) 18.dp else 4.dp, if (rider) 4.dp else 18.dp, 18.dp, 18.dp))
                .background(if (rider) RtColors.PrimaryContainer else RtColors.SurfaceRaised).padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            voiceMs?.let { Text("Voice note · ${it / 60_000}:${String.format(java.util.Locale.US, "%02d", it / 1000 % 60)}", style = RtType.caption, color = RtColors.TextSecondary) }
            Text(text, style = RtType.body, color = RtColors.TextPrimary)
        }
    }
}

/** Studio's floating Storyboard button; the first time, it says what it's for until tapped or dismissed. */
@Composable
fun StoryboardButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("studio-hints", android.content.Context.MODE_PRIVATE) }
    var hint by remember { mutableStateOf(!prefs.getBoolean("storyboard", false)) }
    fun seen() { hint = false; prefs.edit().putBoolean("storyboard", true).apply() }
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (hint) {
            Row(
                Modifier.padding(bottom = 8.dp).widthIn(max = 240.dp).clip(RoundedCornerShape(14.dp)).background(RtColors.SurfaceRaised).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Plan a Reel with Storyboard", style = RtType.caption, color = RtColors.TextPrimary, modifier = Modifier.weight(1f, fill = false))
                Text("✕", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Dismiss") { seen() }.padding(8.dp))
            }
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(RtColors.Primary).clickable(role = Role.Button) { seen(); onOpen() }.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, tint = RtColors.OnPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(6.dp))
            Text("Storyboard", style = RtType.button, color = RtColors.OnPrimary)
        }
    }
}
