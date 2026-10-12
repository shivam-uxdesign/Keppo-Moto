package com.ridetrack.app.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import java.util.Locale
import kotlin.math.roundToInt

private fun mmss(ms: Long): String = String.format(Locale.US, "%d:%02d", ms / 60_000, ms / 1000 % 60)

// ---- the mixer -------------------------------------------------------------------------------------

/**
 * The mix as a desk: one fader per sound, side by side; slide up or down, double-tap to mute,
 * Solo under each. Then the dip under your voice and the voice clean-up.
 */
@Composable
internal fun MixerSheet(vm: StudioViewModel, s: StudioState, plan: StudioPlan, visible: Boolean, onDismiss: () -> Unit) {
    val tracks = buildList {
        add(TrackKind.CLIPS)
        if (plan.audio.any { it.kind == TrackKind.DETACHED }) add(TrackKind.DETACHED)
        if (plan.audio.any { it.kind == TrackKind.ENGINE }) add(TrackKind.ENGINE)
        if (plan.layers.any { it.volume > 0f }) add(TrackKind.LAYERS)
        if (s.takes.isNotEmpty()) add(TrackKind.VOICE_OVER)
        if (s.musicUri != null || plan.audio.any { it.kind == TrackKind.MUSIC }) add(TrackKind.MUSIC)
    }
    StudioSheet(visible, onDismiss, "Sound") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            tracks.forEach { k ->
                val v = plan.mix.volume(k)
                val muted = k in plan.mix.muted
                val solo = plan.mix.solo == k
                Column(
                    Modifier.width(64.dp).pointerInput(k) { detectTapGestures(onDoubleTap = { vm.change(if (muted) "Unmute" else "Mute") { TrackEdits.mute(it, k) } }) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EdgeSlider(
                        v / 1.5f,
                        if (muted) "Muted" else "${(v * 100).roundToInt()}%",
                        Modifier.height(170.dp),
                        onChange = { f -> vm.drag { TrackEdits.trackVolume(it, k, (f * 1.5f * 20).roundToInt() / 20f) } },
                        onEnd = { vm.endDrag("${k.label} volume") },
                    )
                    Text(TRACK_NAMES[k] ?: k.label, style = RtType.caption, color = if (plan.mix.gain(k) == 0f) RtColors.TextTertiary else RtColors.TextPrimary, textAlign = TextAlign.Center, maxLines = 2)
                    Text(
                        "Solo",
                        style = RtType.caption,
                        color = if (solo) RtColors.Primary else RtColors.TextSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.change("Solo") { TrackEdits.solo(it, k) } }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Text("Slide a bar up or down · double-tap it to mute", style = RtType.caption, color = RtColors.TextTertiary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lower the rest while I talk", style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            Switch(checked = plan.mix.duck, onCheckedChange = { on -> vm.change("Dip under voice") { TrackEdits.duck(it, on) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clean up my voice", style = RtType.body, color = RtColors.TextPrimary)
                Text("Takes out wind rumble and engine drone", style = RtType.caption, color = RtColors.TextSecondary)
            }
            Switch(checked = plan.mix.cleanVoice, onCheckedChange = { on -> vm.change("Clean voice") { TrackEdits.cleanVoice(it, on) } })
        }
        if (s.engineFiles.isNotEmpty() && plan.audio.none { it.kind == TrackKind.ENGINE }) {
            SheetRow("+ Engine sound from the second mic") { vm.addEngine() }
        }
    }
}

private val TRACK_NAMES = mapOf(TrackKind.CLIPS to "Your voice", TrackKind.DETACHED to "Moved sound", TrackKind.ENGINE to "Engine", TrackKind.MUSIC to "Music", TrackKind.VOICE_OVER to "Voice-over", TrackKind.LAYERS to "Layers")

// ---- voice-over ------------------------------------------------------------------------------------

/**
 * Voice-over where the tools were: hold the button to talk (let go to stop), or tap to start and
 * tap again to stop. The Reel plays muted from the playhead meanwhile; each take sits on the timeline.
 */
