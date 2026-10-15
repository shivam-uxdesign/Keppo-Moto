package com.ridetrack.app.studio

import com.ridetrack.app.ui.format.Units
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.BorderOuter
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Moving
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FormatPaint
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.asComposeRenderEffect

/** What a text dialog is for. */
private sealed interface TextAsk {
    data object NewText : TextAsk
    data object NewCaption : TextAsk
    data class EditText(val id: String, val text: String) : TextAsk
    data class EditCaption(val index: Int, val line: Int, val text: String) : TextAsk
}

/** Which list of clips the add dialog is for. */
private enum class AddWhat { CLIP, LAYER, REPLACE }

/** The selected clip's tool groups. */
private enum class ClipTab { EDIT, SPEED, LOOK, FRAME }

/** One frame at 30 fps. */
private const val FRAME_MS = 33L
/** How many layers play their video in the preview at once (the top ones); more show a still. */
private const val MAX_LAYER_VIDEOS = 3
/** A playing layer drifting this far from the edit jumps back in step. */
private const val LAYER_DRIFT_MS = 250L
/** A timeline row's height. */
private val ROW = 34.dp
/** The column of row names on the timeline's left. */
private val ROW_NAME_W = 34.dp
/** The timeline's height to start with, and its least; the rider drags the handle above it. */
private const val TIMELINE_DP = 230f
private const val TIMELINE_MIN_DP = 120f

