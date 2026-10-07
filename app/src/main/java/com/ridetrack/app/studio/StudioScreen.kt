package com.ridetrack.app.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ridetrack.app.R
import com.ridetrack.app.share.ShareImages
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

internal val VibeFonts = mapOf(
    Vibe.HYPE to FontFamily(Font(R.font.anton)),
    Vibe.CINE to FontFamily(Font(R.font.instrument_serif_italic)),
    Vibe.CHILL to FontFamily(Font(R.font.permanent_marker)),
    Vibe.VLOG to FontFamily(Font(R.font.geist_semibold)),
)

/** Share ride › Reel: Keppo Studio turns the ride's clips into a 30–60 s Reel. */
@Composable
fun StudioPanel(rideId: String, modifier: Modifier = Modifier, reelId: String? = null) {
    val vm = appViewModel(key = "studio-$rideId-${reelId.orEmpty()}") { StudioViewModel(it, rideId, reelId) }
    val s by vm.state.collectAsStateWithLifecycle()
    // Making a video takes a while: keep the screen on so the phone doesn't sleep through it.
    val view = LocalView.current
    DisposableEffect(s.step) {
        view.keepScreenOn = s.step == StudioStep.WORKING
        onDispose { view.keepScreenOn = false }
    }
    when {
        s.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp) }
        s.blocked != null -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(s.blocked!!, style = RtType.body, color = RtColors.TextSecondary)
        }
        else -> when (s.step) {
            StudioStep.SETUP -> Setup(vm, s, modifier)
            StudioStep.WORKING -> Working(vm, s, modifier)
            StudioStep.READY -> Ready(vm, s, modifier)
            StudioStep.EDIT -> TimelineEditor(vm, s, modifier)
            StudioStep.VOICE -> Voice(vm, s, modifier)
            StudioStep.COVER -> CoverEditor(vm, s, modifier)
            StudioStep.SCRIPT -> ScriptView(vm, s, modifier)
        }
    }
}

/** Studio for gallery videos only (from the Studio tab, when they aren't from a recorded ride). */
@Composable
fun PhoneStudioScreen(reelId: String?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = com.ridetrack.app.ui.theme.RtDimens.screenPadding),
    ) {
        com.ridetrack.app.ui.components.ScreenHeader("Reel from your videos", onBack = onBack)
        Spacer(Modifier.height(12.dp))
        StudioPanel(PHONE_STUDIO, Modifier.weight(1f), reelId)
    }
}

// ---- 1. Setup ---------------------------------------------------------------------------------

@Composable
private fun Setup(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    var settings by remember { mutableStateOf(false) }
    val c = com.ridetrack.app.ui.appContainer()
    val all by c.reels.reels.collectAsStateWithLifecycle()
    val maker by c.reelMaker.state.collectAsStateWithLifecycle()
    val made = all.filter { it.deletedAt == null && (it.rideId == vm.rideId || (s.phoneOnly && it.rideId == null)) }.mapNotNull { it.idea }.toSet()
    val toMake = s.pieces.count { it.script.title !in made }
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { settings = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Studio settings", style = RtType.button, color = RtColors.TextSecondary)
                }
            }
            ClipStrip(vm, s)
            if (!s.phoneOnly) RideReels(vm.rideId, onSendAll = vm::sendAllToJournal) { vm.openSaved(it) } else PhoneReels { vm.openSaved(it) }
            Suggestions(vm, s, made)
            AskFor(vm, s)
            s.error?.let { ErrorBox(vm, it) }
            Text(
                "Something off? Send Studio details",
                style = RtType.caption,
                color = RtColors.TextTertiary,
                modifier = Modifier.clickable(role = Role.Button) { vm.sendDetails() }.padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            maker.current != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Making \u201c${maker.current}\u201d · ${maker.progress ?: 0}%" + if (maker.queued > 0) " · ${maker.queued} more" else "",
                    style = RtType.caption,
                    color = RtColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text("Stop", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { vm.cancelMakeAll() }.padding(8.dp))
            }
            toMake > 1 -> Button("Make all $toMake · in the background", primary = true) { vm.makeAll() }
            !s.canMake -> Text("Studio needs at least 2 clips. Add videos from your phone above.", style = RtType.caption, color = RtColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
    }
    if (settings) StudioSettings(vm, s) { settings = false }
}

/** Ask for a piece in your words; Gemini writes it and Studio makes it. */
@Composable
private fun AskFor(vm: StudioViewModel, s: StudioState) {
    if (!s.gemini || !s.canMake) return
    var text by remember { mutableStateOf("") }
    Section("Ask for something") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("e.g. a 15 s funny one about the water, or slow cinematic of the flyover", style = RtType.body, color = RtColors.TextTertiary) },
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        if (text.isNotBlank() && s.planning == null) {
            Text("Make it", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.ask(text); text = "" }.padding(vertical = 6.dp))
        }
    }
}

