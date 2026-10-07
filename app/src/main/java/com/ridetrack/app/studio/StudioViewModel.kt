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
    /** Every clip Studio can pick from (moments and phone videos), in filming order. */
    val sources: List<StudioSource> = emptyList(),
    /** Clips the rider left out (long-press in the strip). */
    val excluded: Set<String> = emptySet(),
    /** Enough to make a Reel (2+ usable parts). */
    val canMake: Boolean = false,
    /** Gallery videos only, no ride. */
    val phoneOnly: Boolean = false,
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
    /** Bumped when the timeline changes for good (not mid-drag), so the preview reloads. */
    val timelineVersion: Int = 0,
) {
    /** The small label on the first clip: the series and episode, or the title. */
    val label: String get() = if (series.isBlank()) title else "$series · ep $episode"
}

/** Studio: turns a ride's clips into a Reel (captions, stories and tips by Gemini when it can). */
@OptIn(UnstableApi::class)
class StudioViewModel(private val c: AppContainer, val rideId: String, private val openReelId: String? = null) : ViewModel() {
    private val _state = MutableStateFlow(StudioState(gemini = c.transcripts.available))
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private var clips: List<Moment> = emptyList()
    /** Videos from the phone's gallery, by id. */
    private val phone = LinkedHashMap<String, PhoneClip>()
    private val phoneOnly = rideId == PHONE_STUDIO
    /** Clips from other rides the rider added (their files are needed to render). */
    private val borrowed = HashMap<String, Moment>()
    private var samples: List<TelemetrySample> = emptyList()
    private var card: RideCard? = null
    private var bits: List<Bit> = emptyList()
    private var captionsDone = false
    private var job: Job? = null
    private var coachJob: Job? = null
    private val recorder = VoiceRecorder()
    @Volatile private var skipCaptions = false
    /** The script being made; Remix asks for one different from it. */
    private var remixFrom: Script? = null

    /** A script to make next (a suggestion), instead of asking for a new one. */
    private var pendingScript: Script? = null

