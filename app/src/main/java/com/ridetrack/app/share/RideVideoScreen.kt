package com.ridetrack.app.share

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.common.positionAtSmooth
import com.ridetrack.app.ui.components.MapPin
import com.ridetrack.app.ui.components.RouteMap
import com.ridetrack.app.ui.detail.TrackData
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.moments.momentColor
import com.ridetrack.app.ui.moments.momentTitle
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberHaptics
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.RideEventType
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
import java.util.Locale
import kotlin.math.abs

data class RideVideoState(
    val loading: Boolean = true,
    val data: TrackData? = null,
    val moments: List<Moment> = emptyList(),
    val fromRide: Long = 0,
    val toRide: Long = 0,
    val speed: Int = 30,
    val clipSeconds: Int = 5,
    /** Moments the rider unticked. */
    val off: Set<String> = emptySet(),
    val layout: MomentLayout = MomentLayout.BAR,
    val fields: Set<MomentField> = setOf(MomentField.SPEED, MomentField.LEAN, MomentField.TIME, MomentField.BRAND),
    /** Rendering: null = idle, 0..100 = running. */
    val progress: Int? = null,
    val startedAt: Long = 0,
    val exported: File? = null,
    val error: String? = null,
) {
    val rideStart: Long get() = data?.startMillis ?: 0
    val rideEnd: Long get() = data?.let { it.startMillis + it.durationMillis } ?: 0

    fun plan(): RideVideoPlan? = if (toRide > fromRide) {
        RideVideoPlan.of(fromRide, toRide, speed, clipSeconds, moments.filter { it.id !in off }.map(::videoMoment))
    } else {
        null
    }
}

internal fun videoMoment(m: Moment) = VideoMoment(
    id = m.id,
    timeMillis = m.timeMillis,
    isClip = m.kind == MomentKind.CLIP,
    videoStartMillis = m.videoStartMillis,
    durationMillis = m.durationMillis ?: 20_000,
    label = momentTitle(m),
)

