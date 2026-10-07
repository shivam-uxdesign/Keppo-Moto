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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.ScreenHeader
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
        val hits = ClipSearch.search(q, lines, seen) { id -> clips[id]?.let { m -> Triple(m.rideId, rideNames[m.rideId] ?: "A ride", m.videoStartMillis) } }
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

/** Studio › Search: find what you said, or what was in the shot, in every ride's clips. */
@Composable
fun StudioSearchScreen(onBack: () -> Unit, onOpenRide: (String) -> Unit) {
    val vm = appViewModel { StudioSearchViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<SearchHit?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
        ScreenHeader("Search", onBack = onBack, subtitle = "What you said, or what was in the shot")
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::search,
            placeholder = { Text("paani, flyover, rain…", style = RtType.body, color = RtColors.TextTertiary) },
            singleLine = true,
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            listOfNotNull(
                "${s.clips} clips",
                s.unread.takeIf { it > 0 }?.let { "$it not read yet (open their ride's Studio to read them)" },
                s.unseen.takeIf { it > 0 }?.let { "$it Gemini hasn't looked at" },
            ).joinToString(" · "),
            style = RtType.caption,
            color = RtColors.TextTertiary,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (s.unseen > 0) {
            Text(
                s.looking ?: "Let Gemini look at them (finds rain, dogs, flyovers…)",
                style = RtType.button,
                color = if (s.looking != null) RtColors.TextSecondary else RtColors.Primary,
                modifier = Modifier.clickable(enabled = s.looking == null, role = Role.Button) { vm.look() }.padding(vertical = 6.dp),
            )
        }
        s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
        LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.query.isNotBlank() && s.hits.isEmpty() && !s.loading) item { Text("Nothing found", style = RtType.body, color = RtColors.TextSecondary) }
            items(s.hits, key = { "${it.momentId}-${it.line.startMs}-${it.seen}" }) { h ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClickLabel = "Play") { playing = h }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(vm.thumb(h.momentId), Modifier.size(width = 44.dp, height = 70.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.line.text, style = RtType.body, color = if (h.seen) RtColors.TextSecondary else RtColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${h.rideName} · ${Format.rideDate(h.atMillis)} ${Format.timeOfDay(h.atMillis)}", style = RtType.caption, color = RtColors.TextTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
    playing?.let { h ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { playing = null }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                vm.uri(h.momentId)?.let { Player(it, Modifier, seek = h.line.startMs to System.nanoTime(), height = 440.dp) }
                Text(h.line.text, style = RtType.body, color = androidx.compose.ui.graphics.Color.White, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(if (h.seen) "Save clip" else "Save this sentence", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.saveLine(h); playing = null }.padding(8.dp))
                    Text("Open ride", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { playing = null; onOpenRide(h.rideId) }.padding(8.dp))
                    Text("Close", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { playing = null }.padding(8.dp))
                }
            }
        }
    }
}
