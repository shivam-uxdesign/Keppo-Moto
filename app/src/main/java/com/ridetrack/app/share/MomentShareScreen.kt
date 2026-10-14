package com.ridetrack.app.share

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.ClipWriter
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberHaptics
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.TelemetrySample
import com.ridetrack.telemetry.moments.telemetryAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MomentShareState(
    val loading: Boolean = true,
    val moment: Moment? = null,
    val fields: Set<MomentField> = MomentField.DEFAULT,
    /** Which graphic: a moment layout (live numbers) or a whole-ride layout. */
    val choice: OverlayChoice = OverlayChoice.Moment(MomentLayout.MINIMAL),
    /** Photos only: transparent overlay instead of the photo with the overlay. */
    val overlayOnly: Boolean = false,
    /** Full-size preview of the selected graphic, by [OverlayChoice.key]. */
    val previews: Map<String, Bitmap> = emptyMap(),
    /** Small previews for the picker, by [OverlayChoice.key]. */
    val thumbs: Map<String, Bitmap> = emptyMap(),
    /** Which details have data for this moment (the others are shown disabled). */
    val available: Set<MomentField> = emptySet(),
    /** Video export: null = idle, 0..100 = running. */
    val exportProgress: Int? = null,
    val exported: File? = null,
    val error: String? = null,
    /** Clips: the part to share, and frames along the clip for the trim strip. */
    val trim: TrimRange? = null,
    /** The two mics' mix in the shared video (Voice, Engine when there's an engine track, fades). */
    val sound: MomentSound = MomentSound(),
    val frames: List<Bitmap> = emptyList(),
    /** Cutting the plain clip for "Original": null = idle, 0..100 = running. */
    val originalProgress: Int? = null,
    /** Clips: the video's width ÷ height, upright (for the playing preview). */
    val aspect: Float? = null,
)

@androidx.annotation.OptIn(UnstableApi::class)
class MomentShareViewModel(private val c: AppContainer, private val rideId: String, private val momentId: String) : ViewModel() {
    private val _state = MutableStateFlow(MomentShareState())
    val state: StateFlow<MomentShareState> = _state.asStateFlow()

    private var samples: List<TelemetrySample> = emptyList()
    private var route: List<Pair<Double, Double>> = emptyList()
    private var rideName: String? = null
    private var demo = false
    private var background: Bitmap? = null
    private val renderer = MomentShareRenderer(c.appContext)
    private val rideRenderer by lazy { ShareCardRenderer(c.appContext) }
    private var rideCard: ShareCardData? = null
    private val rideGraphics = mutableMapOf<ShareStyle, Bitmap>()
    private var thumbJob: Job? = null
    private var renderJob: Job? = null
    private var exportJob: Job? = null

