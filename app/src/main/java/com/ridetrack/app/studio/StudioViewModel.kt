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
import com.ridetrack.app.transcribe.FirebaseTranscriber
import com.ridetrack.app.ui.format.Format
import com.ridetrack.telemetry.model.RideEventType
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

/** Studio for gallery videos only, with no ride (the rider id Studio is opened with). */
const val PHONE_STUDIO = "phone"

/** One clip Studio can pick from, for the strip: a moment, or a video from the phone. */
data class StudioSource(
    val id: String,
    val thumb: File?,
    val durationMs: Long,
    val talking: Boolean,
    /** "Phone", "Road" (filmed with the back camera); null for an ordinary moment. */
    val label: String?,
    val atMillis: Long,
    val uri: Uri,
)

enum class StudioStep { SETUP, WORKING, READY, EDIT, VOICE, COVER, SCRIPT }

/** One step of making the video, as the rider sees it. */
data class WorkStep(val label: String, val state: Int /* 0 waiting, 1 running, 2 done, 3 skipped */, val detail: String? = null)

data class StudioState(
    val loading: Boolean = true,
    /** Why there's nothing to make (no clips, ride missing); null when ready. */
    val blocked: String? = null,
    val clipCount: Int = 0,
    val talkingCount: Int = 0,
    val posters: List<File> = emptyList(),
    val options: StudioOptions = StudioOptions(),
    val lengths: List<Int> = StudioPlanner.LENGTHS,
    /** The rider's own song (from the phone); null = no music (the default: songs are added in Instagram). */
    val musicUri: Uri? = null,
    val musicName: String? = null,
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
    /** The saved Reel being shown or changed; null until the first video is made. */
    val reelId: String? = null,
    /** Frames to pick the cover from: ms into the Reel → a small picture. */
    val coverFrames: List<Pair<Long, File>> = emptyList(),
    /** Bumped each time the cover is drawn again, so pictures of it refresh. */
    val coverVersion: Int = 0,
    val coverBusy: Boolean = false,
    /** A short message after Journal or cover actions. */
    val toast: String? = null,
    /** What Gemini suggests making from this ride (Reels, Shorts, Stories, long videos). */
    val pieces: List<ContentPiece> = emptyList(),
    /** Suggesting content: what's happening ("Reading what you said · 3–8 of 12"); null when not. */
    val planning: String? = null,
    /** The suggestion being made; null for a Reel of your own choices. */
    val piece: ContentPiece? = null,
    /** Gemini suggested more than are shown ("Gemini suggested 5 · 2 couldn't be fitted to your clips"); null when all are. */
    val suggestNote: String? = null,
    /** The suggestion open in the Script view before it's made (its key); null otherwise. */
    val scriptPiece: String? = null,
    /** Every clip Studio can pick from (moments and phone videos), in filming order. */
    val sources: List<StudioSource> = emptyList(),
    /** Clips the rider left out (long-press in the strip). */
    val excluded: Set<String> = emptySet(),
    /** Enough to make a Reel (2+ usable parts). */
    val canMake: Boolean = false,
    /** Gallery videos only, no ride. */
    val phoneOnly: Boolean = false,
    /** How the posted Reel did, as the rider typed it in; null until they do. */
    val views: Int? = null,
    val likes: Int? = null,
    /** YouTube title and description written by Gemini; empty until asked. */
    val youtubeTitle: String = "",
    val youtubeDescription: String = "",
    /** Gemini's read of the first 3 seconds; null until checked. */
    val hook: HookCheck? = null,
    /** Gemini is writing post text, translating or checking the hook ("Writing for YouTube…"); null when not. */
    val posting: String? = null,
    /** Gemini can be used in this build. */
    val gemini: Boolean = false,
    /** The script the Reel was made from. */
    val script: Script? = null,
    /** What the ride has to work with, in words ("40 s of you talking…"). */
    val content: String? = null,
    /** The script being changed in the Script view; null outside it. */
    val draft: Script? = null,
    /** "Tell Studio what to change", in the rider's words. */
    val note: String = "",
    /** The clips, as the Script view shows and offers them. */
    val footage: List<Footage> = emptyList(),
    /** Earlier versions of this Reel: number and when it was made. */
    val versions: List<Pair<Int, Long>> = emptyList(),
    /** The edit on the timeline (null outside the editor), and what's selected on it. */
    val timeline: StudioPlan? = null,
    val pick: TimelinePick? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** What each undo step did, oldest first (the History list). */
    val steps: List<String> = emptyList(),
    /** A clip's settings copied to paste onto others; null when nothing is copied. */
    val copied: TrackEdits.ClipSettings? = null,
    /** Exporting: progress 0..100; null when not. */
    val exporting: Int? = null,
    /** Clips that have the engine mic's sound (two-mic recording): moment id → its file. */
    val engineFiles: Map<String, String> = emptyMap(),
    /** Bumped when the timeline changes for good (not mid-drag), so the preview reloads. */
    val timelineVersion: Int = 0,
    /** Why Gemini can't help right now (no internet, its limit), shown with Try again; null when fine. */
    val geminiIssue: String? = null,
) {
    /** The small label on the first clip: the series and episode, or the title. */
    val label: String get() = if (series.isBlank()) title else "$series · ep $episode"
}

/** Studio: turns a ride's clips into a Reel (captions, stories and tips by Gemini when it can). */
@OptIn(UnstableApi::class)
class StudioViewModel(private val c: AppContainer, val rideId: String, private val openReelId: String? = null) : ViewModel() {
    private val _state = MutableStateFlow(
        StudioState(
            gemini = c.transcripts.available,
            // Studio settings are the same for every ride.
            options = c.studio.options,
            musicUri = c.studio.music?.first?.let(Uri::parse),
            musicName = c.studio.music?.second,
        ),
    )
    val state: StateFlow<StudioState> = _state.asStateFlow()

    /** The ride's clips, captions and suggestions (shared with the work that runs after a ride). */
    private val engine = StudioEngine(c, rideId)
    private val clips get() = engine.clips
    private val phone get() = engine.phone
    private val phoneOnly = engine.phoneOnly
    private val borrowed get() = engine.borrowed
    private val samples get() = engine.samples
    private val card get() = engine.card
    private val bits get() = engine.bits
    private var job: Job? = null
    private var coachJob: Job? = null
    private val recorder = VoiceRecorder()
    @Volatile private var skipCaptions = false
    /** The script being made; Remix asks for one different from it. */
    private var remixFrom: Script? = null

    /** A script to make next (a suggestion), instead of asking for a new one. */
    private var pendingScript: Script? = null

    init {
        followMaker()
        viewModelScope.launch {
            engine.load()?.let { why -> _state.update { it.copy(loading = false, blocked = why) }; return@launch }
            if (phoneOnly) {
                _state.update {
                    it.copy(loading = false, phoneOnly = true, title = "My videos", series = c.studio.series, episode = c.studio.nextEpisode, options = it.options.copy(outro = false, map = false))
                }
                c.studio.takePending().takeIf { it.isNotEmpty() }?.let { addPhoneVideos(it).join() }
                if (openReelId == null && phone.isNotEmpty()) loadPlan()
                openReelId?.let { id -> c.reels.get(id)?.let { open(it) } }
                return@launch
            }
            val talking = clips.count { likelyTalking(it) }
            _state.update {
                it.copy(
                    loading = false,
                    clipCount = clips.size,
                    talkingCount = talking,
                    posters = clips.sortedByDescending { m -> m.starred }.mapNotNull { m -> m.thumb?.takeIf(File::isFile) }.take(4),
                    title = card?.title.orEmpty(),
                    series = c.studio.series,
                    episode = c.studio.nextEpisode,
                    lengths = StudioPlanner.lengthsFor(bits, it.options.vibe),
                    thumbs = clips.associate { m -> m.id to m.thumb },
                )
            }
            // Road shots filmed with the back camera tick off the coach's "film the road" shot.
            if (clips.any { it.camera == "back" }) c.studio.tickRoadShots()
            c.studio.takePending().takeIf { it.isNotEmpty() }?.let { addPhoneVideos(it).join() }
            refreshSources()
            if (openReelId == null) loadPlan()
            openReelId?.let { id -> c.reels.get(id)?.let { open(it) } }
        }
    }

    // ---- clips: moments and phone videos -----------------------------------------------------

    private fun refreshSources() {
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        val list = clips.map { m ->
            // The voice mark only where words were actually found (not just a clip started by your voice).
            StudioSource(m.id, m.thumb, m.durationMillis ?: 0, StudioText.load(m.file).orEmpty().isNotEmpty() || !m.transcript.isNullOrBlank(), if (m.camera == "back") "Road" else null, m.videoStartMillis, Uri.fromFile(m.file))
        } + phone.values.map { p ->
            val lines = StudioText.load(PhoneVideos.captionKey(c.appContext, p.id))
            val label = if (!phoneOnly && card != null && (samples.isEmpty() || p.startMillis !in samples.first().timeMillis..samples.last().timeMillis)) {
                "Phone · " + day.format(Instant.ofEpochMilli(p.startMillis).atZone(ZoneId.systemDefault()))
            } else {
                "Phone"
            }
            StudioSource(p.id, p.thumb, p.durationMs, lines.orEmpty().isNotEmpty(), label, p.startMillis, p.uri)
        }
        _state.update { s ->
            s.copy(
                sources = list.sortedBy { it.atMillis },
                clipCount = list.size,
                talkingCount = list.count { it.talking },
                canMake = bits.size >= 2,
                thumbs = s.thumbs + phone.values.associate { it.id to it.thumb },
                lengths = StudioPlanner.lengthsFor(bits, s.options.vibe),
                posters = s.posters.ifEmpty { phone.values.mapNotNull { it.thumb }.take(4) },
            )
        }
    }

