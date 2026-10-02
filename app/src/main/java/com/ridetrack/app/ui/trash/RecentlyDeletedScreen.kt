package com.ridetrack.app.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.db.MomentEntity
import com.ridetrack.app.data.db.RideEntity
import com.ridetrack.app.trash.RecentlyDeleted
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class DeletedMoment(val moment: MomentEntity, val rideName: String, val thumb: File?)
data class TrashState(val rides: List<RideEntity> = emptyList(), val moments: List<DeletedMoment> = emptyList(), val loading: Boolean = true)

class RecentlyDeletedViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<TrashState> = combine(c.trash.observeRides(), c.trash.observeMoments()) { rides, moments ->
        TrashState(
            rides = rides,
            moments = moments.map { m ->
                val dir = File(File(c.appContext.filesDir, "moments"), m.rideId)
                DeletedMoment(m, c.rides.get(m.rideId)?.name.orEmpty(), File(dir, m.thumbFile ?: m.file).takeIf { it.exists() })
            },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashState())

    fun restoreRide(id: String) = viewModelScope.launch { c.trash.restoreRide(id) }
    fun restoreMoment(id: String) = viewModelScope.launch { c.trash.restoreMoment(id) }
    fun purgeRide(id: String) = viewModelScope.launch { c.trash.purgeRide(id) }
    fun purgeMoment(id: String) = viewModelScope.launch { c.trash.purgeMoment(id) }
    fun purgeAll() = viewModelScope.launch { c.trash.purgeAll() }
}

/** Profile › Recently deleted: rides and moments kept for 30 days before they're gone for good. */
@Composable
fun RecentlyDeletedScreen(focusRideId: String?, onBack: () -> Unit) {
    val vm = appViewModel { RecentlyDeletedViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val list = rememberLazyListState()
    val now = remember { System.currentTimeMillis() }

    LaunchedEffect(focusRideId, s.rides) {
        val i = s.rides.indexOfFirst { it.id == focusRideId }
        if (i >= 0) list.animateScrollToItem(i + 1)
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = RtDimens.screenPadding),
        state = list,
        verticalArrangement = Arrangement.spacedBy(RtDimens.cardSpacing),
    ) {
        item {
            ScreenHeader(
                "Recently deleted",
                subtitle = "Kept for 30 days, then deleted for good",
                onBack = onBack,
            )
        }
        if (!s.loading && s.rides.isEmpty() && s.moments.isEmpty()) {
            item { EmptyState("Nothing here", "Rides and moments you delete stay here for 30 days, in case you change your mind.", icon = Icons.Outlined.DeleteOutline) }
        }
        if (s.rides.isNotEmpty()) item { SectionHeader("Rides") }
        items(s.rides, key = { "r" + it.id }) { r ->
            TrashRow(
                title = r.name,
                detail = "${Format.rideDate(r.startTimeMillis)} · ${Format.distance(r.distanceM)}",
                daysLeft = RecentlyDeleted.daysLeft(r.deletedAtMillis ?: now, now),
                thumb = null,
                highlight = r.id == focusRideId,
                onRestore = { vm.restoreRide(r.id) },
                onDeleteNow = { confirm = "Delete \"${r.name}\" for good? Its moments go with it. This can't be undone." to { vm.purgeRide(r.id) } },
            )
        }
        if (s.moments.isNotEmpty()) item { SectionHeader("Moments") }
        items(s.moments, key = { "m" + it.moment.id }) { d ->
            val m = d.moment
            TrashRow(
                title = if (m.kind == "CLIP") "Clip" else "Photo",
                detail = d.rideName.ifEmpty { "Ride" } + " · " + Format.timeOfDay(m.timeMillis),
                daysLeft = RecentlyDeleted.daysLeft(m.deletedAtMillis ?: now, now),
                thumb = d.thumb,
                highlight = false,
                onRestore = { vm.restoreMoment(m.id) },
                onDeleteNow = { confirm = "Delete this ${if (m.kind == "CLIP") "clip" else "photo"} for good? This can't be undone." to { vm.purgeMoment(m.id) } },
            )
        }
        if (s.rides.isNotEmpty() || s.moments.isNotEmpty()) {
            item {
                TextButton(
                    onClick = { confirm = "Delete everything in Recently deleted for good? This can't be undone." to { vm.purgeAll() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Empty Recently deleted", color = RtColors.Error) }
                Spacer(Modifier.height(RtDimens.lg))
            }
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete for good?") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Delete", color = RtColors.Error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrashRow(
    title: String,
    detail: String,
    daysLeft: Int,
    thumb: File?,
    highlight: Boolean,
    onRestore: () -> Unit,
    onDeleteNow: () -> Unit,
) {
    RtCard(color = if (highlight) RtColors.PrimaryContainer else RtColors.Surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (thumb != null) {
                Thumb(thumb, Modifier.size(52.dp).clip(RoundedCornerShape(RtDimens.sm)).background(RtColors.SurfaceRaised))
                Spacer(Modifier.width(RtDimens.sm))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1)
                Text(detail, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1)
                Text(
                    when (daysLeft) {
                        0 -> "Deleted for good today"
                        1 -> "1 day left"
                        else -> "$daysLeft days left"
                    },
                    style = RtType.caption,
                    color = if (daysLeft <= 3) RtColors.Warning else RtColors.TextTertiary,
                )
            }
        }
        Row(Modifier.padding(top = RtDimens.xs), horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
            TextButton(onClick = onRestore) { Text("Restore", color = RtColors.Primary) }
            TextButton(onClick = onDeleteNow) { Text("Delete now", color = RtColors.TextSecondary) }
        }
    }
}