    init {
        viewModelScope.launch {
            c.moments.fillTopSpeeds(rideId, c.rides.track(rideId).samples)
            val moment = c.moments.forRide(rideId).firstOrNull { it.id == momentId }
            if (moment == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val ride = c.rides.get(rideId)
            rideName = ride?.name
            demo = ride?.source == DataSourceKind.DEMO
            samples = c.rides.track(rideId).samples
            if (ride != null) rideCard = ShareCardData.from(ride, c.bikes.get(ride.bikeId)?.displayName, samples)
            route = samples.mapNotNull { s -> s.latitude?.let { la -> s.longitude?.let { lo -> la to lo } } }
                .let { pts -> if (pts.size <= 400) pts else pts.filterIndexed { i, _ -> i % (pts.size / 400 + 1) == 0 } }
            background = withContext(Dispatchers.IO) { loadBackground(moment) }
            val aspect = background?.takeIf { moment.kind == MomentKind.CLIP && it.height > 0 }?.let { it.width.toFloat() / it.height }
            val prefs = c.settings.settings.first()
            val probe = overlayAt(moment, moment.timeMillis)
            _state.update {
                it.copy(
                    loading = false,
                    moment = moment,
                    fields = prefs.momentShareFields,
                    choice = OverlayChoice.Moment(prefs.momentShareLayout),
                    available = MomentField.entries.filter { f -> probe.shows(f, MomentField.DEFAULT) }.toSet(),
                    aspect = aspect,
                )
            }
            render()
            renderThumbs()
            if (moment.kind == MomentKind.CLIP) loadTrim(moment)
            // Two-mic clips: the engine mic joins the shared video at 70% (voice at 100%).
            if (moment.kind == MomentKind.CLIP && moment.engineFile.isFile) _state.update { it.copy(sound = it.sound.copy(engine = moment.engineFile, engineLevel = 0.7f)) }
        }
    }

    private suspend fun loadTrim(m: Moment) {
        val length = m.durationMillis ?: withContext(Dispatchers.IO) { clipLength(m.file) } ?: return
        _state.update { it.copy(trim = TrimRange.of(length, m.trimStartMillis, m.trimEndMillis)) }
        val frames = withContext(Dispatchers.IO) { stripFrames(m.file, length) }
        _state.update { it.copy(frames = frames) }
    }

    /** Moving a trim handle (while dragging); [saveTrim] when it's let go. */
    fun setTrimStart(ms: Long) = _state.update { s -> s.copy(trim = s.trim?.withStart(ms), exported = null) }
    fun setTrimEnd(ms: Long) = _state.update { s -> s.copy(trim = s.trim?.withEnd(ms), exported = null) }
    fun resetTrim() {
        _state.update { s -> s.copy(trim = s.trim?.reset(), exported = null) }
        saveTrim()
    }

    fun saveTrim() {
        val t = _state.value.trim ?: return
        viewModelScope.launch {
            if (t.isWhole) c.moments.setTrim(momentId, null, null) else c.moments.setTrim(momentId, t.startMillis, t.endMillis)
        }
    }

    /**
     * The plain clip (no overlay) for Share / Save original: the recorded file itself when
     * untrimmed, else the trimmed part cut into a new file.
     */
    suspend fun original(): File? {
        val s = _state.value
        val m = s.moment ?: return null
        val t = s.trim
        if (t == null || t.isWhole) return m.file
        // shares/ is cleared for each new file, so an overlay video made earlier is gone.
        _state.update { it.copy(originalProgress = 0, error = null, exported = null) }
        val out = File(ShareImages.sharesDir(c.appContext), "keppo-clip-${m.timeMillis}-${t.startMillis / 1000}.mp4")
        val result = MomentVideoExporter(c.appContext).export(
            input = m.file,
            output = out,
            videoStartMillis = m.videoStartMillis,
            draw = null,
            onProgress = { p -> _state.update { it.copy(originalProgress = p) } },
            trim = t,
        )
        _state.update {
            it.copy(
                originalProgress = null,
                error = result.exceptionOrNull()?.let { e -> "Couldn't cut the clip (${e.message ?: e.javaClass.simpleName})" },
            )
        }
        return result.getOrNull()
    }

    fun setSound(f: (MomentSound) -> MomentSound) = _state.update { it.copy(sound = f(it.sound), exported = null) }

    fun toggle(field: MomentField) = change { it.copy(fields = if (field in it.fields) it.fields - field else it.fields + field) }
    fun setChoice(choice: OverlayChoice) {
        if (choice == _state.value.choice) return
        change { it.copy(choice = choice) }
    }
    fun setOverlayOnly(on: Boolean) = change { it.copy(overlayOnly = on) }

    private fun change(f: (MomentShareState) -> MomentShareState) {
        val before = _state.value
        _state.update { f(it).copy(exported = null, error = null) }
        val s = _state.value
        val layout = (s.choice as? OverlayChoice.Moment)?.layout
        if (layout != null) viewModelScope.launch { c.settings.setMomentShare(s.fields, layout) }
        render()
        // Thumbnails depend on the details shown and the photo mode, not on which one is picked.
        if (before.fields != s.fields || before.overlayOnly != s.overlayOnly) renderThumbs()
    }

    fun overlayAt(m: Moment, timeMillis: Long): MomentOverlay = MomentOverlay(
        eventTypes = m.types,
        eventValue = m.peakValue,
        timeText = Format.timeOfDay(timeMillis),
        dateText = Format.rideDate(timeMillis).substringBefore(" ·"),
        rideName = rideName,
        point = telemetryAt(samples, timeMillis),
        topSpeedMps = m.topSpeedMps,
        route = route,
        demo = demo,
    )

    /** What the share would look like (for a clip: the frame at the moment itself). */
    private fun render() {
        val s = _state.value
        val m = s.moment ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(60)
            val img = withContext(Dispatchers.Default) { build(m, s) }
            _state.update { it.copy(previews = mapOf(s.choice.key to img)) }
        }
    }

