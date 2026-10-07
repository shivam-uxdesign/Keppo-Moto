package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Every Reel Studio makes, saved on the phone: files/reels/<id>/ holds reel.mp4, cover.jpg,
 * project.json and the voice-over takes. Deleted Reels stay 30 days in Recently deleted.
 */
class ReelStore(private val context: Context) {
    private val root = File(context.filesDir, "reels").apply { mkdirs() }
    private val _reels = MutableStateFlow(load())
    /** Newest first, including those in Recently deleted (see [ReelProject.deletedAt]). */
    val reels: StateFlow<List<ReelProject>> = _reels.asStateFlow()

    fun dir(id: String) = File(root, id)
    fun video(id: String) = File(dir(id), VIDEO)
    fun cover(id: String) = File(dir(id), COVER)
    fun get(id: String): ReelProject? = _reels.value.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().take(13)

    /**
     * Saves [p] with its freshly made [video] (moved in) and voice-over [takes] (copied in);
     * makes a plain cover from a frame if there's none yet. Returns the saved project.
     */
    suspend fun save(p: ReelProject, video: File?, takes: List<VoiceTake>): ReelProject = withContext(Dispatchers.IO) {
        val d = dir(p.id).apply { mkdirs() }
        video?.let { v ->
            val target = video(p.id)
            if (v.absolutePath != target.absolutePath) {
                if (!v.renameTo(target)) { v.copyTo(target, overwrite = true); v.delete() }
            }
        }
        // Takes live with the Reel, so it can be reopened and remade later.
        val saved = takes.mapIndexed { i, t ->
            val name = "voice-$i.pcm"
            val dst = File(d, name)
            if (t.file.absolutePath != dst.absolutePath && t.file.isFile) t.file.copyTo(dst, overwrite = true)
            SavedTake(t.startMs, t.durMs, name, t.lines)
        }
        d.listFiles()?.filter { it.name.startsWith("voice-") && saved.none { s -> s.file == it.name } }?.forEach { it.delete() }
        val project = p.copy(takes = saved, updatedAt = System.currentTimeMillis())
        File(d, PROJECT).writeText(ReelJson.write(project))
        if (!cover(p.id).isFile) frame(video(p.id), project.durationMs / 6)?.let { writeCover(p.id, it) }
        publish(project)
        project
    }

    /** Updates the saved details only (cover settings, Journal state…). */
    suspend fun update(id: String, f: (ReelProject) -> ReelProject): ReelProject? = withContext(Dispatchers.IO) {
        val p = get(id)?.let(f) ?: return@withContext null
        File(dir(id), PROJECT).writeText(ReelJson.write(p))
        publish(p)
        p
    }

    fun writeCover(id: String, bmp: Bitmap) {
        val tmp = File(dir(id), "$COVER.part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(cover(id))
    }

    /** The voice-over takes as Studio uses them. */
    fun takes(p: ReelProject): List<VoiceTake> = p.takes.map { VoiceTake(it.startMs, it.durMs, File(dir(p.id), it.file), it.lines) }

    /** A copy with a new id (video, cover and takes copied), to try another take. */
    suspend fun duplicate(id: String): ReelProject? = withContext(Dispatchers.IO) {
        val p = get(id) ?: return@withContext null
        val copy = p.copy(id = newId(), createdAt = System.currentTimeMillis(), title = p.title, inJournal = false, journalCover = false)
        dir(id).copyRecursively(dir(copy.id), overwrite = true)
        File(dir(copy.id), PROJECT).writeText(ReelJson.write(copy))
        publish(copy)
        copy
    }

    suspend fun delete(id: String) = update(id) { it.copy(deletedAt = System.currentTimeMillis(), inJournal = false, journalCover = false) }
    suspend fun restore(id: String) = update(id) { it.copy(deletedAt = null) }

    /** Deletes a Reel for good. */
    suspend fun purge(id: String) = withContext(Dispatchers.IO) {
        dir(id).deleteRecursively()
        _reels.value = _reels.value.filter { it.id != id }
    }

    /** Removes Reels that have been in Recently deleted for 30 days. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) {
        _reels.value.filter { (it.deletedAt ?: Long.MAX_VALUE) < now - KEEP_MS }.forEach { purge(it.id) }
    }

    fun bytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun publish(p: ReelProject) {
        _reels.value = (listOf(p) + _reels.value.filter { it.id != p.id }).sortedByDescending { it.createdAt }
    }

    private fun load(): List<ReelProject> =
        root.listFiles()?.mapNotNull { d -> File(d, PROJECT).takeIf { it.isFile }?.let { ReelJson.read(it.readText()) } }
            ?.filter { video(it.id).isFile }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()

    companion object {
        const val VIDEO = "reel.mp4"
        const val COVER = "cover.jpg"
        const val PROJECT = "project.json"
        const val KEEP_MS = 30L * 24 * 3600 * 1000

        /** The frame at [atMs] of [video], upright. */
        fun frame(video: File, atMs: Long): Bitmap? = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(video.path)
                r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                r.release()
            }
        }.getOrNull()
    }
}