class RideVideoViewModel(private val c: AppContainer, private val rideId: String) : ViewModel() {
    private val _state = MutableStateFlow(RideVideoState())
    val state: StateFlow<RideVideoState> = _state.asStateFlow()
    private val renderer = MomentShareRenderer(c.appContext)
    private var rideName: String? = null
    private var demo = false
    private var route: List<Pair<Double, Double>> = emptyList()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val track = c.rides.track(rideId)
            val data = withContext(Dispatchers.Default) { TrackData(track) }
            c.moments.fillTopSpeeds(rideId, track.samples)
            val moments = c.moments.forRide(rideId).sortedBy { it.timeMillis }
            val ride = c.rides.get(rideId)
            rideName = ride?.name
            demo = ride?.source == DataSourceKind.DEMO
            route = data.route.map { it.latitude to it.longitude }.let { pts -> if (pts.size <= 400) pts else pts.filterIndexed { i, _ -> i % (pts.size / 400 + 1) == 0 } }
            val prefs = c.settings.settings.first()
            val start = data.startMillis
            val end = data.startMillis + data.durationMillis
            // Open on the part with the most clips (or the fastest stretch), ready to go.
            val range = RideVideoPlan.aroundMoments(moments.map(::videoMoment), 7 * MIN, start, end)
                ?: RideVideoPlan.bestStretch(data.samples, 5 * MIN)
                ?: start..end
            _state.update {
                it.copy(
                    loading = false,
                    data = data,
                    moments = moments,
                    fromRide = range.first,
                    toRide = range.last,
                    layout = prefs.momentShareLayout,
                    fields = prefs.momentShareFields - MomentField.MAP,
                )
            }
        }
    }

    private fun change(f: (RideVideoState) -> RideVideoState) = _state.update { f(it).copy(exported = null, error = null) }

    fun setTrim(from: Long, to: Long) = change {
        val a = from.coerceIn(it.rideStart, it.rideEnd - MIN_TRIM)
        val b = to.coerceIn(a + MIN_TRIM, it.rideEnd)
        it.copy(fromRide = a, toRide = b)
    }
    fun setSpeed(v: Int) = change { it.copy(speed = v) }
    fun setClipSeconds(v: Int) = change { it.copy(clipSeconds = v) }
    fun toggleMoment(id: String) = change { it.copy(off = if (id in it.off) it.off - id else it.off + id) }
    fun setLayout(l: MomentLayout) = change { it.copy(layout = l) }
    fun toggleField(f: MomentField) = change { it.copy(fields = if (f in it.fields) it.fields - f else it.fields + f) }

    fun bestFive() {
        val d = _state.value.data ?: return
        RideVideoPlan.bestStretch(d.samples, 5 * MIN)?.let { r -> change { it.copy(fromRide = r.first, toRide = r.last, speed = 16) } }
    }

    fun aroundMoments() {
        val s = _state.value
        RideVideoPlan.aroundMoments(s.moments.map(::videoMoment), 6 * MIN, s.rideStart, s.rideEnd)?.let { r -> change { it.copy(fromRide = r.first, toRide = r.last, speed = 30) } }
    }

    fun wholeRide() = change { it.copy(fromRide = it.rideStart, toRide = it.rideEnd, speed = 120) }

    /** The overlay at a moment of the finished video (the event's name shows during its clip). */
    fun overlayAt(s: RideVideoState, plan: RideVideoPlan, outMs: Long, rideTime: Long): MomentOverlay {
        val hold = plan.at(outMs)?.first as? VideoPart.Hold
        val m = hold?.let { h -> s.moments.firstOrNull { it.id == h.moment.id } }
        return MomentOverlay(
            eventTypes = m?.types.orEmpty(),
            eventValue = m?.peakValue,
            timeText = Format.timeOfDay(rideTime),
            dateText = Format.rideDate(rideTime).substringBefore(" ·"),
            rideName = rideName,
            point = s.data?.let { telemetryAt(it.samples, rideTime) },
            route = route,
            demo = demo,
            topSpeedMps = m?.topSpeedMps,
        )
    }

    fun previewOverlay(outMs: Long, w: Int, h: Int): Bitmap? {
        val s = _state.value
        val plan = s.plan() ?: return null
        val t = plan.rideTimeAt(outMs) ?: return null
        return renderer.overlayOnly(w, h, overlayAt(s, plan, outMs, t), s.fields, s.layout)
    }

    fun render() {
        val s = _state.value
        val data = s.data ?: return
        val plan = s.plan() ?: return
        if (job?.isActive == true) return
        _state.update { it.copy(progress = 0, startedAt = System.currentTimeMillis(), exported = null, error = null) }
        job = viewModelScope.launch {
            val out = File(ShareImages.sharesDir(c.appContext), "keppo-ride-${data.startMillis}.mp4")
            val files = s.moments.associate { it.id to it.file }
            val pins = s.moments.filter { it.id !in s.off && it.latitude != null && it.longitude != null && it.timeMillis in s.fromRide..s.toRide }
                .map { RideVideoRenderer.Pin(it.latitude!!, it.longitude!!, pinColor(it)) }
            val result = withContext(Dispatchers.Default) {
                RideVideoRenderer(c.appContext).render(
                    data, plan, files, pins,
                    drawOverlay = { canvas, w, h, rideTime ->
                        // Find the video time for this ride time's frame from the plan (holds keep their moment).
                        renderer.draw(canvas, w, h, overlayFor(s, plan, rideTime), s.fields, s.layout)
                    },
                    output = out,
                    onProgress = { p -> _state.update { it.copy(progress = p) } },
                )
            }
            _state.update {
                it.copy(
                    progress = null,
                    exported = result.getOrNull(),
                    error = result.exceptionOrNull()?.let { e ->
                        if (e is kotlinx.coroutines.CancellationException) null else "Couldn't create the video (${e.message ?: e.javaClass.simpleName})"
                    },
                )
            }
        }
    }

    /** During a clip the overlay names its event; elsewhere it shows the ride's numbers. */
    private fun overlayFor(s: RideVideoState, plan: RideVideoPlan, rideTime: Long): MomentOverlay {
        var t = 0L
        for (p in plan.parts) {
            if (p is VideoPart.Hold && p.moment.isClip && rideTime in p.moment.videoStartMillis + p.clipFrom..p.moment.videoStartMillis + p.clipTo) {
                return overlayAt(s, plan, t, rideTime)
            }
            t += p.outMillis
        }
        return overlayAt(s, plan, -1, rideTime)
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(progress = null) }
    }

    fun again() = _state.update { it.copy(exported = null) }

    private fun pinColor(m: Moment): Int = when {
        m.kind == MomentKind.PHOTO -> android.graphics.Color.WHITE
        RideEventType.HARD_BRAKE in m.types -> 0xFFFB7185.toInt()
        RideEventType.SIGNIFICANT_LEAN in m.types -> 0xFFA5A1FF.toInt()
        RideEventType.STRONG_ACCELERATION in m.types -> 0xFF4ADE80.toInt()
        else -> 0xFF69C8CB.toInt()
    }

    companion object {
        const val MIN = 60_000L
        private const val MIN_TRIM = 30_000L
        val SPEEDS = listOf(16, 30, 60, 120)
        val CLIP_CHOICES = listOf(0, 5, 10, RideVideoPlan.FULL_CLIP)
    }
}

