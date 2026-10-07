package com.ridetrack.app.studio

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.ridetrack.app.ui.components.ScreenHeader
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
    val bmp by produceState<Bitmap?>(null, style, frame) { value = c.styles.preview(style, frame) }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(RtColors.Surface), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = "${style.name} preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
    }
}

/**
 * Styles: every style previewed on your own clip, your brand kit, and making new ones (duplicate,
 * describe it, from a video you like, or a code from another rider). Tap one to change it in detail.
 */
@Composable
fun StylesScreen(onBack: () -> Unit) {
    val c = appContainer()
    val styles by c.styles.styles.collectAsStateWithLifecycle()
    val frame by produceState<Bitmap?>(null) { value = bestFrame(c) }
    var editing by remember { mutableStateOf<StudioStyle?>(null) }
    var creating by remember { mutableStateOf(false) }
    editing?.let { st ->
        StyleEditor(st, frame, onDone = { editing = null })
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
        ScreenHeader(
            "Styles",
            onBack = onBack,
            subtitle = "How your pieces look, sound and move",
            actions = { Text("+ New", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { creating = true }.padding(8.dp)) },
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(2) }) { BrandKitCard() }
            items(styles, key = { it.id }) { st ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Change") { editing = st }) {
                    StylePreview(st, frame, Modifier.fillMaxWidth().aspectRatio(9f / 16f))
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(st.name, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1)
                            Text(if (st.builtIn) "Built in · ${c.styles.uses(st.id)} used" else "Yours · ${c.styles.uses(st.id)} used", style = RtType.caption, color = RtColors.TextTertiary)
                        }
                        Text(
                            if (st.favourite) "★" else "☆",
                            style = RtType.bodyStrong,
                            color = if (st.favourite) Color(0xFFFFD60A) else RtColors.TextTertiary,
                            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Favourite") { c.styles.favourite(st.id) }.padding(6.dp),
                        )
                    }
                }
            }
        }
    }
    if (creating) NewStyleSheet(styles, onClose = { creating = false }) { made -> creating = false; editing = made }
}

/** Your logo, font, handle and colour, used by styles with the brand turned on. */
@Composable
private fun BrandKitCard() {
    val c = appContainer()
    val brand by c.styles.brand.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) scope.launch { note = if (c.styles.setLogo(uri)) "Logo added" else "Couldn't use that picture" } }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch { note = if (c.styles.setFont(uri)) "Font added" else "That isn't a font Android can use (TTF or OTF)" } }
    var handle by remember(brand.handle) { mutableStateOf(brand.handle) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RtColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("BRAND KIT", style = RtType.label, color = RtColors.TextSecondary)
        Text("Your logo, font, handle and colour, on every piece whose style has Brand on.", style = RtType.caption, color = RtColors.TextTertiary)
        OutlinedTextField(
            value = handle,
            onValueChange = { handle = it.take(30) },
            placeholder = { Text("@yourname", style = RtType.body, color = RtColors.TextTertiary) },
            singleLine = true,
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (handle != brand.handle) Text("Save handle", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { c.styles.setBrand(brand.copy(handle = handle.trim())) }.padding(vertical = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Choice(if (brand.logo != null) "Logo ✓" else "Upload logo", brand.logo != null) { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            if (brand.logo != null) Choice("Remove logo", false) { scope.launch { c.styles.setLogo(null) } }
            Choice(if (brand.font != null) "Font ✓" else "Upload font", brand.font != null) { pickFont.launch(arrayOf("font/*", "application/octet-stream", "application/x-font-ttf", "application/font-sfnt")) }
            if (brand.font != null) Choice("Remove font", false) { scope.launch { c.styles.setFont(null) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BRAND_COLOURS.forEach { col ->
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(col))
                        .border(2.dp, if (brand.color == col) RtColors.Primary else Color.Transparent, CircleShape)
                        .clickable(role = Role.Button) { c.styles.setBrand(brand.copy(color = if (brand.color == col) null else col)) },
                )
            }
        }
        note?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
    }
}

private val BRAND_COLOURS = listOf(0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFFFF5D8F.toInt(), 0xFF60A5FA.toInt(), 0xFF34D399.toInt(), 0xFFF97316.toInt())

/** Making a style: duplicate one, describe it (Gemini), from a video you like (Gemini), or a code. */
@Composable
private fun NewStyleSheet(styles: List<StudioStyle>, onClose: () -> Unit, onMade: (StudioStyle) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
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
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("New style", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text("Start from", style = RtType.caption, color = RtColors.TextSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                styles.forEach { st -> Choice(st.name, false) { made(st.copy(id = c.styles.newId(), name = "${st.name} (mine)", builtIn = false, favourite = false)) } }
            }
            if (c.transcripts.available) {
                Text("Describe it", style = RtType.caption, color = RtColors.TextSecondary)
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
                }.padding(vertical = 4.dp))
                Text("From a video you like", style = RtType.caption, color = RtColors.TextSecondary)
                Text("Gemini looks at a few frames of it and makes a style close to its look and pace.", style = RtType.caption, color = RtColors.TextTertiary)
                if (busy == null) Choice("Pick a video", false) { error = null; pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            }
            Text("A code from another rider", style = RtType.caption, color = RtColors.TextSecondary)
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
            }.padding(vertical = 4.dp))
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(it, style = RtType.caption, color = RtColors.TextPrimary) } }
            error?.let { Text(it, style = RtType.caption, color = RtColors.Error) }
        }
    }
}