    /** Small previews of every graphic for the picker, one at a time. */
    private fun renderThumbs() {
        val m = _state.value.moment ?: return
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch {
            OverlayChoice.all.forEach { choice ->
                val s = _state.value.copy(choice = choice)
                val thumb = withContext(Dispatchers.Default) {
                    val full = build(m, s)
                    Bitmap.createScaledBitmap(full, (full.width / 6).coerceAtLeast(1), (full.height / 6).coerceAtLeast(1), true).also { if (it !== full) full.recycle() }
                }
                _state.update { it.copy(thumbs = it.thumbs + (choice.key to thumb)) }
            }
        }
    }

    /** A whole-ride layout at story size (1080×1920), rendered once per style. */
    private fun rideGraphic(style: ShareStyle): Bitmap? {
        val d = rideCard ?: return null
        return synchronized(rideGraphics) { rideGraphics.getOrPut(style) { rideRenderer.render(d, style) } }
    }

    private fun build(m: Moment, s: MomentShareState): Bitmap {
        val bg = background
        val choice = s.choice
        if (choice is OverlayChoice.Ride) {
            val g = rideGraphic(choice.style) ?: return Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            return when {
                m.kind == MomentKind.PHOTO && s.overlayOnly || bg == null -> g.copy(Bitmap.Config.ARGB_8888, false)
                m.kind == MomentKind.PHOTO -> cropPhoto(bg, 1080, 1920).also { drawFrameGraphic(android.graphics.Canvas(it), it.width, it.height, g) }
                else -> bg.copy(Bitmap.Config.ARGB_8888, true).also { drawFrameGraphic(android.graphics.Canvas(it), it.width, it.height, g) }
            }
        }
        val layout = (choice as OverlayChoice.Moment).layout
        val o = overlayAt(m, m.timeMillis)
        return when {
            m.kind == MomentKind.PHOTO && s.overlayOnly -> renderer.overlayOnly(1080, 1920, o, s.fields, layout)
            bg != null && m.kind == MomentKind.PHOTO -> renderer.composePhoto(bg, 1080, 1920, o, s.fields, layout)
            bg != null -> {
                // Clip preview: the frame at its own size, overlay on top, like the export.
                val out = bg.copy(Bitmap.Config.ARGB_8888, true)
                renderer.draw(android.graphics.Canvas(out), out.width, out.height, o, s.fields, layout)
                out
            }
            else -> renderer.overlayOnly(1080, 1920, o, s.fields, layout)
        }
    }

