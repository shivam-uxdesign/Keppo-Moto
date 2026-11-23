package com.ridetrack.app.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Where an ask across all rides is. */
enum class AskPhase { THINKING, QUESTION, RESULTS }

/** An ask across all rides on screen: Gemini reading it, a question back, or what was found. */
data class AskAllState(
    val ask: String,
    val phase: AskPhase = AskPhase.THINKING,
    val doing: String = "Looking through your rides…",
    val plan: AskPlan? = null,
    val filter: AskFilter = AskFilter(),
    val step: Int = 0,
    /** Questions answered so far, with the answer picked. */
    val answered: List<Pair<String, String>> = emptyList(),
    /** For each answer to the question on screen: how many it finds ("14 found"). */
    val hints: List<String> = emptyList(),
    val found: List<AskHit> = emptyList(),
    val kept: List<AskHit> = emptyList(),
    val capped: Boolean = false,
    val dropped: Set<String> = emptySet(),
    /** Clips in range not looked at yet (for things seen), or not tagged yet (for downshifts). */
    val unseen: Int = 0,
    val untagged: Int = 0,
    /** Gemini couldn't read it, no clips, the free Reel is made…: said instead of results. */
    val problem: String? = null,
    /** Looking at clips or tagging older rides, while it runs. */
    val working: String? = null,
) {
    val question: AskQuestion? get() = plan?.questions?.getOrNull(step)
    val title: String get() = filter.title.ifBlank { ask.replaceFirstChar { it.uppercase() }.take(40) }
}

class AskAllViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<AskAllState?>(null)
    /** Null when no ask across all rides is open. */
    val state: StateFlow<AskAllState?> = _state.asStateFlow()
    private var clips: List<AskClip> = emptyList()
    /** A note for the feed once this closes ("Making … in the background"); the feed clears it. */
    val note = MutableStateFlow<String?>(null)

    private fun update(f: (AskAllState) -> AskAllState) = _state.update { it?.let(f) }

    fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _state.value = AskAllState(t)
        if (!c.transcripts.available) return update { it.copy(problem = "Gemini isn't in this build") }
        // As the rest of Studio: a free rider whose weekly Reel is made waits (or goes Pro).
        if (!c.studioOpen()) return update { it.copy(problem = "This week's free Reel is made. Pro asks across all rides any time.") }
        c.studio.rememberAsk(t)
        viewModelScope.launch {
            clips = load()
            if (clips.isEmpty()) return@launch update { it.copy(problem = "No clips yet. Turn on Moments before a ride and they'll be here.") }
            val answer = try {
                StudioGemini(preferred = { c.transcripts.model }, onWorking = { c.transcripts.model = it })
                    .askFilter(AskPrompt.prompt(t, AskSearch.menu(clips), LocalDate.now()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio ask all rides", "Gemini couldn't read the ask", e)
                return@launch update { it.copy(problem = StudioEngine.askProblem(e)) }
            }
            val plan = AskPrompt.parse(answer)
                ?: return@launch update { it.copy(problem = "Gemini's answer couldn't be read. Try saying it another way.") }.also {
                    c.errors.record("Studio ask all rides", "Gemini's answer couldn't be read", null, answer.take(4_000))
                }
            update { it.copy(plan = plan, filter = plan.filter) }
            if (plan.questions.isEmpty()) results() else showQuestion()
        }
    }

    private fun showQuestion() = update { s ->
        val q = s.question ?: return@update s
        s.copy(phase = AskPhase.QUESTION, hints = q.answers.map { a -> "${AskSearch.find(a.patch.apply(s.filter), clips).size} found" })
    }

    /** The rider picked answer [k] to the question on screen. */
    fun answer(k: Int) {
        val s = _state.value ?: return
        val q = s.question ?: return
        val a = q.answers.getOrNull(k) ?: return
        update { it.copy(filter = a.patch.apply(it.filter), answered = it.answered + (q.text to a.label), step = it.step + 1) }
        if (_state.value?.question != null) showQuestion() else results()
    }

    /** No more questions: Studio goes with what it has. */
    fun skip() = results()

    private fun results() = update { s ->
        val found = AskSearch.find(s.filter, clips)
        val (kept, capped) = AskSearch.fit(found.filter { it.key !in s.dropped }, s.filter.lengthSec)
        s.copy(
            phase = AskPhase.RESULTS, found = found, kept = kept, capped = capped,
            unseen = AskSearch.unseen(s.filter, clips).size, untagged = AskSearch.untagged(s.filter, clips).size,
        )
    }

    private val monthMs = 30L * 86_400_000

    /** The chips under the count: the last month only, only with the rider's voice, 15 s. */
    fun toggleLastMonth() {
        update { s -> s.copy(filter = s.filter.copy(fromMillis = if (lastMonth(s)) null else System.currentTimeMillis() - monthMs, toMillis = null)) }
        results()
    }

    fun lastMonth(s: AskAllState): Boolean = s.filter.fromMillis?.let { System.currentTimeMillis() - it in (monthMs - 86_400_000)..(monthMs + 86_400_000) } == true

    fun toggleVoice() { update { it.copy(filter = it.filter.copy(voice = !it.filter.voice)) }; results() }

    fun toggleShort() { update { it.copy(filter = it.filter.copy(lengthSec = if (it.filter.lengthSec == 15) null else 15)) }; results() }

    /** Leaves a part out, or puts it back. */
    fun drop(key: String) {
        update { it.copy(dropped = if (key in it.dropped) it.dropped - key else it.dropped + key) }
        results()
    }

    /** Gemini looks at the clips in range not looked at yet (a few frames each), then the search runs again. */
    fun lookNow() {
        val s = _state.value ?: return
        val ids = AskSearch.unseen(s.filter, clips).map { it.momentId }.toSet()
        if (ids.isEmpty()) return
        update { it.copy(working = "Looking at ${ids.size} clips…") }
        viewModelScope.launch {
            runCatching { ClipSeen.look(c, c.moments.all().filter { it.id in ids }, limit = 60) }
            clips = load()
            update { it.copy(working = null) }
            results()
        }
    }

    /** Older rides' clips get their downshifts found (on the phone, from the engine sound), then the search runs again. */
    fun tagOlder() {
        val s = _state.value ?: return
        val rides = AskSearch.untagged(s.filter, clips).map { it.rideId }.distinct()
        if (rides.isEmpty()) return
        update { it.copy(working = "Finding downshifts in ${rides.size} older ride${if (rides.size == 1) "" else "s"}…") }
        viewModelScope.launch {
            rides.forEach { r -> runCatching { ClipDownshifts.tagRide(c, r) } }
            clips = load()
            update { it.copy(working = null) }
            results()
        }
    }

    /** The found parts as a Reel not made yet, opened in the editor ([open]: its ride and id). */
    fun openInEditor(open: (rideId: String, reelId: String) -> Unit) = prepare { engine, job, host ->
        val saved = c.reels.save(job.project, null, emptyList())
        _state.value = null
        open(host, saved.id)
    }

    /** The found parts made into a Reel in the background; [say] gets the note to show. */
    fun makeNow(say: (String) -> Unit) = prepare { _, job, _ ->
        c.reelMaker.enqueue(listOf(job))
        val t = _state.value?.title.orEmpty()
        _state.value = null
        say("Making “$t” in the background. It’ll be in Your Reels.")
    }

    private fun prepare(then: suspend (StudioEngine, MakeJob, String) -> Unit) {
        val s = _state.value ?: return
        val hits = s.kept.takeIf { it.isNotEmpty() } ?: return
        update { it.copy(working = "Getting the clips ready…") }
        viewModelScope.launch {
            // Hosted by the newest ride; the other rides' clips are borrowed in.
            val host = hits.maxBy { it.atMillis }.rideId
            val engine = Compilations.hosted(c, host, hits.map { it.momentId })
            val pc = engine?.pieces(listOf(AskSearch.script(s.title, hits)))?.firstOrNull()
            val st = StudioState(title = s.title, options = c.studio.options, musicUri = c.studio.music?.first?.let(android.net.Uri::parse), musicName = c.studio.music?.second)
            val job = pc?.let { engine.job(it, st) }
            if (engine == null || job == null) return@launch update { it.copy(working = null, problem = "Those clips couldn't be put together. Some may have been deleted.") }
            then(engine, job, host)
        }
    }

    fun close() { _state.value = null }

    /** Every clip on the phone from a saved ride, as the search sees it. Reads files: off the main thread. */
    private suspend fun load(): List<AskClip> = withContext(Dispatchers.IO) {
        val rides = c.rides.observeCompleted().first().associateBy { it.id }
        c.moments.all().filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) >= 1_500 && it.rideId in rides }.map { m ->
            val dur = m.durationMillis ?: 0
            // Captions with timing when read; else the transcript as one line at the start.
            val lines = StudioText.load(m.file) ?: m.transcript?.takeIf { it.isNotBlank() }?.let { listOf(CaptionLine(0, minOf(dur, 4_000), it)) }.orEmpty()
            AskClip(
                m.id, m.rideId, rides[m.rideId]?.name.orEmpty(), m.videoStartMillis, dur,
                (m.timeMillis - m.videoStartMillis).takeIf { it in 0..dur }, m.types, lines,
                ClipSeen.load(m.file), ClipDownshifts.load(m.file), m.thumb?.takeIf { it.isFile },
            )
        }
    }
}

