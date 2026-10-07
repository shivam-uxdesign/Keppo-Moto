package com.ridetrack.app.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ReelFilter(val label: String) { ALL("All"), RIDES("From rides"), PHONE("From your videos"), JOURNAL("In Journal") }

/**
 * Your Reels: everything Studio made (Reels, Shorts, Stories, long videos), newest first, with
 * filters, storage, multi-select (Delete, Send to Journal) and Recently deleted at the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReelsScreen(onBack: () -> Unit, onOpenReel: (rideId: String, reelId: String) -> Unit, onOpenPhone: (reelId: String) -> Unit, onStyle: () -> Unit) {
    val c = appContainer()
    val store = c.reels
    val all by store.reels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(ReelFilter.ALL) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var bytes by remember { mutableStateOf<Long?>(null) }
    var showDeleted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(all.size) { bytes = withContext(Dispatchers.IO) { store.bytes() } }
    val live = all.filter { it.deletedAt == null }.filter {
        when (filter) {
            ReelFilter.ALL -> true
            ReelFilter.RIDES -> it.rideId != null
            ReelFilter.PHONE -> it.rideId == null
            ReelFilter.JOURNAL -> it.inJournal
        }
    }
    val deleted = all.filter { it.deletedAt != null }
    val selecting = selected.isNotEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
        ScreenHeader(
            if (selecting) "${selected.size} selected" else "Your Reels",
            onBack = { if (selecting) selected = emptySet() else onBack() },
            subtitle = if (selecting) null else "${all.count { it.deletedAt == null }} made · ${bytes?.let { formatMb(it) } ?: "…"}",
            actions = {
                if (!selecting) Text("Your style", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onStyle).padding(8.dp))
            },
        )
        if (selecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Send to Journal", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                    scope.launch { sendToJournal(c, all.filter { it.id in selected && it.rideId != null }); selected = emptySet() }
                }.padding(vertical = 6.dp))
                Text("Delete", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { confirm = true }.padding(vertical = 6.dp))
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReelFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
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
                            onLongClickLabel = "Select",
                            onLongClick = { selected = selected + p.id },
                        ) {
                            when {
                                selecting -> selected = if (on) selected - p.id else selected + p.id
                                p.rideId != null -> onOpenReel(p.rideId, p.id)
                                else -> onOpenPhone(p.id)
                            }
                        },
                ) {
                    ReelTile(p, Modifier.fillMaxWidth()) {
                        when {
                            selecting -> selected = if (on) selected - p.id else selected + p.id
                            p.rideId != null -> onOpenReel(p.rideId, p.id)
                            else -> onOpenPhone(p.id)
                        }
                    }
                }
            }
            if (deleted.isNotEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        if (showDeleted) "Hide Recently deleted" else "Recently deleted · ${deleted.size}",
                        style = RtType.button,
                        color = RtColors.TextSecondary,
                        modifier = Modifier.clickable(role = Role.Button) { showDeleted = !showDeleted }.padding(vertical = 8.dp),
                    )
                }
                if (showDeleted) {
                    items(deleted, key = { "d-" + it.id }, span = { GridItemSpan(3) }) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ReelTile(p, Modifier.width(56.dp)) {}
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1)
                                val days = (30 - (System.currentTimeMillis() - (p.deletedAt ?: 0)) / 86_400_000L).coerceAtLeast(0)
                                Text("Deleted for good in $days days", style = RtType.caption, color = RtColors.TextTertiary)
                            }
                            Text("Restore", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { scope.launch { store.restore(p.id) } }.padding(8.dp))
                            Text("Delete", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { scope.launch { store.purge(p.id) } }.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${selected.size} Reel${if (selected.size == 1) "" else "s"}?") },
            text = { Text("They move to Recently deleted, where you can restore them for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val ids = selected
                    selected = emptySet()
                    scope.launch { ids.forEach { id -> store.delete(id); store.get(id)?.let { c.reelsChanged(it) } } }
                }) { Text("Delete", color = RtColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private fun formatMb(b: Long): String = if (b < 1_000_000_000) "${b / 1_000_000} MB" else String.format(java.util.Locale.US, "%.1f GB", b / 1e9)

/** Sends Reels to Keppo Journal; for each ride, the newest one sent becomes the entry's cover. */
private suspend fun sendToJournal(c: com.ridetrack.app.AppContainer, list: List<ReelProject>) {
    list.groupBy { it.rideId!! }.forEach { (ride, sent) ->
        val coverId = sent.maxBy { it.createdAt }.id
        c.reels.reels.value.filter { it.rideId == ride && (it.journalCover || it.id in sent.map { s -> s.id }) }.forEach { p ->
            c.reels.update(p.id) { it.copy(inJournal = it.inJournal || sent.any { s -> s.id == it.id }, journalCover = it.id == coverId) }
        }
        c.journal.onRideSaved(ride)
    }
}
