package com.ridetrack.app.studio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * A style: the full recipe for how a piece looks, sounds and moves. [base] is the built-in look
 * it grows from (fonts, graphics, the camera's moves); everything else is on top.
 */
data class StudioStyle(
    val id: String,
    val name: String,
    val base: Vibe,
    val builtIn: Boolean = false,
    val favourite: Boolean = false,
    /** A line saying what it's like ("night ride, neon, punchy"). */
    val about: String = "",
    // Captions and text presets
    val captions: CaptionLook = CaptionLook(),
    val textLook: TextLook = TextLook.STYLE,
    val textColor: Int? = null,
    val textIn: TextAnim = TextAnim.FADE,
    val textOut: TextAnim = TextAnim.FADE,
    // Transitions: which, and on every cut or only between sections
    val transition: Transition = Transition(),
    val everyCut: Boolean = false,
    // Pace: riding shots' length (0.6 fast … 1.5 slow)
    val pace: Float = 1f,
    // Camera: a push-in on the words that matter
    val punchIn: Boolean = false,
    // Colour on top of the base's look (or instead of it)
    val color: ClipColor = ClipColor(),
    // Ride overlays
    val speedBadge: Boolean = true,
    val speedSticker: Boolean = false,
    val leanSticker: Boolean = false,
    val map: Boolean = true,
    // Intro and outro cards
    val intro: Boolean = false,
    val outro: Boolean = true,
    // Sound
    val duck: Boolean = true,
    val cleanVoice: Boolean = false,
    // Marks
    val watermark: Boolean = true,
    /** Your brand kit (logo, handle, font) on the piece. */
    val brand: Boolean = false,
) {
    /** In words, for Gemini choosing among the rider's styles. */
    fun describe(): String = buildString {
        append("\"$name\" (${base.label.lowercase()}")
        if (about.isNotBlank()) append("; $about")
        append("; ${captions.kind.label.lowercase()} captions")
        if (transition.kind != TransitionKind.STYLE) append("; ${transition.kind.label.lowercase()} cuts")
        if (pace < 0.9f) append("; fast") else if (pace > 1.15f) append("; slow")
        append(")")
    }
}

/** The rider's own fonts, colours, logo and handle, used by styles that turn the brand on. */
data class BrandKit(
    val handle: String = "",
    /** Paths of the uploaded logo (PNG) and font (TTF or OTF), kept in the app's files. */
    val logo: String? = null,
    val font: String? = null,
    val color: Int? = null,
) {
    val empty: Boolean get() = handle.isBlank() && logo == null && font == null && color == null
}

/** Built-in styles, applying a style to an edit, saving a Reel's look as one, and Shuffle. Pure, unit-tested. */
object Styles {
    val builtIns: List<StudioStyle> = listOf(
        StudioStyle("hype", "Hype", Vibe.HYPE, builtIn = true, about = "fast cuts, bold words", pace = 0.8f, punchIn = true),
        StudioStyle("cine", "Cinematic", Vibe.CINE, builtIn = true, about = "slow, wide, film look", pace = 1.3f, speedBadge = true),
        StudioStyle("chill", "Chill", Vibe.CHILL, builtIn = true, about = "easy pace, warm", pace = 1.15f),
        StudioStyle("vlog", "Vlog", Vibe.VLOG, builtIn = true, about = "your voice leads"),
    )

    /** The options a style sets (look, cards, overlays, marks). */
    fun options(o: StudioOptions, s: StudioStyle): StudioOptions =
        o.copy(vibe = s.base, intro = s.intro, outro = s.outro, map = s.map, watermark = s.watermark, speedBadge = s.speedBadge, brand = s.brand)

    /**
     * [plan] in style [s]: its captions, text, transitions, pace, camera, colour, stickers and
     * sound. [newId] names new stickers.
     */
    fun apply(plan: StudioPlan, s: StudioStyle, newId: () -> String): StudioPlan {
        var p = plan.copy(vibe = s.base, captionLook = s.captions, mix = plan.mix.copy(duck = s.duck, cleanVoice = s.cleanVoice))
        // Text presets onto the timeline's text.
        p = p.copy(texts = p.texts.map { it.copy(look = s.textLook, color = s.textColor, animIn = s.textIn, animOut = s.textOut) })
        // Pace: riding shots (no words) longer or shorter, within their clip.
        if (s.pace != 1f) {
            p.segments.indices.forEach { i ->
                val seg = p.segments[i] as? ClipSegment ?: return@forEach
                if (seg.tail || seg.still != null || seg.lines.isNotEmpty()) return@forEach
                p = TimelineEdits.trimEnd(p, i, kotlin.math.round(seg.durMs * s.pace - seg.durMs).toLong())
            }
        }
        // Transitions: on every cut, or only between the script's sections (plain cuts inside them).
        p = p.copy(
            segments = p.segments.mapIndexed { i, seg ->
                if (i == 0 || seg !is ClipSegment || seg.tail || seg.still != null) return@mapIndexed seg
                val prev = p.segments[i - 1] as? ClipSegment
                val between = prev == null || prev.section < 0 || prev.section != seg.section
                seg.copy(transition = if (s.everyCut || between) s.transition.takeIf { it.kind != TransitionKind.STYLE || s.everyCut } else null)
            },
        )
        // Colour and the camera.
        p = p.copy(segments = p.segments.map { seg -> if (seg is ClipSegment && !seg.tail) seg.copy(color = s.color) else seg })
        if (s.punchIn) p.segments.indices.forEach { i -> (p.segments[i] as? ClipSegment)?.takeIf { it.frame.isEmpty() && it.lines.isNotEmpty() && !it.tail }?.let { p = ClipTools.punchIn(p, i) } }
        // Live ride stickers, once.
        var stickers = p.stickers.filter { (it.kind != StickerKind.SPEED || s.speedSticker) && (it.kind != StickerKind.LEAN || s.leanSticker) }
        p = p.copy(stickers = stickers)
        if (s.speedSticker && stickers.none { it.kind == StickerKind.SPEED }) p = TextTools.addSticker(p, StickerKind.SPEED, 0, newId())
        stickers = p.stickers
        if (s.leanSticker && stickers.none { it.kind == StickerKind.LEAN }) p = TextTools.addSticker(p, StickerKind.LEAN, 0, newId())
        return TimelineEdits.normalize(p)
    }

    /** "Save this Reel's look as a style": what [plan] and [o] do, as a new style called [name]. */
    fun fromReel(plan: StudioPlan, o: StudioOptions, id: String, name: String): StudioStyle {
        val clips = plan.clips
        val transitions = clips.drop(1).mapNotNull { it.transition }
        val common = transitions.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: Transition()
        val text = plan.texts.firstOrNull()
        return StudioStyle(
            id = id, name = name, base = plan.vibe,
            captions = plan.captionLook,
            textLook = text?.look ?: TextLook.STYLE, textColor = text?.color, textIn = text?.animIn ?: TextAnim.FADE, textOut = text?.animOut ?: TextAnim.FADE,
            transition = common, everyCut = clips.size > 2 && transitions.size == clips.size - 1 && transitions.all { it == common },
            punchIn = clips.any { c -> c.frame.size >= 3 && c.frame.any { it.zoom > 1.05f } },
            color = clips.map { it.color }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ClipColor(),
            speedBadge = o.speedBadge, speedSticker = plan.stickers.any { it.kind == StickerKind.SPEED }, leanSticker = plan.stickers.any { it.kind == StickerKind.LEAN },
            map = o.map, intro = o.intro, outro = o.outro, duck = plan.mix.duck, cleanVoice = plan.mix.cleanVoice, watermark = o.watermark, brand = o.brand,
        )
    }

    /** Shuffle: a variation within the style (another transition, caption look, pace or colour). */
    fun shuffle(s: StudioStyle, seed: Int): StudioStyle {
        val r = Random(seed)
        val kinds = listOf(TransitionKind.STYLE, TransitionKind.WHIP, TransitionKind.ZOOM, TransitionKind.FLASH, TransitionKind.FADE, TransitionKind.GLITCH)
        val captions = listOf(CaptionKind.STYLE, CaptionKind.PLAIN, CaptionKind.PUNCH, CaptionKind.SERIF, CaptionKind.LABEL, CaptionKind.CHAT)
        return when (r.nextInt(4)) {
            0 -> s.copy(transition = s.transition.copy(kind = kinds.filter { it != s.transition.kind }.random(r)))
            1 -> s.copy(captions = s.captions.copy(kind = captions.filter { it != s.captions.kind }.random(r)))
            2 -> s.copy(pace = (s.pace + listOf(-0.2f, 0.2f).random(r)).coerceIn(0.6f, 1.5f))
            else -> s.copy(color = s.color.copy(warmth = (s.color.warmth + listOf(-0.2f, 0.2f).random(r)).coerceIn(-1f, 1f), saturation = (s.color.saturation + listOf(-0.15f, 0.15f).random(r)).coerceIn(-1f, 1f)))
        }
    }

    /** One part of [s] back to how [original] had it. */
    fun reset(s: StudioStyle, original: StudioStyle, part: StylePart): StudioStyle = when (part) {
        StylePart.CAPTIONS -> s.copy(captions = original.captions)
        StylePart.TEXT -> s.copy(textLook = original.textLook, textColor = original.textColor, textIn = original.textIn, textOut = original.textOut)
        StylePart.TRANSITIONS -> s.copy(transition = original.transition, everyCut = original.everyCut)
        StylePart.PACE -> s.copy(pace = original.pace)
        StylePart.CAMERA -> s.copy(punchIn = original.punchIn)
        StylePart.COLOUR -> s.copy(color = original.color)
        StylePart.OVERLAYS -> s.copy(speedBadge = original.speedBadge, speedSticker = original.speedSticker, leanSticker = original.leanSticker, map = original.map, intro = original.intro, outro = original.outro, watermark = original.watermark, brand = original.brand)
        StylePart.SOUND -> s.copy(duck = original.duck, cleanVoice = original.cleanVoice)
    }
}

/** The parts of a style, one screen each in the style editor. */
enum class StylePart(val label: String) { CAPTIONS("Captions"), TEXT("Text"), TRANSITIONS("Transitions"), PACE("Pace"), CAMERA("Camera"), COLOUR("Colour"), OVERLAYS("Overlays"), SOUND("Sound") }

/** Styles ↔ JSON (saved, backed up, shared as a code). Pure, unit-tested. */
object StyleJson {
    private const val CODE = "KEPPO-STYLE:"

    fun write(s: StudioStyle): JSONObject = JSONObject()
        .put("id", s.id).put("name", s.name).put("base", s.base.name).put("fav", s.favourite).put("about", s.about)
        .put("captions", JSONObject().put("kind", s.captions.kind.name).put("size", s.captions.size.toDouble()).put("y", s.captions.y?.toDouble() ?: JSONObject.NULL).put("karaoke", s.captions.karaoke))
        .put("textLook", s.textLook.name).put("textColor", s.textColor ?: JSONObject.NULL).put("textIn", s.textIn.name).put("textOut", s.textOut.name)
        .put("transition", s.transition.kind.name).put("transitionLength", s.transition.length.name).put("everyCut", s.everyCut)
        .put("pace", s.pace.toDouble()).put("punchIn", s.punchIn)
        .put("color", JSONObject().put("exp", s.color.exposure.toDouble()).put("con", s.color.contrast.toDouble()).put("sat", s.color.saturation.toDouble()).put("warm", s.color.warmth.toDouble()).put("look", s.color.look))
        .put("speedBadge", s.speedBadge).put("speedSticker", s.speedSticker).put("leanSticker", s.leanSticker).put("map", s.map)
        .put("intro", s.intro).put("outro", s.outro).put("duck", s.duck).put("clean", s.cleanVoice).put("watermark", s.watermark).put("brand", s.brand)

    fun read(o: JSONObject): StudioStyle? = runCatching {
        val base = Vibe.entries.firstOrNull { it.name == o.optString("base") } ?: Vibe.HYPE
        val cap = o.optJSONObject("captions")
        val col = o.optJSONObject("color")
        StudioStyle(
            id = o.getString("id"), name = o.optString("name").ifBlank { "My style" }.take(40), base = base, favourite = o.optBoolean("fav"), about = o.optString("about").take(120),
            captions = cap?.let {
                CaptionLook(CaptionKind.entries.firstOrNull { k -> k.name == it.optString("kind") } ?: CaptionKind.STYLE, it.optDouble("size", 1.0).toFloat(), if (it.isNull("y") || !it.has("y")) null else it.getDouble("y").toFloat(), it.optBoolean("karaoke", true))
            } ?: CaptionLook(),
            textLook = TextLook.entries.firstOrNull { it.name == o.optString("textLook") } ?: TextLook.STYLE,
            textColor = if (o.isNull("textColor") || !o.has("textColor")) null else o.getInt("textColor"),
            textIn = TextAnim.entries.firstOrNull { it.name == o.optString("textIn") } ?: TextAnim.FADE,
            textOut = TextAnim.entries.firstOrNull { it.name == o.optString("textOut") } ?: TextAnim.FADE,
            transition = Transition(
                TransitionKind.entries.firstOrNull { it.name == o.optString("transition") } ?: TransitionKind.STYLE,
                TransitionLength.entries.firstOrNull { it.name == o.optString("transitionLength") } ?: TransitionLength.NORMAL,
            ),
            everyCut = o.optBoolean("everyCut"),
            pace = o.optDouble("pace", 1.0).toFloat().coerceIn(0.6f, 1.5f),
            punchIn = o.optBoolean("punchIn"),
            color = col?.let { ClipColor(it.optDouble("exp", 0.0).toFloat(), it.optDouble("con", 0.0).toFloat(), it.optDouble("sat", 0.0).toFloat(), it.optDouble("warm", 0.0).toFloat(), it.optBoolean("look", true)) } ?: ClipColor(),
            speedBadge = o.optBoolean("speedBadge", true), speedSticker = o.optBoolean("speedSticker"), leanSticker = o.optBoolean("leanSticker"),
            map = o.optBoolean("map", true), intro = o.optBoolean("intro"), outro = o.optBoolean("outro", true),
            duck = o.optBoolean("duck", true), cleanVoice = o.optBoolean("clean"), watermark = o.optBoolean("watermark", true), brand = o.optBoolean("brand"),
        )
    }.getOrNull()

    fun writeAll(list: List<StudioStyle>): String = JSONArray().apply { list.forEach { put(write(it)) } }.toString()

    fun readAll(text: String): List<StudioStyle> = runCatching { JSONArray(text).let { a -> (0 until a.length()).mapNotNull { read(a.getJSONObject(it)) } } }.getOrDefault(emptyList())

    /** A style as a code to send to another rider. */
    fun code(s: StudioStyle): String = CODE + java.util.Base64.getEncoder().encodeToString(write(s.copy(favourite = false)).toString().toByteArray())

    /** A style from a code (pasted, maybe with other text around it); [id] is its new id here. */
    fun fromCode(text: String, id: String): StudioStyle? = runCatching {
        val start = text.indexOf(CODE)
        if (start < 0) return null
        val b64 = text.substring(start + CODE.length).trim().takeWhile { !it.isWhitespace() }
        read(JSONObject(String(java.util.Base64.getDecoder().decode(b64))))?.copy(id = id, favourite = false)
    }.getOrNull()

    /**
     * A style from Gemini's description (words or a reference video): JSON with the fields of
     * [write]; anything missing or unknown keeps the defaults.
     */
    fun fromGemini(reply: String, id: String): StudioStyle? = runCatching {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        val o = JSONObject(reply.substring(a, b + 1))
        o.put("id", id)
        if (!o.has("base")) o.put("base", "HYPE")
        o.put("base", o.optString("base").uppercase().let { if (it == "CINEMATIC") "CINE" else it })
        if (o.has("captions") && o.opt("captions") is String) o.put("captions", JSONObject().put("kind", o.getString("captions").uppercase()))
        listOf("transition", "textLook", "textIn", "textOut").forEach { k -> if (o.has(k)) o.put(k, o.optString(k).uppercase().replace(' ', '_')) }
        read(o)
    }.getOrNull()

    /** What Gemini is asked to fill in for a style. */
    const val SCHEMA = """{"name":"short name","about":"a few words","base":"HYPE|CINE|CHILL|VLOG","captions":{"kind":"STYLE|PUNCH|SERIF|LABEL|CHAT|PLAIN","size":1.0,"karaoke":true},"textLook":"STYLE|PLAIN|OUTLINE|BOX|HAND","textColor":-10496,"textIn":"FADE|POP|TYPE|SLIDE|BOUNCE|NONE","textOut":"FADE|POP|TYPE|SLIDE|BOUNCE|NONE","transition":"STYLE|CUT|FADE|FLASH|ZOOM|WHIP|GLITCH|SLASH|SHUTTER|SUN|CARD","transitionLength":"SHORT|NORMAL|LONG","everyCut":false,"pace":1.0,"punchIn":false,"color":{"exp":0.0,"con":0.0,"sat":0.0,"warm":0.0,"look":true},"speedBadge":true,"speedSticker":false,"leanSticker":false,"map":true,"intro":false,"outro":true,"duck":true}"""

    /** Asks Gemini for a style from the rider's words. */
    fun wordsPrompt(words: String): String =
        "You design video styles for a motorcycle rider's Reels and Shorts. Make a style for: \"${words.replace("\"", "'")}\". " +
            "Colours are ARGB ints. pace: 0.6 (fast cuts) to 1.5 (slow). color values -1..1 around 0. Reply with JSON only: $SCHEMA"

    /** Asks Gemini for a style that approximates the look and pace of the frames sent with it. */
    const val REFERENCE_PROMPT = "These are frames from a short video a motorcycle rider likes, in order (about a second apart). " +
        "Describe its look and pace as a video style they can use on their own clips: how fast it cuts, the captions and text, the colour, the transitions. " +
        "Colours are ARGB ints. pace: 0.6 (fast) to 1.5 (slow). color values -1..1 around 0. Reply with JSON only: $SCHEMA"
}