/**
 * The timeline editor: the edit plays live at the top (no rendering), the timeline below scrolls
 * under a fixed playhead to scrub (pinch to zoom). Tracks: clips (tap to select, drag the edges
 * to trim, Select more to pick several), markers, layers, captions, text and the sound tracks.
 * Tools change with what's selected. Edits are kept as you go; Save makes the video once.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun TimelineEditor(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    val plan = s.timeline ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    // The one preview: it plays the edit and the picture above draws the look, camera and graphics.
    val preview = remember { EditorPreview(context) }
    val files = remember { vm.previewFiles() }
    val active: Player = preview.player
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var safe by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<TextAsk?>(null) }
    var adding by remember { mutableStateOf<AddWhat?>(null) }
    var mixer by remember { mutableStateOf(false) }
    /** A horn or bump tapped on the engine row: (sound item, where it starts in the source, horn?). */
    var noisePick by remember { mutableStateOf<Triple<String, Long, Boolean>?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var clipTab by remember { mutableStateOf(ClipTab.EDIT) }
    /** A tab's tool that picks the clip under the playhead and opens straight onto its panel. */
    var pendingTab by remember { mutableStateOf<ClipTab?>(null) }
    /** Where the edit history stood when a clip panel opened (✕ goes back to it), and the clip's length then. */
    var panelFrom by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var addSticker by remember { mutableStateOf(false) }
    var captionsOpen by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var soundOpen by remember { mutableStateOf(false) }
    var ideasOpen by remember { mutableStateOf(false) }
    // The cut whose transition is being picked (the clip after it); the clips part around it.
    var cutAt by remember { mutableStateOf<Int?>(null) }
    // A word popping up on the video (the style's name after a swipe), and holding to see the made one.
    var flash by remember { mutableStateOf<String?>(null) }
    var holding by remember { mutableStateOf(false) }
    var styleAt by remember { mutableIntStateOf(-1) }
    // A crop being pinched on the video: shown straight away, kept on letting go.
    var crop by remember { mutableStateOf(CropLive()) }
    // Typing on the video: a new text (id null) or the one being changed.
    var typing by remember { mutableStateOf(false) }
    var typingId by remember { mutableStateOf<String?>(null) }
    var captionTab by remember { mutableStateOf(CaptionTab.LOOK) }
    var captionLine by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var voiceOn by remember { mutableStateOf(false) }
    // Which parents are open in the tool bar (indices down the tree); back to the top on a new selection.
    var toolPath by remember { mutableStateOf(emptyList<Int>()) }
    LaunchedEffect(s.pick) { toolPath = emptyList() }
    // With nothing selected: the tab shown at the bottom (Edit · Sound · Text · Look · Add).
    var toolTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ToolTab.EDIT) }
    var micAllowed by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micAllowed = it }
    val styles by appContainer().styles.styles.collectAsStateWithLifecycle()
    // A cut whose transition is playing on a loop (ms into the Reel); null when not.
    var loopAt by remember { mutableStateOf<Long?>(null) }
    // The preview's graphics (transitions, captions, text, stickers), drawn at half size.
    val art = remember(s.options.accent, s.options.grain, s.options.redlineStroke) {
        StudioArt(context, w = 540, h = 960, grainOn = s.options.grain, accentOverride = s.options.accent).also { it.redlineStroke = s.options.redlineStroke }
    }
    val pxPerMs = with(density) { 64.dp.toPx() } * zoom / 1000f
    // A selected clip shows the rest of its recording beside it: everything after it makes room.
    val map = remember(plan, s.pick, pxPerMs) { TimeMap.of(plan, (s.pick as? TimelinePick.Clip)?.index, pxPerMs) }
    val guide = remember { mutableStateOf<Float?>(null) }
    var halfPx by remember { mutableIntStateOf(0) }
    val pullMs = with(density) { 6.dp.toPx() } / pxPerMs
    val letGoMs = with(density) { 2.dp.toPx() } / pxPerMs
    // What blocks snap to, in Reel time: cuts, the start and end of what was said, ride moments.
    val snapTargets = remember(plan, s.rideTraces) {
        val st = TimelineEdits.starts(plan)
        buildList {
            addAll(st); add(plan.totalMs)
            plan.segments.forEachIndexed { i, sg ->
                val cs = sg as? ClipSegment ?: return@forEachIndexed
                if (cs.tail || cs.still != null) return@forEachIndexed
                val f = Speed.outPerSource(cs.speed, cs.steps)
                val range = cs.inMs..cs.inMs + cs.sourceMs
                fun at(c: Long) = st[i] + ((c - cs.inMs) * f).toLong()
                cs.bit.lines.forEach { l -> if (l.startMs in range) add(at(l.startMs)); if (l.endMs in range) add(at(l.endMs)) }
                s.rideTraces[cs.bit.momentId]?.marks?.forEach { m -> if (m.clipMs in range) add(at(m.clipMs)) }
            }
        }
    }
    /** A moving block: its start or its end pulled onto a target just ahead (6 dp), let go 2 dp past. */
    fun blockSnap(raw: Long, dur: Long, dir: Int): Pair<Long, Long>? {
        var best: Pair<Long, Long>? = null
        var bestD = Float.MAX_VALUE
        (snapTargets + pos).forEach { t ->
            listOf(t to t, (t - dur) to t).forEach { (start, at) ->
                val ahead = (start - raw).toFloat() * dir
                if (ahead <= pullMs && ahead >= -letGoMs && kotlin.math.abs(ahead) < bestD && start >= 0) { best = start to at; bestD = kotlin.math.abs(ahead) }
            }
        }
        return best
    }
    var trimSnap by remember { mutableStateOf<Long?>(null) }
    val snapHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    /** A clip edge being trimmed: pulled onto where talking starts or stops, or a ride moment. */
    fun trimSnapped(p: StudioPlan, i: Int, ms: Long, start: Boolean, dir: Int, fast: Boolean): Long {
        val cs = p.segments.getOrNull(i) as? ClipSegment
        if (fast || dir == 0 || cs == null) { trimSnap = null; guide.value = null; return ms }
        val f = Speed.outPerSource(cs.speed, cs.steps)
        val air = 150L
        val clipTargets = cs.bit.lines.map { if (start) it.startMs - air else it.endMs + air } + (s.rideTraces[cs.bit.momentId]?.marks?.map { it.clipMs } ?: emptyList())
        val cands = clipTargets.map { c -> if (start) ((c - cs.inMs) * f).toLong() else ((c - cs.inMs - cs.sourceMs) * f).toLong() }
        val best = cands.filter { c -> val ahead = (c - ms).toFloat() * dir; ahead <= pullMs && ahead >= -letGoMs }.minByOrNull { kotlin.math.abs(it - ms) }
        if (best != null && best != trimSnap) snapHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        trimSnap = best
        return best ?: ms
    }
    val scroll = rememberScrollState()
    val rowScroll = rememberScrollState()
    var timelineDp by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(TIMELINE_DP) }
    val current by rememberUpdatedState(plan)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)

    DisposableEffect(Unit) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        preview.player.addListener(l)
        onDispose { preview.player.removeListener(l); preview.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { active.pause() }
    // A change (not mid-drag): play the new edit from where the playhead is.
    LaunchedEffect(s.timelineVersion) { preview.load(current, files, pos) }
    // The sound tracks besides the clips' own, mixed in the background once edits settle (the last mix plays meanwhile).
    LaunchedEffect(s.timelineVersion, s.takes, s.musicUri) {
        delay(600)
        preview.setSound(vm.previewSound(current))
    }
    // Playing: the timeline follows the video.
    LaunchedEffect(playing, map) {
        while (playing) {
            pos = preview.positionMs().coerceIn(0, current.totalMs)
            preview.syncSound()
            vm.playheadMs = pos
            scroll.scrollTo(map.x(pos).roundToInt())
            delay(33)
        }
    }
    // Scrolling the timeline by hand scrubs the video.
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(map) {
        scroll.scrollTo(map.x(pos).roundToInt())
        var lastSeek = 0L
        snapshotFlow { scroll.value }.collectLatest { v ->
            if (!active.isPlaying) {
                pos = map.ms(v.toFloat()).coerceIn(0, current.totalMs)
                vm.playheadMs = pos
                // The time moves at once; the video jumps at most every 60 ms, and to where the
                // finger stops (a jump on every pixel makes scrubbing heavy).
                val wait = 60 - (android.os.SystemClock.uptimeMillis() - lastSeek)
                if (wait > 0) delay(wait)
                lastSeek = android.os.SystemClock.uptimeMillis()
                preview.seek(pos)
            }
        }
    }
    // Let go near a cut or a marker: the playhead snaps to it.
    LaunchedEffect(dragged) {
        if (dragged) {
            active.pause()
        } else {
            delay(250)
            val snap = (TimelineEdits.snap(current, pos) ?: current.markers.map { it.atMs }.minByOrNull { abs(it - pos) }?.takeIf { abs(it - pos) <= 150 })
            if (snap != null && snap != pos && !scroll.isScrollInProgress) { tick(); scroll.animateScrollTo(map.x(snap).roundToInt()) }
        }
    }
    // A transition just picked (or its mark tapped): play across the cut, three times.
    LaunchedEffect(loopAt, s.timelineVersion) {
        val at = loopAt ?: return@LaunchedEffect
        delay(350) // the new edit loads first
        repeat(3) {
            val t = (at - 1_200).coerceAtLeast(0)
            pos = t
            preview.seek(t)
            scroll.scrollTo(map.x(t).roundToInt())
            active.play()
            delay(2_400)
        }
        active.pause()
        loopAt = null
    }
    LaunchedEffect(dragged) { if (dragged) loopAt = null }
    // A clip panel open: remember where to go back to on ✕, and play the clip round and round so
    // each change is seen (and heard) at once.
    val panelClip = (s.pick as? TimelinePick.Clip)?.index?.takeIf { clipTab != ClipTab.EDIT }
    LaunchedEffect(panelClip, clipTab) {
        panelFrom = panelClip?.let { k -> s.steps.size to (s.timeline?.segments?.getOrNull(k)?.durMs ?: 0L) }
    }
    LaunchedEffect(panelClip, clipTab, s.timelineVersion) {
        val k = panelClip ?: return@LaunchedEffect
        if (clipTab == ClipTab.FRAME) return@LaunchedEffect
        delay(300)
        while (true) {
            val plan0 = s.timeline ?: break
            val from = plan0.startOf(k)
            val len = plan0.segments.getOrNull(k)?.durMs ?: break
            pos = from
            preview.seek(from)
            active.play()
            delay(len.coerceIn(500, 20_000))
        }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(1_300); flash = null } }
    // A different selection closes the transition picker.
    LaunchedEffect(s.pick) { if (s.pick != null) { cutAt = null; voiceOn = false; filterOpen = false }; clipTab = pendingTab ?: ClipTab.EDIT; pendingTab = null }
    // A take stops at the end of the Reel.
    // (Watched without reading the playhead here, so the whole editor isn't redrawn 30 times a second.)
    LaunchedEffect(s.recordingAt) {
        if (s.recordingAt == null) return@LaunchedEffect
        snapshotFlow { pos }.collect { p -> if (p >= current.totalMs - 60) { vm.stopTake(); active.pause(); preview.setLevel(1f) } }
    }
    // Two fingers tap: undo; three: redo. A short banner says what went.
    fun undoBanner(label: String?, redo: Boolean) { label?.let { tick(); flash = (if (redo) "Redo · " else "Undo · ") + it } }
    BackHandler { if (s.canUndo) discard = true else vm.closeEdit() }
    BackHandler(enabled = cutAt != null || s.pick != null) { if (cutAt != null) { cutAt = null; loopAt = null } else vm.pick(null) }

    fun seekTo(ms: Long) {
        active.pause()
        val t = ms.coerceIn(0, current.totalMs)
        pos = t
        vm.playheadMs = t
        preview.seek(t)
    }

    fun close() { if (s.canUndo) discard = true else vm.closeEdit() }

    // Swipe across the video: the next or previous style goes on the edit, its name pops up.
    fun nextStyle(step: Int) {
        val list = styles.sortedByDescending { it.favourite }
        if (list.isEmpty()) { flash = "No styles yet"; return }
        styleAt = Math.floorMod(styleAt + step, list.size)
        val st = list[styleAt]
        vm.styleOnTimeline(st)
        tick()
        flash = "‹  ${st.name}  ›"
    }

    Box(modifier.fingerTaps { fingers -> if (fingers == 2) undoBanner(vm.undo(), false) else if (fingers == 3) undoBanner(vm.redo(), true) }) {
    Column(Modifier.fillMaxSize()) {
        // ---- top bar: close, Ideas, Next
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Rounded.KeyboardArrowDown, "Close the editor") { close() }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(RtColors.SurfaceRaised)
                    .clickable(role = Role.Button, onClickLabel = "Other ideas") { ideasOpen = true }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = RtColors.Primary, modifier = Modifier.size(16.dp))
                Text("Ideas", style = RtType.button, color = RtColors.TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            RoundIcon(Icons.AutoMirrored.Rounded.ArrowForward, "Next: make and share", primary = true) { active.pause(); vm.saveEdit() }
        }

        // ---- preview: tap plays, swipe sideways for another style, hold to see it before, swipe down to close
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BoxWithConstraints(
                Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)).background(Color.Black)
                    .previewGestures(
                        keys = arrayOf(active, s.video, s.canUndo),
                        onTap = { if (active.isPlaying) active.pause() else active.play() },
                        onHold = { on ->
                            if (s.video == null) { if (on) flash = "Nothing made yet to compare" } else { if (on) active.pause(); holding = on }
                        },
                        onSwipe = { dir ->
                            when (dir) {
                                Swipe.LEFT -> nextStyle(1)
                                Swipe.RIGHT -> nextStyle(-1)
                                Swipe.DOWN -> close()
                                Swipe.UP -> Unit
                            }
                        },
                    ),
            ) {
                val boxW = maxWidth
                val boxH = maxHeight
                // The clip under the playhead changes only at cuts, so the editor redraws then and not on
                // every frame; what moves within a clip (camera, graphics, layers) reads the playhead
                // where it's drawn.
                val ti by remember(plan) { derivedStateOf { TimelineEdits.at(plan, pos).first } }
                val cs = (plan.segments.getOrNull(ti) as? ClipSegment)?.takeIf { !it.tail }
                val unit = with(density) { boxW.toPx() } / art.w
                val lookPaint = remember(plan.vibe, cs?.color, s.options.filter, s.options.filterAmount) {
                    android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(Looks.matrix(plan.vibe, cs?.color, s.options.filter, s.options.filterAmount)) }
                }
                // The picture: the style's camera (with the transitions' moves) and the clip's own crop,
                // turn and flip, never zoomed less than the made video's fill; the look as one colour
                // matrix (Red only as a shader where the phone can).
                val cutShader = remember { PreviewEffects.cutShader() }
                // Split, Blur and Luma fade change the picture: on a layer of their own, round the camera.
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val (ci, cl) = TimelineEdits.at(plan, pos)
                        val cseg = plan.segments.getOrNull(ci) as? ClipSegment
                        val cut = cseg?.let { PictureCuts.at(Cuts.frameAt(plan, ci, cl, it.durMs)) }
                        val fx = cut?.let { PreviewEffects.cut(it, size.width, size.height, cutShader) }
                        renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31) fx?.asComposeRenderEffect() else null
                        alpha = if (cut != null && fx == null) 1f - cut.cov * cut.cov * (3 - 2 * cut.cov) else 1f
                        clip = true
                    },
                ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val (fi, fl) = TimelineEdits.at(plan, pos)
                        val fseg = plan.segments.getOrNull(fi) as? ClipSegment
                        val fcs = fseg?.takeIf { !it.tail }
                        val cam = fseg?.let { seg ->
                            val c0 = art.camera(Cuts.frameAt(plan, fi, fl, seg.durMs))
                            if (fcs == null || fcs.frame.isEmpty()) c0 else ClipTools.frameAt(fcs, fl).let { f ->
                                val room = (f.zoom - 1f) / 2f
                                c0.copy(k = c0.k * f.zoom, x = c0.x - f.x * art.w * room, y = c0.y - f.y * art.h * room)
                            }
                        }
                        val turned = fcs != null && (fcs.rotation == 90 || fcs.rotation == 270)
                        val k = (cam?.let { maxOf(it.k, art.fill(it)) } ?: 1f) * crop.z * (if (turned) maxOf(size.height / size.width, size.width / size.height) else 1f)
                        scaleX = k * (if (fcs?.flip == true) -1f else 1f); scaleY = k
                        translationX = (cam?.x ?: 0f) * unit + crop.x
                        translationY = (cam?.y ?: 0f) * unit + crop.y
                        rotationZ = (cam?.deg ?: 0f) + crop.r + (fcs?.rotation ?: 0)
                    },
                ) {
                    val redOnly = if (RideFilters.keepsRed(s.options.filter)) s.options.filterAmount else 0f
                    val redSet = remember { floatArrayOf(-1f) }
                    AndroidView(
                        factory = { ctx ->
                            (android.view.LayoutInflater.from(ctx).inflate(com.ridetrack.app.R.layout.moment_popup_player, null) as PlayerView).also { v -> v.player = active }
                        },
                        update = { v ->
                            if (v.player !== active) v.player = active
                            (v.videoSurfaceView as? android.view.TextureView)?.let { tv ->
                                tv.setLayerPaint(lookPaint)
                                // Only when it changes: a new shader every update is slow.
                                if (redSet[0] != redOnly) {
                                    redSet[0] = redOnly
                                    PreviewEffects.redOnly(tv, redOnly)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // A freeze frame, where one plays.
                    preview.still?.let { b ->
                        androidx.compose.foundation.Image(
                            b.asImageBitmap(), contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(Looks.matrix(plan.vibe, cs?.color, s.options.filter, s.options.filterAmount))),
                        )
                    }
                }
                }
                // The graphics: drawn by the same code as the made video (hidden text and stickers left out).
                val shown = remember(plan) { plan.shown() }
                val overlayIn = remember(shown, s.options, s.title, s.hookLine, s.label, s.takes) { vm.overlayInput(shown) }
                if (overlayIn != null) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val (oi, ol) = TimelineEdits.at(shown, pos)
                        val seg = shown.segments.getOrNull(oi) ?: return@Canvas
                        val live = (seg as? ClipSegment)?.let { ReelOverlay.liveAt(overlayIn, it, ol, seg.durMs) } ?: ReelOverlay.Live(-1, "", null)
                        drawIntoCanvas { c ->
                            val nc = c.nativeCanvas
                            nc.save()
                            nc.scale(size.width / art.w, size.height / art.h)
                            ReelOverlay.draw(nc, art, shown, oi, ol, seg.durMs, overlayIn, live, ReelOverlay.voiceLines(overlayIn, shown, oi, seg.durMs))
                            nc.restore()
                        }
                    }
                }
                // Layers where they'll be, the newest on top. The top few play their video; more
                // than that show a still, so the preview stays smooth.
                val showing by remember(plan) { derivedStateOf(structuralEqualityPolicy()) { plan.layers.filter { !it.hidden && pos in it.startMs until it.endMs } } }
                showing.forEachIndexed { n, l ->
                    androidx.compose.runtime.key(l.id) {
                        LayerPreview(
                            vm, s, l, { pos }, active, files[l.bit.momentId] ?: l.bit.source?.let(android.net.Uri::parse), boxW.value, boxH.value,
                            selected = (s.pick as? TimelinePick.Layer)?.id == l.id,
                            play = n >= showing.size - MAX_LAYER_VIDEOS,
                        )
                    }
                }
                val fontPx = maxWidth.value * 0.075f
                val font = VibeFonts[plan.vibe]
                val style = TextStyle(fontFamily = font, fontSize = fontPx.sp, color = Color.White, textAlign = TextAlign.Center)
                // The caption being said: tap it to fix a word, drag it up or down to move every
                // caption. Its spot is where it's really drawn (measured by drawing it off-screen).
                val captionNow by remember(plan, s.options.captions) {
                    derivedStateOf(structuralEqualityPolicy()) {
                        if (!s.options.captions) return@derivedStateOf null
                        val (si, local) = TimelineEdits.at(plan, pos)
                        val cseg = (plan.segments.getOrNull(si) as? ClipSegment)?.takeIf { !it.tail } ?: return@derivedStateOf null
                        val j = cseg.lines.indexOfLast { local >= it.startMs - Tok.LEAD }
                        if (j >= 0 && local <= cseg.lines[j].endMs + 300) si to j else null
                    }
                }
                captionNow?.let { (si, j) ->
                    val cseg = plan.segments[si] as ClipSegment
                    var moving by remember { mutableStateOf(false) }
                    // Kept still while it's dragged, so the finger's moves read true.
                    val bounds = remember(si, j, cseg.lines, plan.vibe, if (moving) null else plan.captionLook) {
                        val pic = android.graphics.Picture()
                        val line = cseg.lines[j]
                        art.caption(pic.beginRecording(art.w, art.h), plan.vibe, cseg.lines, ((line.startMs + line.endMs) / 2).toFloat(), plan.captionLook).also { pic.endRecording() }
                    }
                    if (bounds != null) {
                        val hPx = with(density) { boxH.toPx() }
                        Box(
                            Modifier.offset(x = boxW * (bounds.left / art.w), y = boxH * (bounds.top / art.h))
                                .size(width = boxW * (bounds.width() / art.w), height = boxH * (bounds.height() / art.h))
                                .then(if (moving) Modifier.border(1.5.dp, RtColors.Primary, RoundedCornerShape(6.dp)) else Modifier)
                                .clickable(role = Role.Button, onClickLabel = "Fix this caption") { active.pause(); captionTab = CaptionTab.WORDS; captionLine = si to j; captionsOpen = true }
                                .pointerInput(si, j) {
                                    var total = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = { total = 0f; moving = true; active.pause() },
                                        onDragEnd = { moving = false; vm.endDrag("Captions height") },
                                        onDragCancel = { moving = false; vm.endDrag("Captions height") },
                                    ) { ch, dy ->
                                        ch.consume()
                                        total += dy
                                        vm.drag { p -> TextTools.captionLook(p) { l -> l.copy(y = ((l.y ?: Tok.CAPTION_Y) + total / hPx).coerceIn(0.1f, 0.9f)) } }
                                    }
                                },
                        )
                    }
                }
                // Text and stickers: drag anywhere, pinch to size and turn (the selected one). They're
                // drawn with the video's graphics (above): here only their spot listens, and the
                // selected one's frame shows, to move it.
                val textsHere by remember(plan) { derivedStateOf(structuralEqualityPolicy()) { plan.texts.filter { !it.hidden && pos in it.startMs until it.endMs } } }
                textsHere.forEach { t ->
                    val selected = (s.pick as? TimelinePick.Text)?.id == t.id
                    Placed(
                        t.x, t.y, t.size, t.rotation, selected,
                        onSelect = { vm.pick(TimelinePick.Text(t.id)) },
                        onDone = { dx, dy, z, r -> vm.change("Move text") { p -> TextTools.rotate(TextTools.scale(TextTools.place(p, t.id, t.x + dx, t.y + dy), t.id, z), t.id, r) } },
                    ) {
                        val look = when (t.look) {
                            TextLook.HAND -> style.copy(fontFamily = VibeFonts[Vibe.CHILL])
                            TextLook.OUTLINE -> style.copy(fontFamily = VibeFonts[Vibe.HYPE])
                            TextLook.PLAIN, TextLook.BOX -> style.copy(fontFamily = VibeFonts[Vibe.VLOG])
                            TextLook.STYLE -> style
                        }.let { st -> fontFamilyOf(t.font)?.let { st.copy(fontFamily = it) } ?: st }
                            .let { st -> if (t.look == TextLook.BOX) st.copy(color = Color.Black, shadow = null) else t.color?.let { st.copy(color = Color(it)) } ?: st }
                        Text(
                            t.text,
                            style = look.copy(color = Color.Transparent, shadow = null),
                            modifier = Modifier
                                .then(if (selected) Modifier.border(1.dp, RtColors.Primary, RoundedCornerShape(4.dp)) else Modifier)
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                }
                val stickersHere by remember(plan) { derivedStateOf(structuralEqualityPolicy()) { plan.stickers.filter { !it.hidden && pos in it.startMs until it.endMs } } }
                stickersHere.forEach { st ->
                    val selected = (s.pick as? TimelinePick.Sticker)?.id == st.id
                    Placed(
                        st.x, st.y, st.size, st.rotation, selected,
                        onSelect = { vm.pick(TimelinePick.Sticker(st.id)) },
                        onDone = { dx, dy, z, r -> vm.change("Move sticker") { p -> TextTools.rotateSticker(TextTools.scaleSticker(TextTools.placeSticker(p, st.id, st.x + dx, st.y + dy), st.id, z), st.id, r) } },
                    ) {
                        StickerPreview(st, hidden = true, selected = selected, unit = boxW.value / 540f)
                    }
                }
                if (safe) SafeZones()
                val clipSel = (s.pick as? TimelinePick.Clip)?.let { p -> (plan.segments.getOrNull(p.index) as? ClipSegment)?.takeIf { !it.tail }?.let { p.index to it } }
                // The selected clip's speed and volume, on the video's left edge.
                if (clipSel != null && clipTab == ClipTab.EDIT && cutAt == null) {
                    val (ci, cs) = clipSel
                    Row(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).fillMaxHeight(0.45f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (cs.still == null) {
                            EdgeSlider(speedToSlider(cs.speed), speedLabel(cs.speed), Modifier.fillMaxHeight(), onChange = { f -> vm.drag { ClipTools.speed(it, ci, sliderToSpeed(f)) } }, onEnd = { vm.endDrag("Speed") })
                        }
                        EdgeSlider(
                            cs.volume / 1.5f,
                            "Vol ${(cs.volume * 100).roundToInt()}%",
                            Modifier.fillMaxHeight(),
                            onChange = { f -> vm.drag { TimelineEdits.volume(it, ci, (f * 1.5f * 20).roundToInt() / 20f) } },
                            onEnd = { vm.endDrag("Sound") },
                        )
                    }
                }
                // Cropping: pinch, drag and twist the video itself.
                if (clipSel != null && clipTab == ClipTab.FRAME) {
                    val (ci, cs) = clipSel
                    val wPx = with(density) { boxW.toPx() }
                    val hPx = with(density) { boxH.toPx() }
                    Box(
                        Modifier.fillMaxSize().border(2.dp, RtColors.Primary, RoundedCornerShape(16.dp))
                            .pointerInput(ci) {
                                detectTransformGestures { _, pan, zoom, turn ->
                                    crop = crop.copy(z = (crop.z * zoom).coerceIn(0.5f, 4f), x = crop.x + pan.x, y = crop.y + pan.y, r = crop.r + turn)
                                }
                            }
                            .pointerInput(ci, cs) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val e = awaitPointerEvent()
                                    } while (e.changes.any { it.pressed })
                                    val c = crop
                                    crop = CropLive()
                                    if (c == CropLive()) return@awaitEachGesture
                                    val here = ClipTools.frameAt(cs, 0)
                                    val z = (here.zoom * c.z).coerceIn(1f, 3f)
                                    val room = (z - 1f) / 2f
                                    val dx = if (room > 0f) -c.x / (wPx * room) else 0f
                                    val dy = if (room > 0f) -c.y / (hPx * room) else 0f
                                    val turns = Math.floorMod((c.r / 90f).roundToInt(), 4)
                                    vm.change("Crop") { p ->
                                        var q = ClipTools.reframe(p, ci, 0, dZoom = z - here.zoom, dx = dx, dy = dy, fixed = true)
                                        repeat(turns) { q = ClipTools.rotate(q, ci) }
                                        q
                                    }
                                }
                            },
                    )
                }
                if (!playing) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center).size(48.dp))
                }
                // The preview couldn't play after three tries: say so, tap to try again.
                preview.problem?.let { msg ->
                    Text(
                        msg,
                        style = RtType.caption,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.65f))
                            .clickable(role = Role.Button, onClickLabel = "Try again") { preview.retry() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (holding) s.video?.let { before ->
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        Player(android.net.Uri.fromFile(before), Modifier.align(Alignment.Center), height = boxH)
                        Pill("Before your changes", Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                    }
                }
                // The style's name (or a note) pops up after a swipe.
                var said by remember { mutableStateOf("") }
                LaunchedEffect(flash) { flash?.let { said = it } }
                androidx.compose.animation.AnimatedVisibility(
                    flash != null,
                    modifier = Modifier.align(Alignment.Center),
                    enter = androidx.compose.animation.scaleIn(initialScale = 0.8f) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    Text(said, style = RtType.bodyStrong, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 8.dp))
                }
                s.recordingAt?.let { RecordingPill({ pos }, it, Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) }
                if (cutAt != null && loopAt != null) {
                    val kind = (plan.segments.getOrNull(cutAt ?: 0) as? ClipSegment)?.transition?.kind ?: TransitionKind.STYLE
                    Pill("Looping the cut · ${kind.label}", Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                }
            }
        }
        // ---- transport: play, a frame either way, the time, undo and redo
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") { if (playing) active.pause() else active.play() }
            Text("‹", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "One frame back") { seekTo(pos - FRAME_MS) }.padding(horizontal = 8.dp))
            Text("›", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "One frame on") { seekTo(pos + FRAME_MS) }.padding(horizontal = 8.dp))
            TimeLabel({ pos }, plan.totalMs, Modifier.weight(1f))
            // Split, by the time: cuts the clip under the playhead.
            SmallIcon(Icons.Rounded.ContentCut, "Split at the playhead") { vm.change("Split") { TimelineEdits.split(it, pos) } }
            SmallIcon(Icons.AutoMirrored.Rounded.Undo, "Undo", enabled = s.canUndo) { undoBanner(vm.undo(), false) }
            SmallIcon(Icons.AutoMirrored.Rounded.Redo, "Redo", enabled = s.canRedo) { undoBanner(vm.redo(), true) }
            SmallIcon(Icons.Rounded.History, "History", enabled = s.steps.isNotEmpty()) { historyOpen = true }
            SmallIcon(if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (full) "Show the timeline" else "Bigger video") { full = !full }
        }

        if (!full) {
            // ---- the handle: drag it up for a taller timeline, down for a bigger video
            val maxTimeline = LocalConfiguration.current.screenHeightDp * 0.6f
            Box(
                Modifier.fillMaxWidth().height(18.dp)
                    .pointerInput(maxTimeline) {
                        detectVerticalDragGestures { ch, dy -> ch.consume(); timelineDp = (timelineDp - dy.toDp().value).coerceIn(TIMELINE_MIN_DP, maxTimeline) }
                    }
                    .semantics { contentDescription = "Drag to make the timeline taller or shorter" },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(RtColors.TextTertiary)) }
            // ---- timeline (pinch to zoom; scrolls up and down when its rows don't fit)
            Box(Modifier.fillMaxWidth().height(timelineDp.coerceAtMost(maxTimeline).dp).pinchToZoom { z -> zoom = (zoom * z).coerceIn(0.25f, 8f) }) {
                BoxWithConstraints(Modifier.fillMaxWidth().verticalScroll(rowScroll)) {
                    val half = maxWidth / 2
                    val starts = TimelineEdits.starts(plan)
                    fun w(ms: Long) = with(density) { (ms * pxPerMs).toDp() }
                    fun span(from: Long, to: Long) = with(density) { (map.xEnd(to) - map.x(from)).coerceAtLeast(0f).toDp() }
                    val many = (s.pick as? TimelinePick.Clips)?.indices
                    val halfPx0 = with(density) { half.roundToPx() }
                    SideEffect { halfPx = halfPx0 }
                    Row(Modifier.horizontalScroll(scroll)) {
                        Spacer(Modifier.width(half))
                        androidx.compose.runtime.CompositionLocalProvider(LocalRowLeft provides { scroll.value - halfPx0 }, LocalGuide provides guide) {
                        Column(Modifier.width(span(0, plan.totalMs) + 52.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            // Markers, and the redline tags as red bars (tap one to remove it)
                            Box(Modifier.fillMaxWidth().height(10.dp)) {
                                plan.redlines.forEach { r ->
                                    Box(
                                        Modifier.offset { IntOffset(map.x(r.startMs).roundToInt(), 0) }.width(span(r.startMs, r.endMs)).height(10.dp)
                                            .clip(RoundedCornerShape(3.dp)).background(Color(Tok.RED).copy(alpha = 0.8f))
                                            .clickable(role = Role.Button, onClickLabel = "Remove redline") { vm.deleteRedline(r.id) },
                                    )
                                }
                                plan.markers.forEach { m ->
                                    val sel = (s.pick as? TimelinePick.Marker)?.id == m.id
                                    Box(
                                        Modifier.offset { IntOffset(map.x(m.atMs).roundToInt() - 5, 0) }.size(10.dp).clip(CircleShape)
                                            .background(if (sel) RtColors.Primary else Color(0xFFFFC83D))
                                            .clickable(role = Role.Button) { seekTo(m.atMs); vm.pick(if (sel) null else TimelinePick.Marker(m.id)) },
                                    )
                                }
                            }
                            // Speed and ride moments, above the clips: tap a moment to jump there. A selected
                            // clip shows them over its whole recording (faded where it isn't in the video).
                            if (s.rideTraces.isNotEmpty()) {
                                val selIdx = (s.pick as? TimelinePick.Clip)?.index
                                val segs = plan.segments.withIndex().filter { (_, sg) -> sg is ClipSegment && !sg.tail && sg.still == null }
                                val accent = RtColors.Primary
                                Rows(1, "Speed and ride moments", Icons.Rounded.Speed) {
                                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                        val h = size.height
                                        segs.forEach { (i, sg) ->
                                            val cs = sg as ClipSegment
                                            val tr = s.rideTraces[cs.bit.momentId] ?: return@forEach
                                            val f = Speed.outPerSource(cs.speed, cs.steps)
                                            val whole = i == selIdx
                                            val from = if (whole) 0L else cs.inMs
                                            val to = if (whole) cs.bit.clipDurationMs else cs.inMs + cs.sourceMs
                                            var prev: androidx.compose.ui.geometry.Offset? = null
                                            tr.speed.forEach { (t, v) ->
                                                if (t < from || t > to) { prev = null; return@forEach }
                                                val pt = androidx.compose.ui.geometry.Offset(map.x(starts[i]) + ((t - cs.inMs) * f * pxPerMs).toFloat(), h - 3f - (v / 130f).coerceIn(0f, 1f) * (h - 8f))
                                                val inUse = t >= cs.inMs && t <= cs.inMs + cs.sourceMs
                                                prev?.let { drawLine(if (inUse) accent else Color.White.copy(alpha = 0.3f), it, pt, strokeWidth = 1.5.dp.toPx()) }
                                                prev = pt
                                            }
                                        }
                                    }
                                    segs.forEach { (i, sg) ->
                                        val cs = sg as ClipSegment
                                        val f = Speed.outPerSource(cs.speed, cs.steps)
                                        val whole = i == selIdx
                                        s.rideTraces[cs.bit.momentId]?.marks?.forEach { m ->
                                            val inUse = m.clipMs >= cs.inMs && m.clipMs <= cs.inMs + cs.sourceMs
                                            if (!inUse && !whole) return@forEach
                                            val x = map.x(starts[i]) + ((m.clipMs - cs.inMs) * f * pxPerMs).toFloat()
                                            val (glyph, col) = MARK_LOOK.getValue(m.kind)
                                            Box(
                                                Modifier.offset { IntOffset(x.roundToInt() - 9.dp.roundToPx(), 7.dp.roundToPx()) }.size(18.dp).zIndex(3f)
                                                    .graphicsLayer { alpha = if (inUse) 1f else 0.45f }
                                                    .clip(CircleShape).background(col)
                                                    .clickable(role = Role.Button, onClickLabel = "${m.label}: jump there") {
                                                        val reelT = starts[i] + ((m.clipMs - cs.inMs) * f).toLong()
                                                        if (inUse) { seekTo(reelT); scope.launch { scroll.animateScrollTo(map.x(reelT).roundToInt()) }; flash = m.label }
                                                        else { scope.launch { scroll.animateScrollTo(x.roundToInt()) }; flash = "${m.label} · not in the video yet: drag the edge to bring it in" }
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) { Text(glyph, color = Color.White, fontSize = 9.sp, lineHeight = 9.sp) }
                                        }
                                    }
                                }
                            }
                            // Clips, with a mark on each cut that plays a transition
                            Box(Modifier.fillMaxWidth().height(52.dp)) {
                            RowName("Video", Icons.Rounded.Movie)
                            Row(Modifier.height(52.dp)) {
                                plan.segments.forEachIndexed { i, seg ->
                                    val showsRest = (s.pick as? TimelinePick.Clip)?.index == i && seg is ClipSegment
                                    if (showsRest && map.leftPx > 0f) Unused(s.thumbs[(seg as ClipSegment).bit.momentId], map.leftPx, (seg.inMs * Speed.outPerSource(seg.speed, seg.steps)).toLong(), before = true)
                                    val selected = (s.pick as? TimelinePick.Clip)?.index == i || many?.contains(i) == true
                                    val isClip = seg is ClipSegment && !seg.tail
                                    val cut = cutAt
                                    val part by animateFloatAsState(if (cut == null) 0f else if (i < cut) -1f else 1f, tween(260), label = "part")
                                    val near = cut == null || i == cut || i == cut - 1
                                    Box(
                                        Modifier.width(w(seg.durMs)).fillMaxHeight()
                                            .graphicsLayer { translationX = part * 14.dp.toPx(); alpha = if (near) 1f else 0.45f }
                                            .padding(horizontal = 1.dp).clip(RoundedCornerShape(6.dp))
                                            .background(RtColors.SurfaceRaised)
                                            .then(if (selected) Modifier.border(2.dp, RtColors.Primary, RoundedCornerShape(6.dp)) else Modifier)
                                            .clickable(enabled = isClip, role = Role.Button) {
                                                when {
                                                    many != null -> vm.pick(TimelinePick.Clips(if (i in many) many - i else many + i).takeIf { it.indices.isNotEmpty() })
                                                    else -> vm.pick(if (selected) null else TimelinePick.Clip(i))
                                                }
                                            },
                                    ) {
                                        when {
                                            seg is ClipSegment && !seg.tail -> {
                                                Thumb(s.thumbs[seg.bit.momentId], Modifier.fillMaxSize())
                                                if (seg.volume == 0f) Text("Muted", style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(3.dp))
                                            }
                                            seg is ClipSegment -> Text("Loop", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.Center))
                                            seg is TitleSegment -> Text("Title", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.Center))
                                            else -> Text("Stats", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.Center))
                                        }
                                        if ((s.pick as? TimelinePick.Clip)?.index == i) {
                                            // Red at the very start or end of the recording.
                                            val cs = seg as? ClipSegment
                                            val atStart = cs != null && cs.inMs <= 0
                                            val atEnd = cs != null && cs.inMs + cs.sourceMs >= cs.bit.clipDurationMs - 20
                                            TrimHandle(Modifier.align(Alignment.CenterStart), pxPerMs, { trimSnap = null; vm.endDrag() }, limit = atStart) { ms, dir, fast -> vm.drag { p -> TimelineEdits.trimStart(p, i, trimSnapped(p, i, ms, true, dir, fast)) } }
                                            TrimHandle(Modifier.align(Alignment.CenterEnd), pxPerMs, { trimSnap = null; vm.endDrag() }, limit = atEnd) { ms, dir, fast -> vm.drag { p -> TimelineEdits.trimEnd(p, i, trimSnapped(p, i, ms, false, dir, fast)) } }
                                        }
                                    }
                                    if (showsRest && map.rightPx > 0f) Unused(s.thumbs[(seg as ClipSegment).bit.momentId], map.rightPx, (map.rightPx / pxPerMs).toLong(), before = false)
                                }
                            }
                            Cuts.marks(plan).forEach { m ->
                                val half = with(density) { 11.dp.toPx() }.roundToInt()
                                val on = cutAt == m.segment
                                val k by animateFloatAsState(if (on) 1.5f else 1f, tween(220), label = "mark")
                                val a by animateFloatAsState(if (cutAt == null || on) 1f else 0f, tween(220), label = "markAlpha")
                                Box(
                                    Modifier.offset { IntOffset(map.x(m.atMs).roundToInt() - half, with(density) { 15.dp.toPx() }.roundToInt()) }
                                        .graphicsLayer { scaleX = k; scaleY = k; alpha = a }
                                        .size(22.dp).clip(RoundedCornerShape(6.dp))
                                        // A plain cut: a faint mark, still tappable to give it a transition.
                                        .background(if (on) RtColors.Primary else if (!m.plays) Color.Black.copy(alpha = 0.35f) else if (m.chosen) RtColors.Primary else Color.Black.copy(alpha = 0.65f))
                                        .border(1.5.dp, Color.White.copy(alpha = if (m.plays || on) 0.9f else 0.4f), RoundedCornerShape(6.dp))
                                        .clickable(role = Role.Button, onClickLabel = if (m.plays) "Transition: ${m.kind.label}" else "No transition: add one") {
                                            vm.pick(null); cutAt = m.segment; loopAt = m.atMs
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { Text(TRANSITION_GLYPH[m.kind] ?: "⧓", style = RtType.caption, color = Color.White) }
                            }
                            // + after the last clip: add videos.
                            Box(
                                Modifier.offset { IntOffset(map.xEnd(plan.totalMs).roundToInt() + 8.dp.roundToPx(), 8.dp.roundToPx()) }
                                    .size(36.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.TextPrimary)
                                    .clickable(role = Role.Button, onClickLabel = "Add videos") { addOpen = true },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = RtColors.Background, modifier = Modifier.size(22.dp)) }
                            }
                            // Layers: things that overlap go on rows of their own, the top row drawn over
                            // the ones below. Hold one and drag: sideways in time, up or down a row.
                            if (plan.layers.isNotEmpty()) {
                                val top = plan.layers.asReversed()
                                val rows = Lanes.pack(top.map { Lanes.Span(it.startMs, it.endMs) })
                                Rows(Lanes.count(rows), "Video on top", Icons.Rounded.Layers) {
                                    top.forEachIndexed { n, l ->
                                        val selected = (s.pick as? TimelinePick.Layer)?.id == l.id
                                        androidx.compose.runtime.key(l.id) {
                                            TrackBlock(
                                                "▣ Layer" + if (l.keys.isNotEmpty()) " · moves" else "", l.startMs, l.durMs, rows[n], map, Color(0x5594E2B8), selected, l.hidden,
                                                onClick = { vm.pick(if (selected) null else TimelinePick.Layer(l.id)) },
                                            snap = { r, d, dir -> blockSnap(r, d, dir) },
                                        ) { ms, r -> vm.change("Move layer") { p -> TrackEdits.stackLayer(TrackEdits.shiftLayer(p, l.id, ms), l.id, r) } }
                                        }
                                    }
                                }
                            }
                            // Captions: ones said at the same time go on rows of their own. Hold one and drag to move it in time.
                            val caps = remember(plan.segments) {
                                plan.segments.flatMapIndexed { i, seg ->
                                    if (seg !is ClipSegment || seg.tail) emptyList()
                                    else seg.lines.mapIndexed { j, l -> Triple(i, j, Lanes.Span(starts[i] + l.startMs, starts[i] + minOf(l.endMs, seg.durMs).coerceAtLeast(l.startMs + 1))) }
                                }
                            }
                            val capRows = remember(caps) { Lanes.pack(caps.map { it.third }) }
                            Rows(Lanes.count(capRows), "Captions", Icons.Rounded.ClosedCaption) {
                                caps.forEachIndexed { n, (i, j, sp) ->
                                    val line = (plan.segments[i] as ClipSegment).lines[j]
                                    val selected = s.pick == TimelinePick.Caption(i, j)
                                    TrackBlock(
                                        line.text, sp.startMs, sp.endMs - sp.startMs, capRows[n], map, RtColors.Primary.copy(alpha = 0.25f), selected, hidden = !s.options.captions,
                                        onClick = { vm.pick(if (selected) null else TimelinePick.Caption(i, j)) },
                                    snap = { r, d, dir -> blockSnap(r, d, dir) },
                                ) { ms, _ -> vm.change("Caption time") { TimelineEdits.nudgeCaption(it, i, j, ms) }; vm.pick(null) }
                                }
                            }
                            // Text
                            run {
                                val top = plan.texts.asReversed()
                                val rows = Lanes.pack(top.map { Lanes.Span(it.startMs, it.endMs) })
                                Rows(Lanes.count(rows), "Text", Icons.Rounded.TextFields) {
                                    top.forEachIndexed { n, t ->
                                        val selected = (s.pick as? TimelinePick.Text)?.id == t.id
                                        androidx.compose.runtime.key(t.id) {
                                            TrackBlock(
                                                "T  ${t.text}", t.startMs, t.endMs - t.startMs, rows[n], map, Color(0x55FFC83D), selected, t.hidden,
                                                onClick = { vm.pick(if (selected) null else TimelinePick.Text(t.id)) },
                                            snap = { r, d, dir -> blockSnap(r, d, dir) },
                                        ) { ms, r -> vm.change("Move text") { p -> TrackEdits.stackText(TimelineEdits.moveText(p, t.id, ms), t.id, r) } }
                                        }
                                    }
                                }
                            }
                            // Stickers
                            if (plan.stickers.isNotEmpty()) {
                                val top = plan.stickers.asReversed()
                                val rows = Lanes.pack(top.map { Lanes.Span(it.startMs, it.endMs) })
                                Rows(Lanes.count(rows), "Stickers", Icons.Rounded.EmojiEmotions) {
                                    top.forEachIndexed { n, st ->
                                        val selected = (s.pick as? TimelinePick.Sticker)?.id == st.id
                                        androidx.compose.runtime.key(st.id) {
                                            TrackBlock(
                                                if (st.kind == StickerKind.EMOJI) st.text else st.kind.label, st.startMs, st.endMs - st.startMs, rows[n], map, Color(0x55F472B6), selected, st.hidden,
                                                onClick = { vm.pick(if (selected) null else TimelinePick.Sticker(st.id)) },
                                            snap = { r, d, dir -> blockSnap(r, d, dir) },
                                        ) { ms, r -> vm.change("Move sticker") { p -> TrackEdits.stackSticker(TextTools.shiftSticker(p, st.id, ms), st.id, r) } }
                                        }
                                    }
                                }
                            }
                            // Your voice: each clip's own sound (its mic), on a row of its own
                            run {
                                val starts = TimelineEdits.starts(plan)
                                val voiced = plan.segments.withIndex().filter { (_, seg) -> seg is ClipSegment && !seg.tail && seg.volume > 0f }
                                if (voiced.isNotEmpty()) {
                                    val muted = plan.mix.gain(TrackKind.CLIPS) == 0f
                                    val label = (if (s.engineFiles.isNotEmpty()) "Voice" else "Clip sound") + if (muted) " · muted" else ""
                                    Rows(1, "Mic 1 · Voice", Icons.Rounded.Mic) {
                                        voiced.forEach { (i, seg) ->
                                            TrackBlock(label, starts[i], seg.durMs, 0, map, Color(0x5538BDF8), false, onClick = { mixer = true })
                                        }
                                        // What was said, as faint text over the row (and over a selected clip's unused parts).
                                        val selIdx = (s.pick as? TimelinePick.Clip)?.index
                                        voiced.forEach { (i, seg) ->
                                            val cs = seg as ClipSegment
                                            val f = Speed.outPerSource(cs.speed, cs.steps)
                                            cs.bit.lines.forEach { l ->
                                                val inUse = l.endMs > cs.inMs && l.startMs < cs.inMs + cs.sourceMs
                                                if (!inUse && i != selIdx) return@forEach
                                                val x = map.x(starts[i]) + ((l.startMs - cs.inMs) * f * pxPerMs).toFloat()
                                                val wpx = ((l.endMs - l.startMs) * f * pxPerMs).toFloat().coerceAtLeast(8f)
                                                Text(
                                                    l.text, color = Color.White.copy(alpha = if (inUse) 0.95f else 0.5f), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.offset { IntOffset(x.roundToInt(), 9.dp.roundToPx()) }.width(with(density) { wpx.toDp() }).zIndex(2f)
                                                        .background(Color.Black.copy(alpha = if (inUse) 0.6f else 0.3f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            // Sound tracks (hold one and drag to move it in time)
                            listOf(TrackKind.DETACHED, TrackKind.ENGINE).forEach { kind ->
                                val items = plan.audio.filter { it.kind == kind }
                                if (items.isEmpty()) return@forEach
                                Rows(1, if (kind == TrackKind.ENGINE) "Mic 2 · Engine" else "Sound", if (kind == TrackKind.ENGINE) Icons.Rounded.TwoWheeler else Icons.Rounded.GraphicEq) {
                                    items.forEach { a ->
                                        val selected = (s.pick as? TimelinePick.Audio)?.id == a.id
                                        val muted = plan.mix.gain(kind) == 0f
                                        TrackBlock(
                                            (if (kind == TrackKind.ENGINE) "Engine" else "♪ Sound") + if (muted) " · muted" else "", a.startMs, a.durMs, 0, map,
                                            if (kind == TrackKind.ENGINE) Color(0x55F97316) else Color(0x55A78BFA), selected,
                                            onClick = { vm.pick(if (selected) null else TimelinePick.Audio(a.id)) },
                                        snap = { r, d, dir -> blockSnap(r, d, dir) },
                                        envelope = { f -> TrackEdits.levelAt(a, (f * a.durMs).toLong()) },
                                    ) { ms, _ -> vm.change("Sound time") { TrackEdits.shiftAudio(it, a.id, ms) } }
                                    }
                                    // Clips whose engine mic wasn't recorded: say so where its sound would be.
                                    if (kind == TrackKind.ENGINE) {
                                        val starts = TimelineEdits.starts(plan)
                                        plan.segments.withIndex().filter { (_, seg) -> seg is ClipSegment && !seg.tail }.forEach { (i, seg) ->
                                            val from = starts[i]
                                            val to = from + seg.durMs
                                            if (items.none { it.startMs < to && it.startMs + it.durMs > from }) {
                                                TrackBlock("No engine sound for this clip", from, seg.durMs, 0, map, Color(0x1AFFFFFF), false, onClick = { mixer = true })
                                            }
                                        }
                                    }
                                }
                                // Horns and bumps the engine mic heard, under its row: tap one to choose.
                                if (kind == TrackKind.ENGINE) {
                                    val marks = items.flatMap { a ->
                                        s.engineNoise[a.file].orEmpty()
                                            .filter { n -> n.endMs > a.inMs && n.startMs < a.inMs + a.durMs }
                                            .map { n -> Triple(a, n, a.noise[n.startMs] ?: NoiseChoice.REDUCE) }
                                    }
                                    if (marks.isNotEmpty()) Rows(1, "Horns and bumps", Icons.Rounded.Campaign) {
                                        marks.forEach { (a, n, choice) ->
                                            val horn = n.kind == com.ridetrack.telemetry.moments.EngineNoise.Kind.HORN
                                            val from = a.startMs + (n.startMs - a.inMs).coerceAtLeast(0)
                                            val to = a.startMs + (n.endMs - a.inMs).coerceAtMost(a.durMs)
                                            TrackBlock(
                                                (if (horn) "Horn" else "Bump") + when (choice) {
                                                    NoiseChoice.REDUCE -> if (horn) " · reduced" else " · softened"
                                                    NoiseChoice.MUTE -> " · muted"
                                                    NoiseChoice.KEEP -> " · kept"
                                                },
                                                from, (to - from).coerceAtLeast(250), 0, map,
                                                if (choice == NoiseChoice.KEEP) Color(0x33F97316) else Color(0x66FBBF24), false,
                                                onClick = { noisePick = Triple(a.id, n.startMs, horn) },
                                            )
                                        }
                                    }
                                }
                            }
                            if (s.takes.isNotEmpty()) {
                                Rows(1, "Voice-over", Icons.Rounded.RecordVoiceOver) {
                                    s.takes.forEach { t -> TrackBlock("Voice-over", t.startMs, t.durMs, 0, map, Color(0x5560A5FA), false, onClick = { mixer = true }) }
                                }
                            }
                            if (s.musicUri != null) {
                                Rows(1, "Music", Icons.Rounded.MusicNote) {
                                    TrackBlock("♫ ${s.musicName ?: "Music"}", 0, plan.totalMs, 0, map, Color(0x5534D399), false, onClick = { mixer = true })
                                }
                            }
                            plan.audio.filter { it.kind == TrackKind.MUSIC }.forEach { a ->
                                val selected = (s.pick as? TimelinePick.Audio)?.id == a.id
                                Rows(1, "Music", Icons.Rounded.MusicNote) {
                                    TrackBlock(
                                        "♫ ${a.name ?: "Sound"}" + if (plan.mix.gain(TrackKind.MUSIC) == 0f) " · muted" else "", a.startMs, a.durMs, 0, map, Color(0x5534D399), selected,
                                        onClick = { vm.pick(if (selected) null else TimelinePick.Audio(a.id)) },
                                    snap = { r, d, dir -> blockSnap(r, d, dir) },
                                ) { ms, _ -> vm.change("Sound time") { TrackEdits.shiftAudio(it, a.id, ms) } }
                                }
                            }
                            Text(
                                "+ Sound",
                                style = RtType.caption,
                                color = RtColors.TextPrimary,
                                modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, RtColors.Outline, RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button, onClickLabel = "Add sound") { soundOpen = true }.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                        }
                        Spacer(Modifier.width(half))
                    }
                }
                // The playhead, over every track; and the yellow guide while something snaps.
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White))
                    guide.value?.let { gx ->
                        Box(Modifier.offset { IntOffset((halfPx + gx - scroll.value).roundToInt(), 0) }.width(1.5.dp).fillMaxHeight().background(Color(0xFFFFD23F)))
                    }
                }
            }

            // ---- tools: a tree of at most 5 icons per level (see docs/plans/editor-toolbar-plan.md)
            fun pickLabel(p: TimelinePick?): String = when (p) {
                null -> "Your video"
                is TimelinePick.Clip -> "Clip ${p.index + 1}" + (plan.segments.getOrNull(p.index)?.let { " · " + com.ridetrack.app.ui.format.Format.clock(it.durMs) } ?: "")
                is TimelinePick.Clips -> "${p.indices.size} clips"
                is TimelinePick.Layer -> "Video on top"
                is TimelinePick.Audio -> when (plan.audio.firstOrNull { it.id == p.id }?.kind) { TrackKind.ENGINE -> "Mic 2 · Engine"; TrackKind.MUSIC -> "Music"; else -> "Sound" }
                is TimelinePick.Caption -> "Caption"
                is TimelinePick.Text -> "Text"
                is TimelinePick.Sticker -> "Sticker"
                is TimelinePick.Marker -> "Marker"
                else -> "Selected"
            }
            fun leaf(icon: ImageVector, label: String, danger: Boolean = false, on: Boolean = false, enabled: Boolean = true, action: () -> Unit) = TNode(icon, label, danger, on, enabled, action = action)
            fun parent(icon: ImageVector, label: String, kids: List<TNode>) = TNode(icon, label, kids = kids)
            fun slider(icon: ImageVector, label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onChange: (Float) -> Unit, onEnd: () -> Unit) =
                TNode(icon, label, slider = TSlider(value, range, format, onChange, onEnd))
            val pct: (Float) -> String = { "${(it * 100).roundToInt()}%" }
            fun addNode() = parent(Icons.Rounded.Add, "Add", listOf(
                parent(Icons.Rounded.Movie, "Video", listOf(
                    leaf(Icons.Rounded.PictureInPicture, "Video on top") { adding = AddWhat.LAYER },
                    leaf(Icons.Rounded.VideoLibrary, "Clip") { adding = AddWhat.CLIP },
                    leaf(Icons.Rounded.AcUnit, "Freeze frame") { vm.freeze() },
                )),
                leaf(Icons.Rounded.TextFields, "Text") { active.pause(); typingId = null; typing = true },
                leaf(Icons.Rounded.EmojiEmotions, "Sticker") { addSticker = true },
                parent(Icons.Rounded.MusicNote, "Sound", listOf(
                    leaf(Icons.Rounded.MusicNote, "Music, sound") { soundOpen = true },
                    leaf(Icons.Rounded.Mic, "Voice-over") { voiceOn = true },
                )),
                leaf(Icons.Rounded.Gradient, if (s.options.filter == RideFilter.NONE) "Filter" else "Filter: ${s.options.filter.label}", on = s.options.filter != RideFilter.NONE) { filterOpen = true },
            ))
            fun timeKids(earlier: () -> Unit, later: () -> Unit, shorter: () -> Unit, longer: () -> Unit) = listOf(
                leaf(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier", action = earlier),
                leaf(Icons.Rounded.KeyboardDoubleArrowRight, "Later", action = later),
                leaf(Icons.Rounded.UnfoldLess, "Shorter", action = shorter),
                leaf(Icons.Rounded.UnfoldMore, "Longer", action = longer),
            )
            // The clip under the playhead: tab tools work on it without selecting it first.
            fun clipHere(): Int? {
                val starts = TimelineEdits.starts(plan)
                return plan.segments.indices.lastOrNull { k -> plan.segments[k] is ClipSegment && !(plan.segments[k] as ClipSegment).tail && starts[k] <= pos }
            }
            fun openOnClip(tab: ClipTab) {
                val k = clipHere() ?: return run { flash = "Move the playhead onto a clip" }
                pendingTab = tab
                vm.pick(TimelinePick.Clip(k))
            }
            fun tabTree(tab: ToolTab): List<TNode> = when (tab) {
                ToolTab.EDIT -> listOf(
                    leaf(Icons.Rounded.ContentCut, "Split") { vm.change("Split") { TimelineEdits.split(it, pos) } },
                    leaf(Icons.Rounded.Speed, "Speed") { openOnClip(ClipTab.SPEED) },
                    leaf(Icons.Rounded.Crop, "Crop") { openOnClip(ClipTab.FRAME) },
                    leaf(Icons.Rounded.Animation, "Transition", enabled = plan.segments.size > 1) {
                        val k = (clipHere() ?: 1).coerceAtLeast(1).coerceAtMost(plan.segments.size - 1)
                        cutAt = k; loopAt = plan.startOf(k)
                    },
                    parent(Icons.Rounded.MoreVert, "More", listOfNotNull(
                        clipHere()?.let { k -> (plan.segments[k] as? ClipSegment)?.takeIf { it.lines.isNotEmpty() && it.still == null }?.let { leaf(Icons.Rounded.Compress, "Remove pauses") { vm.change("Remove pauses") { TimelineEdits.removePauses(it, k) }; flash = "Pauses removed" } } },
                        leaf(Icons.Rounded.Flag, "Marker") { vm.addMarker() },
                        if (plan.vibe.spec.redline) leaf(Icons.Rounded.Speed, "Tag redline") { vm.tagRedline() } else null,
                        leaf(Icons.Rounded.CropFree, "Safe zones", on = safe) { safe = !safe },
                    )),
                )
                ToolTab.SOUND -> listOf(
                    leaf(Icons.Rounded.Tune, "Mixer") { mixer = true },
                    leaf(Icons.Rounded.Mic, "Voice-over") { voiceOn = true },
                    leaf(Icons.AutoMirrored.Rounded.VolumeDown, "Duck", on = plan.mix.duck) {
                        val on = !plan.mix.duck
                        vm.change(if (on) "Dip under voice" else "No dip under voice") { TrackEdits.duck(it, on) }
                        flash = if (on) "Engine and music dip while you talk" else "No dip while you talk"
                    },
                    leaf(Icons.Rounded.MusicNote, "Sound") { soundOpen = true },
                    parent(Icons.Rounded.MoreVert, "More", listOfNotNull(
                        clipHere()?.let { k -> leaf(Icons.AutoMirrored.Rounded.CallSplit, "Detach clip sound") { vm.detach(k) } },
                    )),
                )
                ToolTab.TEXT -> listOf(
                    leaf(Icons.Rounded.TextFields, "Text") { active.pause(); typingId = null; typing = true },
                    leaf(Icons.Rounded.Subtitles, "Caption") { ask = TextAsk.NewCaption },
                    leaf(Icons.Rounded.ClosedCaption, "Caption look") { captionTab = CaptionTab.LOOK; captionLine = null; captionsOpen = true },
                    leaf(if (s.options.captions) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (s.options.captions) "Hide captions" else "Show captions", on = !s.options.captions) { vm.setOptions { o -> o.copy(captions = !o.captions) } },
                    leaf(Icons.Rounded.EmojiEmotions, "Sticker") { addSticker = true },
                )
                ToolTab.LOOK -> listOf(
                    leaf(Icons.Rounded.Palette, "Styles") { styleOpen = true },
                    leaf(Icons.Rounded.Gradient, if (s.options.filter == RideFilter.NONE) "Filter" else s.options.filter.label, on = s.options.filter != RideFilter.NONE) { filterOpen = true },
                    leaf(Icons.Rounded.Contrast, "Colour") { openOnClip(ClipTab.LOOK) },
                    leaf(Icons.Rounded.Animation, "Transitions", enabled = plan.segments.size > 1) { cutAt = 1; loopAt = plan.startOf(1) },
                    leaf(Icons.Rounded.CropFree, "Safe zones", on = safe) { safe = !safe },
                )
                ToolTab.ADD -> listOf(
                    leaf(Icons.Rounded.VideoLibrary, "Clip") { adding = AddWhat.CLIP },
                    leaf(Icons.Rounded.PictureInPicture, "Video on top") { adding = AddWhat.LAYER },
                    leaf(Icons.Rounded.AcUnit, "Freeze frame") { vm.freeze() },
                    leaf(Icons.Rounded.MusicNote, "Sound") { soundOpen = true },
                    leaf(Icons.Rounded.Mic, "Voice-over") { voiceOn = true },
                )
            }
            fun toolTree(picked: TimelinePick?, seg: ClipSegment?): List<TNode> = when (val p = picked) {
                null -> listOf(
                    addNode(),
                    parent(Icons.Rounded.Palette, "Style", listOf(
                        leaf(Icons.Rounded.Palette, "Styles") { styleOpen = true },
                        leaf(Icons.Rounded.Animation, "Transitions", enabled = plan.segments.size > 1) { cutAt = 1; loopAt = plan.startOf(1) },
                        leaf(Icons.Rounded.Gradient, "Filter", on = s.options.filter != RideFilter.NONE) { filterOpen = true },
                    )),
                    parent(Icons.Rounded.ClosedCaption, "Captions", listOf(
                        leaf(Icons.Rounded.Palette, "Look") { captionTab = CaptionTab.LOOK; captionLine = null; captionsOpen = true },
                        leaf(Icons.Rounded.Subtitles, "Add caption") { ask = TextAsk.NewCaption },
                        leaf(if (s.options.captions) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (s.options.captions) "Hide" else "Show", on = !s.options.captions) { vm.setOptions { o -> o.copy(captions = !o.captions) } },
                    )),
                    parent(Icons.Rounded.Tune, "Sound", listOf(
                        leaf(Icons.Rounded.Tune, "Mixer") { mixer = true },
                        leaf(Icons.Rounded.Mic, "Voice-over") { voiceOn = true },
                        leaf(Icons.Rounded.MusicNote, "Music") { soundOpen = true },
                    )),
                    parent(Icons.Rounded.MoreVert, "More", listOfNotNull(
                        leaf(Icons.Rounded.Flag, "Marker") { vm.addMarker() },
                        if (plan.vibe.spec.redline) leaf(Icons.Rounded.Speed, "Tag redline") { vm.tagRedline() } else null,
                        leaf(Icons.Rounded.CropFree, "Safe zones", on = safe) { safe = !safe },
                    )),
                )
                is TimelinePick.Clip -> {
                    val i = p.index
                    val v = seg?.volume ?: 1f
                    listOf(
                        leaf(Icons.Rounded.ContentCut, "Split") { vm.change("Split") { TimelineEdits.split(it, pos) } },
                        if (seg?.still == null) leaf(Icons.Rounded.Speed, "Speed") { clipTab = ClipTab.SPEED } else leaf(Icons.Rounded.AcUnit, "Freeze") { vm.freeze() },
                        parent(if (v == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Sound", listOf(
                            slider(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", v, 0f..1.5f, pct, { x -> vm.drag { TimelineEdits.volume(it, i, x) } }, { vm.endDrag("Sound") }),
                            leaf(if (v == 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, if (v == 0f) "Unmute" else "Mute", on = v == 0f) { vm.change(if (v == 0f) "Unmute" else "Mute") { TimelineEdits.volume(it, i, if (v == 0f) 1f else 0f) } },
                            leaf(Icons.AutoMirrored.Rounded.CallSplit, "Detach") { vm.detach(i) },
                        )),
                        parent(Icons.Rounded.Contrast, "Look", listOf(
                            leaf(Icons.Rounded.Contrast, "Colour") { clipTab = ClipTab.LOOK },
                            leaf(Icons.Rounded.Crop, "Crop") { clipTab = ClipTab.FRAME },
                            leaf(Icons.Rounded.Animation, "Transition") { if (i == 0) flash = "The first clip has no cut before it" else { cutAt = i; vm.pick(null); loopAt = plan.startOf(i) } },
                        )),
                        parent(Icons.Rounded.MoreVert, "More", listOf(
                            parent(Icons.Rounded.ContentCut, "Edit", listOfNotNull(
                            if (seg?.still == null) leaf(Icons.Rounded.FastRewind, "Reverse", on = seg?.reverse != null) { vm.reverse(i) } else null,
                            if (seg?.lines?.isNotEmpty() == true && seg.still == null) leaf(Icons.Rounded.Compress, "Remove pauses") { vm.change("Remove pauses") { TimelineEdits.removePauses(it, i) }; flash = "Pauses removed" } else null,
                                leaf(Icons.Rounded.AcUnit, "Freeze") { vm.freeze() },
                            )),
                            leaf(Icons.Rounded.UnfoldMore, "Use all", enabled = seg != null && seg.still == null && (seg.inMs > 0 || seg.inMs + seg.sourceMs < seg.bit.clipDurationMs - 20)) { vm.change("Use all") { TimelineEdits.useAll(it, i) } },
                            parent(Icons.Rounded.SwapHoriz, "Arrange", listOfNotNull(
                                leaf(Icons.AutoMirrored.Rounded.ArrowBack, "Move left") { vm.change("Move") { TimelineEdits.move(it, i, -1) }; vm.pick(TimelinePick.Clip(i - 1)) },
                                leaf(Icons.AutoMirrored.Rounded.ArrowForward, "Move right") { vm.change("Move") { TimelineEdits.move(it, i, 1) }; vm.pick(TimelinePick.Clip(i + 1)) },
                                if (seg?.still == null) leaf(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier part") { vm.change("Other part") { ClipTools.slip(it, i, -500) } } else null,
                                if (seg?.still == null) leaf(Icons.Rounded.KeyboardDoubleArrowRight, "Later part") { vm.change("Other part") { ClipTools.slip(it, i, 500) } } else null,
                                leaf(Icons.Rounded.LibraryAddCheck, "Select more") { vm.pick(TimelinePick.Clips(setOf(i))) },
                            )),
                            parent(Icons.Rounded.ContentCopy, "Copy", listOfNotNull(
                                leaf(Icons.Rounded.ContentCopy, "Duplicate") { vm.change("Duplicate") { ClipTools.duplicate(it, i) } },
                                leaf(Icons.Rounded.FormatPaint, "Copy look") { vm.copySettings(i) },
                                if (s.copied != null) leaf(Icons.Rounded.ContentPaste, "Paste look") { vm.pasteSettings(setOf(i)) } else null,
                                leaf(Icons.Rounded.SwapHoriz, "Replace") { adding = AddWhat.REPLACE },
                                leaf(Icons.Rounded.BookmarkAdd, "Save clip") { vm.saveSegment(i) },
                            )),
                            leaf(Icons.Rounded.Delete, "Delete", danger = true) { vm.change("Delete") { TimelineEdits.delete(it, i) }; vm.pick(null) },
                        )),
                    )
                }
                is TimelinePick.Clips -> listOfNotNull(
                    leaf(Icons.AutoMirrored.Rounded.ArrowBack, "Move left") { var moved = p.indices; vm.change("Move clips") { pl -> TrackEdits.moveClips(pl, p.indices, -1).also { moved = it.second }.first }; vm.pick(TimelinePick.Clips(moved)) },
                    leaf(Icons.AutoMirrored.Rounded.ArrowForward, "Move right") { var moved = p.indices; vm.change("Move clips") { pl -> TrackEdits.moveClips(pl, p.indices, 1).also { moved = it.second }.first }; vm.pick(TimelinePick.Clips(moved)) },
                    if (s.copied != null) leaf(Icons.Rounded.ContentPaste, "Paste look") { vm.pasteSettings(p.indices) } else null,
                    leaf(Icons.Rounded.Delete, "Delete ${p.indices.size}", danger = true) { vm.change("Delete clips") { TrackEdits.deleteClips(it, p.indices) }; vm.pick(null) },
                )
                is TimelinePick.Layer -> {
                    val l = plan.layers.firstOrNull { it.id == p.id }
                    val shape = l?.shape ?: LayerShape.ROUNDED
                    val nextShape = LayerShape.entries[(shape.ordinal + 1) % LayerShape.entries.size]
                    val loud = (l?.volume ?: 0f) > 0f
                    val hid = l?.hidden == true
                    listOf(
                        parent(Icons.Rounded.Crop, "Edit", listOf(
                            parent(Icons.Rounded.PictureInPicture, "Place", LayerPreset.entries.take(5).map { pr -> leaf(LAYER_ICON[pr] ?: Icons.Rounded.PictureInPicture, pr.label) { vm.change("Layer: ${pr.label}") { TrackEdits.preset(it, p.id, pr) } } }),
                            parent(Icons.Rounded.ZoomIn, "Size", listOf(
                                leaf(Icons.Rounded.ZoomOut, "Smaller") { vm.change("Layer size") { TrackEdits.resizeLayer(it, p.id, 0.85f, pos) } },
                                leaf(Icons.Rounded.ZoomIn, "Bigger") { vm.change("Layer size") { TrackEdits.resizeLayer(it, p.id, 1.18f, pos) } },
                                leaf(Icons.Rounded.RotateRight, "Rotate") { vm.change("Rotate layer") { TrackEdits.rotateLayer(it, p.id, 90f) } },
                            )),
                            leaf(Icons.Rounded.RoundedCorner, shape.label) { vm.change("Layer shape") { TrackEdits.shape(it, p.id, nextShape) } },
                            parent(Icons.Rounded.Moving, "Motion", listOfNotNull(
                                leaf(Icons.Rounded.Moving, "Move here", on = l?.keys?.isNotEmpty() == true) { vm.change("Layer move point") { TrackEdits.addKey(it, p.id, pos) } },
                                if (l?.keys?.isNotEmpty() == true) leaf(Icons.Rounded.LayersClear, "Clear moves") { vm.change("Clear moves") { TrackEdits.clearKeys(it, p.id) } } else null,
                            )),
                            parent(Icons.Rounded.Timer, "Time", timeKids(
                                { vm.change("Layer time") { TrackEdits.shiftLayer(it, p.id, -500) } }, { vm.change("Layer time") { TrackEdits.shiftLayer(it, p.id, 500) } },
                                { vm.change("Layer length") { TrackEdits.resizeLayerTime(it, p.id, -500) } }, { vm.change("Layer length") { TrackEdits.resizeLayerTime(it, p.id, 500) } },
                            )),
                        )),
                        parent(Icons.Rounded.Contrast, "Look", listOf(
                            slider(Icons.Rounded.Opacity, "Opacity", l?.opacity ?: 1f, 0.1f..1f, pct, { x -> vm.drag { TrackEdits.opacity(it, p.id, x) } }, { vm.endDrag("Layer opacity") }),
                            leaf(Icons.Rounded.BorderOuter, "Frame", on = l?.border == true) { vm.change("Layer frame") { TrackEdits.border(it, p.id, l?.border != true) } },
                            leaf(if (hid) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (hid) "Show" else "Hide", on = hid) { vm.change(if (hid) "Show layer" else "Hide layer") { TrackEdits.hideLayer(it, p.id, !hid) } },
                        )),
                        slider(if (loud) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Volume", l?.volume ?: 0f, 0f..1.5f, pct, { x -> vm.drag { TrackEdits.layerVolume(it, p.id, x) } }, { vm.endDrag("Layer sound") }),
                        parent(Icons.Rounded.MoreVert, "More", listOf(
                            leaf(Icons.Rounded.Delete, "Delete", danger = true) { vm.change("Delete layer") { TrackEdits.deleteLayer(it, p.id) }; vm.pick(null) },
                        )),
                    )
                }
                is TimelinePick.Audio -> {
                    val a = plan.audio.firstOrNull { it.id == p.id }
                    val faded = (a?.fadeInMs ?: 0) > 0
                    listOf(
                        parent(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", listOfNotNull(
                            slider(Icons.AutoMirrored.Rounded.VolumeUp, "Level", a?.volume ?: 1f, 0f..1.5f, pct, { x -> vm.drag { TrackEdits.audioVolume(it, p.id, x) } }, { vm.endDrag("Volume") }),
                            a?.let { item -> TNode(Icons.Rounded.ShowChart, "Volume line", panel = {
                                VolumeLinePanel(
                                    item,
                                    playheadMs = pos - item.startMs,
                                    onAdd = { at, level -> vm.change("Volume point") { TrackEdits.addVolumePoint(it, p.id, at, level, Ease.SMOOTH) } },
                                    onMove = { k, at, level -> vm.drag { TrackEdits.moveVolumePoint(it, p.id, k, at, level) } },
                                    onMoveEnd = { vm.endDrag("Volume point") },
                                    onDelete = { k -> vm.change("Delete point") { TrackEdits.deleteVolumePoint(it, p.id, k) } },
                                    onEase = { k, e -> vm.change("Ease: ${e.label}") { TrackEdits.easePoint(it, p.id, k, e) } },
                                )
                            }) },
                            leaf(Icons.AutoMirrored.Rounded.VolumeDown, "Dip here") { vm.change("Volume point") { TrackEdits.addVolumePoint(it, p.id, pos - (a?.startMs ?: 0), 0.3f, Ease.SMOOTH) } },
                            leaf(Icons.AutoMirrored.Rounded.VolumeUp, "Full here") { vm.change("Volume point") { TrackEdits.addVolumePoint(it, p.id, pos - (a?.startMs ?: 0), 1f, Ease.SMOOTH) } },
                            if (a?.curve?.isNotEmpty() == true) leaf(Icons.Rounded.ClearAll, "Clear points") { vm.change("Clear points") { TrackEdits.clearCurve(it, p.id) } } else null,
                        )),
                        parent(Icons.Rounded.Gradient, "Fades", listOf(
                            slider(Icons.Rounded.Gradient, "Fade in", (a?.fadeInMs ?: 0) / 1000f, 0f..3f, { "%.1f s".format(java.util.Locale.US, it) },
                                { x -> vm.drag { TrackEdits.fade(it, p.id, (x * 1000).toLong(), a?.fadeOutMs ?: 0) } }, { vm.endDrag("Fade in") }),
                            slider(Icons.Rounded.Gradient, "Fade out", (a?.fadeOutMs ?: 0) / 1000f, 0f..3f, { "%.1f s".format(java.util.Locale.US, it) },
                                { x -> vm.drag { TrackEdits.fade(it, p.id, a?.fadeInMs ?: 0, (x * 1000).toLong()) } }, { vm.endDrag("Fade out") }),
                            parent(Icons.Rounded.ShowChart, "Shape: ${(a?.fadeShape ?: FadeShape.LINEAR).label}", FadeShape.entries.map { sh ->
                                leaf(Icons.Rounded.ShowChart, sh.label, on = a?.fadeShape == sh) { vm.change("Fade shape") { TrackEdits.fadeShape(it, p.id, sh) } }
                            }),
                        )),
                        parent(Icons.Rounded.Timer, "Time", listOf(
                            leaf(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier") { vm.change("Sound time") { TrackEdits.shiftAudio(it, p.id, -250) } },
                            leaf(Icons.Rounded.KeyboardDoubleArrowRight, "Later") { vm.change("Sound time") { TrackEdits.shiftAudio(it, p.id, 250) } },
                            leaf(Icons.Rounded.FastRewind, "Start sooner") { vm.change("Sound start") { TrackEdits.trimAudioStart(it, p.id, -250) } },
                            leaf(Icons.Rounded.FastForward, "Start later") { vm.change("Sound start") { TrackEdits.trimAudioStart(it, p.id, 250) } },
                            parent(Icons.Rounded.UnfoldMore, "Length", listOf(
                                leaf(Icons.Rounded.UnfoldLess, "Shorter") { vm.change("Sound length") { TrackEdits.resizeAudio(it, p.id, -250) } },
                                leaf(Icons.Rounded.UnfoldMore, "Longer") { vm.change("Sound length") { TrackEdits.resizeAudio(it, p.id, 250) } },
                            )),
                        )),
                        parent(Icons.Rounded.MoreVert, "More", listOf(
                            leaf(Icons.Rounded.Delete, "Delete", danger = true) { vm.change("Delete sound") { TrackEdits.deleteAudio(it, p.id) }; vm.pick(null) },
                        )),
                    )
                }
                is TimelinePick.Marker -> listOf(
                    leaf(Icons.Rounded.Delete, "Delete mark", danger = true) { vm.change("Delete marker") { TrackEdits.deleteMarker(it, p.id) }; vm.pick(null) },
                )
                is TimelinePick.Caption -> {
                    val off = !s.options.captions
                    listOf(
                        leaf(Icons.Rounded.Edit, "Fix words") { captionTab = CaptionTab.WORDS; captionLine = p.index to p.line; captionsOpen = true },
                        leaf(Icons.Rounded.Palette, "Look") { captionTab = CaptionTab.LOOK; captionLine = null; captionsOpen = true },
                        parent(Icons.Rounded.Timer, "Time", listOf(
                            leaf(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier") { vm.change("Caption time") { TimelineEdits.nudgeCaption(it, p.index, p.line, -200) } },
                            leaf(Icons.Rounded.KeyboardDoubleArrowRight, "Later") { vm.change("Caption time") { TimelineEdits.nudgeCaption(it, p.index, p.line, 200) } },
                        )),
                        parent(Icons.Rounded.MoreVert, "More", listOfNotNull(
                            (plan.segments.getOrNull(p.index) as? ClipSegment)?.let { cs -> leaf(Icons.Rounded.Hearing, "Read again") { vm.readAgain(cs.bit.momentId) } },
                            leaf(if (off) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (off) "Show all" else "Hide all", on = off) { vm.setOptions { o -> o.copy(captions = !o.captions) } },
                            leaf(Icons.Rounded.Delete, "Delete", danger = true) { vm.change("Delete caption") { TimelineEdits.caption(it, p.index, p.line, "") }; vm.pick(null) },
                        )),
                    )
                }
                is TimelinePick.Text -> {
                    val t = plan.texts.firstOrNull { it.id == p.id }
                    val hid = t?.hidden == true
                    val ai = t?.animIn ?: TextAnim.FADE
                    val ao = t?.animOut ?: TextAnim.FADE
                    listOf(
                        leaf(Icons.Rounded.Edit, "Edit text") { active.pause(); typingId = p.id; typing = true },
                        parent(Icons.Rounded.Animation, "Animate", listOf(
                            leaf(Icons.Rounded.Animation, "In: ${ai.label}") { vm.change("Text in") { TextTools.anim(it, p.id, animIn = TextAnim.entries[(ai.ordinal + 1) % TextAnim.entries.size]) } },
                            leaf(Icons.Rounded.Animation, "Out: ${ao.label}") { vm.change("Text out") { TextTools.anim(it, p.id, animOut = TextAnim.entries[(ao.ordinal + 1) % TextAnim.entries.size]) } },
                        )),
                        parent(Icons.Rounded.Timer, "Time", timeKids(
                            { vm.change("Text time") { TimelineEdits.moveText(it, p.id, -500) } }, { vm.change("Text time") { TimelineEdits.moveText(it, p.id, 500) } },
                            { vm.change("Text length") { TimelineEdits.resizeText(it, p.id, -500) } }, { vm.change("Text length") { TimelineEdits.resizeText(it, p.id, 500) } },
                        )),
                        parent(Icons.Rounded.MoreVert, "More", listOf(
                            leaf(if (hid) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (hid) "Show" else "Hide", on = hid) { vm.change(if (hid) "Show text" else "Hide text") { TrackEdits.hideText(it, p.id, !hid) } },
                            leaf(Icons.Rounded.Delete, "Delete", danger = true) { vm.change("Delete text") { TimelineEdits.deleteText(it, p.id) }; vm.pick(null) },
                        )),
                    )
                }
                is TimelinePick.Sticker -> {
                    val st = plan.stickers.firstOrNull { it.id == p.id }
                    val hid = st?.hidden == true
                    listOfNotNull(
                        if (st?.kind == StickerKind.EMOJI) leaf(Icons.Rounded.EmojiEmotions, "Change") { addSticker = true } else null,
                        parent(Icons.Rounded.ZoomIn, "Size", listOf(
                            leaf(Icons.Rounded.ZoomOut, "Smaller") { vm.change("Sticker size") { TextTools.scaleSticker(it, p.id, 0.85f) } },
                            leaf(Icons.Rounded.ZoomIn, "Bigger") { vm.change("Sticker size") { TextTools.scaleSticker(it, p.id, 1.18f) } },
                            leaf(Icons.Rounded.RotateRight, "Turn") { vm.change("Turn sticker") { TextTools.rotateSticker(it, p.id, 15f) } },
                        )),
                        parent(Icons.Rounded.Timer, "Time", timeKids(
                            { vm.change("Sticker time") { TextTools.shiftSticker(it, p.id, -500) } }, { vm.change("Sticker time") { TextTools.shiftSticker(it, p.id, 500) } },
                            { vm.change("Sticker length") { TextTools.resizeStickerTime(it, p.id, -500) } }, { vm.change("Sticker length") { TextTools.resizeStickerTime(it, p.id, 500) } },
                        )),
                        parent(Icons.Rounded.MoreVert, "More", listOf(
                            leaf(Icons.Rounded.ContentCopy, "Duplicate") { st?.let { vm.addSticker(it.kind, it.text) } },
                            leaf(if (hid) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (hid) "Show" else "Hide", on = hid) { vm.change(if (hid) "Show sticker" else "Hide sticker") { TrackEdits.hideSticker(it, p.id, !hid) } },
                            leaf(Icons.Rounded.Delete, "Delete", danger = true) { vm.change("Delete sticker") { TextTools.deleteSticker(it, p.id) }; vm.pick(null) },
                        )),
                    )
                }
                else -> listOf(addNode())
            }

            val seg = (s.pick as? TimelinePick.Clip)?.let { plan.segments.getOrNull(it.index) as? ClipSegment }
            val barKey = if (cutAt != null) "cut" else if (voiceOn) "voice" else if (filterOpen) "filter" else "${s.pick?.javaClass?.simpleName}:${(s.pick as? TimelinePick.Clip)?.index}:$clipTab"
            androidx.compose.runtime.key(barKey) {
                val cut = cutAt
                val picked = s.pick
                if (cut != null) {
                    val tr = (plan.segments.getOrNull(cut) as? ClipSegment)?.transition ?: if (Cuts.plays(plan, cut)) Transition() else Transition(TransitionKind.CUT)
                    val at = plan.startOf(cut)
                    TransitionPicker(
                        plan.vibe,
                        tr,
                        looping = loopAt != null,
                        fit = { ms -> Cuts.fitted(ms, plan.segments.getOrNull(cut - 1)?.durMs, plan.segments.getOrNull(cut)?.durMs) },
                        onPick = { t -> tick(); vm.change("Transition") { ClipTools.transition(it, cut, t) }; loopAt = at },
                        onLength = { t -> loopAt = null; vm.drag { ClipTools.transition(it, cut, t) } },
                        onLengthEnd = { vm.endDrag("Transition length"); loopAt = at },
                        onPreview = { if (loopAt != null) { loopAt = null; active.pause() } else loopAt = at },
                        onAll = { vm.change("Transition on all cuts") { ClipTools.transitionAll(it, tr) }; flash = "${tr.kind.label} on every cut" },
                        onDone = { cutAt = null; loopAt = null; active.pause() },
                    )
                } else if (filterOpen) {
                    // Filters right under the video, so each one shows on it as it's tapped.
                    FilterPanel(vm, s.options, onPicked = { f -> tick(); flash = f.label }) { filterOpen = false }
                } else if (!voiceOn && !(picked is TimelinePick.Clip && seg != null && clipTab != ClipTab.EDIT)) {
                    // At most 5 icons, never scrolling: a parent opens its own set, ‹ goes back up.
                    if (picked == null) {
                        // Nothing selected: the tab's tools over five fixed tabs, one tap between them.
                        ToolBar(tabTree(toolTab), toolPath, onPath = { toolPath = it }, label = toolTab.label)
                        ToolTabs(toolTab) { t -> toolTab = t; toolPath = emptyList() }
                    } else {
                        // Something selected: only its tools, its name on the left, ✕ back to the tabs.
                        ToolBar(toolTree(picked, seg), toolPath, onPath = { toolPath = it }, label = pickLabel(picked), onClose = { vm.pick(null) })
                    }
                } else if (!voiceOn && picked is TimelinePick.Clip && seg != null) {
                    val i = picked.index
                    val back = { clipTab = ClipTab.EDIT; active.pause() }
                    val cancel = {
                        panelFrom?.let { (n, _) -> if (s.steps.size > n) { vm.undoTo(n); vm.pick(TimelinePick.Clip(i)) } }
                        clipTab = ClipTab.EDIT
                        active.pause()
                    }
                    val applyAll: (() -> Unit)? = when (clipTab) {
                        ClipTab.SPEED -> { -> vm.change("Speed on every clip") { p -> ClipTools.speedAll(p, i) }; flash = "Speed on every clip" }
                        ClipTab.LOOK -> { -> vm.change("Look on every clip") { p -> ClipTools.colorAll(p, i) }; flash = "Look on every clip" }
                        else -> null
                    }
                    Column {
                    when (clipTab) {
                        ClipTab.SPEED -> SpeedPanel(
                            seg,
                            fromMs = panelFrom?.second,
                            onSpeed = { v -> vm.drag { ClipTools.speed(it, i, v) } },
                            onSpeedEnd = { vm.endDrag("Speed") },
                            onRamp = { r -> vm.change("Speed curve") { ClipTools.ramp(it, i, r) } },
                            onReverse = { vm.reverse(i) },
                            onFreeze = { vm.freeze() },
                            onMute = { vm.change(if (seg.volume == 0f) "Unmute" else "Mute") { TimelineEdits.volume(it, i, if (seg.volume == 0f) 1f else 0f) } },
                            onBack = back,
                            marks = s.rideTraces[seg.bit.momentId]?.marks.orEmpty(),
                            onPoints = { pts -> vm.drag { ClipTools.speedPoints(it, i, pts) } },
                            onPointsEnd = { vm.endDrag("Speed curve") },
                            onPointsSet = { label, pts -> vm.change(label) { ClipTools.speedPoints(it, i, pts) } },
                            playFraction = run {
                                val local = (pos - plan.startOf(i)).coerceAtLeast(0)
                                // Output time back to the part's own time, along the curve.
                                var src = 0f; var out = 0.0; val st = seg.steps; var prev = 0f
                                val total = Speed.outPerSource(seg.speed, st) * seg.sourceMs
                                for ((end, mult) in st) {
                                    val span = (end - prev) * seg.sourceMs / (seg.speed * mult)
                                    if (out + span >= local) { src = prev + ((local - out) / span * (end - prev)).toFloat(); break }
                                    out += span; prev = end; src = end
                                }
                                if (total <= 0) null else src
                            },
                        )
                        ClipTab.LOOK -> LookPanel(
                            seg.color,
                            onColor = { c -> vm.drag { ClipTools.color(it, i) { c } } },
                            onColorEnd = { label -> vm.endDrag(label) },
                            onPreset = { name, c -> vm.change("Look: $name") { ClipTools.color(it, i) { c } } },
                            onBack = back,
                        )
                        ClipTab.FRAME -> CropPanel(
                            seg,
                            onRotate = { vm.change("Rotate") { ClipTools.rotate(it, i) } },
                            onMirror = { vm.change("Mirror") { ClipTools.flip(it, i) } },
                            onPushIn = { vm.change("Slow push-in") { p -> ClipTools.reframe(ClipTools.reframe(ClipTools.clearFrame(p, i), i, 0), i, seg.durMs, dZoom = 0.25f) } },
                            onPunch = { vm.change("Punch-in") { ClipTools.punchIn(it, i) } },
                            onReset = { vm.change("Reset crop") { p -> ClipTools.clearFrame(p, i).let { q -> if (seg.flip) ClipTools.flip(q, i) else q }.let { q -> var r = q; repeat(((360 - seg.rotation) % 360) / 90) { r = ClipTools.rotate(r, i) }; r } } },
                            onBack = back,
                        )
                        ClipTab.EDIT -> Unit
                    }
                    PanelActions(onCancel = cancel, onApplyAll = applyAll, onDone = back)
                    }
                } else if (voiceOn) {
                    VoicePanel(
                        s.takes,
                        s.recordingAt,
                        { pos },
                        allowed = micAllowed,
                        onAsk = { askMic.launch(android.Manifest.permission.RECORD_AUDIO) },
                        onStart = {
                            if (pos >= plan.totalMs - 200) seekTo(0)
                            preview.setLevel(0f)
                            vm.startTake(pos)
                            active.play()
                        },
                        onStop = { vm.stopTake(); active.pause(); preview.setLevel(1f) },
                        onDelete = { vm.deleteTake(it) },
                        onBack = { if (s.recordingAt != null) { vm.stopTake(); active.pause(); preview.setLevel(1f) }; voiceOn = false },
                    )
                }
            }
            Text(
                when {
                    cutAt != null -> "Tap a transition: the cut plays on a loop. Done when you like it."
                    filterOpen -> "Tap a filter: it shows on the video. It goes under every graphic."
                    s.pick == null -> "Tap the video to play, swipe it for another style. Drag a caption up or down to move the captions. Hold a block on the timeline to drag it. Pinch the timeline to zoom."
                    else -> when (s.pick) {
                        is TimelinePick.Clip -> "Drag the white edges to trim. Split cuts at the playhead."
                        is TimelinePick.Clips -> "Tap clips to add or take them out of the selection."
                        is TimelinePick.Layer -> "Drag the layer on the video to move it, pinch to resize. On the timeline, hold it and drag up to put it over the others."
                        is TimelinePick.Audio -> "A sound can run past its cut. Dip here and Full here shape its volume."
                        is TimelinePick.Text -> "Drag the text on the video to place it; pinch to size and turn."
                        else -> "Layers show as stills here; they play in the saved video."
                    }
                },
                style = RtType.caption,
                color = RtColors.TextTertiary,
            )
            s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextPrimary) }
        }
    }

        // ---- sheets over the editor
        val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> if (uris.isNotEmpty()) vm.addPhoneVideos(uris) }
        val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.addSound(uri) }
        StudioSheet(addOpen, { addOpen = false }, "Add videos") {
            Text("They go in at the playhead.", style = RtType.caption, color = RtColors.TextSecondary)
            SheetRow("From your phone") { addOpen = false; pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            SheetRow("From your rides and saved clips", "Find one by what you said") { addOpen = false; adding = AddWhat.CLIP }
            SheetRow("Over the video", "Picture in picture, at the playhead") { addOpen = false; adding = AddWhat.LAYER }
        }
        StudioSheet(soundOpen, { soundOpen = false }, "Add sound") {
            Text("Starts at the playhead, on its own row. Add as many as you like.", style = RtType.caption, color = RtColors.TextSecondary)
            SheetRow("Music or a sound from your phone") { soundOpen = false; pickSound.launch(arrayOf("audio/*")) }
            if (s.engineFiles.isNotEmpty() && plan.audio.none { it.kind == TrackKind.ENGINE }) {
                SheetRow("Engine sound from the second mic", "Lined up under every clip that has it") { soundOpen = false; vm.addEngine() }
            }
            SheetRow("A clip's own sound", "Tap the clip, then Detach sound: it can run past its cut") { soundOpen = false; flash = "Tap a clip, then Detach sound" }
            SheetRow("Volumes and the mix") { soundOpen = false; mixer = true }
        }
        CaptionsSheet(vm, plan, captionsOpen, captionTab, captionLine) { captionsOpen = false }
        noisePick?.let { (audioId, atMs, horn) ->
            val current = plan.audio.firstOrNull { it.id == audioId }?.noise?.get(atMs) ?: NoiseChoice.REDUCE
            StudioSheet(true, { noisePick = null }, if (horn) "Horn in the engine sound" else "Bump in the engine sound") {
                Text(
                    if (horn) "Turned down by default: its own pitches are taken out, the engine stays." else "Softened by default: the thud dips for a moment, the engine carries on.",
                    style = RtType.caption, color = RtColors.TextSecondary,
                )
                val options = if (horn) {
                    listOf(NoiseChoice.REDUCE to "Reduce horn", NoiseChoice.MUTE to "Mute the engine sound here", NoiseChoice.KEEP to "Keep it")
                } else {
                    listOf(NoiseChoice.REDUCE to "Soften bump", NoiseChoice.KEEP to "Keep it")
                }
                options.forEach { (choice, label) ->
                    SheetRow(label + if (choice == current) "  ✓" else "") { vm.noiseChoice(audioId, atMs, choice); noisePick = null }
                }
            }
        }
        MixerSheet(vm, s, plan, mixer) { mixer = false }
        StickersSheet(
            addSticker,
            onPick = { kind, text ->
                val sel = (s.pick as? TimelinePick.Sticker)?.id?.let { id -> plan.stickers.firstOrNull { it.id == id } }
                if (sel?.kind == StickerKind.EMOJI && kind == StickerKind.EMOJI) {
                    vm.change("Change emoji") { p -> p.copy(stickers = p.stickers.map { if (it.id == sel.id) it.copy(text = text) else it }) }
                } else {
                    vm.addSticker(kind, text)
                }
                addSticker = false
            },
            onDismiss = { addSticker = false },
        )
        HistorySheet(
            s.steps,
            plan.markers,
            historyOpen,
            onUndoTo = { vm.undoTo(it) },
            onSeek = { ms -> seekTo(ms); scope.launch { scroll.animateScrollTo(map.x(ms).roundToInt()) } },
            onDeleteMark = { id -> vm.change("Delete marker") { TrackEdits.deleteMarker(it, id) } },
            onDismiss = { historyOpen = false },
        )
        if (typing) {
            val editingText = typingId?.let { id -> plan.texts.firstOrNull { it.id == id } }
            TextComposer(editingText, plan.vibe, onCancel = { typing = false }) { d ->
                typing = false
                fun styled(t: TextItem) = t.copy(look = d.look, font = d.font, color = d.color, size = d.size, align = d.align, animIn = d.anim, animOut = d.anim)
                when {
                    editingText == null -> if (d.text.isNotBlank()) vm.addText(d.text) { styled(it) }
                    d.text.isBlank() -> { vm.change("Delete text") { TimelineEdits.deleteText(it, editingText.id) }; vm.pick(null) }
                    else -> vm.change("Edit text") { p -> p.copy(texts = p.texts.map { if (it.id == editingText.id) styled(it).copy(text = d.text.trim()) else it }) }
                }
            }
        }
        StudioSheet(ideasOpen, { ideasOpen = false }, "Ideas") {
            var changeThis by remember { mutableStateOf(true) }
            var wish by remember { mutableStateOf("") }
            if (vm.canAsk) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised).padding(3.dp)) {
                    listOf(true to "Change this one", false to "Make a new one").forEach { (k, label) ->
                        Text(
                            label,
                            style = RtType.button,
                            color = RtColors.TextPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (changeThis == k) RtColors.Surface else Color.Transparent)
                                .clickable(role = Role.Tab) { changeThis = k }.padding(vertical = 8.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = wish,
                        onValueChange = { wish = it },
                        placeholder = { Text(if (changeThis) "Start with the Tooooo, fewer cuts" else "A 15 s one about the rain", style = RtType.body, color = RtColors.TextTertiary) },
                        enabled = s.planning == null,
                        textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    RoundIcon(Icons.Rounded.ArrowUpward, if (changeThis) "Change it" else "Make it", primary = wish.isNotBlank()) {
                        if (wish.isNotBlank() && s.planning == null) {
                            if (changeThis) vm.reviseInEditor(wish) else vm.ask(wish, open = true)
                            wish = ""
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_ASKS.forEach { q -> Choice(q, false) { wish = if (q.endsWith("…")) q.dropLast(1) + " " else q } }
                }
                (s.planning ?: s.geminiIssue)?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
                Text("Studio remembers what you like.", style = RtType.caption, color = RtColors.TextTertiary)
            }
            Text("OTHER IDEAS FROM THIS RIDE", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                s.pieces.forEach { pc ->
                    val current = pc.key == s.piece?.key
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { ideasOpen = false; if (!current) vm.editIdea(pc.key) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(pc.opening?.bit?.momentId?.let { s.thumbs[it] }, Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pc.script.title.ifBlank { "Idea" }, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pc.script.format.label} · ${(pc.plan.totalMs + 500) / 1000} s", style = RtType.caption, color = RtColors.TextSecondary)
                        }
                        if (current) Text("Editing", style = RtType.caption, color = RtColors.Primary)
                    }
                }
                if (s.pieces.isEmpty()) Text("No other ideas for this one yet.", style = RtType.body, color = RtColors.TextSecondary, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (vm.canAsk && s.planning == null) SheetRow("Every story in this ride", "Gemini lists all of them") { vm.suggestAll() }
        }
    }
    ask?.let { a ->
        var value by remember(a) {
            mutableStateOf(
                when (a) {
                    is TextAsk.EditText -> a.text
                    is TextAsk.EditCaption -> a.text
                    else -> ""
                },
            )
        }
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(when (a) { TextAsk.NewText -> "Text on the video"; TextAsk.NewCaption -> "New caption"; is TextAsk.EditText -> "Edit text"; is TextAsk.EditCaption -> "Edit caption" }) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (a) {
                        TextAsk.NewText -> if (value.isNotBlank()) vm.addText(value)
                        TextAsk.NewCaption -> if (value.isNotBlank()) vm.addCaption(value)
                        is TextAsk.EditText -> vm.change("Edit text") { TimelineEdits.editText(it, a.id, value) }
                        is TextAsk.EditCaption -> vm.change("Edit caption") { TimelineEdits.caption(it, a.index, a.line, value) }
                    }
                    ask = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
        )
    }
    adding?.let { what ->
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> if (uris.isNotEmpty()) vm.addPhoneVideos(uris) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { adding = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when (what) {
                        AddWhat.LAYER -> "Layer over the video, at the playhead"
                        AddWhat.REPLACE -> "Replace the clip (same length)"
                        AddWhat.CLIP -> "Add at the playhead"
                    },
                    style = RtType.bodyStrong,
                    color = RtColors.TextPrimary,
                )
                Text("From your phone", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)); adding = null
                }.padding(vertical = 6.dp))
                var find by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    placeholder = { Text("Find what you said…", style = RtType.body, color = RtColors.TextTertiary) },
                    singleLine = true,
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = if (what == AddWhat.LAYER) vm.insertable() + vm.layerable() else vm.insertable()
                list.distinctBy { it.id }.filter { find.isBlank() || ClipSearch.matches(find, it.lines.joinToString(" ") { l -> l.text }) }.take(50).forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                            when (what) {
                                AddWhat.LAYER -> vm.addLayer(b, LayerPreset.CORNER)
                                AddWhat.REPLACE -> (s.pick as? TimelinePick.Clip)?.let { vm.replace(it.index, b) }
                                AddWhat.CLIP -> vm.insert(b)
                            }
                            adding = null
                        }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(s.thumbs[b.momentId], Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.lines.joinToString(" ") { it.text }.ifBlank { "Riding · ${Units.kmhText(b.speedKmh)}" }, style = RtType.body, color = RtColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(b.fromRide, b.source?.takeIf { b.fromRide == null }?.let { "Phone" }, if (b.camera == "back") "Road" else null, String.format(Locale.US, "%.1f s", (b.outMs - b.inMs) / 1000.0)).joinToString(" · "), style = RtType.caption, color = RtColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
    if (styleOpen) {
        val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
        StylePicker(vm, s, onAll = { nav(com.ridetrack.app.ui.nav.Routes.STYLES) }, timeline = true) { styleOpen = false }
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Discard your changes?") },
            text = { Text("The Reel stays as it was.") },
            confirmButton = { TextButton(onClick = { discard = false; vm.closeEdit() }) { Text("Discard", color = RtColors.Error) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } },
        )
    }
}

/**
 * Something placed on the video at [x], [y] (0..1), [scale] and [rotation]: tap selects it; the
 * selected one drags, pinches and turns, and [onDone] gets the move (fractions), zoom and turn.
 */
@Composable
private fun Placed(
    x: Float,
    y: Float,
    scale: Float,
    rotation: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onDone: (dx: Float, dy: Float, zoom: Float, turn: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        var dx by remember(x, y) { mutableFloatStateOf(0f) }
        var dy by remember(x, y) { mutableFloatStateOf(0f) }
        var z by remember(scale) { mutableFloatStateOf(1f) }
        var r by remember(rotation) { mutableFloatStateOf(0f) }
        Box(
            Modifier.align(Alignment.Center)
                .offset { IntOffset(((x - 0.5f) * wPx + dx).roundToInt(), ((y - 0.5f) * hPx + dy).roundToInt()) }
                .graphicsLayer { scaleX = scale * z; scaleY = scale * z; rotationZ = rotation + r }
                .clickable(role = Role.Button, onClickLabel = "Select", onClick = onSelect)
                .pointerInput(selected) {
                    if (!selected) return@pointerInput
                    detectTransformGestures { _, pan, zoom, turn ->
                        dx += pan.x * scale * z
                        dy += pan.y * scale * z
                        z = (z * zoom).coerceIn(0.2f, 5f)
                        r += turn
                    }
                }
                .pointerInput(selected, "end") {
                    if (!selected) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val e = awaitPointerEvent()
                        } while (e.changes.any { it.pressed })
                        if (dx != 0f || dy != 0f || z != 1f || r != 0f) onDone(dx / wPx, dy / hPx, z, r)
                    }
                },
        ) { content() }
    }
}

