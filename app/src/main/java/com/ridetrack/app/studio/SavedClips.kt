package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** A part of a clip the rider kept, to reuse in Reels of other rides. Gemini never picks these. */
data class SavedClip(
    val id: String,
    val createdAt: Long,
    val durationMs: Long,
    /** What was said in it (ms from its start). */
    val lines: List<CaptionLine>,
    /** Words to find it by, from what was said and how it was filmed. */
    val tags: List<String>,
    /** Where it came from: the ride's name and date; null for a gallery video. */
    val from: String?,
    val rideId: String?,
    /** When it was filmed (wall time), for ordering. */
    val atMillis: Long,
    val camera: String? = null,
)

/** Saved clips' tags: what was said and how it was filmed. Pure, unit-tested. */
object ClipTags {
    /** Words that say nothing about a clip (English and Hinglish). */
    private val STOP = setOf(
        "the", "and", "this", "that", "with", "have", "what", "there", "here", "just", "like", "from", "they", "were", "will", "your", "about", "really", "very",
        "okay", "yeah", "haan", "nahi", "kya", "hai", "hain", "aur", "bhi", "toh", "yaar", "bhai", "abhi", "kuch", "mein", "main", "mera", "meri", "kaise", "kyun", "wala", "wali", "raha", "rahi",
        "gaya", "gayi", "karo", "kar", "karna", "hota", "hoti", "tha", "thi", "ye", "yeh", "woh", "isko", "usko", "apna", "apni", "dekho", "chalo", "sab", "bas",
    )

    fun of(lines: List<CaptionLine>, camera: String?, topKmh: Int?): List<String> {
        val words = lines.flatMap { l -> l.text.lowercase().split(Regex("[^a-z0-9']+")) }
            .map { it.trim('\'') }
            .filter { it.length >= 4 && it !in STOP && it.any(Char::isLetter) }
        val said = words.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { words.indexOf(it.key) }).take(3).map { it.key }
        return listOfNotNull(
            if (camera == "back") "road" else "selfie",
            "talking".takeIf { lines.isNotEmpty() },
            "fast".takeIf { (topKmh ?: 0) >= 60 },
        ) + said
    }
}

/** Saved clips, kept in files/saved-clips/<id>/ (copied, so they stay when a ride is deleted). */
@OptIn(UnstableApi::class)
class SavedClipStore(private val context: Context) {
    private val root = File(context.filesDir, "saved-clips").apply { mkdirs() }
    private val _clips = MutableStateFlow(load())
    /** Newest first. */
    val clips: StateFlow<List<SavedClip>> = _clips.asStateFlow()

    fun video(id: String) = File(File(root, id), "clip.mp4")
    fun thumb(id: String) = File(File(root, id), "thumb.jpg")

    /**
     * Copies [startMs]..[endMs] of [uri] into a saved clip. [lines] are the clip's words (ms from
     * the clip's start); only those inside the part are kept.
     */
    suspend fun save(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        lines: List<CaptionLine>,
        from: String?,
        rideId: String?,
        atMillis: Long,
        camera: String?,
        topKmh: Int?,
    ): SavedClip {
        val id = UUID.randomUUID().toString().take(12)
        val dir = File(root, id).apply { mkdirs() }
        val out = video(id)
        try {
            cut(uri, startMs, endMs, out)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        val inPart = lines.filter { it.endMs > startMs && it.startMs < endMs }
            .map { it.copy(startMs = (it.startMs - startMs).coerceAtLeast(0), endMs = (it.endMs - startMs).coerceAtMost(endMs - startMs)) }
        val clip = SavedClip(id, System.currentTimeMillis(), endMs - startMs, inPart, ClipTags.of(inPart, camera, topKmh), from, rideId, atMillis + startMs, camera)
        withContext(Dispatchers.IO) {
            ReelStore.frame(out, minOf(500L, clip.durationMs / 2))?.let { b ->
                thumb(id).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                b.recycle()
            }
            File(dir, META).writeText(write(clip).toString())
        }
        _clips.value = listOf(clip) + _clips.value
        return clip
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        _clips.value = _clips.value.filter { it.id != id }
    }

    suspend fun setTags(id: String, tags: List<String>) = withContext(Dispatchers.IO) {
        val c = _clips.value.firstOrNull { it.id == id } ?: return@withContext
        val updated = c.copy(tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct())
        File(File(root, id), META).writeText(write(updated).toString())
        _clips.value = _clips.value.map { if (it.id == id) updated else it }
    }

    /** A saved clip as a part Studio can put in a Reel (its file is its source). */
    fun bit(c: SavedClip): Bit = Bit(
        id = "saved-${c.id}", momentId = "saved:${c.id}", clipDurationMs = c.durationMs, inMs = 0, outMs = c.durationMs,
        atMillis = c.atMillis, lines = c.lines, speedKmh = 0.0, punch = 5f, fromRide = "Saved", source = Uri.fromFile(video(c.id)).toString(), camera = c.camera,
    )

    fun bytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The exact part, re-encoded only where it must be (Transformer trims). */
    private suspend fun cut(uri: Uri, startMs: Long, endMs: Long, out: File) {
        val item = MediaItem.Builder().setUri(uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
            .build()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val t = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            out.delete()
                            if (cont.isActive) cont.cancel(exportException)
                        }
                    })
                    .build()
                t.start(item, out.path)
                cont.invokeOnCancellation { t.cancel(); out.delete() }
            }
        }
    }

    private fun load(): List<SavedClip> =
        root.listFiles()?.mapNotNull { d -> runCatching { read(JSONObject(File(d, META).readText())) }.getOrNull()?.takeIf { video(it.id).isFile } }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()

    private fun write(c: SavedClip) = JSONObject()
        .put("id", c.id).put("createdAt", c.createdAt).put("dur", c.durationMs)
        .put("lines", JSONArray().apply { c.lines.forEach { put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) } })
        .put("tags", JSONArray(c.tags)).put("from", c.from ?: JSONObject.NULL).put("rideId", c.rideId ?: JSONObject.NULL)
        .put("at", c.atMillis).put("camera", c.camera ?: JSONObject.NULL)

    private fun read(o: JSONObject) = SavedClip(
        id = o.getString("id"), createdAt = o.getLong("createdAt"), durationMs = o.getLong("dur"),
        lines = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { CaptionLine(it.getLong("s"), it.getLong("e"), it.getString("t")) } } }.orEmpty(),
        tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        from = o.optString("from").takeIf { !o.isNull("from") && it.isNotEmpty() },
        rideId = o.optString("rideId").takeIf { !o.isNull("rideId") && it.isNotEmpty() },
        atMillis = o.optLong("at"),
        camera = o.optString("camera").takeIf { !o.isNull("camera") && it.isNotEmpty() },
    )

    private companion object {
        const val META = "clip.json"
    }
}
