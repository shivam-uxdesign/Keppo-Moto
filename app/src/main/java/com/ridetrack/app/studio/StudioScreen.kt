package com.ridetrack.app.studio

import com.ridetrack.app.ui.format.Units
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
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale

internal val VibeFonts = mapOf(
    Vibe.HYPE to FontFamily(Font(R.font.archivo_black)),
    Vibe.CINE to FontFamily(Font(R.font.instrument_serif_italic)),
    Vibe.CHILL to FontFamily(Font(R.font.fredoka_semibold)),
    Vibe.VLOG to FontFamily(Font(R.font.geist_semibold)),
    Vibe.FILM to FontFamily(Font(R.font.ibm_plex_mono_medium)),
    Vibe.EDITORIAL to FontFamily(Font(R.font.dm_serif_display)),
    Vibe.NATIVE to FontFamily.Default,
    Vibe.SPEEDTRAP to FontFamily(Font(R.font.orbitron_black)),
)

/** Share ride › Reel: Keppo Studio turns the ride's clips into a 30–60 s Reel. */
@Composable
fun StudioPanel(rideId: String, modifier: Modifier = Modifier, reelId: String? = null, idea: String? = null, makeNow: Boolean = false, editNow: Boolean = false, ownWay: Boolean = false, onLeave: () -> Unit = {}, onStep: (StudioStep) -> Unit = {}) {
    val vm = appViewModel(key = "studio-$rideId-${reelId.orEmpty()}-${idea ?: ""}-$makeNow-$editNow-$ownWay") { StudioViewModel(it, rideId, reelId, idea, makeNow, editNow, ownWay) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.leave) { if (s.leave) onLeave() }
    LaunchedEffect(s.step) { onStep(s.step) }
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
            // No setup page: ideas are on the Studio feed, and new Reels open in the editor.
            StudioStep.SETUP -> Landing(vm, s, modifier, onLeave)
            StudioStep.WORKING -> Working(vm, s, modifier)
            StudioStep.READY -> Ready(vm, s, modifier)
            StudioStep.EDIT -> androidx.compose.runtime.key(s.piece?.key, s.reelId) { TimelineEditor(vm, s, modifier) }
            StudioStep.VOICE -> Voice(vm, s, modifier)
            StudioStep.COVER -> CoverEditor(vm, s, modifier)
            StudioStep.SCRIPT -> ScriptView(vm, s, modifier)
        }
    }
}

/** Studio for gallery videos only (from the Studio tab, when they aren't from a recorded ride). */
/** A ride's Studio in the editor: one of its ideas, a saved Reel, or its clips your own way (its ideas are on the Studio feed). */
@Composable
fun RideStudioScreen(rideId: String, reelId: String?, onBack: () -> Unit, idea: String? = null, makeNow: Boolean = false, editNow: Boolean = false, ownWay: Boolean = false, onUnlock: () -> Unit = {}) {
    val c = com.ridetrack.app.ui.appContainer()
    // A new Reel needs the free rider's weekly one (Reels already made still open). Decided on the
    // way in, so the Reel being made isn't taken away when it's saved.
    val consent by c.aiConsent.given.collectAsStateWithLifecycle()
    if (!consent) {
        StudioConsentPage("Studio", onBack)
        return
    }
    val locked = remember(rideId, reelId) { reelId == null && !c.studioOpen() }
    if (locked) {
        StudioLockedPage("Studio", onBack, onUnlock)
        return
    }
    var name by remember(rideId) { mutableStateOf("Studio") }
    LaunchedEffect(rideId) { name = c.rides.get(rideId)?.name?.let { "Studio · $it" } ?: "Studio" }
    // The editor has its own top bar: the page's title goes while it's open.
    var step by remember { mutableStateOf(if ((idea != null && !makeNow) || editNow || ownWay) StudioStep.EDIT else StudioStep.SETUP) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = com.ridetrack.app.ui.theme.RtDimens.screenPadding),
    ) {
        if (step != StudioStep.EDIT) {
            com.ridetrack.app.ui.components.ScreenHeader(name, onBack = onBack)
            Spacer(Modifier.height(12.dp))
        }
        StudioPanel(rideId, Modifier.weight(1f), reelId, idea, makeNow, editNow, ownWay, onLeave = onBack, onStep = { step = it })
    }
}

