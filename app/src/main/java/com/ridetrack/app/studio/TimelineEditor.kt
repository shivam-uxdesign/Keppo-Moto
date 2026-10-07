package com.ridetrack.app.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a text dialog is for. */
private sealed interface TextAsk {
    data object NewText : TextAsk
    data object NewCaption : TextAsk
    data class EditText(val id: String, val text: String) : TextAsk
    data class EditCaption(val index: Int, val line: Int, val text: String) : TextAsk
}

/** Which list of clips the add dialog is for. */
private enum class AddWhat { CLIP, LAYER }

/** One frame at 30 fps. */
private const val FRAME_MS = 33L

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
    val preview = remember { PreviewPlayer(context) }
    val files = remember { vm.previewFiles() }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var safe by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<TextAsk?>(null) }
    var adding by remember { mutableStateOf<AddWhat?>(null) }
    var mixer by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val pxPerMs = with(density) { 64.dp.toPx() } * zoom / 1000f
    val scroll = rememberScrollState()
    val current by rememberUpdatedState(plan)

    DisposableEffect(Unit) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        preview.player.addListener(l)
        onDispose { preview.player.removeListener(l); preview.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { preview.player.pause() }
    // A change (not mid-drag): play the new edit from where the playhead is.
    LaunchedEffect(s.timelineVersion) { preview.load(current, files, pos) }
    // Playing: the timeline follows the video.
    LaunchedEffect(playing, pxPerMs) {
        while (playing) {
            pos = preview.positionMs().coerceIn(0, current.totalMs)
            vm.playheadMs = pos
            scroll.scrollTo((pos * pxPerMs).roundToInt())
            delay(33)
        }
    }
    // Scrolling the timeline by hand scrubs the video.
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pxPerMs) {
        scroll.scrollTo((pos * pxPerMs).roundToInt())
        snapshotFlow { scroll.value }.collect { v ->
            if (!preview.player.isPlaying) {
                pos = (v / pxPerMs).toLong().coerceIn(0, current.totalMs)
                vm.playheadMs = pos
                preview.seek(pos)
            }
        }
    }
    // Let go near a cut or a marker: the playhead snaps to it.
    LaunchedEffect(dragged) {
        if (dragged) {
            preview.player.pause()
        } else {
            delay(250)
            val snap = (TimelineEdits.snap(current, pos) ?: current.markers.map { it.atMs }.minByOrNull { abs(it - pos) }?.takeIf { abs(it - pos) <= 150 })
            if (snap != null && snap != pos && !scroll.isScrollInProgress) scroll.animateScrollTo((snap * pxPerMs).roundToInt())
        }
    }
    BackHandler { if (s.canUndo) discard = true else vm.closeEdit() }

    fun seekTo(ms: Long) {
        preview.player.pause()
        val t = ms.coerceIn(0, current.totalMs)
        pos = t
        vm.playheadMs = t
        preview.seek(t)
    }

    Column(modifier) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { if (s.canUndo) discard = true else vm.closeEdit() }.padding(6.dp))
            Spacer(Modifier.weight(1f))
            Text("Undo", style = RtType.button, color = if (s.canUndo) RtColors.TextPrimary else RtColors.TextTertiary, modifier = Modifier.clickable(enabled = s.canUndo, role = Role.Button) { vm.undo() }.padding(6.dp))
            Text("Redo", style = RtType.button, color = if (s.canRedo) RtColors.TextPrimary else RtColors.TextTertiary, modifier = Modifier.clickable(enabled = s.canRedo, role = Role.Button) { vm.redo() }.padding(6.dp))
            Text("History", style = RtType.button, color = if (s.steps.isNotEmpty()) RtColors.TextSecondary else RtColors.TextTertiary, modifier = Modifier.clickable(enabled = s.steps.isNotEmpty(), role = Role.Button) { historyOpen = true }.padding(6.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Save",
                style = RtType.button,
                color = RtColors.OnPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(RtColors.Primary).clickable(role = Role.Button) { preview.player.pause(); vm.saveEdit() }.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // ---- preview
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BoxWithConstraints(
                Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                    .clickable(role = Role.Button, onClickLabel = "Play or pause") { if (preview.player.isPlaying) preview.player.pause() else preview.player.play() },
            ) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; player = preview.player } },
                    modifier = Modifier.fillMaxSize(),
                )
                val boxW = maxWidth
                val boxH = maxHeight
                // Layers, as still frames where they'll be (they move in the saved video).
                plan.layers.filter { pos in it.startMs until it.endMs }.forEach { l ->
                    LayerPreview(vm, s, l, pos, boxW.value, boxH.value, selected = (s.pick as? TimelinePick.Layer)?.id == l.id)
                }
                val fontPx = maxWidth.value * 0.075f
                val font = VibeFonts[plan.vibe]
                val style = TextStyle(fontFamily = font, fontSize = fontPx.sp, color = Color.White, textAlign = TextAlign.Center, shadow = Shadow(Color.Black, blurRadius = 8f))
                // The caption being said.
                val (si, local) = TimelineEdits.at(plan, pos)
                (plan.segments.getOrNull(si) as? ClipSegment)?.lines?.firstOrNull { local in it.startMs until it.endMs }?.let { l ->
                    Text(l.text, style = style.copy(fontSize = (fontPx * 0.8f).sp), modifier = Modifier.align(Alignment.TopCenter).offset(y = boxH * 0.66f).padding(horizontal = 12.dp))
                }
                // Text on the timeline; the selected one can be dragged up or down.
                plan.texts.filter { pos in it.startMs until it.endMs }.forEach { t ->
                    val selected = (s.pick as? TimelinePick.Text)?.id == t.id
                    var dy by remember(t.id, t.y) { mutableFloatStateOf(0f) }
                    Text(
                        t.text,
                        style = style,
                        modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, ((boxH * t.y - (fontPx / 1.5f).dp).toPx() + dy).roundToInt()) }
                            .then(if (selected) Modifier.border(1.dp, RtColors.Primary, RoundedCornerShape(4.dp)) else Modifier)
                            .pointerInput(t.id, selected) {
                                if (!selected) return@pointerInput
                                detectVerticalDragGestures(
                                    onDragEnd = { val moved = dy / with(density) { boxH.toPx() }; vm.change("Move text") { p -> TimelineEdits.placeText(p, t.id, t.y + moved) }; dy = 0f },
                                ) { ch, d -> ch.consume(); dy += d }
                            }
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                if (safe) SafeZones()
                if (!playing) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center).size(48.dp))
                }
                Text(
                    "Simple preview",
                    style = RtType.caption,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 4.dp),
                )
            }
        }
        // ---- transport
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = RtColors.TextPrimary,
                modifier = Modifier.size(28.dp).clickable(role = Role.Button) { if (playing) preview.player.pause() else preview.player.play() },
            )
            Text("‹", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "One frame back") { seekTo(pos - FRAME_MS) }.padding(horizontal = 10.dp))
            Text("›", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "One frame on") { seekTo(pos + FRAME_MS) }.padding(horizontal = 10.dp))
            Text("${clock(pos)} / ${clock(plan.totalMs)}", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
            Small("Mark") { vm.addMarker() }
            if (s.video != null) Small("Before") { preview.player.pause(); compare = true }
            Small(if (full) "Tracks" else "Full") { full = !full }
        }

        if (!full) {
            // ---- timeline (pinch to zoom)
            Box(Modifier.fillMaxWidth().pinchToZoom { z -> zoom = (zoom * z).coerceIn(0.25f, 8f) }) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val half = maxWidth / 2
                    val starts = TimelineEdits.starts(plan)
                    fun w(ms: Long) = with(density) { (ms * pxPerMs).toDp() }
                    val many = (s.pick as? TimelinePick.Clips)?.indices
                    Row(Modifier.horizontalScroll(scroll)) {
                        Spacer(Modifier.width(half))
                        Column(Modifier.width(w(plan.totalMs)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            // Markers
                            Box(Modifier.fillMaxWidth().height(10.dp)) {
                                plan.markers.forEach { m ->
                                    val sel = (s.pick as? TimelinePick.Marker)?.id == m.id
                                    Box(
                                        Modifier.offset { IntOffset((m.atMs * pxPerMs).roundToInt() - 5, 0) }.size(10.dp).clip(CircleShape)
                                            .background(if (sel) RtColors.Primary else Color(0xFFFFC83D))
                                            .clickable(role = Role.Button) { seekTo(m.atMs); vm.pick(if (sel) null else TimelinePick.Marker(m.id)) },
                                    )
                                }
                            }
                            // Clips
                            Row(Modifier.height(52.dp)) {
                                plan.segments.forEachIndexed { i, seg ->
                                    val selected = (s.pick as? TimelinePick.Clip)?.index == i || many?.contains(i) == true
                                    val isClip = seg is ClipSegment && !seg.tail
                                    Box(
                                        Modifier.width(w(seg.durMs)).fillMaxHeight().padding(horizontal = 1.dp).clip(RoundedCornerShape(6.dp))
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
                            // Layers
                            if (plan.layers.isNotEmpty()) {
                                Box(Modifier.fillMaxWidth().height(24.dp)) {
                                    plan.layers.forEach { l ->
                                        val selected = (s.pick as? TimelinePick.Layer)?.id == l.id
                                        Block("▣ Layer" + if (l.keys.isNotEmpty()) " · moves" else "", l.startMs, l.durMs, pxPerMs, Color(0x5594E2B8), selected) {
                                            vm.pick(if (selected) null else TimelinePick.Layer(l.id))
                                        }
                                    }
                                }
                            }
                            // Captions
                            Box(Modifier.fillMaxWidth().height(22.dp)) {
                                plan.segments.forEachIndexed { i, seg ->
                                    if (seg !is ClipSegment || seg.tail) return@forEachIndexed
                                    seg.lines.forEachIndexed { j, l ->
                                        val selected = s.pick == TimelinePick.Caption(i, j)
                                        Block(l.text, starts[i] + l.startMs, (l.endMs - l.startMs).coerceAtMost(seg.durMs - l.startMs), pxPerMs, RtColors.Primary.copy(alpha = 0.25f), selected) {
                                            vm.pick(if (selected) null else TimelinePick.Caption(i, j))
                                        }
                                    }
                                }
                            }
                            // Text
                            Box(Modifier.fillMaxWidth().height(22.dp)) {
                                plan.texts.forEach { t ->
                                    val selected = (s.pick as? TimelinePick.Text)?.id == t.id
                                    Block("T  ${t.text}", t.startMs, t.endMs - t.startMs, pxPerMs, Color(0x55FFC83D), selected) {
                                        vm.pick(if (selected) null else TimelinePick.Text(t.id))
                                    }
                                }
                            }
                            // Sound tracks
                            listOf(TrackKind.DETACHED, TrackKind.ENGINE).forEach { kind ->
                                val items = plan.audio.filter { it.kind == kind }
                                if (items.isEmpty()) return@forEach
                                Box(Modifier.fillMaxWidth().height(20.dp)) {
                                    items.forEach { a ->
                                        val selected = (s.pick as? TimelinePick.Audio)?.id == a.id
                                        val muted = plan.mix.gain(kind) == 0f
                                        Block((if (kind == TrackKind.ENGINE) "Engine" else "♪ Sound") + if (muted) " · muted" else "", a.startMs, a.durMs, pxPerMs, if (kind == TrackKind.ENGINE) Color(0x55F97316) else Color(0x55A78BFA), selected) {
                                            vm.pick(if (selected) null else TimelinePick.Audio(a.id))
                                        }
                                    }
                                }
                            }
                            if (s.takes.isNotEmpty()) {
                                Box(Modifier.fillMaxWidth().height(18.dp)) {
                                    s.takes.forEach { t -> Block("Voice-over", t.startMs, t.durMs, pxPerMs, Color(0x5560A5FA), false) { mixer = true } }
                                }
                            }
                            if (s.musicUri != null) {
                                Box(Modifier.fillMaxWidth().height(18.dp)) {
                                    Block("♫ ${s.musicName ?: "Music"}", 0, plan.totalMs, pxPerMs, Color(0x5534D399), false) { mixer = true }
                                }
                            }
                        }
                        Spacer(Modifier.width(half))
                    }
                }
                // The playhead, over every track.
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White))
                }
            }

            // ---- tools
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (val p = s.pick) {
                    is TimelinePick.Clip -> {
                        Choice("Split", false) { vm.change("Split") { TimelineEdits.split(it, pos) } }
                        Choice("← Move", false) { vm.change("Move") { TimelineEdits.move(it, p.index, -1) }; vm.pick(TimelinePick.Clip(p.index - 1)) }
                        Choice("Move →", false) { vm.change("Move") { TimelineEdits.move(it, p.index, 1) }; vm.pick(TimelinePick.Clip(p.index + 1)) }
                        val v = (plan.segments.getOrNull(p.index) as? ClipSegment)?.volume ?: 1f
                        val next = when { v >= 1.5f -> 0f; v == 0f -> 0.5f; v < 1f -> 1f; else -> 1.5f }
                        Choice("Sound ${(v * 100).roundToInt()}%", false) { vm.change("Sound") { TimelineEdits.volume(it, p.index, next) } }
                        Choice("Detach sound", false) { vm.detach(p.index) }
                        Choice("Copy settings", false) { vm.copySettings(p.index) }
                        if (s.copied != null) Choice("Paste", false) { vm.pasteSettings(setOf(p.index)) }
                        Choice("Select more", false) { vm.pick(TimelinePick.Clips(setOf(p.index))) }
                        Choice("Save clip", false) { vm.saveSegment(p.index) }
                        Choice("Delete", false) { vm.change("Delete") { TimelineEdits.delete(it, p.index) }; vm.pick(null) }
                    }
                    is TimelinePick.Clips -> {
                        Text("${p.indices.size} selected", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                        Choice("← Move", false) {
                            var moved = p.indices
                            vm.change("Move clips") { pl -> TrackEdits.moveClips(pl, p.indices, -1).also { moved = it.second }.first }
                            vm.pick(TimelinePick.Clips(moved))
                        }
                        Choice("Move →", false) {
                            var moved = p.indices
                            vm.change("Move clips") { pl -> TrackEdits.moveClips(pl, p.indices, 1).also { moved = it.second }.first }
                            vm.pick(TimelinePick.Clips(moved))
                        }
                        if (s.copied != null) Choice("Paste settings", false) { vm.pasteSettings(p.indices) }
                        Choice("Delete", false) { vm.change("Delete clips") { TrackEdits.deleteClips(it, p.indices) }; vm.pick(null) }
                        Choice("Done", false) { vm.pick(null) }
                    }
                    is TimelinePick.Layer -> {
                        val l = plan.layers.firstOrNull { it.id == p.id }
                        LayerPreset.entries.forEach { pr -> Choice(pr.label, false) { vm.change("Layer place") { TrackEdits.preset(it, p.id, pr) } } }
                        Choice("Smaller", false) { vm.change("Layer size") { TrackEdits.resizeLayer(it, p.id, 0.85f, pos) } }
                        Choice("Bigger", false) { vm.change("Layer size") { TrackEdits.resizeLayer(it, p.id, 1.18f, pos) } }
                        val shape = l?.shape ?: LayerShape.ROUNDED
                        val nextShape = LayerShape.entries[(shape.ordinal + 1) % LayerShape.entries.size]
                        Choice(shape.label, false) { vm.change("Layer shape") { TrackEdits.shape(it, p.id, nextShape) } }
                        Choice(if (l?.border == true) "Frame on" else "Frame off", false) { vm.change("Layer frame") { TrackEdits.border(it, p.id, l?.border != true) } }
                        val o = l?.opacity ?: 1f
                        Choice("Opacity ${(o * 100).roundToInt()}%", false) { vm.change("Layer opacity") { TrackEdits.opacity(it, p.id, if (o <= 0.4f) 1f else o - 0.25f) } }
                        Choice("Rotate", false) { vm.change("Rotate layer") { TrackEdits.rotateLayer(it, p.id, 90f) } }
                        Choice("Move here", false) { vm.change("Layer move point") { TrackEdits.addKey(it, p.id, pos) } }
                        if (l?.keys?.isNotEmpty() == true) Choice("Clear moves", false) { vm.change("Clear moves") { TrackEdits.clearKeys(it, p.id) } }
                        Choice("Earlier", false) { vm.change("Layer time") { TrackEdits.shiftLayer(it, p.id, -500) } }
                        Choice("Later", false) { vm.change("Layer time") { TrackEdits.shiftLayer(it, p.id, 500) } }
                        Choice("Shorter", false) { vm.change("Layer length") { TrackEdits.resizeLayerTime(it, p.id, -500) } }
                        Choice("Longer", false) { vm.change("Layer length") { TrackEdits.resizeLayerTime(it, p.id, 500) } }
                        Choice(if ((l?.volume ?: 0f) > 0f) "Its sound on" else "Its sound off", false) { vm.change("Layer sound") { TrackEdits.layerVolume(it, p.id, if ((l?.volume ?: 0f) > 0f) 0f else 1f) } }
                        Choice("Delete", false) { vm.change("Delete layer") { TrackEdits.deleteLayer(it, p.id) }; vm.pick(null) }
                    }
                    is TimelinePick.Audio -> {
                        val a = plan.audio.firstOrNull { it.id == p.id }
                        val v = a?.volume ?: 1f
                        val nextV = when { v >= 1.5f -> 0.25f; v < 0.5f -> 0.5f; v < 1f -> 1f; else -> 1.5f }
                        Choice("Volume ${(v * 100).roundToInt()}%", false) { vm.change("Volume") { TrackEdits.audioVolume(it, p.id, nextV) } }
                        val faded = (a?.fadeInMs ?: 0) > 0
                        Choice(if (faded) "Fades on" else "Fades off", false) { vm.change("Fades") { TrackEdits.fade(it, p.id, if (faded) 0 else 400, if (faded) 0 else 600) } }
                        Choice("Dip here", false) {
                            val local = pos - (a?.startMs ?: 0)
                            vm.change("Volume point") { TrackEdits.addVolumePoint(it, p.id, local, 0.3f) }
                        }
                        Choice("Full here", false) {
                            val local = pos - (a?.startMs ?: 0)
                            vm.change("Volume point") { TrackEdits.addVolumePoint(it, p.id, local, 1f) }
                        }
                        if (a?.curve?.isNotEmpty() == true) Choice("Clear points", false) { vm.change("Clear points") { TrackEdits.clearCurve(it, p.id) } }
                        Choice("Earlier", false) { vm.change("Sound time") { TrackEdits.shiftAudio(it, p.id, -250) } }
                        Choice("Later", false) { vm.change("Sound time") { TrackEdits.shiftAudio(it, p.id, 250) } }
                        Choice("Start sooner", false) { vm.change("Sound start") { TrackEdits.trimAudioStart(it, p.id, -250) } }
                        Choice("Start later", false) { vm.change("Sound start") { TrackEdits.trimAudioStart(it, p.id, 250) } }
                        Choice("Shorter", false) { vm.change("Sound length") { TrackEdits.resizeAudio(it, p.id, -250) } }
                        Choice("Longer", false) { vm.change("Sound length") { TrackEdits.resizeAudio(it, p.id, 250) } }
                        Choice("Delete", false) { vm.change("Delete sound") { TrackEdits.deleteAudio(it, p.id) }; vm.pick(null) }
                    }
                    is TimelinePick.Marker -> {
                        Choice("Delete marker", false) { vm.change("Delete marker") { TrackEdits.deleteMarker(it, p.id) }; vm.pick(null) }
                    }
                    is TimelinePick.Caption -> {
                        val line = (plan.segments.getOrNull(p.index) as? ClipSegment)?.lines?.getOrNull(p.line)
                        Choice("Edit words", false) { ask = TextAsk.EditCaption(p.index, p.line, line?.text.orEmpty()) }
                        Choice("Earlier", false) { vm.change("Caption time") { TimelineEdits.nudgeCaption(it, p.index, p.line, -200) } }
                        Choice("Later", false) { vm.change("Caption time") { TimelineEdits.nudgeCaption(it, p.index, p.line, 200) } }
                        Choice("Delete", false) { vm.change("Delete caption") { TimelineEdits.caption(it, p.index, p.line, "") }; vm.pick(null) }
                    }
                    is TimelinePick.Text -> {
                        val t = plan.texts.firstOrNull { it.id == p.id }
                        Choice("Edit", false) { ask = TextAsk.EditText(p.id, t?.text.orEmpty()) }
                        Choice("Earlier", false) { vm.change("Text time") { TimelineEdits.moveText(it, p.id, -500) } }
                        Choice("Later", false) { vm.change("Text time") { TimelineEdits.moveText(it, p.id, 500) } }
                        Choice("Shorter", false) { vm.change("Text length") { TimelineEdits.resizeText(it, p.id, -500) } }
                        Choice("Longer", false) { vm.change("Text length") { TimelineEdits.resizeText(it, p.id, 500) } }
                        Choice("Delete", false) { vm.change("Delete text") { TimelineEdits.deleteText(it, p.id) }; vm.pick(null) }
                    }
                    null -> {
                        Choice("+ Text", false) { ask = TextAsk.NewText }
                        Choice("+ Caption", false) { ask = TextAsk.NewCaption }
                        Choice("+ Clip", false) { adding = AddWhat.CLIP }
                        Choice("+ Layer", false) { adding = AddWhat.LAYER }
                        if (s.engineFiles.isNotEmpty() && plan.audio.none { it.kind == TrackKind.ENGINE }) Choice("+ Engine sound", false) { vm.addEngine() }
                        Choice("Sound", false) { mixer = true }
                        Choice("Split", false) { vm.change("Split") { TimelineEdits.split(it, pos) } }
                        Choice(if (safe) "Safe zones on" else "Safe zones", false) { safe = !safe }
                    }
                }
            }
            Text(
                when (s.pick) {
                    is TimelinePick.Clip -> "Drag the white edges to trim. Split cuts at the playhead. Select more to move or delete several."
                    is TimelinePick.Clips -> "Tap clips to add or take them out of the selection."
                    is TimelinePick.Layer -> "Drag the layer on the video to move it, pinch to resize. Move here marks where it is now, so it glides there."
                    is TimelinePick.Audio -> "A detached sound can run past its cut. Dip here and Full here shape its volume."
                    is TimelinePick.Text -> "Drag the text up or down on the video to place it."
                    else -> "Scroll the timeline to scrub; pinch it to zoom. Mark drops a marker. Colours, camera moves and layer moves show in the saved video."
                },
                style = RtType.caption,
                color = RtColors.TextTertiary,
            )
            s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextPrimary) }
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
                Text(if (what == AddWhat.LAYER) "Layer over the video, at the playhead" else "Add at the playhead", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                Text("From your phone", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)); adding = null
                }.padding(vertical = 6.dp))
                val list = if (what == AddWhat.LAYER) vm.insertable() + vm.layerable() else vm.insertable()
                list.distinctBy { it.id }.take(50).forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                            if (what == AddWhat.LAYER) vm.addLayer(b, LayerPreset.CORNER) else vm.insert(b)
                            adding = null
                        }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(s.thumbs[b.momentId], Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.lines.joinToString(" ") { it.text }.ifBlank { "Riding · ${b.speedKmh.toInt()} km/h" }, style = RtType.body, color = RtColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(b.fromRide, b.source?.takeIf { b.fromRide == null }?.let { "Phone" }, if (b.camera == "back") "Road" else null, String.format(Locale.US, "%.1f s", (b.outMs - b.inMs) / 1000.0)).joinToString(" · "), style = RtType.caption, color = RtColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
    if (mixer) MixerSheet(vm, s, plan) { mixer = false }
    if (historyOpen) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { historyOpen = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("History", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                Text("Tap a step to go back to just before it.", style = RtType.caption, color = RtColors.TextTertiary)
                Text("As it was", style = RtType.body, color = RtColors.Primary, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.undoTo(0); historyOpen = false }.padding(vertical = 8.dp))
                s.steps.forEachIndexed { i, label ->
                    Text("${i + 1}. $label", style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.undoTo(i); historyOpen = false }.padding(vertical = 8.dp))
                }
            }
        }
    }
    if (compare) {
        val before = s.video
        androidx.compose.ui.window.Dialog(onDismissRequest = { compare = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Before your changes", style = RtType.bodyStrong, color = Color.White)
                if (before != null) Player(android.net.Uri.fromFile(before), Modifier, height = 480.dp)
                Text("Close", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { compare = false }.padding(8.dp))
            }
        }
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

/** A layer on the preview, as a still of its clip; the selected one drags to move and pinches to resize. */
@Composable
private fun LayerPreview(vm: StudioViewModel, s: StudioState, l: LayerItem, pos: Long, boxW: Float, boxH: Float, selected: Boolean) {
    val k = TrackEdits.keyAt(l, pos - l.startMs)
    val w = boxW * k.w
    val h = w / l.aspect
    var dx by remember(l.id, k.cx) { mutableFloatStateOf(0f) }
    var dy by remember(l.id, k.cy) { mutableFloatStateOf(0f) }
    var scale by remember(l.id, k.w) { mutableFloatStateOf(1f) }
    val shape: Shape = when (l.shape) {
        LayerShape.RECT -> RoundedCornerShape(0.dp)
        LayerShape.ROUNDED -> RoundedCornerShape((minOf(w, h) * 0.08f).dp)
        LayerShape.CIRCLE -> CircleShape
    }
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(((boxW * k.cx - w / 2).dp.toPx() + dx).roundToInt(), ((boxH * k.cy - h / 2).dp.toPx() + dy).roundToInt()) }
            .size(w.dp, h.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = l.opacity }
            .rotate(l.rotation)
            .clip(shape)
            .then(if (l.border) Modifier.border(2.dp, Color.White, shape) else Modifier)
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
                    if (mx != 0f || my != 0f || f != 1f) {
                        vm.change("Move layer") { p ->
                            val moved = TrackEdits.moveLayer(p, l.id, mx, my, pos)
                            if (f != 1f) TrackEdits.resizeLayer(moved, l.id, f, pos) else moved
                        }
                    }
                }
            },
    ) {
        Thumb(s.thumbs[l.bit.momentId], Modifier.fillMaxSize())
    }
}