    /** Photo: the moment photo. Clip: the frame at the moment, upright, at display size. */
    private fun loadBackground(m: Moment): Bitmap? = when (m.kind) {
        MomentKind.PHOTO -> ClipWriter.load(m.file, 2200)
        MomentKind.CLIP -> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(m.file.path)
                val at = (m.timeMillis - m.videoStartMillis).coerceAtLeast(0) * 1000
                val frame = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST)
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (frame != null && rot % 180 != 0 && frame.width > frame.height) {
                    Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                } else {
                    frame
                }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
    }

    /** The finished image for photos (JPEG with the photo, PNG when overlay-only). */
    suspend fun photoImage(): Bitmap? {
        val s = _state.value
        val m = s.moment ?: return null
        return withContext(Dispatchers.Default) { build(m, s) }
    }

    /**
     * The chosen graphic over the playing preview, at [wallMillis] (the clock time of the frame
     * shown), drawn the way the export draws it on each frame.
     */
    fun drawOverlay(canvas: android.graphics.Canvas, w: Int, h: Int, wallMillis: Long) {
        val s = _state.value
        val m = s.moment ?: return
        when (val choice = s.choice) {
            is OverlayChoice.Moment -> renderer.draw(canvas, w, h, overlayAt(m, wallMillis), s.fields, choice.layout)
            // Ride layouts are drawn once in the background; until then, nothing on top.
            is OverlayChoice.Ride -> synchronized(rideGraphics) { rideGraphics[choice.style] }?.let { drawFrameGraphic(canvas, w, h, it) }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _state.update { it.copy(exportProgress = null) }
    }

    /** Burns the overlay into the clip. */
    fun exportVideo() {
        val s = _state.value
        val m = s.moment ?: return
        if (m.kind != MomentKind.CLIP || exportJob?.isActive == true) return
        _state.update { it.copy(exportProgress = 0, exported = null, error = null) }
        exportJob = viewModelScope.launch {
            val out = File(ShareImages.sharesDir(c.appContext), "ridetrack-moment-${m.timeMillis}.mp4")
            val result = MomentVideoExporter(c.appContext).export(
                input = m.file,
                output = out,
                videoStartMillis = m.videoStartMillis,
                draw = when (val choice = s.choice) {
                    is OverlayChoice.Moment -> { canvas, w, h, t -> renderer.draw(canvas, w, h, overlayAt(m, t), s.fields, choice.layout) }
                    is OverlayChoice.Ride -> {
                        val g = rideGraphic(choice.style)
                        ({ canvas, w, h, _ -> if (g != null) drawFrameGraphic(canvas, w, h, g) })
                    }
                },
                onProgress = { p -> _state.update { it.copy(exportProgress = p) } },
                trim = s.trim,
                sound = s.sound,
            )
            _state.update {
                it.copy(
                    exportProgress = null,
                    exported = result.getOrNull(),
                    error = result.exceptionOrNull()?.let { e -> "Couldn't create the video (${e.message ?: e.javaClass.simpleName})" },
                )
            }
        }
    }
}

private fun clipLength(file: File): Long? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(file.path)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}

/** Small frames spread along the clip, for the trim strip. */
private fun stripFrames(file: File, lengthMillis: Long, count: Int = 8): List<Bitmap> {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(file.path)
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        (0 until count).mapNotNull { i ->
            val at = lengthMillis * (2 * i + 1) / (2 * count)
            val f = r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@mapNotNull null
            val scale = 160f / maxOf(f.width, f.height)
            val m = Matrix().apply {
                postScale(scale, scale)
                // Some phones hand back frames already upright; only turn sideways ones.
                if (rot % 180 != 0 && f.width > f.height) postRotate(rot.toFloat())
            }
            Bitmap.createBitmap(f, 0, 0, f.width, f.height, m, true).also { if (it !== f) f.recycle() }
        }
    } catch (e: Exception) {
        emptyList()
    } finally {
        runCatching { r.release() }
    }
}

/** The three panels under the preview, one at a time. */
private enum class ShareTab(val label: String) { LOOK("Look"), DETAILS("Details"), SOUND("Sound") }

/** What to do once the video is made. */
private enum class AfterMake { SHARE, SAVE }

