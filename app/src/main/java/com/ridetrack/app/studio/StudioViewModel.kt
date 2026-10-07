package com.ridetrack.app.studio

import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.moments.MomentSource
import com.ridetrack.app.share.ShareImages
import com.ridetrack.app.transcribe.AppCheckSetup
import com.ridetrack.app.transcribe.ClipAudio
import com.ridetrack.app.transcribe.FirebaseTranscriber
import com.ridetrack.app.ui.format.Format
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.TelemetrySample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

enum class StudioStep { SETUP, WORKING, READY, EDIT, VOICE }

/** One step of making the video, as the rider sees it. */
data class WorkStep(val label: String, val state: Int /* 0 waiting, 1 running, 2 done, 3 skipped */, val detail: String? = null)

data class StudioState(
    val loading: Boolean = true,
    /** Why there's nothing to make (no clips, ride missing); null when ready. */
    val blocked: String? = null,
    val clipCount: Int = 0,
    val talkingCount: Int = 0,
    val posters: List<File> = emptyList(),
    val options: StudioOptions = StudioOptions(bpm = MusicLibrary.forVibe(Vibe.HYPE).firstOrNull()?.bpm),
    val lengths: List<Int> = StudioPlanner.LENGTHS,
    /** The rider's own song (from the phone); null = none or a library track. */
    val musicUri: Uri? = null,
    val musicName: String? = null,
    /** A song from the built-in library; it follows the vibe until the rider picks one. */
    val track: Track? = MusicLibrary.forVibe(Vibe.HYPE).firstOrNull(),
    val trackPicked: Boolean = false,
    val step: StudioStep = StudioStep.SETUP,
    val work: List<WorkStep> = emptyList(),
    val renderProgress: Int? = null,
    /** Shown while Gemini reads clips: the rider can skip captions. */
    val canSkipCaptions: Boolean = false,
    val plan: StudioPlan? = null,
    val title: String = "",
    val series: String = "",
    val episode: Int = 1,
    val hookLine: String = "",
    val story: String? = null,
    val postCaption: String = "",
    val video: File? = null,
    val notes: List<String> = emptyList(),
    val error: String? = null,
    /** Thumbnails by moment id, for Edit. */
    val thumbs: Map<String, File?> = emptyMap(),
    /** The coach's tips for this video; null while they're being written. */
    val tips: List<Tip>? = null,
    val takes: List<VoiceTake> = emptyList(),
    /** ms into the Reel where the take being recorded started; null when not recording. */
    val recordingAt: Long? = null,
    /** Good parts of other rides, for Edit › Add from another ride. */
    val otherBits: List<Bit> = emptyList(),
) {
    /** The small label on the first clip: the series and episode, or the title. */
    val label: String get() = if (series.isBlank()) title else "$series · ep $episode"
}

/** Studio: turns a ride's clips into a Reel (captions, stories and tips by Gemini when it can). */
@OptIn(UnstableApi::class)
class StudioViewModel(private val c: AppContainer, private val rideId: String) : ViewModel() {
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private var clips: List<Moment> = emptyList()
    /** Clips from other rides the rider added (their files are needed to render). */
    private val borrowed = HashMap<String, Moment>()
    private var samples: List<TelemetrySample> = emptyList()
    private var card: RideCard? = null
    private var bits: List<Bit> = emptyList()
    private var captionsDone = false
    private var direction: Direction? = null
    /** Which of Gemini's stories this take tells; past the last = a best-of. */
    private var storyIndex = 0
    private var job: Job? = null
    private var coachJob: Job? = null
    private val recorder = VoiceRecorder()
    @Volatile private var skipCaptions = false

