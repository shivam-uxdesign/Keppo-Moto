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
            plan = StudioPlan(o.getJSONArray("segments").let { a -> (0 until a.length()).mapNotNull { readSegment(a.getJSONObject(it)) } }, vibe),
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
        )
    }.getOrNull()

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
            .put("hook", s.hook).put("tail", s.tail).put("teaser", s.teaser).put("section", s.section).put("text", s.text ?: JSONObject.NULL)
        is TitleSegment -> JSONObject().put("type", "title").put("dur", s.durMs)
        is StatsSegment -> JSONObject().put("type", "stats").put("dur", s.durMs)
    }

    private fun readSegment(o: JSONObject): Segment? = when (o.optString("type")) {
        "clip" -> ClipSegment(readBit(o.getJSONObject("bit")), o.getLong("in"), o.getLong("dur"), readLines(o.optJSONArray("lines")), o.optBoolean("hook"), o.optBoolean("tail"), o.optBoolean("teaser"), o.optInt("section", -1), o.optStringOrNull("text"))
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

    private fun lines(l: List<CaptionLine>) = JSONArray().apply { l.forEach { put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) } }

    private fun readLines(a: JSONArray?): List<CaptionLine> =
        if (a == null) emptyList() else (0 until a.length()).map { i -> a.getJSONObject(i).let { CaptionLine(it.getLong("s"), it.getLong("e"), it.getString("t")) } }

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
    private fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
}