/**
 * Share a moment with its details burned in. Clips play right here, with the chosen graphic
 * moving on top and the sound mix as set, so what you see and hear is what gets shared. Under
 * the preview: the trim strip with the playhead, then Look / Details / Sound, then Share and Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MomentShareScreen(rideId: String, momentId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "moment-share-$momentId") { MomentShareViewModel(it, rideId, momentId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_200)
            toast = null
        }
    }
    val m = s.moment
    val isClip = m?.kind == MomentKind.CLIP
    var tab by rememberSaveable { mutableStateOf(ShareTab.LOOK) }
    var menu by remember { mutableStateOf(false) }
    var after by remember { mutableStateOf<AfterMake?>(null) }
    val name = "ridetrack-moment-${m?.timeMillis ?: 0}"

    fun saved(ok: Boolean, where: String) {
        if (ok) haptics.confirm()
        toast = if (ok) "Saved to $where/Keppo Moto" else "Couldn't save here. Use Share instead."
    }
    fun shareVideo(file: File) = ShareImages.share(context, ShareImages.uriFor(context, file), "video/mp4")
    fun saveVideo(file: File) = scope.launch { saved(ShareImages.saveVideo(context, file, name), "Movies") }

    // The video is made with the settings as they are; then it's shared or saved. Made already
    // with these settings: straight away.
    fun make(what: AfterMake) {
        val ready = s.exported
        when {
            ready != null -> if (what == AfterMake.SHARE) shareVideo(ready) else saveVideo(ready)
            else -> { after = what; vm.exportVideo() }
        }
    }
    LaunchedEffect(s.exported) {
        val file = s.exported ?: return@LaunchedEffect
        when (after) {
            AfterMake.SHARE -> shareVideo(file)
            AfterMake.SAVE -> saveVideo(file)
            null -> Unit
        }
        after = null
    }
    LaunchedEffect(s.exportProgress, s.error) { if (s.exportProgress == null && s.exported == null) after = null }

    // ---- the player (clips): the clip itself, plus the engine mic beside it, kept in step
    val player = remember { ExoPlayer.Builder(context).build() }
    val enginePlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release(); enginePlayer.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.playWhenReady = false }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    LaunchedEffect(m?.id) {
        val clip = m?.takeIf { it.kind == MomentKind.CLIP } ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(clip.file)))
        player.prepare()
        if (clip.engineFile.isFile) {
            enginePlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(clip.engineFile)))
            enginePlayer.prepare()
        }
    }
    val trimNow by rememberUpdatedState(s.trim)
    val soundNow by rememberUpdatedState(s.sound)
    val mutedNow by rememberUpdatedState(muted)
    LaunchedEffect(player) {
        // Plays only the kept part, round and round, at the levels and fades that will be shared.
        while (true) {
            val t = trimNow
            var pos = player.currentPosition.coerceAtLeast(0)
            if (t != null && (pos >= t.endMillis || pos < t.startMillis - 250)) {
                player.seekTo(t.startMillis)
                pos = t.startMillis
            }
            positionMs = pos
            playing = player.playWhenReady
            val into = pos - (t?.startMillis ?: 0)
            val fade = soundNow.fade(into, t?.durationMillis ?: player.duration.coerceAtLeast(0))
            player.volume = if (mutedNow) 0f else (soundNow.voice * fade).coerceIn(0f, 1f)
            if (soundNow.engine != null) {
                enginePlayer.volume = if (mutedNow) 0f else (soundNow.engineLevel * fade).coerceIn(0f, 1f)
                if (enginePlayer.playWhenReady != player.playWhenReady) enginePlayer.playWhenReady = player.playWhenReady
                if (kotlin.math.abs(enginePlayer.currentPosition - pos) > 120) enginePlayer.seekTo(pos)
            } else {
                enginePlayer.volume = 0f
            }
            delay(33)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader(if (isClip) "Share clip" else "Share photo", onBack = onBack)

        // ---- the preview
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                !s.loading && m == null -> Text("Moment not found", style = RtType.body, color = RtColors.TextSecondary)
                s.loading -> CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp)
                isClip -> {
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(s.aspect ?: (9f / 16f), matchHeightConstraintsFirst = true)
                            .clip(shape)
                            .background(Color.Black)
                            .clickable(onClickLabel = "Play or pause", role = Role.Button) { player.playWhenReady = !player.playWhenReady },
                    ) {
                        AndroidView(
                            factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                            update = { it.player = player },
                            onRelease = { it.player = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // The graphic, drawn at the time of the frame showing (as the export does).
                        val wall = m!!.videoStartMillis + positionMs
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            drawIntoCanvas { vm.drawOverlay(it.nativeCanvas, size.width.toInt(), size.height.toInt(), wall) }
                        }
                        if (!playing) {
                            Box(
                                Modifier.align(Alignment.Center).size(56.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                        }
                        val t = s.trim
                        if (t != null) {
                            Text(
                                "${Format.clock((positionMs - t.startMillis).coerceAtLeast(0))} / ${Format.clock(t.durationMillis)}",
                                style = RtType.caption,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        IconButton(
                            onClick = { muted = !muted },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(40.dp),
                        ) {
                            Box(Modifier.size(30.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                    contentDescription = if (muted) "Sound on" else "Sound off",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                else -> {
                    val p = s.previews[s.choice.key] ?: s.thumbs[s.choice.key]
                    if (p == null) {
                        CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp)
                    } else {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(p.width.toFloat() / p.height, matchHeightConstraintsFirst = true)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, RtColors.Hairline, RoundedCornerShape(16.dp)),
                        ) {
                            if (s.overlayOnly && s.choice.transparent) SamplePhoto(Modifier.fillMaxSize())
                            Image(p.asImageBitmap(), "${s.choice.label} preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            toast?.let {
                Text(
                    it,
                    style = RtType.caption,
                    color = RtColors.TextPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .background(RtColors.SurfaceRaised, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // ---- the trim strip, with the playhead
        s.trim?.let { t ->
            TrimBar(
                trim = t,
                frames = s.frames,
                playheadMs = positionMs,
                onSeek = { ms -> player.seekTo(ms.coerceIn(t.startMillis, t.endMillis)); positionMs = ms },
                onStart = { ms -> vm.setTrimStart(ms); player.seekTo(ms.coerceAtLeast(0)) },
                onEnd = { ms -> vm.setTrimEnd(ms); player.seekTo((ms - 1_500).coerceAtLeast(t.startMillis)) },
                onDone = vm::saveTrim,
                onReset = vm::resetTrim,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // ---- Look / Details / Sound
        if (m != null) {
            val tabs = if (isClip) ShareTab.entries else listOf(ShareTab.LOOK, ShareTab.DETAILS)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).padding(3.dp),
            ) {
                tabs.forEach { t ->
                    val on = t == tab
                    Text(
                        t.label,
                        style = RtType.button,
                        color = if (on) RtColors.TextPrimary else RtColors.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) RtColors.SurfaceRaised else Color.Transparent)
                            .clickable(role = Role.Tab) { tab = t }
                            .padding(vertical = 8.dp),
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().height(156.dp).padding(top = 10.dp).verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    ShareTab.LOOK -> {
                        LayoutPicker(s.thumbs, s.choice, onSelect = vm::setChoice, sampleBehind = false)
                        Text(
                            "${s.choice.label} · ${s.choice.note}",
                            style = RtType.caption,
                            color = RtColors.TextSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (!isClip) {
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Switch) { vm.setOverlayOnly(!s.overlayOnly) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Overlay only (transparent, for your own photo)", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = s.overlayOnly,
                                    onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedTrackColor = RtColors.Primary, checkedThumbColor = RtColors.OnPrimary),
                                )
                            }
                        }
                    }
                    ShareTab.DETAILS -> {
                        val momentLayout = s.choice is OverlayChoice.Moment
                        Text(
                            if (momentLayout) "Shown on the ${if (isClip) "video. The numbers move as it plays" else "photo"}."
                            else "Whole-ride layouts show the ride's totals. Pick a moment layout in Look to choose numbers.",
                            style = RtType.caption,
                            color = RtColors.TextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MomentField.entries.forEach { f ->
                                val has = f in s.available && momentLayout
                                FilterChip(
                                    selected = f in s.fields && has,
                                    onClick = { vm.toggle(f) },
                                    enabled = has,
                                    label = { Text(f.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f),
                                        selectedLabelColor = RtColors.Primary,
                                        labelColor = RtColors.TextSecondary,
                                    ),
                                )
                            }
                        }
                    }
                    ShareTab.SOUND -> SoundMix(s.sound, vm::setSound)
                }
            }
        }
        s.error?.let { Text(it, style = RtType.caption, color = RtColors.Error, modifier = Modifier.padding(top = 6.dp)) }

        // ---- Share · Save · ⋮
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val busy = s.exportProgress?.let { "Creating video" to it } ?: s.originalProgress?.let { "Cutting the clip" to it }
            when {
                m == null -> Unit
                busy != null -> {
                    Column(Modifier.weight(1f)) {
                        Text("${busy.first}… ${busy.second}%", style = RtType.caption, color = RtColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { busy.second / 100f },
                            color = RtColors.Primary,
                            trackColor = RtColors.Surface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (s.exportProgress != null) Action("Cancel", Icons.Outlined.Close) { after = null; vm.cancelExport() }
                }
                isClip -> {
                    Action("Share", Icons.Outlined.IosShare, primary = true, modifier = Modifier.weight(1f)) { player.playWhenReady = false; make(AfterMake.SHARE) }
                    Action("Save", Icons.Outlined.Download) { player.playWhenReady = false; make(AfterMake.SAVE) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More", tint = RtColors.TextPrimary) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Text(
                                "The clip as filmed, without numbers",
                                style = RtType.caption,
                                color = RtColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            DropdownMenuItem(text = { Text("Share original") }, onClick = {
                                menu = false
                                scope.launch { vm.original()?.let(::shareVideo) }
                            })
                            DropdownMenuItem(text = { Text("Save original") }, onClick = {
                                menu = false
                                scope.launch {
                                    val f = vm.original() ?: return@launch
                                    saved(ShareImages.saveVideo(context, f, "keppo-clip-${m.timeMillis}"), "Movies")
                                }
                            })
                        }
                    }
                }
                else -> {
                    val jpeg = !s.overlayOnly
                    Action("Share", Icons.Outlined.IosShare, primary = true, modifier = Modifier.weight(1f)) {
                        scope.launch {
                            val img = vm.photoImage() ?: return@launch
                            ShareImages.share(context, ShareImages.toCacheUri(context, img, name, jpeg), if (jpeg) "image/jpeg" else "image/png")
                        }
                    }
                    Action("Save", Icons.Outlined.Download) {
                        scope.launch {
                            val img = vm.photoImage() ?: return@launch
                            saved(ShareImages.saveToPhotos(context, img, name, jpeg), "Pictures")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier
            .height(RtDimens.primaryButtonHeight)
            .clip(RoundedCornerShape(50))
            .background(if (primary) RtColors.Primary else RtColors.Surface)
            .border(1.dp, if (primary) Color.Transparent else RtColors.Hairline, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (primary) RtColors.OnPrimary else RtColors.TextPrimary
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = RtType.button, color = fg)
    }
}

/**
 * The trim strip: frames along the clip, with a handle at each end. Outside the kept part is
 * shaded. [onStart]/[onEnd] follow the drag; [onDone] when a handle is let go.
 */