/**
 * Studio settings, the same for every ride: music, series, what's on the video, Gallery.
 * Vibe and length aren't here: Gemini picks them per piece (change them after, in Script or Edit).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StudioSettings(vm: StudioViewModel, s: StudioState, onClose: () -> Unit) {
    val o = s.options
    val pickSong = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.setMusic(uri) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RtColors.SurfaceRaised,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Studio settings", style = RtType.headline, color = RtColors.TextPrimary)
            Text("The same for every ride. Gemini picks each piece's vibe and length; change them after making it.", style = RtType.caption, color = RtColors.TextSecondary)
            Section("Music") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice("Add in Instagram", s.musicUri == null) { vm.setMusic(null) }
                    Choice(s.musicName?.let { "♪ $it" } ?: "Your own song", s.musicUri != null) { pickSong.launch(arrayOf("audio/*")) }
                }
                Text(
                    if (s.musicUri != null) "The song dips while you talk. Instagram may mute songs added outside it; best for WhatsApp or keeping."
                    else "Your voice and the ride's sound. Add a trending song in Instagram: its library is licensed and always current.",
                    style = RtType.caption,
                    color = RtColors.TextTertiary,
                )
            }
            Section("Series") {
                Field(s.series, "Optional, e.g. Evening Ride Diaries") { vm.setSeries(it) }
                Text(
                    if (s.series.isBlank()) "A running name helps followers know what's next." else "Shows as \u201c${s.label}\u201d on the first clip.",
                    style = RtType.caption,
                    color = RtColors.TextTertiary,
                )
            }
            Section("On the video") {
                Toggle("Captions", "What you said, word by word (Gemini reads the clips' sound)", o.captions) { v -> vm.setOptions { it.copy(captions = v) } }
                Toggle("Stats at the end", "When the script ends on them: distance, top speed, time", o.outro) { v -> vm.setOptions { it.copy(outro = v) } }
                Toggle("Loop the ending", "Ends on the opening shot, so it plays on without a jump", o.loopEnd) { v -> vm.setOptions { it.copy(loopEnd = v) } }
                Toggle("Route opening", "A 2 s route sketch before the first clip (off is better for reach)", o.intro) { v -> vm.setOptions { it.copy(intro = v) } }
                Toggle("Map", "The route behind the stats. Off: no map at all", o.map) { v -> vm.setOptions { it.copy(map = v) } }
                Toggle("Keppo Moto mark", "Small, on the stats", o.watermark) { v -> vm.setOptions { it.copy(watermark = v) } }
                val prefs = com.ridetrack.app.ui.appContainer().studio
                var gallery by remember { mutableStateOf(prefs.alsoSaveToGallery) }
                Toggle("Also save to Gallery", "Every piece is kept in Your Reels; this also puts new ones in Movies/Keppo Moto", gallery) { v -> prefs.alsoSaveToGallery = v; gallery = v }
            }
        }
    }
}

/**
 * "Studio picks from these 12 clips": every clip, in filming order, with its length, 🗣 when you
 * talk, and where it's from. Tap to watch; long-press to leave it out (or put it back).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ClipStrip(vm: StudioViewModel, s: StudioState) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> if (uris.isNotEmpty()) vm.addPhoneVideos(uris) }
    var preview by remember { mutableStateOf<StudioSource?>(null) }
    val used = s.sources.size - s.excluded.count { id -> s.sources.any { it.id == id } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.sources.isEmpty()) "Add videos to start" else "Studio picks from these $used clips · ${s.talkingCount} with your voice",
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Add videos",
                style = RtType.button,
                color = RtColors.Primary,
                modifier = Modifier.clickable(role = Role.Button) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }.padding(vertical = 4.dp),
            )
        }
        if (s.sources.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.sources.forEach { src ->
                    val out = src.id in s.excluded
                    Box(
                        Modifier.size(width = 54.dp, height = 96.dp).clip(RoundedCornerShape(8.dp))
                            .combinedClickable(onClickLabel = "Watch", onLongClickLabel = if (out) "Put back" else "Leave out", onLongClick = { vm.toggleExclude(src.id) }) { preview = src },
                    ) {
                        Thumb(src.thumb, Modifier.fillMaxSize())
                        if (out) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)))
                        Text(
                            (if (src.talking) "🗣 " else "") + "${(src.durationMs / 1000).coerceAtLeast(1)}s",
                            style = RtType.caption,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                        )
                        (if (out) "Left out" else src.label)?.let {
                            Text(it, style = RtType.caption, color = Color.White, maxLines = 1, modifier = Modifier.align(Alignment.TopStart).padding(3.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 3.dp))
                        }
                    }
                }
            }
            Text("Tap to watch · long-press to leave one out", style = RtType.caption, color = RtColors.TextTertiary)
        }
    }
    preview?.let { src ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { preview = null }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Player(src.uri, Modifier, height = 480.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(if (src.id in s.excluded) "Put back" else "Leave out", primary = false, modifier = Modifier.weight(1f)) { vm.toggleExclude(src.id); preview = null }
                    Button("Close", primary = true, modifier = Modifier.weight(1f)) { preview = null }
                }
            }
        }
    }
}

/** The clips in the finished Reel, in order, the opening one marked; tap one to jump there. */
@Composable
private fun UsedStrip(s: StudioState, onSeek: (Long) -> Unit) {
    val plan = s.plan ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("IN THIS REEL · ${plan.clips.size} CLIPS", style = RtType.label, color = RtColors.TextSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            plan.segments.forEachIndexed { i, seg ->
                if (seg !is ClipSegment || seg.tail || seg.teaser) return@forEachIndexed
                val label = when {
                    seg.hook -> "Opens"
                    seg.bit.fromRide != null -> seg.bit.fromRide
                    seg.bit.source != null -> "Phone"
                    seg.bit.camera == "back" -> "Road"
                    else -> null
                }
                Box(Modifier.size(width = 44.dp, height = 78.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Jump here") { onSeek(plan.startOf(i)) }) {
                    Thumb(s.thumbs[seg.bit.momentId], Modifier.fillMaxSize())
                    label?.let {
                        Text(it, style = RtType.caption, color = if (seg.hook) RtColors.OnPrimary else Color.White, maxLines = 1, modifier = Modifier.align(Alignment.TopStart).padding(2.dp).clip(RoundedCornerShape(4.dp)).background(if (seg.hook) RtColors.Primary else Color.Black.copy(alpha = 0.55f)).padding(horizontal = 3.dp))
                    }
                }
            }
        }
    }
}