@Composable
fun PhoneStudioScreen(reelId: String?, onBack: () -> Unit, editNow: Boolean = false, onUnlock: () -> Unit = {}) {
    val c = com.ridetrack.app.ui.appContainer()
    val consent by c.aiConsent.given.collectAsStateWithLifecycle()
    if (!consent) {
        StudioConsentPage("Reel from your videos", onBack)
        return
    }
    val locked = remember(reelId) { reelId == null && !c.studioOpen() }
    if (locked) {
        StudioLockedPage("Reel from your videos", onBack, onUnlock)
        return
    }
    // A new Reel opens in the editor (its own top bar); a saved one on its page unless asked to edit.
    var step by remember { mutableStateOf(if (editNow || reelId == null) StudioStep.EDIT else StudioStep.SETUP) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = com.ridetrack.app.ui.theme.RtDimens.screenPadding),
    ) {
        if (step != StudioStep.EDIT) {
            com.ridetrack.app.ui.components.ScreenHeader("Reel from your videos", onBack = onBack)
            Spacer(Modifier.height(12.dp))
        }
        StudioPanel(PHONE_STUDIO, Modifier.weight(1f), reelId, editNow = editNow, onLeave = onBack, onStep = { step = it })
    }
}

/** A Studio page before the rider has agreed to Gemini: its title and back, under the consent card. */
@Composable
private fun StudioConsentPage(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = com.ridetrack.app.ui.theme.RtDimens.screenPadding)) {
            com.ridetrack.app.ui.components.ScreenHeader(title, onBack = onBack)
        }
        com.ridetrack.app.ui.pro.AiConsentCard(onNotNow = onBack, modifier = Modifier.padding(top = 72.dp))
    }
}

/** A Studio page a free rider can't open this week: its title and back, under the lock. */
@Composable
private fun StudioLockedPage(title: String, onBack: () -> Unit, onUnlock: () -> Unit) {
    val c = com.ridetrack.app.ui.appContainer()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = com.ridetrack.app.ui.theme.RtDimens.screenPadding)) {
            com.ridetrack.app.ui.components.ScreenHeader(title, onBack = onBack)
        }
        com.ridetrack.app.ui.pro.StudioLocked(c.freeReels.nextAt(), onUnlock, Modifier.padding(top = 72.dp))
    }
}

/**
 * A ride's Studio between screens: opening what was asked for, or what went wrong (a video that
 * couldn't be made). Back in it with nothing to say, it goes back to the Studio feed.
 */
@Composable
private fun Landing(vm: StudioViewModel, s: StudioState, modifier: Modifier, onLeave: () -> Unit) {
    // A message from opening it ("That idea isn't here any more"), not one left over from the editor.
    val problem = s.error ?: s.toast?.takeIf { s.piece == null && s.reelId == null }
    when {
        !s.opened -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp) }
        problem != null -> Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            if (s.error != null) ErrorBox(vm, s.error!!) else Text(problem, style = RtType.body, color = RtColors.TextSecondary)
            Button("Back to Studio", primary = true, modifier = Modifier.fillMaxWidth(), onClick = onLeave)
        }
        else -> LaunchedEffect(Unit) { onLeave() }
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

/** Where the video is going; it decides its shape. */
private val PLACE_NAMES = mapOf(ExportPreset.INSTAGRAM to "Reel", ExportPreset.SHORTS to "Short", ExportPreset.STORY to "Story", ExportPreset.WHATSAPP to "WhatsApp")

