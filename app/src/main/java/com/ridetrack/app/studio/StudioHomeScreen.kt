package com.ridetrack.app.studio

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.RideEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** A ride Studio can make a Reel from. */
data class StudioRide(
    val id: String,
    val name: String,
    val startMillis: Long,
    val distanceM: Double,
    val clips: Int,
    val talking: Int,
    val thumbs: List<File>,
)

class StudioHomeViewModel(c: AppContainer) : ViewModel() {
    /** Rides with at least 2 clips, newest first; null while loading. */
    val rides: StateFlow<List<StudioRide>?> = c.rides.observeCompleted().map { rides ->
        val clips = c.moments.all().filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) >= 1_500 }.groupBy { it.rideId }
        rides.mapNotNull { r ->
            val ms = clips[r.id].orEmpty()
            if (ms.size < 2) return@mapNotNull null
            StudioRide(
                id = r.id,
                name = r.name,
                startMillis = r.startTimeMillis,
                distanceM = r.stats.distanceM,
                clips = ms.size,
                talking = ms.count { m -> RideEventType.VOICE in m.types || !m.transcript.isNullOrBlank() },
                thumbs = ms.sortedByDescending { it.starred }.mapNotNull { it.thumb?.takeIf(File::isFile) }.take(3),
            )
        }.sortedByDescending { it.startMillis }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** The Studio tab: pick a ride, and Studio turns its clips into a Reel. */
@Composable
fun StudioHomeScreen(onOpenRide: (String) -> Unit) {
    val vm = appViewModel { StudioHomeViewModel(it) }
    val rides by vm.rides.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Studio", Modifier.padding(horizontal = RtDimens.screenPadding), subtitle = "Pick a ride. Studio turns its clips into a Reel.")
        val list = rides
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp) }
            list.isEmpty() -> EmptyState(
                title = "No rides with clips yet",
                message = "Turn on Moments on Home before a ride. Rides with 2 or more clips show up here, ready to become a Reel.",
                icon = Icons.Outlined.Movie,
                modifier = Modifier.padding(RtDimens.screenPadding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = RtDimens.screenPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { r -> RideRow(r) { onOpenRide(r.id) } }
            }
        }
    }
}

@Composable
private fun RideRow(r: StudioRide, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RtColors.Surface)
            .clickable(role = Role.Button, onClickLabel = "Make a Reel", onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Up to three clip frames, like a contact sheet.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            r.thumbs.forEach { Thumb(it, Modifier.size(width = 36.dp, height = 56.dp).clip(RoundedCornerShape(6.dp))) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Format.rideDate(r.startMillis)} · ${Format.distance(r.distanceM)}", style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                "${r.clips} clips" + if (r.talking > 0) " · ${r.talking} with your voice" else "",
                style = RtType.caption,
                color = RtColors.TextTertiary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("Make a Reel", style = RtType.button, color = RtColors.Primary)
    }
}
