package com.ridetrack.app.studio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.zip.ZipInputStream

/** A song from Studio's library (public domain, CC0; free to post anywhere). */
data class Track(val id: String, val title: String, val vibe: Vibe, val mood: String, val bpm: Int, val seconds: Int) {
    /** Inside the app (Play builds) or imported from the song pack (test builds). */
    val uri: Uri get() = MusicLibrary.uriOf(this)
}

/**
 * Studio's songs, grouped by the vibe they suit; tempos measured so cuts land on the beat.
 * Play builds carry them inside the app; test builds import them once from the song pack.
 */
object MusicLibrary {
    private var dir: File? = null
    private var bundled: Set<String> = emptySet()
    private val _version = MutableStateFlow(0)
    /** Changes when songs are imported, so screens refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        dir = File(context.filesDir, "music")
        bundled = runCatching { context.assets.list("music")?.toSet() }.getOrNull().orEmpty()
        _version.value++
    }

    /** The songs this phone has. */
    val TRACKS: List<Track> get() = ALL.filter { "${it.id}.m4a" in bundled || File(dir ?: return@filter false, "${it.id}.m4a").isFile }

    fun uriOf(t: Track): Uri =
        if ("${t.id}.m4a" in bundled) Uri.parse("asset:///music/${t.id}.m4a") else Uri.fromFile(File(dir, "${t.id}.m4a"))

    /** Copies the songs out of a song-pack zip; returns how many it added. */
    fun importPack(context: Context, zip: Uri): Int {
        val out = File(context.filesDir, "music").apply { mkdirs() }
        val known = ALL.map { "${it.id}.m4a" }.toSet()
        var n = 0
        context.contentResolver.openInputStream(zip)?.use { input ->
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (!e.isDirectory && name in known) {
                        val tmp = File(out, "$name.part")
                        tmp.outputStream().use { z.copyTo(it) }
                        if (tmp.renameTo(File(out, name))) n++
                    }
                }
            }
        }
        init(context)
        return n
    }

    val ALL: List<Track> = listOf(
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
