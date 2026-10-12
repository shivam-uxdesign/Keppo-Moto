package com.ridetrack.app.studio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.share.ShareImages
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class ReelsTab(val label: String) { REELS("Reels"), SAVED("Saved clips"), DELETED("Deleted") }

private enum class ReelFilter(val label: String) { ALL("All"), DRAFTS("Not posted"), RIDES("From rides"), PHONE("Your videos"), JOURNAL("In Journal") }

/**
 * Your Reels, like Photos: Reels | Saved clips | Deleted. Hold a Reel for its menu (it lifts, the
 * rest dims); Select, then tap or swipe across tiles to pick several and act on them together.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReelsScreen(onBack: () -> Unit, onOpenReel: (rideId: String, reelId: String, edit: Boolean) -> Unit, onOpenPhone: (reelId: String, edit: Boolean) -> Unit, onStyle: () -> Unit) {
    val c = appContainer()
    val store = c.reels
    val all by store.reels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableStateOf(ReelsTab.REELS) }
    var filter by rememberSaveable { mutableStateOf(ReelFilter.ALL) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<Set<String>?>(null) }
    var menu by remember { mutableStateOf<ReelProject?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) { if (note != null && !note!!.endsWith("…")) { kotlinx.coroutines.delay(3_000); note = null } }
    var bytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(all.size) { bytes = withContext(Dispatchers.IO) { store.bytes() } }
    val live = all.filter { it.deletedAt == null }.filter {
        when (filter) {
            ReelFilter.ALL -> true
            ReelFilter.DRAFTS -> !it.posted
            ReelFilter.RIDES -> it.rideId != null
            ReelFilter.PHONE -> it.rideId == null
            ReelFilter.JOURNAL -> it.inJournal
        }
    }
    val deleted = all.filter { it.deletedAt != null }
    fun open(p: ReelProject, edit: Boolean = false) = if (p.rideId != null) onOpenReel(p.rideId, p.id, edit) else onOpenPhone(p.id, edit)
    fun stopSelecting() { selecting = false; selected = emptySet() }
    fun save(ids: Set<String>) = scope.launch {
        note = "Saving ${ids.size}…"
        val ok = ids.count { id -> store.video(id).takeIf { it.isFile }?.let { f -> ShareImages.saveVideo(context, f, "Keppo Reel $id") } == true }
        ids.forEach { id -> store.update(id) { it.copy(posted = true) } }
        note = if (ok == ids.size) "Saved $ok to Movies/Keppo Moto" else "Saved $ok of ${ids.size}"
    }
    fun share(ids: Set<String>) {
        val files = ids.map { store.video(it) }.filter { it.isFile }
        ShareImages.shareMany(context, files.map { ShareImages.uriFor(context, it) }, "video/mp4")
        scope.launch { ids.forEach { id -> store.update(id) { it.copy(posted = true) } } }
    }
    if (selecting) BackHandler { stopSelecting() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Studio", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp))
                Spacer(Modifier.weight(1f))
                if (tab == ReelsTab.REELS) {
                    Text(
                        if (selecting) "Cancel" else "Select",
                        style = RtType.button,
                        color = RtColors.Primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { if (selecting) stopSelecting() else selecting = true }.padding(8.dp),
                    )
                } else if (tab == ReelsTab.SAVED) {
                    Text("Your style", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onStyle).padding(8.dp))
                }
            }
            Text(if (selecting) "${selected.size} selected" else "Your Reels", style = RtType.title, color = RtColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
            // Reels | Saved clips | Deleted
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised).padding(3.dp)) {
                ReelsTab.entries.forEach { t ->
                    val on = t == tab
                    Text(
                        if (t == ReelsTab.DELETED && deleted.isNotEmpty()) "${t.label} · ${deleted.size}" else t.label,
                        style = RtType.button,
                        color = RtColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) RtColors.Surface else Color.Transparent)
                            .clickable(role = Role.Tab) { tab = t; stopSelecting() }.padding(vertical = 8.dp),
                    )
                }
            }
            note?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp)) }
            when (tab) {
                ReelsTab.SAVED -> SavedClipsGrid()
                ReelsTab.DELETED -> DeletedList(deleted, onRestore = { id -> scope.launch { store.restore(id) } }, onPurge = { id -> scope.launch { store.purge(id) } })
                ReelsTab.REELS -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReelFilter.entries.forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(f.label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f), selectedLabelColor = RtColors.Primary, labelColor = RtColors.TextSecondary),
                            )
                        }
                    }
                    Text("${all.count { it.deletedAt == null }} made · ${bytes?.let { formatMb(it) } ?: "…"} on this phone", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(bottom = 8.dp))
                    val grid = rememberLazyGridState()
                    // While selecting, a swipe across the tiles picks every one it passes (up and down still scrolls).
                    val swipeSelect = Modifier.pointerInput(selecting, live) {
                        if (!selecting) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var sideways = false
                            var adding: Boolean? = null
                            fun at(o: androidx.compose.ui.geometry.Offset): String? = grid.layoutInfo.visibleItemsInfo.firstOrNull { i ->
                                o.x >= i.offset.x && o.x < i.offset.x + i.size.width && o.y >= i.offset.y && o.y < i.offset.y + i.size.height
                            }?.key as? String
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val d = ch.position - down.position
                                if (!sideways && abs(d.x) > viewConfiguration.touchSlop && abs(d.x) > abs(d.y)) sideways = true
                                if (!sideways && abs(d.y) > viewConfiguration.touchSlop) break
                                if (sideways) {
                                    ch.consume()
                                    listOfNotNull(at(down.position), at(ch.position)).forEach { id ->
                                        val add = adding ?: (id !in selected).also { adding = it }
                                        selected = if (add) selected + id else selected - id
                                    }
                                }
                            }
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = grid,
                        modifier = Modifier.weight(1f).then(swipeSelect),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (live.isEmpty()) {
                            item(span = { GridItemSpan(3) }) {
                                EmptyState(title = "Nothing here yet", message = "Reels you make in Studio are kept here, with everything they were made with.", icon = Icons.Outlined.Movie)
                            }
                        }
                        items(live, key = { it.id }) { p ->
                            val on = p.id in selected
                            Box(
                                Modifier.clip(RoundedCornerShape(10.dp))
                                    .border(2.dp, if (on) RtColors.Primary else Color.Transparent, RoundedCornerShape(10.dp))
                                    .combinedClickable(
                                        onClickLabel = if (selecting) "Select" else "Open",
                                        onLongClickLabel = "Options",
                                        onLongClick = { if (!selecting) menu = p },
                                    ) { if (selecting) selected = if (on) selected - p.id else selected + p.id else open(p) },
                            ) {
                                ReelTile(p, Modifier.fillMaxWidth(), clickable = false) {}
                                if (selecting) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(6.dp).width(22.dp).aspectRatio(1f).clip(RoundedCornerShape(11.dp))
                                            .background(if (on) RtColors.Primary else Color.Black.copy(alpha = 0.4f)).border(1.5.dp, Color.White, RoundedCornerShape(11.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) { if (on) Text("✓", style = RtType.caption, color = Color.White) }
                                }
                            }
                        }
                    }
                    if (selecting) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            val ids = selected
                            BarAction("Journal", ids.isNotEmpty()) { scope.launch { sendToJournal(c, all.filter { it.id in ids && it.rideId != null }); note = "Sent to Keppo Journal"; stopSelecting() } }
                            BarAction("Save", ids.isNotEmpty()) { save(ids); stopSelecting() }
                            BarAction("Share", ids.isNotEmpty()) { share(ids); stopSelecting() }
                            BarAction("Delete", ids.isNotEmpty(), danger = true) { confirm = ids }
                        }
                    } else {
                        Text("Hold a Reel for options · Select, then swipe across tiles to pick several", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
        // The hold menu: the Reel lifts, the rest dims.
        ReelMenu(
            menu,
            onDismiss = { menu = null },
            onOpen = { p -> menu = null; open(p) },
            onEdit = { p -> menu = null; open(p, edit = true) },
            onShare = { p -> menu = null; share(setOf(p.id)) },
            onSave = { p -> menu = null; save(setOf(p.id)) },
            onJournal = { p -> menu = null; scope.launch { sendToJournal(c, listOf(p)); note = "Sent to Keppo Journal" } },
            onDuplicate = { p -> menu = null; scope.launch { store.duplicate(p.id); note = "Duplicated" } },
            onDelete = { p -> menu = null; confirm = setOf(p.id) },
        )
    }
    confirm?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${ids.size} Reel${if (ids.size == 1) "" else "s"}?") },
            text = { Text("They move to Deleted, where you can restore them for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    stopSelecting()
                    scope.launch { ids.forEach { id -> store.delete(id); store.get(id)?.let { c.reelsChanged(it) } } }
                }) { Text("Delete", color = RtColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BarAction(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = RtType.button,
        color = when { !enabled -> RtColors.TextTertiary; danger -> RtColors.Error; else -> RtColors.Primary },
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** The menu a held Reel opens: it plays here, with what can be done to it. */
@Composable
private fun ReelMenu(
    p: ReelProject?,
    onDismiss: () -> Unit,
    onOpen: (ReelProject) -> Unit,
    onEdit: (ReelProject) -> Unit,
    onShare: (ReelProject) -> Unit,
    onSave: (ReelProject) -> Unit,
    onJournal: (ReelProject) -> Unit,
    onDuplicate: (ReelProject) -> Unit,
    onDelete: (ReelProject) -> Unit,
) {
    if (p != null) BackHandler(onBack = onDismiss)
    val store = appContainer().reels
    AnimatedVisibility(p != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val r = p ?: return@Box
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(true, enter = scaleIn(initialScale = 0.85f) + fadeIn()) {
                    val video = store.video(r.id)
                    if (video.isFile) Player(android.net.Uri.fromFile(video), Modifier, height = 300.dp)
                    else ReelTile(r, Modifier.width(170.dp), clickable = false) {}
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(r.title, style = RtType.bodyStrong, color = Color.White)
                    Text(listOfNotNull(Format.clock(r.durationMs), if (r.posted) "Posted" else "Draft", if (r.inJournal) "In Journal" else null).joinToString(" · "), style = RtType.caption, color = Color.White.copy(alpha = 0.7f))
                }
                Column(Modifier.width(260.dp).clip(RoundedCornerShape(14.dp)).background(RtColors.SurfaceRaised)) {
                    MenuRow("Open", "›") { onOpen(r) }
                    MenuRow("Open in the editor", "✎") { onEdit(r) }
                    MenuRow("Share", "↗") { onShare(r) }
                    MenuRow("Save to phone", "↓") { onSave(r) }
                    if (r.rideId != null && !r.inJournal) MenuRow("Send to Keppo Journal", "◫") { onJournal(r) }
                    MenuRow("Duplicate", "⧉") { onDuplicate(r) }
                    MenuRow("Delete", "⌫", danger = true) { onDelete(r) }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, glyph: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RtType.body, color = if (danger) RtColors.Error else RtColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(glyph, style = RtType.body, color = if (danger) RtColors.Error else RtColors.TextSecondary)
    }
}

/** Deleted Reels, kept 30 days: restore or delete for good. */
@Composable
private fun DeletedList(deleted: List<ReelProject>, onRestore: (String) -> Unit, onPurge: (String) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(1), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (deleted.isEmpty()) {
            item { EmptyState(title = "Nothing deleted", message = "Deleted Reels wait here for 30 days, in case you want them back.", icon = Icons.Outlined.Movie) }
        }
        items(deleted, key = { it.id }) { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReelTile(p, Modifier.width(56.dp), clickable = false) {}
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1)
                    val days = (30 - (System.currentTimeMillis() - (p.deletedAt ?: 0)) / 86_400_000L).coerceAtLeast(0)
                    Text("Deleted for good in $days days", style = RtType.caption, color = RtColors.TextTertiary)
                }
                Text("Restore", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { onRestore(p.id) }.padding(8.dp))
                Text("Delete", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { onPurge(p.id) }.padding(8.dp))
            }
        }
    }
}

