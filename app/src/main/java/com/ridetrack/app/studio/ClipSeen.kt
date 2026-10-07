package com.ridetrack.app.studio

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.transcribe.FirebaseTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File

/** What Gemini saw in a clip (a few words), kept next to it so search can find "rain" or "flyover". */
object ClipSeen {
    fun file(clip: File) = File(clip.parentFile, "${clip.name}.seen.json")

    fun load(clip: File): List<String>? = file(clip).takeIf { it.isFile }?.let { f ->
        runCatching { JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
    }

    fun save(clip: File, words: List<String>) {
        runCatching { file(clip).writeText(JSONArray(words).toString()) }
    }

    /** Two small frames of a clip (a third and two thirds in), as JPEGs. */
    private fun frames(clip: File, durMs: Long): List<ByteArray> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(clip.path)
            listOf(durMs / 3, durMs * 2 / 3).mapNotNull { t ->
                r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 568)?.let { b ->
                    ByteArrayOutputStream().use { o -> b.compress(Bitmap.CompressFormat.JPEG, 70, o); b.recycle(); o.toByteArray() }
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Gemini looks at [clips] not looked at yet, six per request, until done, [limit] reached, or
     * its free allowance runs out. Returns how many were looked at.
     */
    suspend fun look(c: AppContainer, clips: List<Moment>, limit: Int = 60, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val todo = clips.filter { it.file.isFile && load(it.file) == null }.take(limit)
        if (todo.isEmpty() || !c.transcripts.available) return@withContext 0
        val g = StudioGemini(preferred = { c.transcripts.model }, onWorking = { c.transcripts.model = it })
        var done = 0
        for (batch in todo.chunked(6)) {
            onProgress(done, todo.size)
            val frames = batch.map { frames(it.file, it.durationMillis ?: 10_000) }
            val seen = try {
                g.seen(frames)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: FirebaseTranscriber.Busy) {
                break
            } catch (e: Exception) {
                c.errors.record("Studio search", "Gemini couldn't look at ${batch.size} clips", e)
                break
            } ?: break
            batch.forEachIndexed { i, m -> save(m.file, seen.getOrElse(i) { emptyList() }) }
            done += batch.size
        }
        onProgress(done, todo.size)
        done
    }
}