/**
 * Share: the video, where it's going (Reel, Short, Story in parts, WhatsApp), the cover, the
 * caption (copied when you tap Share), subtitles, the hook check and Keppo Journal; Share and Save
 * at the bottom. ‹ Edit goes back to the editor; everything else is under ⋯. Once posted: how it did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ready(vm: StudioViewModel, s: StudioState, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2_200); toast = null } }
    val video = s.video ?: return
    var seek by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var exportOpen by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var place by rememberSaveable { mutableStateOf(ExportPreset.INSTAGRAM) }
    var copied by remember { mutableStateOf(false) }
    val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
    val c = com.ridetrack.app.ui.appContainer()
    val all by c.reels.reels.collectAsStateWithLifecycle()
    val me = all.firstOrNull { it.id == s.reelId }
    if (styleOpen) StylePicker(vm, s, onAll = { nav(com.ridetrack.app.ui.nav.Routes.STYLES) }) { styleOpen = false }
    if (exportOpen) ExportSheet(vm, s) { exportOpen = false }
    var saveStyle by remember { mutableStateOf(false) }
    if (saveStyle) SaveStyleDialog(vm, s) { saveStyle = false }
    // An export made for a place: hand it to the share sheet.
    LaunchedEffect(s.exported) {
        s.exported?.let { files ->
            ShareImages.shareMany(context, files.map { ShareImages.uriFor(context, it) }, "video/mp4")
            vm.exportShared()
        }
    }
    fun copyCaption() {
        if (s.postCaption.isBlank()) return
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Caption", s.postCaption))
        copied = true
    }
    val total = s.plan?.totalMs ?: 0
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Edit", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { vm.edit() }.padding(vertical = 8.dp, horizontal = 4.dp))
                Text("Share", style = RtType.bodyStrong, color = RtColors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                RoundIcon(Icons.Outlined.MoreHoriz, "More") { moreOpen = true }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Player(android.net.Uri.fromFile(video), Modifier.align(Alignment.CenterHorizontally), seek = seek, height = 380.dp)
                Text(
                    listOfNotNull(
                        s.exporting?.let { "Making it for ${PLACE_NAMES[place]} · $it%" } ?: "Ready",
                        Format.clock(total),
                        "${place.spec.height.coerceAtMost(place.spec.width.coerceAtLeast(place.spec.height)).let { if (place.spec.width >= 1080) "1080p" else "720p" }}",
                        s.musicName?.let { "♪ $it" },
                    ).joinToString(" · "),
                    style = RtType.caption,
                    color = RtColors.TextSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                // The title, tap to change it
                var naming by remember { mutableStateOf(false) }
                if (naming) {
                    var t by remember { mutableStateOf(s.title) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = t,
                            onValueChange = { t = it.take(60) },
                            singleLine = true,
                            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                            modifier = Modifier.weight(1f),
                        )
                        Text("Done", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.rename(t); naming = false }.padding(10.dp))
                    }
                } else {
                    Text(
                        s.title.ifBlank { "Untitled" } + "  ✎",
                        style = RtType.headline,
                        color = RtColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change the title") { naming = true },
                    )
                }
                // Where it's going
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportPreset.entries.forEach { p ->
                        val on = p == place
                        val parts = p.splitSec?.let { sec -> Exports.parts(total, sec * 1000L).size.takeIf { it > 1 } }
                        Text(
                            (PLACE_NAMES[p] ?: p.label) + (parts?.let { " · $it parts" } ?: ""),
                            style = RtType.button,
                            color = if (on) RtColors.OnPrimary else RtColors.TextPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) RtColors.Primary else RtColors.SurfaceRaised)
                                .clickable(role = Role.RadioButton) { place = p }.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                // Cover
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClickLabel = "Change the cover") { vm.cover() }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    me?.let { p -> androidx.compose.runtime.key(p.updatedAt) { Thumb(c.reels.cover(p.id).takeIf { it.isFile }, Modifier.size(width = 44.dp, height = 78.dp).clip(RoundedCornerShape(8.dp)), maxEdge = 240) } }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("COVER", style = RtType.label, color = RtColors.TextSecondary)
                        Text("Any frame, with or without the title ›", style = RtType.body, color = RtColors.TextPrimary)
                    }
                }
                // Caption, copied when you share
                if (s.postCaption.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text("CAPTION", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                            Text(if (copied) "Copied ✓" else "Copy", style = RtType.caption, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { copyCaption() })
                        }
                        Text(s.postCaption, style = RtType.body, color = RtColors.TextPrimary)
                        Text("Copied for you when you tap Share: just paste it in Instagram.", style = RtType.caption, color = RtColors.TextTertiary)
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface)) {
                    if (s.gemini) {
                        val busy = s.posting != null
                        ShareRow("Subtitles", if (busy) s.posting else "As said") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Choice("English", false) { if (!busy) vm.translateCaptions("English") }
                                Choice("Hindi", false) { if (!busy) vm.translateCaptions("Hindi") }
                            }
                        }
                        ShareRow("Check the hook", s.hook?.let { if (it.stops) "Stops the scroll ✓" else "Might not stop the scroll" } ?: "Gemini looks at the first 2 s", onClick = { if (!busy) vm.checkHook() }) {
                            s.hook?.let { h ->
                                Text(h.why, style = RtType.caption, color = RtColors.TextSecondary)
                                h.fix?.let { Text("Try: $it", style = RtType.caption, color = RtColors.TextSecondary) }
                            }
                        }
                        if (place == ExportPreset.SHORTS) {
                            ShareRow("Title and description for YouTube", if (s.youtubeTitle.isBlank()) "Gemini writes them" else "Copy", onClick = {
                                if (s.youtubeTitle.isBlank()) { if (!busy) vm.writeYouTube() } else {
                                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("YouTube", (s.youtubeTitle + "\n\n" + s.youtubeDescription).trim()))
                                    toast = "YouTube text copied"
                                }
                            }) {
                                if (s.youtubeTitle.isNotBlank()) Text(s.youtubeTitle, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                            }
                        }
                    }
                    if (!s.phoneOnly && me != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Add to Keppo Journal", style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                            androidx.compose.material3.Switch(checked = me.inJournal, onCheckedChange = { on -> if (on) vm.sendToJournal() else vm.removeFromJournal() })
                        }
                    }
                }
                if (me?.posted == true) HowItDid(vm, s, me, all)
                s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextPrimary) }
                s.notes.forEach { Text(it, style = RtType.caption, color = RtColors.Warning) }
                if (s.notes.isNotEmpty() && s.gemini) com.ridetrack.app.transcribe.rememberGeminiWait().line?.let { Text(it, style = RtType.caption, color = RtColors.Warning) }
                if (s.notes.isNotEmpty()) ErrorActions(vm)
                Coach(s.tips) { vm.act(it) }
                toast?.let { Text(it, style = RtType.caption, color = RtColors.TextPrimary, modifier = Modifier.align(Alignment.CenterHorizontally)) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(if (s.exporting != null) "Making… ${s.exporting}%" else "Share", primary = s.exporting == null, icon = Icons.Outlined.IosShare, modifier = Modifier.weight(2f)) {
                    if (s.exporting != null) return@Button
                    copyCaption()
                    if (place == ExportPreset.INSTAGRAM) {
                        vm.posted()
                        ShareImages.share(context, ShareImages.uriFor(context, video), "video/mp4")
                    } else {
                        vm.export(place, vm.specFor(place), share = true)
                    }
                }
                Button("Save", primary = false, icon = Icons.Outlined.Download, modifier = Modifier.weight(1f)) {
                    if (place != ExportPreset.INSTAGRAM) { vm.export(place, vm.specFor(place)); return@Button }
                    scope.launch {
                        val ok = ShareImages.saveVideo(context, video, "Keppo Reel ${System.currentTimeMillis() / 1000}")
                        if (ok) { haptics.confirm(); vm.posted() }
                        toast = if (ok) "Saved to Movies/Keppo Moto" else "Couldn't save here. Use Share instead."
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        StudioSheet(moreOpen, { moreOpen = false }) {
            SheetRow("The story", "Read it and change any line") { moreOpen = false; vm.scriptView() }
            SheetRow("Make it again, differently", "Remix") { moreOpen = false; vm.remix() }
            SheetRow("Voice-over") { moreOpen = false; vm.voice() }
            SheetRow("Another style") { moreOpen = false; styleOpen = true }
            SheetRow("Save this style") { moreOpen = false; saveStyle = true }
            SheetRow("Quality, 4K and 60 fps") { moreOpen = false; exportOpen = true }
            SheetRow("Duplicate") { moreOpen = false; vm.duplicate() }
            SheetRow("Back to the ideas") { moreOpen = false; vm.back() }
            SheetRow("Delete", danger = true) { moreOpen = false; vm.delete() }
        }
    }
}

/** A row in the Share page's list: its name and state, tap for its action, details under it. */
@Composable
private fun ShareRow(label: String, value: String?, onClick: (() -> Unit)? = null, below: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            value?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
        }
        below()
    }
}

