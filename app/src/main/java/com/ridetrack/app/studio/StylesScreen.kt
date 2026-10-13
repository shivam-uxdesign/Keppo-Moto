package com.ridetrack.app.studio

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.moments.ClipWriter
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A frame of the rider's own best clip, to preview styles on (a starred clip first). */
internal suspend fun bestFrame(c: AppContainer): Bitmap? = withContext(Dispatchers.IO) {
    val clips = c.moments.all().filter { it.kind == MomentKind.CLIP && it.thumb?.isFile == true }
    val pick = clips.filter { it.starred }.maxByOrNull { it.timeMillis } ?: clips.maxByOrNull { it.timeMillis }
    pick?.thumb?.let { ClipWriter.load(it, 960) }
}

/** A style's preview, drawn on [frame]; redrawn when the style changes. */
@Composable
internal fun StylePreview(style: StudioStyle, frame: Bitmap?, modifier: Modifier) {
    val c = appContainer()
    var bmp by remember(style, frame) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(style, frame) { bmp = c.styles.preview(style, frame) }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(RtColors.Surface), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = "${style.name} preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
    }
}

/** The rider's recent clips (starred first) as stills to preview styles on; [k] picks one, round and round. */
internal suspend fun clipFrame(c: AppContainer, k: Int): Bitmap? = withContext(Dispatchers.IO) {
    val clips = c.moments.all().filter { it.kind == MomentKind.CLIP && it.thumb?.isFile == true }
        .sortedWith(compareByDescending<com.ridetrack.app.moments.Moment> { it.starred }.thenByDescending { it.timeMillis }).take(8)
    clips.getOrNull(Math.floorMod(k, clips.size.coerceAtLeast(1)))?.thumb?.let { ClipWriter.load(it, 960) }
}

/** Which styles the library shows. */
private enum class StyleGroup(val label: String) { YOURS("Yours"), FAVOURITES("Favourites"), BUILT_IN("Built in") }