/**
 * The ask across all rides, full screen over the Studio feed: Gemini reading it, at most two
 * questions back, then what was found, to narrow down and open in the editor or make now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAllSheet(vm: AskAllViewModel, onOpenReel: (rideId: String, reelId: String) -> Unit, onNote: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val st = s ?: return
    BackHandler { vm.close() }
    Column(
        Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Studio") { vm.close() }
            Column(Modifier.weight(1f)) {
                Text("ALL RIDES", style = RtType.label, color = RtColors.TextSecondary)
                Text(st.ask, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            st.problem != null -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                Text(st.problem, style = RtType.body, color = RtColors.TextSecondary)
                Button("Back to Studio", primary = true) { vm.close() }
            }
            st.phase == AskPhase.THINKING -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                Text(st.doing, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                Text("Only your words go to Gemini. Your clips stay on the phone.", style = RtType.caption, color = RtColors.TextSecondary)
            }
            st.phase == AskPhase.QUESTION -> Question(vm, st)
            else -> Results(vm, st, onOpenReel, onNote)
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            style = RtType.body,
            color = if (mine) RtColors.OnInverse else RtColors.TextSecondary,
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .background(if (mine) RtColors.Inverse else RtColors.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Question(vm: AskAllViewModel, st: AskAllState) {
    val q = st.question ?: return
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(8.dp))
        Bubble(st.ask, mine = true)
        st.answered.forEach { (question, answer) -> Bubble(question, mine = false); Bubble(answer, mine = true) }
        Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp)).background(RtColors.SurfaceRaised).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = RtColors.Primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(6.dp))
                Text("STUDIO · QUESTION ${st.step + 1} OF ${st.plan?.questions?.size ?: 1}", style = RtType.label, color = RtColors.Primary)
            }
            Spacer(Modifier.height(6.dp))
            Text(q.text, style = RtType.bodyStrong, color = RtColors.TextPrimary)
            q.why?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
        }
    }
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        q.answers.forEachIndexed { k, a ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(RtColors.Surface)
                    .clickable(role = Role.Button) { vm.answer(k) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.label, style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                st.hints.getOrNull(k)?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
            }
        }
        Text(
            "Skip: Studio decides",
            style = RtType.button,
            color = RtColors.TextSecondary,
            modifier = Modifier.align(Alignment.CenterHorizontally).clickable(role = Role.Button) { vm.skip() }.padding(12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Results(vm: AskAllViewModel, st: AskAllState, onOpenReel: (String, String) -> Unit, onNote: (String) -> Unit) {
    val total = st.kept.sumOf { it.lengthMs }
    Text(AskSearch.count(st.filter, st.kept.size), style = RtType.metricL, color = RtColors.TextPrimary, modifier = Modifier.padding(top = 8.dp))
    Text("${AskSearch.rides(st.kept)} ride${if (AskSearch.rides(st.kept) == 1) "" else "s"} · ${Format.clock(total)}", style = RtType.body, color = RtColors.TextSecondary)
    // What to know about the result, and what can be done about it.
    val note: Pair<String, Pair<String, () -> Unit>?>? = when {
        st.working != null -> st.working to null
        st.untagged > 0 -> "${st.untagged} older clips haven't been checked for downshifts yet." to ("Check them" to vm::tagOlder)
        st.unseen > 0 -> "${st.unseen} clips haven't been looked at yet, so what's in them can't be found." to ("Look now" to vm::lookNow)
        st.kept.isEmpty() && st.found.isEmpty() -> "Nothing found for that. Try other words, or turn a chip off." to null
        st.capped -> "Found ${st.found.size}: the best that fit ${st.filter.lengthSec ?: AskSearch.MAX_SEC} s are in, one from each ride first. Tap × to swap one out." to null
        else -> null
    }
    note?.let { (text, action) ->
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (st.working != null) CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(text, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
            action?.let { (label, act) ->
                Text(label, style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(RtColors.PrimaryContainer).clickable(role = Role.Button, onClick = act).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Choice("Last month", vm.lastMonth(st)) { vm.toggleLastMonth() }
        Choice("With my voice", st.filter.voice) { vm.toggleVoice() }
        Choice("15 s", st.filter.lengthSec == 15) { vm.toggleShort() }
    }
    // What's in, and what was taken out (to put back); parts left out by the length aren't shown.
    val shown = st.found.filter { it in st.kept || it.key in st.dropped }
    LazyVerticalGrid(
        GridCells.Fixed(3),
        Modifier.weight(1f).padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        items(shown, key = { it.key }) { h ->
            val out = h.key in st.dropped
            Box(Modifier.height(150.dp).clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).alpha(if (out) 0.35f else 1f)) {
                Thumb(h.thumb, Modifier.fillMaxSize())
                Text(
                    Format.clock(h.lengthMs),
                    style = RtType.label,
                    color = Color.White,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
                IconButton(onClick = { vm.drop(h.key) }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                    Icon(
                        if (out) Icons.Rounded.Add else Icons.Rounded.Close,
                        contentDescription = (if (out) "Put back " else "Leave out ") + h.rideName,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
                    )
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(6.dp)) {
                    Text(h.why, style = RtType.caption, color = RtColors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(h.rideName, style = RtType.caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Format.rideDate(h.atMillis), style = RtType.caption, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                }
            }
        }
    }
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val ready = st.kept.isNotEmpty() && st.working == null
        Box(Modifier.alpha(if (ready) 1f else 0.4f)) {
            Button("Open in the editor", primary = true, modifier = Modifier.fillMaxWidth()) { if (ready) vm.openInEditor(onOpenReel) }
        }
        Text(
            "Make it now, ready to share",
            style = RtType.button,
            color = RtColors.Primary,
            modifier = Modifier.align(Alignment.CenterHorizontally).alpha(if (ready) 1f else 0.4f)
                .clickable(role = Role.Button, enabled = ready) { vm.makeNow(onNote) }.padding(12.dp),
        )
    }
}
