package com.ridetrack.app.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.nav.Routes
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A series' folder: what it is, its look, its episodes in order (Ready or Posted), "Make next
 * episode", and what the rider told it. [id] [Routes.NEW_SERIES] starts a new one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesScreen(id: String, onBack: () -> Unit, onOpenReel: (rideId: String, reelId: String) -> Unit, onOpenPhone: (reelId: String) -> Unit) {
    val c = appContainer()
    val list by c.series.series.collectAsStateWithLifecycle()
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val styles by c.styles.styles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // A new series opens straight in its settings (set now, not after the first frame, which would find no series and leave).
    var editing by remember { mutableStateOf(if (id == Routes.NEW_SERIES) Series(c.series.newId(), "", createdAt = System.currentTimeMillis()) else null) }
    var seriesId by remember { mutableStateOf(id.takeIf { it != Routes.NEW_SERIES }) }
    val s = list.firstOrNull { it.id == seriesId }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tell by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    editing?.let { e ->
        SeriesEditor(e, styles, onCancel = { editing = null; if (seriesId == null) onBack() }) { saved ->
            c.series.save(saved)
            seriesId = saved.id
            editing = null
        }
        return
    }
    if (s == null) {
        // Deleted, or not found: say so, with a way back.
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This series isn't here any more", style = RtType.title, color = RtColors.TextPrimary)
            Text("‹ Back", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onBack).padding(vertical = 8.dp))
        }
        return
    }
    val episodes = reels.filter { it.seriesId == s.id && it.deletedAt == null }.sortedBy { it.episode }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp))
            Spacer(Modifier.weight(1f))
            Text("Edit", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { editing = s }.padding(8.dp))
        }
        Text(s.name, style = RtType.title, color = RtColors.TextPrimary)
        if (s.brief.isNotBlank()) Text(s.brief, style = RtType.body, color = RtColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                s.format.label,
                "${s.minSec}–${s.maxSec} s",
                if (s.perRide == 1) "1 per ride" else "Every moment that fits",
                s.styleId?.let { st -> styles.firstOrNull { it.id == st }?.name } ?: "Gemini picks the look",
                "Ep ${s.nextEpisode} next",
            ).forEach { Tag(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("After each ride", style = RtType.body, color = RtColors.TextPrimary)
                Text(if (s.active) "Gemini looks for episodes and they're made by themselves" else "Off: no episodes are looked for", style = RtType.caption, color = RtColors.TextSecondary)
            }
            Switch(checked = s.active, onCheckedChange = { c.series.save(s.copy(active = it)) })
        }
        Button(if (busy) "Looking in your rides…" else "Make next episode", primary = true) {
            if (busy) return@Button
            busy = true
            scope.launch {
                note = runCatching { SeriesWork.makeNext(c, s) }.getOrElse { "Couldn't look: ${it.message?.take(60)}" }
                busy = false
            }
        }
        note?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }

        Text("EPISODES · ${episodes.size}", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        if (episodes.isEmpty()) {
            Text("None yet. After your next ride Gemini looks for moments that fit, or tap Make next episode.", style = RtType.caption, color = RtColors.TextTertiary)
        }
        episodes.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    Column(Modifier.weight(1f)) {
                        ReelTile(p, Modifier.fillMaxWidth()) { if (p.rideId != null) onOpenReel(p.rideId, p.id) else onOpenPhone(p.id) }
                        Text("Ep ${p.episode} · ${if (p.posted) "Posted" else "Ready"}", style = RtType.caption, color = if (p.posted) RtColors.TextTertiary else RtColors.Primary)
                        Text(
                            "Use its look",
                            style = RtType.caption,
                            color = RtColors.TextSecondary,
                            modifier = Modifier.clickable(role = Role.Button) {
                                scope.launch { val n = SeriesWork.useLook(c, p); note = "Every episode uses this look now" + if (n > 0) " · making $n again" else "" }
                            }.padding(vertical = 4.dp),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("Use its look: that episode's look becomes the series' look, and unposted episodes are made again in it.", style = RtType.caption, color = RtColors.TextTertiary)

        Text("WHAT YOU TOLD THIS SERIES", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        s.notes.forEachIndexed { i, n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                Text("Forget", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { c.series.save(s.copy(notes = s.notes.filterIndexed { k, _ -> k != i })) }.padding(8.dp))
            }
        }
        OutlinedTextField(
            value = tell,
            onValueChange = { tell = it },
            placeholder = { Text("e.g. shorter, end on “Kal milte hain”", style = RtType.body, color = RtColors.TextTertiary) },
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (tell.isNotBlank()) {
            Text("Add", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { c.series.save(s.copy(notes = s.notes + tell.trim())); tell = "" }.padding(vertical = 6.dp))
        }
        Text("Only this series' episodes follow these, with how its episodes did.", style = RtType.caption, color = RtColors.TextTertiary)
        Text("Delete series", style = RtType.button, color = RtColors.Error, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp).clickable(role = Role.Button) { confirmDelete = true }.padding(vertical = 6.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${s.name}”?") },
            text = { Text("Its episodes stay in Your Reels as ordinary Reels.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        episodes.forEach { p -> c.reels.update(p.id) { it.copy(seriesId = null) } }
                        c.series.delete(s.id)
                        onBack()
                    }
                }) { Text("Delete", color = RtColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

/** A series' settings: name, what it's about, format and length, per ride, look and caption. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesEditor(start: Series, styles: List<StudioStyle>, onCancel: () -> Unit, onSave: (Series) -> Unit) {
    var s by remember(start.id) { mutableStateOf(start) }
    val fresh = start.name.isBlank()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onCancel).padding(vertical = 8.dp, horizontal = 4.dp))
            Spacer(Modifier.weight(1f))
            Text(
                "Save",
                style = RtType.button,
                color = if (s.name.isNotBlank()) RtColors.Primary else RtColors.TextTertiary,
                modifier = Modifier.clickable(enabled = s.name.isNotBlank(), role = Role.Button) { onSave(s.copy(name = s.name.trim(), brief = s.brief.trim())) }.padding(8.dp),
            )
        }
        Text(if (fresh) "New series" else "Series", style = RtType.title, color = RtColors.TextPrimary)
        Field("Name", s.name, "e.g. Delhi Commute Diaries") { s = s.copy(name = it) }
        Field("What it is, in your words", s.brief, "e.g. One funny or annoying thing from the commute. Hinglish, my voice leads, ends with “Kal milte hain”.", lines = 3) { s = s.copy(brief = it) }
        Text("Gemini follows this when it looks for episodes in a ride.", style = RtType.caption, color = RtColors.TextTertiary)

        Label("Format")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PieceFormat.entries.forEach { f ->
                Choice(f.label, s.format == f) {
                    val mid = (f.minSec + f.maxSec) / 2
                    s = s.copy(format = f, minSec = f.minSec.coerceAtLeast(mid - 10).coerceAtMost(f.maxSec), maxSec = (mid + 10).coerceIn(f.minSec, f.maxSec))
                }
            }
        }
        Label("Length · ${s.minSec}–${s.maxSec} s")
        RangeSlider(
            value = s.minSec.toFloat()..s.maxSec.toFloat(),
            onValueChange = { r -> s = s.copy(minSec = r.start.roundToInt(), maxSec = r.endInclusive.roundToInt().coerceAtLeast(r.start.roundToInt())) },
            valueRange = s.format.minSec.toFloat()..s.format.maxSec.toFloat(),
        )
        Label("Episodes per ride")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("1 per ride", s.perRide == 1) { s = s.copy(perRide = 1) }
            Choice("Every moment that fits", s.perRide == 0) { s = s.copy(perRide = 0) }
        }
        Label("Look · every episode is made in it")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("Gemini picks", s.styleId == null) { s = s.copy(styleId = null) }
            styles.forEach { st -> Choice(st.name, s.styleId == st.id) { s = s.copy(styleId = st.id) } }
        }
        Field("Post caption", s.captionTemplate, "{name} · Ep {ep}\n{caption}\n#motovlog", lines = 3) { s = s.copy(captionTemplate = it) }
        Text("{name} is the series, {ep} the episode, {caption} the episode's own words. Empty: the series label, then its own caption.", style = RtType.caption, color = RtColors.TextTertiary)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, lines: Int = 1, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(hint, style = RtType.body, color = RtColors.TextTertiary) },
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            singleLine = lines == 1,
            minLines = lines,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = RtType.label, color = RtColors.TextSecondary)

@Composable
private fun Tag(text: String) = Text(
    text,
    style = RtType.caption,
    color = RtColors.TextPrimary,
    maxLines = 1,
    modifier = Modifier.clip(RoundedCornerShape(50)).background(RtColors.Surface).border(1.dp, RtColors.Hairline, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
)

/** A folder tile in Your Reels: the newest episode's cover, the name and how many. */
@Composable
internal fun SeriesFolder(s: Series, episodes: List<ReelProject>, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open series", onClick = onClick)) {
        val latest = episodes.maxByOrNull { it.episode }
        if (latest != null) {
            ReelTile(latest, Modifier.fillMaxWidth(), clickable = false) {}
        } else {
            Column(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.Surface), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No episodes yet", style = RtType.caption, color = RtColors.TextTertiary)
            }
        }
        Text(s.name, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        val ready = episodes.count { !it.posted }
        Text(
            "${episodes.size} episode${if (episodes.size == 1) "" else "s"}" + (if (ready > 0) " · $ready ready" else "") + if (!s.active) " · off" else "",
            style = RtType.caption,
            color = RtColors.TextTertiary,
            maxLines = 1,
        )
    }
}
