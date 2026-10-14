package com.ridetrack.app.studio

import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.telemetry.moments.Downshifts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Downshift tags per clip ([Downshifts]): kept next to the clip ("<clip>.downshifts.json", ms
 * into it), worked out once from its engine sound (the engine mic's when there is one) and the
 * ride's speed. Studio's ideas and search read them.
 */
object ClipDownshifts {
    fun file(clip: File) = File(clip.parentFile, clip.name + ".downshifts.json")

    /** The downshifts in [clip] (ms into it); null when not worked out yet. */
    fun load(clip: File): List<Long>? = runCatching {
        val f = file(clip)
        if (!f.isFile) return null
        JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getLong(it) } }
    }.getOrNull()

    /** Tags the ride's clips that aren't yet. Returns how many downshifts were found. */
    suspend fun tagRide(c: AppContainer, rideId: String): Int = withContext(Dispatchers.IO) {
        val clips = c.moments.forRide(rideId).filter { it.kind == MomentKind.CLIP && it.file.isFile && load(it.file) == null }
        if (clips.isEmpty()) return@withContext 0
        val samples = c.rides.track(rideId).samples
        var found = 0
        clips.forEach { m ->
            val pitches = runCatching { EngineAudio.pitches(c.appContext, m.file, m.engineFile.takeIf { it.isFile }, null) }.getOrNull() ?: return@forEach
            val list = Downshifts.find(pitches, Revs.HOP_MS) { ms ->
                StudioNumbers.speedAt(samples, m.videoStartMillis + ms).takeIf { it >= 0 }?.let { it / 3.6 }
            }
            runCatching { file(m.file).writeAtomically(JSONArray(list).toString()) }
            found += list.size
        }
        found
    }
}