/**
 * Styles, like Procreate's brush library: groups down the left (yours, favourites, built in, the
 * brand kit), the group's styles on the right with a preview on your own clip. Tap to change one,
 * hold for more; swipe a preview to see it on another clip; + makes a new one.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StylesScreen(onBack: () -> Unit) {
    val c = appContainer()
    val styles by c.styles.styles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var shot by remember { mutableIntStateOf(0) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(shot) { frame = clipFrame(c, shot) }
    var editing by remember { mutableStateOf<StudioStyle?>(null) }
    var creating by remember { mutableStateOf(false) }
    var brandOpen by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf(if (styles.any { !it.builtIn }) StyleGroup.YOURS else StyleGroup.BUILT_IN) }
    var held by remember { mutableStateOf<StudioStyle?>(null) }
    editing?.let { st ->
        StyleEditor(st, frame, onOtherClip = { shot++ }, onDone = { editing = null })
        return
    }
    if (brandOpen) {
        BrandKitPage(styles, frame, onBack = { brandOpen = false })
        return
    }
    val shown = when (group) {
        StyleGroup.YOURS -> styles.filter { !it.builtIn }
        StyleGroup.FAVOURITES -> styles.filter { it.favourite }
        StyleGroup.BUILT_IN -> styles.filter { it.builtIn }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp))
                Spacer(Modifier.weight(1f))
                RoundIcon(androidx.compose.material.icons.Icons.Rounded.Add, "New style", primary = true) { creating = true }
            }
            Text("Styles", style = RtType.title, color = RtColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
            Row(Modifier.weight(1f)) {
                // Groups
                Column(Modifier.width(118.dp).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StyleGroup.entries.forEach { g ->
                        val n = when (g) { StyleGroup.YOURS -> styles.count { !it.builtIn }; StyleGroup.FAVOURITES -> styles.count { it.favourite }; StyleGroup.BUILT_IN -> styles.count { it.builtIn } }
                        Text(
                            "${g.label} · $n",
                            style = RtType.body,
                            color = if (g == group) RtColors.TextPrimary else RtColors.TextSecondary,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (g == group) RtColors.SurfaceRaised else Color.Transparent)
                                .clickable(role = Role.Tab) { group = g }.padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Brand kit ›", style = RtType.body, color = RtColors.Primary, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { brandOpen = true }.padding(horizontal = 10.dp, vertical = 10.dp))
                }
                // The group's styles
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                when (group) { StyleGroup.YOURS -> "None of your own yet. Tap + to make one."; StyleGroup.FAVOURITES -> "Tap ☆ on a style to keep it here."; StyleGroup.BUILT_IN -> "" },
                                style = RtType.body,
                                color = RtColors.TextSecondary,
                            )
                        }
                    }
                    items(shown, key = { it.id }) { st ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface)
                                .combinedClickable(onClickLabel = "Change", onLongClickLabel = "More", onLongClick = { held = st }) { editing = st }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StylePreview(
                                st,
                                frame,
                                Modifier.width(64.dp).aspectRatio(9f / 16f).pointerInput(Unit) {
                                    var dx = 0f
                                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (kotlin.math.abs(dx) > 40f) shot += if (dx < 0) 1 else -1 }) { ch, d -> ch.consume(); dx += d }
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(st.name + if (st.favourite) " ★" else "", style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1)
                                val uses = c.styles.uses(st.id)
                                Text(listOfNotNull(st.about.takeIf { it.isNotBlank() }, if (uses > 0) "used $uses×" else null).joinToString(" · ").ifBlank { st.base.label }, style = RtType.caption, color = RtColors.TextTertiary, maxLines = 2)
                            }
                        }
                    }
                }
            }
            Text("Tap to change · hold for more · swipe a preview for another clip", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(vertical = 8.dp))
        }
        NewStyleSheet(creating, styles, onClose = { creating = false }) { made -> creating = false; editing = made }
        StudioSheet(held != null, { held = null }, title = held?.name) {
            held?.let { st ->
                SheetRow(if (st.favourite) "Take out of Favourites" else "Add to Favourites") { c.styles.favourite(st.id); held = null }
                SheetRow("Duplicate") { val copy = st.copy(id = c.styles.newId(), name = "${st.name} (copy)", builtIn = false, favourite = false); c.styles.save(copy); held = null; editing = copy }
                SheetRow("Share as a code", "Another rider pastes it in + › Paste a style code") {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "My Keppo Moto style \"${st.name}\": ${StyleJson.code(st)}")
                    context.startActivity(android.content.Intent.createChooser(send, "Share the style"))
                    held = null
                }
                if (!st.builtIn) SheetRow("Delete", danger = true) { c.styles.delete(st.id); held = null }
            }
        }
    }
}

/** The brand kit: where it sits on a video, the logo, handle, font and colour, and which styles turn it on. */
@Composable
private fun BrandKitPage(styles: List<StudioStyle>, frame: Bitmap?, onBack: () -> Unit) {
    val c = appContainer()
    val brand by c.styles.brand.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) scope.launch { note = if (c.styles.setLogo(uri)) "Logo added" else "Couldn't use that picture" } }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch { note = if (c.styles.setFont(uri)) "Font added" else "That isn't a font Android can use (TTF or OTF)" } }
    var handle by remember(brand.handle) { mutableStateOf(brand.handle) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("‹ Styles", style = RtType.button, color = RtColors.Primary, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp))
        Text("Brand kit", style = RtType.title, color = RtColors.TextPrimary)
        Text("Your logo, handle, font and colour, on any style that turns the brand on.", style = RtType.caption, color = RtColors.TextSecondary)
        val preview = styles.firstOrNull { it.brand } ?: styles.firstOrNull()
        preview?.let { StylePreview(it.copy(brand = true), frame, Modifier.height(260.dp).aspectRatio(9f / 16f).align(Alignment.CenterHorizontally)) }
        Text("It sits low on the left, clear of Instagram's buttons.", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.align(Alignment.CenterHorizontally))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface)) {
            KitRow("Logo", if (brand.logo != null) "Added ›" else "Add ›") { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            if (brand.logo != null) KitRow("Remove the logo", null, danger = true) { scope.launch { c.styles.setLogo(null) } }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("Handle", style = RtType.body, color = RtColors.TextPrimary)
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it.take(30) },
                    placeholder = { Text("@yourname", style = RtType.body, color = RtColors.TextTertiary) },
                    singleLine = true,
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (handle != brand.handle) Text("Save handle", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { c.styles.setBrand(brand.copy(handle = handle.trim())) }.padding(vertical = 6.dp))
            }
            KitRow("Font", if (brand.font != null) "Your font · Aa ›" else "Add a TTF or OTF ›") { pickFont.launch(arrayOf("font/*", "application/octet-stream", "application/x-font-ttf", "application/font-sfnt")) }
            if (brand.font != null) KitRow("Remove the font", null, danger = true) { scope.launch { c.styles.setFont(null) } }
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Colour", style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                BRAND_COLOURS.forEach { col ->
                    Box(
                        Modifier.padding(start = 6.dp).size(26.dp).clip(CircleShape).background(Color(col))
                            .border(2.dp, if (brand.color == col) RtColors.Primary else Color.Transparent, CircleShape)
                            .clickable(role = Role.Button) { c.styles.setBrand(brand.copy(color = if (brand.color == col) null else col)) },
                    )
                }
            }
        }
        note?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
        Text("ON", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface)) {
            styles.forEach { st ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { c.styles.save(st.copy(brand = !st.brand)) }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(st.name, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                    if (st.brand) Text("✓", style = RtType.bodyStrong, color = RtColors.Primary)
                }
            }
        }
        Text("Kept in your Google Drive backup.", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun KitRow(label: String, detail: String?, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp)) {
        Text(label, style = RtType.body, color = if (danger) RtColors.Error else RtColors.TextPrimary, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
    }
}

