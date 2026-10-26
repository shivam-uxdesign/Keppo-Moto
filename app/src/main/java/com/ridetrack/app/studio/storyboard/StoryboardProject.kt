package com.ridetrack.app.studio.storyboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.studio.Section
import com.ridetrack.app.studio.VoiceRecorder
import com.ridetrack.app.studio.VoiceTake
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** A note at the bottom of the project: what just happened, with Undo (and the changes, when Keppo made them). */
private data class Snack(val text: String, val undo: (() -> Unit)?, val changes: List<String> = emptyList(), val at: Long = System.currentTimeMillis())

/** Quick asks in the Ask Keppo sheet; each goes to Keppo as written, about the selection when there is one. */
private val QUICK = listOf("Punchier", "Shorter", "Funnier", "More Hinglish", "Split this into two shots", "Add a hook", "Try a new take")

/**
 * A Storyboard project: one board for one Reel, in three stages. Write is the board itself (shot
 * cards edited in place, select some and Ask Keppo, rules pinned for this Reel, versions, the
 * health strip); Shoot is the shots by setup with what's filmed; Edit makes the Reel in the
 * editor. Keppo's changes land on the board where they can be seen, with Undo.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StoryboardProjectScreen(
    chatId: String,
    onBack: () -> Unit,
    onFill: (reelId: String, shotId: String) -> Unit,
    onReadLines: (reelId: String) -> Unit,
    onOpenTemplate: (reelId: String) -> Unit,
    onPlay: (chatId: String) -> Unit,
    onShoot: (reelId: String, shotIds: List<String>) -> Unit,
) {
    val c = appContainer()
    val context = LocalContext.current
    val chats by c.storyboard.chats.collectAsStateWithLifecycle()
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val gear by c.storyboard.gear.collectAsStateWithLifecycle()
    val working by c.storyboard.working.collectAsStateWithLifecycle()
    val chat = chats.firstOrNull { it.id == chatId } ?: return
    val s = chat.script
    val talk = remember { StoryboardTalk(c) }
    val scope = rememberCoroutineScope()
    var tab by remember(chatId) { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(listOf<String>()) }
    var sheet by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var glow by remember { mutableStateOf(setOf<String>()) }
    var snack by remember { mutableStateOf<Snack?>(null) }
    var diff by remember { mutableStateOf<List<String>?>(null) }
    var askClips by remember { mutableStateOf<Script?>(null) }
    var making by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val template = reels.filter { it.id in chat.templateIds && it.deletedAt == null }.maxByOrNull { it.updatedAt }
    LaunchedEffect(snack?.at) { if (snack != null) { delay(6_000); snack = null } }
    LaunchedEffect(glow) { if (glow.isNotEmpty()) { delay(2_500); glow = emptySet() } }

    /** The rider's own change to the board: a "Your edits" version, with Undo. */
    fun edit(n: Script, what: String) {
        val before = chat.script
        c.storyboard.update(chatId) { it.edited(n) }
        snack = Snack(what, before?.let { b -> { c.storyboard.update(chatId) { ch -> ch.edited(b) }; Unit } })
    }

    fun ask(text: String, ownScript: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || busy != null) return
        talk.blocked(chat)?.let { why -> snack = Snack(why, null); sheet = false; return }
        busy = "Keppo is writing…"
        sheet = false
        val sel = selected.toList()
        scope.launch {
            val ride = chat.rideId?.let { runCatching { RideBrief.of(c, it) }.getOrNull() }
            val turn = runCatching { talk.send(chatId, t, null, sel, ownScript, ride) }.getOrNull()
            busy = null
            if (turn == null) { snack = Snack("Couldn't reach Keppo. Your board is as it was.", null); return@launch }
            if (turn.changed.isNotEmpty()) {
                glow = turn.changed
                val from = turn.fromIndex
                snack = Snack(
                    "Keppo changed ${turn.changed.size} shot${if (turn.changed.size > 1) "s" else ""}",
                    if (from >= 0) ({ c.storyboard.update(chatId) { it.copy(current = from) }; glow = emptySet() }) else null,
                    turn.changes,
                )
            } else turn.chat.messages.lastOrNull()?.takeIf { !it.fromRider }?.let { m ->
                // A question, a rule or an answer: show it where the rider looks.
                if (m.question != null) sheet = true else snack = Snack(m.text.take(140), null)
            }
        }
    }

    fun makeTemplate(script: Script, clips: ClipSource) {
        making = true
        scope.launch {
            runCatching {
                val p = TemplateBuilder.make(c, chatId, script, chat.currentIndex + 1, clips, existing = template?.id, rideId = chat.rideId)
                c.storyboard.update(chatId) { it.copy(templateIds = (it.templateIds - p.id) + p.id) }
                ToFilmList.sync(c)
                snack = Snack(if (template == null) "Template made · ${clips.label}" else "Template updated from the board", null)
            }.onFailure { e -> c.errors.record("Storyboard", "Couldn't make the template", e); snack = Snack("Couldn't make the template (${e.message ?: e.javaClass.simpleName})", null) }
            making = false
        }
    }

    fun startMake() {
        val sc = chat.script ?: return
        val said = chat.clips
        if (said != null) makeTemplate(sc, said) else askClips = sc
    }

    Box(Modifier.fillMaxSize().background(RtColors.Background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Top bar: back, the Reel's name and length, Play.
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = RtColors.TextPrimary) }
                Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Rename") { renaming = true }) {
                    Text(chat.title, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        s?.let { "${it.format.label} · ${seconds(it.totalMs)} of ${it.format.maxSec} s · ${Placeholders.shots(it).size} shots" } ?: "New Reel",
                        style = RtType.caption, color = RtColors.TextSecondary,
                    )
                }
                if (s != null) Row(
                    Modifier.clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised).clickable(role = Role.Button, onClickLabel = "Play the animatic") { onPlay(chatId) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = RtColors.TextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play", style = RtType.button, color = RtColors.TextPrimary)
                }
            }
            // Stages.
            val open0 = template?.let { TemplateBuilder.placeholders(it.plan).size }
            val total = s?.let { Placeholders.shots(it).size } ?: 0
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Write", "Shoot " + (if (open0 != null) "${total - open0}/$total" else "0/$total"), "Edit").forEachIndexed { i, label ->
                    val on = tab == i
                    Text(
                        label, style = RtType.button, color = if (on) RtColors.Primary else RtColors.TextSecondary,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) RtColors.PrimaryContainer else RtColors.Surface)
                            .clickable(role = Role.Tab, enabled = s != null || i == 0) { tab = i }.padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    s == null -> EmptyBoard(chat, onAsk = { sheet = true }, onBlank = { c.storyboard.update(chatId) { it.edited(Blank.script()) } })
                    tab == 0 -> WriteBoard(
                        chat, s, open, selected, glow, gear,
                        onOpen = { id -> open = if (open == id) null else id },
                        onSelect = { id -> selected = if (id in selected) selected - id else selected + id },
                        onClearSelection = { selected = emptyList() },
                        onAskSelected = { sheet = true },
                        onEdit = ::edit,
                        onUnpin = { p -> c.storyboard.update(chatId) { it.copy(pins = it.pins - p) }; snack = Snack("Rule removed: $p", { c.storyboard.update(chatId) { ch -> ch.copy(pins = ch.pins + p) }; Unit }) },
                        onClearClips = { val was = chat.clips; c.storyboard.update(chatId) { it.copy(clips = null) }; snack = Snack("Footage will be asked at Make template", { c.storyboard.update(chatId) { ch -> ch.copy(clips = was) }; Unit }) },
                        onVersion = { i ->
                            val from = chat.currentIndex
                            if (i != from) {
                                c.storyboard.update(chatId) { it.copy(current = i) }
                                snack = Snack("Back to v${i + 1}: ${chat.versionName(i)}", { c.storyboard.update(chatId) { ch -> ch.copy(current = from) }; Unit }, StoryboardEngine.diff(chat.versions[from], chat.versions[i]))
                            }
                        },
                    )
                    tab == 1 -> ShootStage(chat, s, template, making, onMake = ::startMake, onFill = onFill, onShoot = onShoot, onChangeClips = { askClips = s })
                    else -> EditStage(chat, s, template, making, onMake = ::startMake, onOpen = onOpenTemplate, onReadLines = onReadLines)
                }
            }
            // Ask Keppo: always one tap away.
            Row(
                Modifier.fillMaxWidth().background(RtColors.Background).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(50)).background(RtColors.Surface)
                        .clickable(role = Role.Button, enabled = busy == null && chatId !in working) { sheet = true }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val writing = busy ?: if (chatId in working) "Keppo is writing…" else null
                    if (writing != null) CircularProgressIndicator(Modifier.size(18.dp), color = RtColors.Primary, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.AutoAwesome, null, tint = RtColors.Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        writing ?: when {
                            selected.isNotEmpty() -> "Ask about ${selectionLabel(s, selected).lowercase(Locale.US)}…"
                            s == null -> "Tell Keppo your idea…"
                            else -> "Ask Keppo to change anything…"
                        },
                        style = RtType.body, color = if (writing != null) RtColors.TextPrimary else RtColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // The note at the bottom: Undo, and what changed.
        snack?.let { sn ->
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 84.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(RtColors.Inverse).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sn.text, style = RtType.body, color = RtColors.OnInverse, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (sn.changes.isNotEmpty()) TextButton(onClick = { diff = sn.changes }) { Text("See changes", color = RtColors.OnInverse.copy(alpha = 0.8f)) }
                sn.undo?.let { u -> TextButton(onClick = { u(); snack = null }) { Text("Undo", color = RtColors.OnInverse, fontWeight = FontWeight.Bold) } }
            }
        }
    }

    if (sheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = state, containerColor = RtColors.Surface) {
            AskSheet(chat, selected, talk, onClearSelection = { selected = emptyList() }, onSend = { t, own -> ask(t, own) })
        }
    }
    diff?.let { list ->
        AlertDialog(
            onDismissRequest = { diff = null },
            title = { Text("What changed") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { ch -> Text(ch, style = RtType.body, color = when { ch.startsWith("Removed") -> RtColors.Error; ch.startsWith("Added") -> RtColors.Ok; else -> RtColors.TextPrimary }) }
                }
            },
            confirmButton = { TextButton(onClick = { diff = null }) { Text("Done") } },
        )
    }
    askClips?.let { sc ->
        AlertDialog(
            onDismissRequest = { askClips = null },
            title = { Text("Footage for this Reel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClipSource.entries.forEach { src ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.SurfaceRaised)
                                .clickable(role = Role.Button) { askClips = null; makeTemplate(sc, src) }.padding(12.dp),
                        ) {
                            Text(src.label, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                            Text(src.detail, style = RtType.caption, color = RtColors.TextSecondary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askClips = null }) { Text("Cancel") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name this Reel") },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) c.storyboard.update(chatId) { it.copy(title = name.trim()) }; renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

/** "3.5" seconds. */
internal fun seconds(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

/** "Shot #3" or "3 shots". */
internal fun selectionLabel(s: Script?, ids: List<String>): String =
    if (ids.size == 1 && s != null) "Shot #" + Placeholders.number(s, ids[0]).trimStart('0') else "${ids.size} shots"

/** A blank board: one shot to start from. */
internal object Blank {
    fun script(): Script = Placeholders.empty(
        "New Reel", com.ridetrack.app.studio.PieceFormat.REEL,
        listOf(com.ridetrack.app.studio.SectionKind.HOOK to listOf("s1" to Storyboard("New shot: say what to film", riding = false, durationMs = 2_000))),
    )
}

@Composable
private fun EmptyBoard(chat: StoryboardChat, onAsk: () -> Unit, onBlank: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Tell Keppo your idea", style = RtType.headline, color = RtColors.TextPrimary)
        Text(
            "As rough as you like: the story, the steps, the feel. Keppo asks a question or two, then lays out the shots here. Or paste your own script and every word stays as you wrote it.",
            style = RtType.body, color = RtColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        PrimaryButton("Describe it", onAsk, Modifier.fillMaxWidth())
        if (chat.messages.isEmpty()) SecondaryButton("Start with a blank board", onBlank, Modifier.fillMaxWidth())
    }
}

/** Write: the board, with rules, versions, the length bar, the health strip and the shots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WriteBoard(
    chat: StoryboardChat,
    s: Script,
    open: String?,
    selected: List<String>,
    glow: Set<String>,
    gear: Gear,
    onOpen: (String) -> Unit,
    onSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    onAskSelected: () -> Unit,
    onEdit: (Script, String) -> Unit,
    onUnpin: (String) -> Unit,
    onClearClips: () -> Unit,
    onVersion: (Int) -> Unit,
) {
    val list = rememberLazyListState()
    LazyColumn(state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Rules for this Reel (and the footage choice, when said).
        if (chat.pins.isNotEmpty() || chat.clips != null) item(key = "pins") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("RULES FOR THIS REEL", style = RtType.label, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.CenterVertically))
                chat.clips?.let { PinChip(it.label, onRemove = onClearClips) }
                chat.pins.forEach { p -> PinChip(p, onRemove = { onUnpin(p) }) }
            }
        }
        if (chat.versions.size > 1) item(key = "versions") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("VERSIONS", style = RtType.label, color = RtColors.TextTertiary)
                chat.versions.indices.forEach { i ->
                    val on = i == chat.currentIndex
                    Text(
                        "v${i + 1}" + if (on) " · ${chat.versionName(i)}" else "",
                        style = RtType.caption, fontWeight = FontWeight.SemiBold, color = if (on) RtColors.OnInverse else RtColors.TextSecondary, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) RtColors.Inverse else RtColors.SurfaceRaised)
                            .clickable(role = Role.Button, onClickLabel = "Use version ${i + 1}: ${chat.versionName(i)}") { onVersion(i) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        item(key = "bar") { LengthBar(s) }
        item(key = "health") { HealthStrip(s, gear, onEdit) }
        if (selected.isNotEmpty()) item(key = "selection") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.PrimaryContainer).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selectionLabel(s, selected)} selected", style = RtType.bodyStrong, color = RtColors.Primary, modifier = Modifier.weight(1f))
                TextButton(onClick = onAskSelected) { Text("Ask Keppo", fontWeight = FontWeight.Bold) }
                TextButton(onClick = onClearSelection) { Text("Clear", color = RtColors.TextPrimary) }
            }
        }
        s.sections.forEachIndexed { k, sec ->
            item(key = "sec$k") { SectionHead(k, sec) }
            sec.shots.forEach { sh ->
                val id = sh.id ?: return@forEach
                if (sh.board == null) return@forEach
                item(key = "shot-$id") {
                    BoardCard(
                        s, sh, open = open == id, selected = id in selected, glowing = id in glow,
                        onOpen = { onOpen(id) }, onSelect = { onSelect(id) }, onEdit = onEdit,
                    )
                }
            }
        }
        item(key = "add") {
            Text(
                "+ Add a shot", style = RtType.button, color = RtColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, RtColors.Outline, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { Placeholders.shots(s).lastOrNull()?.second?.id?.let { last -> onEdit(ShotEdits.add(s, last), "Added a shot") } }.padding(vertical = 14.dp),
            )
        }
        s.musicNote?.let { m -> item(key = "music") { Text("Music: $m", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 4.dp)) } }
    }
}

@Composable
private fun PinChip(label: String, onRemove: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.PushPin, null, tint = RtColors.Primary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = RtType.caption, color = RtColors.TextPrimary)
        Box(Modifier.size(32.dp).clickable(role = Role.Button, onClickLabel = "Remove the rule $label", onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, null, tint = RtColors.TextTertiary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun LengthBar(s: Script) {
    val max = s.format.maxSec * 1000L
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(RtColors.Surface), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        s.sections.forEachIndexed { i, sec ->
            sec.shots.forEach { sh -> Box(Modifier.weight(sh.durMs.toFloat().coerceAtLeast(1f)).fillMaxSize().background(sectionColor(i))) }
        }
        val rest = (max - s.totalMs).coerceAtLeast(0)
        if (rest > 0) Box(Modifier.weight(rest.toFloat()).fillMaxSize())
    }
}

@Composable
private fun SectionHead(k: Int, sec: Section) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Box(Modifier.size(8.dp).background(sectionColor(k), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text((sec.text ?: sec.kind.label).uppercase(Locale.US), style = RtType.label, color = RtColors.TextSecondary)
        Spacer(Modifier.width(8.dp))
        Text(seconds(sec.shots.sumOf { it.durMs }) + " s", style = RtType.caption, color = RtColors.TextTertiary)
    }
}

/**
 * One shot on the board. Collapsed: number (tap to select), the placeholder, what to film, its
 * setup, the Say line and its captions. Open: everything edited in place; Done saves (the fields
 * changed are then locked, so Keppo leaves them).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardCard(
    s: Script,
    sh: ScriptShot,
    open: Boolean,
    selected: Boolean,
    glowing: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onEdit: (Script, String) -> Unit,
) {
    val b = sh.board!!
    val id = sh.id!!
    val num = Placeholders.number(s, id).trimStart('0')
    val ring by animateColorAsState(if (glowing || selected) RtColors.Primary else Color.Transparent, tween(400), label = "ring")
    val text = s.texts.firstOrNull { it.shotId == id }?.text
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(RtColors.Surface).border(2.dp, ring, RoundedCornerShape(20.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (selected) RtColors.Primary else RtColors.SurfaceRaised)
                    .clickable(role = Role.Checkbox, onClickLabel = if (selected) "Unselect shot $num" else "Select shot $num", onClick = onSelect),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = RtColors.OnPrimary, modifier = Modifier.size(18.dp))
                else Text(num, style = RtType.caption, fontWeight = FontWeight.Bold, color = RtColors.TextPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised), contentAlignment = Alignment.Center) {
                Text(when (b.framingKind) { Framing.EXTREME -> "EYES"; Framing.CLOSE -> "CU"; Framing.MEDIUM -> "MED"; Framing.WIDE -> "WIDE" }, style = RtType.label, color = RtColors.Primary)
                Text(seconds(b.durationMs) + " s", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = if (open) "Close shot $num" else "Open shot $num", onClick = onOpen),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(b.action, style = RtType.body, color = RtColors.TextPrimary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(b.camera, b.mount, if (b.riding) "Riding" else "Still").forEach { Tag(it) }
                }
                b.say?.let { say ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("SAY", style = RtType.label, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(RtColors.PrimaryContainer).padding(horizontal = 5.dp, vertical = 2.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("“$say”", style = RtType.caption, color = RtColors.TextPrimary)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag("CC · " + when (b.caption) { Captions.OFF -> "Off"; Captions.CUSTOM -> "Custom"; else -> if (b.say != null) "From Say" else "None" }, dim = b.caption == Captions.OFF || (b.caption == Captions.SAY && b.say == null))
                    text?.let { Tag("T · $it", color = RtColors.Warning) }
                    if (glowing) Tag("Changed by Keppo", color = RtColors.Primary)
                    if (sh.locked.isNotEmpty()) Tag("Yours: " + sh.locked.joinToString { lockName(it) }, color = RtColors.Left)
                    b.fallback?.let { Tag("Using ${it.using}", color = RtColors.Warning) }
                }
            }
        }
        if (open) ShotEditor(s, sh, onEdit, onDone = onOpen)
    }
}

internal fun lockName(f: String): String = when (f) {
    ShotFields.ACTION -> "what to film"; ShotFields.DURATION -> "length"; ShotFields.SAY -> "Say"; ShotFields.CAPTION -> "captions"; ShotFields.TEXT -> "text"
    else -> f
}

@Composable
private fun Tag(text: String, color: Color = RtColors.TextSecondary, dim: Boolean = false) {
    Text(
        text, style = RtType.caption, color = if (dim) RtColors.TextTertiary else color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(8.dp)).background(RtColors.SurfaceRaised).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The shot open on the board: its words in three lanes, the setup, and moving or removing it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShotEditor(s: Script, sh: ScriptShot, onEdit: (Script, String) -> Unit, onDone: () -> Unit) {
    val b = sh.board!!
    val id = sh.id!!
    var action by remember(id, b) { mutableStateOf(b.action) }
    var say by remember(id, b) { mutableStateOf(b.say.orEmpty()) }
    var caption by remember(id, b) { mutableStateOf(b.caption) }
    var captionText by remember(id, b) { mutableStateOf(b.captionText.orEmpty()) }
    val oldText = s.texts.firstOrNull { it.shotId == id }?.text.orEmpty()
    var onScreen by remember(id, s.texts) { mutableStateOf(oldText) }
    var len by remember(id, b) { mutableStateOf(b.durationMs) }
    var framing by remember(id, b) { mutableStateOf(b.framing) }
    var camera by remember(id, b) { mutableStateOf(b.camera) }
    var mount by remember(id, b) { mutableStateOf(b.mount) }
    var riding by remember(id, b) { mutableStateOf(b.riding) }

    fun save() {
        var n = ShotEdits.edit(s, id) {
            it.copy(
                action = action.trim().ifBlank { it.action }, say = say.trim().ifBlank { null }, caption = caption,
                captionText = captionText.trim().ifBlank { null }, durationMs = len, framing = framing,
                camera = camera.trim().ifBlank { it.camera }, mount = mount.trim().ifBlank { it.mount }, riding = riding,
            )
        }
        if (onScreen.trim() != oldText) n = ShotEdits.setText(n, id, onScreen.trim())
        if (n != s) onEdit(n, "Shot #${Placeholders.number(s, id).trimStart('0')} saved · yours now")
        onDone()
    }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("What to film", action, { action = it }, minLines = 2)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SAY · VOICE-OVER", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                val locked = ShotFields.SAY in sh.locked
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Switch, onClickLabel = if (locked) "Let Keppo change this line" else "Lock this line") {
                        onEdit(ShotEdits.lock(s, id, ShotFields.SAY, !locked), if (locked) "Keppo may change this line again" else "Line locked: Keppo won't change it")
                    }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if (locked) RtColors.Primary else RtColors.TextTertiary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (locked) "Locked" else "Lock", style = RtType.caption, color = if (locked) RtColors.Primary else RtColors.TextTertiary)
                }
            }
            Field(null, say, { say = it }, placeholder = "What you say over this shot", minLines = 2)
            val need = Health.sayMs(say)
            if (need > 0) Text("Takes about ${seconds(need)} s to say · the shot is ${seconds(len)} s", style = RtType.caption, color = if (need > len) RtColors.Warning else RtColors.TextTertiary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CAPTIONS", style = RtType.label, color = RtColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Captions.SAY to "From Say", Captions.CUSTOM to "Custom", Captions.OFF to "Off").forEach { (k, label) ->
                    val on = caption == k
                    Text(
                        label, style = RtType.button, color = if (on) RtColors.Primary else RtColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) RtColors.PrimaryContainer else RtColors.SurfaceRaised)
                            .clickable(role = Role.RadioButton) { caption = k }.padding(vertical = 9.dp),
                    )
                }
            }
            if (caption == Captions.CUSTOM) Field(null, captionText, { captionText = it }, placeholder = "The caption words")
            Text(
                when (caption) {
                    Captions.CUSTOM -> "These words show as captions, timed to your voice-over (or to the shot)."
                    Captions.OFF -> "No captions on this shot."
                    else -> "Your Say line shows as captions, timed to your recording."
                },
                style = RtType.caption, color = RtColors.TextTertiary,
            )
        }
        Field("On-screen text", onScreen, { onScreen = it.take(60) }, placeholder = "A title or label, e.g. The pour")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SETUP", style = RtType.label, color = RtColors.TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Framing.entries.forEach { f ->
                    val on = Framing.of(framing) == f
                    Choice(f.label, on) { framing = f.label.lowercase(Locale.US) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field("Camera", camera, { camera = it }) }
                Box(Modifier.weight(1f)) { Field("Mount", mount, { mount = it }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice("Still", !riding) { riding = false }
                Choice("Riding", riding) { riding = true }
                Spacer(Modifier.weight(1f))
                Stepper(len) { len = it }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onEdit(ShotEdits.move(s, id, -1), "Moved shot earlier"); onDone() }) { Text("Earlier") }
            TextButton(onClick = { onEdit(ShotEdits.move(s, id, 1), "Moved shot later"); onDone() }) { Text("Later") }
            TextButton(onClick = { onEdit(ShotEdits.add(s, id), "Duplicated the shot"); onDone() }) { Text("Copy") }
            TextButton(onClick = { onEdit(ShotEdits.delete(s, id), "Removed shot"); onDone() }) { Text("Remove", color = RtColors.Error) }
            Spacer(Modifier.weight(1f))
            PrimaryButton("Done", ::save)
        }
    }
}

@Composable
private fun Field(label: String?, value: String, onChange: (String) -> Unit, placeholder: String? = null, minLines: Int = 1) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it.uppercase(Locale.US), style = RtType.label, color = RtColors.TextSecondary) }
        TextField(
            value, onChange, minLines = minLines, maxLines = 5, modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { p -> { Text(p, color = RtColors.TextTertiary) } }, shape = RoundedCornerShape(12.dp), textStyle = RtType.body,
            colors = TextFieldDefaults.colors(focusedContainerColor = RtColors.SurfaceRaised, unfocusedContainerColor = RtColors.SurfaceRaised, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, style = RtType.caption, fontWeight = FontWeight.SemiBold, color = if (on) RtColors.Primary else RtColors.TextSecondary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) RtColors.PrimaryContainer else RtColors.SurfaceRaised)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Length in half seconds, 0.5–10 s. */
@Composable
private fun Stepper(ms: Long, onChange: (Long) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = "Shorter") { onChange((ms - 500).coerceAtLeast(StoryboardJson.MIN_MS)) }, contentAlignment = Alignment.Center) {
            Text("−", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        }
        Text(seconds(ms) + " s", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = "Longer") { onChange((ms + 500).coerceAtMost(StoryboardJson.MAX_MS)) }, contentAlignment = Alignment.Center) {
            Text("+", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        }
    }
}

/**
 * Ask Keppo: the conversation for this Reel, what the ask is about (the selection, or the whole
 * board), quick asks, a box to type or hold the mic, and "This is my script" to paste one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskSheet(chat: StoryboardChat, selected: List<String>, talk: StoryboardTalk, onClearSelection: () -> Unit, onSend: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var own by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var hearing by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder() }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val list = rememberLazyListState()
    LaunchedEffect(chat.messages.size) { if (chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.size) }

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
            hearing = true
            val heard = runCatching {
                val wav = withContext(Dispatchers.IO) { VoiceRecorder.takeWav(VoiceTake(0, ms, pcm), File(context.cacheDir, "storyboard-note.wav")).readBytes() }
                talk.voiceNote(wav)
            }.getOrNull()
            hearing = false
            if (!heard.isNullOrBlank()) text = (text + " " + heard).trim()
        }
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        Text("Ask Keppo", style = RtType.headline, color = RtColors.TextPrimary)
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 80.dp), state = list, contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chat.messages.isEmpty()) item {
                Bubble(false, "Tell me your Reel idea, as rough as you like, or paste your script and tap \"This is my script\": every word stays as you wrote it.")
            }
            chat.messages.forEachIndexed { i, m ->
                item(key = "m${m.at}") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Bubble(m.fromRider, m.text, m.voiceMs)
                        if (m.chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            m.chips.forEach { ch -> Tag(if (ch.undone) "Undone: ${ch.text}" else ch.text, color = if (ch.undone) RtColors.TextTertiary else RtColors.Ok) }
                        }
                        if (m.version != null && m.changes.isNotEmpty()) Text("v${m.version}: " + m.changes.take(3).joinToString(" · ") + if (m.changes.size > 3) " · +${m.changes.size - 3}" else "", style = RtType.caption, color = RtColors.TextTertiary)
                        if (m.question != null && i == chat.messages.lastIndex) {
                            Text(m.question, style = RtType.body, color = RtColors.TextPrimary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                m.choices.forEach { choice ->
                                    Text(choice, style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, RtColors.Primary, RoundedCornerShape(50))
                                        .clickable(role = Role.Button) { onSend(choice, false) }.padding(horizontal = 16.dp, vertical = 9.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        // What this ask is about.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                if (selected.isNotEmpty()) "Changing ${selectionLabel(chat.script, selected).lowercase(Locale.US)} only" else if (chat.script == null) "A new board" else "Changing the whole board",
                style = RtType.caption, fontWeight = FontWeight.SemiBold, color = if (selected.isNotEmpty()) RtColors.Primary else RtColors.TextSecondary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected.isNotEmpty()) RtColors.PrimaryContainer else RtColors.SurfaceRaised).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (selected.isNotEmpty()) TextButton(onClick = onClearSelection) { Text("Whole board") }
        }
        if (chat.script != null) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QUICK.forEach { q ->
                Text(q, style = RtType.button, color = RtColors.TextPrimary, modifier = Modifier.clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised)
                    .clickable(role = Role.Button) { onSend(q, false) }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Choice("This is my script", own) { own = !own }
            Spacer(Modifier.width(8.dp))
            Text(if (own) "Every word stays exactly as written" else "Keppo writes from your idea", style = RtType.caption, color = RtColors.TextTertiary)
        }
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text(when { recording -> "Listening… let go to stop"; hearing -> "Writing down your voice note…"; own -> "Paste your script"; else -> "e.g. make the hook punchier" }, color = RtColors.TextTertiary) },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), maxLines = if (own) 10 else 5,
                colors = TextFieldDefaults.colors(focusedContainerColor = RtColors.SurfaceRaised, unfocusedContainerColor = RtColors.SurfaceRaised, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (recording) RtColors.Recording else RtColors.SurfaceRaised)
                    .semantics { contentDescription = "Hold to talk" }
                    .pointerInput(Unit) { detectTapGestures(onPress = { startVoice(); tryAwaitRelease(); recorder.stop() }) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Mic, null, tint = RtColors.TextPrimary) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(RtColors.Primary).clickable(role = Role.Button, onClickLabel = "Send") { val t = text; text = ""; onSend(t, own) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ArrowUpward, "Send", tint = RtColors.OnPrimary) }
        }
    }
}

/** Shoot: the footage choice, then every shot grouped by where the phone goes, with what's filmed. */
@Composable
private fun ShootStage(
    chat: StoryboardChat,
    s: Script,
    template: ReelProject?,
    making: Boolean,
    onMake: () -> Unit,
    onFill: (String, String) -> Unit,
    onShoot: (String, List<String>) -> Unit,
    onChangeClips: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (template == null) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(RtColors.Surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ready to film?", style = RtType.headline, color = RtColors.TextPrimary)
                    Text("Make the template first: each shot gets its slot, and you choose where its footage comes from.", style = RtType.body, color = RtColors.TextSecondary)
                    PrimaryButton(if (making) "Making…" else "Make the template", onMake, Modifier.fillMaxWidth(), enabled = !making)
                }
            }
            return@LazyColumn
        }
        val openIds = TemplateBuilder.placeholders(template.plan).toSet()
        val suggested = template.template?.suggested.orEmpty()
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("FOOTAGE", style = RtType.label, color = RtColors.TextTertiary)
                    Text(template.template?.clips?.label ?: ClipSource.AUTO.label, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                }
                TextButton(onClick = onChangeClips) { Text("Change") }
            }
        }
        val shots = Placeholders.shots(s).map { it.second.id!! to it.second.board!! }
        ToFilm.groups(shots).forEach { g ->
            item(key = "g-${g.where}") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(RtColors.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.where, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                            Text(if (g.riding) "Filmed while riding: from the ride pop-up, or pick it from your moments" else "Off the bike: set up once, film these in a row", style = RtType.caption, color = RtColors.TextSecondary)
                        }
                        val todo = g.shots.map { it.first }.filter { it in openIds }
                        if (!g.riding && todo.isNotEmpty()) PrimaryButton("Shoot ${todo.size}", { onShoot(template.id, todo) })
                    }
                    g.shots.forEach { (id, b) ->
                        val status = when {
                            id in suggested && id !in openIds -> "Suggested · check it"
                            id !in openIds -> "Filmed"
                            b.match.mustRecord -> "To film · fresh only"
                            else -> "To film"
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.SurfaceRaised).clickable(role = Role.Button, onClickLabel = "Fill shot") { onFill(template.id, id) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#" + Placeholders.number(s, id).trimStart('0'), style = RtType.caption, fontWeight = FontWeight.Bold, color = RtColors.TextSecondary, modifier = Modifier.width(32.dp))
                            Text(b.action, style = RtType.caption, color = RtColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(status, style = RtType.caption, color = when { status == "Filmed" -> RtColors.Ok; status.startsWith("Suggested") -> RtColors.Warning; else -> RtColors.TextTertiary })
                        }
                    }
                }
            }
        }
    }
}