/**
 * After posting: two big numbers to type from Instagram Insights (like Health entries), how your
 * Reels compare by views, and Make one like this.
 */
@Composable
private fun HowItDid(vm: StudioViewModel, s: StudioState, me: ReelProject, all: List<ReelProject>) {
    var typing by remember { mutableStateOf<String?>(null) }
    Text("HOW DID IT DO", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("views" to s.views, "likes" to s.likes).forEach { (what, n) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClickLabel = "Type the $what") { typing = what }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (typing == what) {
                    var v by remember(what) { mutableStateOf(n?.toString().orEmpty()) }
                    NumberField(v, what.replaceFirstChar { it.uppercase() }, Modifier.fillMaxWidth()) { v = it }
                    Text("Done", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                        if (what == "views") vm.setStats(v.toIntOrNull(), s.likes) else vm.setStats(s.views, v.toIntOrNull())
                        typing = null
                    }.padding(vertical = 4.dp))
                } else {
                    Text(n?.let { shortCount(it.toLong()) } ?: "—", style = RtType.metricL, color = RtColors.TextPrimary)
                    Text(what, style = RtType.caption, color = RtColors.TextSecondary)
                }
            }
        }
    }
    Text("Tap a number to type it from Instagram Insights. Studio leans towards what does well.", style = RtType.caption, color = RtColors.TextTertiary)
    val ranked = all.filter { it.deletedAt == null && it.views != null }.sortedByDescending { it.views }.take(5)
    if (ranked.size > 1) {
        Text("YOUR REELS, BY VIEWS", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        val top = (ranked.first().views ?: 1).coerceAtLeast(1)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ranked.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = RtType.caption, color = if (p.id == me.id) RtColors.Primary else RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
                    Box(Modifier.weight(1f).height(10.dp)) {
                        Box(Modifier.fillMaxWidth(((p.views ?: 0).toFloat() / top).coerceIn(0.03f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (p.id == me.id) RtColors.Primary else RtColors.Outline))
                    }
                    Text(shortCount((p.views ?: 0).toLong()), style = RtType.caption, color = RtColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                }
            }
        }
    }
    if (vm.canAsk) Text("Make one like this ›", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
        vm.back()
        vm.ask("Another one like “${me.title}”: the same kind of opening, pace and words")
    }.padding(vertical = 6.dp))
}

