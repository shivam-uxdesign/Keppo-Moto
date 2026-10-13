package com.ridetrack.app.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.share.ShareImages
import com.ridetrack.app.transcribe.rememberGeminiWait
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.nav.LocalNavigate
import com.ridetrack.app.ui.nav.Routes
import com.ridetrack.app.ui.profile.ProfilePage
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

// ---- the making pill ---------------------------------------------------------------------------

/**
 * While something is being made in the background, a pill at the top of Studio says so (like a
 * Live Activity): what, how far, how many wait. When one is ready it says that. Tap for the queue.
 */
@Composable
internal fun MakingPill(modifier: Modifier, onOpen: () -> Unit) {
    val maker by appContainer().reelMaker.state.collectAsStateWithLifecycle()
    var seen by remember { mutableStateOf(setOf<String>()) }
    val fresh = maker.ready.firstOrNull { it.first !in seen }
    // Gemini's free limit used up: said here too, with when it's free again.
    val gemini = rememberGeminiWait().line
    val show = maker.current != null || maker.queued > 0 || fresh != null || gemini != null
    AnimatedVisibility(show, modifier = modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1C1C1F))
                .clickable(role = Role.Button, onClickLabel = "What's being made") { fresh?.let { seen = seen + it.first }; onOpen() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                maker.current != null -> {
                    CircularProgressIndicator(progress = { (maker.progress ?: 0) / 100f }, modifier = Modifier.size(18.dp), color = RtColors.Primary, strokeWidth = 2.dp, trackColor = Color.White.copy(alpha = 0.15f))
                    Spacer(Modifier.width(10.dp))
                    Text("Making “${maker.current}”", style = RtType.body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(listOfNotNull(maker.progress?.let { "$it%" }, maker.queued.takeIf { it > 0 }?.let { "+$it" }).joinToString(" · "), style = RtType.caption, color = Color.White.copy(alpha = 0.7f))
                }
                maker.queued > 0 -> {
                    Text("${maker.queued} waiting to be made", style = RtType.body, color = Color.White, modifier = Modifier.weight(1f))
                }
                fresh == null && gemini != null -> {
                    Text(gemini, style = RtType.caption, color = RtColors.Warning, maxLines = 2, modifier = Modifier.weight(1f))
                }
                fresh != null -> {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(RtColors.Primary), contentAlignment = Alignment.Center) { Text("✓", style = RtType.caption, color = RtColors.OnPrimary) }
                    Spacer(Modifier.width(10.dp))
                    Text("“${fresh.second}” is ready", style = RtType.body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("Open", style = RtType.button, color = RtColors.Primary)
                }
            }
        }
    }
}

/** The queue: the one being made (Stop), what's next (Remove), what's ready (Share, Open), and Gemini's allowance. */
@Composable
internal fun MakingSheet(visible: Boolean, onDismiss: () -> Unit) {
    val c = appContainer()
    val maker by c.reelMaker.state.collectAsStateWithLifecycle()
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val nav = LocalNavigate.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val wait = rememberGeminiWait()
    StudioSheet(visible, onDismiss, "In the background") {
        Text("It keeps going if you leave Studio or the app.", style = RtType.caption, color = RtColors.TextSecondary)
        maker.current?.let { title ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.SurfaceRaised).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Stop", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { maker.currentId?.let { c.reelMaker.cancel(it) } }.padding(4.dp))
                }
                LinearProgressIndicator(progress = { (maker.progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth(), color = RtColors.Primary, trackColor = RtColors.Surface)
                Text("Making · ${maker.progress ?: 0}%", style = RtType.caption, color = RtColors.TextSecondary)
            }
        }
        maker.next.forEach { (id, title) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Next up", style = RtType.caption, color = RtColors.TextTertiary)
                }
                Text("Remove", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { c.reelMaker.cancel(id) }.padding(4.dp))
            }
        }
        maker.ready.forEach { (id, title) ->
            val p = reels.firstOrNull { it.id == id && it.deletedAt == null } ?: return@forEach
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Ready", style = RtType.caption, color = RtColors.Primary)
                }
                Text("Share", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                    c.reels.video(id).takeIf { it.isFile }?.let { ShareImages.shareMany(context, listOf(ShareImages.uriFor(context, it)), "video/mp4") }
                }.padding(horizontal = 8.dp, vertical = 4.dp))
                Text("Open", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                    onDismiss()
                    nav(p.rideId?.let { Routes.savedReel(it, id) } ?: Routes.studioPhone(id))
                }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        if (maker.idle && maker.ready.isEmpty()) Text("Nothing being made right now.", style = RtType.body, color = RtColors.TextSecondary, modifier = Modifier.padding(vertical = 6.dp))
        Text("GEMINI", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        Text(wait.line ?: "Free allowance available. When it runs out, Studio says when it's free again and carries on by itself.", style = RtType.caption, color = RtColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Error log", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { onDismiss(); nav(Routes.profilePage(ProfilePage.ERRORS)) }.padding(vertical = 6.dp))
        }
    }
}