/** Edit: the template (make, update from the board, open in the editor), voice-over and captions. */
@Composable
private fun EditStage(chat: StoryboardChat, s: Script, template: ReelProject?, making: Boolean, onMake: () -> Unit, onOpen: (String) -> Unit, onReadLines: (String) -> Unit) {
    val lines = VoiceLines.lines(s)
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(RtColors.Surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (template == null) {
                    Text("Make the Reel", style = RtType.headline, color = RtColors.TextPrimary)
                    Text("The board becomes a Reel in the editor: a slot per shot, your on-screen text, and captions from your voice-over.", style = RtType.body, color = RtColors.TextSecondary)
                    PrimaryButton(if (making) "Making…" else "Make the template", onMake, Modifier.fillMaxWidth(), enabled = !making)
                } else {
                    val left = TemplateBuilder.placeholders(template.plan).size
                    val total = template.template?.shots ?: 0
                    Text(template.title, style = RtType.headline, color = RtColors.TextPrimary)
                    Text("${template.durationMs / 1000} s · ${total - left} of $total shots filmed · made from v${template.template?.version ?: 1}", style = RtType.caption, color = RtColors.TextSecondary)
                    val behind = TemplateSync.behind(chat, template)
                    if (behind != null) Text(behind, style = RtType.caption, color = RtColors.Warning)
                    PrimaryButton("Open in the editor", { onOpen(template.id) }, Modifier.fillMaxWidth())
                    if (behind != null) SecondaryButton(if (making) "Updating…" else "Update it from the board", onMake, Modifier.fillMaxWidth(), enabled = !making)
                }
            }
        }
        if (template != null && lines.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(RtColors.Surface).clickable(role = Role.Button) { onReadLines(template.id) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Voice-over booth", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text(
                        "${lines.size} line${if (lines.size > 1) "s" else ""} · captions: ${(template.template?.captions ?: CaptionSource.WRITTEN).label}" + if (template.takes.isNotEmpty()) " · recorded" else "",
                        style = RtType.caption, color = RtColors.TextSecondary,
                    )
                }
                Text("›", style = RtType.headline, color = RtColors.TextTertiary)
            }
        }
    }
}