    /**
     * Adds videos from the phone's gallery (they aren't copied). In Edit, each one's best part also
     * goes into the Reel. Returns the job, so callers can wait for it.
     */
    fun addPhoneVideos(uris: List<Uri>): Job = viewModelScope.launch {
        val added = withContext(Dispatchers.IO) {
            uris.mapIndexedNotNull { i, u ->
                PhoneVideos.read(c.appContext, u)?.let { p ->
                    // Undated videos go after the ride (or now), apart from each other.
                    if (p.startMillis > 0) p else p.copy(startMillis = (samples.lastOrNull()?.timeMillis ?: System.currentTimeMillis()) + (i + 1) * 600_000L)
                }
            }
        }
        val missed = uris.size - added.size
        added.forEach { phone[it.id] = it }
        engine.refreshBits()
        refreshSources()
        if (_state.value.step == StudioStep.EDIT) {
            added.forEach { p -> bits.filter { it.momentId == p.id }.maxByOrNull { it.punch }?.let { b -> insert(b) } }
            if (added.isNotEmpty()) say("Added. Their words get captions when you Remix.")
        }
        if (missed > 0) say("$missed couldn't be opened")
    }

    /** Leaves a clip out of what Studio picks from (or puts it back). */
    fun toggleExclude(id: String) {
        _state.update { it.copy(excluded = if (id in it.excluded) it.excluded - id else it.excluded + id) }
        engine.excluded = _state.value.excluded
        viewModelScope.launch {
            engine.refreshBits()
            refreshSources()
        }
    }

    // ---- suggested content ---------------------------------------------------------------------

    /** Loads the saved suggestions, or asks for them the first time the ride's Studio opens. */
    private suspend fun loadPlan() {
        val saved = engine.loadPlan()
        if (saved != null) showPieces(saved) else if (_state.value.canMake) suggest()
    }

    private suspend fun showPieces(scripts: List<Script>) {
        val o = _state.value.options
        val (list, text) = withContext(Dispatchers.Default) { engine.pieces(scripts, o) to ScriptWriter.summary(footage()).text }
        _state.update { it.copy(pieces = list, content = text) }
    }

    /**
     * Gemini reads the ride (captions once) and suggests what to make from it, as scripts
     * (one request). Without Gemini, the app suggests a Reel and a Short itself.
     */
    fun suggest(all: Boolean = false) {
        if (job?.isActive == true || _state.value.planning != null) return
        retryJob?.cancel()
        job = viewModelScope.launch {
            try {
                val gemini = c.transcripts.available
                _state.update { it.copy(geminiIssue = null) }
                if (gemini && _state.value.options.captions && withContext(Dispatchers.IO) { engine.unreadCount() } > 0) {
                    _state.update { it.copy(planning = "Reading what you said", work = listOf(WorkStep("Reading what you said", 1))) }
                    val read = engine.readCaptions(onProgress = { d -> _state.update { it.copy(planning = "Reading what you said · $d") } })
                    refreshSources()
                    // Couldn't reach Gemini: suggesting would fail the same way. Wait for the internet.
                    read.unreachable?.let { e -> waitForGemini(e); return@launch }
                }
                if (gemini) _state.update { it.copy(planning = if (all) "Gemini is finding every story in this ride" else "Gemini is suggesting what to make", work = emptyList()) }
                val scripts = when (val r = engine.suggest(_state.value.title, all)) {
                    is Suggested.Scripts -> r.scripts
                    is Suggested.Unreachable -> { waitForGemini(r.error); return@launch }
                    Suggested.Busy -> {
                        _state.update { it.copy(geminiIssue = "Gemini's free limit is used up, so it can't suggest now.") }
                        return@launch
                    }
                }
                showPieces(scripts)
                // Say when some of Gemini's suggestions couldn't be used, and keep why for Studio details.
                val asked = ScriptWriter.piecesAsked(engine.lastAnswer)
                val shown = _state.value.pieces.size
                if (asked > shown) {
                    val unread = asked - scripts.size
                    val unfit = scripts.size - shown
                    val note = "Gemini suggested $asked · " + listOfNotNull(
                        unread.takeIf { it > 0 }?.let { "$it couldn't be read" },
                        unfit.takeIf { it > 0 }?.let { "$it didn't fit your clips" },
                    ).joinToString(", ")
                    _state.update { it.copy(suggestNote = note) }
                    c.errors.warn("Studio content plan", note, extra = engine.planDetails()?.take(8_000))
                } else {
                    _state.update { it.copy(suggestNote = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio content plan", "Couldn't suggest content", e)
            } finally {
                _state.update { it.copy(planning = null, work = emptyList()) }
            }
        }
    }

    private suspend fun savePlan(scripts: List<Script>, why: String? = null) = engine.savePlan(scripts, why = why)

    private var retryJob: Job? = null
    private var retries = 0

    /**
     * Gemini can't be reached: say why, and try again by itself when the connection comes back
     * (or in 10 minutes when the phone is online but something blocks Google's server). Three tries.
     */
    private fun waitForGemini(e: Throwable) {
        _state.update { it.copy(geminiIssue = com.ridetrack.app.transcribe.GeminiNet.message(e)) }
        if (retries >= 3) return
        retries++
        val ctx = c.appContext
        val wasOnline = com.ridetrack.app.transcribe.GeminiNet.online(ctx)
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            if (wasOnline) delay(10 * 60_000L) else { com.ridetrack.app.transcribe.GeminiNet.awaitOnline(ctx); delay(3_000) }
            if (_state.value.geminiIssue != null) suggest()
        }
    }

    /** Try again now (the rider tapped it). */
    fun retryGemini() {
        retries = 0
        suggest()
    }

    /** Every story, reaction, line and fast stretch Gemini can find in the ride (one more request). */
    fun suggestAll() {
        retries = 0
        suggest(all = true)
    }

    /** Suggestions the app makes itself, when the rider doesn't want to wait for Gemini. */
    fun suggestWithoutGemini() {
        viewModelScope.launch {
            val fs = withContext(Dispatchers.Default) { footage() }
            val scripts = localPieces(fs, "Made by the app, without Gemini")
            savePlan(scripts, "Asked for without Gemini")
            showPieces(scripts)
            _state.update { it.copy(geminiIssue = null) }
        }
    }

    /**
     * The rider asks for a piece in their words ("a 15 s funny one about the water"): Gemini
     * writes it (one request), it joins the suggestions and is made straight away.
     */
    fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty() || job?.isActive == true) return
        val cd = card ?: return
        viewModelScope.launch {
            _state.update { it.copy(planning = "Gemini is writing \u201c${t.take(40)}\u201d", geminiIssue = null) }
            val fs = withContext(Dispatchers.Default) { footage() }
            val sc = try {
                gemini().scripts(ScriptWriter.askPrompt(cd.title, cd.subtitle, fs, style(), t, c.styles.forSuggestions()), fs) { raw ->
                    c.errors.record("Studio ask", "Gemini's answer couldn't be read", null, raw.take(4_000))
                }.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio ask", "Gemini couldn't write what you asked for", e)
                _state.update {
                    it.copy(geminiIssue = when {
                        com.ridetrack.app.transcribe.GeminiNet.isUnreachable(e) -> com.ridetrack.app.transcribe.GeminiNet.message(e)
                        e is FirebaseTranscriber.Busy -> "Gemini's free limit is used up, so it can't write that now."
                        else -> "Gemini couldn't write that (${e.message?.take(60)})."
                    })
                }
                null
            } finally {
                _state.update { it.copy(planning = null) }
            }
            if (sc == null) return@launch
            c.style.record("(asked for)", sc.describe(fs), t)
            val scripts = listOf(sc) + _state.value.pieces.map { it.script }
            savePlan(scripts)
            showPieces(scripts)
            _state.value.pieces.firstOrNull()?.let { makePiece(it.key) }
        }
    }

    /** The app's own suggestions: a Reel as long as the footage fills well, and a 15 s Short. */
    private fun localPieces(fs: List<Footage>, reason: String): List<Script> = engine.localPieces(fs, reason, _state.value.title)

    /** The Reel being made by [ReelMaker] for this screen; null when none. */
    @Volatile private var makingId: String? = null

    /**
     * Makes a suggestion as a new Reel (whatever is on screen stays saved). It's made by the
     * background maker, so leaving the app doesn't stop it; this screen follows it and opens it.
     */
    fun makePiece(key: String) {
        if (job?.isActive == true || makingId != null) return
        val piece = _state.value.pieces.firstOrNull { it.key == key } ?: return
        val vibe = piece.script.vibe ?: _state.value.options.vibe
        _state.update {
            it.copy(
                piece = piece, reelId = null, takes = emptyList(), tips = null, coverFrames = emptyList(), title = card?.title ?: it.title,
                options = it.options.copy(vibe = vibe),
            )
        }
        val mj = engine.job(piece, _state.value) ?: return
        makingId = mj.project.id
        _state.update {
            it.copy(
                step = StudioStep.WORKING, error = null, video = null, renderProgress = null, notes = emptyList(),
                plan = piece.plan, script = piece.script,
                work = listOf(
                    WorkStep("Fitting the script to your clips", 2, "${piece.plan.clips.size} clips · ${Format.clock(piece.plan.totalMs)}"),
                    WorkStep("Making the video", 1, "Keeps going if you leave the app"),
                ),
            )
        }
        c.reelMaker.enqueue(listOf(mj))
    }

