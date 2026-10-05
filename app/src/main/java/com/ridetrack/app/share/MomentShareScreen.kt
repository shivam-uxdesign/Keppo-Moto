package com.ridetrack.app.share

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Movie
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
import com.ridetrack.app.ui.components.Label
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
    val frames: List<Bitmap> = emptyList(),
    /** Cutting the plain clip for "Original": null = idle, 0..100 = running. */
    val originalProgress: Int? = null,
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
            val prefs = c.settings.settings.first()
            val probe = overlayAt(moment, moment.timeMillis)
            _state.update {
                it.copy(
                    loading = false,
                    moment = moment,
                    fields = prefs.momentShareFields,
                    choice = OverlayChoice.Moment(prefs.momentShareLayout),
                    available = MomentField.entries.filter { f -> probe.shows(f, MomentField.DEFAULT) }.toSet(),
                )
            }
            render()
            renderThumbs()
            if (moment.kind == MomentKind.CLIP) loadTrim(moment)
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

/** Share a moment with its details burned in: photo or video, with each detail on or off. */
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader(if (isClip) "Share clip" else "Share photo", onBack = onBack)
        val choices = OverlayChoice.all
        val pager = rememberPagerState(initialPage = choices.indexOf(s.choice).coerceAtLeast(0)) { choices.size }
        LaunchedEffect(pager.settledPage) { vm.setChoice(choices[pager.settledPage]) }
        LaunchedEffect(s.loading) { if (!s.loading) pager.scrollToPage(choices.indexOf(s.choice).coerceAtLeast(0)) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                !s.loading && m == null -> Text("Moment not found", style = RtType.body, color = RtColors.TextSecondary)
                s.loading -> CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp)
                // Swipe left or right to change the graphic.
                else -> HorizontalPager(pager, Modifier.fillMaxSize(), key = { choices[it].key }) { page ->
                    val choice = choices[page]
                    val p = s.previews[choice.key] ?: s.thumbs[choice.key]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (p == null) {
                            CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp)
                        } else {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(p.width.toFloat() / p.height)
                                    .clip(RoundedCornerShape(18.dp))
                                    .border(1.dp, RtColors.Hairline, RoundedCornerShape(18.dp)),
                            ) {
                                if (s.overlayOnly && !isClip && choice.transparent) SamplePhoto(Modifier.fillMaxSize())
                                Image(p.asImageBitmap(), "${choice.label} preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                if (isClip && choice is OverlayChoice.Moment) {
                                    Text(
                                        "Numbers move with the video",
                                        style = RtType.caption,
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 10.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
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

        s.trim?.let { t ->
            TrimBar(
                trim = t,
                frames = s.frames,
                onStart = vm::setTrimStart,
                onEnd = vm::setTrimEnd,
                onDone = vm::saveTrim,
                onReset = vm::resetTrim,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Text(
            "${s.choice.label} · ${s.choice.note} · swipe for more",
            style = RtType.caption,
            color = RtColors.TextSecondary,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
        )
        LayoutPicker(s.thumbs, s.choice, onSelect = { ch -> scope.launch { pager.animateScrollToPage(choices.indexOf(ch)) } }, sampleBehind = false)
        Spacer(Modifier.height(10.dp))
        val momentLayout = s.choice is OverlayChoice.Moment
        Label(if (momentLayout) "Show" else "Show (moment layouts only; ride layouts show the whole ride)")
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
        if (!isClip && m != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { vm.setOverlayOnly(!s.overlayOnly) }
                    .padding(vertical = 4.dp),
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
        s.error?.let { Text(it, style = RtType.caption, color = RtColors.Error, modifier = Modifier.padding(top = 6.dp)) }

        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val name = "ridetrack-moment-${m?.timeMillis ?: 0}"
            when {
                m == null -> Unit
                isClip && s.exportProgress != null -> Column(Modifier.weight(1f)) {
                    Text("Creating video… ${s.exportProgress}%", style = RtType.caption, color = RtColors.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (s.exportProgress ?: 0) / 100f },
                        color = RtColors.Primary,
                        trackColor = RtColors.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                isClip && s.originalProgress != null -> Column(Modifier.weight(1f)) {
                    Text("Cutting the clip… ${s.originalProgress}%", style = RtType.caption, color = RtColors.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (s.originalProgress ?: 0) / 100f },
                        color = RtColors.Primary,
                        trackColor = RtColors.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                isClip && s.exported == null -> Action("Create video", Icons.Outlined.Movie, primary = true, modifier = Modifier.weight(1f)) { vm.exportVideo() }
                isClip -> {
                    val file = s.exported!!
                    Action("Share", Icons.Outlined.IosShare, primary = true, modifier = Modifier.weight(1f)) {
                        ShareImages.share(context, ShareImages.uriFor(context, file), "video/mp4")
                    }
                    Action("Save", Icons.Outlined.Download) {
                        scope.launch {
                            val ok = ShareImages.saveVideo(context, file, name)
                            if (ok) haptics.confirm()
                            toast = if (ok) "Saved to Movies/Keppo Moto" else "Couldn't save here. Use Share instead."
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
                            val ok = ShareImages.saveToPhotos(context, img, name, jpeg)
                            if (ok) haptics.confirm()
                            toast = if (ok) "Saved to Pictures/Keppo Moto" else "Couldn't save here. Use Share instead."
                        }
                    }
                }
            }
        }
        if (isClip && m != null && s.exportProgress == null && s.originalProgress == null) {
            // The clip as filmed: no numbers on it (just the trim, if any).
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Original", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                Action("Share", Icons.Outlined.IosShare) {
                    scope.launch {
                        val f = vm.original() ?: return@launch
                        ShareImages.share(context, ShareImages.uriFor(context, f), "video/mp4")
                    }
                }
                Action("Save", Icons.Outlined.Download) {
                    scope.launch {
                        val f = vm.original() ?: return@launch
                        val ok = ShareImages.saveVideo(context, f, "keppo-clip-${m.timeMillis}")
                        if (ok) haptics.confirm()
                        toast = if (ok) "Saved to Movies/Keppo Moto" else "Couldn't save here. Use Share instead."
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
            Row(Modifier.fillMaxSize()) {
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

