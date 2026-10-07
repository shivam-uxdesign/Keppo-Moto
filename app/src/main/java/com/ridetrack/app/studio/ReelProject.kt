package com.ridetrack.app.studio

import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved Reel: everything needed to show it, and to reopen it in Studio exactly as it was
 * (plan, captions, voice-over takes, options, texts). Stored as `project.json` next to the video.
 */
data class ReelProject(
    val id: String,
    /** The ride it was made from; null for a Reel from phone videos only. */
    val rideId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val series: String,
    val episode: Int,
    val hookLine: String,
    val postCaption: String,
    val story: String?,
    val options: StudioOptions,
    val musicUri: String?,
    val musicName: String?,
    val plan: StudioPlan,
    /** Voice-over takes, files relative to the Reel's folder. */
    val takes: List<SavedTake>,
    val tips: List<Tip>,
    val durationMs: Long,
    /** Set while in Recently deleted. */
    val deletedAt: Long? = null,
    /** Sent to Keppo Journal (in the ride's shared folder). */
    val inJournal: Boolean = false,
    /** This Reel's cover is the Journal entry's cover. */
    val journalCover: Boolean = false,
    /** Cover settings: the frame (ms into the Reel), whether text is on it, its text, or the route card. */
    val coverAtMs: Long? = null,
    val coverText: Boolean = true,
    val coverLine: String? = null,
    val coverRoute: Boolean = false,
    /** Which Reel idea it was made from ("Highlights", "The 15-second hook"…); null for a plain one. */
    val idea: String? = null,
    /** The script it was made from (sections and shots); null for Reels made before scripts. */
    val script: Script? = null,
    /** How it was made, when that's worth knowing ("Made with a simpler method on this phone"); null when as usual. */
    val note: String? = null,
    /** Shared or saved to the phone at least once; until then it's a draft. */
    val posted: Boolean = false,
) {
    val vibe: Vibe get() = options.vibe
}

data class SavedTake(val startMs: Long, val durMs: Long, val file: String, val lines: List<CaptionLine>)

/** project.json ↔ [ReelProject]. Pure, unit-tested. */
object ReelJson {
    fun write(p: ReelProject): String = JSONObject().apply {
        put("v", 1)
        put("id", p.id)
        put("rideId", p.rideId ?: JSONObject.NULL)
        put("createdAt", p.createdAt)
        put("updatedAt", p.updatedAt)
        put("title", p.title)
        put("series", p.series)
        put("episode", p.episode)
        put("hookLine", p.hookLine)
        put("postCaption", p.postCaption)
        put("story", p.story ?: JSONObject.NULL)
        put("options", options(p.options))
        put("musicUri", p.musicUri ?: JSONObject.NULL)
        put("musicName", p.musicName ?: JSONObject.NULL)
        put("vibe", p.plan.vibe.name)
        put("segments", JSONArray().apply { p.plan.segments.forEach { put(segment(it)) } })
        put("texts", JSONArray().apply { p.plan.texts.forEach { t -> put(textItem(t)) } })
        put("captionLook", JSONObject().put("kind", p.plan.captionLook.kind.name).put("size", p.plan.captionLook.size.toDouble()).put("y", p.plan.captionLook.y?.toDouble() ?: JSONObject.NULL).put("karaoke", p.plan.captionLook.karaoke))
        put("stickers", JSONArray().apply { p.plan.stickers.forEach { st -> put(JSONObject().put("id", st.id).put("kind", st.kind.name).put("s", st.startMs).put("e", st.endMs).put("x", st.x.toDouble()).put("y", st.y.toDouble()).put("size", st.size.toDouble()).put("rot", st.rotation.toDouble()).put("t", st.text)) } })
        put("layers", JSONArray().apply { p.plan.layers.forEach { put(layer(it)) } })
        put("audio", JSONArray().apply { p.plan.audio.forEach { put(audio(it)) } })
        put("mix", mix(p.plan.mix))
        put("markers", JSONArray().apply { p.plan.markers.forEach { m -> put(JSONObject().put("id", m.id).put("at", m.atMs).put("label", m.label)) } })
        put("takes", JSONArray().apply { p.takes.forEach { t -> put(JSONObject().put("start", t.startMs).put("dur", t.durMs).put("file", t.file).put("lines", lines(t.lines))) } })
        put("tips", JSONArray().apply { p.tips.forEach { t -> put(JSONObject().put("text", t.text).put("action", t.action?.name ?: JSONObject.NULL).put("nextRide", t.nextRide)) } })
        put("durationMs", p.durationMs)
        put("deletedAt", p.deletedAt ?: JSONObject.NULL)
        put("inJournal", p.inJournal)
        put("journalCover", p.journalCover)
        put("coverAtMs", p.coverAtMs ?: JSONObject.NULL)
        put("coverText", p.coverText)
        put("coverLine", p.coverLine ?: JSONObject.NULL)
        put("coverRoute", p.coverRoute)
        put("idea", p.idea ?: JSONObject.NULL)
        put("script", p.script?.let { ScriptJson.write(it) } ?: JSONObject.NULL)
        put("note", p.note ?: JSONObject.NULL)
        put("posted", p.posted)
    }.toString()

