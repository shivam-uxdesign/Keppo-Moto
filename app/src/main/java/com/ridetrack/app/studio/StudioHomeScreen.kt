package com.ridetrack.app.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** A ride Studio can make a Reel from. */
data class StudioRide(
    val id: String,
    val name: String,
    val startMillis: Long,
    val endMillis: Long,
    val distanceM: Double,
    val clips: Int,
    val talking: Int,
    val thumbs: List<File>,
    /** Suggestions not made yet (titles and formats), from the saved plan. */
    val ready: List<Pair<String, String>> = emptyList(),
    val made: Int = 0,
    /** Clips whose words haven't been read yet. */
    val unread: Int = 0,
    /** Why suggestions aren't there yet (waiting for Wi-Fi, Gemini's limit); null when not waiting. */
    val waiting: String? = null,
) {
    /** The ride's state in one line. */
    val stateLine: String
        get() = listOfNotNull(
            ready.size.takeIf { it > 0 }?.let { "$it suggestion${if (it > 1) "s" else ""} ready" },
            made.takeIf { it > 0 }?.let { "$it made" },
            unread.takeIf { it > 0 }?.let { "$it clip${if (it > 1) "s" else ""} not read yet" },
            waiting,
        ).joinToString(" · ").ifEmpty { "$clips clips" + if (talking > 0) " · $talking with your voice" else "" }
}

class StudioHomeViewModel(private val c: AppContainer) : ViewModel() {
    /** Rides with at least 2 clips, newest first; null while loading. */
    val rides: StateFlow<List<StudioRide>?> = kotlinx.coroutines.flow.combine(c.rides.observeCompleted(), c.reels.reels) { rides, reels -> rides to reels }.map { (rides, reels) ->
        val clips = c.moments.all().filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) >= 1_500 }.groupBy { it.rideId }
        val now = System.currentTimeMillis()
        val geminiAt = com.ridetrack.app.transcribe.GeminiQuota.freeAt(com.ridetrack.app.transcribe.GeminiQuota.STORY)?.takeIf { it > now }
        rides.mapNotNull { r ->
            val ms = clips[r.id].orEmpty()
            if (ms.size < 2) return@mapNotNull null
            val madeIdeas = reels.filter { it.rideId == r.id && it.deletedAt == null }
            val planned = planOf(r.id)
            val ready = planned.filter { (title, _) -> madeIdeas.none { it.idea == title } }
            val waiting = if (planned.isEmpty()) {
                when {
                    !afterRideWaiting(r.id) -> null
                    geminiAt != null -> "Gemini free at ${com.ridetrack.app.transcribe.GeminiQuota.clock(geminiAt)}"
                    else -> "suggestions waiting for a connection"
                }
            } else {
                null
            }
            StudioRide(
                ready = ready,
                made = madeIdeas.size,
                unread = ms.count { StudioText.load(it.file) == null },
                waiting = waiting,
                id = r.id,
                name = r.name,
                startMillis = r.startTimeMillis,
                endMillis = r.endTimeMillis ?: r.startTimeMillis,
                distanceM = r.stats.distanceM,
                clips = ms.size,
                talking = ms.count { m -> RideEventType.VOICE in m.types || !m.transcript.isNullOrBlank() },
                thumbs = ms.sortedByDescending { it.starred }.mapNotNull { it.thumb?.takeIf(File::isFile) }.take(3),
            )
        }.sortedByDescending { it.startMillis }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The saved suggestions of a ride: (title, format). */
    private fun planOf(rideId: String): List<Pair<String, String>> = runCatching {
        val f = File(File(c.appContext.filesDir, "studio-plans"), "$rideId.json")
        if (!f.isFile) return emptyList()
        val a = org.json.JSONObject(f.readText()).getJSONArray("pieces")
        (0 until a.length()).mapNotNull { ScriptJson.read(a.optJSONObject(it)) }.map { it.title to it.format.label }
    }.getOrDefault(emptyList())

    /** The after-ride suggestions are still waiting to run (for Wi-Fi, a connection, or Gemini). */
    private fun afterRideWaiting(rideId: String): Boolean = runCatching {
        androidx.work.WorkManager.getInstance(c.appContext).getWorkInfosForUniqueWork("studio-after-ride-$rideId").get()
            .any { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.RUNNING }
    }.getOrDefault(false)

    /**
     * Videos picked on the Studio tab: when all were filmed during one recorded ride, that ride's
     * Studio opens with them (real speed and route); otherwise Studio for phone videos only.
     */
    fun picked(uris: List<android.net.Uri>, openRide: (String) -> Unit, openPhone: () -> Unit) {
        viewModelScope.launch {
            val times = kotlinx.coroutines.withContext(Dispatchers.IO) { uris.mapNotNull { PhoneVideos.read(c.appContext, it)?.startMillis } }
            val ride = c.rides.observeCompleted().first().firstOrNull { r ->
                val end = r.endTimeMillis ?: return@firstOrNull false
                times.isNotEmpty() && times.all { it in (r.startTimeMillis - 120_000)..(end + 120_000) }
            }
            c.studio.setPending(uris)
            if (ride != null) openRide(ride.id) else openPhone()
        }
    }
}