/** Saved clips: search by tag or words, watch, change tags, delete. */
@Composable
private fun SavedClipsGrid() {
    val store = appContainer().savedClips
    val clips by store.clips.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<SavedClip?>(null) }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) clips else clips.filter { c -> c.tags.any { it.contains(q) } || c.lines.any { it.text.lowercase().contains(q) } || c.from?.lowercase()?.contains(q) == true }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Find by tag or words", style = RtType.body, color = RtColors.TextTertiary) },
            singleLine = true,
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            modifier = Modifier.fillMaxWidth(),
        )
        val tags = clips.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
        if (tags.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    FilterChip(
                        selected = q == t,
                        onClick = { query = if (q == t) "" else t },
                        label = { Text(t) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f), selectedLabelColor = RtColors.Primary, labelColor = RtColors.TextSecondary),
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    EmptyState(
                        title = if (clips.isEmpty()) "No saved clips yet" else "Nothing matches",
                        message = "Keep a clip from a ride's clips (Keep), Studio's clip strip (long-press › Save clip) or the editor (Save clip). Use it in any Reel from the editor's + Clip.",
                        icon = Icons.Outlined.Movie,
                    )
                }
            }
            items(shown, key = { it.id }) { c ->
                Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Watch") { open = c }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(RtColors.Surface)) {
                        com.ridetrack.app.ui.moments.Thumb(store.thumb(c.id).takeIf { it.isFile }, Modifier.fillMaxSize())
                        Text(
                            com.ridetrack.app.ui.format.Format.clock(c.durationMs),
                            style = RtType.caption,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp),
                        )
                    }
                    Text(c.tags.joinToString(" · "), style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    c.from?.let { Text(it, style = RtType.caption, color = RtColors.TextTertiary, maxLines = 1) }
                }
            }
        }
    }
    open?.let { c ->
        var tags by remember(c.id) { mutableStateOf(c.tags.joinToString(", ")) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { open = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Player(android.net.Uri.fromFile(store.video(c.id)), Modifier, height = 380.dp)
                if (c.lines.isNotEmpty()) Text(c.lines.joinToString(" ") { it.text }, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 4)
                androidx.compose.material3.OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags") },
                    singleLine = true,
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Delete", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { scope.launch { store.delete(c.id) }; open = null }.padding(8.dp))
                    Text("Done", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                        scope.launch { store.setTags(c.id, tags.split(',')) }
                        open = null
                    }.padding(8.dp))
                }
            }
        }
    }
}

private fun formatMb(b: Long): String = if (b < 1_000_000_000) "${b / 1_000_000} MB" else String.format(java.util.Locale.US, "%.1f GB", b / 1e9)

/** Sends Reels to Keppo Journal; for each ride, the newest one sent becomes the entry's cover. */
internal suspend fun sendToJournal(c: com.ridetrack.app.AppContainer, list: List<ReelProject>) {
    list.groupBy { it.rideId!! }.forEach { (ride, sent) ->
        val coverId = sent.maxBy { it.createdAt }.id
        c.reels.reels.value.filter { it.rideId == ride && (it.journalCover || it.id in sent.map { s -> s.id }) }.forEach { p ->
            c.reels.update(p.id) { it.copy(inJournal = it.inJournal || sent.any { s -> s.id == it.id }, journalCover = it.id == coverId) }
        }
        c.journal.onRideSaved(ride)
    }
}
