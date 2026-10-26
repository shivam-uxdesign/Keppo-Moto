package com.ridetrack.app.studio.storyboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.AppContainer
import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.StudioGemini
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.VoiceRecorder
import com.ridetrack.app.studio.VoiceTake
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Voice-over lines read in one go: the rider reads every shot's line in order, tapping Next
 * between them. The recording is cut at the taps, the quiet at each end trimmed, and each line
 * placed where its shot starts. Pure parts unit-tested.
 */
object VoiceLines {
    /** Shots with a line to say, in order: shot id → the line. */
    fun lines(s: Script): List<Pair<String, String>> =
        Placeholders.shots(s).mapNotNull { (_, sh) -> sh.board?.say?.takeIf { it.isNotBlank() }?.let { sh.id!! to it } }

    /**
     * Captions for a take of [text] lasting [durMs], in the words as written: short phrases (a
     * sentence, or up to [maxWords] words), each on screen for its share of the take by length.
     */
    fun written(text: String, durMs: Long, maxWords: Int = 6): List<CaptionLine> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || durMs <= 0) return emptyList()
        val phrases = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        words.forEach { w ->
            cur += w
            if (cur.size >= maxWords || w.last() in ".!?,;:…") { phrases += cur; cur = ArrayList() }
        }
        if (cur.isNotEmpty()) phrases += cur
        // Time by letters (a long word takes longer to say), a little for each gap.
        fun weight(p: List<String>) = p.sumOf { it.count(Char::isLetterOrDigit) + 1 }.coerceAtLeast(1)
        val total = phrases.sumOf { weight(it) }.toDouble()
        var at = 0.0
        return phrases.map { ph ->
            val len = durMs * weight(ph) / total
            CaptionLine(at.toLong(), (at + len).toLong(), ph.joinToString(" ")).also { at += len }
        }
    }

    /** [take]'s captions for its [line], as [source] says; written when it can't be heard. */
    suspend fun captionsFor(c: AppContainer, take: VoiceTake, line: String, source: CaptionSource): List<CaptionLine> {
        if (source == CaptionSource.SAID && c.transcripts.available && c.aiConsent.isGiven && take.durMs >= 500) {
            val wav = File(c.appContext.cacheDir, "voice-line-${take.startMs}.wav")
            try {
                val heard = runCatching { StudioGemini(preferred = { c.transcripts.model }, onWorking = { c.transcripts.model = it }).captions(VoiceRecorder.takeWav(take, wav), "audio/wav") }
                    .onFailure { c.errors.record("Storyboard", "Couldn't caption a voice-over line", it) }.getOrNull()
                if (!heard.isNullOrEmpty()) return heard
            } finally {
                wav.delete()
            }
        }
        return written(line, take.durMs)
    }

    /**
     * The captions a shot's own picture carries in the template: none when captions are off or
     * the voice-over carries them (its take has them), custom words over the shot when there's no
     * voice-over, else what's said in the clip itself ([clipLines]).
     */
    fun segmentLines(b: Storyboard, clipLines: List<CaptionLine>, durMs: Long): List<CaptionLine> = when {
        b.caption == Captions.OFF -> emptyList()
        b.say != null -> emptyList()
        b.caption == Captions.CUSTOM -> b.captionText?.let { written(it, durMs) }.orEmpty()
        else -> clipLines
    }

    /** [take]'s captions for shot [b]: none when its captions are off, its own words when custom, else its Say line as [source] says. */
    suspend fun captionsForShot(c: AppContainer, take: VoiceTake, b: Storyboard, source: CaptionSource): List<CaptionLine> = when (b.caption) {
        Captions.OFF -> emptyList()
        Captions.CUSTOM -> b.captionText?.let { written(it, take.durMs) }.orEmpty()
        else -> b.say?.let { captionsFor(c, take, it, source) }.orEmpty()
    }

    /** Switches template [reelId]'s voice-over captions to [source], captioning its lines' takes again. */
    suspend fun recaption(c: AppContainer, reelId: String, source: CaptionSource) {
        val p = c.reels.get(reelId) ?: return
        val s = p.script ?: return
        val filled = p.template?.filled.orEmpty()
        val byStart = lines(s).mapNotNull { (id, _) -> shotStart(p.plan, filled, id)?.let { at -> Placeholders.find(s, id)?.board?.let { at to it } } }.toMap()
        val takes = c.reels.takes(p).map { t -> byStart[t.startMs]?.let { b -> t.copy(lines = captionsForShot(c, t, b, source)) } ?: t }
        c.reels.update(reelId) { q ->
            q.copy(template = q.template?.copy(captions = source), takes = q.takes.zip(takes).map { (saved, t) -> saved.copy(lines = t.lines) })
        }
    }

    /** The parts of a [totalMs] recording between the rider's taps ([marks], ms from the start). */
    fun pieces(marks: List<Long>, totalMs: Long): List<LongRange> {
        val cuts = listOf(0L) + marks.filter { it in 1 until totalMs }.sorted() + totalMs
        return cuts.zipWithNext { a, b -> a until b }
    }

    /** [from]..[to] (samples) without the quiet at either end, keeping a short lead-in and tail. */
    fun trim(pcm: ShortArray, from: Int, to: Int, rate: Int = VoiceRecorder.RATE, quiet: Int = 700): IntRange {
        val win = rate / 50
        fun loud(at: Int): Boolean {
            var peak = 0
            for (i in at until minOf(at + win, to)) peak = maxOf(peak, kotlin.math.abs(pcm[i].toInt()))
            return peak >= quiet
        }
        var a = from
        while (a < to && !loud(a)) a += win
        var b = to
        while (b > a && !loud(maxOf(a, b - win))) b -= win
        if (b <= a) return IntRange.EMPTY
        val pad = rate * 80 / 1000
        return maxOf(from, a - pad) until minOf(to, b + pad)
    }

    /** Where shot [shotId] starts in the template's [plan] (ms); null if it isn't there. */
    fun shotStart(plan: StudioPlan, filled: Map<String, String>, shotId: String): Long? {
        val i = plan.segments.indexOfFirst { it is ClipSegment && (TemplateBuilder.shotIdOf(it.bit) == shotId || it.bit.momentId == filled[shotId]) }
        return if (i < 0) null else plan.startOf(i)
    }

    /** How long shot [shotId] runs in [plan] (ms). */
    fun shotLength(plan: StudioPlan, filled: Map<String, String>, shotId: String): Long? =
        plan.segments.firstOrNull { it is ClipSegment && (TemplateBuilder.shotIdOf(it.bit) == shotId || it.bit.momentId == filled[shotId]) }?.durMs

    /**
     * Cuts the recording in [pcmFile] at [marks] and places each piece on its shot in template
     * [reelId] ([only]: a retake of that one shot's line). Earlier takes on other shots stay.
     * Returns the shots whose line runs past the shot.
     */
    suspend fun place(c: AppContainer, reelId: String, pcmFile: File, marks: List<Long>, totalMs: Long, only: String? = null): List<String> = withContext(Dispatchers.IO) {
        val p = c.reels.get(reelId) ?: return@withContext emptyList()
        val s = p.script ?: return@withContext emptyList()
        val filled = p.template?.filled.orEmpty()
        val said = lines(s).filter { only == null || it.first == only }
        val raw = pcmFile.readBytes()
        val pcm = ShortArray(raw.size / 2).also { ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val rate = VoiceRecorder.RATE
        val stage = File(c.appContext.cacheDir, "voice-lines").apply { deleteRecursively(); mkdirs() }
        val placed = ArrayList<VoiceTake>()
        val long = ArrayList<String>()
        pieces(marks, totalMs).zip(said).forEachIndexed { k, (range, line) ->
            val (shotId, _) = line
            val start = shotStart(p.plan, filled, shotId) ?: return@forEachIndexed
            val from = (range.first * rate / 1000).toInt().coerceIn(0, pcm.size)
            val to = ((range.last + 1) * rate / 1000).toInt().coerceIn(from, pcm.size)
            val keep = trim(pcm, from, to)
            if (keep.isEmpty()) return@forEachIndexed
            val out = File(stage, "line-$k.pcm")
            val bytes = ByteBuffer.allocate((keep.last - keep.first + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in keep) bytes.putShort(pcm[i])
            out.writeBytes(bytes.array())
            val dur = (keep.last - keep.first + 1) * 1000L / rate
            val take = VoiceTake(start, dur, out)
            val b = Placeholders.find(s, shotId)?.board
            placed += take.copy(lines = b?.let { captionsForShot(c, take, it, p.template?.captions ?: CaptionSource.WRITTEN) }.orEmpty())
            if (dur > (shotLength(p.plan, filled, shotId) ?: Long.MAX_VALUE) + 300) long += shotId
        }
        // Takes already there that aren't on these shots' starts stay, copied aside first (save renames).
        val starts = placed.map { it.startMs }.toSet()
        val kept = p.takes.filter { it.startMs !in starts }.mapIndexedNotNull { i, t ->
            val f = File(c.reels.dir(reelId), t.file).takeIf { it.isFile } ?: return@mapIndexedNotNull null
            VoiceTake(t.startMs, t.durMs, f.copyTo(File(stage, "kept-$i.pcm"), overwrite = true), t.lines)
        }
        c.reels.save(p, null, (kept + placed).sortedBy { it.startMs })
        stage.deleteRecursively()
        long
    }
}

/**
 * The voice-over booth: read every line in one go (Start, Next, Done), or record any one line
 * again on its own. Each line shows its take's length against its shot.
 */
@Composable
fun VoiceLinesScreen(reelId: String, onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val p: ReelProject = reels.firstOrNull { it.id == reelId } ?: return
    val s = p.script ?: return
    val said = remember(s) { VoiceLines.lines(s) }
    val recorder = remember { VoiceRecorder() }
    val scope = rememberCoroutineScope()
    var at by remember { mutableIntStateOf(-1) }
    var startedAt by remember { mutableStateOf(0L) }
    val marks = remember { mutableStateListOf<Long>() }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(at) { if (at >= 0) list.animateScrollToItem(at) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pcm = remember { File(context.cacheDir, "voice-lines.pcm") }
    var single by remember { mutableStateOf<String?>(null) }

    /** Records [id]'s line on its own (tap again to stop), replacing its take. */
    fun recordOne(id: String) {
        if (single != null) { recorder.stop(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        single = id; note = null
        scope.launch {
            val one = File(context.cacheDir, "voice-line-one.pcm")
            val ms = recorder.record(one)
            busy = true
            val long = runCatching { VoiceLines.place(c, reelId, one, emptyList(), ms, only = id) }
            busy = false
            single = null
            note = long.fold({ l -> "Shot ${Placeholders.number(s, id)}: new take placed." + if (l.isNotEmpty()) " It's longer than the shot." else "" }, { "Couldn't place the take (${it.message ?: it.javaClass.simpleName})" })
        }
    }

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        marks.clear(); note = null; at = 0
        startedAt = System.currentTimeMillis()
        scope.launch {
            val ms = recorder.record(pcm)
            busy = true
            val long = runCatching { VoiceLines.place(c, reelId, pcm, marks.toList(), ms) }
            busy = false
            at = -1
            note = long.fold(
                { l -> "Placed ${said.size} line${if (said.size > 1) "s" else ""} on their shots." + if (l.isEmpty()) "" else " Longer than the shot: ${l.joinToString { Placeholders.number(s, it) }}." },
                { "Couldn't place the lines (${it.message ?: it.javaClass.simpleName})" },
            )
        }
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { recorder.stop(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = RtColors.TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text("Voice-over booth", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                Text("All in one go (tap Next between lines), or tap Record on any line to redo just that one.", style = RtType.caption, color = RtColors.TextSecondary)
            }
        }
        // Whose words the captions show; switching captions the takes already there again.
        val captions = p.template?.captions ?: CaptionSource.WRITTEN
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Captions", style = RtType.caption, color = RtColors.TextSecondary)
            CaptionSource.entries.forEach { src ->
                val on = src == captions
                Text(src.label, style = RtType.button, color = if (on) RtColors.Background else RtColors.TextPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) RtColors.TextPrimary else RtColors.SurfaceRaised)
                        .clickable(role = Role.RadioButton, enabled = !busy && at < 0 && !on) {
                            busy = true
                            scope.launch {
                                runCatching { VoiceLines.recaption(c, reelId, src) }
                                    .onFailure { e -> note = "Couldn't change the captions (${e.message ?: e.javaClass.simpleName})" }
                                busy = false
                            }
                        }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        Text(
            if (captions == CaptionSource.WRITTEN) "Captions show your lines exactly as written, timed to your voice." else "Captions show what you actually said, heard from your recording.",
            style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(said) { i, (id, line) ->
                val now = i == at
                val filled = p.template?.filled.orEmpty()
                val shotMs = VoiceLines.shotLength(p.plan, filled, id) ?: 0
                val take = VoiceLines.shotStart(p.plan, filled, id)?.let { st -> p.takes.firstOrNull { it.startMs == st } }
                val mine = single == id
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (now || mine) RtColors.SurfaceRaised else RtColors.Surface).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Shot ${Placeholders.number(s, id)} · " + if (take != null) "take ${String.format(Locale.US, "%.1f", take.durMs / 1000.0)} s of ${String.format(Locale.US, "%.1f", shotMs / 1000.0)} s" else String.format(Locale.US, "%.1f s, not recorded", shotMs / 1000.0),
                            style = RtType.caption, color = when { now || mine -> RtColors.Primary; take != null && take.durMs > shotMs + 300 -> RtColors.Warning; take != null -> RtColors.Ok; else -> RtColors.TextTertiary },
                            modifier = Modifier.weight(1f),
                        )
                        if (at < 0) Text(
                            if (mine) "Stop" else "Record", style = RtType.button, color = if (mine) RtColors.Recording else RtColors.Primary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, enabled = !busy && (single == null || mine)) { recordOne(id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Text(line, style = if (now || mine) RtType.headline else RtType.body, color = if (now || mine || at < 0) RtColors.TextPrimary else RtColors.TextSecondary)
                }
            }
        }
        note?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp)) }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                busy -> Text(if (at < 0 && p.takes.isNotEmpty()) "Working on your captions…" else "Placing your lines…", style = RtType.body, color = RtColors.TextSecondary)
                single != null -> Text("Recording shot ${Placeholders.number(s, single!!)}… tap Stop when done", style = RtType.body, color = RtColors.Recording)
                at < 0 -> PrimaryButton(if (p.takes.isEmpty()) "Read all in one go" else "Read them all again", { start() }, Modifier.weight(1f))
                at < said.size - 1 -> {
                    SecondaryButton("Stop", { recorder.stop() }, Modifier.weight(1f))
                    PrimaryButton("Next line", { marks += System.currentTimeMillis() - startedAt; at++ }, Modifier.weight(1f))
                }
                else -> PrimaryButton("Done", { recorder.stop() }, Modifier.weight(1f))
            }
        }
    }
}