private val BRAND_COLOURS = listOf(0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFFFF5D8F.toInt(), 0xFF60A5FA.toInt(), 0xFF34D399.toInt(), 0xFFF97316.toInt())

/** + in Styles: describe it in words, like a video you love, from one of your Reels, a code, or a copy of one. */
@Composable
internal fun NewStyleSheet(visible: Boolean, styles: List<StudioStyle>, onClose: () -> Unit, onMade: (StudioStyle) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var way by remember(visible) { mutableStateOf<String?>(null) }
    var words by remember(visible) { mutableStateOf("") }
    var code by remember(visible) { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember(visible) { mutableStateOf<String?>(null) }
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val gemini = remember { StudioGemini(preferred = { c.transcripts.model }, onWorking = { c.transcripts.model = it }) }
    fun made(st: StudioStyle?) {
        busy = null
        if (st == null) { error = "Gemini's answer couldn't be read. Try again or describe it differently."; return }
        c.styles.save(st)
        onMade(st)
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Gemini is watching the video…"
        scope.launch {
            val frames = c.styles.referenceFrames(uri)
            if (frames.isEmpty()) { busy = null; error = "Couldn't read that video"; return@launch }
            made(runCatching { gemini.styleFromFrames(frames, c.styles.newId()) }.onFailure { c.errors.record("Studio styles", "Gemini couldn't describe the video", it) }.getOrNull())
        }
    }
    StudioSheet(visible, onClose, "New style") {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (c.transcripts.available) {
                SheetRow("Describe it in words", "“night, neon, punchy” · Gemini") { way = "words"; error = null }
                if (way == "words") {
                    OutlinedTextField(
                        value = words,
                        onValueChange = { words = it.take(200) },
                        placeholder = { Text("night ride, neon, punchy, big yellow captions", style = RtType.body, color = RtColors.TextTertiary) },
                        textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (words.isNotBlank() && busy == null) Text("Make it", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                        busy = "Gemini is drafting the style…"
                        error = null
                        scope.launch { made(runCatching { gemini.styleFromWords(words, c.styles.newId()) }.onFailure { c.errors.record("Studio styles", "Gemini couldn't draft the style", it) }.getOrNull()) }
                    }.padding(vertical = 6.dp))
                }
                SheetRow("Like a video I love", "Pick any video · Gemini copies the feel") { error = null; if (busy == null) pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            }
            SheetRow("From one of my Reels", "Its captions, cuts, pace and colour") { way = "reel"; error = null }
            if (way == "reel") {
                reels.filter { it.deletedAt == null }.take(10).forEach { p ->
                    Text(p.title, style = RtType.body, color = RtColors.Primary, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        made(Styles.fromReel(p.plan, p.options, c.styles.newId(), p.title.take(30)).copy(about = "from a Reel"))
                    }.padding(vertical = 8.dp, horizontal = 8.dp))
                }
            }
            SheetRow("Paste a style code") { way = "code"; error = null }
            if (way == "code") {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    placeholder = { Text("KEPPO-STYLE:…", style = RtType.body, color = RtColors.TextTertiary) },
                    singleLine = true,
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (code.isNotBlank()) Text("Add it", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) {
                    val st = StyleJson.fromCode(code, c.styles.newId())
                    if (st == null) error = "That isn't a style code" else made(st)
                }.padding(vertical = 6.dp))
            }
            SheetRow("A copy of one", "Then change it") { way = "copy"; error = null }
            if (way == "copy") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    styles.forEach { st -> Choice(st.name, false) { made(st.copy(id = c.styles.newId(), name = "${st.name} (mine)", builtIn = false, favourite = false)) } }
                }
            }
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(it, style = RtType.caption, color = RtColors.TextPrimary) } }
            error?.let { Text(it, style = RtType.caption, color = RtColors.Error) }
        }
    }
}

