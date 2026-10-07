package com.ridetrack.app.studio

import android.net.Uri

/** A song bundled with the app (public domain, CC0; free to post anywhere). */
data class Track(val id: String, val title: String, val vibe: Vibe, val mood: String, val bpm: Int, val seconds: Int) {
    val uri: Uri get() = Uri.parse("asset:///music/$id.m4a")
}

/** Studio's built-in songs, grouped by the vibe they suit; tempos measured so cuts land on the beat. */
object MusicLibrary {
    val TRACKS: List<Track> = emptyList()

    fun forVibe(v: Vibe): List<Track> = TRACKS.filter { it.vibe == v }
}