    /** Follows the maker for the Reel this screen asked for: progress, then open it (or say why not). */
    private fun followMaker() {
        viewModelScope.launch {
            c.reelMaker.state.collect { m ->
                val id = makingId ?: return@collect
                if (m.currentId == id) {
                    _state.update { it.copy(renderProgress = m.progress) }
                } else {
                    c.reelMaker.waitingBefore(id)?.let { n ->
                        step(_state.value.work.lastIndex, 1, if (n == 0) "Next up" else "After $n other${if (n > 1) "s" else ""} being made")
                    }
                }
            }
        }
        viewModelScope.launch {
            c.reelMaker.events.collect { e ->
                if (e.id != makingId) return@collect
                makingId = null
                when (e) {
                    is MakerEvent.Made -> {
                        open(e.project)
                        _state.update { it.copy(renderProgress = null, notes = listOfNotNull(e.project.note)) }
                        coach()
                    }
                    is MakerEvent.Failed -> _state.update { it.copy(step = StudioStep.SETUP, renderProgress = null, error = "Couldn't make the video (${e.message})") }
                }
            }
        }
    }

    /** Makes every suggestion not made yet, in the background (a notification shows progress). */
    fun makeAll() {
        val made = engine.madeIdeas()
        val s = _state.value
        val jobs = s.pieces.filter { it.script.title !in made }.mapNotNull { engine.job(it, s) }
        c.reelMaker.enqueue(jobs)
        say(if (jobs.isEmpty()) "All suggestions are made already" else "Making ${jobs.size} in the background. They'll be in Your Reels.")
    }

    fun cancelMakeAll() = c.reelMaker.cancelAll()


    // ---- saved Reels -----------------------------------------------------------------------

    /** Shows a saved Reel, with everything it was made with, ready to change and make again. */
    private suspend fun open(p: ReelProject) {
        // Clips from other rides need their files to be made again.
        val missing = p.plan.clips.filter { it.bit.source == null }.map { it.bit.momentId }.filter { id -> clips.none { it.id == id } }.toSet()
        if (missing.isNotEmpty()) c.moments.all().filter { it.id in missing }.forEach { borrowed[it.id] = it }
        val gallery = p.plan.clips.mapNotNull { it.bit.source }.distinct().filter { u -> phone.values.none { it.uri.toString() == u } }
        if (gallery.isNotEmpty()) {
            withContext(Dispatchers.IO) { gallery.mapNotNull { PhoneVideos.read(c.appContext, Uri.parse(it)) } }.forEach { phone[it.id] = it }
            engine.refreshBits()
            refreshSources()
        }
        _state.update {
            it.copy(
                reelId = p.id,
                step = StudioStep.READY,
                notes = listOfNotNull(p.note),
                coverFrames = emptyList(),
                piece = null,
                script = p.script,
                versions = c.reels.versions(p.id),
                options = p.options,
                plan = p.plan,
                title = p.title,
                series = p.series,
                episode = p.episode,
                hookLine = p.hookLine,
                postCaption = p.postCaption,
                story = p.story,
                musicUri = p.musicUri?.let(Uri::parse),
                musicName = p.musicName,
                takes = c.reels.takes(p),
                tips = p.tips,
                views = p.views,
                likes = p.likes,
                youtubeTitle = p.youtubeTitle.orEmpty(),
                youtubeDescription = p.youtubeDescription.orEmpty(),
                hook = null,
                video = c.reels.video(p.id),
                thumbs = it.thumbs + borrowed.values.associate { m -> m.id to m.thumb },
            )
        }
    }

    /** Saves the Reel just made (a new one, or the one being changed) with its whole edit. */
    private suspend fun persist(video: File, note: String?) {
        val s = _state.value
        val plan = s.plan ?: return
        val isNew = s.reelId == null
        val id = s.reelId ?: c.reels.newId()
        val old = c.reels.get(id)
        // Making it again: the Reel as it was stays as a version to go back to.
        if (old != null) c.reels.archive(id)
        val project = ReelProject(
            id = id,
            rideId = rideId.takeIf { !phoneOnly },
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            title = s.title,
            series = s.series,
            episode = s.episode,
            hookLine = s.hookLine,
            postCaption = s.postCaption,
            story = s.story,
            options = s.options,
            musicUri = s.musicUri?.toString(),
            musicName = s.musicName,
            plan = plan,
            takes = emptyList(),
            tips = s.tips.orEmpty(),
            durationMs = plan.totalMs,
            inJournal = old?.inJournal ?: false,
            posted = old?.posted ?: false,
            journalCover = old?.journalCover ?: false,
            coverAtMs = old?.coverAtMs,
            coverText = old?.coverText ?: true,
            coverLine = old?.coverLine,
            coverRoute = old?.coverRoute ?: false,
            idea = old?.idea ?: s.piece?.script?.title,
            script = s.script,
            note = note,
            views = old?.views,
            likes = old?.likes,
            youtubeTitle = s.youtubeTitle.ifBlank { null },
            youtubeDescription = s.youtubeDescription.ifBlank { null },
        )
        val saved = c.reels.save(project, video, s.takes)
        _state.update { it.copy(reelId = saved.id, video = c.reels.video(saved.id), takes = c.reels.takes(saved), coverFrames = emptyList(), versions = c.reels.versions(saved.id)) }
        if (isNew && c.studio.alsoSaveToGallery) ShareImages.saveVideo(c.appContext, c.reels.video(saved.id), "Keppo Reel ${saved.id}")
        onSaved(saved)
    }

    /** After a save: draw the cover, then let Keppo Journal know if the Reel is in it. */
    private suspend fun onSaved(p: ReelProject) {
        val saved = drawCover(p)
        c.reelsChanged(saved)
    }

    // ---- cover -----------------------------------------------------------------------------

    /**
     * Draws [p]'s cover from its settings; with no frame chosen yet, picks the best of the hook
     * clip (sharp, lit, a face if there is one) and remembers it.
     */
    private suspend fun drawCover(p: ReelProject): ReelProject {
        val route = if (p.coverRoute) routePicture() else null
        val saved = drawReelCover(c.appContext, c.reels, p, files(), route)
        route?.recycle()
        _state.update { it.copy(coverVersion = it.coverVersion + 1) }
        return saved
    }

    /** The clips' videos, for the editor's live preview. */
    fun previewFiles(): Map<String, Uri> = files()

    private fun files(): Map<String, Uri> = engine.files()

    /** The source frame (not the finished Reel, so no captions) at [atMs] into the Reel. */
    private fun sourceFrame(plan: StudioPlan, atMs: Long): android.graphics.Bitmap? {
        val spot = ReelCover.spotAt(plan, atMs) ?: return null
        val uri = files()[spot.segment.bit.momentId] ?: spot.segment.bit.source?.let(Uri::parse) ?: return null
        return ReelCover.frame(c.appContext, uri, spot.sourceMs)
    }