// ---- settings ---------------------------------------------------------------------------------------

/** Studio › Settings, grouped like iOS: after each ride, what every new video starts with, saving, your look, help. */
@Composable
fun StudioSettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val prefs = c.studio
    val nav = LocalNavigate.current
    var o by remember { mutableStateOf(prefs.options) }
    fun set(f: (StudioOptions) -> StudioOptions) { o = f(o); prefs.options = o }
    var auto by remember { mutableStateOf(prefs.autoSuggest) }
    var top by remember { mutableStateOf(prefs.autoMakeTop) }
    var mobile by remember { mutableStateOf(prefs.autoMobileData) }
    var overnight by remember { mutableStateOf(prefs.makeOvernight) }
    var gallery by remember { mutableStateOf(prefs.alsoSaveToGallery) }
    val seriesList by c.series.series.collectAsStateWithLifecycle()
    var music by remember { mutableStateOf(prefs.music) }
    val styles by c.styles.styles.collectAsStateWithLifecycle()
    val pickSong = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { c.appContext.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                c.appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
            }.getOrNull()?.substringBeforeLast('.') ?: "Your song"
            music = uri.toString() to name
            prefs.music = music
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
        Text("‹ Studio", style = RtType.button, color = RtColors.Primary, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp))
        Text("Settings", style = RtType.title, color = RtColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Group("After each ride") {
                SwitchLine("Find ideas", "Ready when the ride ends (above 50% battery), or on Wi-Fi", auto) { auto = it; prefs.autoSuggest = it }
                if (auto) {
                    SwitchLine("Make the top 3", "A long reel, the best moment and your story, waiting in Your Reels", top) { top = it; prefs.autoMakeTop = it }
                    SwitchLine("Use mobile data on low battery", "Otherwise it waits for Wi-Fi", mobile) { mobile = it; prefs.autoMobileData = it }
                }
                SwitchLine("Make every idea overnight", "While the phone charges: they're in Your Reels by morning", overnight) {
                    overnight = it; prefs.makeOvernight = it; OvernightMakeWorker.set(c.appContext, it)
                }
            }
            Group("Every new video starts with") {
                SwitchLine("Captions", "What you said, word by word", o.captions) { v -> set { it.copy(captions = v) } }
                SwitchLine("Stats at the end", "Distance, top speed, time", o.outro) { v -> set { it.copy(outro = v) } }
                SwitchLine("Loop the ending", "Ends on the opening shot, so it plays on", o.loopEnd) { v -> set { it.copy(loopEnd = v) } }
                SwitchLine("Route opening", "A 2 s route sketch first (off is better for reach)", o.intro) { v -> set { it.copy(intro = v) } }
                SwitchLine("Map", "The route behind the stats", o.map) { v -> set { it.copy(map = v) } }
                SwitchLine("Speed in the corner", null, o.speedBadge) { v -> set { it.copy(speedBadge = v) } }
                SwitchLine("Brand kit on the video", "Your logo, handle and font", o.brand) { v -> set { it.copy(brand = v) } }
                SwitchLine("Keppo Moto mark", "Small, on the stats", o.watermark) { v -> set { it.copy(watermark = v) } }
            }
            Group("Series") {
                seriesList.forEach { se -> LinkLine(se.name, if (se.active) "Ep ${se.nextEpisode} next" else "Off") { nav(Routes.series(se.id)) } }
                LinkLine("New series", "A name, what it's about and its look") { nav(Routes.series(Routes.NEW_SERIES)) }
            }
            Group("Music") {
                LinkLine(if (music == null) "Add it in Instagram" else "♪ ${music!!.second}", if (music == null) "Its library is licensed and current" else "Dips while you talk") { pickSong.launch(arrayOf("audio/*")) }
                if (music != null) LinkLine("Use Instagram's music instead", null) { music = null; prefs.music = null }
            }
            Group("Saving") {
                SwitchLine("Also save to Gallery", "New ones also go to Movies/Keppo Moto", gallery) { gallery = it; prefs.alsoSaveToGallery = it }
            }
            Group("Your look") {
                LinkLine("Styles", "${styles.size}") { nav(Routes.STYLES) }
                LinkLine("What Studio learned", "Your habits, from your edits") { nav(Routes.STUDIO_STYLE) }
            }
            Group("Help") {
                LinkLine("Error log", "What went wrong, to send") { nav(Routes.profilePage(ProfilePage.ERRORS)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface), content = content)
}

@Composable
private fun SwitchLine(label: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!on) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = RtType.body, color = RtColors.TextPrimary)
            detail?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkLine(label: String, detail: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1) }
        Text("  ›", style = RtType.body, color = RtColors.TextTertiary)
    }
}
