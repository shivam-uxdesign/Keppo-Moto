package com.ridetrack.app.studio.storyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch
import java.util.Locale

/** The rider's direct changes to shots: each changed field gets locked so Gemini leaves it. Pure, unit-tested. */
object ShotEdits {
    fun edit(s: Script, id: String, f: (Storyboard) -> Storyboard): Script {
        var out = s
        out = out.copy(sections = out.sections.map { sec ->
            sec.copy(shots = sec.shots.map { sh ->
                val b = sh.board
                if (sh.id != id || b == null) sh else {
                    val n = f(b)
                    sh.copy(board = n, locked = sh.locked + changed(b, n), outMs = if (sh.clip.isEmpty()) sh.inMs + n.durationMs else sh.outMs)
                }
            })
        })
        return out.copy(lengthSec = (out.totalMs / 1000).toInt().coerceAtLeast(1))
    }

    /** Which fields differ (the ones to lock). */
    fun changed(a: Storyboard, b: Storyboard): Set<String> = buildSet {
        if (a.action != b.action) add(ShotFields.ACTION)
        if (a.framing != b.framing) add(ShotFields.FRAMING)
        if (a.mount != b.mount) add(ShotFields.MOUNT)
        if (a.camera != b.camera) add(ShotFields.CAMERA)
        if (a.durationMs != b.durationMs) add(ShotFields.DURATION)
        if (a.say != b.say) add(ShotFields.SAY)
    }

    /**
     * Moves shot [id] earlier or later by one place. Each part keeps its number of shots, so at a
     * part's edge the shot crosses into the next part (and its neighbour crosses back).
     */
    fun move(s: Script, id: String, by: Int): Script {
        val order = s.sections.flatMap { it.shots }.toMutableList()
        val a = order.indexOfFirst { it.id == id }
        if (a < 0) return s
        val b = (a + by).coerceIn(0, order.size - 1)
        if (a == b) return s
        order.add(b, order.removeAt(a))
        var k = 0
        return s.copy(sections = s.sections.map { sec -> sec.copy(shots = order.subList(k, k + sec.shots.size).toList().also { k += sec.shots.size }) })
    }

    fun delete(s: Script, id: String): Script =
        if (s.sections.sumOf { it.shots.size } <= 1) s
        else s.copy(sections = s.sections.map { sec -> sec.copy(shots = sec.shots.filter { it.id != id }) }.filter { it.shots.isNotEmpty() })

    /** A new shot after [after], a copy of it to change. */
    fun add(s: Script, after: String): Script {
        val id = Placeholders.newId(s)
        return s.copy(sections = s.sections.map { sec ->
            sec.copy(shots = sec.shots.flatMap { sh -> if (sh.id == after && sh.board != null) listOf(sh, ScriptShot("", 0, sh.board.durationMs, id = id, board = sh.board.copy(action = "New shot: say what to film"))) else listOf(sh) })
        })
    }

    /** One line about a shot for the style memory's edit log. */
    fun describe(b: Storyboard): String = "${b.action} (${b.framing}, ${b.mount}, ${String.format(Locale.US, "%.1f", b.durationMs / 1000.0)} s)"
}