    fun read(text: String): ReelProject? = runCatching {
        val o = JSONObject(text)
        val vibe = Vibe.valueOf(o.getString("vibe"))
        ReelProject(
            id = o.getString("id"),
            rideId = o.optStringOrNull("rideId"),
            createdAt = o.getLong("createdAt"),
            updatedAt = o.optLong("updatedAt", o.getLong("createdAt")),
            title = o.optString("title"),
            series = o.optString("series"),
            episode = o.optInt("episode", 1),
            hookLine = o.optString("hookLine"),
            postCaption = o.optString("postCaption"),
            story = o.optStringOrNull("story"),
            options = readOptions(o.optJSONObject("options") ?: JSONObject()),
            musicUri = o.optStringOrNull("musicUri"),
            musicName = o.optStringOrNull("musicName"),
            plan = StudioPlan(
                o.getJSONArray("segments").let { a -> (0 until a.length()).mapNotNull { readSegment(a.getJSONObject(it)) } },
                vibe,
                o.optJSONArray("texts")?.let { a ->
                    (0 until a.length()).map { i -> readTextItem(a.getJSONObject(i)) }
                }.orEmpty(),
                layers = o.optJSONArray("layers")?.let { a -> (0 until a.length()).mapNotNull { runCatching { readLayer(a.getJSONObject(it)) }.getOrNull() } }.orEmpty(),
                audio = o.optJSONArray("audio")?.let { a -> (0 until a.length()).mapNotNull { runCatching { readAudio(a.getJSONObject(it)) }.getOrNull() } }.orEmpty(),
                mix = o.optJSONObject("mix")?.let { readMix(it) } ?: TrackMix(),
                captionLook = o.optJSONObject("captionLook")?.let { c ->
                    CaptionLook(CaptionKind.entries.firstOrNull { it.name == c.optString("kind") } ?: CaptionKind.STYLE, c.optDouble("size", 1.0).toFloat(), c.optDoubleOrNull("y")?.toFloat(), c.optBoolean("karaoke", true))
                } ?: CaptionLook(),
                stickers = o.optJSONArray("stickers")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val st = a.getJSONObject(i)
                        val kind = StickerKind.entries.firstOrNull { it.name == st.optString("kind") } ?: return@mapNotNull null
                        StickerItem(st.getString("id"), kind, st.getLong("s"), st.getLong("e"), st.optDouble("x", 0.5).toFloat(), st.optDouble("y", 0.5).toFloat(), st.optDouble("size", 1.0).toFloat(), st.optDouble("rot", 0.0).toFloat(), st.optString("t"))
                    }
                }.orEmpty(),
                markers = o.optJSONArray("markers")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { m -> Marker(m.getString("id"), m.getLong("at"), m.optString("label")) } } }.orEmpty(),
            ),
            takes = o.optJSONArray("takes")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { t -> SavedTake(t.getLong("start"), t.getLong("dur"), t.getString("file"), readLines(t.optJSONArray("lines"))) } }
            }.orEmpty(),
            tips = o.optJSONArray("tips")?.let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { t -> Tip(t.getString("text"), t.optStringOrNull("action")?.let { n -> TipAction.entries.firstOrNull { it.name == n } }, t.optBoolean("nextRide")) }
                }
            }.orEmpty(),
            durationMs = o.optLong("durationMs"),
            deletedAt = o.optLongOrNull("deletedAt"),
            inJournal = o.optBoolean("inJournal"),
            journalCover = o.optBoolean("journalCover"),
            coverAtMs = o.optLongOrNull("coverAtMs"),
            coverText = o.optBoolean("coverText", true),
            coverLine = o.optStringOrNull("coverLine"),
            coverRoute = o.optBoolean("coverRoute"),
            idea = o.optStringOrNull("idea"),
            script = o.optJSONObject("script")?.let { ScriptJson.read(it) },
            note = o.optStringOrNull("note"),
            posted = o.optBoolean("posted"),
        )
    }.getOrNull()

    fun writeOptionsJson(o: StudioOptions): String = options(o).toString()
    fun readOptionsJson(text: String): StudioOptions = readOptions(JSONObject(text))

    private fun options(o: StudioOptions) = JSONObject()
        .put("vibe", o.vibe.name).put("lengthSec", o.lengthSec).put("intro", o.intro).put("outro", o.outro).put("loopEnd", o.loopEnd)
        .put("map", o.map).put("captions", o.captions).put("watermark", o.watermark).put("seed", o.seed).put("bpm", o.bpm ?: JSONObject.NULL).put("teaser", o.teaser)

    private fun readOptions(o: JSONObject) = StudioOptions(
        vibe = o.optStringOrNull("vibe")?.let { n -> Vibe.entries.firstOrNull { it.name == n } } ?: Vibe.HYPE,
        lengthSec = o.optInt("lengthSec", 30),
        intro = o.optBoolean("intro", false),
        outro = o.optBoolean("outro", true),
        loopEnd = o.optBoolean("loopEnd", true),
        map = o.optBoolean("map", true),
        captions = o.optBoolean("captions", true),
        watermark = o.optBoolean("watermark", true),
        seed = o.optInt("seed", 1),
        bpm = o.optLongOrNull("bpm")?.toInt(),
        teaser = o.optBoolean("teaser"),
    )

    private fun segment(s: Segment): JSONObject = when (s) {
        is ClipSegment -> JSONObject().put("type", "clip").put("bit", bit(s.bit)).put("in", s.inMs).put("dur", s.durMs).put("lines", lines(s.lines))
            .put("hook", s.hook).put("tail", s.tail).put("teaser", s.teaser).put("section", s.section).put("text", s.text ?: JSONObject.NULL).put("volume", s.volume.toDouble())
            .put("speed", s.speed.toDouble()).put("ramp", s.ramp.name).put("reverse", s.reverse ?: JSONObject.NULL).put("still", s.still ?: JSONObject.NULL)
            .put("rotation", s.rotation).put("flip", s.flip)
            .put("frame", JSONArray().apply { s.frame.forEach { k -> put(JSONObject().put("at", k.atMs).put("z", k.zoom.toDouble()).put("x", k.x.toDouble()).put("y", k.y.toDouble())) } })
            .put("color", JSONObject().put("exp", s.color.exposure.toDouble()).put("con", s.color.contrast.toDouble()).put("sat", s.color.saturation.toDouble()).put("warm", s.color.warmth.toDouble()).put("look", s.color.look))
            .put("transition", s.transition?.let { JSONObject().put("kind", it.kind.name).put("length", it.length.name) } ?: JSONObject.NULL)
        is TitleSegment -> JSONObject().put("type", "title").put("dur", s.durMs)
        is StatsSegment -> JSONObject().put("type", "stats").put("dur", s.durMs)
    }

    private fun readSegment(o: JSONObject): Segment? = when (o.optString("type")) {
        "clip" -> ClipSegment(
            readBit(o.getJSONObject("bit")), o.getLong("in"), o.getLong("dur"), readLines(o.optJSONArray("lines")), o.optBoolean("hook"), o.optBoolean("tail"), o.optBoolean("teaser"),
            o.optInt("section", -1), o.optStringOrNull("text"), o.optDouble("volume", 1.0).toFloat(),
            speed = o.optDouble("speed", 1.0).toFloat(),
            ramp = SpeedRamp.entries.firstOrNull { it.name == o.optString("ramp") } ?: SpeedRamp.NONE,
            reverse = o.optStringOrNull("reverse"),
            still = o.optStringOrNull("still"),
            rotation = o.optInt("rotation", 0),
            flip = o.optBoolean("flip"),
            frame = o.optJSONArray("frame")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { k -> FrameKey(k.getLong("at"), k.getDouble("z").toFloat(), k.getDouble("x").toFloat(), k.getDouble("y").toFloat()) } } }.orEmpty(),
            color = o.optJSONObject("color")?.let { c -> ClipColor(c.optDouble("exp", 0.0).toFloat(), c.optDouble("con", 0.0).toFloat(), c.optDouble("sat", 0.0).toFloat(), c.optDouble("warm", 0.0).toFloat(), c.optBoolean("look", true)) } ?: ClipColor(),
            transition = o.optJSONObject("transition")?.let { t ->
                Transition(TransitionKind.entries.firstOrNull { it.name == t.optString("kind") } ?: TransitionKind.STYLE, TransitionLength.entries.firstOrNull { it.name == t.optString("length") } ?: TransitionLength.NORMAL)
            },
        )
        "title" -> TitleSegment(o.getLong("dur"))
        "stats" -> StatsSegment(o.getLong("dur"))
        else -> null
    }

    private fun bit(b: Bit) = JSONObject().put("id", b.id).put("momentId", b.momentId).put("clipDur", b.clipDurationMs).put("in", b.inMs).put("out", b.outMs)
        .put("at", b.atMillis).put("lines", lines(b.lines)).put("kmh", b.speedKmh).put("punch", b.punch.toDouble()).put("fromRide", b.fromRide ?: JSONObject.NULL)
        .put("source", b.source ?: JSONObject.NULL).put("camera", b.camera ?: JSONObject.NULL)

    private fun readBit(o: JSONObject) = Bit(
        id = o.getString("id"),
        momentId = o.getString("momentId"),
        clipDurationMs = o.getLong("clipDur"),
        inMs = o.getLong("in"),
        outMs = o.getLong("out"),
        atMillis = o.getLong("at"),
        lines = readLines(o.optJSONArray("lines")),
        speedKmh = o.optDouble("kmh", 0.0),
        punch = o.optDouble("punch", 3.0).toFloat(),
        fromRide = o.optStringOrNull("fromRide"),
        source = o.optStringOrNull("source"),
        camera = o.optStringOrNull("camera"),
    )

    private fun textItem(t: TextItem) = JSONObject().put("id", t.id).put("s", t.startMs).put("e", t.endMs).put("t", t.text).put("y", t.y.toDouble())
        .put("x", t.x.toDouble()).put("size", t.size.toDouble()).put("rot", t.rotation.toDouble()).put("look", t.look.name).put("color", t.color ?: JSONObject.NULL)
        .put("align", t.align.name).put("in", t.animIn.name).put("out", t.animOut.name)

    private fun readTextItem(t: JSONObject) = TextItem(
        t.getString("id"), t.getLong("s"), t.getLong("e"), t.getString("t"), t.optDouble("y", 0.3).toFloat(),
        x = t.optDouble("x", 0.5).toFloat(), size = t.optDouble("size", 1.0).toFloat(), rotation = t.optDouble("rot", 0.0).toFloat(),
        look = TextLook.entries.firstOrNull { it.name == t.optString("look") } ?: TextLook.STYLE,
        color = if (t.isNull("color") || !t.has("color")) null else t.getInt("color"),
        align = TextAlignment.entries.firstOrNull { it.name == t.optString("align") } ?: TextAlignment.CENTER,
        animIn = TextAnim.entries.firstOrNull { it.name == t.optString("in") } ?: TextAnim.FADE,
        animOut = TextAnim.entries.firstOrNull { it.name == t.optString("out") } ?: TextAnim.FADE,
    )

    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)

    private fun layer(l: LayerItem) = JSONObject().put("id", l.id).put("bit", bit(l.bit)).put("in", l.inMs).put("start", l.startMs).put("dur", l.durMs)
        .put("cx", l.cx.toDouble()).put("cy", l.cy.toDouble()).put("w", l.w.toDouble()).put("aspect", l.aspect.toDouble()).put("rotation", l.rotation.toDouble())
        .put("shape", l.shape.name).put("opacity", l.opacity.toDouble()).put("border", l.border).put("volume", l.volume.toDouble())
        .put("keys", JSONArray().apply { l.keys.forEach { k -> put(JSONObject().put("at", k.atMs).put("cx", k.cx.toDouble()).put("cy", k.cy.toDouble()).put("w", k.w.toDouble())) } })

    private fun readLayer(o: JSONObject) = LayerItem(
        id = o.getString("id"), bit = readBit(o.getJSONObject("bit")), inMs = o.getLong("in"), startMs = o.getLong("start"), durMs = o.getLong("dur"),
        cx = o.optDouble("cx", 0.72).toFloat(), cy = o.optDouble("cy", 0.22).toFloat(), w = o.optDouble("w", 0.42).toFloat(), aspect = o.optDouble("aspect", 0.5625).toFloat(),
        rotation = o.optDouble("rotation", 0.0).toFloat(), shape = LayerShape.entries.firstOrNull { it.name == o.optString("shape") } ?: LayerShape.ROUNDED,
        opacity = o.optDouble("opacity", 1.0).toFloat(), border = o.optBoolean("border", true), volume = o.optDouble("volume", 0.0).toFloat(),
        keys = o.optJSONArray("keys")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { k -> LayerKey(k.getLong("at"), k.getDouble("cx").toFloat(), k.getDouble("cy").toFloat(), k.getDouble("w").toFloat()) } } }.orEmpty(),
    )

    private fun audio(a: AudioItem) = JSONObject().put("id", a.id).put("kind", a.kind.name).put("bit", bit(a.bit)).put("in", a.inMs).put("start", a.startMs).put("dur", a.durMs)
        .put("volume", a.volume.toDouble()).put("fadeIn", a.fadeInMs).put("fadeOut", a.fadeOutMs).put("file", a.file ?: JSONObject.NULL)
        .put("curve", JSONArray().apply { a.curve.forEach { p -> put(JSONObject().put("at", p.atMs).put("l", p.level.toDouble())) } })

    private fun readAudio(o: JSONObject) = AudioItem(
        id = o.getString("id"), kind = TrackKind.valueOf(o.getString("kind")), bit = readBit(o.getJSONObject("bit")), inMs = o.getLong("in"), startMs = o.getLong("start"), durMs = o.getLong("dur"),
        volume = o.optDouble("volume", 1.0).toFloat(), fadeInMs = o.optLong("fadeIn"), fadeOutMs = o.optLong("fadeOut"), file = o.optStringOrNull("file"),
        curve = o.optJSONArray("curve")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { p -> VolumePoint(p.getLong("at"), p.getDouble("l").toFloat()) } } }.orEmpty(),
    )

    private fun mix(m: TrackMix) = JSONObject()
        .put("volumes", JSONObject().apply { m.volumes.forEach { (k, v) -> put(k.name, v.toDouble()) } })
        .put("muted", JSONArray(m.muted.map { it.name })).put("solo", m.solo?.name ?: JSONObject.NULL).put("duck", m.duck).put("clean", m.cleanVoice)

    private fun readMix(o: JSONObject) = TrackMix(
        volumes = o.optJSONObject("volumes")?.let { v -> TrackKind.entries.filter { v.has(it.name) }.associateWith { v.getDouble(it.name).toFloat() } }.orEmpty(),
        muted = o.optJSONArray("muted")?.let { a -> (0 until a.length()).mapNotNull { i -> TrackKind.entries.firstOrNull { it.name == a.getString(i) } }.toSet() }.orEmpty(),
        solo = o.optStringOrNull("solo")?.let { n -> TrackKind.entries.firstOrNull { it.name == n } },
        duck = o.optBoolean("duck", true),
        cleanVoice = o.optBoolean("clean", false),
    )

    private fun lines(l: List<CaptionLine>) = JSONArray().apply { l.forEach { put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) } }

    private fun readLines(a: JSONArray?): List<CaptionLine> =
        if (a == null) emptyList() else (0 until a.length()).map { i -> a.getJSONObject(i).let { CaptionLine(it.getLong("s"), it.getLong("e"), it.getString("t")) } }

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
    private fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
}
