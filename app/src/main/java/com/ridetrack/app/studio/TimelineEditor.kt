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
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.draw.clip
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
    var historyOpen by remember { mutableStateOf(false) }
    var clipTab by remember { mutableStateOf(ClipTab.EDIT) }
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
    LaunchedEffect(playing, pxPerMs) {
        while (playing) {
            pos = preview.positionMs().coerceIn(0, current.totalMs)
            preview.syncSound()
            vm.playheadMs = pos
            scroll.scrollTo((pos * pxPerMs).roundToInt())
            delay(33)
        }
    }
    // Scrolling the timeline by hand scrubs the video.
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pxPerMs) {
        scroll.scrollTo((pos * pxPerMs).roundToInt())
        var lastSeek = 0L
        snapshotFlow { scroll.value }.collectLatest { v ->
            if (!active.isPlaying) {
                pos = (v / pxPerMs).toLong().coerceIn(0, current.totalMs)
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
            if (snap != null && snap != pos && !scroll.isScrollInProgress) { tick(); scroll.animateScrollTo((snap * pxPerMs).roundToInt()) }
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
            scroll.scrollTo((t * pxPerMs).roundToInt())
            active.play()
            delay(2_400)
        }
        active.pause()
        loopAt = null
    }
    LaunchedEffect(dragged) { if (dragged) loopAt = null }
    LaunchedEffect(flash) { if (flash != null) { delay(1_300); flash = null } }
    // A different selection closes the transition picker.
    LaunchedEffect(s.pick) { if (s.pick != null) { cutAt = null; voiceOn = false; filterOpen = false }; clipTab = ClipTab.EDIT }
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
                    val many = (s.pick as? TimelinePick.Clips)?.indices
                    Row(Modifier.horizontalScroll(scroll)) {
                        Spacer(Modifier.width(half))
                        Column(Modifier.width(w(plan.totalMs) + 52.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            // Markers, and the redline tags as red bars (tap one to remove it)
                            Box(Modifier.fillMaxWidth().height(10.dp)) {
                                plan.redlines.forEach { r ->
                                    Box(
                                        Modifier.offset { IntOffset((r.startMs * pxPerMs).roundToInt(), 0) }.width(w(r.endMs - r.startMs)).height(10.dp)
                                            .clip(RoundedCornerShape(3.dp)).background(Color(Tok.RED).copy(alpha = 0.8f))
                                            .clickable(role = Role.Button, onClickLabel = "Remove redline") { vm.deleteRedline(r.id) },
                                    )
                                }
                                plan.markers.forEach { m ->
                                    val sel = (s.pick as? TimelinePick.Marker)?.id == m.id
                                    Box(
                                        Modifier.offset { IntOffset((m.atMs * pxPerMs).roundToInt() - 5, 0) }.size(10.dp).clip(CircleShape)
                                            .background(if (sel) RtColors.Primary else Color(0xFFFFC83D))
                                            .clickable(role = Role.Button) { seekTo(m.atMs); vm.pick(if (sel) null else TimelinePick.Marker(m.id)) },
                                    )
                                }
                            }
                            // Clips, with a mark on each cut that plays a transition
                            Box(Modifier.fillMaxWidth().height(52.dp)) {
                            Row(Modifier.height(52.dp)) {
                                plan.segments.forEachIndexed { i, seg ->
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
                                            TrimHandle(Modifier.align(Alignment.CenterStart), pxPerMs, vm::endDrag) { ms -> vm.drag { p -> TimelineEdits.trimStart(p, i, ms) } }
                                            TrimHandle(Modifier.align(Alignment.CenterEnd), pxPerMs, vm::endDrag) { ms -> vm.drag { p -> TimelineEdits.trimEnd(p, i, ms) } }
                                        }
                                    }
                                }
                            }
                            Cuts.marks(plan).forEach { m ->
                                val half = with(density) { 11.dp.toPx() }.roundToInt()
                                val on = cutAt == m.segment
                                val k by animateFloatAsState(if (on) 1.5f else 1f, tween(220), label = "mark")
                                val a by animateFloatAsState(if (cutAt == null || on) 1f else 0f, tween(220), label = "markAlpha")
                                Box(
                                    Modifier.offset { IntOffset((m.atMs * pxPerMs).roundToInt() - half, with(density) { 15.dp.toPx() }.roundToInt()) }
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
                                Modifier.offset { IntOffset((plan.totalMs * pxPerMs).roundToInt() + 8.dp.roundToPx(), 8.dp.roundToPx()) }
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
                                Rows(Lanes.count(rows)) {
                                    top.forEachIndexed { n, l ->
                                        val selected = (s.pick as? TimelinePick.Layer)?.id == l.id
                                        androidx.compose.runtime.key(l.id) {
                                            TrackBlock(
                                                "▣ Layer" + if (l.keys.isNotEmpty()) " · moves" else "", l.startMs, l.durMs, rows[n], pxPerMs, Color(0x5594E2B8), selected, l.hidden,
                                                onClick = { vm.pick(if (selected) null else TimelinePick.Layer(l.id)) },
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
                            Rows(Lanes.count(capRows)) {
                                caps.forEachIndexed { n, (i, j, sp) ->
                                    val line = (plan.segments[i] as ClipSegment).lines[j]
                                    val selected = s.pick == TimelinePick.Caption(i, j)
                                    TrackBlock(
                                        line.text, sp.startMs, sp.endMs - sp.startMs, capRows[n], pxPerMs, RtColors.Primary.copy(alpha = 0.25f), selected, hidden = !s.options.captions,
                                        onClick = { vm.pick(if (selected) null else TimelinePick.Caption(i, j)) },
                                    ) { ms, _ -> vm.change("Caption time") { TimelineEdits.nudgeCaption(it, i, j, ms) }; vm.pick(null) }
                                }
                            }
                            // Text
                            run {
                                val top = plan.texts.asReversed()
                                val rows = Lanes.pack(top.map { Lanes.Span(it.startMs, it.endMs) })
                                Rows(Lanes.count(rows)) {
                                    top.forEachIndexed { n, t ->
                                        val selected = (s.pick as? TimelinePick.Text)?.id == t.id
                                        androidx.compose.runtime.key(t.id) {
                                            TrackBlock(
                                                "T  ${t.text}", t.startMs, t.endMs - t.startMs, rows[n], pxPerMs, Color(0x55FFC83D), selected, t.hidden,
                                                onClick = { vm.pick(if (selected) null else TimelinePick.Text(t.id)) },
                                            ) { ms, r -> vm.change("Move text") { p -> TrackEdits.stackText(TimelineEdits.moveText(p, t.id, ms), t.id, r) } }
                                        }
                                    }
                                }
                            }
                            // Stickers
                            if (plan.stickers.isNotEmpty()) {
                                val top = plan.stickers.asReversed()
                                val rows = Lanes.pack(top.map { Lanes.Span(it.startMs, it.endMs) })
                                Rows(Lanes.count(rows)) {
                                    top.forEachIndexed { n, st ->
                                        val selected = (s.pick as? TimelinePick.Sticker)?.id == st.id
                                        androidx.compose.runtime.key(st.id) {
                                            TrackBlock(
                                                if (st.kind == StickerKind.EMOJI) st.text else st.kind.label, st.startMs, st.endMs - st.startMs, rows[n], pxPerMs, Color(0x55F472B6), selected, st.hidden,
                                                onClick = { vm.pick(if (selected) null else TimelinePick.Sticker(st.id)) },
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
                                    Rows(1) {
                                        voiced.forEach { (i, seg) ->
                                            TrackBlock(label, starts[i], seg.durMs, 0, pxPerMs, Color(0x5538BDF8), false, onClick = { mixer = true })
                                        }
                                    }
                                }
                            }
                            // Sound tracks (hold one and drag to move it in time)
                            listOf(TrackKind.DETACHED, TrackKind.ENGINE).forEach { kind ->
                                val items = plan.audio.filter { it.kind == kind }
                                if (items.isEmpty()) return@forEach
                                Rows(1) {
                                    items.forEach { a ->
                                        val selected = (s.pick as? TimelinePick.Audio)?.id == a.id
                                        val muted = plan.mix.gain(kind) == 0f
                                        TrackBlock(
                                            (if (kind == TrackKind.ENGINE) "Engine" else "♪ Sound") + if (muted) " · muted" else "", a.startMs, a.durMs, 0, pxPerMs,
                                            if (kind == TrackKind.ENGINE) Color(0x55F97316) else Color(0x55A78BFA), selected,
                                            onClick = { vm.pick(if (selected) null else TimelinePick.Audio(a.id)) },
                                        ) { ms, _ -> vm.change("Sound time") { TrackEdits.shiftAudio(it, a.id, ms) } }
                                    }
                                }
                            }
                            if (s.takes.isNotEmpty()) {
                                Rows(1) {
                                    s.takes.forEach { t -> TrackBlock("Voice-over", t.startMs, t.durMs, 0, pxPerMs, Color(0x5560A5FA), false, onClick = { mixer = true }) }
                                }
                            }
                            if (s.musicUri != null) {
                                Rows(1) {
                                    TrackBlock("♫ ${s.musicName ?: "Music"}", 0, plan.totalMs, 0, pxPerMs, Color(0x5534D399), false, onClick = { mixer = true })
                                }
                            }
                            plan.audio.filter { it.kind == TrackKind.MUSIC }.forEach { a ->
                                val selected = (s.pick as? TimelinePick.Audio)?.id == a.id
                                Rows(1) {
                                    TrackBlock(
                                        "♫ ${a.name ?: "Sound"}" + if (plan.mix.gain(TrackKind.MUSIC) == 0f) " · muted" else "", a.startMs, a.durMs, 0, pxPerMs, Color(0x5534D399), selected,
                                        onClick = { vm.pick(if (selected) null else TimelinePick.Audio(a.id)) },
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
                        Spacer(Modifier.width(half))
                    }
                }
                // The playhead, over every track.
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White))
                }
            }

            // ---- tools: they change with what's selected (a back arrow returns to all of them)
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
                } else if (!voiceOn && (picked == null || (picked is TimelinePick.Clip && clipTab == ClipTab.EDIT))) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        var n = 0
                        if (picked is TimelinePick.Clip) {
                            val i = picked.index
                            BackTool("All tools") { vm.pick(null) }
                            Tool(Icons.Rounded.ContentCut, "Split", n++) { vm.change("Split") { TimelineEdits.split(it, pos) } }
                            if (seg?.still == null) Tool(Icons.Rounded.Speed, "Speed", n++) { clipTab = ClipTab.SPEED }
                            Tool(Icons.Rounded.Contrast, "Look", n++) { clipTab = ClipTab.LOOK }
                            Tool(Icons.Rounded.Crop, "Crop", n++) { clipTab = ClipTab.FRAME }
                            Tool(Icons.Rounded.Animation, "Transition", n++) {
                                if (i == 0) flash = "The first clip has no cut before it" else { cutAt = i; vm.pick(null); loopAt = plan.startOf(i) }
                            }
                            Tool(Icons.Rounded.SwapHoriz, "Replace", n++) { adding = AddWhat.REPLACE }
                            Tool(Icons.Rounded.ContentCopy, "Duplicate", n++) { vm.change("Duplicate") { ClipTools.duplicate(it, i) } }
                            if (seg?.still == null) Tool(Icons.Rounded.FastRewind, "Reverse", n++, on = seg?.reverse != null) { vm.reverse(i) }
                            val v = seg?.volume ?: 1f
                            val next = when { v >= 1.5f -> 0f; v == 0f -> 0.5f; v < 1f -> 1f; else -> 1.5f }
                            Tool(if (v == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Sound ${(v * 100).roundToInt()}%", n++) { vm.change("Sound") { TimelineEdits.volume(it, i, next) } }
                            Tool(Icons.AutoMirrored.Rounded.CallSplit, "Detach sound", n++) { vm.detach(i) }
                            if (seg?.still == null) {
                                Tool(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier part", n++) { vm.change("Other part") { ClipTools.slip(it, i, -500) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowRight, "Later part", n++) { vm.change("Other part") { ClipTools.slip(it, i, 500) } }
                            }
                            Tool(Icons.AutoMirrored.Rounded.ArrowBack, "Move left", n++) { vm.change("Move") { TimelineEdits.move(it, i, -1) }; vm.pick(TimelinePick.Clip(i - 1)) }
                            Tool(Icons.AutoMirrored.Rounded.ArrowForward, "Move right", n++) { vm.change("Move") { TimelineEdits.move(it, i, 1) }; vm.pick(TimelinePick.Clip(i + 1)) }
                            Tool(Icons.Rounded.FormatPaint, "Copy look", n++) { vm.copySettings(i) }
                            if (s.copied != null) Tool(Icons.Rounded.ContentPaste, "Paste look", n++) { vm.pasteSettings(setOf(i)) }
                            Tool(Icons.Rounded.LibraryAddCheck, "Select more", n++) { vm.pick(TimelinePick.Clips(setOf(i))) }
                            Tool(Icons.Rounded.BookmarkAdd, "Save clip", n++) { vm.saveSegment(i) }
                            Tool(Icons.Rounded.Delete, "Delete", n++, danger = true) { vm.change("Delete") { TimelineEdits.delete(it, i) }; vm.pick(null) }
                        } else {
                            Tool(Icons.Rounded.Palette, "Style", n++) { styleOpen = true }
                            Tool(Icons.Rounded.Gradient, if (s.options.filter == RideFilter.NONE) "Filter" else "Filter: ${s.options.filter.label}", n++, on = s.options.filter != RideFilter.NONE) { filterOpen = true }
                            Tool(Icons.Rounded.TextFields, "Text", n++) { active.pause(); typingId = null; typing = true }
                            Tool(Icons.Rounded.ClosedCaption, "Captions", n++) { captionTab = CaptionTab.LOOK; captionLine = null; captionsOpen = true }
                            Tool(Icons.Rounded.Subtitles, "Add caption", n++) { ask = TextAsk.NewCaption }
                            Tool(Icons.Rounded.EmojiEmotions, "Stickers", n++) { addSticker = true }
                            Tool(Icons.Rounded.MusicNote, "Sound", n++) { soundOpen = true }
                            Tool(Icons.Rounded.Mic, "Voice-over", n++) { voiceOn = true }
                            Tool(Icons.Rounded.Tune, "Mix", n++) { mixer = true }
                            Tool(Icons.Rounded.PictureInPicture, "Over video", n++) { adding = AddWhat.LAYER }
                            Tool(Icons.Rounded.AcUnit, "Freeze", n++) { vm.freeze() }
                            Tool(Icons.Rounded.ContentCut, "Split", n++) { vm.change("Split") { TimelineEdits.split(it, pos) } }
                            Tool(Icons.Rounded.Flag, "Marker", n++) { vm.addMarker() }
                            if (plan.vibe.spec.redline) Tool(Icons.Rounded.Speed, "Tag redline", n++) { vm.tagRedline() }
                            Tool(Icons.Rounded.CropFree, "Safe zones", n++, on = safe) { safe = !safe }
                        }
                    }
                } else if (!voiceOn && picked is TimelinePick.Clip && seg != null) {
                    val i = picked.index
                    val back = { clipTab = ClipTab.EDIT }
                    when (clipTab) {
                        ClipTab.SPEED -> SpeedPanel(
                            seg,
                            onSpeed = { v -> vm.drag { ClipTools.speed(it, i, v) } },
                            onSpeedEnd = { vm.endDrag("Speed") },
                            onRamp = { r -> vm.change("Speed curve") { ClipTools.ramp(it, i, r) } },
                            onReverse = { vm.reverse(i) },
                            onFreeze = { vm.freeze() },
                            onMute = { vm.change(if (seg.volume == 0f) "Unmute" else "Mute") { TimelineEdits.volume(it, i, if (seg.volume == 0f) 1f else 0f) } },
                            onBack = back,
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
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BackTool("All tools") { vm.pick(null) }
                        var n = 0
                        when (val p = picked) {
                            is TimelinePick.Clips -> {
                                Tool(Icons.AutoMirrored.Rounded.ArrowBack, "Move left", n++) {
                                    var moved = p.indices
                                    vm.change("Move clips") { pl -> TrackEdits.moveClips(pl, p.indices, -1).also { moved = it.second }.first }
                                    vm.pick(TimelinePick.Clips(moved))
                                }
                                Tool(Icons.AutoMirrored.Rounded.ArrowForward, "Move right", n++) {
                                    var moved = p.indices
                                    vm.change("Move clips") { pl -> TrackEdits.moveClips(pl, p.indices, 1).also { moved = it.second }.first }
                                    vm.pick(TimelinePick.Clips(moved))
                                }
                                if (s.copied != null) Tool(Icons.Rounded.ContentPaste, "Paste look", n++) { vm.pasteSettings(p.indices) }
                                Tool(Icons.Rounded.Delete, "Delete ${p.indices.size}", n++, danger = true) { vm.change("Delete clips") { TrackEdits.deleteClips(it, p.indices) }; vm.pick(null) }
                            }
                            is TimelinePick.Layer -> {
                                val l = plan.layers.firstOrNull { it.id == p.id }
                                LayerPreset.entries.forEach { pr -> Tool(LAYER_ICON[pr] ?: Icons.Rounded.PictureInPicture, pr.label, n++) { vm.change("Layer: ${pr.label}") { TrackEdits.preset(it, p.id, pr) } } }
                                Tool(Icons.Rounded.ZoomOut, "Smaller", n++) { vm.change("Layer size") { TrackEdits.resizeLayer(it, p.id, 0.85f, pos) } }
                                Tool(Icons.Rounded.ZoomIn, "Bigger", n++) { vm.change("Layer size") { TrackEdits.resizeLayer(it, p.id, 1.18f, pos) } }
                                val shape = l?.shape ?: LayerShape.ROUNDED
                                val nextShape = LayerShape.entries[(shape.ordinal + 1) % LayerShape.entries.size]
                                Tool(Icons.Rounded.RoundedCorner, shape.label, n++) { vm.change("Layer shape") { TrackEdits.shape(it, p.id, nextShape) } }
                                Tool(Icons.Rounded.BorderOuter, "Frame", n++, on = l?.border == true) { vm.change("Layer frame") { TrackEdits.border(it, p.id, l?.border != true) } }
                                val o = l?.opacity ?: 1f
                                Tool(Icons.Rounded.Opacity, "See-through ${(o * 100).roundToInt()}%", n++) { vm.change("Layer opacity") { TrackEdits.opacity(it, p.id, if (o <= 0.4f) 1f else o - 0.25f) } }
                                Tool(Icons.Rounded.RotateRight, "Rotate", n++) { vm.change("Rotate layer") { TrackEdits.rotateLayer(it, p.id, 90f) } }
                                Tool(Icons.Rounded.Moving, "Move here", n++, on = l?.keys?.isNotEmpty() == true) { vm.change("Layer move point") { TrackEdits.addKey(it, p.id, pos) } }
                                if (l?.keys?.isNotEmpty() == true) Tool(Icons.Rounded.LayersClear, "Clear moves", n++) { vm.change("Clear moves") { TrackEdits.clearKeys(it, p.id) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier", n++) { vm.change("Layer time") { TrackEdits.shiftLayer(it, p.id, -500) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowRight, "Later", n++) { vm.change("Layer time") { TrackEdits.shiftLayer(it, p.id, 500) } }
                                Tool(Icons.Rounded.UnfoldLess, "Shorter", n++) { vm.change("Layer length") { TrackEdits.resizeLayerTime(it, p.id, -500) } }
                                Tool(Icons.Rounded.UnfoldMore, "Longer", n++) { vm.change("Layer length") { TrackEdits.resizeLayerTime(it, p.id, 500) } }
                                val loud = (l?.volume ?: 0f) > 0f
                                Tool(if (loud) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, if (loud) "Its sound on" else "Its sound off", n++, on = loud) {
                                    vm.change("Layer sound") { TrackEdits.layerVolume(it, p.id, if (loud) 0f else 1f) }
                                }
                                val hid = l?.hidden == true
                                Tool(if (hid) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (hid) "Show" else "Hide", n++, on = hid) { vm.change(if (hid) "Show layer" else "Hide layer") { TrackEdits.hideLayer(it, p.id, !hid) } }
                                Tool(Icons.Rounded.Delete, "Delete", n++, danger = true) { vm.change("Delete layer") { TrackEdits.deleteLayer(it, p.id) }; vm.pick(null) }
                            }
                            is TimelinePick.Audio -> {
                                val a = plan.audio.firstOrNull { it.id == p.id }
                                val v = a?.volume ?: 1f
                                val nextV = when { v >= 1.5f -> 0.25f; v < 0.5f -> 0.5f; v < 1f -> 1f; else -> 1.5f }
                                Tool(Icons.AutoMirrored.Rounded.VolumeUp, "Volume ${(v * 100).roundToInt()}%", n++) { vm.change("Volume") { TrackEdits.audioVolume(it, p.id, nextV) } }
                                val faded = (a?.fadeInMs ?: 0) > 0
                                Tool(Icons.Rounded.Gradient, "Fade in, out", n++, on = faded) { vm.change("Fades") { TrackEdits.fade(it, p.id, if (faded) 0 else 400, if (faded) 0 else 600) } }
                                Tool(Icons.AutoMirrored.Rounded.VolumeDown, "Dip here", n++) { vm.change("Volume point") { TrackEdits.addVolumePoint(it, p.id, pos - (a?.startMs ?: 0), 0.3f) } }
                                Tool(Icons.AutoMirrored.Rounded.VolumeUp, "Full here", n++) { vm.change("Volume point") { TrackEdits.addVolumePoint(it, p.id, pos - (a?.startMs ?: 0), 1f) } }
                                if (a?.curve?.isNotEmpty() == true) Tool(Icons.Rounded.ClearAll, "Clear points", n++) { vm.change("Clear points") { TrackEdits.clearCurve(it, p.id) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier", n++) { vm.change("Sound time") { TrackEdits.shiftAudio(it, p.id, -250) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowRight, "Later", n++) { vm.change("Sound time") { TrackEdits.shiftAudio(it, p.id, 250) } }
                                Tool(Icons.Rounded.FastRewind, "Start sooner", n++) { vm.change("Sound start") { TrackEdits.trimAudioStart(it, p.id, -250) } }
                                Tool(Icons.Rounded.FastForward, "Start later", n++) { vm.change("Sound start") { TrackEdits.trimAudioStart(it, p.id, 250) } }
                                Tool(Icons.Rounded.UnfoldLess, "Shorter", n++) { vm.change("Sound length") { TrackEdits.resizeAudio(it, p.id, -250) } }
                                Tool(Icons.Rounded.UnfoldMore, "Longer", n++) { vm.change("Sound length") { TrackEdits.resizeAudio(it, p.id, 250) } }
                                Tool(Icons.Rounded.Delete, "Delete", n++, danger = true) { vm.change("Delete sound") { TrackEdits.deleteAudio(it, p.id) }; vm.pick(null) }
                            }
                            is TimelinePick.Marker -> {
                                Tool(Icons.Rounded.Delete, "Delete mark", n++, danger = true) { vm.change("Delete marker") { TrackEdits.deleteMarker(it, p.id) }; vm.pick(null) }
                            }
                            is TimelinePick.Caption -> {
                                Tool(Icons.Rounded.Edit, "Fix words", n++) { captionTab = CaptionTab.WORDS; captionLine = p.index to p.line; captionsOpen = true }
                                Tool(Icons.Rounded.Palette, "Look", n++) { captionTab = CaptionTab.LOOK; captionLine = null; captionsOpen = true }
                                Tool(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier", n++) { vm.change("Caption time") { TimelineEdits.nudgeCaption(it, p.index, p.line, -200) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowRight, "Later", n++) { vm.change("Caption time") { TimelineEdits.nudgeCaption(it, p.index, p.line, 200) } }
                                (plan.segments.getOrNull(p.index) as? ClipSegment)?.let { seg -> Tool(Icons.Rounded.Hearing, "Read again", n++) { vm.readAgain(seg.bit.momentId) } }
                                val off = !s.options.captions
                                Tool(if (off) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (off) "Show captions" else "Hide captions", n++, on = off) { vm.setOptions { o -> o.copy(captions = off) } }
                                Tool(Icons.Rounded.Delete, "Delete", n++, danger = true) { vm.change("Delete caption") { TimelineEdits.caption(it, p.index, p.line, "") }; vm.pick(null) }
                            }
                            is TimelinePick.Text -> {
                                val t = plan.texts.firstOrNull { it.id == p.id }
                                Tool(Icons.Rounded.Edit, "Edit", n++) { active.pause(); typingId = p.id; typing = true }
                                Tool(Icons.Rounded.Animation, "In: ${(t?.animIn ?: TextAnim.FADE).label}", n++) {
                                    val ai = t?.animIn ?: TextAnim.FADE
                                    vm.change("Text in") { TextTools.anim(it, p.id, animIn = TextAnim.entries[(ai.ordinal + 1) % TextAnim.entries.size]) }
                                }
                                Tool(Icons.Rounded.Animation, "Out: ${(t?.animOut ?: TextAnim.FADE).label}", n++) {
                                    val ao = t?.animOut ?: TextAnim.FADE
                                    vm.change("Text out") { TextTools.anim(it, p.id, animOut = TextAnim.entries[(ao.ordinal + 1) % TextAnim.entries.size]) }
                                }
                                Tool(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier", n++) { vm.change("Text time") { TimelineEdits.moveText(it, p.id, -500) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowRight, "Later", n++) { vm.change("Text time") { TimelineEdits.moveText(it, p.id, 500) } }
                                Tool(Icons.Rounded.UnfoldLess, "Shorter", n++) { vm.change("Text length") { TimelineEdits.resizeText(it, p.id, -500) } }
                                Tool(Icons.Rounded.UnfoldMore, "Longer", n++) { vm.change("Text length") { TimelineEdits.resizeText(it, p.id, 500) } }
                                val hid = t?.hidden == true
                                Tool(if (hid) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (hid) "Show" else "Hide", n++, on = hid) { vm.change(if (hid) "Show text" else "Hide text") { TrackEdits.hideText(it, p.id, !hid) } }
                                Tool(Icons.Rounded.Delete, "Delete", n++, danger = true) { vm.change("Delete text") { TimelineEdits.deleteText(it, p.id) }; vm.pick(null) }
                            }
                            is TimelinePick.Sticker -> {
                                val st = plan.stickers.firstOrNull { it.id == p.id }
                                if (st?.kind == StickerKind.EMOJI) Tool(Icons.Rounded.EmojiEmotions, "Change", n++) { addSticker = true }
                                Tool(Icons.Rounded.ZoomOut, "Smaller", n++) { vm.change("Sticker size") { TextTools.scaleSticker(it, p.id, 0.85f) } }
                                Tool(Icons.Rounded.ZoomIn, "Bigger", n++) { vm.change("Sticker size") { TextTools.scaleSticker(it, p.id, 1.18f) } }
                                Tool(Icons.Rounded.RotateRight, "Turn", n++) { vm.change("Turn sticker") { TextTools.rotateSticker(it, p.id, 15f) } }
                                Tool(Icons.Rounded.ContentCopy, "Duplicate", n++) { st?.let { vm.addSticker(it.kind, it.text) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowLeft, "Earlier", n++) { vm.change("Sticker time") { TextTools.shiftSticker(it, p.id, -500) } }
                                Tool(Icons.Rounded.KeyboardDoubleArrowRight, "Later", n++) { vm.change("Sticker time") { TextTools.shiftSticker(it, p.id, 500) } }
                                Tool(Icons.Rounded.UnfoldLess, "Shorter", n++) { vm.change("Sticker length") { TextTools.resizeStickerTime(it, p.id, -500) } }
                                Tool(Icons.Rounded.UnfoldMore, "Longer", n++) { vm.change("Sticker length") { TextTools.resizeStickerTime(it, p.id, 500) } }
                                val hid = st?.hidden == true
                                Tool(if (hid) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (hid) "Show" else "Hide", n++, on = hid) { vm.change(if (hid) "Show sticker" else "Hide sticker") { TrackEdits.hideSticker(it, p.id, !hid) } }
                                Tool(Icons.Rounded.Delete, "Delete", n++, danger = true) { vm.change("Delete sticker") { TextTools.deleteSticker(it, p.id) }; vm.pick(null) }
                            }
                            else -> Unit
                        }
                    }
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
            onSeek = { ms -> seekTo(ms); scope.launch { scroll.animateScrollTo((ms * pxPerMs).roundToInt()) } },
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

/** A group of timeline rows, [count] high. */
@Composable
private fun Rows(count: Int, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(ROW * count.coerceAtLeast(1)), content = content)
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
    pxPerMs: Float,
    color: Color,
    selected: Boolean,
    hidden: Boolean = false,
    onClick: () -> Unit,
    onDrop: ((deltaMs: Long, rows: Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val rowPx = with(density) { ROW.toPx() }
    val drop by rememberUpdatedState(onDrop)
    val perMs by rememberUpdatedState(pxPerMs)
    Box(
        Modifier.offset { IntOffset((startMs * pxPerMs + dx).roundToInt(), (row * rowPx + dy).roundToInt()) }
            .zIndex(if (held) 1f else 0f)
            .width(with(density) { (durMs.coerceAtLeast(200) * pxPerMs).toDp() }).height(ROW - 4.dp)
            .graphicsLayer {
                alpha = if (hidden && !held) 0.45f else 1f
                if (held) { scaleY = 1.08f; shadowElevation = 8.dp.toPx() }
            }
            .clip(RoundedCornerShape(6.dp)).background(color)
            .then(if (selected || held) Modifier.border(2.dp, RtColors.Primary, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .then(
                if (onDrop == null) Modifier else Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { held = true; dx = 0f; dy = 0f; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                        onDragEnd = {
                            val ms = (dx / perMs).toLong()
                            val rows = (dy / rowPx).roundToInt()
                            held = false; dx = 0f; dy = 0f
                            if (ms != 0L || rows != 0) drop?.invoke(ms, rows)
                        },
                        onDragCancel = { held = false; dx = 0f; dy = 0f },
                    ) { ch, amount -> ch.consume(); dx += amount.x; dy += amount.y }
                },
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun TrimHandle(modifier: Modifier, pxPerMs: Float, onEnd: () -> Unit, onDrag: (Long) -> Unit) {
    var acc by remember { mutableFloatStateOf(0f) }
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
                    onDrag((acc / pxPerMs).toLong())
                }
            },
    ) {
        Box(Modifier.align(Alignment.Center).width(2.dp).height(18.dp).background(Color.Black.copy(alpha = 0.6f)))
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