@Composable
private fun NumberField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onChange(t.filter(Char::isDigit).take(9)) },
        label = { Text(label, style = RtType.caption) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        textStyle = RtType.body.copy(color = RtColors.TextPrimary),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
        modifier = modifier,
    )
}

/** The finished Reel (or a clip), playing on a loop; [seek] (ms, a nonce) jumps to a point. */
@Composable
internal fun Player(uri: android.net.Uri, modifier: Modifier, seek: Pair<Long, Long>? = null, height: androidx.compose.ui.unit.Dp = 440.dp) {
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
    // A caption being changed: its clip, the line, the words so far.
    var editing by remember { mutableStateOf<Triple<String, CaptionLine, String>?>(null) }
    var addTo by remember { mutableStateOf<Int?>(null) }
    // A line written on the video being changed (its index; one past the end = a new line).
    var onScreenEdit by remember { mutableStateOf<Int?>(null) }
    onScreenEdit?.let { i ->
        val now = draft.onScreen.getOrNull(i).orEmpty()
        CaptionEdit(now, now, title = "Text on the video", onSave = { t ->
            vm.editDraft { sc ->
                val lines = sc.onScreen.toMutableList()
                when {
                    t.isBlank() -> if (i < lines.size) lines.removeAt(i)
                    i < lines.size -> lines[i] = t.trim()
                    else -> lines += t.trim()
                }
                sc.copy(onScreen = lines)
            }
            onScreenEdit = null
        }, onClose = { onScreenEdit = null })
    }
    editing?.let { (clip, line, now) -> CaptionEdit(line.text, now, onSave = { vm.setCaption(clip, line.startMs, it); editing = null }, onClose = { editing = null }) }
    addTo?.let { sec -> ScriptAddClip(vm, s, onPick = { b -> vm.addScriptClip(sec, b); addTo = null }, onClose = { addTo = null }) }
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Reads like a note: the title, the story, then each part.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.scriptFromEditor) "‹ Editor" else "‹ Video", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { vm.back() }.padding(vertical = 6.dp, horizontal = 4.dp))
                Spacer(Modifier.weight(1f))
                Text("${draft.format.label} · ${Format.clock(vm.draftLengthMs())} · ${(draft.vibe ?: s.options.vibe).label}", style = RtType.caption, color = RtColors.TextSecondary)
            }
            Text(s.title.ifBlank { draft.title }, style = RtType.title, color = RtColors.TextPrimary)
            Text(draft.story ?: ScriptWriter.storyOf(draft, s.footage), style = RtType.body, color = RtColors.TextPrimary)
            draft.why?.let { Text(it, style = RtType.caption, color = RtColors.TextTertiary) }
            if (s.hookLine.isNotBlank()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Opens with", style = RtType.caption, color = RtColors.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text("\u201c${s.hookLine}\u201d", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                }
            }
            if (draft.onScreen.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("WRITTEN ON THE VIDEO, IN TURN", style = RtType.label, color = RtColors.TextSecondary)
                    draft.onScreen.forEachIndexed { i, line ->
                        Text(
                            "${i + 1}. $line  ✎",
                            style = RtType.body,
                            color = RtColors.TextPrimary,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change this text") { onScreenEdit = i },
                        )
                    }
                    if (draft.onScreen.size < 3) Text("+ Add a line", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { onScreenEdit = draft.onScreen.size }.padding(vertical = 4.dp))
                }
            }
            draft.sections.forEachIndexed { i, sec ->
                val secs = sec.shots.sumOf { it.durMs } / 1000.0
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        (sec.kind.label + if (secs > 0) " · " + String.format(Locale.US, "%.0f s", secs) else "").uppercase(),
                        style = RtType.label,
                        color = RtColors.TextSecondary,
                    )
                    Text(sec.form, style = RtType.caption, color = RtColors.TextTertiary)
                    sec.why?.let { Text(it, style = RtType.caption, color = RtColors.TextTertiary, maxLines = 2) }
                    sec.text?.let { Text("Text on screen: $it", style = RtType.caption, color = RtColors.TextSecondary) }
                    sec.shots.forEachIndexed { k, sh ->
                        val f = byId[sh.clip]
                        val lines = f?.lines.orEmpty().filter { it.endMs > sh.inMs && it.startMs < sh.outMs }
                        Row(verticalAlignment = Alignment.Top) {
                            Thumb(s.thumbs[sh.clip], Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    listOfNotNull(f?.label?.takeIf { it.isNotBlank() }, f?.look?.takeIf { it != "selfie" }, "${Format.clock(sh.inMs)}–${Format.clock(sh.outMs)}").joinToString(" · "),
                                    style = RtType.caption,
                                    color = RtColors.TextTertiary,
                                )
                                if (lines.isEmpty()) Text("No talking", style = RtType.caption, color = RtColors.TextTertiary)
                                lines.forEach { l ->
                                    val changed = draft.captions[ScriptWriter.captionKey(sh.clip, l.startMs)]
                                    Text("Said: ${l.text}", style = RtType.caption, color = RtColors.TextSecondary)
                                    Text(
                                        when {
                                            changed == null -> "Caption: ${l.text}  ✎"
                                            changed.isBlank() -> "Caption hidden  ✎"
                                            else -> "Caption: $changed  ✎"
                                        },
                                        style = RtType.body,
                                        color = if (changed?.isBlank() == true) RtColors.TextTertiary else RtColors.TextPrimary,
                                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change caption") { editing = Triple(sh.clip, l, changed ?: l.text) },
                                    )
                                }
                            }
                            Text("✕", style = RtType.button, color = RtColors.TextTertiary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Remove this shot") { vm.removeScriptShot(i, k) }.padding(6.dp))
                        }
                    }
                    Text("+ Add a clip", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { addTo = i }.padding(vertical = 4.dp))
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
                if (!s.scriptFromEditor) Text("Edit on the timeline", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.edit() }.padding(vertical = 6.dp))
                val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
                Text("Your style", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { nav(com.ridetrack.app.ui.nav.Routes.STUDIO_STYLE) }.padding(vertical = 6.dp))
            }
            s.toast?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            when {
                s.scriptFromEditor -> "Use this script"
                s.scriptPiece != null -> "Make this"
                else -> "Make it again"
            },
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        ) { vm.applyDraft() }
        if (s.scriptFromEditor) {
            Text("It replaces the edit on the timeline. Undo in the editor takes it back.", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Change a caption's words, or hide it; what was said stays shown above. */
@Composable
private fun CaptionEdit(said: String, now: String, title: String = "Caption", onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(now) }
    val spoken = title == "Caption"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (spoken) Text("Said: $said", style = RtType.caption, color = RtColors.TextSecondary)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Hide it", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { onSave("") }.padding(vertical = 4.dp))
                    if (spoken) Text("As said", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { onSave(said) }.padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(if (spoken) text.ifBlank { said } else text) }) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Clips to add to a script section: this ride's, other rides' and Saved clips; type to find what was said. */
@Composable
private fun ScriptAddClip(vm: StudioViewModel, s: StudioState, onPick: (Bit) -> Unit, onClose: () -> Unit) {
    var find by remember { mutableStateOf("") }
    val all = remember(s.otherBits, s.draft) { vm.scriptAddable() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Add a clip", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            OutlinedTextField(
                value = find,
                onValueChange = { find = it },
                placeholder = { Text("Find what you said…", style = RtType.body, color = RtColors.TextTertiary) },
                singleLine = true,
                textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
            all.filter { find.isBlank() || ClipSearch.matches(find, it.lines.joinToString(" ") { l -> l.text }) }.take(60).forEach { b ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onPick(b) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(s.thumbs[b.momentId], Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.lines.joinToString(" ") { it.text }.ifBlank { "Riding · ${Units.kmhText(b.speedKmh)}" }, style = RtType.body, color = RtColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(b.fromRide ?: "This ride", if (b.camera == "back") "Road" else null, String.format(Locale.US, "%.1f s", (b.outMs - b.inMs) / 1000.0)).joinToString(" · "), style = RtType.caption, color = RtColors.TextSecondary)
                    }
                }
            }
            if (all.isEmpty()) Text("No other clips yet", style = RtType.caption, color = RtColors.TextTertiary)
        }
    }
}

