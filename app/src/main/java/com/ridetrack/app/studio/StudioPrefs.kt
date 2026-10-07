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

    /** e.g. "Evening Ride Diaries"; empty = no series. */
    var series: String
        get() = prefs.getString(SERIES, "").orEmpty()
        set(v) = prefs.edit().putString(SERIES, v.trim()).apply()

    /** Each new Reel also goes to Movies/Keppo Moto. */
    var alsoSaveToGallery: Boolean
        get() = prefs.getBoolean(GALLERY, false)
        set(v) = prefs.edit().putBoolean(GALLERY, v).apply()

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
        const val SERIES = "series"
        const val EPISODE = "episode"
        const val SHOTS = "shots"
        const val MUSIC_GUIDE = "music_guide_seen"
        const val GALLERY = "also_gallery"
        const val MAX = 4
    }
}