/** Reels made from phone videos only. */
@Composable
private fun PhoneReels(onOpen: (String) -> Unit) {
    val all by com.ridetrack.app.ui.appContainer().reels.reels.collectAsStateWithLifecycle()
    val list = all.filter { it.rideId == null && it.deletedAt == null }
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FROM YOUR VIDEOS", style = RtType.label, color = RtColors.TextSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { p -> ReelTile(p, Modifier.width(96.dp)) { onOpen(p.id) } }
        }
    }
}

/**
 * What Gemini suggests making from this ride: big cards (format, title, vibe, length, why).
 * Tap one to make it. While Gemini reads, placeholders; when it can't help, why, and what to do.
 */
@Composable
private fun Suggestions(vm: StudioViewModel, s: StudioState, made: Set<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SUGGESTED FOR THIS RIDE", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
            if (s.planning == null && s.pieces.isNotEmpty() && s.gemini) {
                Text("Suggest again", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.retryGemini() }.padding(vertical = 4.dp))
            }
        }
        s.content?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
        s.geminiIssue?.let { issue ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Warning.copy(alpha = 0.12f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(issue, style = RtType.body, color = RtColors.TextPrimary)
                com.ridetrack.app.transcribe.rememberGeminiWait().line?.let { Text(it, style = RtType.caption, color = RtColors.Warning) }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Try again", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.retryGemini() }.padding(vertical = 6.dp))
                    if (s.canMake) Text("Make them without Gemini", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.suggestWithoutGemini() }.padding(vertical = 6.dp))
                }
            }
        }
        if (s.planning != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(listOfNotNull(s.planning, s.work.firstOrNull()?.detail).joinToString(" · "), style = RtType.caption, color = RtColors.TextPrimary)
            }
            if (s.pieces.isEmpty()) repeat(2) {
                Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)).background(RtColors.Surface))
            }
        } else if (s.pieces.isEmpty() && s.geminiIssue == null) {
            Text(
                if (s.canMake) "Suggest what to make" else "Add clips to get suggestions",
                style = RtType.button,
                color = if (s.canMake) RtColors.Primary else RtColors.TextTertiary,
                modifier = Modifier.clickable(enabled = s.canMake, role = Role.Button) { vm.retryGemini() }.padding(vertical = 6.dp),
            )
        }
        s.pieces.forEach { pc ->
            val sc = pc.script
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.Surface)
                    .clickable(role = Role.Button, onClickLabel = "Make this") { vm.makePiece(pc.key) }
                    .padding(10.dp),
            ) {
                Box(Modifier.width(90.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised)) {
                    Thumb(pc.opening?.bit?.momentId?.let { s.thumbs[it] }, Modifier.fillMaxSize())
                    Text(Format.clock(pc.plan.totalMs), style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(sc.format.label, style = RtType.caption, color = RtColors.OnPrimary, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(RtColors.Primary).padding(horizontal = 6.dp))
                        Text((sc.vibe ?: s.options.vibe).label, style = RtType.caption, color = RtColors.TextSecondary)
                        if (sc.title in made) Text("· Made", style = RtType.caption, color = RtColors.Primary)
                    }
                    Text(sc.title, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 2)
                    sc.why?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 4) }
                    Text("${pc.plan.clips.size} shots · ${sc.shape.replace('_', ' ')}", style = RtType.caption, color = RtColors.TextTertiary)
                    // The app's checks changed it noticeably: say so, so the length isn't a surprise.
                    if (pc.lengthChanged) Text("Planned ${Format.clock(pc.plannedMs)} · ${Format.clock(pc.plan.totalMs)} after checks", style = RtType.caption, color = RtColors.Warning)
                }
            }
        }
        s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
    }
}