/** A saved Reel as a cover tile with its length and vibe. */
@Composable
internal fun ReelTile(p: ReelProject, modifier: Modifier, clickable: Boolean = true, onClick: () -> Unit) {
    val store = com.ridetrack.app.ui.appContainer().reels
    Column(modifier.then(if (clickable) Modifier.clickable(role = Role.Button, onClickLabel = "Open Reel", onClick = onClick) else Modifier)) {
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
            p.views?.let { v ->
                Text("▶ ${shortCount(v.toLong())}", style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 22.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp))
            }
            if (!p.posted) {
                Text("Draft", style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp))
            }
            if (p.seriesId != null) {
                Text("Ep ${p.episode}", style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(RtColors.Primary.copy(alpha = 0.85f)).padding(horizontal = 4.dp))
            } else if (p.inJournal) {
                Text("Journal", style = RtType.caption, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp))
            }
        }
        Text(p.title, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(listOfNotNull(p.vibe.label, p.views?.let { "%,d views".format(it) }).joinToString(" · "), style = RtType.caption, color = RtColors.TextTertiary, maxLines = 1)
    }
}

/** 12400 → "12.4K", 1200000 → "1.2M". */
internal fun shortCount(v: Long): String = when {
    v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1e6).replace(".0M", "M")
    v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1e3).replace(".0K", "K")
    else -> v.toString()
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
                            drawRect(Color(Tok.YELLOW), androidx.compose.ui.geometry.Offset(size.width * t.startMs / total, 0f), androidx.compose.ui.geometry.Size(size.width * t.durMs / total, size.height))
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