/** A sticker as the editor shows it (the made video draws it properly, with live numbers). */
@Composable
private fun StickerPreview(st: StickerItem, hidden: Boolean, selected: Boolean, unit: Float) {
    val frame = if (selected) Modifier.border(2.dp, RtColors.Primary, RoundedCornerShape(8.dp)) else Modifier
    val ink = if (hidden) Color.Transparent else Color.White
    when (st.kind) {
        StickerKind.SPEED, StickerKind.LEAN -> Box(
            frame.size((184 * unit).dp).clip(CircleShape).background(if (hidden) Color.Transparent else Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (st.kind == StickerKind.SPEED) "${Units.fromKmh(62.0)}\n${Units.speedUnit.uppercase()}" else "32°", style = RtType.bodyStrong.copy(fontSize = (28 * unit).sp, textAlign = TextAlign.Center), color = ink)
        }
        StickerKind.EMOJI -> Text(st.text.ifEmpty { "🔥" }, style = TextStyle(fontSize = (110 * unit).sp, color = if (hidden) Color.Transparent else Color.Unspecified), modifier = frame)
        StickerKind.ARROW -> Text("➜", style = TextStyle(fontSize = (140 * unit).sp, color = ink), modifier = frame)
        StickerKind.CIRCLE -> Box(frame.size((240 * unit).dp).border((5 * unit).dp, if (hidden) Color.Transparent else Color(Tok.YELLOW), CircleShape))
    }
}