// ---- 2. Working -------------------------------------------------------------------------------

@Composable
private fun Working(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            s.piece?.let { Text("${it.script.format.label} · ${it.script.title}", style = RtType.bodyStrong, color = RtColors.TextPrimary) }
            s.work.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        when (w.state) {
                            1 -> CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            2 -> Icon(Icons.Rounded.Check, contentDescription = "Done", tint = RtColors.Primary, modifier = Modifier.size(18.dp))
                            else -> Box(Modifier.size(8.dp).background(RtColors.TextTertiary, CircleShape))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.label, style = RtType.body, color = if (w.state == 0 || w.state == 3) RtColors.TextTertiary else RtColors.TextPrimary)
                        w.detail?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
                    }
                }
            }
            s.renderProgress?.let { p ->
                LinearProgressIndicator(progress = { p / 100f }, color = RtColors.Primary, trackColor = RtColors.Surface, modifier = Modifier.fillMaxWidth())
                Text("$p%", style = RtType.caption, color = RtColors.TextSecondary)
            }
        }
        val wait = com.ridetrack.app.transcribe.rememberGeminiWait()
        if (s.gemini) wait.line?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = RtType.caption, color = RtColors.Warning, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Spacer(Modifier.height(28.dp))
        if (s.canSkipCaptions) {
            Button("Skip captions", primary = false) { vm.skipCaptions() }
            Spacer(Modifier.height(8.dp))
        }
        Button("Cancel", primary = false) { vm.cancel() }
    }
}

// ---- 3. Ready: watch, change, share -------------------------------------------------------------

