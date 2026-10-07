package com.ridetrack.app.studio

import android.net.Uri

/** A song bundled with the app (public domain, CC0; free to post anywhere). */
data class Track(val id: String, val title: String, val vibe: Vibe, val mood: String, val bpm: Int, val seconds: Int) {
    val uri: Uri get() = Uri.parse("asset:///music/$id.m4a")
}

/** Studio's built-in songs, grouped by the vibe they suit; tempos measured so cuts land on the beat. */
object MusicLibrary {
    val TRACKS: List<Track> = listOf(
        Track("battle_ready", "Battle Ready", Vibe.HYPE, "Energetic", 89, 75),
        Track("beat_one", "Beat One", Vibe.HYPE, "Energetic", 117, 75),
        Track("breaking_bollywood", "Breaking Bollywood", Vibe.HYPE, "Energetic", 140, 75),
        Track("fireworks", "Fireworks", Vibe.HYPE, "Energetic", 117, 75),
        Track("cornfield_chase", "Cornfield Chase", Vibe.CINE, "Epic", 89, 75),
        Track("emotional_blockbuster_2", "Emotional Blockbuster 2", Vibe.CINE, "Epic", 96, 75),
        Track("kings_trailer", "Kings Trailer", Vibe.CINE, "Epic", 108, 75),
        Track("be_chillin", "Be Chillin", Vibe.CHILL, "Laid-back", 136, 75),
        Track("nomadic_sunset", "Nomadic Sunset", Vibe.CHILL, "Laid-back", 144, 75),
        Track("sunny_rasta", "Sunny Rasta", Vibe.CHILL, "Laid-back", 172, 75),
        Track("relaxing_ballad", "Relaxing Ballad", Vibe.CHILL, "Laid-back", 136, 75),
        Track("limit_70", "Limit 70", Vibe.VLOG, "Upbeat", 103, 75),
        Track("motions", "Motions", Vibe.VLOG, "Upbeat", 123, 75),
        Track("inspiration", "Inspiration", Vibe.VLOG, "Upbeat", 117, 75),
        Track("bollywood_groove", "Bollywood Groove", Vibe.VLOG, "Upbeat", 161, 75),
        Track("be_jammin", "Be Jammin", Vibe.VLOG, "Upbeat", 152, 75),
    )

    fun forVibe(v: Vibe): List<Track> = TRACKS.filter { it.vibe == v }
}