@Composable
private fun TrimBar(
    trim: TrimRange,
    frames: List<Bitmap>,
    playheadMs: Long,
    onSeek: (Long) -> Unit,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(
                if (trim.isWhole) "Trim · whole clip (${Format.clock(trim.lengthMillis)})"
                else "Trim · ${Format.clock(trim.durationMillis)} of ${Format.clock(trim.lengthMillis)}",
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (!trim.isWhole) {
                Text(
                    "Reset",
                    style = RtType.caption,
                    color = RtColors.Primary,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onReset).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(RtColors.Surface),
        ) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val length = trim.lengthMillis.coerceAtLeast(1)
            fun toMs(x: Float) = (x / widthPx * length).toLong()
            val seek = rememberUpdatedState(onSeek)
            Row(
                Modifier.fillMaxSize().pointerInput(widthPx, length) {
                    // Tap or drag on the frames (not the handles) moves the playhead.
                    detectTapGestures { o -> seek.value(toMs(o.x)) }
                }.pointerInput(widthPx, length) {
                    detectHorizontalDragGestures { change, _ -> change.consume(); seek.value(toMs(change.position.x)) }
                },
            ) {
                frames.forEach { f ->
                    Image(f.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
            val startFrac = trim.startMillis.toFloat() / length
            val endFrac = trim.endMillis.toFloat() / length
            val density = LocalDensity.current
            val startDp = with(density) { (startFrac * widthPx).toDp() }
            val endDp = with(density) { (endFrac * widthPx).toDp() }
            // Shade what's cut away.
            Box(Modifier.width(startDp).fillMaxHeight().background(Color.Black.copy(alpha = 0.6f)))
            Box(Modifier.offset(x = endDp).width(maxWidth - endDp).fillMaxHeight().background(Color.Black.copy(alpha = 0.6f)))
            Box(
                Modifier
                    .offset(x = startDp)
                    .width((endDp - startDp).coerceAtLeast(0.dp))
                    .fillMaxHeight()
                    .border(2.dp, RtColors.Primary, RoundedCornerShape(10.dp)),
            )
            // The playhead: where the preview is.
            val headDp = with(density) { (playheadMs.toFloat() / length * widthPx).toDp() }
            Box(Modifier.offset(x = headDp - 1.5.dp).width(3.dp).fillMaxHeight().background(Color.White, RoundedCornerShape(2.dp)))
            TrimHandle(
                x = startDp,
                onDrag = { dx -> onStart(trim.startMillis + toMs(dx)) },
                onDone = onDone,
                label = "Trim start",
            )
            TrimHandle(
                x = endDp - 14.dp,
                onDrag = { dx -> onEnd(trim.endMillis + toMs(dx)) },
                onDone = onDone,
                label = "Trim end",
            )
        }
    }
}

@Composable
private fun TrimHandle(x: Dp, onDrag: (Float) -> Unit, onDone: () -> Unit, label: String) {
    val drag = rememberUpdatedState(onDrag)
    Box(
        Modifier
            .offset(x = x)
            .width(14.dp)
            .fillMaxHeight()
            .background(RtColors.Primary, RoundedCornerShape(6.dp))
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                // Each step moves the handle from where it is now.
                detectHorizontalDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, dx ->
                    change.consume()
                    drag.value(dx)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(16.dp).background(RtColors.OnPrimary, RoundedCornerShape(1.dp)))
    }
}

/**
 * Sound for the shared video: Mic 1 · Voice and (for two-mic clips) Mic 2 · Engine at their own
 * levels with a mute each, and how it fades in and out. The preview plays it as set.
 */
@Composable
private fun SoundMix(sound: MomentSound, onChange: ((MomentSound) -> MomentSound) -> Unit) {
    // The level before muting, to come back to.
    var voiceWas by remember { mutableStateOf(1f) }
    var engineWas by remember { mutableStateOf(0.7f) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MixLane("Voice", "Mic 1", sound.voice, RtColors.Primary, onValue = { v -> onChange { it.copy(voice = v) } }) {
            if (sound.voice > 0f) { voiceWas = sound.voice; onChange { it.copy(voice = 0f) } } else onChange { it.copy(voice = voiceWas) }
        }
        if (sound.engine != null) {
            MixLane("Engine", "Mic 2", sound.engineLevel, RtColors.Warning, onValue = { v -> onChange { it.copy(engineLevel = v) } }) {
                if (sound.engineLevel > 0f) { engineWas = sound.engineLevel; onChange { it.copy(engineLevel = 0f) } } else onChange { it.copy(engineLevel = engineWas) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FadeSlider("Fade in", sound.fadeInMs, Modifier.weight(1f)) { ms -> onChange { it.copy(fadeInMs = ms) } }
            FadeSlider("Fade out", sound.fadeOutMs, Modifier.weight(1f)) { ms -> onChange { it.copy(fadeOutMs = ms) } }
        }
    }
}

@Composable
private fun MixLane(name: String, mic: String, value: Float, tint: Color, onValue: (Float) -> Unit, onMute: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(64.dp)) {
            Text(name, style = RtType.body, color = RtColors.TextPrimary)
            Text(mic, style = RtType.caption, color = RtColors.TextTertiary)
        }
        androidx.compose.material3.Slider(
            value = value, onValueChange = onValue, valueRange = 0f..1.5f, modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
        )
        Text(
            "${(value * 100).roundToInt()}%",
            style = RtType.caption,
            color = RtColors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
        IconButton(onClick = onMute) {
            Icon(
                if (value > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = if (value > 0f) "Mute $name" else "Unmute $name",
                tint = if (value > 0f) RtColors.TextSecondary else RtColors.Warning,
            )
        }
    }
}

@Composable
private fun FadeSlider(label: String, ms: Long, modifier: Modifier, onValue: (Long) -> Unit) {
    Column(modifier) {
        Text("$label · ${"%.1f s".format(java.util.Locale.US, ms / 1000f)}", style = RtType.caption, color = RtColors.TextSecondary)
        androidx.compose.material3.Slider(
            value = ms / 1000f, onValueChange = { onValue((it * 10).roundToInt() * 100L) }, valueRange = 0f..3f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = RtColors.Primary, activeTrackColor = RtColors.Primary),
        )
    }
}