@Composable
private fun Ready(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2_200); toast = null } }
    val video = s.video ?: return
    var seek by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Player(android.net.Uri.fromFile(video), Modifier.align(Alignment.CenterHorizontally), seek = seek)
            Text(
                listOfNotNull(s.script?.let { sc -> "${sc.format.label} · “${sc.title}”" }, s.options.vibe.label, Format.clock(s.plan?.totalMs ?: 0), "${s.plan?.clips?.size ?: 0} clips", s.musicName?.let { "♪ $it" }).joinToString(" · "),
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Edit", Icons.Outlined.Edit, Modifier.weight(1f)) { vm.edit() }
                Action("Script", Icons.Outlined.Notes, Modifier.weight(1f)) { vm.scriptView() }
                Action("Remix", Icons.Outlined.Refresh, Modifier.weight(1f)) { vm.remix() }
                Action("Voice-over", Icons.Outlined.Mic, Modifier.weight(1f)) { vm.voice() }
                Action("Cover", Icons.Outlined.Image, Modifier.weight(1f)) { vm.cover() }
            }
            UsedStrip(s) { seek = it to System.nanoTime() }
            ReelMenu(onDuplicate = vm::duplicate, onDelete = vm::delete, onChange = vm::back)
            if (!s.phoneOnly) JournalCard(vm, s)
            s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextPrimary) }
            s.notes.forEach { Text(it, style = RtType.caption, color = RtColors.Warning) }
            if (s.notes.isNotEmpty() && s.gemini) com.ridetrack.app.transcribe.rememberGeminiWait().line?.let { Text(it, style = RtType.caption, color = RtColors.Warning) }
            if (s.notes.isNotEmpty()) ErrorActions(vm)
            if (s.musicUri == null) MusicGuide()
            Coach(s.tips) { vm.act(it) }
            if (s.postCaption.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("CAPTION FOR YOUR POST", style = RtType.label, color = RtColors.TextSecondary)
                    Text(s.postCaption, style = RtType.body, color = RtColors.TextPrimary)
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Caption", s.postCaption))
                            toast = "Caption copied"
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = RtColors.Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy caption", style = RtType.button, color = RtColors.Primary)
                    }
                }
            }
            toast?.let { Text(it, style = RtType.caption, color = RtColors.TextPrimary, modifier = Modifier.align(Alignment.CenterHorizontally)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Share", primary = true, icon = Icons.Outlined.IosShare, modifier = Modifier.weight(1f)) {
                vm.posted()
                ShareImages.share(context, ShareImages.uriFor(context, video), "video/mp4")
            }
            Button("Save", primary = false, icon = Icons.Outlined.Download, modifier = Modifier.weight(1f)) {
                scope.launch {
                    val ok = ShareImages.saveVideo(context, video, "Keppo Reel ${System.currentTimeMillis() / 1000}")
                    if (ok) { haptics.confirm(); vm.posted() }
                    toast = if (ok) "Saved to Movies/Keppo Moto" else "Couldn't save here. Use Share instead."
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** The finished Reel (or a clip), playing on a loop; [seek] (ms, a nonce) jumps to a point. */
@Composable
private fun Player(uri: android.net.Uri, modifier: Modifier, seek: Pair<Long, Long>? = null, height: androidx.compose.ui.unit.Dp = 440.dp) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(seek) { seek?.let { player.seekTo(it.first); player.playWhenReady = true } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.playWhenReady = false }
    Box(
        modifier
            .height(height)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, RtColors.Hairline, RoundedCornerShape(18.dp))
            .background(Color.Black)
            .clickable(role = Role.Button, onClickLabel = "Play or pause") { player.playWhenReady = !player.playWhenReady },
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false } },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The script the Reel was made from, section by section. Change it directly, or tell Studio in
 * your words what to change; then Make it again. Earlier versions stay to go back to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptView(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    val draft = s.draft ?: return
    val byId = s.footage.associateBy { it.momentId }
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${draft.format.label} · ${draft.shape.replace('_', ' ')} · ${Format.clock(vm.draftLengthMs())}",
                style = RtType.bodyStrong,
                color = RtColors.TextPrimary,
            )
            draft.why?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
            draft.sections.forEachIndexed { i, sec ->
                val secs = sec.shots.sumOf { it.durMs } / 1000.0
                val said = sec.shots.flatMap { sh -> byId[sh.clip]?.lines.orEmpty().filter { it.endMs > sh.inMs && it.startMs < sh.outMs } }.joinToString(" ") { it.text }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        sec.shots.firstOrNull()?.let { sh -> Thumb(s.thumbs[sh.clip], Modifier.size(width = 40.dp, height = 54.dp).clip(RoundedCornerShape(6.dp))) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${sec.kind.label} · ${sec.form}" + if (secs > 0) " · " + String.format(Locale.US, "%.1f s", secs) else "",
                                style = RtType.bodyStrong,
                                color = RtColors.TextPrimary,
                            )
                            if (said.isNotBlank()) Text("\u201c$said\u201d", style = RtType.caption, color = RtColors.TextSecondary, maxLines = 3)
                            sec.why?.let { Text(it, style = RtType.caption, color = RtColors.TextTertiary, maxLines = 2) }
                        }
                    }
                    sec.text?.let { Text("Text: $it", style = RtType.caption, color = RtColors.TextSecondary) }
                }
            }
            Section("Vibe") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Vibe.entries.forEach { v -> Choice(v.label, (draft.vibe ?: s.options.vibe) == v) { vm.draftVibe(v) } }
                }
            }
            Section("Opening") {
                Field(s.hookLine, "Hook line on the first frame, e.g. 3 hours in. No break?") { vm.setHookLine(it) }
                Field(s.title, "Title") { vm.setTitle(it) }
            }
            val hooks = remember(s.footage) { ScriptEdits.hookChoices(s.footage) }
            if (hooks.isNotEmpty()) {
                Section("Open with") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        hooks.forEach { (shot, text) -> Choice("\u201c${text.take(28)}\u201d", false) { vm.draftHook(shot, text) } }
                    }
                }
            }
            Section("Tell Studio what to change") {
                OutlinedTextField(
                    value = s.note,
                    onValueChange = vm::setNote,
                    placeholder = { Text("e.g. start with the Tooooo, fewer cuts, make it funnier", style = RtType.body, color = RtColors.TextTertiary) },
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Text(
                    if (s.gemini) "Gemini rewrites the script with your note. Studio also learns from it for the next ones." else "Gemini isn't available in this build: only your direct changes are used.",
                    style = RtType.caption,
                    color = RtColors.TextTertiary,
                )
            }
            if (s.versions.isNotEmpty()) {
                Section("Earlier versions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.versions.forEach { (n, at) -> Choice("v$n · ${Format.timeOfDay(at)}", false) { vm.restoreVersion(n) } }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Edit on the timeline", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.edit() }.padding(vertical = 6.dp))
                val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
                Text("Your style", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { nav(com.ridetrack.app.ui.nav.Routes.STUDIO_STYLE) }.padding(vertical = 6.dp))
            }
            s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Back", primary = false, modifier = Modifier.weight(1f)) { vm.back() }
            Button("Make it again", primary = true, modifier = Modifier.weight(2f)) { vm.applyDraft() }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Duplicate (try another take, keep this one) and Delete (to Recently deleted, 30 days). */
