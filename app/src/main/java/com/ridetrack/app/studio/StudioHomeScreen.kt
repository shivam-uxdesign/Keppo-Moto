package com.ridetrack.app.studio

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.RideEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** One idea for a ride, as the Studio tab shows it. */
data class IdeaCard(
    val title: String,
    val format: String,
    val why: String?,
    val lengthSec: Int,
    val thumb: File?,
    val made: Boolean,
    val fromClips: Boolean,
    /** When its unsaved edit was last changed; null when there's none. */
    val draftAt: Long? = null,
    /** Its opening clip and where in it, for a peek. */
    val peek: Pair<File, Long>? = null,
)

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
    /** Its ideas (Gemini's first, then Show all's), from the saved plan. */
    val ideas: List<IdeaCard> = emptyList(),
    val made: Int = 0,
    /** Clips whose words haven't been read yet. */
    val unread: Int = 0,
    /** Why ideas aren't there yet (waiting for Wi-Fi, Gemini's limit); null when not waiting. */
    val waiting: String? = null,
)

/** What the Studio tab asks of its view model (a stand-in draws it in screenshots). */
internal interface StudioHomeActions {
    fun hide(rideId: String, title: String)
    fun unhide(rideId: String, title: String)
    fun makeAll(rideId: String, done: (Int) -> Unit)
    fun sendAllToJournal(rideId: String, done: (Int) -> Unit)
    fun picked(uris: List<android.net.Uri>, openRide: (String) -> Unit, openPhone: () -> Unit)
}

