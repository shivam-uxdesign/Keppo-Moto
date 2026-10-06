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

enum class StudioStep { SETUP, WORKING, READY, EDIT }

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
    val musicUri: Uri? = null,
    val musicName: String? = null,
    val step: StudioStep = StudioStep.SETUP,
    val work: List<WorkStep> = emptyList(),
    val renderProgress: Int? = null,
    /** Shown while Gemini reads clips: the rider can skip captions. */
    val canSkipCaptions: Boolean = false,
    val plan: StudioPlan? = null,
    val title: String = "",
    val postCaption: String = "",
    val video: File? = null,
    val notes: List<String> = emptyList(),
    val error: String? = null,
    /** Thumbnails by moment id, for Edit. */
    val thumbs: Map<String, File?> = emptyMap(),
)

/** Studio: turns a ride's clips into a Reel (captions and picks by Gemini when it can). */
@OptIn(UnstableApi::class)
class StudioViewModel(private val c: AppContainer, private val rideId: String) : ViewModel() {
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private var clips: List<Moment> = emptyList()
    private var samples: List<TelemetrySample> = emptyList()
    private var card: RideCard? = null
    private var bits: List<Bit> = emptyList()
    private var captionsDone = false
    private var direction: Direction? = null
    private var job: Job? = null
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

    fun back() = _state.update { it.copy(step = if (it.step == StudioStep.EDIT) StudioStep.READY else StudioStep.SETUP, error = null) }

    // ---- making ----------------------------------------------------------------------------

    /** Captions (Gemini) → picks (Gemini director) → plan → video. */
    fun make() {
        if (job?.isActive == true) return
        val o = _state.value.options
        val gemini = c.transcripts.available
        val needCaptions = o.captions && gemini && !captionsDone
        skipCaptions = false
        _state.update {
            it.copy(
                step = StudioStep.WORKING, error = null, video = null, renderProgress = null, notes = emptyList(),
                work = listOfNotNull(
                    WorkStep("Reading what you said", if (needCaptions) 0 else 3, if (!o.captions) "Captions are off" else if (!gemini) "Not available in this build" else if (captionsDone) "Done earlier" else null),
                    WorkStep("Picking your best moments", 0),
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
                if (gemini && direction == null) direction = runCatching { direct() }.getOrNull()
                val d = direction
                val scored = bits.map { b -> b.copy(punch = if (b.id == d?.hookId) 10f else d?.punch?.get(b.id) ?: b.punch) }
                val plan = StudioPlanner.plan(scored, _state.value.options)
                if (plan.clips.isEmpty()) error("No usable clips")
                val title = d?.title ?: _state.value.title
                _state.update { it.copy(plan = plan, title = title, postCaption = d?.postCaption ?: fallbackCaption(), lengths = StudioPlanner.lengthsFor(bits, it.options.vibe)) }
                step(1, 2, "${plan.clips.size} clips · ${Format.clock(plan.totalMs)}")
                render(notes)
            } catch (e: CancellationException) {
                _state.update { it.copy(step = StudioStep.SETUP, canSkipCaptions = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = StudioStep.SETUP, canSkipCaptions = false, error = "Couldn't make the video (${e.message ?: e.javaClass.simpleName})") }
            }
        }
    }

    /** A new take from the same moments (no Gemini calls). */
    fun remix() {
        val s = _state.value
        if (s.plan == null || job?.isActive == true) return
        setOptions { it.copy(seed = it.seed + 1) }
        make()
    }

    fun skipCaptions() { skipCaptions = true }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(step = StudioStep.SETUP, renderProgress = null) }
    }

    // ---- edit ------------------------------------------------------------------------------

    fun edit() = _state.update { it.copy(step = StudioStep.EDIT) }
    fun setTitle(t: String) = _state.update { it.copy(title = t) }
    fun nudge(i: Int, start: Long, end: Long) = editPlan { StudioPlanner.nudge(it, i, start, end) }
    fun setText(i: Int, text: String) = editPlan { StudioPlanner.setText(it, i, text) }
    fun move(i: Int, by: Int) = editPlan { StudioPlanner.move(it, i, by) }
    fun remove(i: Int) = editPlan { StudioPlanner.remove(it, i) }
    fun swap(i: Int) = editPlan { StudioPlanner.swap(it, i, bits) }
    private fun editPlan(f: (StudioPlan) -> StudioPlan) = _state.update { s -> s.plan?.let { s.copy(plan = f(it)) } ?: s }

    /** Done editing: make the video again from the edited plan. */
    fun remake() {
        if (job?.isActive == true) return
        _state.update {
            it.copy(step = StudioStep.WORKING, video = null, error = null, work = listOf(WorkStep("Your changes", 2), WorkStep("Making the video", 0)))
        }
        job = viewModelScope.launch {
            try {
                render(ArrayList())
            } catch (e: CancellationException) {
                _state.update { it.copy(step = StudioStep.EDIT) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = StudioStep.EDIT, error = "Couldn't make the video (${e.message ?: e.javaClass.simpleName})") }
            }
        }
    }

    private suspend fun render(notes: List<String>) {
        val s = _state.value
        val plan = s.plan ?: return
        val idx = s.work.lastIndex
        step(idx, 1)
        val baseCard = card ?: return
        val input = RenderInput(
            plan = plan,
            files = clips.associate { it.id to it.file },
            card = baseCard.copy(title = s.title.ifBlank { baseCard.title }),
            options = s.options,
            speedAt = ::speedAt,
            clockAt = { Format.timeOfDay(it).lowercase(Locale.getDefault()) },
            music = s.musicUri,
        )
        val out = File(ShareImages.sharesDir(c.appContext), "keppo-reel-${System.currentTimeMillis()}.mp4")
        val result = StudioRenderer(c.appContext).render(input, out) { p -> _state.update { it.copy(renderProgress = p) } }
        val file = result.getOrThrow()
        step(idx, 2)
        _state.update { it.copy(step = StudioStep.READY, video = file, renderProgress = null, notes = notes) }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                missed++
                note = when {
                    AppCheckSetup.isRejected(e) -> "Captions: Firebase didn't accept this phone (App Check). Add the debug token from Profile › Moments in Firebase."
                    e is FirebaseTranscriber.Busy -> "Captions: Gemini is busy right now, so some clips have no captions. Remix later to try again."
                    else -> "Captions: couldn't read ${missed} clip${if (missed > 1) "s" else ""} (${e.message?.take(80)})."
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
    }

    private companion object {
        const val MAX_CAPTION_CLIPS = 12
        const val BETWEEN_MS = 4_000L
        const val MAX_AUDIO_BYTES = 14L * 1024 * 1024
    }
}
