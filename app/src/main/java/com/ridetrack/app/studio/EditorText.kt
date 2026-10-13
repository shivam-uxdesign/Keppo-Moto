package com.ridetrack.app.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.R
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import java.util.Locale
import kotlin.math.roundToInt

/** The typeface for a [FontChoice] on screen; null = the look's own. */
internal fun fontFamilyOf(font: FontChoice): FontFamily? = when (font) {
    FontChoice.STYLE -> null
    FontChoice.BOLD -> FontFamily(Font(R.font.anton))
    FontChoice.SERIF -> FontFamily(Font(R.font.instrument_serif_italic))
    FontChoice.CLEAN -> FontFamily(Font(R.font.geist_semibold))
    FontChoice.HAND -> FontFamily(Font(R.font.permanent_marker))
    FontChoice.BRAND -> FontFamily(Font(R.font.geist_medium))
}

/** The typeface a text look draws in when no font is picked. */
internal fun lookFamily(look: TextLook, vibe: Vibe): FontFamily? = when (look) {
    TextLook.HAND -> VibeFonts[Vibe.CHILL]
    TextLook.OUTLINE -> VibeFonts[Vibe.HYPE]
    TextLook.PLAIN, TextLook.BOX -> VibeFonts[Vibe.VLOG]
    TextLook.STYLE -> VibeFonts[vibe]
}

/** Colours for words on the video: the look's own, then a few. */
internal val WORD_COLOURS: List<Int?> = listOf(null, 0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(), 0xFF0B0B0D.toInt(), 0xFFFF5D8F.toInt(), 0xFF60A5FA.toInt(), 0xFF34D399.toInt(), 0xFFFB923C.toInt())

/** A row of colour dots; the first (null) is the look's own. */
@Composable
internal fun ColourDots(selected: Int?, onPick: (Int?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WORD_COLOURS.forEach { c ->
            val on = c == selected
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(c?.let { Color(it) } ?: RtColors.SurfaceRaised)
                    .border(if (on) 3.dp else 1.dp, if (on) RtColors.Primary else Color.White.copy(alpha = 0.5f), CircleShape)
                    .clickable(onClickLabel = if (c == null) "The look's own colour" else "Colour") { onPick(c) },
                contentAlignment = Alignment.Center,
            ) { if (c == null) Text("A", style = RtType.caption, color = Color.White) }
        }
    }
}

/** What the text composer hands back. */
internal data class TextDraft(
    val text: String,
    val look: TextLook,
    val font: FontChoice,
    val color: Int?,
    val size: Float,
    val align: TextAlignment,
    val anim: TextAnim,
)

/**
 * Typing straight on the video, like a Story: the video dims and the words are big. Above the
 * keyboard: the typeface and colour; on top: alignment, the background (look) and how it comes
 * in. The left edge sizes it.
 */