@Composable
private fun ReelMenu(onDuplicate: () -> Unit, onDelete: () -> Unit, onChange: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Other suggestions", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onChange).padding(vertical = 6.dp))
        Text("Duplicate", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onDuplicate).padding(vertical = 6.dp))
        Text("Delete", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { confirm = true }.padding(vertical = 6.dp))
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this Reel?") },
            text = { Text("It moves to Recently deleted on the Studio tab, where you can restore it for 30 days.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = RtColors.Error) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** This ride's saved Reels, newest first; tap one to open it. */
@Composable
private fun RideReels(rideId: String, onSendAll: () -> Unit, onOpen: (String) -> Unit) {
    val all by com.ridetrack.app.ui.appContainer().reels.reels.collectAsStateWithLifecycle()
    val list = all.filter { it.rideId == rideId && it.deletedAt == null }
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("THIS RIDE'S REELS", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
            if (list.size > 1 && list.any { !it.inJournal }) {
                Text("Send all to Journal", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onSendAll).padding(vertical = 4.dp))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { p -> ReelTile(p, Modifier.width(96.dp)) { onOpen(p.id) } }
        }
    }
}

/** A saved Reel as a cover tile with its length and vibe. */
@Composable
internal fun ReelTile(p: ReelProject, modifier: Modifier, onClick: () -> Unit) {
    val store = com.ridetrack.app.ui.appContainer().reels
    Column(modifier.clickable(role = Role.Button, onClickLabel = "Open Reel", onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(RtColors.Surface)) {
            // The cover is redrawn in place: a new update time loads it again.
            androidx.compose.runtime.key(p.updatedAt) { Thumb(store.cover(p.id).takeIf { it.isFile }, Modifier.fillMaxSize(), maxEdge = 480) }
            Text(
                Format.clock(p.durationMs),
                style = RtType.caption,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp),
            )
            p.script?.format?.takeIf { it != PieceFormat.REEL }?.let { f ->
                Text(f.label, style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp))
            }
            if (p.inJournal) {
                Text("Journal", style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp))
            }
        }
        Text(p.title, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(p.vibe.label, style = RtType.caption, color = RtColors.TextTertiary, maxLines = 1)
    }
}

/** Send to Keppo Journal, or (once sent) its cover choice and Remove. */
@Composable
private fun JournalCard(vm: StudioViewModel, s: StudioState) {
    val all by com.ridetrack.app.ui.appContainer().reels.reels.collectAsStateWithLifecycle()
    val p = all.firstOrNull { it.id == s.reelId } ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("KEPPO JOURNAL", style = RtType.label, color = RtColors.TextSecondary)
        if (!p.inJournal) {
            Text("The Reel and its cover go into this ride's Journal entry, and the cover becomes the entry's cover.", style = RtType.caption, color = RtColors.TextSecondary)
            Text("Send to Journal", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.sendToJournal() }.padding(vertical = 6.dp))
        } else {
            Text(if (p.journalCover) "In the Journal · its cover is the entry's cover" else "In the Journal", style = RtType.body, color = RtColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (!p.journalCover) Text("Use as Journal cover", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.useAsJournalCover() }.padding(vertical = 6.dp))
                Text("Remove", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { vm.removeFromJournal() }.padding(vertical = 6.dp))
            }
        }
    }
}

// ---- 6. Cover ---------------------------------------------------------------------------------