/** The mixer: each sound track's volume, mute and solo; the dip under your voice, and the voice clean-up. */
@Composable
private fun MixerSheet(vm: StudioViewModel, s: StudioState, plan: StudioPlan, onClose: () -> Unit) {
    val tracks = buildList {
        add(TrackKind.CLIPS)
        if (plan.audio.any { it.kind == TrackKind.DETACHED }) add(TrackKind.DETACHED)
        if (plan.audio.any { it.kind == TrackKind.ENGINE }) add(TrackKind.ENGINE)
        if (plan.layers.any { it.volume > 0f }) add(TrackKind.LAYERS)
        if (s.takes.isNotEmpty()) add(TrackKind.VOICE_OVER)
        if (s.musicUri != null) add(TrackKind.MUSIC)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Sound", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            tracks.forEach { k ->
                val v = plan.mix.volume(k)
                val muted = k in plan.mix.muted
                val solo = plan.mix.solo == k
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.label, style = RtType.body, color = if (plan.mix.gain(k) == 0f) RtColors.TextTertiary else RtColors.TextPrimary)
                        Text("${(v * 100).roundToInt()}%", style = RtType.caption, color = RtColors.TextSecondary)
                    }
                    Small("−") { vm.change("Track volume") { TrackEdits.trackVolume(it, k, (v - 0.1f).coerceAtLeast(0f)) } }
                    Small("+") { vm.change("Track volume") { TrackEdits.trackVolume(it, k, (v + 0.1f).coerceAtMost(1.5f)) } }
                    Small(if (muted) "Muted" else "Mute", on = muted) { vm.change("Mute") { TrackEdits.mute(it, k) } }
                    Small("Solo", on = solo) { vm.change("Solo") { TrackEdits.solo(it, k) } }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dip music and engine while I talk", style = RtType.body, color = RtColors.TextPrimary)
                }
                Switch(checked = plan.mix.duck, onCheckedChange = { on -> vm.change("Dip under voice") { TrackEdits.duck(it, on) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Clean up my voice", style = RtType.body, color = RtColors.TextPrimary)
                    Text("Takes out wind rumble and engine drone under your words", style = RtType.caption, color = RtColors.TextSecondary)
                }
                Switch(checked = plan.mix.cleanVoice, onCheckedChange = { on -> vm.change("Clean voice") { TrackEdits.cleanVoice(it, on) } })
            }
            if (s.engineFiles.isNotEmpty() && plan.audio.none { it.kind == TrackKind.ENGINE }) {
                Text("+ Engine sound from the second mic", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.addEngine() }.padding(vertical = 6.dp))
            }
            Text("The mix plays in the saved video.", style = RtType.caption, color = RtColors.TextTertiary)
            Text("Done", style = RtType.button, color = RtColors.Primary, modifier = Modifier.align(Alignment.End).clickable(role = Role.Button, onClick = onClose).padding(8.dp))
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

/** A block on a track, from [startMs] for [durMs]. */
@Composable
private fun Block(label: String, startMs: Long, durMs: Long, pxPerMs: Float, color: Color, selected: Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier.offset { IntOffset((startMs * pxPerMs).roundToInt(), 0) }
            .width(with(density) { (durMs.coerceAtLeast(200) * pxPerMs).toDp() }).fillMaxHeight()
            .clip(RoundedCornerShape(4.dp)).background(color)
            .then(if (selected) Modifier.border(2.dp, RtColors.Primary, RoundedCornerShape(4.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Where Instagram and YouTube put their buttons and text: keep your words out of these. */
@Composable
private fun SafeZones() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shade = Color(0x55FF3B3B)
        Box(Modifier.fillMaxWidth().height(maxHeight * 0.1f).background(shade))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(maxHeight * 0.22f).background(shade))
        Box(Modifier.align(Alignment.CenterEnd).width(maxWidth * 0.16f).fillMaxHeight(0.5f).offset(y = maxHeight * 0.1f).background(shade))
        Text("Safe area", style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.Center))
    }
}

private fun clock(ms: Long): String = String.format(Locale.US, "%d:%04.1f", ms / 60_000, (ms % 60_000) / 1000.0)