@Composable
internal fun TextComposer(start: TextItem?, vibe: Vibe, onCancel: () -> Unit, onDone: (TextDraft) -> Unit) {
    var text by remember { mutableStateOf(start?.text.orEmpty()) }
    var look by remember { mutableStateOf(start?.look ?: TextLook.STYLE) }
    var font by remember { mutableStateOf(start?.font ?: FontChoice.STYLE) }
    var color by remember { mutableStateOf(start?.color) }
    var size by remember { mutableFloatStateOf(start?.size ?: 1f) }
    var align by remember { mutableStateOf(start?.align ?: TextAlignment.CENTER) }
    var anim by remember { mutableStateOf(start?.animIn ?: TextAnim.FADE) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onCancel)
    val family = fontFamilyOf(font) ?: lookFamily(look, vibe)
    // On a box the colour is the paper's and the words are black.
    val ink = if (look == TextLook.BOX) Color.Black else color?.let { Color(it) } ?: Color.White
    val style = TextStyle(
        fontFamily = family,
        fontSize = (30 * size).sp,
        color = ink,
        textAlign = when (align) { TextAlignment.LEFT -> TextAlign.Start; TextAlignment.CENTER -> TextAlign.Center; TextAlignment.RIGHT -> TextAlign.End },
        shadow = if (look == TextLook.BOX) null else Shadow(Color.Black, blurRadius = 8f),
    )
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(enabled = false) {}.statusBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TopWord("Cancel", onClick = onCancel)
                Spacer(Modifier.weight(1f))
                TopWord(when (align) { TextAlignment.LEFT -> "≡ Left"; TextAlignment.CENTER -> "≡ Centre"; TextAlignment.RIGHT -> "≡ Right" }) {
                    align = TextAlignment.entries[(align.ordinal + 1) % TextAlignment.entries.size]
                }
                TopWord("A ${look.label}") { look = TextLook.entries[(look.ordinal + 1) % TextLook.entries.size] }
                TopWord("✦ ${anim.label}") { anim = TextAnim.entries[(anim.ordinal + 1) % TextAnim.entries.size] }
                Spacer(Modifier.weight(1f))
                TopWord("Done", strong = true) { onDone(TextDraft(text, look, font, color, size, align, anim)) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EdgeSlider(
                    ((size - 0.4f) / 2.6f).coerceIn(0f, 1f),
                    "Size",
                    Modifier.padding(start = 10.dp).fillMaxHeight(0.5f),
                    onChange = { f -> size = 0.4f + f * 2.6f },
                    onEnd = {},
                )
                Box(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = style,
                        cursorBrush = SolidColor(RtColors.Primary),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            .then(if (look == TextLook.BOX) Modifier.background(Color(color ?: android.graphics.Color.WHITE), RoundedCornerShape(10.dp)).padding(8.dp) else Modifier),
                        decorationBox = { inner ->
                            if (text.isEmpty()) Text("Type here", style = style.copy(color = Color.White.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth())
                            inner()
                        },
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FontChoice.entries.forEach { f ->
                        val on = f == font
                        Text(
                            if (f == FontChoice.STYLE) "Style's" else f.label,
                            style = TextStyle(fontFamily = fontFamilyOf(f) ?: lookFamily(look, vibe), fontSize = 16.sp),
                            color = if (on) Color.Black else Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.15f))
                                .clickable { font = f }.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                ColourDots(color) { color = it }
            }
        }
    }
}

