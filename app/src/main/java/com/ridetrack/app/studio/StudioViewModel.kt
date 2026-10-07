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

enum class StudioStep { SETUP, WORKING, READY, EDIT, VOICE, COVER }

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
) {
    /** The small label on the first clip: the series and episode, or the title. */
    val label: String get() = if (series.isBlank()) title else "$series · ep $episode"
}

/** Studio: turns a ride's clips into a Reel (captions, stories and tips by Gemini when it can). */
@OptIn(UnstableApi::class)
class StudioViewModel(private val c: AppContainer, val rideId: String, private val openReelId: String? = null) : ViewModel() {
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
            openReelId?.let { id -> c.reels.get(id)?.let { open(it) } }
        }
    }

    // ---- saved Reels -----------------------------------------------------------------------

    /** Shows a saved Reel, with everything it was made with, ready to change and make again. */
    private suspend fun open(p: ReelProject) {
        // Clips from other rides need their files to be made again.
        val missing = p.plan.clips.map { it.bit.momentId }.filter { id -> clips.none { it.id == id } }.toSet()
        if (missing.isNotEmpty()) c.moments.all().filter { it.id in missing }.forEach { borrowed[it.id] = it }
        captionsDone = true
        _state.update {
            it.copy(
                reelId = p.id,
                step = StudioStep.READY,
                coverFrames = emptyList(),
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
        val project = ReelProject(
            id = id,
            rideId = rideId,
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
        )
        val saved = c.reels.save(project, video, s.takes)
        _state.update { it.copy(reelId = saved.id, video = c.reels.video(saved.id), takes = c.reels.takes(saved), coverFrames = emptyList()) }
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
    private suspend fun drawCover(p: ReelProject): ReelProject = withContext(Dispatchers.Default) {
        val ctx = c.appContext
        val at = p.coverAtMs ?: ReelCover.candidates(p.plan).mapNotNull { t ->
            sourceFrame(p.plan, t)?.let { b -> (t to ReelCover.score(b)).also { b.recycle() } }
        }.maxByOrNull { it.second }?.first ?: (p.durationMs / 6)
        val frame = sourceFrame(p.plan, at) ?: ReelStore.frame(c.reels.video(p.id), at)
        val route = if (p.coverRoute) routePicture() else null
        val line = if (p.coverText) (p.coverLine ?: p.hookLine.ifBlank { p.title }) else null
        val bmp = ReelCover.render(ctx, frame, route, p.vibe, line)
        withContext(Dispatchers.IO) { c.reels.writeCover(p.id, bmp) }
        bmp.recycle(); frame?.recycle(); route?.recycle()
        val saved = c.reels.update(p.id) { it.copy(coverAtMs = at) } ?: p
        _state.update { it.copy(coverVersion = it.coverVersion + 1) }
        saved
    }

    /** The source frame (not the finished Reel, so no captions) at [atMs] into the Reel. */
    private fun sourceFrame(plan: StudioPlan, atMs: Long): android.graphics.Bitmap? {
        val spot = ReelCover.spotAt(plan, atMs) ?: return null
        val file = (clips + borrowed.values).firstOrNull { it.id == spot.segment.bit.momentId }?.file
        return ReelCover.frame(c.appContext, file, spot.segment.bit.source, spot.sourceMs)
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

    fun setOptions(f: (StudioOptions) -> StudioOptions) = _state.update {
        val o = f(it.options)
        val lengths = StudioPlanner.lengthsFor(bits, o.vibe)
        it.copy(options = o.copy(lengthSec = if (o.lengthSec in lengths) o.lengthSec else lengths.last()), lengths = lengths)
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
        it.copy(step = if (it.step == StudioStep.EDIT || it.step == StudioStep.VOICE || it.step == StudioStep.COVER) StudioStep.READY else StudioStep.SETUP, error = null)
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
            music = s.musicUri,
            opener = StudioArt.Opener(label = if (s.options.intro) s.label.takeIf { s.series.isNotBlank() } else s.label, hookLine = s.hookLine.ifBlank { null }),
            voice = voice,
            voiceLines = s.takes.flatMap { t -> t.lines.map { it.copy(startMs = it.startMs + t.startMs, endMs = it.endMs + t.startMs) } },
        )
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
        val todo = clips.filter { likelyTalking(it) && StudioText.load(it.file) == null }
            .sortedByDescending { (if (RideEventType.VOICE in it.types) 2 else 0) + (it.transcript?.length ?: 0).coerceAtMost(200) / 100 }
            .take(MAX_CAPTION_CLIPS)
        if (todo.isEmpty()) return null
        val g = gemini()
        var missed = 0
        var note: String? = null
        var done = 0
        for (batch in todo.chunked(BATCH)) {
            if (skipCaptions) break
            step(0, 1, "${done + 1}–${done + batch.size} of ${todo.size} clips")
            val audios = batch.map { m -> m to File(c.appContext.cacheDir, "studio-${m.id}.m4a") }
            try {
                val usable = audios.filter { (m, f) -> ClipAudio.extract(m.file, f) && f.length() <= MAX_AUDIO_BYTES / 2 }
                audios.filter { it !in usable }.forEach { (m, _) -> StudioText.save(m.file, emptyList()) }
                val results = if (usable.isEmpty()) emptyList() else g.captionsBatch(usable.map { it.second })
                usable.forEachIndexed { i, (m, _) ->
                    val lines = results.getOrNull(i)
                    if (lines == null) { missed++; return@forEachIndexed }
                    StudioText.save(m.file, lines)
                    // The words also become the clip's transcript (shown in the ride and the clip viewer).
                    if (m.transcript == null) c.moments.setTranscript(m.id, lines.joinToString(" ") { it.text })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.errors.record("Studio captions", "Couldn't read ${batch.size} clips (${batch.joinToString { it.file.name }})", e)
                missed += batch.size
                note = when {
                    AppCheckSetup.isRejected(e) -> "Captions: Firebase didn't accept this phone (App Check). Add the debug token from Profile › Moments in Firebase."
                    e is FirebaseTranscriber.Busy -> "Captions: ${FirebaseTranscriber.busyMessage(e)} Remix later to try again."
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
        const val MAX_CAPTION_CLIPS = 16
        /** Clips read per Gemini request. */
        const val BATCH = 8
        const val MAX_AUDIO_BYTES = 14L * 1024 * 1024
    }
}