/**
 * A layer on the preview, where it is at the playhead (read as it's laid out, not every frame in
 * the composition). With [play] its video plays in step with the edit; otherwise a still shows.
 * The selected one drags to move and pinches to resize.
 */
@Composable
private fun LayerPreview(
    vm: StudioViewModel,
    s: StudioState,
    l: LayerItem,
    pos: () -> Long,
    main: Player,
    uri: android.net.Uri?,
    boxW: Float,
    boxH: Float,
    selected: Boolean,
    play: Boolean,
) {
    var dx by remember(l) { mutableFloatStateOf(0f) }
    var dy by remember(l) { mutableFloatStateOf(0f) }
    var scale by remember(l) { mutableFloatStateOf(1f) }
    val shape: Shape = when (l.shape) {
        LayerShape.RECT -> RoundedCornerShape(0.dp)
        LayerShape.ROUNDED -> RoundedCornerShape((Tok.R_MD * boxW / 540f).dp)
        LayerShape.CIRCLE -> CircleShape
    }
    val density = LocalDensity.current
    Box(
        Modifier
            .layout { m, c ->
                val k = TrackEdits.keyAt(l, pos() - l.startMs)
                val w = (boxW * k.w).dp.roundToPx().coerceAtLeast(1)
                val h = (boxW * k.w / l.aspect).dp.roundToPx().coerceAtLeast(1)
                val p = m.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) {
                    p.place(((boxW * k.cx).dp.toPx() - w / 2f + dx).roundToInt(), ((boxH * k.cy).dp.toPx() - h / 2f + dy).roundToInt())
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = l.opacity }
            .rotate(l.rotation)
            .clip(shape)
            .then(if (l.border) Modifier.border((Tok.BORDER * boxW / 540f).dp, Color.White.copy(alpha = Tok.BORDER_A), shape) else Modifier)
            .then(if (selected) Modifier.border(2.dp, RtColors.Primary, shape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = "Select layer") { vm.pick(TimelinePick.Layer(l.id)) }
            .pointerInput(l.id, selected) {
                if (!selected) return@pointerInput
                detectTransformGestures(
                    onGesture = { _, pan, zoom, _ ->
                        dx += pan.x
                        dy += pan.y
                        scale = (scale * zoom).coerceIn(0.3f, 3f)
                    },
                )
            }
            .pointerInput(l.id, selected, "end") {
                if (!selected) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val e = awaitPointerEvent()
                    } while (e.changes.any { it.pressed })
                    // The gesture ended: keep where it was put.
                    val mx = with(density) { dx / (boxW.dp.toPx()) }
                    val my = with(density) { dy / (boxH.dp.toPx()) }
                    val f = scale
                    val at = pos()
                    if (mx != 0f || my != 0f || f != 1f) {
                        vm.change("Move layer") { p ->
                            val moved = TrackEdits.moveLayer(p, l.id, mx, my, at)
                            if (f != 1f) TrackEdits.resizeLayer(moved, l.id, f, at) else moved
                        }
                    }
                }
            },
    ) {
        Thumb(s.thumbs[l.bit.momentId], Modifier.fillMaxSize())
        if (play && uri != null) LayerVideo(l, uri, main, pos)
    }
}