/** The Studio tab: for making. Your Reels are behind the icon at the top right. */
@Composable
fun StudioHomeScreen(onOpenRide: (String) -> Unit, onOpenPhone: (reelId: String?) -> Unit, onOpenReels: () -> Unit, onOpenStyles: () -> Unit = {}) {
    val vm = appViewModel { StudioHomeViewModel(it) }
    val rides by vm.rides.collectAsStateWithLifecycle()
    val store = com.ridetrack.app.ui.appContainer().reels
    val reels by store.reels.collectAsStateWithLifecycle()
    val made = reels.count { it.deletedAt == null }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.picked(uris, onOpenRide) { onOpenPhone(null) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(
            "Studio",
            Modifier.padding(horizontal = RtDimens.screenPadding),
            subtitle = "Pick a ride, or your own videos.",
            actions = {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Styles", onClick = onOpenStyles).padding(8.dp)) {
                    androidx.compose.material3.Icon(Icons.Outlined.Palette, contentDescription = "Styles", tint = RtColors.TextPrimary, modifier = Modifier.size(26.dp))
                }
                // Your Reels, with how many there are.
                Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Your Reels", onClick = onOpenReels).padding(8.dp)) {
                    androidx.compose.material3.Icon(Icons.Outlined.VideoLibrary, contentDescription = "Your Reels", tint = RtColors.TextPrimary, modifier = Modifier.size(26.dp))
                    if (made > 0) {
                        Text(
                            if (made > 99) "99+" else "$made",
                            style = RtType.caption,
                            color = RtColors.OnPrimary,
                            modifier = Modifier.align(Alignment.TopEnd).offsetBadge().clip(RoundedCornerShape(50)).background(RtColors.Primary).padding(horizontal = 5.dp),
                        )
                    }
                }
            },
        )
        val list = rides
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp) }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = RtDimens.screenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.Surface)
                        .clickable(role = Role.Button) { pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(Icons.Outlined.VideoLibrary, contentDescription = null, tint = RtColors.Primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Make a Reel from your videos", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                        Text("Pick videos from your gallery. Ones filmed on a recorded ride get its speed and route.", style = RtType.caption, color = RtColors.TextSecondary)
                    }
                }
            }
            // New suggestions from recent rides, ready to make.
            val fresh = list.filter { it.ready.isNotEmpty() && System.currentTimeMillis() - it.startMillis < READY_DAYS * 86_400_000L }
            if (fresh.isNotEmpty()) {
                item { Label("Ready for you") }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        fresh.forEach { r -> r.ready.forEach { (title, format) -> ReadyCard(r, title, format) { onOpenRide(r.id) } } }
                    }
                }
            }
            item { Label("Your rides") }
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        title = "No rides with clips yet",
                        message = "Turn on Moments on Home before a ride. Rides with 2 or more clips show up here, ready to become a Reel.",
                        icon = Icons.Outlined.Movie,
                    )
                }
            }
            items(list, key = { it.id }) { r -> RideRow(r) { onOpenRide(r.id) } }
        }
    }
}

private fun Modifier.offsetBadge() = this.offset(x = 6.dp, y = (-4).dp)

/** Suggestions from rides in the last this-many days show under Ready for you. */
private const val READY_DAYS = 14

/** A suggestion ready to make, as a cover card. */
@Composable
private fun ReadyCard(r: StudioRide, title: String, format: String, onClick: () -> Unit) {
    Column(
        Modifier.width(128.dp).clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClickLabel = "Open", onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Thumb(r.thumbs.firstOrNull(), Modifier.fillMaxSize())
            Text(
                format,
                style = RtType.caption,
                color = RtColors.OnPrimary,
                modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(RtColors.Primary).padding(horizontal = 6.dp),
            )
        }
        Column(Modifier.padding(8.dp)) {
            Text(title, style = RtType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = RtColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.name, style = RtType.caption, color = RtColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(java.util.Locale.getDefault()), style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
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
            Text(r.stateLine, style = RtType.caption, color = if (r.ready.isNotEmpty()) RtColors.Primary else RtColors.TextTertiary)
        }
        Spacer(Modifier.width(8.dp))
        Text("Open", style = RtType.button, color = RtColors.Primary)
    }
}