/** Where the video is going: presets, quality and frame rate, and about how big it'll be. */
@Composable
private fun ExportSheet(vm: StudioViewModel, s: StudioState, onClose: () -> Unit) {
    var preset by remember { mutableStateOf(ExportPreset.INSTAGRAM) }
    var k4 by remember { mutableStateOf(false) }
    var fps60 by remember { mutableStateOf(false) }
    val choices by produceState(false to false) { value = vm.exportChoices() }
    val total = s.plan?.totalMs ?: 0
    val spec = preset.spec.let { base ->
        var o = base
        if (k4 && choices.first) o = o.copy(width = 2160, height = 3840, bitrate = base.bitrate * 3)
        if (fps60 && choices.second) o = o.copy(fps = 60, bitrate = (o.bitrate * 1.5).toInt())
        o
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Export", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            ExportPreset.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (p == preset) RtColors.Primary.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable(role = Role.RadioButton) { preset = p }.padding(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = RtType.body, color = RtColors.TextPrimary)
                        Text(p.blurb + if (p.splitSec != null && total > p.splitSec * 1000L) " · ${Exports.parts(total, p.splitSec * 1000L).size} parts" else "", style = RtType.caption, color = RtColors.TextSecondary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("1080p", !k4) { k4 = false }
                Choice("4K", k4 && choices.first) { if (choices.first) k4 = true }
                Spacer(Modifier.width(8.dp))
                Choice("30 fps", !fps60) { fps60 = false }
                Choice("60 fps", fps60 && choices.second) { if (choices.second) fps60 = true }
            }
            if (!choices.first || !choices.second) {
                Text(
                    listOfNotNull("4K needs every clip filmed in 4K".takeIf { !choices.first }, "60 fps needs every clip filmed at 60".takeIf { !choices.second }).joinToString(" · "),
                    style = RtType.caption,
                    color = RtColors.TextTertiary,
                )
            }
            Text("${spec.label} · ${Exports.sizeLabel(Exports.sizeBytes(spec, total))} · saved to Movies/Keppo Moto", style = RtType.caption, color = RtColors.TextSecondary)
            Row {
                Text("Cancel", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button, onClick = onClose).padding(8.dp))
                Spacer(Modifier.weight(1f))
                Text("Export", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.export(preset, spec); onClose() }.padding(8.dp))
            }
            Text("It keeps going if you leave the app.", style = RtType.caption, color = RtColors.TextTertiary)
        }
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