@Composable
private fun TopWord(label: String, strong: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = RtType.button,
        color = if (strong) RtColors.Primary else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** Which part of the captions sheet is showing. */
internal enum class CaptionTab { LOOK, WORDS }

/**
 * Captions in one sheet. Look: the kind, size, height, colours, font, words lighting up and a
 * dark band. Words: every line to fix (tap it), a word fixed everywhere, and Read this clip again.
 */
@Composable
internal fun CaptionsSheet(
    vm: StudioViewModel,
    plan: StudioPlan,
    visible: Boolean,
    startTab: CaptionTab,
    editing: Pair<Int, Int>?,
    onDismiss: () -> Unit,
) {
    var tab by remember(visible, startTab) { mutableStateOf(startTab) }
    var open by remember(visible, editing) { mutableStateOf(editing) }
    val look = plan.captionLook
    StudioSheet(visible, onDismiss) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised).padding(3.dp)) {
            CaptionTab.entries.forEach { t ->
                val on = t == tab
                Text(
                    if (t == CaptionTab.LOOK) "Look" else "Words",
                    style = RtType.button,
                    color = RtColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) RtColors.Surface else Color.Transparent).clickable { tab = t }.padding(vertical = 8.dp),
                )
            }
        }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == CaptionTab.LOOK) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionKind.entries.forEach { k ->
                        val on = look.kind == k
                        Column(
                            Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.change("Captions: ${k.label}") { TextTools.captionLook(it) { l -> l.copy(kind = k) } } },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(RtColors.SurfaceRaised).then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (k == CaptionKind.PUNCH) "AA" else "Aa", style = RtType.bodyStrong, color = Color.White) }
                            Text(k.label, style = RtType.caption, color = if (on) RtColors.Primary else RtColors.TextPrimary)
                        }
                    }
                }
                LabelledSlider("Size", "${(look.size * 100).roundToInt()}%", look.size, 0.5f..2f, { v -> vm.drag { TextTools.captionLook(it) { l -> l.copy(size = v) } } }) { vm.endDrag("Captions size") }
                LabelledSlider("Height", look.y?.let { "${(it * 100).roundToInt()}%" } ?: "Style's", look.y ?: 0.68f, 0.1f..0.9f, { v -> vm.drag { TextTools.captionLook(it) { l -> l.copy(y = v) } } }) { vm.endDrag("Captions height") }
                Text("Words", style = RtType.caption, color = RtColors.TextSecondary)
                ColourDots(look.color) { c -> vm.change("Captions colour") { TextTools.captionLook(it) { l -> l.copy(color = c) } } }
                Text("The word being said", style = RtType.caption, color = RtColors.TextSecondary)
                ColourDots(look.highlight) { c -> vm.change("Captions highlight") { TextTools.captionLook(it) { l -> l.copy(highlight = c) } } }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FontChoice.entries.forEach { f ->
                        val on = f == look.font
                        Text(
                            f.label,
                            style = TextStyle(fontFamily = fontFamilyOf(f), fontSize = 15.sp),
                            color = if (on) Color.Black else RtColors.TextPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else RtColors.SurfaceRaised)
                                .clickable { vm.change("Captions font") { TextTools.captionLook(it) { l -> l.copy(font = f) } } }.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                SwitchRow("Light up each word", "As it's said. Off: the whole line at once.", look.karaoke) { on -> vm.change("Captions words") { TextTools.captionLook(it) { l -> l.copy(karaoke = on) } } }
                SwitchRow("Dark band behind", "So they read on any shot", look.box) { on -> vm.change("Captions band") { TextTools.captionLook(it) { l -> l.copy(box = on) } } }
                SwitchRow("English under Hinglish", "A small English line under each Hinglish caption", look.english) { on -> vm.setEnglish(on) }
            } else {
                var from by remember { mutableStateOf("") }
                var to by remember { mutableStateOf("") }
                Text("Fix a word everywhere", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SheetField(from, "Heard as", Modifier.weight(1f)) { from = it }
                    Text("→", style = RtType.body, color = RtColors.TextSecondary)
                    SheetField(to, "Should be", Modifier.weight(1f)) { to = it }
                }
                if (from.isNotBlank()) {
                    Text("Fix everywhere", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable {
                        val n = TextTools.replaceWord(plan, from, to).second
                        if (n > 0) vm.change("Fix “${from.trim()}”") { TextTools.replaceWord(it, from, to).first }
                        vm.note(if (n == 0) "“${from.trim()}” isn't in the captions" else "Fixed $n ${if (n == 1) "time" else "times"}")
                        from = ""; to = ""
                    }.padding(vertical = 4.dp))
                }
                val starts = TimelineEdits.starts(plan)
                plan.segments.forEachIndexed { i, seg ->
                    if (seg !is ClipSegment || seg.tail || seg.lines.isEmpty()) return@forEachIndexed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clip ${i + 1}", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                        Text("Read this clip again", style = RtType.caption, color = RtColors.Primary, modifier = Modifier.clickable { vm.readAgain(seg.bit.momentId) }.padding(4.dp))
                    }
                    seg.lines.forEachIndexed { j, l ->
                        val at = starts[i] + l.startMs
                        if (open == i to j) {
                            var value by remember(i, j) { mutableStateOf(l.text) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SheetField(value, "Caption", Modifier.weight(1f)) { value = it }
                                Text("Done", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable {
                                    vm.change(if (value.isBlank()) "Delete caption" else "Edit caption") { TimelineEdits.caption(it, i, j, value) }
                                    open = null
                                }.padding(8.dp))
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = i to j }.padding(vertical = 6.dp)) {
                                Text(String.format(Locale.US, "%d:%02d", at / 60_000, at / 1000 % 60), style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.width(44.dp))
                                Text(l.text, style = RtType.body, color = RtColors.TextPrimary)
                            }
                        }
                    }
                }
                if (plan.segments.none { it is ClipSegment && !it.tail && it.lines.isNotEmpty() }) {
                    Text("No captions yet. Add one with Add caption, or read a clip again.", style = RtType.body, color = RtColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun LabelledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onEnd: () -> Unit) {
    Column {
        Row {
            Text(label, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(value, style = RtType.caption, color = RtColors.TextSecondary)
        }
        Slider(
            value = v.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange((it * 20).roundToInt() / 20f) },
            onValueChangeFinished = onEnd,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = RtColors.Primary, inactiveTrackColor = RtColors.SurfaceRaised),
        )
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = RtType.body, color = RtColors.TextPrimary)
            Text(detail, style = RtType.caption, color = RtColors.TextSecondary)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun SheetField(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, style = RtType.body, color = RtColors.TextTertiary) },
        singleLine = true,
        textStyle = RtType.body.copy(color = RtColors.TextPrimary),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
        modifier = modifier,
    )
}
