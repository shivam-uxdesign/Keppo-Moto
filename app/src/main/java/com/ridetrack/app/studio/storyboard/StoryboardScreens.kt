package com.ridetrack.app.studio.storyboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.studio.VoiceRecorder
import com.ridetrack.app.studio.VoiceTake
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Section colours on the storyboard card and shot cards (the prototype's). */
fun sectionColor(i: Int): Color = listOf(0xFFA5A1FF, 0xFF69C8CB, 0xFFFBBF24, 0xFFFB7185, 0xFF4ADE80, 0xFFF4F4F5, 0xFF85858E).map { Color(it) }[i % 7]

/** Storyboard: "New storyboard", then the saved chats, newest first, with how many shots are filled. */
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
    deleting?.let { ch ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \u201c${ch.title}\u201d?") },
            text = { Text("The chat and its script versions are deleted. Templates made from it stay in Your Reels.") },
            confirmButton = { TextButton(onClick = { c.storyboard.delete(ch.id); deleting = null }) { Text("Delete", color = RtColors.Error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = RtColors.TextPrimary) }
            Text("Storyboard", style = RtType.title, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            if (!pro) Text("${c.storyboard.startedThisMonth()} of ${StoryboardEngine.FREE_CHATS_A_MONTH} this month", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(end = 12.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(RtColors.PrimaryContainer)
                        .clickable(role = Role.Button) {
                            val why = talk.blocked(null)
                            if (why != null) note = why else onOpen(c.storyboard.newChat().id)
                        }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).background(RtColors.Primary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = RtColors.OnPrimary) }
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text("New storyboard", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                        Text("Describe a Reel idea, by text or voice", style = RtType.caption, color = RtColors.TextSecondary)
                    }
                }
                note?.let { Text(it, style = RtType.caption, color = RtColors.Warning, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (chats.isNotEmpty()) item { Text("YOUR CHATS", style = RtType.label, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 8.dp)) }
            items(chats, key = { it.id }) { ch ->
                val tpls = reels.filter { it.id in ch.templateIds && it.deletedAt == null }
                val latest = tpls.maxByOrNull { it.updatedAt }
                val left = latest?.let { com.ridetrack.app.studio.storyboard.TemplateBuilder.placeholders(it.plan).size }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.Surface).clickable(role = Role.Button) { onOpen(ch.id) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ch.title, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(if (tpls.isEmpty()) "No template yet" else "${tpls.size} template${if (tpls.size > 1) "s" else ""}", Format.rideDate(ch.updatedAt)).joinToString(" · "),
                            style = RtType.caption, color = RtColors.TextSecondary,
                        )
                        if (latest != null && left != null) {
                            val total = latest.template?.shots ?: 0
                            Text(if (left == 0) "All shots filled" else "${total - left} of $total shots filled", style = RtType.caption, color = if (left == 0) RtColors.Ok else RtColors.Warning)
                        }
                    }
                    IconButton(onClick = { deleting = ch }) { Icon(Icons.Rounded.Delete, "Delete the chat", tint = RtColors.TextTertiary) }
                }
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
}

/**
 * One Storyboard chat: the conversation (Studio's replies with the storyboard card, memory chips
 * with Undo, one question with tap-to-answer choices, the templates made), and a box to type or
 * hold the mic to talk.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoryboardChatScreen(chatId: String, onBack: () -> Unit, onReview: (version: Int?) -> Unit, onOpenTemplate: (String) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val chats by c.storyboard.chats.collectAsStateWithLifecycle()
    val chat = chats.firstOrNull { it.id == chatId }
    val gear by c.storyboard.gear.collectAsStateWithLifecycle()
    val talk = remember { StoryboardTalk(c) }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var voiceMs by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder() }
    val list = rememberLazyListState()
    LaunchedEffect(chat?.messages?.size) { chat?.messages?.size?.takeIf { it > 0 }?.let { list.animateScrollToItem(it) } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun send(t: String) {
        if (t.isBlank() || busy != null) return
        busy = "Keppo is writing…"
        val ms = voiceMs
        text = ""; voiceMs = null
        scope.launch {
            runCatching { talk.send(chatId, t.trim(), ms) }
            busy = null
        }
    }

    fun startVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val pcm = File(context.cacheDir, "storyboard-note.pcm")
        recording = true
        scope.launch {
            val ms = recorder.record(pcm)
            recording = false
            if (ms < 700) return@launch
            busy = "Writing down your voice note…"
            val heard = runCatching {
                val wav = withContext(Dispatchers.IO) { VoiceRecorder.takeWav(VoiceTake(0, ms, pcm), File(context.cacheDir, "storyboard-note.wav")).readBytes() }
                talk.voiceNote(wav)
            }.getOrNull()
            busy = null
            if (!heard.isNullOrBlank()) { text = (text + " " + heard).trim(); voiceMs = ms }
        }
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = RtColors.TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text(chat?.title ?: "Storyboard", style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chat?.versions?.takeIf { it.isNotEmpty() }?.let { "Version ${chat.currentIndex + 1} of ${it.size}" } ?: "Storyboard", style = RtType.caption, color = RtColors.TextSecondary)
            }
            if ((chat?.versions?.size ?: 0) > 1) TextButton(onClick = { history = true }) { Text("Versions") }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (chat == null || chat.messages.isEmpty()) item {
                Bubble(false, "Tell me your Reel idea, as rough as you like: the title, the steps or the story, the feel. Type it or hold the mic to talk." +
                    if (!gear.asked) " And what do you film with? Cameras, mounts, mics." else "")
            }
            chat?.messages?.forEach { m ->
                item(key = "m${m.at}") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Bubble(m.fromRider, m.text, m.voiceMs)
                        if (m.chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            m.chips.forEach { ch ->
                                Row(Modifier.clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (ch.undone) "Undone: ${ch.text}" else ch.text, style = RtType.caption, color = if (ch.undone) RtColors.TextTertiary else RtColors.Ok)
                                    if (!ch.undone && ch.undo != null) Text("  Undo", style = RtType.caption, color = RtColors.Primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(role = Role.Button) { talk.undo(chatId, m.at, ch) })
                                }
                            }
                        }
                        if (m.question != null && m == chat.messages.lastOrNull()) {
                            Text(m.question, style = RtType.body, color = RtColors.TextPrimary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                m.choices.forEach { choice ->
                                    Text(choice, style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, RtColors.Primary, RoundedCornerShape(50))
                                        .clickable(role = Role.Button) { send(choice) }.padding(horizontal = 16.dp, vertical = 9.dp))
                                }
                            }
                        }
                        m.version?.let { v -> chat.versions.getOrNull(v - 1)?.let { s -> StoryboardCard(s, v, m.changes, current = v - 1 == chat.currentIndex, onUse = { c.storyboard.update(chatId) { it.copy(current = v - 1) } }, onReview = { onReview(v) }, making = making) {
                            if (making) return@StoryboardCard
                            making = true
                            scope.launch {
                                runCatching {
                                    val p = TemplateBuilder.make(c, chatId, s, v)
                                    c.storyboard.update(chatId) { it.copy(templateIds = it.templateIds + p.id, messages = it.messages + ChatMessage(false, "Template made: ${s.title} (version $v).", System.currentTimeMillis(), templateId = p.id)) }
                                    ToFilmList.sync(c)
                                }.onFailure { e -> c.errors.record("Storyboard", "Couldn't make the template", e) }
                                making = false
                            }
                        } } }
                        m.templateId?.let { id -> TemplateTile(id, onOpenTemplate) }
                    }
                }
            }
            busy?.let { b -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), color = RtColors.Primary, strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text(b, style = RtType.caption, color = RtColors.TextSecondary) } } }
        }
        val why = talk.blocked(chat)
        if (why != null && chat?.messages.isNullOrEmpty()) Text(why, style = RtType.caption, color = RtColors.Warning, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text(if (recording) "Listening… let go to stop" else if (chat?.script == null) "Describe your Reel idea…" else if (chat.versions.size > 1) "Change version ${chat.currentIndex + 1}…" else "Describe a change…") },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), maxLines = 5,
                colors = TextFieldDefaults.colors(focusedContainerColor = RtColors.Surface, unfocusedContainerColor = RtColors.Surface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (recording) RtColors.Recording else RtColors.SurfaceRaised)
                    .semantics { contentDescription = "Hold to talk" }
                    .pointerInput(Unit) { detectTapGestures(onPress = { startVoice(); tryAwaitRelease(); recorder.stop() }) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Mic, null, tint = RtColors.TextPrimary) }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(RtColors.Primary).clickable(role = Role.Button, onClickLabel = "Send") { send(text) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ArrowUpward, "Send", tint = RtColors.OnPrimary) }
        }
    }
    if (history && chat != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { history = false },
            title = { Text("Versions") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chat.versions.indices.reversed().toList()) { i ->
                        val v = chat.versions[i]
                        val made = chat.messages.firstOrNull { it.version == i + 1 }
                        val what = made?.changes?.takeIf { it.isNotEmpty() }?.let { ch -> ch.take(3).joinToString("\n") + if (ch.size > 3) "\n+${ch.size - 3} more" else "" }
                            ?: if (i == 0) "First draft" else made?.let { "From the chat" } ?: "Your edit on the shot cards"
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (i == chat.currentIndex) RtColors.PrimaryContainer else RtColors.Surface)
                                .clickable(role = Role.Button, onClickLabel = "Use version ${i + 1}") { c.storyboard.update(chatId) { it.copy(current = i) }; history = false }.padding(12.dp),
                        ) {
                            Text("Version ${i + 1}" + if (i == chat.currentIndex) " · current" else "", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                            Text("${Placeholders.shots(v).size} shots · ${v.totalMs / 1000} s", style = RtType.caption, color = RtColors.TextSecondary)
                            Text(what, style = RtType.caption, color = RtColors.TextSecondary)
                            Text("Review shots", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { history = false; onReview(i + 1) }.padding(top = 6.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { history = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Bubble(rider: Boolean, text: String, voiceMs: Long? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (rider) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(if (rider) 18.dp else 4.dp, if (rider) 4.dp else 18.dp, 18.dp, 18.dp))
                .background(if (rider) RtColors.PrimaryContainer else RtColors.Surface).padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            voiceMs?.let { Text("Voice note · ${it / 60_000}:${String.format(java.util.Locale.US, "%02d", it / 1000 % 60)}", style = RtType.caption, color = RtColors.TextSecondary) }
            Text(text, style = RtType.body, color = RtColors.TextPrimary)
        }
    }
}

/**
 * The storyboard in a reply: what changed from the version before (worked out by the app, not
 * Gemini's say-so), every shot in its part, the music note, and Review shots / Make template. An
 * older version says so and can be made the current one.
 */
@Composable
fun StoryboardCard(
    s: com.ridetrack.app.studio.Script, version: Int, changes: List<String>, current: Boolean,
    onUse: () -> Unit, onReview: () -> Unit, making: Boolean, onMake: () -> Unit,
) {
    val shots = Placeholders.shots(s)
    var all by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(RtColors.Surface)
            .border(1.dp, if (current) RtColors.Primary else RtColors.Outline, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("STORYBOARD · VERSION $version" + if (current) " · CURRENT" else "", style = RtType.label, color = if (current) RtColors.Primary else RtColors.TextTertiary, modifier = Modifier.weight(1f))
            if (!current) Text("Use this", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onUse).padding(4.dp))
        }
        Text(s.title, style = RtType.headline, color = RtColors.TextPrimary)
        Text("${s.format.label} · ${s.totalMs / 1000} s · ${shots.size} shot${if (shots.size != 1) "s" else ""} · ${shots.count { !it.second.board!!.riding || it.second.board!!.match.mustRecord }} to film", style = RtType.caption, color = RtColors.TextSecondary)
        if (changes.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.SurfaceRaised).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("WHAT CHANGED", style = RtType.label, color = RtColors.TextTertiary)
                changes.forEach { ch ->
                    Text(ch, style = RtType.caption, color = when { ch.startsWith("Removed") -> RtColors.Error; ch.startsWith("Added") -> RtColors.Ok; else -> RtColors.TextPrimary })
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            s.sections.forEachIndexed { i, sec -> Box(Modifier.weight(sec.shots.sumOf { it.durMs }.toFloat().coerceAtLeast(1f)).fillMaxSize().background(sectionColor(i))) }
        }
        val limit = if (all) Int.MAX_VALUE else 8
        var n = 0
        s.sections.forEachIndexed { i, sec ->
            if (n >= limit) return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(sectionColor(i), CircleShape))
                Spacer(Modifier.size(8.dp))
                Text((sec.text ?: sec.kind.label).uppercase(java.util.Locale.US), style = RtType.label, color = RtColors.TextSecondary)
            }
            sec.shots.forEach { sh ->
                val b = sh.board ?: return@forEach
                if (n >= limit) return@forEach
                n++
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.Top) {
                    Text("#${Placeholders.number(s, sh.id!!).trimStart('0')}", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.widthIn(min = 28.dp))
                    Text(b.action, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(String.format(java.util.Locale.US, "%.1f s", b.durationMs / 1000.0), style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (shots.size > 8) Text(if (all) "Show less" else "Show all ${shots.size} shots", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { all = !all }.padding(vertical = 2.dp))
        s.musicNote?.let { m ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.MusicNote, null, tint = RtColors.TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("Music: $m", style = RtType.caption, color = RtColors.TextSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.ridetrack.app.ui.components.SecondaryButton("Review shots", onReview, Modifier.weight(1f))
            com.ridetrack.app.ui.components.PrimaryButton(if (making) "Making…" else "Make template", onMake, Modifier.weight(1f), enabled = !making)
        }
    }
}

@Composable
private fun TemplateTile(id: String, onOpen: (String) -> Unit) {
    val c = appContainer()
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val p = reels.firstOrNull { it.id == id } ?: return
    val left = TemplateBuilder.placeholders(p.plan).size
    val total = p.template?.shots ?: 0
    Row(
        Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(RtColors.Surface).border(1.dp, RtColors.Outline, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onOpen(id) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Template · version ${p.template?.version ?: 1}", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text("${p.durationMs / 1000} s · ${total - left} of $total shots filled", style = RtType.caption, color = RtColors.TextSecondary)
            Text("Open in editor", style = RtType.button, color = RtColors.Primary)
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