/** The Reel's cover: pick the frame, the text on it (or none), or the route card. */
@Composable
private fun CoverEditor(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    val store = com.ridetrack.app.ui.appContainer().reels
    val all by store.reels.collectAsStateWithLifecycle()
    val p = all.firstOrNull { it.id == s.reelId } ?: return
    var line by remember(p.id) { mutableStateOf(p.coverLine ?: p.hookLine.ifBlank { p.title }) }
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).height(380.dp).aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(18.dp)).border(1.dp, RtColors.Hairline, RoundedCornerShape(18.dp)).background(Color.Black),
            ) {
                androidx.compose.runtime.key(s.coverVersion) { Thumb(store.cover(p.id).takeIf { it.isFile }, Modifier.fillMaxSize(), maxEdge = 960) }
                // Instagram's grid shows the middle 3:4 of a Reel's cover.
                val band = Modifier.fillMaxWidth().fillMaxHeight(0.125f).background(Color.Black.copy(alpha = 0.35f))
                Box(band.align(Alignment.TopCenter))
                Box(band.align(Alignment.BottomCenter))
                if (s.coverBusy) CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
            Text("The shaded edges are hidden on your profile grid.", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.CenterHorizontally))
            Section("Frame") {
                if (s.coverFrames.isEmpty()) {
                    Text("Loading frames…", style = RtType.caption, color = RtColors.TextTertiary)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val chosen = p.coverAtMs
                        val near = chosen?.let { at -> s.coverFrames.minByOrNull { kotlin.math.abs(it.first - at) }?.first }
                        s.coverFrames.forEach { (t, f) ->
                            val on = !p.coverRoute && t == near
                            Thumb(
                                f,
                                Modifier.size(width = 54.dp, height = 96.dp).clip(RoundedCornerShape(8.dp))
                                    .border(2.dp, if (on) RtColors.Primary else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button, onClickLabel = "Use this frame") { vm.setCover { it.copy(coverAtMs = t, coverRoute = false) } },
                            )
                        }
                    }
                }
                Toggle("Route card", "Your route on the dark map instead of a frame", p.coverRoute) { on -> vm.setCover { it.copy(coverRoute = on) } }
            }
            Section("Text") {
                Toggle("Text on the cover", "In the ${p.vibe.label} style", p.coverText) { on -> vm.setCover { it.copy(coverText = on) } }
                if (p.coverText) {
                    Field(line, "A few words, e.g. 3 hours in. No break?") { line = it }
                    if (line != (p.coverLine ?: p.hookLine.ifBlank { p.title })) {
                        Text("Update cover", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.setCover { it.copy(coverLine = line.trim()) } }.padding(vertical = 6.dp))
                    }
                }
            }
            s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextPrimary) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Save cover", primary = false, icon = Icons.Outlined.Download, modifier = Modifier.weight(1f)) { vm.saveCover() }
            Button("Done", primary = true, modifier = Modifier.weight(1f)) { vm.back() }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ---- music ------------------------------------------------------------------------------------

/** Once (until dismissed): how to add a trending song in Instagram without drowning the voice. */
@Composable
private fun MusicGuide() {
    val prefs = com.ridetrack.app.ui.appContainer().studio
    var seen by remember { mutableStateOf(prefs.musicGuideSeen) }
    if (seen) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("ADD A TRENDING SONG IN INSTAGRAM", style = RtType.label, color = RtColors.TextSecondary)
        Text("1.  In Instagram's editor tap Music, then the Trending ↗ list, and pick a song.", style = RtType.body, color = RtColors.TextPrimary)
        Text("2.  Tap Volume: Original audio high, the song low, so your voice stays clear.", style = RtType.body, color = RtColors.TextPrimary)
        Text("3.  Trending sounds help the Reel reach more people, and Instagram's songs are licensed, so it won't be muted.", style = RtType.body, color = RtColors.TextPrimary)
        Text(
            "Got it",
            style = RtType.button,
            color = RtColors.Primary,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { prefs.musicGuideSeen = true; seen = true }.padding(vertical = 6.dp),
        )
    }
}

// ---- coach ------------------------------------------------------------------------------------