/**
 * A layer's own video, kept in step with the edit: it plays while the edit plays and shows the
 * right frame while scrubbing. Silent (its sound, when on, is in the preview's sound mix).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LayerVideo(l: LayerItem, uri: android.net.Uri, main: Player, pos: () -> Long) {
    val context = LocalContext.current
    val player = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            volume = 0f
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val layer by rememberUpdatedState(l)
    LaunchedEffect(player) {
        while (true) {
            val at = layer
            val want = (at.inMs + pos() - at.startMs).coerceAtLeast(0)
            if (main.isPlaying) {
                if (!player.playWhenReady) player.playWhenReady = true
                if (abs(player.currentPosition - want) > LAYER_DRIFT_MS) player.seekTo(want)
            } else {
                if (player.playWhenReady) player.playWhenReady = false
                if (abs(player.currentPosition - want) > FRAME_MS) player.seekTo(want)
            }
            delay(60)
        }
    }
    AndroidView(
        factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(com.ridetrack.app.R.layout.layer_player, null) as PlayerView).also { it.player = player } },
        modifier = Modifier.fillMaxSize(),
    )
}

/** "Recording" and how long, over the video (reads the playhead on its own). */
@Composable
private fun RecordingPill(pos: () -> Long, from: Long, modifier: Modifier) {
    Pill("● Recording · ${clock((pos() - from).coerceAtLeast(0))}", modifier)
}