    private suspend fun routePicture(): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        c.journal.ride(rideId)?.let { c.journal.routePng(it) }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    }

    /** Opens the cover editor, with a strip of frames from the Reel to pick from. */
    fun cover() {
        _state.update { it.copy(step = StudioStep.COVER) }
        val plan = _state.value.plan ?: return
        if (_state.value.coverFrames.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(c.appContext.cacheDir, "studio-cover").apply { deleteRecursively(); mkdirs() }
            val n = 10
            val frames = (0 until n).mapNotNull { i ->
                val t = plan.totalMs * (2 * i + 1) / (2 * n)
                sourceFrame(plan, t)?.let { b ->
                    val r = ReelCover.crop(b.width, b.height)
                    val small = android.graphics.Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height()).let { cr ->
                        android.graphics.Bitmap.createScaledBitmap(cr, 135, 240, true).also { if (it != cr) cr.recycle() }
                    }
                    b.recycle()
                    val f = File(dir, "f$i.jpg")
                    f.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                    small.recycle()
                    t to f
                }
            }
            _state.update { it.copy(coverFrames = frames) }
        }
    }

    /** Changes the cover's settings and draws it again. */
    fun setCover(f: (ReelProject) -> ReelProject) {
        val id = _state.value.reelId ?: return
        if (_state.value.coverBusy) return
        _state.update { it.copy(coverBusy = true) }
        viewModelScope.launch {
            try {
                c.reels.update(id, f)?.let { drawCover(it) }?.let { c.reelsChanged(it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                c.errors.record("Studio cover", e.message ?: "Couldn't draw the cover", e, null)
            } finally {
                _state.update { it.copy(coverBusy = false) }
            }
        }
    }

    fun saveCover() {
        val id = _state.value.reelId ?: return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(c.reels.cover(id).path) }
            val ok = bmp != null && ShareImages.saveToPhotos(c.appContext, bmp, "Keppo Reel cover $id", jpeg = true)
            bmp?.recycle()
            say(if (ok) "Cover saved to Photos" else "Couldn't save the cover")
        }
    }

    // ---- script view -------------------------------------------------------------------------

    /** Opens the Script view: the sections as they were made, to change and make again. */
    fun scriptView() {
        val s = _state.value
        val sc = s.script ?: s.plan?.let { ScriptEdits.fromPlan(it, s.title) } ?: return
        _state.update { it.copy(step = StudioStep.SCRIPT, draft = sc, note = "", footage = footage()) }
        if (_state.value.otherBits.isEmpty()) viewModelScope.launch { loadOtherRides() }
    }

    /** Opens a suggestion in the Script view to read (story, what was said, captions) and change before it's made. */
    fun openPiece(key: String) {
        val piece = _state.value.pieces.firstOrNull { it.key == key } ?: return
        _state.update { it.copy(step = StudioStep.SCRIPT, draft = piece.script, scriptPiece = key, note = "", footage = footage()) }
        if (_state.value.otherBits.isEmpty()) viewModelScope.launch { loadOtherRides() }
    }

    fun editDraft(f: (Script) -> Script) = _state.update { s -> s.draft?.let { s.copy(draft = f(it)) } ?: s }

    /** A caption's new words ("" hides it); [startMs] is where the line starts in its clip. */
    fun setCaption(momentId: String, startMs: Long, text: String) = editDraft { sc ->
        val k = ScriptWriter.captionKey(momentId, startMs)
        val original = _state.value.footage.firstOrNull { it.momentId == momentId }?.lines?.firstOrNull { it.startMs == startMs }?.text
        sc.copy(captions = if (text.trim() == original) sc.captions - k else sc.captions + (k to text.trim()))
    }

    /** Clips that can go into the script: this ride's parts not used yet, other rides' and Saved clips. */
    fun scriptAddable(): List<Bit> {
        val used = _state.value.draft?.sections?.flatMap { sec -> sec.shots.map { it.clip } }.orEmpty().toSet()
        val saved = c.savedClips.clips.value.map(c.savedClips::bit)
        return (bits.filter { it.momentId !in used } + _state.value.otherBits.filter { it.momentId !in used } + saved.filter { it.momentId !in used }).distinctBy { it.id }
    }

    /** Puts [b] at the end of section [section]; the piece gets longer by as much, so the checks keep it. */
    fun addScriptClip(section: Int, b: Bit) {
        viewModelScope.launch {
            if (b.fromRide != null) {
                engine.extras[b.momentId] = b.copy(inMs = 0, outMs = b.clipDurationMs)
                engine.refreshBits()
            }
            val fs = withContext(Dispatchers.Default) { footage() }
            _state.update { it.copy(footage = fs) }
            editDraft { sc ->
                val secs = sc.sections.toMutableList()
                val i = section.coerceIn(0, secs.lastIndex)
                secs[i] = secs[i].copy(shots = secs[i].shots + ScriptShot(b.momentId, b.inMs, b.outMs))
                sc.copy(sections = secs, lengthSec = (sc.lengthSec + ((b.outMs - b.inMs) / 1000).toInt()).coerceAtMost(sc.format.maxSec))
            }
        }
    }

    /** Takes shot [shot] out of section [section]. */
    fun removeScriptShot(section: Int, shot: Int) = editDraft { sc ->
        sc.copy(sections = sc.sections.mapIndexed { i, sec -> if (i == section) sec.copy(shots = sec.shots.filterIndexed { k, _ -> k != shot }) else sec })
    }
    fun setNote(t: String) = _state.update { it.copy(note = t) }
    /** Another look for this Reel (the next Make it again uses it). */
    fun draftVibe(v: Vibe) {
        editDraft { it.copy(vibe = v) }
        setOptions { it.copy(vibe = v) }
    }

    fun draftHook(shot: ScriptShot, text: String) = editDraft { ScriptEdits.hook(it, shot, ScriptWriter.isSound(text)) }

    /** How long the draft would be, after the app's checks. */
    fun draftLengthMs(): Long {
        val s = _state.value
        val d = s.draft ?: return 0
        return ScriptWriter.toPlan(d, s.footage, bits, s.options.vibe, s.options).plan.totalMs
    }

    /**
     * Makes the Reel again from the changed script. With a note, Gemini first rewrites the script
     * to do what the rider asked (one request). The change is kept to learn the rider's style.
     */
    fun applyDraft() {
        val s = _state.value
        val draft = s.draft ?: return
        val piece = s.scriptPiece?.let { k -> s.pieces.firstOrNull { it.key == k } }
        if (piece != null) {
            // Unchanged: made in the background like any suggestion.
            if (draft == piece.script && s.note.isBlank()) {
                _state.update { it.copy(draft = null, scriptPiece = null) }
                makePiece(piece.key)
                return
            }
            _state.update {
                it.copy(
                    piece = piece, reelId = null, takes = emptyList(), tips = null, coverFrames = emptyList(), scriptPiece = null,
                    title = card?.title ?: it.title, options = it.options.copy(vibe = draft.vibe ?: piece.script.vibe ?: it.options.vibe),
                )
            }
        }
        val before = piece?.script ?: s.script ?: ScriptEdits.fromPlan(s.plan ?: return, s.title)
        val note = s.note.trim()
        viewModelScope.launch {
            var result = draft
            if (note.isNotEmpty() && c.transcripts.available) {
                _state.update { it.copy(toast = "Gemini is rewriting the script…") }
                val fs = s.footage
                runCatching { gemini().scripts(ScriptWriter.revisePrompt(card?.title ?: s.title, fs, style(), draft, note, ScriptWriter.keys(fs)), fs).firstOrNull() }
                    .onFailure { c.errors.record("Studio script", "Gemini couldn't rewrite the script", it) }
                    .getOrNull()?.let { result = it.copy(lengthSec = it.lengthSec.coerceAtLeast(draft.lengthSec / 2)) }
                    ?: say("Gemini couldn't rewrite it, so your own changes were used")
            }
            c.style.record(before.describe(s.footage), result.describe(s.footage), note.ifEmpty { null })
            pendingScript = result
            _state.update { it.copy(draft = null, note = "") }
            make()
        }
    }

    /** Goes back to an earlier version of this Reel (the current one is kept as a version). */
    fun restoreVersion(n: Int) {
        val id = _state.value.reelId ?: return
        viewModelScope.launch {
            c.reels.restoreVersion(id, n)?.let { open(it); c.reelsChanged(it) }
            say("Back to version $n")
        }
    }

    // ---- Keppo Journal ---------------------------------------------------------------------

    /** Sends this Reel (video and cover) to the ride's Keppo Journal entry; its cover becomes the entry's cover. */
    fun sendToJournal() {
        val id = _state.value.reelId ?: return
        viewModelScope.launch { sendToJournal(listOf(id)) }
    }

    /** Sends all of this ride's Reels; the newest one's cover becomes the entry's cover. */
    fun sendAllToJournal() {
        val ids = c.reels.reels.value.filter { it.rideId == rideId && it.deletedAt == null }.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch { sendToJournal(ids) }
    }

    private suspend fun sendToJournal(ids: List<String>) {
        val coverId = ids.first()
        c.reels.reels.value.filter { it.rideId == rideId }.forEach { p ->
            val send = p.id in ids
            if (send || p.journalCover) c.reels.update(p.id) { it.copy(inJournal = it.inJournal || send, journalCover = it.id == coverId) }
        }
        c.journal.onRideSaved(rideId)
        say(
            if (!c.journal.enabled()) "Saved for Keppo Journal. Turn on sharing in Profile › Keppo Journal to see it there."
            else if (ids.size > 1) "${ids.size} Reels sent to Keppo Journal" else "Sent to Keppo Journal · its cover is the entry's cover",
        )
    }

    /** Takes this Reel out of Keppo Journal; the entry's cover falls back to the newest other sent Reel, or the route. */
    fun removeFromJournal() {
        val id = _state.value.reelId ?: return
        viewModelScope.launch {
            val was = c.reels.get(id)?.journalCover == true
            c.reels.update(id) { it.copy(inJournal = false, journalCover = false) }
            if (was) c.reels.reels.value.firstOrNull { it.rideId == rideId && it.inJournal && it.deletedAt == null }?.let { next ->
                c.reels.update(next.id) { it.copy(journalCover = true) }
            }
            c.journal.onRideSaved(rideId)
            say("Removed from Keppo Journal")
        }
    }

    /** Makes this Reel's cover the Journal entry's cover. */
    fun useAsJournalCover() {
        val id = _state.value.reelId ?: return
        viewModelScope.launch {
            c.reels.reels.value.filter { it.rideId == rideId && (it.journalCover || it.id == id) }.forEach { p ->
                c.reels.update(p.id) { it.copy(journalCover = it.id == id) }
            }
            c.journal.onRideSaved(rideId)
            say("This cover is now the Journal entry's cover")
        }
    }

    private fun say(text: String) {
        _state.update { it.copy(toast = text) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_600)
            _state.update { if (it.toast == text) it.copy(toast = null) else it }
        }
    }

    /** A copy of this Reel to try another take; the original stays as it is. */
    fun duplicate() {
        val id = _state.value.reelId ?: return
        viewModelScope.launch { c.reels.duplicate(id)?.let { open(it) } }
    }

    /** Moves this Reel to Recently deleted and starts a fresh one. */
    fun delete() {
        val id = _state.value.reelId ?: return
        viewModelScope.launch {
            c.reels.delete(id)
            c.reels.get(id)?.let { c.reelsChanged(it) }
            _state.update { it.copy(reelId = null, video = null, step = StudioStep.SETUP, tips = null) }
        }
    }

    fun openSaved(id: String) {
        viewModelScope.launch { c.reels.get(id)?.let { open(it) } }
    }

    // ---- choices ---------------------------------------------------------------------------

    fun setOptions(f: (StudioOptions) -> StudioOptions) {
        _state.update {
            val o = f(it.options)
            val lengths = StudioPlanner.lengthsFor(bits, o.vibe)
            it.copy(options = o.copy(lengthSec = if (o.lengthSec in lengths) o.lengthSec else lengths.last()), lengths = lengths)
        }
        if (!phoneOnly) c.studio.options = _state.value.options
    }

    fun setMusic(uri: Uri?) {
        val name = uri?.let { u ->
            runCatching {
                c.appContext.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
            }.getOrNull()?.substringBeforeLast('.') ?: "Your song"
        }
        uri?.let { runCatching { c.appContext.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        _state.update { it.copy(musicUri = uri, musicName = name) }
        c.studio.music = uri?.let { it.toString() to (name ?: "Your song") }
    }

    fun setSeries(name: String) {
        c.studio.series = name
        _state.update { it.copy(series = name) }
    }

    fun back() = _state.update {
        it.copy(
            step = if (it.step in setOf(StudioStep.EDIT, StudioStep.VOICE, StudioStep.COVER, StudioStep.SCRIPT) && it.scriptPiece == null && it.video != null) StudioStep.READY else StudioStep.SETUP,
            error = null, draft = null, scriptPiece = null,
        )
    }

    /** Shared or saved: the next Reel of the series is the next episode. */
    fun posted() {
        val s = _state.value
        if (s.series.isNotBlank()) c.studio.episodeUsed(s.episode)
        // No longer a draft.
        s.reelId?.let { id -> viewModelScope.launch { c.reels.update(id) { it.copy(posted = true) } } }
    }

    // ---- posting ---------------------------------------------------------------------------

    /** How the posted Reel did; Studio's next suggestions lean towards what did well. */
    fun setStats(views: Int?, likes: Int?) {
        _state.update { it.copy(views = views, likes = likes) }
        _state.value.reelId?.let { id -> viewModelScope.launch { c.reels.update(id) { it.copy(views = views, likes = likes, posted = true) } } }
    }

    /** Gemini writes the YouTube title and description (and a fresh Instagram caption if there's none). */
    fun writeYouTube() {
        val s = _state.value
        if (s.posting != null || !s.gemini) return
        _state.update { it.copy(posting = "Writing for YouTube…") }
        viewModelScope.launch {
            val about = listOfNotNull(s.script?.let { "${it.format.label}: ${it.title}" }, s.story, s.hookLine.takeIf { it.isNotBlank() }).joinToString(". ")
            val t = runCatching { gemini().postTexts(s.title, about, s.postCaption) }
                .onFailure { c.errors.record("Studio post text", "Gemini couldn't write the post text", it) }.getOrNull()
            if (t == null) {
                _state.update { it.copy(posting = null, toast = "Gemini couldn't write it now. Try again in a bit.") }
                return@launch
            }
            _state.update {
                it.copy(posting = null, youtubeTitle = t.youtubeTitle, youtubeDescription = t.youtubeDescription, postCaption = it.postCaption.ifBlank { t.instagram })
            }
            val now = _state.value
            now.reelId?.let { id -> c.reels.update(id) { p -> p.copy(youtubeTitle = now.youtubeTitle.ifBlank { null }, youtubeDescription = now.youtubeDescription.ifBlank { null }, postCaption = now.postCaption) } }
        }
    }

    /** Subtitles in [lang] (English or Hindi): Gemini translates every caption line, then the video is made again. */
    fun translateCaptions(lang: String) {
        val s = _state.value
        val plan = s.plan ?: return
        if (s.posting != null || !s.gemini) return
        val lines = Posting.captionTexts(plan)
        if (lines.isEmpty()) { _state.update { it.copy(toast = "There are no captions to translate") }; return }
        _state.update { it.copy(posting = "Translating to $lang…") }
        viewModelScope.launch {
            val out = runCatching { gemini().translate(lines, lang) }
                .onFailure { c.errors.record("Studio subtitles", "Gemini couldn't translate the captions", it) }.getOrNull()
            if (out == null) {
                _state.update { it.copy(posting = null, toast = "Couldn't translate now. Try again in a bit.") }
                return@launch
            }
            _state.update { it.copy(posting = null, plan = Posting.withCaptions(plan, out)) }
            remake()
        }
    }

    /** Three frames of the first 3 seconds and the hook line go to Gemini: would it stop the scroll? */
    fun checkHook() {
        val s = _state.value
        val video = s.video ?: return
        if (s.posting != null || !s.gemini) return
        _state.update { it.copy(posting = "Watching the first 3 seconds…", hook = null) }
        viewModelScope.launch {
            val frames = withContext(Dispatchers.IO) {
                listOf(200L, 1_500L, 2_800L).mapNotNull { at ->
                    ReelStore.frame(video, at)?.let { b ->
                        val small = android.graphics.Bitmap.createScaledBitmap(b, 360, (360f * b.height / b.width).toInt().coerceAtLeast(1), true)
                        java.io.ByteArrayOutputStream().also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                    }
                }
            }
            if (frames.isEmpty()) { _state.update { it.copy(posting = null, toast = "Couldn't read the video's first seconds") }; return@launch }
            val h = runCatching { gemini().hookCheck(frames, s.hookLine.ifBlank { s.title }) }
                .onFailure { c.errors.record("Studio hook check", "Gemini couldn't check the hook", it) }.getOrNull()
            _state.update { it.copy(posting = null, hook = h, toast = if (h == null) "Gemini couldn't check it now. Try again in a bit." else it.toast) }
        }
    }

    // ---- making ----------------------------------------------------------------------------

    /** Captions (Gemini) → stories and picks (Gemini director) → plan → video → tips. */
    fun make() {
        if (job?.isActive == true) return
        val o = _state.value.options
        val gemini = c.transcripts.available
        // A suggestion's script is written already: nothing to read. Otherwise only clips not read yet.
        val unread = if (pendingScript == null && o.captions && gemini && !engine.captionsTried) engine.unreadCount() else 0
        val needCaptions = unread > 0
        skipCaptions = false
        _state.update {
            it.copy(
                step = StudioStep.WORKING, error = null, video = null, renderProgress = null, notes = emptyList(), tips = null,
                work = listOfNotNull(
                    WorkStep("Reading what you said", 0).takeIf { needCaptions },
                    WorkStep(if (pendingScript != null) "Fitting the script to your clips" else "Writing the script", 0),
                    WorkStep("Making the video", 0),
                ),
            )
        }
        job = viewModelScope.launch {
            val notes = ArrayList<String>()
            try {
                val w = if (needCaptions) 1 else 0
                if (needCaptions) {
                    step(0, 1)
                    _state.update { it.copy(canSkipCaptions = true) }
                    val read = engine.readCaptions(onProgress = { d -> step(0, 1, d) }, skip = { skipCaptions })
                    _state.update { it.copy(canSkipCaptions = false) }
                    read.note?.let { notes += it }
                    step(0, if (skipCaptions) 3 else 2, if (skipCaptions) "Skipped" else null)
                    refreshSources()
                }
                step(w, 1)
                // Script first: a suggestion's script, or Gemini writes one (or the app's calm fallback); then the app checks it.
                val sc = pendingScript ?: writeScript(avoid = remixFrom)
                pendingScript = null
                remixFrom = null
                applyScript(sc)
                val s = _state.value
                step(w, 2, listOfNotNull(s.script?.title?.let { "“$it”" } ?: s.story?.let { "“$it”" }, "${s.plan?.clips?.size} clips · ${Format.clock(s.plan?.totalMs ?: 0)}").joinToString(" · "))
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

    // ---- scripts ---------------------------------------------------------------------------

    private fun footage(): List<Footage> = engine.footage()

    /** Gemini's script for the chosen length (one request), or the app's own when Gemini can't. */
    private suspend fun writeScript(avoid: Script?): Script {
        val fs = withContext(Dispatchers.Default) { footage() }
        val o = _state.value.options
        val cd = card
        if (c.transcripts.available && cd != null) {
            val prompt = ScriptWriter.piecePrompt(cd.title, cd.subtitle, fs, style(), PieceFormat.REEL, o.lengthSec, avoid, ScriptWriter.keys(fs))
            var why = "Made by the app: Gemini's answer couldn't be read"
            runCatching { gemini().scripts(prompt, fs).firstOrNull() }
                .onFailure {
                    c.errors.record("Studio script", "Gemini couldn't write the script", it)
                    why = if (com.ridetrack.app.transcribe.GeminiNet.isUnreachable(it)) "Made by the app: couldn't reach Gemini (no internet, or blocked)"
                    else if (it is FirebaseTranscriber.Busy) "Made by the app: Gemini's free limit is used up" else "Made by the app: Gemini had a problem"
                }
                .getOrNull()?.let { return it.copy(lengthSec = minOf(it.lengthSec, o.lengthSec)) }
            return ScriptWriter.local(fs, PieceFormat.REEL, o.lengthSec, _state.value.title, why) ?: error("No usable clips")
        }
        return ScriptWriter.local(fs, PieceFormat.REEL, o.lengthSec, _state.value.title) ?: error("No usable clips")
    }

    /** Turns [sc] into the plan (the app's checks fix what Gemini got wrong) and shows it. */
    private suspend fun applyScript(sc: Script) {
        sc.vibe?.let { v -> _state.update { it.copy(options = it.options.copy(vibe = v)) } }
        val fs = withContext(Dispatchers.Default) { footage() }
        var planned = ScriptWriter.toPlan(sc, fs, bits, _state.value.options.vibe, _state.value.options)
        var used = sc
        if (planned.plan.clips.isEmpty()) {
            // Nothing usable in Gemini's script: the app's own.
            used = ScriptWriter.local(fs, sc.format, sc.lengthSec, _state.value.title) ?: error("No usable clips")
            planned = ScriptWriter.toPlan(used, fs, bits, _state.value.options.vibe, _state.value.options)
        }
        if (planned.plan.clips.isEmpty()) error("No usable clips")
        if (planned.fixes.isNotEmpty()) c.errors.record("Studio script", "Fixed ${planned.fixes.size} things in the script", null, planned.fixes.joinToString("\n"))
        val hook = used.sections.firstOrNull { it.kind == SectionKind.HOOK }
        _state.update {
            it.copy(
                plan = planned.plan,
                script = used,
                story = null,
                title = if (it.title == card?.title && used.title.isNotBlank() && used.why?.startsWith("Made by the app") != true) used.title else it.title,
                hookLine = if (used.format == PieceFormat.CAPTION) "" else used.hookLine ?: hook?.text ?: it.hookLine,
                postCaption = used.postCaption ?: fallbackCaption(),
                content = ScriptWriter.summary(fs).text,
            )
        }
    }

    private fun style(): StyleContext? = engine.style()

    /** Another take: Gemini writes a script clearly different from this one (one request). */
    fun remix() {
        if (_state.value.plan == null || job?.isActive == true) return
        remixFrom = _state.value.script
        setOptions { it.copy(seed = it.seed + 1) }
        make()
    }

    fun skipCaptions() { skipCaptions = true }

    fun cancel() {
        job?.cancel()
        makingId?.let { c.reelMaker.cancel(it) }
        makingId = null
        _state.update { it.copy(step = StudioStep.SETUP, renderProgress = null) }
    }

    /** One tap on a coach tip. */
    fun act(a: TipAction) {
        when (a) {
            TipAction.TITLE_ON_HOOK -> { setOptions { it.copy(intro = false) }; make() }
            TipAction.SHORTER -> {
                setOptions { it.copy(lengthSec = 15) }
                make()
            }
            TipAction.STORY -> remix()
            TipAction.VOICE_OVER -> voice()
            TipAction.OTHER_RIDES -> edit()
        }
    }

    // ---- edit ------------------------------------------------------------------------------

    fun setTitle(t: String) = _state.update { it.copy(title = t) }
    fun setHookLine(t: String) = _state.update { it.copy(hookLine = t) }

    // ---- timeline editor --------------------------------------------------------------------

    private var history: EditHistory? = null
    private var dragFrom: StudioPlan? = null
    /** Where the editor's playhead is (Reel ms), for adding things there. */
    @Volatile var playheadMs: Long = 0L

    /** Opens the timeline editor on this Reel. */
    fun edit() {
        val plan = _state.value.plan ?: return
        // An edit left unsaved (the app closed) carries on where it was.
        val draft = readDraft()
        history = EditHistory(draft ?: TimelineEdits.liftTexts(plan) { newTextId() })
        val saved = c.savedClips.clips.value.associate { "saved:${it.id}" to c.savedClips.thumb(it.id).takeIf(File::isFile) }
        val engine = (clips + borrowed.values).filter { it.engineFile.isFile }.associate { it.id to Uri.fromFile(it.engineFile).toString() }
        _state.update { it.copy(step = StudioStep.EDIT, pick = null, footage = footage(), thumbs = it.thumbs + saved, engineFiles = engine) }
        if (draft != null) say("Carried on from your unsaved edit")
        publish(reload = true)
        if (_state.value.otherBits.isEmpty()) viewModelScope.launch { loadOtherRides() }
    }

    private fun newTextId() = "t" + java.util.UUID.randomUUID().toString().take(8)

    private fun publish(reload: Boolean) {
        val h = history ?: return
        _state.update { it.copy(timeline = h.current, canUndo = h.canUndo, canRedo = h.canRedo, steps = h.steps, timelineVersion = it.timelineVersion + if (reload) 1 else 0) }
        if (reload) writeDraft(h.current)
    }

    /** One change on the timeline (one undo step, named [label] in History). */
    fun change(label: String = "Change", f: (StudioPlan) -> StudioPlan) {
        val h = history ?: return
        h.apply(f(h.current), label)
        publish(reload = true)
    }

    /** Back to just before History step [index]. */
    fun undoTo(index: Int) {
        history?.undoTo(index)
        _state.update { it.copy(pick = null) }
        publish(reload = true)
    }

    // ---- autosave: the edit is kept as it changes, so nothing is lost if the app closes ----

    private val draftFile: File
        get() = File(File(c.appContext.filesDir, "studio-drafts").apply { mkdirs() }, (_state.value.reelId ?: "ride-$rideId") + ".json")

    private fun writeDraft(plan: StudioPlan) {
        val s = _state.value
        val p = ReelProject(
            id = s.reelId ?: "draft", rideId = rideId.takeIf { !phoneOnly }, createdAt = 0, updatedAt = System.currentTimeMillis(), title = s.title, series = s.series,
            episode = s.episode, hookLine = s.hookLine, postCaption = s.postCaption, story = null, options = s.options, musicUri = null, musicName = null,
            plan = plan, takes = emptyList(), tips = emptyList(), durationMs = plan.totalMs,
        )
        val f = draftFile
        viewModelScope.launch(Dispatchers.IO) { runCatching { f.writeText(ReelJson.write(p)) } }
    }

    /** The unsaved edit of this Reel, when it's newer than the Reel itself. */
    private fun readDraft(): StudioPlan? = runCatching {
        val f = draftFile
        if (!f.isFile) return null
        val d = ReelJson.read(f.readText()) ?: return null
        val saved = _state.value.reelId?.let { c.reels.get(it)?.updatedAt } ?: 0L
        d.plan.takeIf { d.updatedAt > saved && d.plan != _state.value.plan }
    }.getOrNull()

    private fun dropDraft() {
        val f = draftFile
        viewModelScope.launch(Dispatchers.IO) { f.delete() }
    }

    // ---- layers and sound ----------------------------------------------------------------

    private fun newId(prefix: String) = prefix + java.util.UUID.randomUUID().toString().take(8)

    /** Puts [b] over the edit at the playhead, in the [preset] place. */
    fun addLayer(b: Bit, preset: LayerPreset) {
        val id = newId("l")
        change("Add layer") { TrackEdits.addLayer(it, b, playheadMs, id, preset) }
        _state.update { it.copy(pick = TimelinePick.Layer(id)) }
    }

    /** The engine mic's sound under every clip that has it. */
    fun addEngine() {
        val engine = _state.value.engineFiles
        if (engine.isEmpty()) return say("No engine sound in these clips (record with two mics)")
        change("Engine sound") { TrackEdits.addEngine(it, engine, { newId("e") }) }
    }

    fun detach(i: Int) {
        val id = newId("d")
        change("Detach sound") { TrackEdits.detach(it, i, id) }
        _state.update { it.copy(pick = TimelinePick.Audio(id)) }
    }

    fun copySettings(i: Int) {
        val s = _state.value.timeline?.let { TrackEdits.copySettings(it, i) } ?: return
        _state.update { it.copy(copied = s) }
        say("Copied · select clips and Paste")
    }

    fun pasteSettings(indices: Set<Int>) {
        val s = _state.value.copied ?: return
        change("Paste settings") { TrackEdits.pasteSettings(it, indices, s) }
    }

    fun addMarker() = change("Marker") { TrackEdits.addMarker(it, playheadMs, newId("m")) }

    /** A trim being dragged: [f] gets the plan from before the drag. */
    fun drag(f: (StudioPlan) -> StudioPlan) {
        val h = history ?: return
        val from = dragFrom ?: h.current.also { dragFrom = it }
        h.preview(f(from))
        publish(reload = false)
    }

    fun endDrag() {
        val h = history ?: return
        dragFrom?.let { h.settle(it) }
        dragFrom = null
        publish(reload = true)
    }

    fun pick(p: TimelinePick?) = _state.update { it.copy(pick = p) }
    fun undo() { history?.undo(); _state.update { it.copy(pick = null) }; publish(reload = true) }
    fun redo() { history?.redo(); _state.update { it.copy(pick = null) }; publish(reload = true) }

    /** New text, in the look of the last text the rider styled (in this Reel). */
    fun addText(text: String) {
        val id = newTextId()
        change("Add text") { p ->
            val added = TimelineEdits.addText(p, playheadMs, text, id)
            val like = p.texts.lastOrNull { it.look != TextLook.STYLE || it.color != null || it.animIn != TextAnim.FADE }
            added.copy(texts = added.texts.map { if (it.id == id) TextTools.styled(it, like) else it })
        }
        _state.update { it.copy(pick = TimelinePick.Text(id)) }
    }
    fun addCaption(text: String) = change("Add caption") { TimelineEdits.addCaption(it, playheadMs, text) }
    fun insert(b: Bit) = change("Add clip") { TimelineEdits.insert(it, b, playheadMs) }

    /** Your saved clips first, then parts of this ride (and the other rides Studio read) that aren't in the edit yet. */
    fun insertable(): List<Bit> {
        val used = _state.value.timeline?.clips?.map { it.bit.momentId }.orEmpty().toSet()
        val saved = c.savedClips.clips.value.map(c.savedClips::bit)
        return saved.filter { it.momentId !in used } + bits.filter { it.momentId !in used } + _state.value.otherBits.filter { it.momentId !in used }
    }

    /** What the exact preview plays for [plan]: the same as the made video (the voice-over mixed in). */
    suspend fun previewInput(plan: StudioPlan): RenderInput? {
        val s = _state.value
        val cd = card ?: return null
        val voice = s.takes.takeIf { it.isNotEmpty() }?.let { takes ->
            withContext(Dispatchers.IO) { VoiceRecorder.mix(takes, plan.totalMs, File(c.appContext.cacheDir, "studio-preview-voice.wav")) }
        }
        return renderInput(plan, s, cd, voice)
    }

    /** The exact preview didn't work here: kept as a warning, the simple one is used. */
    fun previewFailed(e: Throwable) {
        c.errors.warn("Studio preview", "The exact preview didn't play on this phone: using the simple preview", e)
    }

    // ---- clip tools that need the clip's pictures --------------------------------------------

    /** Holds the frame under the playhead for 2 s (a freeze frame, to put text on). */
    fun freeze() {
        val plan = _state.value.timeline ?: return
        val (i, local) = TimelineEdits.at(plan, playheadMs)
        val seg = plan.segments.getOrNull(i) as? ClipSegment ?: return
        if (seg.tail) return
        viewModelScope.launch {
            val uri = files()[seg.bit.momentId] ?: seg.bit.source?.let(Uri::parse) ?: return@launch say("Couldn't find that clip")
            val at = seg.inMs + if (seg.still != null) 0 else (local / Speed.outPerSource(seg.speed, seg.ramp)).toLong()
            val still = seg.still?.let(::File) ?: ClipMedia.still(c.appContext, uri, at) ?: return@launch say("Couldn't read that frame")
            val ms = playheadMs
            change("Freeze frame") { ClipTools.freeze(it, ms, still.path) }
            say("Frozen for 2 s · + Text to write on it")
        }
    }

    /** Plays the clip at [i] backwards (made once: it takes a little while), or forwards again. */
    fun reverse(i: Int) {
        val seg = _state.value.timeline?.segments?.getOrNull(i) as? ClipSegment ?: return
        if (seg.reverse != null) { change("Forwards") { ClipTools.reverse(it, i, null) }; return }
        if (seg.still != null) return
        if (seg.sourceMs > ClipMedia.MAX_REVERSE_MS) return say("Reverse works on parts up to ${ClipMedia.MAX_REVERSE_MS / 1000} s: trim it first")
        viewModelScope.launch {
            val uri = files()[seg.bit.momentId] ?: seg.bit.source?.let(Uri::parse) ?: return@launch say("Couldn't find that clip")
            say("Reversing…")
            runCatching { c.reelMaker.keepRunning { ClipMedia.reverse(c.appContext, uri, seg.inMs, seg.sourceMs) } }
                .onSuccess { f -> change("Reverse") { ClipTools.reverse(it, i, f.path) }; say("Reversed · plays backwards in the saved video") }
                .onFailure { e -> c.errors.record("Studio reverse", "Couldn't reverse the clip", e); say("Couldn't reverse it") }
        }
    }

    fun replace(i: Int, b: Bit) = change("Replace clip") { ClipTools.replace(it, i, b) }

    // ---- styles -------------------------------------------------------------------------------

    /** Makes this Reel again in style [st]: its look, captions, text, transitions, pace, colour and stickers. */
    fun applyStyle(st: StudioStyle) {
        val plan = _state.value.plan ?: return
        var n = 0
        val styled = Styles.apply(plan, st) { "st${System.currentTimeMillis() % 100_000}-${n++}" }
        c.styles.used(st.id)
        _state.update { it.copy(plan = styled, options = Styles.options(it.options, st)) }
        remake()
    }

    /** A style on the edit in the timeline (undoable; the video is made on Save). */
    fun styleOnTimeline(st: StudioStyle) {
        var n = 0
        change("Style \u201c${st.name}\u201d") { Styles.apply(it, st) { "st${System.currentTimeMillis() % 100_000}-${n++}" } }
        c.styles.used(st.id)
        setOptions { Styles.options(it, st) }
    }

    /** Keeps this Reel's look as a new style (Styles). */
    fun saveLookAsStyle(name: String) {
        val s = _state.value
        val plan = s.timeline ?: s.plan ?: return
        val st = Styles.fromReel(plan, s.options, c.styles.newId(), name.trim().ifBlank { "My look" })
        c.styles.save(st)
        say("Saved as the style \u201c${st.name}\u201d")
    }

    /** Gemini reads one clip's words again (one request); its captions in the edit are replaced. */
    fun readAgain(momentId: String) {
        if (!c.transcripts.available) return say("Gemini isn't in this build")
        val key = engine.captionKey(momentId) ?: return say("Can't read that clip again")
        viewModelScope.launch {
            say("Reading the clip again…")
            val before = withContext(Dispatchers.IO) { StudioText.load(key) }
            engine.forget(momentId)
            val read = runCatching { engine.readCaptions() }.getOrNull()
            val lines = withContext(Dispatchers.IO) { StudioText.load(key) }
            if (lines == null) {
                // Didn't work: keep the words it had.
                before?.let { withContext(Dispatchers.IO) { StudioText.save(key, it) } }
                return@launch say(read?.note ?: "Couldn't read it again")
            }
            change("Read again") { TextTools.relines(it, momentId, lines) }
            say(if (lines.isEmpty()) "No clear words in that clip" else "Read again · ${lines.size} line${if (lines.size > 1) "s" else ""}")
        }
    }

    // ---- text style that carries on, and stickers ---------------------------------------------

    fun addSticker(kind: StickerKind, text: String = "") {
        val id = newId("s")
        change("Add sticker") { TextTools.addSticker(it, kind, playheadMs, id, text) }
        _state.update { it.copy(pick = TimelinePick.Sticker(id)) }
    }

    // ---- export -------------------------------------------------------------------------------

    /** Whether 4K and 60 fps make sense for this Reel's clips. */
    suspend fun exportChoices(): Pair<Boolean, Boolean> {
        val plan = _state.value.plan ?: return false to false
        val f = files()
        val uris = plan.clips.mapNotNull { f[it.bit.momentId] ?: it.bit.source?.let(Uri::parse) }.distinct()
        return withContext(Dispatchers.IO) { Exports.choices(uris.mapNotNull { ClipMedia.info(c.appContext, it) }) }
    }

    /**
     * Writes the Reel for [preset] with [spec] (in the background: leaving the app doesn't stop
     * it) and saves it to Movies/Keppo Moto; a Story is cut into its parts.
     */
    fun export(preset: ExportPreset, spec: OutputSpec) {
        val s = _state.value
        val plan = s.plan ?: return
        val cd = card ?: return
        val title = s.title
        _state.update { it.copy(exporting = 0) }
        c.appScope.launch {
            try {
                val saved = c.reelMaker.keepRunning {
                    val voice = s.takes.takeIf { it.isNotEmpty() }?.let { takes ->
                        withContext(Dispatchers.IO) { VoiceRecorder.mix(takes, plan.totalMs, File(c.appContext.cacheDir, "studio-export-voice.wav")) }
                    }
                    val dir = File(c.appContext.cacheDir, "studio-exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    val out = File(dir, "keppo-${preset.name.lowercase()}-${System.currentTimeMillis()}.mp4")
                    val input = renderInput(plan, s, cd, voice).copy(output = spec)
                    val done = StudioRenderer(c.appContext).render(input, out) { p -> _state.update { it.copy(exporting = p) } }.getOrThrow()
                    if (done.failures.isNotEmpty()) c.errors.warn("Studio export", "Export needed a fallback: ${done.note ?: "a simpler method"}")
                    val files = preset.splitSec?.let { sec ->
                        Exports.parts(plan.totalMs, sec * 1000L).takeIf { it.size > 1 }?.mapIndexed { k, r ->
                            File(dir, "${out.nameWithoutExtension}-part${k + 1}.mp4").also { ClipMedia.cut(c.appContext, done.file, r.first, r.last, it) }
                        }
                    } ?: listOf(done.file)
                    files.mapIndexed { k, f -> ShareImages.saveVideo(c.appContext, f, "Keppo ${preset.label} $title" + if (files.size > 1) " part ${k + 1}" else "") }.count { it }
                }
                posted()
                say(if (saved > 1) "$saved parts saved to Movies/Keppo Moto" else if (saved == 1) "Saved to Movies/Keppo Moto" else "Couldn't save to the gallery")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio export", "Couldn't export for ${preset.label}", e)
                say("Couldn't export (${e.message?.take(60)})")
            } finally {
                _state.update { it.copy(exporting = null) }
            }
        }
    }

    /** Everything that can go over the edit as a layer: saved clips, this ride's parts (used ones too), other rides'. */
    fun layerable(): List<Bit> = c.savedClips.clips.value.map(c.savedClips::bit) + bits + _state.value.otherBits

    /**
     * Keeps [startMs]..[endMs] of a clip in Saved clips (copied, with its words and tags), to
     * reuse in Reels of other rides.
     */
    fun saveClip(momentId: String, startMs: Long, endMs: Long, source: String? = null) {
        viewModelScope.launch {
            val f = footage().firstOrNull { it.momentId == momentId }
            val uri = files()[momentId] ?: source?.let(Uri::parse) ?: return@launch say("Couldn't find that clip")
            val moment = (clips + borrowed.values).firstOrNull { it.id == momentId }
            val lines = f?.lines ?: withContext(Dispatchers.IO) { moment?.let { StudioText.load(it.file) } }.orEmpty()
            say("Saving the clip…")
            runCatching {
                c.savedClips.save(
                    uri, startMs, endMs.coerceAtLeast(startMs + 300), lines,
                    from = card?.let { "${it.title} · ${it.subtitle.substringBefore(" · ")}" }, rideId = rideId.takeIf { !phoneOnly },
                    atMillis = f?.startMillis ?: moment?.videoStartMillis ?: System.currentTimeMillis(), camera = moment?.camera, topKmh = f?.kmhMax,
                )
            }.onSuccess { say("Saved · Your Reels › Saved clips") }
                .onFailure { e -> c.errors.record("Studio saved clips", "Couldn't save the clip", e); say("Couldn't save the clip") }
        }
    }

    /** Saves the whole clip from the strip. */
    fun saveSource(id: String) {
        val src = _state.value.sources.firstOrNull { it.id == id } ?: return
        saveClip(id, 0, src.durationMs, src.uri.toString())
    }

    /** Saves exactly the part the edit uses of the clip at [index]. */
    fun saveSegment(index: Int) {
        val seg = _state.value.timeline?.segments?.getOrNull(index) as? ClipSegment ?: return
        saveClip(seg.bit.momentId, seg.inMs, seg.inMs + seg.durMs, seg.bit.source)
    }

    /** Leaves the editor without keeping the changes. */
    fun closeEdit() {
        dropDraft()
        history = null
        dragFrom = null
        _state.update { it.copy(step = StudioStep.READY, timeline = null, pick = null) }
    }

    /** Keeps the edit and makes the video from it (the only render). The change is kept for Your style. */
    fun saveEdit() {
        val h = history ?: return
        val edited = TimelineEdits.normalize(h.current)
        val s = _state.value
        val fs = s.footage.ifEmpty { footage() }
        val before = s.script ?: s.plan?.let { ScriptEdits.fromPlan(it, s.title) }
        val after = before?.let { ScriptEdits.sync(it, edited) }
        if (before != null && after != null) c.style.record(before.describe(fs), after.describe(fs) + if (edited.texts.isNotEmpty()) " + text: " + edited.texts.joinToString { it.text } else "", "(edited on the timeline)")
        dropDraft()
        history = null
        _state.update { it.copy(plan = edited, script = after ?: it.script, timeline = null, pick = null, step = StudioStep.READY) }
        remake()
    }

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
        val gone = plan.clips.mapNotNull { it.bit.source }.distinct().filter { u -> !withContext(Dispatchers.IO) { PhoneVideos.exists(c.appContext, Uri.parse(u)) } }.toSet()
        if (gone.isNotEmpty()) {
            var p = plan
            while (true) {
                val i = p.segments.indexOfFirst { it is ClipSegment && !it.tail && it.bit.source in gone }
                if (i < 0 || p.clips.size <= 1) break
                p = StudioPlanner.remove(p, i)
            }
            if (p.clips.any { it.bit.source in gone }) error("The phone videos in this Reel are gone from the gallery")
            _state.update { it.copy(plan = p) }
            return render(notes + "${gone.size} phone video${if (gone.size > 1) "s" else ""} gone from the gallery, left out.")
        }
        val input = renderInput(plan, s, baseCard, voice)
        val out = File(ShareImages.sharesDir(c.appContext), "keppo-reel-${System.currentTimeMillis()}.mp4")
        // Keeps the app running if the rider leaves it meanwhile.
        val result = c.reelMaker.keepRunning { StudioRenderer(c.appContext).render(input, out) { p -> _state.update { it.copy(renderProgress = p) } } }
        val done = result.getOrThrow()
        persist(done.file, done.note ?: if (done.failures.isNotEmpty()) "Made with a simpler method on this phone" else null)
        // Attempts that failed before one worked are worth knowing about too.
        if (done.failures.isNotEmpty()) {
            val e = StudioRenderer.ExportFailed("Made on attempt ${done.failures.size + 1}").also { x -> done.failures.forEach { x.addSuppressed(it) } }
            c.errors.warn("Studio export", "Needed a fallback: ${done.note ?: "a simpler method"}", e, describePlan(plan))
        }
        step(idx, 2)
        _state.update { it.copy(step = StudioStep.READY, renderProgress = null, notes = notes + listOfNotNull(done.note)) }
        coach()
    }

    private fun renderInput(plan: StudioPlan, s: StudioState, baseCard: RideCard, voice: File?) = engine.renderInput(plan, s, baseCard, voice)

    /** Tips for this video: Gemini's when it can, the app's own otherwise. "Film next time" ones go on Home. */
    private fun coach() {
        coachJob?.cancel()
        coachJob = viewModelScope.launch {
            val s = _state.value
            val plan = s.plan ?: return@launch
            val d = s.script?.let { sc -> Direction(sc.title, sc.postCaption, null, emptyMap(), sc.hookLine) }
            val story: Story? = null
            val voiceOver = s.takes.isNotEmpty()
            val tips = (if (c.transcripts.available) {
                runCatching { gemini().coach(StudioCoach.summary(plan, s.options, bits, d, story, voiceOver, s.musicUri != null)) }
                    .onFailure { c.errors.record("Studio coach", "Gemini couldn't write tips", it) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }).ifEmpty { StudioCoach.localTips(plan, s.options, bits, d, voiceOver) }
            c.studio.setShots(tips.filter { it.nextRide }.map { it.text })
            _state.update { it.copy(tips = tips) }
            // The tips are kept with the saved Reel.
            _state.value.reelId?.let { id -> c.reels.update(id) { p -> p.copy(tips = tips) } }
        }
    }

    /**
     * Everything about this ride's Studio work, to send to the developer: the clips, the
     * suggestions (with Gemini's answer and what the checks changed), the Reel on screen, and the
     * recent Studio errors and warnings.
     */
    fun sendDetails() {
        viewModelScope.launch {
            val s = _state.value
            val text = withContext(Dispatchers.IO) {
                buildString {
                    appendLine("Studio · ride $rideId · ${card?.title} · ${card?.subtitle}")
                    appendLine("Clips: ${clips.size} moments, ${phone.size} phone videos, ${s.excluded.size} left out · ${engine.unreadCount()} not read yet · ${bits.size} usable parts")
                    appendLine("Settings: ${ReelJson.writeOptionsJson(s.options)}")
                    appendLine()
                    appendLine("Suggestions shown: ${s.pieces.size}")
                    s.pieces.forEach { pc ->
                        appendLine("  ${pc.script.format.label} · \u201c${pc.script.title}\u201d · planned ${pc.plannedMs} ms · made ${pc.plan.totalMs} ms · ${pc.plan.clips.size} clips · ${pc.script.why.orEmpty().take(80)}")
                    }
                    engine.planDetails()?.let { appendLine(it) }
                    s.plan?.let { appendLine(); appendLine("Reel on screen (${s.reelId ?: "not saved"}):"); append(describePlan(it)) }
                    s.reelId?.let { id -> c.reels.get(id)?.note?.let { appendLine("Note: $it") } }
                    appendLine()
                    appendLine("Recent Studio errors and warnings:")
                    c.errors.entries.value.filter { it.area.startsWith("Studio") || it.area.startsWith("Gemini") }.take(8).forEach { appendLine(it.text().take(6_000)); appendLine("----") }
                }
            }
            c.errors.shareText("Keppo Moto Studio details", text)
        }
    }

    /** The plan in a few lines, so an error report shows what was being made. */
    private fun describePlan(p: StudioPlan): String = buildString {
        val s = _state.value
        appendLine("Plan: ${p.vibe} · ${p.totalMs} ms · ${p.segments.size} segments · length ${s.options.lengthSec}s · music ${s.musicUri?.let { "own" } ?: "none"} · voice takes ${s.takes.size}")
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

    private fun gemini() = engine.gemini()

    /** Voice-triggered, filmed on purpose, or already known to have words. */
    private fun likelyTalking(m: Moment) =
        RideEventType.VOICE in m.types || m.source == MomentSource.MANUAL || !m.transcript.isNullOrBlank()

    private fun fallbackCaption(): String = engine.fallbackCaption()

    override fun onCleared() {
        job?.cancel()
        coachJob?.cancel()
        recorder.stop()
    }

}
