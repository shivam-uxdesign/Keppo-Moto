package com.ridetrack.app.studio

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A video the rider picked from the phone's gallery. It isn't copied: Studio keeps access to it. */
data class PhoneClip(
    val id: String,
    val uri: Uri,
    /** When filming started (wall time); 0 when the video doesn't say. */
    val startMillis: Long,
    val durationMs: Long,
    val thumb: File?,
)

/** Reads gallery videos for Studio: when they were filmed, how long, a thumbnail, their bits. */
object PhoneVideos {
    /** Ids of phone clips start with this, so they never clash with moments. */
    const val PREFIX = "phone-"

    fun dir(context: Context) = File(context.filesDir, "studio-phone").apply { mkdirs() }

    fun idOf(uri: Uri): String =
        PREFIX + MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    /** Where the clip's captions are kept (`<id>.mp4.lines.json`, as for moments). */
    fun captionKey(context: Context, id: String) = File(dir(context), "$id.mp4")

    /** Reads [uri]; null if it can't be opened (deleted, or access lost). */
    fun read(context: Context, uri: Uri): PhoneClip? = runCatching {
        // Picker links can be kept across restarts where the phone allows it.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val id = idOf(uri)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val start = parseDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)).takeIf { it > 0 } ?: dateTaken(context, uri)
            val thumbFile = File(dir(context), "$id.jpg")
            if (!thumbFile.isFile) {
                r.getFrameAtTime((dur / 3) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                    val k = 320f / maxOf(b.width, b.height)
                    val small = if (k < 1) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
                    thumbFile.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (small !== b) small.recycle()
                    b.recycle()
                }
            }
            PhoneClip(id, uri, start, dur, thumbFile.takeIf { it.isFile })
        } finally {
            r.release()
        }
    }.getOrNull()

    /** The gallery's "date taken" (the photo picker provides it); 0 if unknown. */
    private fun dateTaken(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** Still there and readable. */
    fun exists(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    /** "20261005T123456.000Z" (the container's creation time, UTC) → ms; 0 if missing. Pure. */
    fun parseDate(text: String?): Long {
        val t = text?.trim().orEmpty()
        if (t.length < 15 || t.startsWith("1904") || t.startsWith("1970")) return 0
        return runCatching {
            LocalDateTime.parse(t.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(0)
    }

    /**
     * A phone video's bits: its talking parts when it has captions, otherwise riding bits of 4 s
     * spread through it (one per ~12 s, at most 6), so long videos give Studio choices. Pure.
     */
    fun bits(clip: PhoneClip, lines: List<CaptionLine>, speedAt: (Long) -> Double): List<Bit> {
        val base = StudioPlanner.bitsOf(clip.id, clip.startMillis, clip.durationMs, lines, speedAt)
        val talking = base.filter { it.talking }
        if (talking.isNotEmpty() || clip.durationMs < 12_000) return base.map { it.copy(source = clip.uri.toString(), punch = it.punch + 0.5f) }
        val n = (clip.durationMs / 12_000).toInt().coerceIn(1, 6)
        return (0 until n).map { k ->
            val len = 4_000L
            val focus = clip.durationMs * (2 * k + 1) / (2 * n)
            val a = (focus - len / 2).coerceIn(0, clip.durationMs - len)
            Bit("${clip.id}#s$k", clip.id, clip.durationMs, a, a + len, clip.startMillis + a, emptyList(), speedAt(clip.startMillis + a), 1.5f, source = clip.uri.toString())
        }
    }
}