/** Share ride › 3D video: pick the part, speed and moments, preview it, then render once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideVideoPanel(rideId: String, modifier: Modifier = Modifier) {
    val vm = appViewModel(key = "ride-video-$rideId") { RideVideoViewModel(it, rideId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val data = s.data
    if (s.loading || data == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp) }
        return
    }
    if (data.route.size < 2) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This ride has no GPS route to show.", style = RtType.body, color = RtColors.TextSecondary) }
        return
    }
    val plan = s.plan()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2_200); toast = null } }

    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Preview(vm, s, plan, data, Modifier.align(Alignment.CenterHorizontally))
            if (plan != null) {
                val over = plan.totalMillis > 60_000
                Text(
                    buildString {
                        append("Video ").append(Format.clock(plan.totalMillis))
                        append(" · ").append(Format.clock(plan.mapMillis)).append(" map at ").append(s.speed).append("×")
                        if (plan.clipCount > 0) append(" + ${plan.clipCount} clip${if (plan.clipCount > 1) "s" else ""}")
                        if (over) append(" · Stories cut at 1:00")
                    },
                    style = RtType.caption,
                    color = if (over) RtColors.Warning else RtColors.TextSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            Section("Part of the ride") {
                TrimStrip(s, data, onTrim = vm::setTrim)
                Row(Modifier.fillMaxWidth()) {
                    Text(Format.timeOfDay(s.fromRide), style = RtType.caption, color = RtColors.TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Text("${Format.clock(s.toRide - s.fromRide)} of ride", style = RtType.caption, color = RtColors.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Text(Format.timeOfDay(s.toRide), style = RtType.caption, color = RtColors.TextSecondary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice("Best 5 min", false) { vm.bestFive() }
                    if (s.moments.any { it.kind == MomentKind.CLIP }) Choice("Around moments", false) { vm.aroundMoments() }
                    Choice("Whole ride", false) { vm.wholeRide() }
                }
            }
            Section("Speed") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RideVideoViewModel.SPEEDS.forEach { v ->
                        Choice("${v}×  ${Format.clock((s.toRide - s.fromRide) / v)}", s.speed == v) { vm.setSpeed(v) }
                    }
                }
            }
            Section("Moment clips") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RideVideoViewModel.CLIP_CHOICES.forEach { v ->
                        Choice(
                            when (v) {
                                0 -> "Off"
                                RideVideoPlan.FULL_CLIP -> "Full"
                                else -> "$v s"
                            },
                            s.clipSeconds == v,
                        ) { vm.setClipSeconds(v) }
                    }
                }
                val inRange = s.moments.filter { it.timeMillis in s.fromRide..s.toRide }
                if (inRange.isEmpty()) {
                    Text("No moments in this part of the ride.", style = RtType.caption, color = RtColors.TextTertiary)
                } else if (s.clipSeconds > 0) {
                    inRange.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { vm.toggleMoment(m.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).background(momentColor(m), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                momentTitle(m) + " · " + Format.timeOfDay(m.timeMillis) + if (m.kind == MomentKind.PHOTO) " · shown 1 s" else "",
                                style = RtType.caption,
                                color = RtColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Checkbox(
                                checked = m.id !in s.off,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = RtColors.Primary, checkmarkColor = RtColors.OnPrimary),
                            )
                        }
                    }
                }
            }
            Section("Show on the video") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MomentLayout.entries.forEach { l -> Choice(l.label, s.layout == l) { vm.setLayout(l) } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(MomentField.SPEED, MomentField.LEAN, MomentField.G_FORCE, MomentField.EVENT, MomentField.TOP_SPEED, MomentField.TIME, MomentField.BRAND).forEach { f ->
                        Choice(f.label, f in s.fields) { vm.toggleField(f) }
                    }
                }
            }
            s.error?.let { Text(it, style = RtType.caption, color = RtColors.Error) }
            toast?.let { Text(it, style = RtType.caption, color = RtColors.TextPrimary, modifier = Modifier.align(Alignment.CenterHorizontally)) }
        }

        // Render, then share.
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val exported = s.exported
            val p = s.progress
            when {
                p != null -> {
                    val elapsed = System.currentTimeMillis() - s.startedAt
                    val left = if (p in 3..99) elapsed * (100 - p) / p else null
                    Row {
                        Text("Creating video… $p%", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                        left?.let { Text("about ${Format.clock(it)} left", style = RtType.caption, color = RtColors.TextSecondary) }
                    }
                    LinearProgressIndicator(progress = { p / 100f }, color = RtColors.Primary, trackColor = RtColors.Surface, modifier = Modifier.fillMaxWidth())
                    Button("Cancel", primary = false) { vm.cancel() }
                }
                exported != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button("Share", primary = true, icon = Icons.Outlined.IosShare, modifier = Modifier.weight(1f)) {
                            ShareImages.share(context, ShareImages.uriFor(context, exported), "video/mp4")
                        }
                        Button("Save", primary = false, icon = Icons.Outlined.Download, modifier = Modifier.weight(1f)) {
                            scope.launch {
                                val ok = ShareImages.saveVideo(context, exported, exported.nameWithoutExtension)
                                if (ok) haptics.confirm()
                                toast = if (ok) "Saved to Movies/Keppo Moto" else "Couldn't save here. Use Share instead."
                            }
                        }
                    }
                    Button("Change and create again", primary = false) { vm.again() }
                }
                else -> Button(
                    "Create video" + (plan?.let { " · ${Format.clock(it.totalMillis)}" } ?: ""),
                    primary = true,
                    enabled = plan != null,
                ) { vm.render() }
            }
        }
    }
}

/** The preview: the real 3D map following the bike, the overlay, and moments in the centre card. */
@Composable
private fun Preview(vm: RideVideoViewModel, s: RideVideoState, plan: RideVideoPlan?, data: TrackData, modifier: Modifier) {
    var playing by remember { mutableStateOf(false) }
    var outMs by remember { mutableLongStateOf(0L) }
    var heading by remember { mutableFloatStateOf(Float.NaN) }
    val total = plan?.totalMillis ?: 0L
    LaunchedEffect(plan) {
        playing = false
        outMs = 0
    }
    val currentPlan = rememberUpdatedState(plan)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (outMs >= total) outMs = 0
        var last = 0L
        while (playing) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 16L else ((n - last) / 1_000_000L).coerceAtMost(100L)
                last = n
                outMs = (outMs + dt).coerceAtMost(total)
            }
            if (outMs >= total) playing = false
        }
    }
    val p = currentPlan.value
    val at = p?.at(outMs)
    val rideTime = p?.rideTimeAt(outMs) ?: s.fromRide
    val hold = at?.first as? VideoPart.Hold
    val mapTime = if (hold != null) hold.atRide else rideTime
    val pos = data.samples.positionAtSmooth(mapTime.toDouble())
    data.bearingAtTime(mapTime.toDouble())?.let { b ->
        heading = if (heading.isNaN() || !playing) b else RideVideoRenderer.easeAngle(heading, b, 0.15f)
    }
    // The overlay is redrawn a few times a second; plenty for a preview.
    var overlay by remember { mutableStateOf<Bitmap?>(null) }
    val bucket = outMs / 200
    LaunchedEffect(bucket, s.layout, s.fields, plan) {
        overlay = withContext(Dispatchers.Default) { vm.previewOverlay(outMs, 540, 960) }
    }
    Box(
        modifier
            .height(400.dp)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, RtColors.Hairline, RoundedCornerShape(18.dp))
            .background(Color(0xFF0D0E10)),
    ) {
        RouteMap(
            route = data.route,
            marker = pos,
            pins = s.moments.filter { it.id !in s.off && it.timeMillis in s.fromRide..s.toRide && it.latitude != null && it.longitude != null }
                .map { m -> MapPin(m.id, m.latitude!!, m.longitude!!, m.thumb, momentColor(m)) },
            threeD = true,
            bearing = heading.takeIf { !it.isNaN() },
            playing = playing,
            corner = 0.dp,
            modifier = Modifier.fillMaxSize(),
        )
        if (hold != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        overlay?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        if (hold != null) {
            val m = s.moments.firstOrNull { it.id == hold.moment.id }
            Column(Modifier.align(Alignment.Center).offset(y = (-12).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth(0.66f)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(12.dp)),
                ) { Thumb(m?.thumb ?: m?.file, Modifier.fillMaxSize()) }
                Text(hold.moment.label, style = RtType.caption, color = Color.White, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(role = Role.Button, onClickLabel = if (playing) "Pause preview" else "Play preview") { playing = !playing },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
        }
        if (total > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.15f)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((outMs.toFloat() / total).coerceIn(0f, 1f)).height(3.dp).background(RtColors.Primary))
        }
    }
}