/**
 * One style in detail, like Procreate's Brush Studio: the live preview on top (Shuffle, Share
 * code, Other clip), the parts down the left, that part's settings beside them.
 */
@Composable
internal fun StyleEditor(start: StudioStyle, frame: Bitmap?, onSaved: (StudioStyle) -> Unit = {}, onOtherClip: (() -> Unit)? = null, onDone: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    var st by remember { mutableStateOf(start) }
    var part by remember { mutableStateOf(StylePart.CAPTIONS) }
    var shuffles by remember { mutableIntStateOf(1) }
    var naming by remember { mutableStateOf(false) }
    val original = remember { c.styles.original(start) }
    androidx.activity.compose.BackHandler { onDone() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button, onClick = onDone).padding(6.dp))
            Spacer(Modifier.weight(1f))
            if (naming) {
                OutlinedTextField(
                    value = st.name,
                    onValueChange = { st = st.copy(name = it.take(40)) },
                    singleLine = true,
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.width(180.dp),
                )
            } else {
                Text(st.name + " ✎", style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Rename") { naming = true }.padding(6.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("Done", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { c.styles.save(st); onSaved(st); onDone() }.padding(6.dp))
        }
        StylePreview(st, frame, Modifier.height(260.dp).aspectRatio(9f / 16f).align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
            Small2("Shuffle") { st = Styles.shuffle(st, shuffles++) }
            Small2("Share code") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "My Keppo Moto style \"${st.name}\": ${StyleJson.code(st)}")
                context.startActivity(android.content.Intent.createChooser(send, "Share the style"))
            }
            onOtherClip?.let { Small2("Other clip ›", onClick = it) }
        }
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(96.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StylePart.entries.forEach { p ->
                    Text(
                        p.label,
                        style = RtType.body,
                        color = if (p == part) RtColors.TextPrimary else RtColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (p == part) RtColors.SurfaceRaised else Color.Transparent)
                            .clickable(role = Role.Tab) { part = p }.padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (part) {
                    StylePart.CAPTIONS -> {
                        val cap = st.captions
                        Chips("Base look", Vibe.entries.map { it.label to (st.base == it) }) { i -> st = st.copy(base = Vibe.entries[i]) }
                        Chips("Captions", CaptionKind.entries.map { it.label to (cap.kind == it) }) { i -> st = st.copy(captions = cap.copy(kind = CaptionKind.entries[i])) }
                        Chips("Font", FontChoice.entries.map { it.label to (cap.font == it) }) { i -> st = st.copy(captions = cap.copy(font = FontChoice.entries[i])) }
                        Slide("Size", cap.size, 0.5f..2f, { "${(it * 100).toInt()}%" }) { st = st.copy(captions = cap.copy(size = it)) }
                        Slide("Height", cap.y ?: -1f, 0.1f..0.9f, { if (it < 0) "Style's" else "${(it * 100).toInt()}% down" }, onReset = { st = st.copy(captions = cap.copy(y = null)) }) { st = st.copy(captions = cap.copy(y = it)) }
                        Colours(if (cap.kind == CaptionKind.LABEL || cap.kind == CaptionKind.CHAT) "Colour (the label or bubble)" else "Colour", cap.color) { st = st.copy(captions = cap.copy(color = it)) }
                        Colours("The word being said", cap.highlight, own = "Yellow") { st = st.copy(captions = cap.copy(highlight = it)) }
                        Toggle("Light up each word", cap.karaoke) { st = st.copy(captions = cap.copy(karaoke = it)) }
                        Toggle("Dark band behind them", cap.box) { st = st.copy(captions = cap.copy(box = it)) }
                    }
                    StylePart.TEXT -> {
                        Chips("Text", TextLook.entries.map { it.label to (st.textLook == it) }) { i -> st = st.copy(textLook = TextLook.entries[i]) }
                        Chips("Font", FontChoice.entries.map { it.label to (st.textFont == it) }) { i -> st = st.copy(textFont = FontChoice.entries[i]) }
                        Colours("Colour", st.textColor) { st = st.copy(textColor = it) }
                        Slide("Size", st.textSize, 0.5f..2f, { "${(it * 100).toInt()}%" }) { st = st.copy(textSize = it) }
                        Slide("Height", st.textY ?: -1f, 0.05f..0.95f, { if (it < 0) "Where each text is" else "${(it * 100).toInt()}% down" }, onReset = { st = st.copy(textY = null) }) { st = st.copy(textY = it) }
                        Chips("Comes in", TextAnim.entries.map { it.label to (st.textIn == it) }) { i -> st = st.copy(textIn = TextAnim.entries[i]) }
                        Chips("Goes out", TextAnim.entries.map { it.label to (st.textOut == it) }) { i -> st = st.copy(textOut = TextAnim.entries[i]) }
                    }
                    StylePart.TRANSITIONS -> {
                        val kinds = TransitionKind.entries.filter { it != TransitionKind.GLITCH }
                        Chips("Transition", kinds.map { it.label to (st.transition.kind == it) }) { i -> st = st.copy(transition = st.transition.copy(kind = kinds[i])) }
                        // One length for every cut: the transition's own, or the rider's (0.1 to 1.5 s).
                        Slide("Length", st.transition.customMs?.toFloat() ?: -1f, 100f..1_500f, { if (it < 0) "Its own" else String.format(java.util.Locale.US, "%.2f s", it / 1000) }, onReset = { st = st.copy(transition = st.transition.copy(customMs = null, length = TransitionLength.NORMAL)) }) {
                            st = st.copy(transition = st.transition.copy(customMs = (kotlin.math.round(it / 50) * 50).toLong()))
                        }
                        Toggle("On every cut (off: only between the script's sections)", st.everyCut) { st = st.copy(everyCut = it) }
                    }
                    StylePart.PACE -> Chips("Riding shots", listOf(0.6f to "Fast", 0.8f to "Quick", 1f to "Normal", 1.25f to "Easy", 1.5f to "Slow").map { it.second to (st.pace == it.first) }) { i ->
                        st = st.copy(pace = listOf(0.6f, 0.8f, 1f, 1.25f, 1.5f)[i])
                    }
                    StylePart.CAMERA -> Toggle("Punch in on the words that matter", st.punchIn) { st = st.copy(punchIn = it) }
                    StylePart.COLOUR -> {
                        Text("Style colour", style = RtType.caption, color = RtColors.TextSecondary)
                        ColourDots(st.accent) { st = st.copy(accent = it) }
                        Toggle("Keep the base look's colour", st.color.look) { st = st.copy(color = st.color.copy(look = it)) }
                        val pct = { v: Float -> (if (v > 0) "+" else "") + "${(v * 100).toInt()}" }
                        Slide("Exposure", st.color.exposure, -1f..1f, pct) { st = st.copy(color = st.color.copy(exposure = it)) }
                        Slide("Contrast", st.color.contrast, -1f..1f, pct) { st = st.copy(color = st.color.copy(contrast = it)) }
                        Slide("Saturation", st.color.saturation, -1f..1f, pct) { st = st.copy(color = st.color.copy(saturation = it)) }
                        Slide("Warmth", st.color.warmth, -1f..1f, pct) { st = st.copy(color = st.color.copy(warmth = it)) }
                    }
                    StylePart.OVERLAYS -> {
                        Toggle("Speed in the corner", st.speedBadge) { st = st.copy(speedBadge = it) }
                        Toggle("Speed gauge sticker", st.speedSticker) { st = st.copy(speedSticker = it) }
                        Toggle("Lean sticker", st.leanSticker) { st = st.copy(leanSticker = it) }
                        Toggle("Route map behind the stats", st.map) { st = st.copy(map = it) }
                        if (st.base != Vibe.NATIVE) Toggle("Route opening card", st.intro) { st = st.copy(intro = it) }
                        Toggle("Stats at the end", st.outro) { st = st.copy(outro = it) }
                        Toggle("Keppo Moto mark", st.watermark) { st = st.copy(watermark = it) }
                        Toggle("My brand kit (logo, handle, font)", st.brand) { st = st.copy(brand = it) }
                    }
                    StylePart.SOUND -> {
                        Toggle("Music and engine dip while I talk", st.duck) { st = st.copy(duck = it) }
                        Toggle("Clean up my voice", st.cleanVoice) { st = st.copy(cleanVoice = it) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Reset ${part.label.lowercase()}", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { st = Styles.reset(st, original, part) }.padding(vertical = 6.dp))
                    if (!start.builtIn) Text("Delete style", style = RtType.button, color = RtColors.Error, modifier = Modifier.clickable(role = Role.Button) { c.styles.delete(start.id); onDone() }.padding(vertical = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Small2(label: String, onClick: () -> Unit) {
    Text(label, style = RtType.button, color = RtColors.TextPrimary, modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

private val SWATCHES: List<Int> = Tok.SWATCHES

/**
 * A slider with its value; [v] below the range means "not set" (shown by [label]), and [onReset]
 * puts it back to not set.
 */
@Composable
internal fun Slide(name: String, v: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, onReset: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    val set = v >= range.start
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(label(v), style = RtType.caption, color = RtColors.TextPrimary)
            if (onReset != null && set) Text("Reset", style = RtType.caption, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClick = onReset).padding(start = 10.dp))
        }
        androidx.compose.material3.Slider(
            value = v.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = RtColors.Primary, activeTrackColor = if (set) RtColors.Primary else RtColors.Hairline, inactiveTrackColor = RtColors.Hairline),
        )
    }
}

/** Any colour: the look's own, a swatch, or anywhere on the rainbow (and how light). */
@Composable
internal fun Colours(name: String, value: Int?, own: String = "Look's own", onChange: (Int?) -> Unit) {
    var hue by remember(value == null) { mutableStateOf(value?.let { FloatArray(3).also { hsv -> android.graphics.Color.colorToHSV(it, hsv) }[0] } ?: 0f) }
    var light by remember(value == null) { mutableStateOf(value?.let { FloatArray(3).also { hsv -> android.graphics.Color.colorToHSV(it, hsv) }[2] } ?: 1f) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = RtType.caption, color = RtColors.TextSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Choice(own, value == null) { onChange(null) }
            SWATCHES.forEach { c ->
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                        .border(if (value == c) 3.dp else 1.dp, if (value == c) RtColors.Primary else RtColors.Hairline, CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Pick this colour") { onChange(c) },
                )
            }
        }
        // The rainbow, then how light: for colours the swatches don't have.
        Box(
            Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.9f, 1f) }))
                .pointerInput(Unit) {
                    detectTapGestures { o -> hue = (o.x / size.width * 360f).coerceIn(0f, 359f); onChange(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.9f, light))) }
                },
        )
        androidx.compose.material3.Slider(
            value = light,
            onValueChange = { light = it; onChange(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.9f, it))) },
            valueRange = 0.2f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = value?.let { Color(it) } ?: RtColors.Primary, activeTrackColor = RtColors.Hairline, inactiveTrackColor = RtColors.Hairline),
        )
    }
}

