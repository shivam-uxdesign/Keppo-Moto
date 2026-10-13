package com.ridetrack.app.studio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A series: a running idea ("Delhi Commute Diaries") with a brief Gemini follows, a format and
 * length, a look every episode shares, and the episode number that comes next. After each ride
 * Gemini looks for moments that fit each active series; its episodes live in one folder.
 */
data class Series(
    val id: String,
    val name: String,
    /** What the series is, in the rider's words. */
    val brief: String = "",
    val format: PieceFormat = PieceFormat.REEL,
    val minSec: Int = 20,
    val maxSec: Int = 30,
    /** Episodes per ride: 1, or 0 for every moment that fits. */
    val perRide: Int = 1,
    /** The style every episode is made in; null = Gemini's pick per episode. */
    val styleId: String? = null,
    /** The post caption: {name} and {ep} are filled in; blank = the episode's own caption. */
    val captionTemplate: String = "",
    val active: Boolean = true,
    val nextEpisode: Int = 1,
    /** Things the rider told this series ("shorter", "end on Kal milte hain"). */
    val notes: List<String> = emptyList(),
    val createdAt: Long = 0,
) {
    /** "Delhi Commute Diaries · Ep 4". */
    fun label(ep: Int): String = "$name · Ep $ep"
}

/** Series ↔ JSON, and the words around them. Pure, unit-tested. */
object SeriesText {
    fun write(list: List<Series>): String = JSONArray().apply {
        list.forEach { s ->
            put(
                JSONObject().put("id", s.id).put("name", s.name).put("brief", s.brief).put("format", s.format.name)
                    .put("min", s.minSec).put("max", s.maxSec).put("perRide", s.perRide).put("style", s.styleId ?: JSONObject.NULL)
                    .put("caption", s.captionTemplate).put("active", s.active).put("next", s.nextEpisode)
                    .put("notes", JSONArray(s.notes)).put("at", s.createdAt),
            )
        }
    }.toString()