    init {
        viewModelScope.launch {
            if (phoneOnly) {
                card = RideCard(title = "My videos", subtitle = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(java.time.LocalDate.now()), route = emptyList(), stats = emptyList())
                _state.update {
                    it.copy(loading = false, phoneOnly = true, title = "My videos", series = c.studio.series, episode = c.studio.nextEpisode, options = it.options.copy(outro = false, map = false))
                }
                c.studio.takePending().takeIf { it.isNotEmpty() }?.let { addPhoneVideos(it).join() }
                if (openReelId == null && phone.isNotEmpty()) loadPlan()
                openReelId?.let { id -> c.reels.get(id)?.let { open(it) } }
                return@launch
            }
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
            StudioSource(m.id, m.thumb, m.durationMillis ?: 0, likelyTalking(m) || StudioText.load(m.file).orEmpty().isNotEmpty(), if (m.camera == "back") "Road" else null, m.videoStartMillis, Uri.fromFile(m.file))
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
        if (added.isNotEmpty()) captionsDone = false
        bits = withContext(Dispatchers.IO) { buildBits() }
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
        viewModelScope.launch {
            bits = withContext(Dispatchers.IO) { buildBits() }
            refreshSources()
            }
    }

    // ---- suggested content ---------------------------------------------------------------------

    private val planFile get() = File(File(c.appContext.filesDir, "studio-plans").apply { mkdirs() }, "$rideId.json")

    /** Loads the saved suggestions, or asks for them the first time the ride's Studio opens. */
    private suspend fun loadPlan() {
        val saved = withContext(Dispatchers.IO) {
            runCatching { org.json.JSONObject(planFile.readText()).getJSONArray("pieces") }.getOrNull()?.let { a ->
                (0 until a.length()).mapNotNull { ScriptJson.read(a.optJSONObject(it)) }
            }
        }
        if (saved != null) showPieces(saved) else if (_state.value.canMake) suggest()
    }

    private suspend fun showPieces(scripts: List<Script>) {
        val fs = withContext(Dispatchers.Default) { footage() }
        val o = _state.value.options
        val list = scripts.mapIndexedNotNull { i, sc ->
            val plan = ScriptWriter.toPlan(sc, fs, bits, o.vibe, o).plan
            if (plan.clips.isEmpty()) null else ContentPiece("p$i", sc, plan)
        }
        _state.update { it.copy(pieces = list, content = ScriptWriter.summary(fs).text) }
    }

    /**
     * Gemini reads the ride (captions once) and suggests what to make from it, as scripts
     * (one request). Without Gemini, the app suggests a Reel and a Short itself.
     */
    fun suggest() {
        if (job?.isActive == true || _state.value.planning != null) return
        job = viewModelScope.launch {
            try {
                val gemini = c.transcripts.available
                if (gemini && !captionsDone && _state.value.options.captions) {
                    _state.update { it.copy(planning = "Reading what you said", work = listOf(WorkStep("Reading what you said", 1))) }
                    withContext(Dispatchers.IO) { readCaptions() }
                    bits = withContext(Dispatchers.IO) { buildBits() }
                    refreshSources()
                }
                _state.update { it.copy(planning = "Gemini is suggesting what to make", work = emptyList()) }
                val fs = withContext(Dispatchers.Default) { footage() }
                val cd = card ?: return@launch
                val scripts = (if (gemini) runCatching {
                    gemini().scripts(ScriptWriter.planPrompt(cd.title, cd.subtitle, fs, style(), PieceFormat.entries), fs)
                }.onFailure { c.errors.record("Studio content plan", "Gemini couldn't suggest content", it) }.getOrDefault(emptyList()) else emptyList())
                    .ifEmpty { localPieces(fs) }
                withContext(Dispatchers.IO) {
                    planFile.writeText(org.json.JSONObject().put("pieces", org.json.JSONArray().apply { scripts.forEach { put(ScriptJson.write(it)) } }).toString())
                }
                showPieces(scripts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio content plan", "Couldn't suggest content", e)
            } finally {
                _state.update { it.copy(planning = null, work = emptyList()) }
            }
        }
    }

    /** The app's own suggestions: a Reel as long as the footage fills well, and a 15 s Short. */
    private fun localPieces(fs: List<Footage>): List<Script> {
        val good = ScriptWriter.summary(fs).goodLengthSec
        return listOfNotNull(
            ScriptWriter.local(fs, PieceFormat.REEL, good, _state.value.title),
            ScriptWriter.local(fs, PieceFormat.SHORT, 15, _state.value.title)?.takeIf { good > 15 },
        )
    }

    /** Makes a suggestion as a new Reel (whatever is on screen stays saved). */
    fun makePiece(key: String) {
        if (job?.isActive == true) return
        val piece = _state.value.pieces.firstOrNull { it.key == key } ?: return
        pendingScript = piece.script
        _state.update { it.copy(piece = piece, reelId = null, takes = emptyList(), tips = null, coverFrames = emptyList(), title = card?.title ?: it.title) }
        make()
    }

    /** Makes every suggestion not made yet, in the background (a notification shows progress). */
    fun makeAll() {
        val made = c.reels.reels.value.filter { it.rideId == rideId.takeIf { !phoneOnly } && it.deletedAt == null }.mapNotNull { it.idea }.toSet()
        val s = _state.value
        val cd = card ?: return
        val jobs = s.pieces.filter { it.script.title !in made }.map { pc ->
            val sc = pc.script
            val project = ReelProject(
                id = c.reels.newId(), rideId = rideId.takeIf { !phoneOnly }, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                title = sc.title, series = s.series, episode = s.episode, hookLine = sc.hookLine ?: sc.sections.firstOrNull()?.text.orEmpty(),
                postCaption = sc.postCaption ?: fallbackCaption(), story = null, options = s.options, musicUri = s.musicUri?.toString(), musicName = s.musicName,
                plan = pc.plan, takes = emptyList(), tips = emptyList(), durationMs = pc.plan.totalMs, idea = sc.title, script = sc,
            )
            MakeJob(project, renderInput(pc.plan, s.copy(title = sc.title, hookLine = project.hookLine), cd, voice = null))
        }
        c.reelMaker.enqueue(jobs)
        say(if (jobs.isEmpty()) "All suggestions are made already" else "Making ${jobs.size} in the background. They'll be in Your Reels.")
    }

    fun cancelMakeAll() = c.reelMaker.cancelAll()

    /** A new Reel with the choices below (the one on screen stays saved). */
    fun makeNew() {
        _state.update { it.copy(piece = null, reelId = null, takes = emptyList(), tips = null, coverFrames = emptyList()) }
        make()
    }

    /** The Reel on screen, made again with the choices below. */
    fun makeAgain() {
        _state.update { it.copy(piece = null) }
        make()
    }

    // ---- saved Reels -----------------------------------------------------------------------

    /** Shows a saved Reel, with everything it was made with, ready to change and make again. */
    private suspend fun open(p: ReelProject) {
        // Clips from other rides need their files to be made again.
        val missing = p.plan.clips.filter { it.bit.source == null }.map { it.bit.momentId }.filter { id -> clips.none { it.id == id } }.toSet()
        if (missing.isNotEmpty()) c.moments.all().filter { it.id in missing }.forEach { borrowed[it.id] = it }
        val gallery = p.plan.clips.mapNotNull { it.bit.source }.distinct().filter { u -> phone.values.none { it.uri.toString() == u } }
        if (gallery.isNotEmpty()) {
            withContext(Dispatchers.IO) { gallery.mapNotNull { PhoneVideos.read(c.appContext, Uri.parse(it)) } }.forEach { phone[it.id] = it }
            bits = withContext(Dispatchers.IO) { buildBits() }
            refreshSources()
        }
        captionsDone = true
        _state.update {
            it.copy(
                reelId = p.id,
                step = StudioStep.READY,
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
                video = c.reels.video(p.id),
                thumbs = it.thumbs + borrowed.values.associate { m -> m.id to m.thumb },
            )
        }
    }

    /** Saves the Reel just made (a new one, or the one being changed) with its whole edit. */
    private suspend fun persist(video: File) {
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
            journalCover = old?.journalCover ?: false,
            coverAtMs = old?.coverAtMs,
            coverText = old?.coverText ?: true,
            coverLine = old?.coverLine,
            coverRoute = old?.coverRoute ?: false,
            idea = old?.idea ?: s.piece?.script?.title,
            script = s.script,
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

    /** Every clip's video by moment id: moments' files and gallery videos. */
    private fun files(): Map<String, Uri> =
        (clips + borrowed.values).associate { it.id to Uri.fromFile(it.file) } + phone.values.associate { it.id to it.uri }

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
    }

    fun editDraft(f: (Script) -> Script) = _state.update { s -> s.draft?.let { s.copy(draft = f(it)) } ?: s }
    fun setNote(t: String) = _state.update { it.copy(note = t) }
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
        val before = s.script ?: ScriptEdits.fromPlan(s.plan ?: return, s.title)
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
    }

    fun setMusic(uri: Uri?) {
        val name = uri?.let { u ->
            runCatching {
                c.appContext.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
            }.getOrNull()?.substringBeforeLast('.') ?: "Your song"
        }
        uri?.let { runCatching { c.appContext.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        _state.update { it.copy(musicUri = uri, musicName = name) }
    }

    fun setSeries(name: String) {
        c.studio.series = name
        _state.update { it.copy(series = name) }
    }

    fun back() = _state.update {
        it.copy(step = if (it.step in setOf(StudioStep.EDIT, StudioStep.VOICE, StudioStep.COVER, StudioStep.SCRIPT)) StudioStep.READY else StudioStep.SETUP, error = null, draft = null)
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
                    WorkStep("Writing the script", 0),
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
                // Script first: a suggestion's script, or Gemini writes one (or the app's calm fallback); then the app checks it.
                val sc = pendingScript ?: writeScript(avoid = remixFrom)
                pendingScript = null
                remixFrom = null
                applyScript(sc)
                val s = _state.value
                step(1, 2, listOfNotNull(s.script?.title?.let { "“$it”" } ?: s.story?.let { "“$it”" }, "${s.plan?.clips?.size} clips · ${Format.clock(s.plan?.totalMs ?: 0)}").joinToString(" · "))
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

    /** The clips as the script writer sees them: short keys in filming order, how each looks, what was said. */
    private fun footage(): List<Footage> {
        val out = _state.value.excluded
        val list = clips.filter { it.id !in out }.map { m ->
            val dur = m.durationMillis ?: 0
            Footage(
                key = "", momentId = m.id, durationMs = dur, startMillis = m.videoStartMillis,
                look = if (m.camera == "back") "road" else "selfie",
                lines = StudioText.load(m.file).orEmpty(),
                kmhMax = (0..(dur / 1000).toInt()).maxOfOrNull { s -> speedAt(m.videoStartMillis + s * 1000L) }?.coerceAtLeast(0) ?: 0,
                events = m.types.filter { it != RideEventType.VOICE }.joinToString { it.name.lowercase().replace('_', ' ') },
                label = Format.timeOfDay(m.videoStartMillis),
            )
        } + phone.values.filter { it.id !in out }.map { p ->
            Footage(
                key = "", momentId = p.id, durationMs = p.durationMs, startMillis = p.startMillis, look = "phone",
                lines = StudioText.load(PhoneVideos.captionKey(c.appContext, p.id)).orEmpty(),
                kmhMax = (0..(p.durationMs / 1000).toInt()).maxOfOrNull { s -> speedAt(p.startMillis + s * 1000L) }?.coerceAtLeast(0) ?: 0,
                label = "phone video",
            )
        }
        return list.sortedBy { it.startMillis }.mapIndexed { i, f -> f.copy(key = "c${i + 1}") }
    }

    /** Gemini's script for the chosen length (one request), or the app's own when Gemini can't. */
    private suspend fun writeScript(avoid: Script?): Script {
        val fs = withContext(Dispatchers.Default) { footage() }
        val o = _state.value.options
        val cd = card
        if (c.transcripts.available && cd != null) {
            val prompt = ScriptWriter.piecePrompt(cd.title, cd.subtitle, fs, style(), PieceFormat.REEL, o.lengthSec, avoid, ScriptWriter.keys(fs))
            runCatching { gemini().scripts(prompt, fs).firstOrNull() }
                .onFailure { c.errors.record("Studio script", "Gemini couldn't write the script", it) }
                .getOrNull()?.let { return it.copy(lengthSec = minOf(it.lengthSec, o.lengthSec)) }
        }
        return ScriptWriter.local(fs, PieceFormat.REEL, o.lengthSec, _state.value.title) ?: error("No usable clips")
    }

    /** Turns [sc] into the plan (the app's checks fix what Gemini got wrong) and shows it. */
    private suspend fun applyScript(sc: Script) {
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
                hookLine = used.hookLine ?: hook?.text ?: it.hookLine,
                postCaption = used.postCaption ?: fallbackCaption(),
                content = ScriptWriter.summary(fs).text,
            )
        }
    }

    /** What Studio has learnt about the rider's style (see Your style). */
    private fun style(): StyleContext? = c.style.context()

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
        history = EditHistory(TimelineEdits.liftTexts(plan) { newTextId() })
        _state.update { it.copy(step = StudioStep.EDIT, pick = null, footage = footage()) }
        publish(reload = true)
        if (_state.value.otherBits.isEmpty()) viewModelScope.launch { loadOtherRides() }
    }

    private fun newTextId() = "t" + java.util.UUID.randomUUID().toString().take(8)

    private fun publish(reload: Boolean) {
        val h = history ?: return
        _state.update { it.copy(timeline = h.current, canUndo = h.canUndo, canRedo = h.canRedo, timelineVersion = it.timelineVersion + if (reload) 1 else 0) }
    }

    /** One change on the timeline (one undo step). */
    fun change(f: (StudioPlan) -> StudioPlan) {
        val h = history ?: return
        h.apply(f(h.current))
        publish(reload = true)
    }

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

    fun addText(text: String) = change { TimelineEdits.addText(it, playheadMs, text, newTextId()) }
    fun addCaption(text: String) = change { TimelineEdits.addCaption(it, playheadMs, text) }
    fun insert(b: Bit) = change { TimelineEdits.insert(it, b, playheadMs) }

    /** Parts of this ride (and the other rides Studio read) that aren't in the edit yet. */
    fun insertable(): List<Bit> {
        val used = _state.value.timeline?.clips?.map { it.bit.momentId }.orEmpty().toSet()
        return bits.filter { it.momentId !in used } + _state.value.otherBits.filter { it.momentId !in used }
    }

    /** Leaves the editor without keeping the changes. */
    fun closeEdit() {
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
        val result = StudioRenderer(c.appContext).render(input, out) { p -> _state.update { it.copy(renderProgress = p) } }
        val done = result.getOrThrow()
        persist(done.file)
        // Attempts that failed before one worked are worth knowing about too.
        if (done.failures.isNotEmpty()) {
            val e = StudioRenderer.ExportFailed("Made on attempt ${done.failures.size + 1}").also { x -> done.failures.forEach { x.addSuppressed(it) } }
            c.errors.record("Studio export", "Needed a fallback: ${done.note ?: "no note"}", e, describePlan(plan))
        }
        step(idx, 2)
        _state.update { it.copy(step = StudioStep.READY, renderProgress = null, notes = notes + listOfNotNull(done.note)) }
        coach()
    }

    /** What the renderer needs for [plan] with the choices in [s]. */
    private fun renderInput(plan: StudioPlan, s: StudioState, baseCard: RideCard, voice: File?): RenderInput {
        val ride = samples.toList()
        return RenderInput(
            plan = plan,
            files = files(),
            card = baseCard.copy(title = s.title.ifBlank { baseCard.title }),
            options = s.options,
            speedAt = { StudioNumbers.speedAt(ride, it) },
            clockAt = { Format.timeOfDay(it).lowercase(Locale.getDefault()) },
            music = s.musicUri,
            opener = StudioArt.Opener(label = if (s.options.intro) s.label.takeIf { s.series.isNotBlank() } else s.label, hookLine = s.hookLine.ifBlank { null }),
            voice = voice,
            voiceLines = s.takes.flatMap { t -> t.lines.map { it.copy(startMs = it.startMs + t.startMs, endMs = it.endMs + t.startMs) } },
        )
    }

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

    // ---- captions and the director ---------------------------------------------------------

    /**
     * Asks Gemini for timed lines for the clips that likely have talking, several clips per request
     * (the free tier allows few requests a day); returns a note if some were missed.
     */
    private suspend fun readCaptions(): String? {
        val out = _state.value.excluded
        val moments = clips.filter { it.id !in out && likelyTalking(it) && StudioText.load(it.file) == null }
            .sortedByDescending { (if (RideEventType.VOICE in it.types) 2 else 0) + (it.transcript?.length ?: 0).coerceAtMost(200) / 100 }
            .map { m ->
                CaptionJob(m.file.name, m.file, { f -> ClipAudio.extract(m.file, f) }) { lines ->
                    // The words also become the clip's transcript (shown in the ride and the clip viewer).
                    if (m.transcript == null) c.moments.setTranscript(m.id, lines.joinToString(" ") { it.text })
                }
            }
        // Phone videos are filmed on purpose: always worth reading.
        val gallery = phone.values.filter { it.id !in out && StudioText.load(PhoneVideos.captionKey(c.appContext, it.id)) == null }
            .map { p -> CaptionJob("phone ${p.id}", PhoneVideos.captionKey(c.appContext, p.id), { f -> ClipAudio.extract(c.appContext, p.uri, f) }) {} }
        val todo = (gallery + moments).take(MAX_CAPTION_CLIPS)
        if (todo.isEmpty()) return null
        val g = gemini()
        var missed = 0
        var note: String? = null
        var done = 0
        for (batch in todo.chunked(BATCH)) {
            if (skipCaptions) break
            step(0, 1, "${done + 1}–${done + batch.size} of ${todo.size} clips")
            val audios = batch.mapIndexed { k, j -> j to File(c.appContext.cacheDir, "studio-cap-$done-$k.m4a") }
            try {
                val usable = audios.filter { (j, f) -> j.extract(f) && f.length() <= MAX_AUDIO_BYTES / 2 }
                audios.filter { it !in usable }.forEach { (j, _) -> StudioText.save(j.key, emptyList()) }
                val results = if (usable.isEmpty()) emptyList() else g.captionsBatch(usable.map { it.second })
                usable.forEachIndexed { i, (j, _) ->
                    val lines = results.getOrNull(i)
                    if (lines == null) { missed++; return@forEachIndexed }
                    StudioText.save(j.key, lines)
                    j.saved(lines)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio captions", "Couldn't read ${batch.size} clips (${batch.joinToString { it.name }})", e)
                missed += batch.size
                note = when {
                    AppCheckSetup.isRejected(e) -> "Captions: Firebase didn't accept this phone (App Check). Add the debug token from Profile › Moments in Firebase."
                    e is FirebaseTranscriber.Busy -> "Captions were skipped: Gemini's free limit is used up. Remix once it's free again."
                    else -> "Captions: couldn't read $missed clip${if (missed > 1) "s" else ""} (${e.message?.take(80)})."
                }
                // Out of allowance or refused: more requests won't help now.
                if (AppCheckSetup.isRejected(e) || e is FirebaseTranscriber.Busy) break
            } finally {
                audios.forEach { it.second.delete() }
            }
            done += batch.size
        }
        captionsDone = !skipCaptions && missed == 0
        return note
    }

    /** One clip whose words Gemini reads: [key] is where its captions are kept. */
    private class CaptionJob(val name: String, val key: File, val extract: (File) -> Boolean, val saved: suspend (List<CaptionLine>) -> Unit)

    private fun gemini() = StudioGemini(preferred = { c.transcripts.model }, onWorking = { c.transcripts.model = it })

    /** Voice-triggered, filmed on purpose, or already known to have words. */
    private fun likelyTalking(m: Moment) =
        RideEventType.VOICE in m.types || m.source == MomentSource.MANUAL || !m.transcript.isNullOrBlank()

    private fun buildBits(): List<Bit> {
        val out = _state.value.excluded
        return clips.filter { it.id !in out }.flatMap { m ->
            val dur = m.durationMillis ?: 0
            val lines = StudioText.load(m.file).orEmpty()
            val focus = (m.timeMillis - m.videoStartMillis).coerceIn(0, dur)
            StudioPlanner.bitsOf(m.id, m.videoStartMillis, dur, lines, { t -> speedAt(t).coerceAtLeast(0).toDouble() }, focus).map { it.copy(camera = m.camera) }
        } + phone.values.filter { it.id !in out }.flatMap { p ->
            PhoneVideos.bits(p, StudioText.load(PhoneVideos.captionKey(c.appContext, p.id)).orEmpty()) { t -> speedAt(t).coerceAtLeast(0).toDouble() }
        }
    }

    // ---- ride numbers ----------------------------------------------------------------------

    /** km/h at [wall]; -1 when there's no speed for that moment (a gap in GPS): no badge then. */
    private fun speedAt(wall: Long): Int = StudioNumbers.speedAt(samples, wall)

    private fun rideCard(name: String, start: Long, distanceM: Double, durMs: Long, topMps: Double?, moments: Int): RideCard {
        val zone = ZoneId.systemDefault()
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(start).atZone(zone))
        // The ride's saved stats can cover only part of it (a ride that survived the app dying):
        // take whichever is bigger, the stats or what the samples show, so the card agrees with the badges.
        val fromSamples = StudioNumbers.summary(samples)
        val km = String.format(Locale.US, "%.1f", maxOf(distanceM, fromSamples.distanceM) / 1000)
        val min = (maxOf(durMs, fromSamples.movingMs) / 60_000).coerceAtLeast(1)
        val topKmh = maxOf((topMps ?: 0.0) * 3.6, fromSamples.topKmh)
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
            stats = listOf(km to "km", "${topKmh.roundToInt()}" to "top km/h", "$min" to "minutes", "$moments" to "moments"),
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
        const val MAX_CAPTION_CLIPS = 32
        /** Clips read per Gemini request. */
        const val BATCH = 8
        const val MAX_AUDIO_BYTES = 14L * 1024 * 1024
    }
}