class StudioHomeViewModel(private val c: AppContainer) : ViewModel(), StudioHomeActions {
    private val prefs = c.appContext.getSharedPreferences("studio-feed", Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(prefs.getStringSet(HIDDEN, emptySet()).orEmpty())
    /** Ideas the rider swiped away: "rideId|title". */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    /** Rides with at least 2 clips, newest first; null while loading. */
    val rides: StateFlow<List<StudioRide>?> = kotlinx.coroutines.flow.combine(c.rides.observeCompleted(), c.reels.reels) { rides, reels -> rides to reels }.map { (rides, reels) ->
        val clips = c.moments.all().filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) >= 1_500 }.groupBy { it.rideId }
        val now = System.currentTimeMillis()
        val geminiAt = com.ridetrack.app.transcribe.GeminiQuota.freeAt(com.ridetrack.app.transcribe.GeminiQuota.STORY)?.takeIf { it > now }
        rides.mapNotNull { r ->
            val ms = clips[r.id].orEmpty()
            if (ms.size < 2) return@mapNotNull null
            val madeIdeas = reels.filter { it.rideId == r.id && it.deletedAt == null }
            val thumbOf = ms.associate { it.id to it.thumb?.takeIf(File::isFile) }
            val fileOf = ms.associate { it.id to it.file }
            val drafts = File(c.appContext.filesDir, "studio-drafts")
            // One card per moment: other takes on it are on the ride's own page, under its card.
            val ideas = IdeaGroups.group(planOf(r.id)) { it }.map { it.main }.map { sc ->
                val first = sc.sections.firstOrNull { it.shots.isNotEmpty() }?.shots?.first()
                IdeaCard(
                    title = sc.title, format = sc.format.label, why = sc.why, lengthSec = sc.lengthSec,
                    thumb = first?.clip?.let { thumbOf[it] } ?: ms.first().thumb,
                    made = madeIdeas.any { it.idea == sc.title }, fromClips = sc.every,
                    draftAt = File(drafts, "ride-${r.id}-idea-" + Integer.toHexString(sc.title.hashCode()) + ".json").takeIf { it.isFile }?.lastModified(),
                    peek = first?.let { sh -> fileOf[sh.clip]?.let { it to sh.inMs } },
                )
            }
            val waiting = if (ideas.isEmpty()) {
                when {
                    !afterRideWaiting(r.id) -> null
                    geminiAt != null -> "Gemini is free again at ${com.ridetrack.app.transcribe.GeminiQuota.clock(geminiAt)}"
                    else -> "Ideas are waiting for a connection"
                }
            } else {
                null
            }
            StudioRide(
                ideas = ideas,
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

    /** The saved ideas of a ride. */
    private fun planOf(rideId: String): List<Script> = runCatching {
        val f = File(File(c.appContext.filesDir, "studio-plans"), "$rideId.json")
        if (!f.isFile) return emptyList()
        val a = org.json.JSONObject(f.readText()).getJSONArray("pieces")
        (0 until a.length()).mapNotNull { ScriptJson.read(a.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** The after-ride ideas are still waiting to run (for Wi-Fi, a connection, or Gemini). */
    private fun afterRideWaiting(rideId: String): Boolean = runCatching {
        androidx.work.WorkManager.getInstance(c.appContext).getWorkInfosForUniqueWork("studio-after-ride-$rideId").get()
            .any { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.RUNNING }
    }.getOrDefault(false)

    /** Every idea of [rideId] not made yet, made in the background; [done] gets how many. */
    override fun makeAll(rideId: String, done: (Int) -> Unit) {
        viewModelScope.launch { done(StudioBatch.makeAll(c, rideId, service = true)) }
    }

    /** This ride's Reels into its Keppo Journal entry. */
    override fun sendAllToJournal(rideId: String, done: (Int) -> Unit) {
        viewModelScope.launch {
            val list = c.reels.reels.value.filter { it.rideId == rideId && it.deletedAt == null }
            if (list.isNotEmpty()) sendToJournal(c, list)
            done(list.size)
        }
    }

    override fun hide(rideId: String, title: String) = setHidden(_hidden.value + "$rideId|$title")
    override fun unhide(rideId: String, title: String) = setHidden(_hidden.value - "$rideId|$title")
    private fun setHidden(v: Set<String>) {
        _hidden.value = v
        prefs.edit().putStringSet(HIDDEN, v).apply()
    }

    /**
     * Videos picked for a new Reel: when all were filmed during one recorded ride, that ride's
     * Studio opens with them (real speed and route); otherwise Studio for phone videos only.
     */
    override fun picked(uris: List<android.net.Uri>, openRide: (String) -> Unit, openPhone: () -> Unit) {
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

    private companion object {
        const val HIDDEN = "hidden"
    }
}

/**
 * The Studio tab: a strip of rides, then the chosen ride's ideas one big card at a time
 * (swipe up for the next, left to hide, hold for options, tap to open it in the editor).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StudioHomeScreen(
    onOpenRide: (String) -> Unit,
    onOpenPhone: (reelId: String?) -> Unit,
    onOpenReels: () -> Unit,
    onOpenStyles: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenIdea: (rideId: String, title: String, make: Boolean) -> Unit = { r, _, _ -> onOpenRide(r) },
    onOpenPaywall: () -> Unit = {},
) {
    val c = com.ridetrack.app.ui.appContainer()
    val pro by c.entitlements.pro.collectAsStateWithLifecycle()
    val usedAt by c.freeReels.usedAt.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    // A free rider whose weekly Reel is made: Studio shows through, blurred, under the lock.
    val locked = !pro && !com.ridetrack.app.pro.FreeReelRule.available(usedAt, now)
    val consent by c.aiConsent.given.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Box(if (locked || !consent) Modifier.fillMaxSize().blur(18.dp) else Modifier.fillMaxSize()) {
            StudioHomeBody(onOpenRide, onOpenPhone, onOpenReels, onOpenStyles, onOpenSearch, onOpenSettings, onOpenIdea)
        }
        if (!consent) com.ridetrack.app.ui.pro.AiConsentCard(onNotNow = null)
        else if (locked) com.ridetrack.app.ui.pro.StudioLocked(com.ridetrack.app.pro.FreeReelRule.nextAt(usedAt, now), onOpenPaywall, onOpenReels = onOpenReels)
        else if (!pro) com.ridetrack.app.ui.pro.FreeReelChip(onOpenPaywall, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioHomeBody(
    onOpenRide: (String) -> Unit,
    onOpenPhone: (reelId: String?) -> Unit,
    onOpenReels: () -> Unit,
    onOpenStyles: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenIdea: (rideId: String, title: String, make: Boolean) -> Unit,
) {
    val vm = appViewModel { StudioHomeViewModel(it) }
    val rides by vm.rides.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val reels by com.ridetrack.app.ui.appContainer().reels.reels.collectAsStateWithLifecycle()
    StudioHomeContent(
        vm, rides, hidden, reels.count { it.deletedAt == null },
        onOpenRide, onOpenPhone, onOpenReels, onOpenStyles, onOpenSearch, onOpenSettings, onOpenIdea,
        pill = { open -> MakingPill(Modifier.fillMaxWidth(), open) },
        makingSheet = { visible, close -> MakingSheet(visible, close) },
    )
}

/** The Studio tab itself, drawn from [rides]; [pill] and [makingSheet] are the background-making parts. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StudioHomeContent(
    vm: StudioHomeActions,
    rides: List<StudioRide>?,
    hidden: Set<String>,
    made: Int,
    onOpenRide: (String) -> Unit,
    onOpenPhone: (reelId: String?) -> Unit,
    onOpenReels: () -> Unit,
    onOpenStyles: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenIdea: (rideId: String, title: String, make: Boolean) -> Unit,
    pill: @Composable (open: () -> Unit) -> Unit = {},
    makingSheet: @Composable (visible: Boolean, close: () -> Unit) -> Unit = { _, _ -> },
) {
    val haptics = LocalHapticFeedback.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.picked(uris, onOpenRide) { onOpenPhone(null) }
    }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var newReel by remember { mutableStateOf(false) }
    // A Gemini result being reported: its title and line ("" for a general report).
    var reporting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var more by remember { mutableStateOf(false) }
    var making by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) { if (note != null) { delay(3_000); note = null } }
    var allRides by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<IdeaCard?>(null) }
    var undo by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(4_000); undo = null } }

    val list = rides
    fun visible(r: StudioRide) = r.ideas.filter { "${r.id}|${it.title}" !in hidden }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
            // ---- top bar: the title, then + (a new Reel), Search, Your Reels, more
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Studio", style = RtType.title, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                RoundIcon(Icons.Outlined.Add, "New Reel from your videos", primary = true) { newReel = true }
                RoundIcon(Icons.Outlined.Search, "Search your clips", onClick = onOpenSearch)
                Box {
                    RoundIcon(Icons.Outlined.VideoLibrary, "Your Reels", onClick = onOpenReels)
                    if (made > 0) {
                        Text(
                            if (made > 99) "99+" else "$made",
                            style = RtType.caption,
                            color = RtColors.OnPrimary,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).clip(RoundedCornerShape(50)).background(RtColors.Primary).padding(horizontal = 5.dp),
                        )
                    }
                }
                RoundIcon(Icons.Outlined.MoreHoriz, "More") { more = true }
            }
            pill { making = true }
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp) }
                return@Column
            }
            if (list.isEmpty()) {
                EmptyState(
                    title = "No rides with clips yet",
                    message = "Turn on Moments on Home before a ride. Rides with 2 or more clips show up here with ideas. Or tap + to make a Reel from your own videos.",
                    icon = Icons.Outlined.Movie,
                )
                return@Column
            }
            // The ride shown: the one picked, else the newest with ideas.
            val ride = list.firstOrNull { it.id == picked } ?: list.firstOrNull { visible(it).isNotEmpty() } ?: list.first()
            val ideas = visible(ride)

            // ---- the edit left off last, to carry on with
            list.flatMap { r -> visible(r).filter { it.draftAt != null }.map { r to it } }.maxByOrNull { it.second.draftAt ?: 0 }?.let { (r, idea) ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(RtColors.Surface)
                        .clickable(role = Role.Button, onClickLabel = "Continue editing") { onOpenIdea(r.id, idea.title, false) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.ridetrack.app.ui.moments.Thumb(idea.thumb, Modifier.size(width = 34.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Continue editing", style = RtType.caption, color = RtColors.Primary)
                        Text(idea.title, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", style = RtType.headline, color = RtColors.TextTertiary)
                }
            }

            // ---- the rides, newest first
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(list.take(12), key = { it.id }) { r ->
                    val on = r.id == ride.id
                    val bg by animateColorAsState(if (on) RtColors.PrimaryContainer else RtColors.Surface, label = "ride")
                    val n = visible(r).size
                    Column(
                        Modifier.widthIn(max = 170.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                            .border(1.dp, if (on) RtColors.Primary else RtColors.Outline, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Tab, onClickLabel = "Show its ideas") { picked = r.id }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(r.name, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Format.rideDate(r.startMillis)} · " + if (n > 0) "$n idea${if (n > 1) "s" else ""}" else "no ideas yet", style = RtType.caption, color = if (on) RtColors.TextPrimary else RtColors.TextTertiary, maxLines = 1)
                    }
                }
                if (list.size > 1) {
                    item {
                        Box(
                            Modifier.height(54.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, RtColors.Outline, RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button) { allRides = true }.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("All rides ›", style = RtType.caption, color = RtColors.TextSecondary) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ---- its ideas: one big card at a time
            val pager = rememberPagerState(pageCount = { ideas.size })
            LaunchedEffect(ride.id) { pager.scrollToPage(0) }
            Text(
                listOfNotNull("${ride.clips} clips", ride.talking.takeIf { it > 0 }?.let { "$it with your voice" }, ideas.size.takeIf { it > 1 }?.let { "idea ${pager.currentPage + 1} of $it" }).joinToString(" · "),
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (ideas.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).border(1.dp, RtColors.Outline, RoundedCornerShape(22.dp)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No ideas for this ride yet", style = RtType.headline, color = RtColors.TextPrimary)
                        Text(ride.waiting ?: "${ride.clips} clips. Gemini reads them and suggests what to make.", style = RtType.body, color = RtColors.TextSecondary)
                        Text(
                            "Find ideas",
                            style = RtType.button,
                            color = RtColors.OnPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(RtColors.Primary).clickable(role = Role.Button) { onOpenRide(ride.id) }.padding(horizontal = 22.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VerticalPager(pager, Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(22.dp)), pageSpacing = 12.dp, key = { ideas.getOrNull(it)?.title ?: "$it" }) { page ->
                            val idea = ideas[page]
                            IdeaCardView(
                                idea,
                                onOpen = { onOpenIdea(ride.id, idea.title, false) },
                                onHold = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); quick = idea },
                                onHide = { vm.hide(ride.id, idea.title); undo = ride.id to idea.title },
                            )
                        }
                        // Where you are in the ride's ideas.
                        Column(Modifier.align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ideas.indices.take(12).forEach { i ->
                                val h by animateDpAsState(if (i == pager.currentPage) 22.dp else 6.dp, label = "dot")
                                Box(Modifier.width(6.dp).height(h).clip(RoundedCornerShape(3.dp)).background(if (i == pager.currentPage) RtColors.Primary else RtColors.Outline))
                            }
                        }
                    }
                }
            }
            if (ideas.isNotEmpty()) Text("Swipe up for the next idea · left to hide · hold for options", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            // ---- ask for something
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(RtColors.Surface)
                    .border(1.dp, RtColors.Outline, RoundedCornerShape(24.dp)).clickable(role = Role.Button) { onOpenRide(ride.id) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = RtColors.Primary, modifier = Modifier.size(18.dp))
                Text("Ask for something, e.g. \"15 s, funny, the water\"", style = RtType.body, color = RtColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // ---- "Idea hidden · Undo"
        AnimatedVisibility(undo != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp, start = 16.dp, end = 16.dp), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Inverse).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Idea hidden", style = RtType.body, color = RtColors.OnInverse, modifier = Modifier.weight(1f))
                Text("Undo", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { undo?.let { (r, t) -> vm.unhide(r, t) }; undo = null }.padding(6.dp))
            }
        }

        AnimatedVisibility(note != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp, start = 16.dp, end = 16.dp), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Text(note.orEmpty(), style = RtType.body, color = RtColors.OnInverse, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Inverse).padding(horizontal = 14.dp, vertical = 12.dp))
        }

        StudioSheet(newReel, { newReel = false }, title = "New Reel") {
            SheetRow("From your phone's videos", "Ones filmed on a ride get its speed and route") {
                newReel = false
                pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
            SheetRow("From Saved clips") { newReel = false; onOpenReels() }
            list?.firstOrNull { it.id == picked }?.let { r -> SheetRow("From ${r.name}'s clips, your own way") { newReel = false; onOpenRide(r.id) } }
        }
        StudioSheet(more, { more = false }) {
            SheetRow("Styles", "Your looks, the brand kit") { more = false; onOpenStyles() }
            SheetRow("Your Reels", "Made, drafts, saved clips, deleted") { more = false; onOpenReels() }
            // Series: each one's folder, and starting one.
            val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
            val series by com.ridetrack.app.ui.appContainer().series.series.collectAsStateWithLifecycle()
            series.forEach { se ->
                SheetRow("Series · ${se.name}", if (se.active) "Its episodes · Ep ${se.nextEpisode} next" else "Off") { more = false; nav(com.ridetrack.app.ui.nav.Routes.series(se.id)) }
            }
            SheetRow("New series", "A name, what it's about and its look; episodes are found after each ride") {
                more = false; nav(com.ridetrack.app.ui.nav.Routes.series(com.ridetrack.app.ui.nav.Routes.NEW_SERIES))
            }
            val shown = list?.firstOrNull { it.id == picked } ?: list?.firstOrNull { visible(it).isNotEmpty() } ?: list?.firstOrNull()
            shown?.takeIf { visible(it).any { i -> !i.made && !i.fromClips } }?.let { r ->
                SheetRow("Make all of ${r.name}'s ideas", "In the background; they'll be in Your Reels") {
                    more = false
                    vm.makeAll(r.id) { n -> note = if (n == 0) "All its ideas are made already" else "Making $n in the background" }
                }
            }
            shown?.takeIf { it.made > 0 }?.let { r ->
                SheetRow("Send ${r.name}'s Reels to Keppo Journal") { more = false; vm.sendAllToJournal(r.id) { n -> note = "Sent $n to Keppo Journal" } }
            }
            SheetRow("In the background", "What's being made, and Gemini's allowance") { more = false; making = true }
            SheetRow("Settings", "After each ride, what new videos start with, saving") { more = false; onOpenSettings() }
            SheetRow("Report a Gemini result", "Something it wrote was offensive, harmful or made up") { more = false; reporting = "" to "" }
        }
        makingSheet(making) { making = false }
        reporting?.let { (title, line) ->
            com.ridetrack.app.ui.pro.AiReportDialog(if (title.isBlank()) "Gemini result" else "idea", listOf(title, line).filter { it.isNotBlank() }.joinToString(": ")) { sent ->
                reporting = null
                if (sent) note = "Thanks: reported. It helps make Studio better."
            }
        }
        StudioSheet(quick != null, { quick = null }, title = quick?.title) {
            val r = list?.firstOrNull { it.id == picked } ?: list?.firstOrNull { it.ideas.any { i -> i.title == quick?.title } }
            quick?.let { idea ->
                // A peek: its opening clip plays.
                idea.peek?.let { (file, at) -> Player(android.net.Uri.fromFile(file), Modifier.align(Alignment.CenterHorizontally), seek = at to idea.title.hashCode().toLong(), height = 240.dp) }
                SheetRow("Open in the editor") { quick = null; r?.let { onOpenIdea(it.id, idea.title, false) } }
                SheetRow("Make it now, ready to share", "In the background; it'll be in Your Reels") { quick = null; r?.let { onOpenIdea(it.id, idea.title, true) } }
                SheetRow("Hide this idea", danger = true) { quick = null; r?.let { vm.hide(it.id, idea.title); undo = it.id to idea.title } }
                SheetRow("Report this idea", "Offensive, harmful or made up: Gemini wrote it") { quick = null; reporting = idea.title to (idea.why ?: "") }
            }
        }
        StudioSheet(allRides, { allRides = false }, title = "All rides") {
            Column(Modifier.verticalScroll(rememberScrollState()).height(460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list?.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.SurfaceRaised).clickable(role = Role.Button) { picked = r.id; allRides = false }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { r.thumbs.take(2).forEach { Thumb(it, Modifier.size(width = 30.dp, height = 46.dp).clip(RoundedCornerShape(5.dp))) } }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Format.rideDate(r.startMillis)} · ${Format.distance(r.distanceM)} · ${visible(r).size} ideas", style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** One idea, big: its first shot, what it is, why it works. Tap, hold, or swipe it left. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IdeaCardView(idea: IdeaCard, onOpen: () -> Unit, onHold: () -> Unit, onHide: () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val dx by animateFloatAsState(drag, label = "swipe")
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { translationX = dx; alpha = 1f - (kotlin.math.abs(dx) / 900f).coerceIn(0f, 0.6f) }
            .clip(RoundedCornerShape(22.dp)).background(RtColors.Surface)
            .pointerInput(idea.title) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (drag < -160f) onHide() else drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, d -> drag = (drag + d).coerceAtMost(0f) }
            }
            .combinedClickable(role = Role.Button, onClickLabel = "Open in the editor", onLongClickLabel = "Options", onLongClick = onHold, onClick = onOpen),
    ) {
        Thumb(idea.thumb, Modifier.fillMaxSize())
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(idea.format, style = RtType.caption, color = RtColors.OnPrimary, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(RtColors.Primary).padding(horizontal = 8.dp, vertical = 2.dp))
            Text(Format.clock(idea.lengthSec * 1000L), style = RtType.caption, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 2.dp))
            if (idea.made) Text("Made", style = RtType.caption, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 2.dp))
            if (idea.draftAt != null) Text("Edited", style = RtType.caption, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 2.dp))
            if (idea.fromClips) Text("From your clips", style = RtType.caption, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Box(Modifier.align(Alignment.Center).size(64.dp).clip(RoundedCornerShape(32.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB80A0A0B)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(idea.title, style = RtType.headline, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            idea.why?.let { Text(it, style = RtType.body, color = Color(0xFFC9C9CF), maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}
