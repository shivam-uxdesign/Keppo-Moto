package com.ridetrack.app.studio

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchState(
    val loading: Boolean = true,
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val clips: Int = 0,
    /** Clips whose words haven't been read (they can't be found by what was said). */
    val unread: Int = 0,
    /** Clips Gemini hasn't looked at (they can't be found by what's in them). */
    val unseen: Int = 0,
    /** "Gemini is looking · 12 of 40"; null when not. */
    val looking: String? = null,
    val toast: String? = null,
)

/** Search across every clip of every ride: what you said, and what Gemini saw. */
class StudioSearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var clips: Map<String, Moment> = emptyMap()
    private var rideNames: Map<String, String> = emptyMap()
    private var lines: Map<String, List<CaptionLine>> = emptyMap()
    private var seen: Map<String, List<String>> = emptyMap()
    private var tags: Map<String, List<String>> = emptyMap()

    init { reload() }

    private fun reload() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val all = c.moments.all().filter { it.kind == MomentKind.CLIP && it.file.isFile }
                clips = all.associateBy { it.id }
                rideNames = all.map { it.rideId }.distinct().associateWith { id -> c.rides.get(id)?.name ?: "A ride" }
                // Studio's timed lines, or the transcript as one line.
                lines = all.associate { m ->
                    m.id to (StudioText.load(m.file) ?: m.transcript?.takeIf { it.isNotBlank() }?.let { listOf(CaptionLine(0, m.durationMillis ?: 0, it)) }.orEmpty())
                }
                seen = all.mapNotNull { m -> ClipSeen.load(m.file)?.let { m.id to it } }.toMap()
                // Tags the app worked out itself ("downshift"), searchable like what Gemini saw.
                tags = all.mapNotNull { m -> ClipDownshifts.load(m.file)?.takeIf { it.isNotEmpty() }?.let { m.id to listOf("downshift", "downshifting") } }.toMap()
            }
            _state.update { s ->
                s.copy(
                    loading = false,
                    clips = clips.size,
                    unread = clips.values.count { StudioText.load(it.file) == null && it.transcript == null },
                    unseen = clips.size - seen.size,
                )
            }
            search(_state.value.query)
        }
    }

    fun search(q: String) {
        _state.update { it.copy(query = q) }
        if (q.isBlank()) { _state.update { it.copy(hits = emptyList()) }; return }
        val seenAndTags = (seen.keys + tags.keys).associateWith { id -> seen[id].orEmpty() + tags[id].orEmpty() }
        val hits = ClipSearch.search(q, lines, seenAndTags) { id -> clips[id]?.let { m -> Triple(m.rideId, rideNames[m.rideId] ?: "A ride", m.videoStartMillis) } }
        _state.update { it.copy(hits = hits.take(100)) }
    }

    fun thumb(id: String) = clips[id]?.thumb

    fun uri(id: String): Uri? = clips[id]?.file?.let(Uri::fromFile)

    /** Gemini looks at the clips it hasn't seen yet (a few per request, while the free allowance lasts). */
    fun look() {
        if (_state.value.looking != null) return
        viewModelScope.launch {
            val n = ClipSeen.look(c, clips.values.sortedByDescending { it.timeMillis }) { done, of -> _state.update { it.copy(looking = "Gemini is looking · $done of $of") } }
            _state.update { it.copy(looking = null, toast = if (n == 0) "Gemini couldn't look now (its free limit, or no connection)" else "Looked at $n clips") }
            reload()
        }
    }

    /** Keeps just that sentence (a little either side) in Saved clips. */
    fun saveLine(h: SearchHit) {
        val m = clips[h.momentId] ?: return
        viewModelScope.launch {
            val start = (h.line.startMs - 300).coerceAtLeast(0)
            val end = if (h.seen) (m.durationMillis ?: 10_000) else (h.line.endMs + 400).coerceAtMost(m.durationMillis ?: (h.line.endMs + 400))
            val ok = runCatching {
                c.savedClips.save(Uri.fromFile(m.file), start, end, lines[m.id].orEmpty(), from = h.rideName, rideId = m.rideId, atMillis = m.videoStartMillis, camera = m.camera, topKmh = m.topSpeedMps?.let { (it * 3.6).toInt() })
            }.onFailure { c.errors.record("Studio saved clips", "Couldn't save the clip", it) }.isSuccess
            _state.update { it.copy(toast = if (ok) "Saved · Your Reels › Saved clips" else "Couldn't save it") }
        }
    }
}

