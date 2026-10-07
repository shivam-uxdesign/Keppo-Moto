package com.ridetrack.app.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.io.File
import java.util.Locale

private val VibeFonts = mapOf(
    Vibe.HYPE to FontFamily(Font(R.font.anton)),
    Vibe.CINE to FontFamily(Font(R.font.instrument_serif_italic)),
    Vibe.CHILL to FontFamily(Font(R.font.permanent_marker)),
    Vibe.VLOG to FontFamily(Font(R.font.geist_semibold)),
)

/** Share ride › Reel: Keppo Studio turns the ride's clips into a 30–60 s Reel. */
@Composable
fun StudioPanel(rideId: String, modifier: Modifier = Modifier) {
    val vm = appViewModel(key = "studio-$rideId") { StudioViewModel(it, rideId) }
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
            StudioStep.EDIT -> Edit(vm, s, modifier)
            StudioStep.VOICE -> Voice(vm, s, modifier)
        }
    }
}

// ---- 1. Setup ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    val o = s.options
    val pickSong = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.setMusic(uri) }
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "${s.clipCount} clips · ${s.talkingCount} with your voice. Studio picks the best parts and cuts them into a Reel.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Section("Vibe") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Vibe.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { v -> VibeCard(v, o.vibe == v, s.posters.getOrNull(v.ordinal % s.posters.size.coerceAtLeast(1)), Modifier.weight(1f)) { vm.setOptions { it.copy(vibe = v) } } }
                        }
                    }
                }
            }
            Section("Length") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.lengths.forEach { n -> Choice("$n s", o.lengthSec == n) { vm.setOptions { it.copy(lengthSec = n) } } }
                }
                if (s.lengths.size < StudioPlanner.LENGTHS.size) Text("Longer Reels need more clips.", style = RtType.caption, color = RtColors.TextTertiary)
            }
            Section("Music") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice(s.musicName?.let { "♪ $it" } ?: "Your song", s.musicUri != null) { pickSong.launch(arrayOf("audio/*")) }
                    Choice("No music", s.musicUri == null) { vm.setMusic(null) }
                }
                Text(
                    if (s.musicUri != null) "The song dips while you talk." else "Your voice and the ride's sound only. You can add a trending song in Instagram.",
                    style = RtType.caption,
                    color = RtColors.TextTertiary,
                )
            }
            Section("Series") {
                Field(s.series, "Optional, e.g. Evening Ride Diaries") { vm.setSeries(it) }
                Text(
                    if (s.series.isBlank()) "A running name helps followers know what's next. The ride's title shows instead." else "Shows as “${s.label}” on the first clip.",
                    style = RtType.caption,
                    color = RtColors.TextTertiary,
                )
            }
            Section("On the video") {
                Toggle("Route opening", "A 2 s route sketch before the first clip. Off: opens on your best moment (better for reach)", o.intro) { v -> vm.setOptions { it.copy(intro = v) } }
                Toggle("Stats at the end", "Distance, top speed, time, moments, for a second and a half", o.outro) { v -> vm.setOptions { it.copy(outro = v) } }
                Toggle("Loop the ending", "Ends on the opening shot, so the Reel plays on without a jump", o.loopEnd) { v -> vm.setOptions { it.copy(loopEnd = v) } }
                Toggle("Map", "The route behind the stats (and in the route opening). Off: no map at all", o.map) { v -> vm.setOptions { it.copy(map = v) } }
                Toggle("Captions", "What you said, word by word (Gemini reads the clips' sound)", o.captions) { v -> vm.setOptions { it.copy(captions = v) } }
                Toggle("Keppo Moto mark", "Small, on the stats", o.watermark) { v -> vm.setOptions { it.copy(watermark = v) } }
            }
            s.error?.let { Text(it, style = RtType.caption, color = RtColors.Error) }
        }
        Spacer(Modifier.height(12.dp))
        Button("Make my Reel", primary = true) { vm.make() }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VibeCard(v: Vibe, selected: Boolean, poster: File?, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(108.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) RtColors.Primary else RtColors.Hairline, RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Thumb(poster, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.75f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(
                if (v == Vibe.HYPE) v.label.uppercase(Locale.getDefault()) else v.label,
                fontFamily = VibeFonts[v],
                fontSize = if (v == Vibe.CHILL) 17.sp else 20.sp,
                color = Color.White,
            )
            Text(v.blurb, style = RtType.caption, color = Color.White.copy(alpha = 0.8f))
        }
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).background(RtColors.Primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = RtColors.OnPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ---- 2. Working -------------------------------------------------------------------------------

@Composable
private fun Working(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Player(video, Modifier.align(Alignment.CenterHorizontally))
            Text(
                listOfNotNull(s.story?.let { "“$it”" }, s.options.vibe.label, Format.clock(s.plan?.totalMs ?: 0), "${s.plan?.clips?.size ?: 0} clips", s.musicName?.let { "♪ $it" }).joinToString(" · "),
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Remix", Icons.Outlined.Refresh, Modifier.weight(1f)) { vm.remix() }
                Action("Edit", Icons.Outlined.Edit, Modifier.weight(1f)) { vm.edit() }
                Action("Voice-over", Icons.Outlined.Mic, Modifier.weight(1f)) { vm.voice() }
                Action("Change", Icons.Outlined.Tune, Modifier.weight(1f)) { vm.back() }
            }
            s.notes.forEach { Text(it, style = RtType.caption, color = RtColors.Warning) }
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

/** The finished Reel, playing on a loop. */
@Composable
private fun Player(file: File, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.playWhenReady = false }
    Box(
        modifier
            .height(440.dp)
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

// ---- 4. Edit ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Edit(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    val plan = s.plan ?: return
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Section("Opening") {
                Field(s.hookLine, "Hook line on the first frame, e.g. 3 hours in. No break?") { vm.setHookLine(it) }
                Field(s.title, "Title") { vm.setTitle(it) }
            }
            Section("Clips, in order") {
                plan.segments.forEachIndexed { i, seg ->
                    if (seg !is ClipSegment || seg.tail) return@forEachIndexed
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Thumb(s.thumbs[seg.bit.momentId], Modifier.size(width = 40.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (seg.bit.fromRide ?: Format.timeOfDay(seg.bit.atMillis)) + " · ${seg.bit.speedKmh.toInt()} km/h" + if (seg.hook) " · opens the Reel" else "",
                                    style = RtType.body,
                                    color = RtColors.TextPrimary,
                                )
                                Text(String.format(Locale.US, "%.1f s", seg.durMs / 1000.0), style = RtType.caption, color = RtColors.TextSecondary)
                            }
                        }
                        Field(seg.lines.joinToString(" ") { it.text }, if (seg.lines.isEmpty()) "No words · add text for this clip" else "Caption") { vm.setText(i, it) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Choice("Start −½ s", false) { vm.nudge(i, -500, 0) }
                            Choice("Start +½ s", false) { vm.nudge(i, 500, 0) }
                            Choice("End −½ s", false) { vm.nudge(i, 0, -500) }
                            Choice("End +½ s", false) { vm.nudge(i, 0, 500) }
                            Choice("↑ Earlier", false) { vm.move(i, -1) }
                            Choice("↓ Later", false) { vm.move(i, 1) }
                            Choice("Swap", false) { vm.swap(i) }
                            if (plan.clips.size > 1) Choice("Remove", false) { vm.remove(i) }
                        }
                    }
                }
            }
            Section("Add from another ride") {
                val used = plan.clips.map { it.bit.id }.toSet()
                val others = s.otherBits.filter { it.id !in used }
                if (others.isEmpty()) Text("Your other rides' clips show here once Studio has read them.", style = RtType.caption, color = RtColors.TextTertiary)
                others.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { vm.add(b) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(s.thumbs[b.momentId], Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.lines.joinToString(" ") { it.text }.ifBlank { "Riding · ${b.speedKmh.toInt()} km/h" }, style = RtType.body, color = RtColors.TextPrimary, maxLines = 2)
                            Text("${b.fromRide} · ${String.format(Locale.US, "%.1f s", (b.outMs - b.inMs) / 1000.0)}", style = RtType.caption, color = RtColors.TextSecondary)
                        }
                        Text("Add", style = RtType.button, color = RtColors.Primary)
                    }
                }
            }
            s.error?.let { Text(it, style = RtType.caption, color = RtColors.Error) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Back", primary = false, modifier = Modifier.weight(1f)) { vm.back() }
            Button("Make it again · ${Format.clock(plan.totalMs)}", primary = true, modifier = Modifier.weight(2f)) { vm.remake() }
        }
        Spacer(Modifier.height(12.dp))
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
            s.error?.let { Text(it, style = RtType.caption, color = RtColors.Error) }
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

// ---- small parts ------------------------------------------------------------------------------

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
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
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun Button(
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