/**
 * The shots of a storyboard as cards, grouped by part: the action, framing, camera and length,
 * the gear fallback, the line to say, and "Must record" or "Can use footage". Tap a card to change
 * it (the field is then yours: Gemini won't change it). The To film tab groups unfilled shots by
 * where the phone goes. Versions: every change is a new version; an older one can be brought back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShotCardsScreen(chatId: String, onBack: () -> Unit, onFill: (templateId: String, shotId: String) -> Unit) {
    val c = appContainer()
    val chats by c.storyboard.chats.collectAsStateWithLifecycle()
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val chat = chats.firstOrNull { it.id == chatId } ?: return
    val s = chat.script ?: return
    var tab by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<ScriptShot?>(null) }
    val template = reels.filter { it.id in chat.templateIds && it.deletedAt == null }.maxByOrNull { it.updatedAt }
    val open = template?.let { TemplateBuilder.placeholders(it.plan).toSet() }
    val suggested = template?.template?.suggested.orEmpty()
    val scope = rememberCoroutineScope()

    fun save(n: Script, note: String?, before: String, after: String) {
        c.storyboard.update(chatId) { it.copy(versions = it.versions + n) }
        runCatching { c.style.record(before, after, note) }
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = RtColors.TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text("${Placeholders.shots(s).size} shots · ${s.totalMs / 1000} s", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                Text("Version ${chat.versions.size} · tap a shot to change it", style = RtType.caption, color = RtColors.TextSecondary)
            }
        }
        if (chat.versions.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chat.versions.forEachIndexed { i, v ->
                val now = i == chat.versions.size - 1
                Text("v${i + 1}", style = RtType.caption, color = if (now) RtColors.OnPrimary else RtColors.TextPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (now) RtColors.Primary else RtColors.SurfaceRaised)
                        .clickable(role = Role.Button, onClickLabel = "Bring back version ${i + 1}") { if (!now) c.storyboard.update(chatId) { it.copy(versions = it.versions + v) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All shots", "To film" + (open?.let { " (${it.size})" } ?: "")).forEachIndexed { i, label ->
                Text(label, style = RtType.button, color = if (tab == i) RtColors.Background else RtColors.TextPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) RtColors.TextPrimary else RtColors.SurfaceRaised)
                        .clickable(role = Role.Tab) { tab = i }.padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 0) s.sections.forEachIndexed { k, sec ->
                item(key = "sec$k") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Box(Modifier.size(10.dp).background(sectionColor(k), CircleShape))
                        Spacer(Modifier.size(8.dp))
                        Text((sec.text ?: sec.kind.label).uppercase(Locale.US), style = RtType.label, color = RtColors.TextPrimary)
                    }
                }
                sec.shots.forEach { sh ->
                    val b = sh.board ?: return@forEach
                    item(key = sh.id) {
                        val status = when {
                            open == null -> if (b.match.mustRecord) "Must record" else "Can use your footage"
                            sh.id in suggested && sh.id !in open -> "Suggested from your rides · Keep it, or Fill to change"
                            sh.id !in open -> "Filled"
                            b.match.mustRecord -> "Must record"
                            else -> "To film · or use your footage"
                        }
                        ShotCard(Placeholders.number(s, sh.id!!), sh, b, status, onEdit = { editing = sh }, onFill = template?.takeIf { sh.id in open.orEmpty() || sh.id in suggested }?.let { t -> { onFill(t.id, sh.id) } },
                            onKeep = template?.takeIf { sh.id in suggested && sh.id !in open.orEmpty() }?.let { t -> { scope.launch { TemplateFill.keep(c, t.id, sh.id) } } })
                    }
                }
            } else {
                val shots = Placeholders.shots(s).filter { open == null || it.second.id in open }.map { it.second.id!! to it.second.board!! }
                item { Text("Grouped by where the phone goes, so you set up once per group. They're on your shot list on Home and in the ride pop-up too.", style = RtType.caption, color = RtColors.TextSecondary) }
                ToFilm.groups(shots).forEach { g ->
                    item(key = g.where) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(g.where, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                            g.shots.forEach { (id, b) -> Text("Shot ${Placeholders.number(s, id)}: ${b.action}", style = RtType.caption, color = RtColors.TextSecondary) }
                            Text(if (g.riding) "In the ride pop-up for the first 30 s" else "Off the bike: Record from the shot's Fill", style = RtType.caption, color = RtColors.Primary)
                        }
                    }
                }
            }
        }
    }
    editing?.let { sh ->
        val b = sh.board!!
        val shotId = sh.id!!
        var action by remember { mutableStateOf(b.action) }
        var secs by remember { mutableStateOf(String.format(Locale.US, "%.1f", b.durationMs / 1000.0)) }
        var framing by remember { mutableStateOf(b.framing) }
        var mount by remember { mutableStateOf(b.mount) }
        var camera by remember { mutableStateOf(b.camera) }
        var say by remember { mutableStateOf(b.say.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Shot ${Placeholders.number(s, sh.id!!)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(action, { action = it }, label = { Text("What to film") }, minLines = 2)
                    OutlinedTextField(secs, { secs = it.filter { ch -> ch.isDigit() || ch == '.' }.take(4) }, label = { Text("Length (s)") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Framing.entries.forEach { f ->
                            val on = Framing.of(framing) == f
                            Text(f.label, style = RtType.caption, color = if (on) RtColors.OnPrimary else RtColors.TextPrimary,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) RtColors.Primary else RtColors.SurfaceRaised).clickable(role = Role.RadioButton) { framing = f.label.lowercase(Locale.US) }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                    OutlinedTextField(mount, { mount = it }, label = { Text("Mount or where the phone goes") }, singleLine = true)
                    OutlinedTextField(camera, { camera = it }, label = { Text("Camera") }, singleLine = true)
                    OutlinedTextField(say, { say = it }, label = { Text("Voice-over line (optional)") })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { save(ShotEdits.move(s, shotId, -1), "Moved a shot earlier", ShotEdits.describe(b), "moved earlier"); editing = null }) { Text("Earlier") }
                        TextButton(onClick = { save(ShotEdits.move(s, shotId, 1), "Moved a shot later", ShotEdits.describe(b), "moved later"); editing = null }) { Text("Later") }
                        TextButton(onClick = { save(ShotEdits.add(s, shotId), null, "", "added a shot"); editing = null }) { Text("Add after") }
                        TextButton(onClick = { save(ShotEdits.delete(s, shotId), "Removed a shot", ShotEdits.describe(b), "removed"); editing = null }) { Text("Remove", color = RtColors.Error) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = ShotEdits.edit(s, shotId) { it.copy(action = action.trim().ifBlank { it.action }, durationMs = ((secs.toDoubleOrNull() ?: (it.durationMs / 1000.0)) * 1000).toLong().coerceIn(StoryboardJson.MIN_MS, StoryboardJson.MAX_MS), framing = framing, mount = mount.trim().ifBlank { it.mount }, camera = camera.trim().ifBlank { it.camera }, say = say.trim().ifBlank { null }) }
                    val nb = Placeholders.find(n, shotId)!!.board!!
                    if (nb != b) save(n, "Changed a shot in Storyboard", ShotEdits.describe(b), ShotEdits.describe(nb))
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShotCard(number: String, sh: ScriptShot, b: Storyboard, status: String, onEdit: () -> Unit, onFill: (() -> Unit)?, onKeep: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClickLabel = "Change the shot", onClick = onEdit).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 12.dp)) {
                Text(number, style = RtType.caption, color = RtColors.TextSecondary, fontWeight = FontWeight.Bold)
                Box(Modifier.padding(top = 4.dp).size(40.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised), contentAlignment = Alignment.Center) {
                    Text(when (b.framingKind) { Framing.EXTREME -> "EYES"; Framing.CLOSE -> "CU"; Framing.MEDIUM -> "MED"; Framing.WIDE -> "WIDE" }, style = RtType.caption, color = RtColors.Primary, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(b.action, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(b.framingKind.label, "${b.camera} · ${b.mount}", String.format(Locale.US, "%.1f s", b.durationMs / 1000.0)).forEachIndexed { i, chip ->
                        val mine = (i == 0 && ShotFields.FRAMING in sh.locked) || (i == 1 && (ShotFields.MOUNT in sh.locked || ShotFields.CAMERA in sh.locked)) || (i == 2 && ShotFields.DURATION in sh.locked)
                        Text(chip, style = RtType.caption, color = if (mine) RtColors.Left else RtColors.TextSecondary, modifier = Modifier.clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised).padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                }
                if (sh.locked.isNotEmpty()) Text("Edited by you: ${sh.locked.joinToString()} · Studio won't change it", style = RtType.caption, color = RtColors.Left)
                b.fallback?.let { Text("Best with ${it.ideal} (${it.reason}). Using ${it.using}.", style = RtType.caption, color = RtColors.Warning) }
                b.say?.let { Text("Say: “$it”", style = RtType.caption, color = RtColors.Primary) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status, style = RtType.caption, color = when { status.startsWith("Must") -> RtColors.Error; status.startsWith("Filled") || status.startsWith("Suggested") -> RtColors.Ok; else -> RtColors.TextSecondary }, modifier = Modifier.weight(1f))
                    onKeep?.let { Text("Keep", style = RtType.button, color = RtColors.Ok, modifier = Modifier.clickable(role = Role.Button, onClick = it).padding(6.dp)) }
                    onFill?.let { Text("Fill", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = it).padding(6.dp)) }
                }
            }
        }
    }
}