/** A group of timeline rows, [count] high, named on the left. Nothing at all when empty. */
@Composable
private fun Rows(count: Int, name: String, icon: ImageVector, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    if (count <= 0) return
    Box(Modifier.fillMaxWidth().height(ROW * count)) {
        content()
        RowName(name, icon)
    }
}

/**
 * The timeline's left edge, in the rows' own coordinates (it moves as the timeline scrolls):
 * row names stay pinned there, and block labels slide along so they're never off screen.
 */
private val LocalRowLeft = androidx.compose.runtime.staticCompositionLocalOf<() -> Int> { { 0 } }

/** The snapping guide's place on the timeline (px from its start), or null when nothing's snapped. */
private val LocalGuide = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.runtime.mutableStateOf<Float?>(null) }

/** Faster than this (px per ms) a drag passes through snap points. */
private const val FAST_PX_PER_MS = 0.6f

/** The fixed column of row names: an icon over the row's left edge, named for TalkBack. */
@Composable
private fun RowName(name: String, icon: ImageVector) {
    val left = LocalRowLeft.current
    Box(
        Modifier
            .offset { IntOffset(left(), 0) }
            .zIndex(4f)
            .width(ROW_NAME_W)
            .fillMaxHeight()
            .background(RtColors.Background.copy(alpha = 0.94f))
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.size(16.dp)) }
}