/**
 * One style in detail: a part at a time (captions, text, transitions, pace, camera, colour,
 * overlays, sound), with the preview updating as it changes. Reset a part, Shuffle, share it.
 */
@Composable
private fun StyleEditor(start: StudioStyle, frame: Bitmap?, onDone: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    var st by remember { mutableStateOf(start) }
    var part by remember { mutableStateOf(StylePart.CAPTIONS) }
    var shuffles by remember { mutableIntStateOf(1) }
    val original = remember { c.styles.original(start) }
    androidx.activity.compose.BackHandler { onDone() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = RtDimens.screenPadding)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button, onClick = onDone).padding(6.dp))
            Spacer(Modifier.weight(1f))
            Text("Shuffle", style = RtType.button, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button) { st = Styles.shuffle(st, shuffles++) }.padding(6.dp))
            Text("Share", style = RtType.button, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button) {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "My Keppo Moto style \"${st.name}\": ${StyleJson.code(st)}")
                context.startActivity(android.content.Intent.createChooser(send, "Share the style"))
            }.padding(6.dp))
            Text("Save", style = RtType.button, color = RtColors.OnPrimary, modifier = Modifier.clip(RoundedCornerShape(50)).background(RtColors.Primary).clickable(role = Role.Button) { c.styles.save(st); onDone() }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
        StylePreview(st, frame, Modifier.height(300.dp).aspectRatio(9f / 16f).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = st.name,
            onValueChange = { st = st.copy(name = it.take(40)) },
            singleLine = true,
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StylePart.entries.forEach { p -> Choice(p.label, part == p) { part = p } }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (part) {
                StylePart.CAPTIONS -> {
                    Chips("Base look", Vibe.entries.map { it.label to (st.base == it) }) { i -> st = st.copy(base = Vibe.entries[i]) }
                    Chips("Captions", CaptionKind.entries.map { it.label to (st.captions.kind == it) }) { i -> st = st.copy(captions = st.captions.copy(kind = CaptionKind.entries[i])) }
                    Chips("Size", listOf(0.8f, 1f, 1.2f, 1.4f).map { "${(it * 100).toInt()}%" to (st.captions.size == it) }) { i -> st = st.copy(captions = st.captions.copy(size = listOf(0.8f, 1f, 1.2f, 1.4f)[i])) }
                    Chips("Height", listOf<Float?>(null, 0.6f, 0.7f, 0.8f).map { (it?.let { h -> "${(h * 100).toInt()}%" } ?: "Style's") to (st.captions.y == it) }) { i -> st = st.copy(captions = st.captions.copy(y = listOf<Float?>(null, 0.6f, 0.7f, 0.8f)[i])) }
                    Toggle("Light up each word", st.captions.karaoke) { st = st.copy(captions = st.captions.copy(karaoke = it)) }
                }
                StylePart.TEXT -> {
                    Chips("Text", TextLook.entries.map { it.label to (st.textLook == it) }) { i -> st = st.copy(textLook = TextLook.entries[i]) }
                    Chips("Colour", STYLE_COLOURS.map { it.first to (st.textColor == it.second) }) { i -> st = st.copy(textColor = STYLE_COLOURS[i].second) }
                    Chips("Comes in", TextAnim.entries.map { it.label to (st.textIn == it) }) { i -> st = st.copy(textIn = TextAnim.entries[i]) }
                    Chips("Goes out", TextAnim.entries.map { it.label to (st.textOut == it) }) { i -> st = st.copy(textOut = TextAnim.entries[i]) }
                }
                StylePart.TRANSITIONS -> {
                    Chips("Transition", TransitionKind.entries.map { it.label to (st.transition.kind == it) }) { i -> st = st.copy(transition = st.transition.copy(kind = TransitionKind.entries[i])) }
                    Chips("Length", TransitionLength.entries.map { it.label to (st.transition.length == it) }) { i -> st = st.copy(transition = st.transition.copy(length = TransitionLength.entries[i])) }
                    Toggle("On every cut (off: only between the script's sections)", st.everyCut) { st = st.copy(everyCut = it) }
                }
                StylePart.PACE -> Chips("Riding shots", listOf(0.6f to "Fast", 0.8f to "Quick", 1f to "Normal", 1.25f to "Easy", 1.5f to "Slow").map { it.second to (st.pace == it.first) }) { i ->
                    st = st.copy(pace = listOf(0.6f, 0.8f, 1f, 1.25f, 1.5f)[i])
                }
                StylePart.CAMERA -> Toggle("Punch in on the words that matter", st.punchIn) { st = st.copy(punchIn = it) }
                StylePart.COLOUR -> {
                    Toggle("Keep the base look's colour", st.color.look) { st = st.copy(color = st.color.copy(look = it)) }
                    Steps("Exposure", st.color.exposure) { st = st.copy(color = st.color.copy(exposure = it)) }
                    Steps("Contrast", st.color.contrast) { st = st.copy(color = st.color.copy(contrast = it)) }
                    Steps("Saturation", st.color.saturation) { st = st.copy(color = st.color.copy(saturation = it)) }
                    Steps("Warmth", st.color.warmth) { st = st.copy(color = st.color.copy(warmth = it)) }
                }
                StylePart.OVERLAYS -> {
                    Toggle("Speed in the corner", st.speedBadge) { st = st.copy(speedBadge = it) }
                    Toggle("Speed gauge sticker", st.speedSticker) { st = st.copy(speedSticker = it) }
                    Toggle("Lean sticker", st.leanSticker) { st = st.copy(leanSticker = it) }
                    Toggle("Route map behind the stats", st.map) { st = st.copy(map = it) }
                    Toggle("Route opening card", st.intro) { st = st.copy(intro = it) }
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

private val STYLE_COLOURS: List<Pair<String, Int?>> = listOf("Own" to null, "White" to 0xFFFFFFFF.toInt(), "Yellow" to 0xFFFFD60A.toInt(), "Pink" to 0xFFFF5D8F.toInt(), "Blue" to 0xFF60A5FA.toInt(), "Black" to 0xFF0B0B0D.toInt())

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

@Composable
private fun Steps(label: String, v: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
        Text("−", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button) { onChange(((v - 0.1f) * 10).let { kotlin.math.round(it) } / 10f) }.padding(horizontal = 12.dp, vertical = 4.dp))
        Text("${(v * 100).toInt()}", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.width(36.dp))
        Text("+", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.clickable(role = Role.Button) { onChange(((v + 0.1f) * 10).let { kotlin.math.round(it) } / 10f) }.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/** On a finished Reel: try a style on it, or keep the Reel's look as a new style. */
@Composable
internal fun StylePicker(vm: StudioViewModel, s: StudioState, onAll: () -> Unit, onClose: () -> Unit) {
    val c = appContainer()
    val styles by c.styles.styles.collectAsStateWithLifecycle()
    val frame by produceState<Bitmap?>(null, s.plan) {
        value = withContext(Dispatchers.IO) { s.plan?.clips?.firstOrNull()?.bit?.momentId?.let { s.thumbs[it] }?.let { ClipWriter.load(it, 960) } } ?: bestFrame(c)
    }
    var name by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.SurfaceRaised).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Style", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text("Tap one to make this Reel again in it.", style = RtType.caption, color = RtColors.TextTertiary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                styles.sortedByDescending { it.favourite }.forEach { st ->
                    Column(Modifier.width(110.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { vm.applyStyle(st); onClose() }) {
                        StylePreview(st, frame, Modifier.fillMaxWidth().aspectRatio(9f / 16f))
                        Text((if (st.favourite) "★ " else "") + st.name, style = RtType.caption, color = RtColors.TextPrimary, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
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
