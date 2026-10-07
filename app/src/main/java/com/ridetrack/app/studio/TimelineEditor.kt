package com.ridetrack.app.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt

/** What a text dialog is for. */
private sealed interface TextAsk {
    data object NewText : TextAsk
    data object NewCaption : TextAsk
    data class EditText(val id: String, val text: String) : TextAsk
    data class EditCaption(val index: Int, val line: Int, val text: String) : TextAsk
}

/**
 * The timeline editor: the edit plays live at the top (no rendering), the timeline below scrolls
 * under a fixed playhead to scrub. Tracks: clips (tap to select, drag the edges to trim), captions,
 * text, voice-over. Tools change with what's selected. Save makes the video once.
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
    var ask by remember { mutableStateOf<TextAsk?>(null) }
    var addClip by remember { mutableStateOf(false) }
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
    LaunchedEffect(dragged) { if (dragged) preview.player.pause() }
    BackHandler { if (s.canUndo) discard = true else vm.closeEdit() }

    Column(modifier) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { if (s.canUndo) discard = true else vm.closeEdit() }.padding(6.dp))
            Spacer(Modifier.weight(1f))
            Text("Undo", style = RtType.button, color = if (s.canUndo) RtColors.TextPrimary else RtColors.TextTertiary, modifier = Modifier.clickable(enabled = s.canUndo, role = Role.Button) { vm.undo() }.padding(6.dp))
            Text("Redo", style = RtType.button, color = if (s.canRedo) RtColors.TextPrimary else RtColors.TextTertiary, modifier = Modifier.clickable(enabled = s.canRedo, role = Role.Button) { vm.redo() }.padding(6.dp))
            Text("Safe zones", style = RtType.button, color = if (safe) RtColors.Primary else RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Switch) { safe = !safe }.padding(6.dp))
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
                val boxH = maxHeight
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
                                    onDragEnd = { val moved = dy / with(density) { boxH.toPx() }; vm.change { p -> TimelineEdits.placeText(p, t.id, t.y + moved) }; dy = 0f },
                                ) { ch, d -> ch.consume(); dy += d }
                            }
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                if (safe) SafeZones()
                if (!playing) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center).size(48.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = RtColors.TextPrimary,
                modifier = Modifier.size(28.dp).clickable(role = Role.Button) { if (playing) preview.player.pause() else preview.player.play() },
            )
            Spacer(Modifier.width(8.dp))
            Text("${clock(pos)} / ${clock(plan.totalMs)}", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
            Text("−", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Zoom out") { zoom = (zoom / 1.5f).coerceAtLeast(0.25f) }.padding(horizontal = 12.dp))
            Text("+", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Zoom in") { zoom = (zoom * 1.5f).coerceAtMost(6f) }.padding(horizontal = 12.dp))
        }

        // ---- timeline
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val half = maxWidth / 2
            val starts = TimelineEdits.starts(plan)
            fun w(ms: Long) = with(density) { (ms * pxPerMs).toDp() }
            Row(Modifier.horizontalScroll(scroll)) {
                Spacer(Modifier.width(half))
                Column(Modifier.width(w(plan.totalMs)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Clips
                    Row(Modifier.height(56.dp)) {
                        plan.segments.forEachIndexed { i, seg ->
                            val selected = (s.pick as? TimelinePick.Clip)?.index == i
                            val isClip = seg is ClipSegment && !seg.tail
                            Box(
                                Modifier.width(w(seg.durMs)).fillMaxHeight().padding(horizontal = 1.dp).clip(RoundedCornerShape(6.dp))
                                    .background(RtColors.SurfaceRaised)
                                    .then(if (selected) Modifier.border(2.dp, RtColors.Primary, RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable(enabled = isClip, role = Role.Button) { vm.pick(if (selected) null else TimelinePick.Clip(i)) },
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
                                if (selected) {
                                    TrimHandle(Modifier.align(Alignment.CenterStart), pxPerMs, vm::endDrag) { ms -> vm.drag { p -> TimelineEdits.trimStart(p, i, ms) } }
                                    TrimHandle(Modifier.align(Alignment.CenterEnd), pxPerMs, vm::endDrag) { ms -> vm.drag { p -> TimelineEdits.trimEnd(p, i, ms) } }
                                }
                            }
                        }
                    }
                    // Captions
                    Box(Modifier.fillMaxWidth().height(26.dp)) {
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
                    Box(Modifier.fillMaxWidth().height(26.dp)) {
                        plan.texts.forEach { t ->
                            val selected = (s.pick as? TimelinePick.Text)?.id == t.id
                            Block("T  ${t.text}", t.startMs, t.endMs - t.startMs, pxPerMs, Color(0x55FFC83D), selected) {
                                vm.pick(if (selected) null else TimelinePick.Text(t.id))
                            }
                        }
                    }
                    // Voice-over
                    if (s.takes.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().height(20.dp)) {
                            s.takes.forEach { t -> Block("Voice", t.startMs, t.durMs, pxPerMs, Color(0x5560A5FA), false) {} }
                        }
                    }
                }
                Spacer(Modifier.width(half))
            }
            // The playhead.
            Box(Modifier.align(Alignment.TopCenter).width(2.dp).height(140.dp).background(Color.White))
        }

        // ---- tools
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val p = s.pick) {
                is TimelinePick.Clip -> {
                    Choice("Split", false) { vm.change { TimelineEdits.split(it, pos) } }
                    Choice("← Move", false) { vm.change { TimelineEdits.move(it, p.index, -1) }; vm.pick(TimelinePick.Clip(p.index - 1)) }
                    Choice("Move →", false) { vm.change { TimelineEdits.move(it, p.index, 1) }; vm.pick(TimelinePick.Clip(p.index + 1)) }
                    val v = (plan.segments.getOrNull(p.index) as? ClipSegment)?.volume ?: 1f
                    val next = when { v >= 1.5f -> 0f; v == 0f -> 0.5f; v < 1f -> 1f; else -> 1.5f }
                    Choice("Sound ${(v * 100).roundToInt()}%", false) { vm.change { TimelineEdits.volume(it, p.index, next) } }
                    Choice("Delete", false) { vm.change { TimelineEdits.delete(it, p.index) }; vm.pick(null) }
                }
                is TimelinePick.Caption -> {
                    val line = (plan.segments.getOrNull(p.index) as? ClipSegment)?.lines?.getOrNull(p.line)
                    Choice("Edit words", false) { ask = TextAsk.EditCaption(p.index, p.line, line?.text.orEmpty()) }
                    Choice("Earlier", false) { vm.change { TimelineEdits.nudgeCaption(it, p.index, p.line, -200) } }
                    Choice("Later", false) { vm.change { TimelineEdits.nudgeCaption(it, p.index, p.line, 200) } }
                    Choice("Delete", false) { vm.change { TimelineEdits.caption(it, p.index, p.line, "") }; vm.pick(null) }
                }
                is TimelinePick.Text -> {
                    val t = plan.texts.firstOrNull { it.id == p.id }
                    Choice("Edit", false) { ask = TextAsk.EditText(p.id, t?.text.orEmpty()) }
                    Choice("Earlier", false) { vm.change { TimelineEdits.moveText(it, p.id, -500) } }
                    Choice("Later", false) { vm.change { TimelineEdits.moveText(it, p.id, 500) } }
                    Choice("Shorter", false) { vm.change { TimelineEdits.resizeText(it, p.id, -500) } }
                    Choice("Longer", false) { vm.change { TimelineEdits.resizeText(it, p.id, 500) } }
                    Choice("Delete", false) { vm.change { TimelineEdits.deleteText(it, p.id) }; vm.pick(null) }
                }
                null -> {
                    Choice("+ Text", false) { ask = TextAsk.NewText }
                    Choice("+ Caption", false) { ask = TextAsk.NewCaption }
                    Choice("+ Clip", false) { addClip = true }
                    Choice("Split", false) { vm.change { TimelineEdits.split(it, pos) } }
                }
            }
        }
        Text(
            when (s.pick) {
                is TimelinePick.Clip -> "Drag the white edges to trim. Split cuts at the playhead."
                is TimelinePick.Text -> "Drag the text up or down on the video to place it."
                else -> "Scroll the timeline to scrub. Tap a clip, caption or text to change it. Colours and camera moves show in the saved video."
            },
            style = RtType.caption,
            color = RtColors.TextTertiary,
        )
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
                        is TextAsk.EditText -> vm.change { TimelineEdits.editText(it, a.id, value) }
                        is TextAsk.EditCaption -> vm.change { TimelineEdits.caption(it, a.index, a.line, value) }
                    }
                    ask = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
        )
    }
    if (addClip) {
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> if (uris.isNotEmpty()) vm.addPhoneVideos(uris) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { addClip = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Add at the playhead", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                Text("From your phone", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)); addClip = false
                }.padding(vertical = 6.dp))
                vm.insertable().take(40).forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { vm.insert(b); addClip = false }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(s.thumbs[b.momentId], Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.lines.joinToString(" ") { it.text }.ifBlank { "Riding · ${b.speedKmh.toInt()} km/h" }, style = RtType.body, color = RtColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(b.fromRide, b.source?.let { "Phone" }, String.format(Locale.US, "%.1f s", (b.outMs - b.inMs) / 1000.0)).joinToString(" · "), style = RtType.caption, color = RtColors.TextSecondary)
                        }
                    }
                }
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