/**
 * A block on a timeline row, from [startMs] for [durMs]. Tap selects it. With [onDrop], hold it
 * and drag: sideways moves it in time, up or down to another row; [onDrop] gets how far (ms) and
 * how many rows (negative: up).
 */
@Composable
private fun TrackBlock(
    label: String,
    startMs: Long,
    durMs: Long,
    row: Int,
    map: TimeMap,
    color: Color,
    selected: Boolean,
    hidden: Boolean = false,
    onClick: () -> Unit,
    /** While moving: (start it would have, its length, direction) → the start to snap to and where the guide goes. */
    snap: ((Long, Long, Int) -> Pair<Long, Long>?)? = null,
    /** A sound's level along the block (0..1 of its length → 0..1.5), drawn as a shape: fades and points show as you change them. */
    envelope: ((Float) -> Float)? = null,
    onDrop: ((deltaMs: Long, rows: Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val pxPerMs = map.pxPerMs
    val guide = LocalGuide.current
    val snapNow by rememberUpdatedState(snap)
    val startNow by rememberUpdatedState(startMs)
    val durNow by rememberUpdatedState(durMs)
    val mapNow by rememberUpdatedState(map)
    // Where the block shows while moving: the finger, or the snap point it's pulled onto.
    var shown by remember { mutableFloatStateOf(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val rowPx = with(density) { ROW.toPx() }
    val drop by rememberUpdatedState(onDrop)
    val perMs by rememberUpdatedState(pxPerMs)
    Box(
        Modifier.offset { IntOffset((map.x(startMs) + (if (held) shown else dx)).roundToInt(), (row * rowPx + dy).roundToInt()) }
            .zIndex(if (held) 1f else 0f)
            .width(with(density) { (map.xEnd(startMs + durMs.coerceAtLeast(200)) - map.x(startMs)).toDp() }).height(ROW - 4.dp)
            .graphicsLayer {
                alpha = if (hidden && !held) 0.45f else 1f
                if (held) { scaleY = 1.08f; shadowElevation = 8.dp.toPx() }
            }
            .clip(RoundedCornerShape(6.dp)).background(color)
            .then(
                if (envelope == null) Modifier else Modifier.drawBehind {
                    // The level as a filled shape from the bottom; 100% sits at two thirds of the height.
                    val n = 48
                    val path = androidx.compose.ui.graphics.Path()
                    path.moveTo(0f, size.height)
                    for (k in 0..n) {
                        val f = k / n.toFloat()
                        val lv = envelope(f).coerceIn(0f, 1.5f) / 1.5f
                        path.lineTo(f * size.width, size.height * (1f - lv))
                    }
                    path.lineTo(size.width, size.height)
                    path.close()
                    drawPath(path, Color.White.copy(alpha = 0.22f))
                },
            )
            .then(if (selected || held) Modifier.border(2.dp, RtColors.Primary, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .then(
                if (onDrop == null) Modifier else Modifier.pointerInput(Unit) {
                    var lastT = 0L
                    var lastSnap: Long? = null
                    detectDragGesturesAfterLongPress(
                        onDragStart = { held = true; dx = 0f; dy = 0f; shown = 0f; lastT = 0L; lastSnap = null; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                        onDragEnd = {
                            val ms = (shown / perMs).toLong()
                            val rows = (dy / rowPx).roundToInt()
                            held = false; dx = 0f; dy = 0f; shown = 0f; guide.value = null
                            if (ms != 0L || rows != 0) drop?.invoke(ms, rows)
                        },
                        onDragCancel = { held = false; dx = 0f; dy = 0f; shown = 0f; guide.value = null },
                    ) { ch, amount ->
                        ch.consume(); dx += amount.x; dy += amount.y
                        // Moving fast passes through snap points; slowing near one pulls onto it.
                        val now = ch.uptimeMillis
                        val fast = lastT > 0 && kotlin.math.abs(amount.x) / (now - lastT).coerceAtLeast(1) > FAST_PX_PER_MS
                        lastT = now
                        val dir = if (amount.x > 0) 1 else if (amount.x < 0) -1 else 0
                        val raw = startNow + (dx / perMs).toLong()
                        val hit = if (fast || dir == 0) null else snapNow?.invoke(raw, durNow, dir)
                        shown = if (hit != null) (hit.first - startNow) * perMs else dx
                        guide.value = hit?.let { mapNow.x(it.second) }
                        if (hit != null && hit.first != lastSnap) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        lastSnap = hit?.first
                    }
                },
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The label slides along the block to stay just right of the row names while the block's
        // start is scrolled off screen.
        val left = LocalRowLeft.current
        val nameW = with(density) { (ROW_NAME_W + 4.dp).roundToPx() }
        val blockX = map.x(startMs).roundToInt()
        val room = ((durMs.coerceAtLeast(200) * pxPerMs) - with(density) { 60.dp.toPx() }).roundToInt().coerceAtLeast(0)
        Row(
            Modifier.offset { IntOffset((left() + nameW - blockX).coerceIn(0, room), 0) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hidden) Icon(Icons.Rounded.VisibilityOff, contentDescription = "Hidden", tint = RtColors.TextPrimary, modifier = Modifier.size(12.dp))
            Text(label, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small text button. */
@Composable
private fun Small(label: String, on: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = RtType.button,
        color = if (on) RtColors.Primary else RtColors.TextPrimary,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Two fingers pinch to zoom (one finger still scrolls). */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val z = event.calculateZoom()
                if (z != 1f) onZoom(z)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** A white edge on the selected clip: drag it to trim; reports ms since the drag started. */
@Composable
private fun TrimHandle(modifier: Modifier, pxPerMs: Float, onEnd: () -> Unit, limit: Boolean = false, onDrag: (ms: Long, dir: Int, fast: Boolean) -> Unit) {
    var acc by remember { mutableFloatStateOf(0f) }
    var lastT by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    Box(
        modifier.width(14.dp).fillMaxHeight().background(Color.White)
            .pointerInput(pxPerMs) {
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                ) { ch, dx ->
                    ch.consume()
                    acc += dx
                    val now = ch.uptimeMillis
                    val fast = lastT > 0 && kotlin.math.abs(dx) / (now - lastT).coerceAtLeast(1) > FAST_PX_PER_MS
                    lastT = now
                    onDrag((acc / pxPerMs).toLong(), if (dx > 0) 1 else if (dx < 0) -1 else 0, fast)
                }
            },
    ) {
        Box(Modifier.align(Alignment.Center).width(if (limit) 3.dp else 2.dp).height(18.dp).background(if (limit) Color(0xFFE5484D) else Color.Black.copy(alpha = 0.6f)))
    }
}

/**
 * The rest of a selected clip's recording, beside it on the timeline: its picture faded and
 * striped, with how much there is ("← 4.2 s"). Drag the clip's white edge into it to bring it in.
 */
@Composable
private fun Unused(thumb: java.io.File?, widthPx: Float, ms: Long, before: Boolean) {
    val density = LocalDensity.current
    Box(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(RtColors.SurfaceRaised)
            .semantics { contentDescription = "${seconds(ms)} of the recording not in the video, ${if (before) "before" else "after"} this clip" },
    ) {
        Thumb(thumb, Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f })
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val step = 12.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(Color.White.copy(alpha = 0.10f), androidx.compose.ui.geometry.Offset(x, size.height), androidx.compose.ui.geometry.Offset(x + size.height, 0f), strokeWidth = 5.dp.toPx())
                x += step
            }
        }
        if (widthPx > with(density) { 44.dp.toPx() }) {
            Text(
                if (before) "← ${seconds(ms)}" else "${seconds(ms)} →",
                style = RtType.caption,
                color = Color.White,
                modifier = Modifier.align(if (before) Alignment.BottomStart else Alignment.BottomEnd).padding(3.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
    }
}

/** Where Instagram and YouTube put their buttons and text: keep your words out of these. */
@Composable
private fun SafeZones() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The same lines the made video keeps rider-placed text and stickers inside.
        val shade = RtColors.Error.copy(alpha = 0.33f)
        Box(Modifier.fillMaxWidth().height(maxHeight * Tok.TOP_SAFE).background(shade))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(maxHeight * (1 - Tok.BOTTOM_SAFE)).background(shade))
        Box(Modifier.offset(x = maxWidth * Tok.RAIL_X, y = maxHeight * Tok.RAIL_TOP).width(maxWidth * (1 - Tok.RAIL_X)).height(maxHeight * (Tok.RAIL_BOTTOM - Tok.RAIL_TOP)).background(shade))
        Text("Safe area", style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.Center))
    }
}

private fun clock(ms: Long): String = String.format(Locale.US, "%d:%04.1f", ms / 60_000, (ms % 60_000) / 1000.0)

/** The playhead's time and the length, redrawn on its own as the video plays. */
@Composable
private fun TimeLabel(pos: () -> Long, totalMs: Long, modifier: Modifier) {
    Text("${clock(pos())} / ${clock(totalMs)}", style = RtType.caption, color = RtColors.TextSecondary, modifier = modifier, textAlign = TextAlign.Center)
}

/** Quick asks in the Ideas sheet; one ending in … is finished by the rider. */
private val QUICK_ASKS = listOf("Funnier", "15 s", "A caption clip", "Only my voice", "Slow and cinematic", "Fewer cuts", "Open with…")

/** A picture for each layer layout. */
private val LAYER_ICON = mapOf(
    LayerPreset.CORNER to Icons.Rounded.PictureInPicture,
    LayerPreset.TOP to Icons.Rounded.VerticalAlignTop,
    LayerPreset.BOTTOM to Icons.Rounded.VerticalAlignBottom,
    LayerPreset.BOTTOM_LEFT to Icons.Rounded.SouthWest,
    LayerPreset.BOTTOM_RIGHT to Icons.Rounded.SouthEast,
    LayerPreset.FULL to Icons.Rounded.Fullscreen,
)

/**
 * Quick taps with several fingers anywhere (two to undo, three to redo, like Procreate). Only
 * watches: pinches, drags and other gestures carry on as normal.
 */
private fun Modifier.fingerTaps(onTap: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val t0 = first.uptimeMillis
        val starts = HashMap<androidx.compose.ui.input.pointer.PointerId, androidx.compose.ui.geometry.Offset>()
        starts[first.id] = first.position
        var most = 1
        var moved = false
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            e.changes.forEach { c ->
                val from = starts.getOrPut(c.id) { c.position }
                if ((c.position - from).getDistance() > viewConfiguration.touchSlop) moved = true
            }
            most = maxOf(most, e.changes.count { it.pressed })
            if (e.changes.none { it.pressed }) {
                val quick = (e.changes.firstOrNull()?.uptimeMillis ?: t0) - t0 < 350
                if (most >= 2 && quick && !moved) onTap(most)
                break
            }
        }
    }
}

/** A crop being pinched: zoom, move (px) and turn (degrees) on top of what's kept. */
private data class CropLive(val z: Float = 1f, val x: Float = 0f, val y: Float = 0f, val r: Float = 0f)

/** Which way a finger swiped. */
private enum class Swipe { LEFT, RIGHT, UP, DOWN }

/**
 * The video's gestures: a tap, a hold ([onHold] true, then false on letting go) and a swipe.
 * A touch something on the video took (text, a sticker, a layer) is left to it. Restarts when
 * [keys] change.
 */
private fun Modifier.previewGestures(keys: Array<Any?>, onTap: () -> Unit, onHold: (Boolean) -> Unit, onSwipe: (Swipe) -> Unit): Modifier = pointerInput(*keys) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
        var moved = false
        var other = false
        val beforeHold = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id }
                if (c == null || e.changes.count { it.pressed } > 1 || (c.pressed && c.isConsumed)) { other = true; break }
                if (!c.pressed) { up = c; break }
                if ((c.position - down.position).getDistance() > slop) { moved = true; break }
            }
        }
        if (other) return@awaitEachGesture
        if (beforeHold == null) {
            // Held: until every finger is up.
            onHold(true)
            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
            onHold(false)
            return@awaitEachGesture
        }
        up?.let { if (!it.isConsumed) onTap(); return@awaitEachGesture }
        if (!moved) return@awaitEachGesture
        var last = down.position
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (c.isConsumed) return@awaitEachGesture
            last = c.position
            if (!c.pressed) break
        }
        val d = last - down.position
        val far = 48.dp.toPx()
        when {
            abs(d.x) > abs(d.y) && abs(d.x) > far -> onSwipe(if (d.x < 0) Swipe.LEFT else Swipe.RIGHT)
            abs(d.y) > far -> onSwipe(if (d.y < 0) Swipe.UP else Swipe.DOWN)
        }
    }
}