/** "Make the next one better": a few tips, each with a one-tap fix where there is one. */
@Composable
private fun Coach(tips: List<Tip>?, onAct: (TipAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("MAKE THE NEXT ONE BETTER", style = RtType.label, color = RtColors.TextSecondary)
        if (tips == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Looking at your Reel…", style = RtType.caption, color = RtColors.TextSecondary)
            }
            return@Column
        }
        tips.forEach { t ->
            Row {
                Text("→", style = RtType.body, color = RtColors.Primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t.text, style = RtType.body, color = RtColors.TextPrimary)
                    if (t.nextRide) Text("Added to your shot list for the next ride", style = RtType.caption, color = RtColors.TextTertiary)
                    t.action?.let { a ->
                        Text(
                            when (a) {
                                TipAction.TITLE_ON_HOOK -> "Open on the hook"
                                TipAction.SHORTER -> "Make a 15 s cut"
                                TipAction.VOICE_OVER -> "Record a voice-over"
                                TipAction.OTHER_RIDES -> "Add from another ride"
                                TipAction.STORY -> "Try another story"
                            },
                            style = RtType.button,
                            color = RtColors.Primary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onAct(a) }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---- 5. Voice-over ----------------------------------------------------------------------------

/** Play the Reel muted, tap to talk over it; each take sits where it was recorded. */
@Composable
private fun Voice(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    val context = LocalContext.current
    val video = s.video ?: return
    var allowed by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val player = remember(video) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(video)))
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var position by remember { mutableStateOf(0L) }
    LaunchedEffect(player) { while (true) { position = player.currentPosition; delay(100) } }
    // A take stops when the Reel ends.
    LaunchedEffect(s.recordingAt, position) {
        if (s.recordingAt != null && !player.isPlaying && player.playbackState == Player.STATE_ENDED) vm.stopTake()
    }
    val total = s.plan?.totalMs ?: 0
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).height(380.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(18.dp)).background(Color.Black),
            ) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false } }, update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
                if (s.recordingAt != null) {
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(Color(0xFFFF4D5E), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("Recording · ${Format.clock(position - s.recordingAt)}", style = RtType.caption, color = Color.White)
                    }
                }
            }
            // Where the takes sit in the Reel.
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(RtColors.Surface)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    if (total > 0) {
                        s.takes.forEach { t ->
                            drawRect(Color(0xFFFFD60A), androidx.compose.ui.geometry.Offset(size.width * t.startMs / total, 0f), androidx.compose.ui.geometry.Size(size.width * t.durMs / total, size.height))
                        }
                        drawRect(Color.White, androidx.compose.ui.geometry.Offset(size.width * position / total - 1f, 0f), androidx.compose.ui.geometry.Size(2f, size.height))
                    }
                }
            }
            Text(
                "Play the Reel and tap the mic where you want to talk; tap again to stop. The Reel is muted while you record, and the clips' sound dips under your voice.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            s.takes.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Format.clock(t.startMs)} – ${Format.clock(t.startMs + t.durMs)}" + (t.lines.joinToString(" ") { it.text }.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: ""), style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 2)
                    Text("Delete", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { vm.deleteTake(t) }.padding(8.dp))
                }
            }
            s.error?.let { ErrorBox(vm, it) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button("Back", primary = false, modifier = Modifier.weight(1f)) { vm.stopTake(); vm.back() }
            val rec = s.recordingAt != null
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(if (rec) Color(0xFFFF4D5E) else RtColors.Primary).clickable(role = Role.Button, onClickLabel = if (rec) "Stop recording" else "Record") {
                    when {
                        !allowed -> ask.launch(android.Manifest.permission.RECORD_AUDIO)
                        rec -> { vm.stopTake(); player.pause() }
                        else -> {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            vm.startTake(player.currentPosition)
                            player.play()
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (rec) Icons.Rounded.Stop else Icons.Outlined.Mic, contentDescription = null, tint = if (rec) Color.White else RtColors.OnPrimary, modifier = Modifier.size(28.dp))
            }
            Button("Done", primary = s.takes.isNotEmpty(), modifier = Modifier.weight(1f)) { if (s.takes.isNotEmpty()) vm.finishVoice() else vm.back() }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** A failure, with buttons to send its full technical detail. */
@Composable
private fun ErrorBox(vm: StudioViewModel, message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = RtType.caption, color = RtColors.Error)
        ErrorActions(vm)
    }
}

/** Open the error log, or send everything about this Studio work to the developer. */
@Composable
private fun ErrorActions(vm: StudioViewModel) {
    val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Details",
            style = RtType.button,
            color = RtColors.Primary,
            modifier = Modifier.clickable(role = Role.Button) { nav(com.ridetrack.app.ui.nav.Routes.profilePage(com.ridetrack.app.ui.profile.ProfilePage.ERRORS)) }.padding(vertical = 6.dp),
        )
        Text(
            "Send Studio details",
            style = RtType.button,
            color = RtColors.Primary,
            modifier = Modifier.clickable(role = Role.Button) { vm.sendDetails() }.padding(vertical = 6.dp),
        )
    }
}

// ---- small parts ------------------------------------------------------------------------------

@Composable
internal fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, style = RtType.body, color = RtColors.TextTertiary) },
        textStyle = RtType.body.copy(color = RtColors.TextPrimary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = RtColors.Primary,
            unfocusedBorderColor = RtColors.Hairline,
            cursorColor = RtColors.Primary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!on) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RtType.body, color = RtColors.TextPrimary)
            Text(sub, style = RtType.caption, color = RtColors.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = RtColors.Primary, checkedThumbColor = RtColors.OnPrimary),
        )
    }
}

@Composable
private fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = RtColors.TextPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = RtType.caption, color = RtColors.TextPrimary)
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
internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (label.startsWith("♪")) ({ Icon(Icons.Outlined.MusicNote, null, modifier = Modifier.size(16.dp)) }) else null,
        border = if (selected) BorderStroke(1.dp, RtColors.Primary) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f),
            selectedLabelColor = RtColors.Primary,
            labelColor = RtColors.TextSecondary,
        ),
    )
}

@Composable
internal fun Button(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (primary) RtColors.Primary else RtColors.Surface)
            .border(1.dp, if (primary) Color.Transparent else RtColors.Hairline, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (primary) RtColors.OnPrimary else RtColors.TextPrimary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = RtType.button, color = fg)
    }
}