@Composable
private fun Chips(label: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = RtType.caption, color = RtColors.TextSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, (name, on) -> Choice(name, on) { onPick(i) } }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}


/** "Save this style": the made video's look (captions, colour, cuts, pace, stickers) as a new style, named. */
@Composable
internal fun SaveStyleDialog(vm: StudioViewModel, s: StudioState, onClose: () -> Unit) {
    var name by remember { mutableStateOf(s.title.take(30).ifBlank { "My look" }) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Save this style") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Its captions, text, colour, cuts, pace and stickers, to use on other videos.", style = RtType.caption, color = RtColors.TextSecondary)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text("Name") },
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.saveLookAsStyle(name); onClose() }) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** A full-screen sheet over Studio, for the style editor. */
@Composable
private fun FullSheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(RtColors.Background)) { content() }
    }
}

/**
 * Styles inside Studio: try one on this Reel (or, in the editor, on the edit), change one,
 * make a new one, or keep the Reel's look as a new style.
 */
@Composable
internal fun StylePicker(vm: StudioViewModel, s: StudioState, onAll: () -> Unit, timeline: Boolean = false, onClose: () -> Unit) {
    val c = appContainer()
    val styles by c.styles.styles.collectAsStateWithLifecycle()
    val plan = if (timeline) s.timeline else s.plan
    var frame by remember(plan) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(plan) {
        frame = withContext(Dispatchers.IO) { plan?.clips?.firstOrNull()?.bit?.momentId?.let { s.thumbs[it] }?.let { ClipWriter.load(it, 960) } } ?: bestFrame(c)
    }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<StudioStyle?>(null) }
    var creating by remember { mutableStateOf(false) }
    fun use(st: StudioStyle) = if (timeline) vm.styleOnTimeline(st) else vm.applyStyle(st)
    editing?.let { st ->
        // Saved in the editor: on the edit straight away (on a made Reel, tap it to make it again).
        FullSheet({ editing = null }) { StyleEditor(st, frame, onSaved = { if (timeline) use(it) }, onDone = { editing = null }) }
        return
    }
    if (creating) {
        FullSheet({ creating = false }) { Box(Modifier.fillMaxSize()) { NewStyleSheet(true, styles, onClose = { creating = false }) { made -> creating = false; editing = made } } }
        return
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Style", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(if (timeline) "Tap one to put it on the edit (Undo takes it off)." else "Tap one to make this Reel again in it.", style = RtType.caption, color = RtColors.TextTertiary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                styles.sortedByDescending { it.favourite }.forEach { st ->
                    Column(Modifier.width(110.dp)) {
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { use(st); onClose() }) {
                            StylePreview(st, frame, Modifier.fillMaxWidth().aspectRatio(9f / 16f))
                            Text((if (st.favourite) "★ " else "") + st.name, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                        }
                        Text("Edit", style = RtType.caption, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Edit ${st.name}") { editing = st }.padding(vertical = 4.dp))
                    }
                }
            }
            Text("+ New style", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { creating = true }.padding(vertical = 4.dp))
            LaunchedEffect(Unit) { name = s.title.take(30).ifBlank { "My look" } }
            Text("Keep this Reel's look as a style", style = RtType.caption, color = RtColors.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.weight(1f),
                )
                Text("Save", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { vm.saveLookAsStyle(name); onClose() }.padding(10.dp))
            }
            Row {
                Text("All styles ›", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { onClose(); onAll() }.padding(vertical = 6.dp))
                Spacer(Modifier.weight(1f))
                Text("Close", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button, onClick = onClose).padding(vertical = 6.dp))
            }
        }
    }
}