/** A short note in a dark pill, on the video. */
@Composable
private fun Pill(text: String, modifier: Modifier) {
    Text(text, style = RtType.caption, color = Color.White, modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** A small round icon button (the transport row). */
@Composable
private fun SmallIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) RtColors.TextPrimary else RtColors.TextTertiary, modifier = Modifier.size(22.dp))
    }
}

/** One tool in the bar under the timeline: an icon on a tile and its name. It rises in, the [n]th a little after the one before. */
@Composable
private fun Tool(icon: ImageVector, label: String, n: Int = 0, danger: Boolean = false, on: Boolean = false, onClick: () -> Unit) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(n * 30L); rise.animateTo(1f, tween(220)) }
    Column(
        Modifier.width(64.dp)
            .graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 12.dp.toPx() }
            .clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) RtColors.Primary.copy(alpha = 0.25f) else RtColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (danger) RtColors.Error else if (on) RtColors.Primary else RtColors.TextPrimary, modifier = Modifier.size(22.dp))
        }
        Text(label, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Back to the full set of tools. */
@Composable
private fun BackTool(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 36.dp, height = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = label, tint = RtColors.TextPrimary, modifier = Modifier.size(26.dp))
    }
}

/** The picker's order: None (a plain cut) first, then the style's own, then the rest. */
/**
 * The transitions in the picker: Instant first, then the style's own and its picks, then every
 * other one (Glitch is kept for saved edits only). All of them, whatever the style.
 */
private fun pickerOrder(vibe: Vibe): List<TransitionKind> {
    val first = listOf(TransitionKind.CUT, TransitionKind.STYLE) + vibe.spec.picker
    return (first + TransitionKind.entries.filter { it != TransitionKind.GLITCH }).distinct()
}

/** A mark for each transition, on the timeline and in the picker. */
/** One tool bar icon: an action, a parent with its own set ([kids]), or a value ([slider]). */
private class TNode(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val kids: List<TNode>? = null,
    val slider: TSlider? = null,
    val action: (() -> Unit)? = null,
    /** A bigger control in place of the icons (the volume line). */
    val panel: (@Composable () -> Unit)? = null,
)

private class TSlider(val value: Float, val range: ClosedFloatingPointRange<Float>, val format: (Float) -> String, val onChange: (Float) -> Unit, val onEnd: () -> Unit)

/**
 * The tool bar: where you are ("Add › Sticker"), ‹ fixed on the left to go back, and at most 5
 * icons spread across the width (never scrolling). A parent (small corner mark) opens its set; a
 * value opens a slider in place of the icons.
 */
@Composable
private fun ToolBar(top: List<TNode>, path: List<Int>, onPath: (List<Int>) -> Unit, label: String, onClose: (() -> Unit)? = null) {
    var level = top
    val names = ArrayList<String>()
    var open: TNode? = null
    for (k in path) {
        val n = level.getOrNull(k) ?: break
        names += n.label
        if (n.kids != null) level = n.kids else { open = n; break }
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 44.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (names.isEmpty()) label else (listOf(label) + names).joinToString(" › "),
                style = RtType.caption, color = if (onClose != null) RtColors.TextPrimary else RtColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onClose != null) {
                Text(
                    "✕ Done", style = RtType.caption, color = RtColors.TextSecondary,
                    modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Deselect", onClick = onClose).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp).height(48.dp), contentAlignment = Alignment.Center) {
                if (path.isNotEmpty()) {
                    IconButton(onClick = { onPath(path.dropLast(1)) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = RtColors.TextPrimary) }
                }
            }
            val sl = open?.slider
            val panel = open?.panel
            if (panel != null) {
                Box(Modifier.weight(1f).padding(end = 12.dp)) { panel() }
            } else if (sl != null) {
                var v by remember(open) { mutableFloatStateOf(sl.value) }
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Row {
                        Text(open.label, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                        Text(sl.format(v), style = RtType.caption, color = RtColors.TextPrimary)
                    }
                    androidx.compose.material3.Slider(
                        value = v, onValueChange = { v = it; sl.onChange(it) }, onValueChangeFinished = sl.onEnd, valueRange = sl.range,
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = RtColors.Primary, activeTrackColor = RtColors.Primary),
                    )
                }
            } else {
                Row(Modifier.weight(1f)) {
                    level.take(5).forEachIndexed { k, n ->
                        TreeTool(n, Modifier.weight(1f)) {
                            when {
                                n.kids != null || n.slider != null || n.panel != null -> onPath(path + k)
                                else -> n.action?.invoke()
                            }
                        }
                    }
                    repeat((5 - level.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Under a tool's panel: ✕ takes back everything since it opened, ✓ keeps it, Apply to all where it fits. */
@Composable
private fun PanelActions(onCancel: () -> Unit, onApplyAll: (() -> Unit)?, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClickLabel = "Cancel", onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = RtColors.TextPrimary) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (onApplyAll != null) {
                Text(
                    "Apply to all", style = RtType.button, color = RtColors.TextPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(RtColors.Surface).clickable(role = Role.Button, onClick = onApplyAll).padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        Box(
            Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(12.dp)).background(RtColors.Primary).clickable(role = Role.Button, onClickLabel = "Done", onClick = onDone),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Check, contentDescription = "Done", tint = RtColors.OnPrimary) }
    }
}

/** The five areas of the editor, always at the bottom while nothing is selected. */
private enum class ToolTab(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    SOUND("Sound", Icons.Rounded.Tune),
    TEXT("Text", Icons.Rounded.TextFields),
    LOOK("Look", Icons.Rounded.Palette),
    ADD("Add", Icons.Rounded.Add),
}

@Composable
private fun ToolTabs(selected: ToolTab, onSelect: (ToolTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).border(width = 0.5.dp, color = RtColors.Hairline, shape = RoundedCornerShape(0.dp)).padding(vertical = 4.dp),
    ) {
        ToolTab.entries.forEach { t ->
            val on = t == selected
            Column(
                Modifier.weight(1f).clickable(role = Role.Tab, onClickLabel = t.label) { onSelect(t) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(t.icon, contentDescription = null, tint = if (on) RtColors.Primary else RtColors.TextTertiary, modifier = Modifier.size(22.dp))
                Text(t.label, style = RtType.caption, color = if (on) RtColors.TextPrimary else RtColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun TreeTool(n: TNode, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.alpha(if (n.enabled) 1f else 0.35f).clickable(enabled = n.enabled, role = Role.Button, onClickLabel = if (n.kids != null) "Open" else null, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(48.dp, 40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (n.on) RtColors.Primary.copy(alpha = 0.25f) else RtColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(n.icon, contentDescription = null, tint = if (n.danger) RtColors.Error else RtColors.TextPrimary, modifier = Modifier.size(20.dp))
            if (n.kids != null || n.slider != null || n.panel != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(5.dp).clip(RoundedCornerShape(1.dp)).background(RtColors.TextTertiary))
            }
        }
        Text(n.label, style = RtType.caption, color = if (n.danger) RtColors.Error else RtColors.TextPrimary, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The volume line of one sound, full width: tap to add a point, drag one to move it (sideways in
 * time, up and down in level), hold one to delete it. The selected point's ease (how the line
 * goes on to the next point) is picked below it. 100% is the dashed line.
 */
@Composable
private fun VolumeLinePanel(
    a: AudioItem,
    playheadMs: Long,
    onAdd: (atMs: Long, level: Float) -> Unit,
    onMove: (k: Int, atMs: Long, level: Float) -> Unit,
    onMoveEnd: () -> Unit,
    onDelete: (Int) -> Unit,
    onEase: (Int, Ease) -> Unit,
) {
    var picked by remember(a.id) { mutableStateOf<Int?>(null) }
    val curve by rememberUpdatedState(a.curve)
    val dur = a.durMs.coerceAtLeast(1)
    val accent = RtColors.Primary
    val faint = RtColors.TextTertiary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.Surface)) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            fun x(ms: Long) = ms.toFloat() / dur * wPx
            fun y(level: Float) = hPx - 8f - (level / 1.5f) * (hPx - 16f)
            fun level(yy: Float) = ((hPx - 8f - yy) / (hPx - 16f) * 1.5f).coerceIn(0f, 1.5f)
            fun ms(xx: Float) = (xx / wPx * dur).toLong().coerceIn(0, dur)
            fun hit(o: androidx.compose.ui.geometry.Offset): Int? = curve.indices.minByOrNull { k -> kotlin.math.abs(x(curve[k].atMs) - o.x) + kotlin.math.abs(y(curve[k].level) - o.y) }
                ?.takeIf { k -> kotlin.math.abs(x(curve[k].atMs) - o.x) < 48f && kotlin.math.abs(y(curve[k].level) - o.y) < 48f }
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(a.id) {
                        detectTapGestures(
                            onTap = { o -> val k = hit(o); if (k != null) picked = k else onAdd(ms(o.x), level(o.y)) },
                            onLongPress = { o -> hit(o)?.let { k -> onDelete(k); picked = null } },
                        )
                    }
                    .pointerInput(a.id, "drag") {
                        var k: Int? = null
                        detectDragGestures(
                            onDragStart = { o -> k = hit(o); picked = k },
                            onDragEnd = { if (k != null) onMoveEnd(); k = null },
                            onDragCancel = { if (k != null) onMoveEnd(); k = null },
                        ) { ch, _ ->
                            val kk = k ?: return@detectDragGestures
                            ch.consume()
                            onMove(kk, ms(ch.position.x), level(ch.position.y))
                        }
                    },
            ) {
                // 100% guide, the playhead, the line and its points.
                drawLine(faint, androidx.compose.ui.geometry.Offset(0f, y(1f)), androidx.compose.ui.geometry.Offset(size.width, y(1f)), strokeWidth = 1f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                if (playheadMs in 0..dur) drawLine(Color.White, androidx.compose.ui.geometry.Offset(x(playheadMs), 0f), androidx.compose.ui.geometry.Offset(x(playheadMs), size.height), strokeWidth = 2f)
                var prev: androidx.compose.ui.geometry.Offset? = null
                val steps = 120
                for (j in 0..steps) {
                    val t = dur * j / steps
                    val pt = androidx.compose.ui.geometry.Offset(x(t), y(TrackEdits.levelAt(a, t) / a.volume.coerceAtLeast(0.01f) * a.volume))
                    prev?.let { drawLine(accent, it, pt, strokeWidth = 3f) }
                    prev = pt
                }
                curve.forEachIndexed { k, pnt ->
                    drawCircle(if (k == picked) Color.White else accent, radius = if (k == picked) 11f else 8f, center = androidx.compose.ui.geometry.Offset(x(pnt.atMs), y(pnt.level)))
                }
            }
        }
        val k = picked?.takeIf { it < a.curve.size }
        if (k != null && k < a.curve.size - 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ease.entries.forEach { e ->
                    val on = a.curve[k].ease == e
                    Text(
                        e.label, style = RtType.caption, color = if (on) RtColors.Background else RtColors.TextPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) RtColors.Primary else RtColors.Surface)
                            .clickable(role = Role.Button) { onEase(k, e) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        } else {
            Text(
                if (a.curve.isEmpty()) "Tap the line to add a point · drag it up or down" else "Tap a point to choose how it eases into the next · hold to delete",
                style = RtType.caption, color = RtColors.TextSecondary,
            )
        }
    }
}

/** Ride moments on the timeline: a glyph and a colour each. */
private val MARK_LOOK = mapOf(
    RideMark.Kind.DOWNSHIFT to ("⬇" to Color(0xFFE7792B)),
    RideMark.Kind.BRAKING to ("■" to Color(0xFFE5484D)),
    RideMark.Kind.TOP to ("▲" to Color(0xFF2FB5B5)),
    RideMark.Kind.LEAN to ("◢" to Color(0xFF8B7CF6)),
)

private val TRANSITION_GLYPH = mapOf(
    TransitionKind.STYLE to "✦", TransitionKind.CUT to "⚡", TransitionKind.FADE to "◐", TransitionKind.FLASH to "✺", TransitionKind.ZOOM to "⊕",
    TransitionKind.WHIP to "≫", TransitionKind.GLITCH to "▚", TransitionKind.SLASH to "▮", TransitionKind.SHUTTER to "◫", TransitionKind.SUN to "●", TransitionKind.CARD to "▭",
    TransitionKind.DIP to "◼", TransitionKind.ZOOM_THROUGH to "⊙", TransitionKind.LIGHT_LEAK to "☀", TransitionKind.TACH to "◔",
    TransitionKind.GEAR to "⚙", TransitionKind.VISOR to "◒", TransitionKind.MIRROR to "⬭", TransitionKind.EXHAUST to "☁", TransitionKind.BLIP to "〰",
    TransitionKind.SPLIT to "◧", TransitionKind.BLUR to "◍", TransitionKind.LUMA to "◑",
)

/** The longest a transition can be set to (the cut's two clips may shorten it further). */
private const val TRANSITION_MAX_MS = 1_500f

/**
 * The transition picker, where the tools were: every transition in a row to swipe through (the
 * chosen one bigger, each named), its length from Instant to 1.5 s, Apply to all and Done. The
 * cut plays on a loop meanwhile. [fit] is how long a length really plays on this cut.
 */
@Composable
private fun TransitionPicker(
    vibe: Vibe,
    tr: Transition,
    looping: Boolean,
    fit: (Long) -> Long,
    onPick: (Transition) -> Unit,
    onLength: (Transition) -> Unit,
    onLengthEnd: () -> Unit,
    onPreview: () -> Unit,
    onAll: () -> Unit,
    onDone: () -> Unit,
) {
    val list = rememberLazyListState()
    val order = remember(vibe) { pickerOrder(vibe) }
    // The transition to come back to when the length is dragged up from Instant.
    var lastKind by remember { mutableStateOf(if (tr.kind == TransitionKind.CUT) TransitionKind.STYLE else tr.kind) }
    LaunchedEffect(tr.kind) {
        if (tr.kind != TransitionKind.CUT) lastKind = tr.kind
        list.animateScrollToItem((order.indexOf(tr.kind) - 2).coerceAtLeast(0))
    }
    val instant = tr.kind == TransitionKind.CUT
    val ms = if (instant) 0L else tr.ms(vibe)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(
            state = list,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(order) { k ->
                val on = k == tr.kind
                val z by animateFloatAsState(if (on) 1.12f else 0.92f, tween(200), label = "pick")
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = k.label) { onPick(Transition(k)) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.graphicsLayer { scaleX = z; scaleY = z }.size(52.dp).clip(CircleShape)
                            .background(if (on) RtColors.Primary else RtColors.SurfaceRaised)
                            .then(if (on) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Text(TRANSITION_GLYPH[k] ?: "", fontSize = 20.sp, color = Color.White) }
                    Text(
                        if (k == TransitionKind.STYLE) "Style's" else k.label,
                        style = RtType.caption,
                        color = if (on) RtColors.Primary else RtColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(if (tr.kind == TransitionKind.STYLE) "Style's own · ${vibe.spec.section.label}" else tr.kind.label, style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.align(Alignment.CenterHorizontally))
        // Length: all the way left is Instant; 0.1 s steps feel too coarse at the short end, so 0.05 s.
        val plays = if (instant) 0L else fit(ms)
        val said = when {
            instant -> "Instant"
            plays < ms -> "${seconds(ms)} · plays ${seconds(plays)} (short clips)"
            else -> seconds(ms)
        }
        Box(Modifier.padding(horizontal = 16.dp)) {
            LabelledSlider(
                "Length", said, ms.toFloat().coerceAtMost(TRANSITION_MAX_MS), 0f..TRANSITION_MAX_MS,
                { v ->
                    val step = (v / 50f).roundToInt() * 50L
                    onLength(if (step < 50) Transition(TransitionKind.CUT) else tr.copy(kind = if (instant) lastKind else tr.kind, customMs = step.coerceAtLeast(100)))
                },
                onLengthEnd,
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Small(if (looping) "■ Stop" else "▶ Play", on = looping, onClick = onPreview)
            Small("Apply to all", onClick = onAll)
            Spacer(Modifier.weight(1f))
            Text(
                "Done",
                style = RtType.button,
                color = RtColors.Background,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(RtColors.TextPrimary).clickable(role = Role.Button, onClick = onDone).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/** A length in seconds, as few decimals as it needs ("0.35 s", "0.5 s", "1.0 s"). */
private fun seconds(ms: Long): String = String.format(Locale.US, "%.2f", ms / 1000.0).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it } + " s"