/** Which hits Search shows. */
private enum class SearchKind(val label: String) { BOTH("Both"), SAID("What you said"), SEEN("What's in it") }

/**
 * Studio › Search: find what you said, or what was in the shot, in every ride's clips. Hits come
 * grouped by ride with the word lit; tap one to play it right there, with what can be done with it.
 */
@Composable
fun StudioSearchScreen(onBack: () -> Unit, onOpenRide: (String) -> Unit, onOpenStudio: (String) -> Unit = onOpenRide) {
    val vm = appViewModel { StudioSearchViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<SearchHit?>(null) }
    var kind by remember { mutableStateOf(SearchKind.BOTH) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val hits = s.hits.filter { h -> when (kind) { SearchKind.BOTH -> true; SearchKind.SAID -> !h.seen; SearchKind.SEEN -> h.seen } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(RtColors.SurfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Search, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = s.query,
                    onValueChange = { open = null; vm.search(it) },
                    singleLine = true,
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(RtColors.Primary),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        if (s.query.isEmpty()) Text("paani, flyover, rain…", style = RtType.body, color = RtColors.TextTertiary)
                        inner()
                    },
                )
            }
            Text("Cancel", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onBack).padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised).padding(3.dp)) {
            SearchKind.entries.forEach { k ->
                Text(
                    k.label,
                    style = RtType.caption,
                    color = RtColors.TextPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (k == kind) RtColors.Surface else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Tab) { kind = k }.padding(vertical = 8.dp),
                )
            }
        }
        // Gemini looking at clips is a Pro feature (and needs the rider's OK).
        if (s.unseen > 0 && kind != SearchKind.SAID && com.ridetrack.app.ui.appContainer().proAi()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.looking ?: "Gemini hasn't looked at ${s.unseen} clips yet, so rain, dogs or flyovers in them won't show.",
                    style = RtType.caption,
                    color = RtColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (s.looking == null) Text("Look now", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.look() }.padding(start = 10.dp))
            }
        }
        if (s.unread > 0 && kind != SearchKind.SEEN) {
            Text("${s.unread} clips aren't read yet: open their ride's Studio to read them.", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))
        }
        s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 6.dp)) }
        LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.query.isNotBlank() && hits.isEmpty() && !s.loading) item { Text("Nothing found", style = RtType.body, color = RtColors.TextSecondary) }
            if (s.query.isBlank()) item { Text("Search ${s.clips} clips from every ride", style = RtType.body, color = RtColors.TextSecondary) }
            hits.groupBy { it.rideId }.forEach { (_, group) ->
                item(key = "ride-${group.first().rideId}") {
                    Text("${group.first().rideName.uppercase()} · ${group.size}", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                }
                items(group, key = { "${it.momentId}-${it.line.startMs}-${it.seen}" }) { h ->
                    val isOpen = open == h
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Surface)
                            .clickable(role = Role.Button, onClickLabel = if (isOpen) "Close" else "Play") { open = if (isOpen) null else h }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!isOpen) {
                                Thumb(vm.thumb(h.momentId), Modifier.size(width = 40.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)))
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (h.seen) androidx.compose.ui.text.AnnotatedString("Seen: " + h.line.text) else lit("\u201c${h.line.text}\u201d", s.query, RtColors.Primary),
                                    style = RtType.body,
                                    color = if (h.seen) RtColors.TextSecondary else RtColors.TextPrimary,
                                    maxLines = if (isOpen) 6 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    Format.timeOfDay(h.atMillis + if (h.seen) 0 else h.line.startMs) + if (isOpen) "" else if (h.seen) " · what's in it" else " · tap to play",
                                    style = RtType.caption,
                                    color = RtColors.TextTertiary,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (isOpen) {
                            vm.uri(h.momentId)?.let { Player(it, Modifier.align(Alignment.CenterHorizontally), seek = h.line.startMs to System.nanoTime(), height = 300.dp) }
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(if (h.seen) "Save the clip" else "Save the sentence", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.saveLine(h) }.padding(vertical = 6.dp))
                                Text("Add to an edit", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { open = null; onOpenStudio(h.rideId) }.padding(vertical = 6.dp))
                                Text("Open the ride", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { open = null; onOpenRide(h.rideId) }.padding(vertical = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [text] with every [query] word lit in the brand colour. */
private fun lit(text: String, query: String, ink: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    val words = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }
    words.forEach { w ->
        Regex(Regex.escape(w), RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            addStyle(androidx.compose.ui.text.SpanStyle(color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), m.range.first, m.range.last + 1)
        }
    }
}