/** The whole ride as speed bars with moment dots; two handles pick the part to share. */
@Composable
private fun TrimStrip(s: RideVideoState, data: TrackData, onTrim: (Long, Long) -> Unit) {
    val span = (s.rideEnd - s.rideStart).coerceAtLeast(1)
    val state = rememberUpdatedState(s)
    val accent = RtColors.Primary
    val muted = RtColors.TextTertiary
    val speeds = data.speed.values
    val dots = s.moments.map { ((it.timeMillis - s.rideStart).toFloat() / span) to momentColor(it) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(RtColors.Surface)
            .semantics { contentDescription = "Part of the ride to share, ${Format.timeOfDay(s.fromRide)} to ${Format.timeOfDay(s.toRide)}" },
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val top = speeds.filter { !it.isNaN() }.maxOrNull()?.takeIf { it > 0 } ?: 1f
            val bars = (size.width / 4f).toInt().coerceAtLeast(1)
            for (b in 0 until bars) {
                val i = (b.toFloat() / bars * (speeds.size - 1)).toInt().coerceIn(0, (speeds.size - 1).coerceAtLeast(0))
                val v = speeds.getOrNull(i)?.takeIf { !it.isNaN() } ?: 0f
                val h = 6f + (size.height - 26f) * (v / top)
                drawRect(
                    Color(RideVideoRenderer.heat(v / top)).copy(alpha = 0.85f),
                    topLeft = Offset(b * 4f, size.height - 8f - h),
                    size = androidx.compose.ui.geometry.Size(3f, h),
                )
            }
            dots.forEach { (f, c) -> drawCircle(c, 5f, Offset(f * size.width, 9f)) }
            val a = (s.fromRide - s.rideStart).toFloat() / span * size.width
            val b = (s.toRide - s.rideStart).toFloat() / span * size.width
            drawRect(Color.Black.copy(alpha = 0.6f), Offset.Zero, androidx.compose.ui.geometry.Size(a, size.height))
            drawRect(Color.Black.copy(alpha = 0.6f), Offset(b, 0f), androidx.compose.ui.geometry.Size(size.width - b, size.height))
            drawRect(accent, Offset(a, 0f), androidx.compose.ui.geometry.Size(b - a, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            drawRect(accent, Offset(a - 8f, 0f), androidx.compose.ui.geometry.Size(16f, size.height))
            drawRect(accent, Offset(b - 8f, 0f), androidx.compose.ui.geometry.Size(16f, size.height))
            drawLine(muted, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
        }
        // Drag near a handle to move it; drag in the middle to slide the whole part.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var grab = 0
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val st = state.value
                        val sp = (st.rideEnd - st.rideStart).coerceAtLeast(1)
                        val ax = (st.fromRide - st.rideStart).toFloat() / sp * widthPx
                        val bx = (st.toRide - st.rideStart).toFloat() / sp * widthPx
                        grab = when {
                            abs(o.x - ax) < 48f -> 1
                            abs(o.x - bx) < 48f -> 2
                            o.x in ax..bx -> 3
                            else -> 0
                        }
                    },
                ) { change, dx ->
                    change.consume()
                    val st = state.value
                    val dMs = (dx / widthPx * (st.rideEnd - st.rideStart)).toLong()
                    when (grab) {
                        1 -> onTrim(st.fromRide + dMs, st.toRide)
                        2 -> onTrim(st.fromRide, st.toRide + dMs)
                        3 -> {
                            val len = st.toRide - st.fromRide
                            val from = (st.fromRide + dMs).coerceIn(st.rideStart, st.rideEnd - len)
                            onTrim(from, from + len)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(Locale.getDefault()), style = RtType.label, color = RtColors.TextSecondary)
        content()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f),
            selectedLabelColor = RtColors.Primary,
            labelColor = RtColors.TextSecondary,
        ),
    )
}

@Composable
private fun Button(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (primary && enabled) RtColors.Primary else RtColors.Surface)
            .border(1.dp, if (primary) Color.Transparent else RtColors.Hairline, RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (primary && enabled) RtColors.OnPrimary else if (enabled) RtColors.TextPrimary else RtColors.TextTertiary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = RtType.button, color = fg)
    }
}