    init {
        viewModelScope.launch {
            val ride = c.rides.get(rideId)
            if (ride == null) { _state.update { it.copy(loading = false, blocked = "Ride not found") }; return@launch }
            samples = withContext(Dispatchers.IO) { c.rides.track(rideId).samples }
            val moments = c.moments.forRide(rideId)
            clips = moments.filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) >= 1_500 }.sortedBy { it.videoStartMillis }
            val talking = clips.count { likelyTalking(it) }
            card = rideCard(ride.name, ride.startTimeMillis, ride.stats.distanceM, ride.durationMillis ?: 0, ride.stats.maxSpeedMps, moments.size)
            bits = withContext(Dispatchers.IO) { buildBits() }
            _state.update {
                it.copy(
                    loading = false,
                    blocked = if (clips.size < 2) "Studio needs at least 2 clips. This ride has ${clips.size}." else null,
                    clipCount = clips.size,
                    talkingCount = talking,
                    posters = clips.sortedByDescending { m -> m.starred }.mapNotNull { m -> m.thumb?.takeIf(File::isFile) }.take(4),
                    title = ride.name,
                    series = c.studio.series,
                    episode = c.studio.nextEpisode,
                    lengths = StudioPlanner.lengthsFor(bits, it.options.vibe),
                    thumbs = clips.associate { m -> m.id to m.thumb },
                )
            }
        }
    }

    // ---- choices ---------------------------------------------------------------------------

    fun setOptions(f: (StudioOptions) -> StudioOptions) = _state.update {
        val o = f(it.options)
        val lengths = StudioPlanner.lengthsFor(bits, o.vibe)
        // A suggested song follows the vibe until the rider picks one themselves.
        val track = if (!it.trackPicked && (it.track == null || it.track.vibe != o.vibe)) MusicLibrary.forVibe(o.vibe).firstOrNull() else it.track
        it.copy(
            options = o.copy(lengthSec = if (o.lengthSec in lengths) o.lengthSec else lengths.last(), bpm = track?.bpm),
            lengths = lengths,
            track = track,
        )
    }

    /** Imports Studio's song pack (a zip of the songs) on builds that don't carry them. */
    fun importSongs(zip: Uri) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { MusicLibrary.importPack(c.appContext, zip) } }
            n.onFailure { e ->
                c.errors.record("Studio music", "Couldn't import the song pack", e)
                _state.update { it.copy(error = "Couldn't read the song pack (${e.message})") }
            }
            n.onSuccess { count ->
                if (count == 0) _state.update { it.copy(error = "That zip has none of Studio's songs. Pick keppo-studio-songs.zip.") }
                setOptions { it }
            }
        }
    }

    /** A library song (cuts follow its tempo); null with [setMusic] null = no music. */
    fun setTrack(t: Track) = _state.update {
        it.copy(track = t, trackPicked = true, musicUri = null, musicName = null, options = it.options.copy(bpm = t.bpm))
    }

    fun setMusic(uri: Uri?) {
        val name = uri?.let { u ->
            runCatching {
                c.appContext.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
            }.getOrNull()?.substringBeforeLast('.') ?: "Your song"
        }
        uri?.let { runCatching { c.appContext.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        _state.update { it.copy(musicUri = uri, musicName = name, track = null, trackPicked = true, options = it.options.copy(bpm = null)) }
    }

    fun setSeries(name: String) {
        c.studio.series = name
        _state.update { it.copy(series = name) }
    }

    fun back() = _state.update {
        it.copy(step = if (it.step == StudioStep.EDIT || it.step == StudioStep.VOICE) StudioStep.READY else StudioStep.SETUP, error = null)
    }

    /** Shared or saved: the next Reel of the series is the next episode. */
    fun posted() {
        val s = _state.value
        if (s.series.isNotBlank()) c.studio.episodeUsed(s.episode)
    }

    // ---- making ----------------------------------------------------------------------------

    /** Captions (Gemini) → stories and picks (Gemini director) → plan → video → tips. */
    fun make() {
        if (job?.isActive == true) return
        val o = _state.value.options
        val gemini = c.transcripts.available
        val needCaptions = o.captions && gemini && !captionsDone
        skipCaptions = false
        _state.update {
            it.copy(
                step = StudioStep.WORKING, error = null, video = null, renderProgress = null, notes = emptyList(), tips = null,
                work = listOfNotNull(
                    WorkStep("Reading what you said", if (needCaptions) 0 else 3, if (!o.captions) "Captions are off" else if (!gemini) "Not available in this build" else if (captionsDone) "Done earlier" else null),
                    WorkStep("Finding the story", 0),
                    WorkStep("Making the video", 0),
                ),
            )
        }
        job = viewModelScope.launch {
            val notes = ArrayList<String>()
            try {
                if (needCaptions) {
                    step(0, 1)
                    _state.update { it.copy(canSkipCaptions = true) }
                    val note = withContext(Dispatchers.IO) { readCaptions() }
                    _state.update { it.copy(canSkipCaptions = false) }
                    note?.let { notes += it }
                    step(0, if (skipCaptions) 3 else 2, if (skipCaptions) "Skipped" else null)
                    bits = withContext(Dispatchers.IO) { buildBits() }
                }
                step(1, 1)
                if (gemini && direction == null) direction = runCatching { direct() }.onFailure { c.errors.record("Studio director", "Gemini couldn't pick the story", it) }.getOrNull()
                replan()
                val s = _state.value
                step(1, 2, listOfNotNull(s.story?.let { "“$it”" }, "${s.plan?.clips?.size} clips · ${Format.clock(s.plan?.totalMs ?: 0)}").joinToString(" · "))
                render(notes)
            } catch (e: CancellationException) {
                _state.update { it.copy(step = StudioStep.SETUP, canSkipCaptions = false) }
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio", "Couldn't make the video", e, _state.value.plan?.let(::describePlan))
                _state.update { it.copy(step = StudioStep.SETUP, canSkipCaptions = false, error = "Couldn't make the video (${e.message ?: e.javaClass.simpleName})") }
            }
        }
    }

    /** Plans from the bits, Gemini's scores and the current story. */
    private fun replan() {
        val d = direction
        val stories = d?.stories.orEmpty()
        val story = stories.getOrNull(storyIndex)
        val scored = bits.map { b -> b.copy(punch = if (b.id == d?.hookId && story == null) 10f else d?.punch?.get(b.id) ?: b.punch) }
        val plan = StudioPlanner.plan(scored, _state.value.options, story?.ids.orEmpty(), d?.endingId)
        if (plan.clips.isEmpty()) error("No usable clips")
        _state.update {
            it.copy(
                plan = plan,
                story = story?.name,
                title = if (it.title == card?.title) d?.title ?: it.title else it.title,
                hookLine = d?.hookLine ?: it.hookLine,
                postCaption = d?.postCaption ?: fallbackCaption(),
                lengths = StudioPlanner.lengthsFor(bits, it.options.vibe),
            )
        }
    }

    /** Another take from the same ride: the next story Gemini found, then best-ofs (no new Gemini calls). */
    fun remix() {
        if (_state.value.plan == null || job?.isActive == true) return
        val n = direction?.stories?.size ?: 0
        storyIndex = if (n == 0) 0 else (storyIndex + 1) % (n + 1)
        setOptions { it.copy(seed = it.seed + 1) }
        make()
    }

    fun skipCaptions() { skipCaptions = true }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(step = StudioStep.SETUP, renderProgress = null) }
    }

    /** One tap on a coach tip. */
    fun act(a: TipAction) {
        when (a) {
            TipAction.TITLE_ON_HOOK -> { setOptions { it.copy(intro = false) }; make() }
            TipAction.SHORTER -> {
                if (direction?.stories?.isNotEmpty() == true && storyIndex >= direction!!.stories.size) storyIndex = 0
                setOptions { it.copy(lengthSec = 15) }
                make()
            }
            TipAction.STORY -> remix()
            TipAction.VOICE_OVER -> voice()
            TipAction.OTHER_RIDES -> edit()
        }
    }

    // ---- edit ------------------------------------------------------------------------------

    fun edit() {
        _state.update { it.copy(step = StudioStep.EDIT) }
        if (_state.value.otherBits.isEmpty()) viewModelScope.launch { loadOtherRides() }
    }
    fun setTitle(t: String) = _state.update { it.copy(title = t) }
    fun setHookLine(t: String) = _state.update { it.copy(hookLine = t) }
    fun nudge(i: Int, start: Long, end: Long) = editPlan { StudioPlanner.nudge(it, i, start, end) }
    fun setText(i: Int, text: String) = editPlan { StudioPlanner.setText(it, i, text) }
    fun move(i: Int, by: Int) = editPlan { StudioPlanner.move(it, i, by) }
    fun remove(i: Int) = editPlan { StudioPlanner.remove(it, i) }
    fun swap(i: Int) = editPlan { StudioPlanner.swap(it, i, bits) }
    fun add(b: Bit) = editPlan { StudioPlanner.add(it, b) }
    private fun editPlan(f: (StudioPlan) -> StudioPlan) = _state.update { s -> s.plan?.let { s.copy(plan = f(it)) } ?: s }

    /** Done editing (or recording): make the video again from the edited plan. */
    fun remake() {
        if (job?.isActive == true) return
        val from = _state.value.step
        _state.update {
            it.copy(step = StudioStep.WORKING, video = null, error = null, tips = null, work = listOf(WorkStep("Your changes", 2), WorkStep("Making the video", 0)))
        }
        job = viewModelScope.launch {
            try {
                render(ArrayList())
            } catch (e: CancellationException) {
                _state.update { it.copy(step = from) }
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio", "Couldn't make the video again", e, _state.value.plan?.let(::describePlan))
                _state.update { it.copy(step = from, error = "Couldn't make the video (${e.message ?: e.javaClass.simpleName})") }
            }
        }
    }

    /** The best talking parts of other rides (by their saved captions), then a few riding shots. */
    private suspend fun loadOtherRides() {
        val list = withContext(Dispatchers.IO) {
            val others = c.moments.all().filter { it.rideId != rideId && it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) >= 1_500 }
            val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
            val talking = ArrayList<Pair<Bit, Moment>>()
            val riding = ArrayList<Pair<Bit, Moment>>()
            others.sortedByDescending { it.timeMillis }.forEach { m ->
                val lines = StudioText.load(m.file)
                val kmh = (m.speedMps ?: 0.0) * 3.6
                val label = day.format(Instant.ofEpochMilli(m.timeMillis).atZone(ZoneId.systemDefault()))
                val dur = m.durationMillis ?: 0
                val bs = StudioPlanner.bitsOf(m.id, m.videoStartMillis, dur, lines.orEmpty(), { kmh }, (m.timeMillis - m.videoStartMillis).coerceIn(0, dur))
                    .map { it.copy(fromRide = label) }
                bs.forEach { b -> if (b.talking) talking += b to m else if (riding.size < 6 && kmh > 20) riding += b to m }
            }
            (talking.sortedByDescending { it.first.punch }.take(14) + riding)
        }
        list.forEach { (b, m) -> borrowed[b.momentId] = m }
        _state.update { it.copy(otherBits = list.map { it.first }, thumbs = it.thumbs + list.associate { (b, m) -> b.momentId to m.thumb }) }
    }

    // ---- voice-over ------------------------------------------------------------------------

    fun voice() = _state.update { it.copy(step = StudioStep.VOICE) }

    /** Starts recording a take at [atMs] into the Reel (the screen plays the Reel muted meanwhile). */
    fun startTake(atMs: Long) {
        if (_state.value.recordingAt != null) return
        val dir = File(c.appContext.filesDir, "studio").apply { mkdirs() }
        val file = File(dir, "voice-$rideId-${System.currentTimeMillis()}.pcm")
        _state.update { it.copy(recordingAt = atMs) }
        viewModelScope.launch {
            val ms = runCatching { recorder.record(file) }.getOrElse { e ->
                _state.update { it.copy(recordingAt = null, error = "Couldn't record (${e.message})") }
                return@launch
            }
            val total = _state.value.plan?.totalMs ?: 0
            val take = VoiceTake(atMs, ms.coerceAtMost((total - atMs).coerceAtLeast(0)), file)
            _state.update { it.copy(recordingAt = null, takes = it.takes + take) }
        }
    }

    fun stopTake() = recorder.stop()

    fun deleteTake(t: VoiceTake) {
        t.file.delete()
        _state.update { it.copy(takes = it.takes - t) }
    }

    /** Captions for takes that don't have them yet (Gemini), then the video again. */
    fun finishVoice() {
        if (job?.isActive == true) return
        _state.update {
            it.copy(step = StudioStep.WORKING, video = null, error = null, tips = null, work = listOf(WorkStep("Reading your voice-over", 0), WorkStep("Making the video", 0)))
        }
        job = viewModelScope.launch {
            try {
                step(0, 1)
                if (c.transcripts.available && _state.value.options.captions) {
                    val g = gemini()
                    val updated = _state.value.takes.map { t ->
                        if (t.lines.isNotEmpty() || t.durMs < 500) return@map t
                        val wav = File(c.appContext.cacheDir, "take.wav")
                        try {
                            t.copy(lines = runCatching { withContext(Dispatchers.IO) { g.captions(VoiceRecorder.takeWav(t, wav), "audio/wav") } }
                                .onFailure { c.errors.record("Studio voice-over", "Couldn't caption a take", it) }.getOrDefault(emptyList()))
                        } finally {
                            wav.delete()
                        }
                    }
                    _state.update { it.copy(takes = updated) }
                }
                step(0, 2)
                render(ArrayList())
            } catch (e: CancellationException) {
                _state.update { it.copy(step = StudioStep.VOICE) }
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio", "Couldn't make the video with the voice-over", e, _state.value.plan?.let(::describePlan))
                _state.update { it.copy(step = StudioStep.VOICE, error = "Couldn't make the video (${e.message ?: e.javaClass.simpleName})") }
            }
        }
    }

    // ---- rendering ---------------------------------------------------------------------------

    private suspend fun render(notes: List<String>) {
        val s = _state.value
        val plan = s.plan ?: return
        val idx = s.work.lastIndex
        step(idx, 1)
        val baseCard = card ?: return
        val voice = s.takes.takeIf { it.isNotEmpty() }?.let { takes ->
            withContext(Dispatchers.IO) { VoiceRecorder.mix(takes, plan.totalMs, File(c.appContext.cacheDir, "studio-voice.wav")) }
        }
        val input = RenderInput(
            plan = plan,
            files = (clips + borrowed.values).associate { it.id to it.file },
            card = baseCard.copy(title = s.title.ifBlank { baseCard.title }),
            options = s.options,
            speedAt = ::speedAt,
            clockAt = { Format.timeOfDay(it).lowercase(Locale.getDefault()) },
            music = s.track?.uri ?: s.musicUri,
            opener = StudioArt.Opener(label = if (s.options.intro) s.label.takeIf { s.series.isNotBlank() } else s.label, hookLine = s.hookLine.ifBlank { null }),
            voice = voice,
            voiceLines = s.takes.flatMap { t -> t.lines.map { it.copy(startMs = it.startMs + t.startMs, endMs = it.endMs + t.startMs) } },
        )
        val out = File(ShareImages.sharesDir(c.appContext), "keppo-reel-${System.currentTimeMillis()}.mp4")
        val result = StudioRenderer(c.appContext).render(input, out) { p -> _state.update { it.copy(renderProgress = p) } }
        val done = result.getOrThrow()
        // Attempts that failed before one worked are worth knowing about too.
        if (done.failures.isNotEmpty()) {
            val e = StudioRenderer.ExportFailed("Made on attempt ${done.failures.size + 1}").also { x -> done.failures.forEach { x.addSuppressed(it) } }
            c.errors.record("Studio export", "Needed a fallback: ${done.note ?: "no note"}", e, describePlan(plan))
        }
        step(idx, 2)
        _state.update { it.copy(step = StudioStep.READY, video = done.file, renderProgress = null, notes = notes + listOfNotNull(done.note)) }
        coach()
    }

    /** Tips for this video: Gemini's when it can, the app's own otherwise. "Film next time" ones go on Home. */
    private fun coach() {
        coachJob?.cancel()
        coachJob = viewModelScope.launch {
            val s = _state.value
            val plan = s.plan ?: return@launch
            val d = direction
            val story = d?.stories?.getOrNull(storyIndex)
            val voiceOver = s.takes.isNotEmpty()
            val tips = (if (c.transcripts.available) {
                runCatching { gemini().coach(StudioCoach.summary(plan, s.options, bits, d, story, voiceOver, s.musicUri != null || s.track != null)) }
                    .onFailure { c.errors.record("Studio coach", "Gemini couldn't write tips", it) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }).ifEmpty { StudioCoach.localTips(plan, s.options, bits, d, voiceOver) }
            c.studio.setShots(tips.filter { it.nextRide }.map { it.text })
            _state.update { it.copy(tips = tips) }
        }
    }

    /** The plan in a few lines, so an error report shows what was being made. */
    private fun describePlan(p: StudioPlan): String = buildString {
        val s = _state.value
        appendLine("Plan: ${p.vibe} · ${p.totalMs} ms · ${p.segments.size} segments · length ${s.options.lengthSec}s · music ${s.track?.id ?: s.musicUri?.let { "own" } ?: "none"} · voice takes ${s.takes.size}")
        p.segments.forEachIndexed { i, seg ->
            appendLine(
                "  $i " + when (seg) {
                    is ClipSegment -> "clip ${seg.bit.momentId} in=${seg.inMs} dur=${seg.durMs} clipDur=${seg.bit.clipDurationMs}${if (seg.tail) " tail" else ""}${if (seg.bit.fromRide != null) " other-ride" else ""} file=${(clips + borrowed.values).firstOrNull { it.id == seg.bit.momentId }?.file?.let { f -> "${f.name} ${f.length() / 1024}KB" }}"
                    is TitleSegment -> "title dur=${seg.durMs}"
                    is StatsSegment -> "stats dur=${seg.durMs}"
                },
            )
        }
    }

    private fun step(i: Int, state: Int, detail: String? = null) = _state.update { s ->
        s.copy(work = s.work.mapIndexed { k, w -> if (k == i) w.copy(state = state, detail = detail ?: w.detail) else w })
    }

    // ---- captions and the director ---------------------------------------------------------

    /** Asks Gemini for timed lines for the clips that likely have talking; returns a note if some were missed. */
    private suspend fun readCaptions(): String? {
        val todo = clips.filter { likelyTalking(it) && StudioText.load(it.file) == null }
            .sortedByDescending { (if (RideEventType.VOICE in it.types) 2 else 0) + (it.transcript?.length ?: 0).coerceAtMost(200) / 100 }
            .take(MAX_CAPTION_CLIPS)
        val g = gemini()
        var missed = 0
        var note: String? = null
        for ((i, m) in todo.withIndex()) {
            if (skipCaptions) break
            step(0, 1, "${i + 1} of ${todo.size}")
            val audio = File(c.appContext.cacheDir, "studio-${m.id}.m4a")
            try {
                if (!ClipAudio.extract(m.file, audio) || audio.length() > MAX_AUDIO_BYTES) { StudioText.save(m.file, emptyList()); continue }
                val lines = try {
                    g.captions(audio)
                } catch (e: FirebaseTranscriber.Busy) {
                    delay(20_000)
                    g.captions(audio)
                }
                StudioText.save(m.file, lines)
                // The words also become the clip's transcript (shown in the ride and the clip viewer).
                if (m.transcript == null) c.moments.setTranscript(m.id, lines.joinToString(" ") { it.text })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio captions", "Couldn't read clip ${m.file.name}", e)
                missed++
                note = when {
                    AppCheckSetup.isRejected(e) -> "Captions: Firebase didn't accept this phone (App Check). Add the debug token from Profile › Moments in Firebase."
                    e is FirebaseTranscriber.Busy -> "Captions: ${FirebaseTranscriber.busyMessage(e)} Some clips have no captions; Remix later to try again."
                    else -> "Captions: couldn't read $missed clip${if (missed > 1) "s" else ""} (${e.message?.take(80)})."
                }
                if (AppCheckSetup.isRejected(e)) break
            } finally {
                audio.delete()
            }
            if (i < todo.lastIndex) delay(BETWEEN_MS)
        }
        captionsDone = !skipCaptions && missed == 0
        return note
    }

    private suspend fun direct(): Direction? {
        val cd = card ?: return null
        val talking = bits.filter { it.talking }
        if (talking.isEmpty()) return null
        val use = (talking.sortedByDescending { it.punch }.take(24) + bits.filter { !it.talking }.take(6)).sortedBy { it.atMillis }
        return gemini().direct(cd.title, cd.subtitle, use)
    }

    private fun gemini() = StudioGemini(preferred = { c.transcripts.model }, onWorking = { c.transcripts.model = it })

    /** Voice-triggered, filmed on purpose, or already known to have words. */
    private fun likelyTalking(m: Moment) =
        RideEventType.VOICE in m.types || m.source == MomentSource.MANUAL || !m.transcript.isNullOrBlank()

    private fun buildBits(): List<Bit> = clips.flatMap { m ->
        val dur = m.durationMillis ?: 0
        val lines = StudioText.load(m.file).orEmpty()
        val focus = (m.timeMillis - m.videoStartMillis).coerceIn(0, dur)
        StudioPlanner.bitsOf(m.id, m.videoStartMillis, dur, lines, { t -> speedAt(t).toDouble() }, focus)
    }

    // ---- ride numbers ----------------------------------------------------------------------

    private fun speedAt(wall: Long): Int {
        if (samples.isEmpty()) return 0
        var lo = 0
        var hi = samples.lastIndex
        while (lo < hi) { val mid = (lo + hi) / 2; if (samples[mid].timeMillis < wall) lo = mid + 1 else hi = mid }
        val s = samples[lo].takeIf { kotlin.math.abs(it.timeMillis - wall) < 3_000 } ?: return 0
        return ((s.speedMps ?: 0.0) * 3.6).roundToInt()
    }

    private fun rideCard(name: String, start: Long, distanceM: Double, durMs: Long, topMps: Double?, moments: Int): RideCard {
        val zone = ZoneId.systemDefault()
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(start).atZone(zone))
        val km = String.format(Locale.US, "%.1f", distanceM / 1000)
        val min = (durMs / 60_000).coerceAtLeast(1)
        val pts = samples.filter { it.latitude != null && it.longitude != null }
        val step = (pts.size / 400 + 1).coerceAtLeast(1)
        val route = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }
        val top = (topMps ?: route.maxOfOrNull { it.speedMps ?: 0.0 } ?: 1.0).coerceAtLeast(1.0)
        val norm = if (route.size >= 2) {
            val lat0 = route.map { it.latitude!! }.average()
            val k = cos(Math.toRadians(lat0))
            val xs = route.map { it.longitude!! * k }
            val ys = route.map { -it.latitude!! }
            val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min()).coerceAtLeast(1e-9) / 2
            val cx = (xs.max() + xs.min()) / 2
            val cy = (ys.max() + ys.min()) / 2
            route.indices.map { i -> Triple(((xs[i] - cx) / span).toFloat(), ((ys[i] - cy) / span).toFloat(), ((route[i].speedMps ?: 0.0) / top).toFloat()) }
        } else {
            emptyList()
        }
        return RideCard(
            title = name,
            subtitle = "$day · $km km · $min min",
            route = norm,
            stats = listOf(km to "km", "${((topMps ?: 0.0) * 3.6).roundToInt()}" to "top km/h", "$min" to "minutes", "$moments" to "moments"),
        )
    }

    private fun fallbackCaption(): String {
        val cd = card ?: return ""
        return "${cd.title} 🏍️\n${cd.subtitle}\n\n#motovlog #bikelife #ridersofindia #keppomoto"
    }

    override fun onCleared() {
        job?.cancel()
        coachJob?.cancel()
        recorder.stop()
    }

    private companion object {
        const val MAX_CAPTION_CLIPS = 12
        const val BETWEEN_MS = 4_000L
        const val MAX_AUDIO_BYTES = 14L * 1024 * 1024
    }
}
