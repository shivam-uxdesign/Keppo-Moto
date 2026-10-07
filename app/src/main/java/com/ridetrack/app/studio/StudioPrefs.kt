package com.ridetrack.app.studio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Something to film on the next ride, from the coach; the rider ticks it off. */
data class Shot(val text: String, val done: Boolean = false)

/** Studio's small memory on this phone: the series name and episode, and the shot list for the next ride. */
class StudioPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("studio", Context.MODE_PRIVATE)
    private val _shots = MutableStateFlow(readShots())
    val shots: StateFlow<List<Shot>> = _shots.asStateFlow()

    /** Videos picked on the Studio tab, waiting for the Studio page that opens next. */
    @Volatile private var pending: List<android.net.Uri> = emptyList()

    fun setPending(uris: List<android.net.Uri>) { pending = uris }

    fun takePending(): List<android.net.Uri> = pending.also { pending = emptyList() }

    /** e.g. "Evening Ride Diaries"; empty = no series. */
    var series: String
        get() = prefs.getString(SERIES, "").orEmpty()
        set(v) = prefs.edit().putString(SERIES, v.trim()).apply()

    /** The switches in Studio settings (stats, loop, map, captions…), the same for every ride. */
    var options: StudioOptions
        get() = prefs.getString(OPTIONS, null)?.let { runCatching { ReelJson.readOptionsJson(it) }.getOrNull() } ?: StudioOptions()
        set(v) = prefs.edit().putString(OPTIONS, ReelJson.writeOptionsJson(v)).apply()

    /** The rider's own song for every Reel (null = add music in Instagram), and its name. */
    var music: Pair<String, String>?
        get() = prefs.getString(MUSIC_URI, null)?.let { it to prefs.getString(MUSIC_NAME, "Your song").orEmpty() }
        set(v) = prefs.edit().putString(MUSIC_URI, v?.first).putString(MUSIC_NAME, v?.second).apply()

    /** Each new Reel also goes to Movies/Keppo Moto. */
    var alsoSaveToGallery: Boolean
        get() = prefs.getBoolean(GALLERY, false)
        set(v) = prefs.edit().putBoolean(GALLERY, v).apply()

    /** After each ride, Gemini suggests what to make (in the background, with a notification). */
    var autoSuggest: Boolean
        get() = prefs.getBoolean(AUTO_SUGGEST, true)
        set(v) = prefs.edit().putBoolean(AUTO_SUGGEST, v).apply()

    /** The first suggestion is also made, so it's waiting in Your Reels. */
    var autoMakeTop: Boolean
        get() = prefs.getBoolean(AUTO_MAKE, true)
        set(v) = prefs.edit().putBoolean(AUTO_MAKE, v).apply()

    /** On a low battery the after-ride work waits for Wi-Fi; this lets it use mobile data too. */
    var autoMobileData: Boolean
        get() = prefs.getBoolean(AUTO_MOBILE, false)
        set(v) = prefs.edit().putBoolean(AUTO_MOBILE, v).apply()

    /** The "add a trending song in Instagram" guide was dismissed. */
    var musicGuideSeen: Boolean
        get() = prefs.getBoolean(MUSIC_GUIDE, false)
        set(v) = prefs.edit().putBoolean(MUSIC_GUIDE, v).apply()

    /** The episode the next shared Reel of the series gets. */
    val nextEpisode: Int get() = prefs.getInt(EPISODE, 1)

    /** A Reel of the series was shared or saved: the next one is the next episode. */
    fun episodeUsed(n: Int) {
        if (n >= nextEpisode) prefs.edit().putInt(EPISODE, n + 1).apply()
    }

    /** The coach's "film next time" tips replace the old list (unticked ones that aren't repeated are kept). */
    fun setShots(texts: List<String>) {
        if (texts.isEmpty()) return
        val keep = _shots.value.filter { !it.done && it.text !in texts }
        save((texts.map { Shot(it) } + keep).take(MAX))
    }

    fun toggle(text: String) = save(_shots.value.map { if (it.text == text) it.copy(done = !it.done) else it })

    fun clear() = save(emptyList())

    /** A back-camera clip was filmed: shots about the road ahead are done. */
    fun tickRoadShots() = save(_shots.value.map { if (ROAD.containsMatchIn(it.text)) it.copy(done = true) else it })

    private fun save(list: List<Shot>) {
        _shots.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.text).put("d", it.done)) }
        prefs.edit().putString(SHOTS, arr.toString()).apply()
    }

    private fun readShots(): List<Shot> = runCatching {
        val arr = JSONArray(prefs.getString(SHOTS, "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Shot(it.getString("t"), it.optBoolean("d")) } }
    }.getOrDefault(emptyList())

    private companion object {
        val ROAD = Regex("road|back camera|ahead|view", RegexOption.IGNORE_CASE)
        const val SERIES = "series"
        const val EPISODE = "episode"
        const val SHOTS = "shots"
        const val MUSIC_GUIDE = "music_guide_seen"
        const val GALLERY = "also_gallery"
        const val AUTO_SUGGEST = "auto_suggest"
        const val AUTO_MAKE = "auto_make_top"
        const val AUTO_MOBILE = "auto_mobile_data"
        const val OPTIONS = "options"
        const val MUSIC_URI = "music_uri"
        const val MUSIC_NAME = "music_name"
        const val MAX = 4
    }
}
