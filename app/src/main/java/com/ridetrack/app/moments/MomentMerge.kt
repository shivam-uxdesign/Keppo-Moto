package com.ridetrack.app.moments

import android.content.Context
import com.ridetrack.app.studio.ClipMedia
import com.ridetrack.app.studio.StudioText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Which of a ride's clips to join into one: clips from the same camera that overlap or are less
 * than [GAP_MILLIS] apart. Pure.
 */
object MomentMerge {
    const val GAP_MILLIS = 5_000L

    /** A clip on the ride's clock: when its video starts and how long it is. */
    data class Span(val id: String, val startMillis: Long, val durationMillis: Long, val camera: String?) {
        val endMillis: Long get() = startMillis + durationMillis
    }

    /** The part of a clip that goes into a joined one: [fromMillis]..[toMillis] into its own video. */
    data class Part(val id: String, val fromMillis: Long, val toMillis: Long)

    /**
     * Groups of two or more clips to join, each as its parts in order: footage two clips both
     * have is kept once (the later clip starts where the earlier ends).
     */
    fun plan(clips: List<Span>, gapMillis: Long = GAP_MILLIS): List<List<Part>> {
        val out = ArrayList<List<Part>>()
        clips.filter { it.durationMillis > 0 }.groupBy { it.camera }.values.forEach { same ->
            var group = ArrayList<Part>()
            var end = Long.MIN_VALUE
            same.sortedBy { it.startMillis }.forEach { c ->
                if (group.isNotEmpty() && c.startMillis - end < gapMillis) {
                    val from = (end - c.startMillis).coerceAtLeast(0)
                    // Wholly inside what's already there: nothing new to add (it's still merged away).
                    group += Part(c.id, from.coerceAtMost(c.durationMillis), c.durationMillis)
                    end = maxOf(end, c.endMillis)
                } else {
                    if (group.size > 1) out += group
                    group = arrayListOf(Part(c.id, 0, c.durationMillis))
                    end = c.endMillis
                }
            }
            if (group.size > 1) out += group
        }
        return out.sortedBy { g -> clips.first { it.id == g.first().id }.startMillis }
    }
}

/**
 * After a ride: joins clips that overlap or are under 5 s apart into one video (the first one's
 * moment keeps its place, star and thumbnail; the others are removed). Their captions are read
 * again later. A join that fails leaves the clips as they were.
 */
class MomentMerger(private val context: Context, private val moments: MomentRepository) {
    /** Returns how many clips were joined away. */
    suspend fun mergeRide(rideId: String): Int {
        val clips = moments.forRide(rideId).filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) > 0 }
        val byId = clips.associateBy { it.id }
        val groups = MomentMerge.plan(clips.map { MomentMerge.Span(it.id, it.videoStartMillis, it.durationMillis ?: 0, it.camera) })
        var removed = 0
        groups.forEach { parts ->
            val ms = parts.mapNotNull { byId[it.id] }
            if (ms.size != parts.size) return@forEach
            if (runCatching { join(ms, parts) }.isSuccess) removed += ms.size - 1
        }
        // Studio's ideas point at the clips: they're suggested again from the joined ones.
        if (removed > 0) withContext(Dispatchers.IO) { File(File(context.filesDir, "studio-plans"), "$rideId.json").delete() }
        return removed
    }

    /**
     * Rides of the last two days saved before joining existed (or while it failed), with no Reel
     * made from them yet: joined now. Returns the rides that changed.
     */
    suspend fun catchUp(rideIds: List<String>, hasReels: (String) -> Boolean): List<String> =
        rideIds.filter { !hasReels(it) }.filter { id -> runCatching { mergeRide(id) }.getOrDefault(0) > 0 }

    private suspend fun join(ms: List<Moment>, parts: List<MomentMerge.Part>) {
        val first = ms.first()
        val pieces = parts.zip(ms).filter { (p, _) -> p.toMillis - p.fromMillis > 100 }
        val dir = first.file.parentFile ?: return
        val outVideo = File(dir, first.file.nameWithoutExtension + ".joining.mp4")
        ClipMedia.join(context, pieces.map { (p, m) -> Triple(m.file, p.fromMillis, p.toMillis) }, outVideo)
        // The engine mic's track joins too when every clip has one; otherwise it's dropped (it must line up).
        val engines = pieces.map { (_, m) -> m.engineFile }
        val outEngine = File(dir, first.file.nameWithoutExtension + ".joining.m4a")
        val engineOk = engines.all { it.isFile } && runCatching {
            ClipMedia.join(context, pieces.map { (p, m) -> Triple(m.engineFile, p.fromMillis, p.toMillis) }, outEngine, audioOnly = true)
        }.isSuccess
        withContext(Dispatchers.IO) {
            val length = ClipMedia.lengthMs(outVideo) ?: error("joined clip unreadable")
            // The first clip's file becomes the joined one.
            if (!outVideo.renameTo(first.file)) { outVideo.copyTo(first.file, overwrite = true); outVideo.delete() }
            if (engineOk) { outEngine.renameTo(first.engineFile) } else { outEngine.delete(); first.engineFile.delete() }
            sidecars(first.file).forEach { it.delete() }
            moments.add(
                first.copy(
                    types = ms.flatMap { it.types }.toSet(),
                    peakValue = ms.mapNotNull { it.peakValue }.maxByOrNull { kotlin.math.abs(it) },
                    starred = ms.any { it.starred },
                    durationMillis = length,
                    clipStartMillis = first.videoStartMillis,
                ),
            )
            ms.drop(1).forEach { m -> moments.remove(m) }
        }
    }

    /** Captions and what Gemini saw, kept next to a clip (read again for the joined one). */
    private fun sidecars(clip: File) = listOf(StudioText.linesFile(clip), File(clip.parentFile, "${clip.name}.seen.json"))
}