@Composable
internal fun VoicePanel(
    takes: List<VoiceTake>,
    recordingAt: Long?,
    posMs: Long,
    allowed: Boolean,
    onAsk: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: (VoiceTake) -> Unit,
    onBack: () -> Unit,
) {
    val rec by rememberUpdatedState(recordingAt != null)
    val ok by rememberUpdatedState(allowed)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val ask by rememberUpdatedState(onAsk)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "Back to the tools", onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Back", tint = RtColors.TextPrimary)
            }
            Text("Voice-over", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            recordingAt?.let { Text("● ${mmss((posMs - it).coerceAtLeast(0))}", style = RtType.bodyStrong, color = Color(0xFFFF4D5E)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.padding(start = 8.dp).size(68.dp).clip(CircleShape).background(if (recordingAt != null) Color(0xFFFF4D5E) else RtColors.Primary)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            if (!ok) { ask(); return@detectTapGestures }
                            val was = rec
                            val t0 = System.currentTimeMillis()
                            if (!was) start()
                            tryAwaitRelease()
                            // A tap while recording stops; holding to talk stops on letting go.
                            if (was || System.currentTimeMillis() - t0 > 400) stop()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (recordingAt != null) Icons.Rounded.Stop else Icons.Rounded.Mic, contentDescription = if (recordingAt != null) "Stop recording" else "Record", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (allowed) "Hold to talk, or tap to start and stop" else "Tap to let Studio use the mic", style = RtType.body, color = RtColors.TextPrimary)
                Text("The Reel plays muted from the playhead. Captions for it come when you make the video.", style = RtType.caption, color = RtColors.TextSecondary)
            }
        }
        if (takes.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                takes.forEachIndexed { k, t ->
                    Text(
                        "Take ${k + 1} · ${mmss(t.startMs)}  ✕",
                        style = RtType.caption,
                        color = RtColors.TextPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x5560A5FA)).clickable(onClickLabel = "Delete take ${k + 1}") { onDelete(t) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// ---- stickers --------------------------------------------------------------------------------------

/** Emoji a rider reaches for; any other can be typed. */
private val RIDE_EMOJI = listOf("🏍️", "🔥", "💨", "😂", "👀", "🤯", "🌧️", "⛰️", "🌅", "👍", "🙏", "⚡", "🛣️", "😎", "❤️", "🏁")

/** Stickers: live speed and lean, an arrow, a circle, or an emoji. */
@Composable
internal fun StickersSheet(visible: Boolean, onPick: (StickerKind, String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    StudioSheet(visible, onDismiss, "Stickers") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(StickerKind.SPEED to "62", StickerKind.LEAN to "32°", StickerKind.ARROW to "➜", StickerKind.CIRCLE to "◯").forEach { (k, face) ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(k, "") }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(RtColors.SurfaceRaised).border(1.dp, RtColors.Outline, CircleShape), contentAlignment = Alignment.Center) {
                        Text(face, style = RtType.bodyStrong, color = Color.White)
                    }
                    Text(k.label, style = RtType.caption, color = RtColors.TextPrimary)
                }
            }
        }
        Text("Speed and lean follow the ride as the video plays.", style = RtType.caption, color = RtColors.TextTertiary)
        RIDE_EMOJI.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { e -> Text(e, fontSize = 26.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(StickerKind.EMOJI, e) }.padding(4.dp)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(8) },
                placeholder = { Text("Or type any emoji", style = RtType.body, color = RtColors.TextTertiary) },
                singleLine = true,
                textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (typed.isNotBlank()) Text("Add", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable { onPick(StickerKind.EMOJI, typed.trim()); typed = "" }.padding(8.dp))
        }
    }
}

// ---- history ---------------------------------------------------------------------------------------

/**
 * History: Changes (newest first; tap one to go back to just before it, Redo brings them back)
 * and Marks (the spots you marked; tap to go there).
 */
@Composable
internal fun HistorySheet(
    steps: List<String>,
    markers: List<Marker>,
    visible: Boolean,
    onUndoTo: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onDeleteMark: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var marks by remember(visible) { mutableStateOf(false) }
    StudioSheet(visible, onDismiss) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised).padding(3.dp)) {
            listOf(false to "Changes", true to "Marks").forEach { (m, label) ->
                Text(
                    label,
                    style = RtType.button,
                    color = RtColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (marks == m) RtColors.Surface else Color.Transparent).clickable { marks = m }.padding(vertical = 8.dp),
                )
            }
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            if (!marks) {
                Text("Tap a change to go back to just before it. Nothing is lost: Redo brings later changes back. Two fingers tap to undo, three to redo.", style = RtType.caption, color = RtColors.TextTertiary)
                HistoryRow("Now", null, RtColors.Primary) { onDismiss() }
                steps.withIndex().reversed().forEach { (i, label) -> HistoryRow(label, "step ${i + 1}", RtColors.TextPrimary) { onUndoTo(i); onDismiss() } }
                HistoryRow("As it was when it opened", "start", RtColors.TextSecondary) { onUndoTo(0); onDismiss() }
            } else {
                if (markers.isEmpty()) Text("No marks yet. Marker in the tools drops one at the playhead.", style = RtType.body, color = RtColors.TextSecondary, modifier = Modifier.padding(vertical = 8.dp))
                markers.sortedBy { it.atMs }.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HistoryRow("Mark at ${mmss(m.atMs)}", null, RtColors.TextPrimary, Modifier.weight(1f)) { onSeek(m.atMs); onDismiss() }
                        Text("Delete", style = RtType.caption, color = RtColors.Error, modifier = Modifier.clickable { onDeleteMark(m.id) }.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(label: String, detail: String?, color: Color, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = RtType.body, color = color, modifier = Modifier.weight(1f), maxLines = 1)
        detail?.let { Text(it, style = RtType.caption, color = RtColors.TextTertiary) }
    }
}