    fun read(text: String): List<Series> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val format = PieceFormat.of(o.optString("format"))
            Series(
                id = o.optString("id").ifEmpty { return@mapNotNull null },
                name = o.optString("name"),
                brief = o.optString("brief"),
                format = format,
                minSec = o.optInt("min", format.minSec).coerceIn(format.minSec, format.maxSec),
                maxSec = o.optInt("max", format.maxSec).coerceIn(format.minSec, format.maxSec),
                perRide = o.optInt("perRide", 1).coerceAtLeast(0),
                styleId = o.optString("style").takeIf { it.isNotEmpty() && it != "null" },
                captionTemplate = o.optString("caption"),
                active = o.optBoolean("active", true),
                nextEpisode = o.optInt("next", 1).coerceAtLeast(1),
                notes = o.optJSONArray("notes")?.let { n -> (0 until n.length()).map { n.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
                createdAt = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    /** The episode's post caption: the series' template filled in, or its own caption under the series label. */
    fun caption(s: Series, ep: Int, own: String?): String {
        val t = s.captionTemplate.trim()
        if (t.isEmpty()) return listOfNotNull(s.label(ep), own?.takeIf { it.isNotBlank() }).joinToString("\n")
        return t.replace("{name}", s.name).replace("{ep}", ep.toString()).let { filled ->
            if (own.isNullOrBlank() || "{caption}" !in filled) filled.replace("{caption}", "") else filled.replace("{caption}", own)
        }.trim()
    }

    /** A script Gemini wrote for [s], as one of its episodes: the series' format, length and look. */
    fun asEpisode(sc: Script, s: Series): Script = sc.copy(
        format = s.format,
        lengthSec = sc.lengthSec.coerceIn(s.minSec, s.maxSec),
        style = s.styleId ?: sc.style,
        series = s.id,
        every = false,
        variationOf = null,
    )

    /**
     * Reels that look like a series already: at least [least] made Reels outside any series with
     * the same format and look (the style or vibe they were made in). Biggest group first.
     */
    fun alike(reels: List<ReelProject>, least: Int = 4): List<List<ReelProject>> =
        reels.filter { it.deletedAt == null && it.seriesId == null && it.script != null }
            .groupBy { p -> p.script!!.format to (p.script.style ?: p.options.vibe.name) }
            .values.filter { it.size >= least }
            .sortedByDescending { it.size }

    /** A brief for a series made from [reels], when Gemini doesn't write one. */
    fun briefFrom(reels: List<ReelProject>): String {
        val sc = reels.firstNotNullOfOrNull { it.script }
        val kind = sc?.format?.label?.lowercase() ?: "reel"
        val examples = reels.take(3).joinToString(", ") { "“${it.title}”" }
        return "A ${(sc?.vibe ?: reels.first().options.vibe).label.lowercase()} $kind from the ride, like $examples."
    }
}

/** The rider's series, kept in files/studio-series.json (it goes in the Drive backup). */
class SeriesStore(private val context: Context, migrate: () -> Pair<String, Int>) {
    private val file = File(context.filesDir, FILE)
    private val prefs = context.getSharedPreferences("studio_series", Context.MODE_PRIVATE)
    private val _series = MutableStateFlow(load(migrate))
    val series: StateFlow<List<Series>> = _series.asStateFlow()

    fun get(id: String?): Series? = id?.let { i -> _series.value.firstOrNull { it.id == i } }

    fun active(): List<Series> = _series.value.filter { it.active && it.name.isNotBlank() }

    fun newId(): String = "se-" + UUID.randomUUID().toString().take(8)

    fun save(s: Series) = synchronized(this) {
        _series.value = if (_series.value.any { it.id == s.id }) _series.value.map { if (it.id == s.id) s else it } else _series.value + s
        write()
    }

    fun delete(id: String) = synchronized(this) {
        _series.value = _series.value.filter { it.id != id }
        write()
    }

    /** The next episode's number, now taken (a video is being made with it). */
    fun takeEpisode(id: String): Int = synchronized(this) {
        val s = get(id) ?: return 1
        save(s.copy(nextEpisode = s.nextEpisode + 1))
        s.nextEpisode
    }

    /** An episode numbered [ep] was shared: the next one comes after it. */
    fun used(id: String, ep: Int) = synchronized(this) {
        get(id)?.takeIf { it.nextEpisode <= ep }?.let { save(it.copy(nextEpisode = ep + 1)) }
    }

    /** Gemini looked at this ride for this series already (found an episode or not). */
    fun tried(rideId: String, seriesId: String): Boolean = prefs.getBoolean("$rideId|$seriesId", false)

    fun markTried(rideId: String, seriesId: String) = prefs.edit().putBoolean("$rideId|$seriesId", true).apply()

    /** Reels the rider said aren't a series ("Not now" on the suggestion). */
    fun dismissed(): Set<String> = prefs.getStringSet(DISMISSED, emptySet()).orEmpty()

    fun dismiss(ids: Collection<String>) = prefs.edit().putStringSet(DISMISSED, dismissed() + ids).apply()

    private fun load(migrate: () -> Pair<String, Int>): List<Series> {
        if (file.isFile) return SeriesText.read(file.readText())
        // The one series name from before series had folders becomes the first series, at its episode.
        val (old, next) = runCatching(migrate).getOrDefault("" to 1)
        return if (old.isBlank()) emptyList() else listOf(Series(newId(), old.trim(), nextEpisode = next.coerceAtLeast(1), createdAt = System.currentTimeMillis())).also { list ->
            runCatching { file.writeText(SeriesText.write(list)) }
        }
    }

    private fun write() {
        runCatching { file.writeText(SeriesText.write(_series.value)) }
    }

    companion object {
        const val FILE = "studio-series.json"
        private const val DISMISSED = "dismissed"
    }
}
