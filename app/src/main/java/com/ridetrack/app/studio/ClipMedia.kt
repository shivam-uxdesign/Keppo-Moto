package com.ridetrack.app.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** Pictures and copies made from clips: freeze frames, reversed parts, and what a clip is (size, frame rate). */
@OptIn(UnstableApi::class)
object ClipMedia {
    /** The longest part that can be reversed (it's read frame by frame). */
    const val MAX_REVERSE_MS = 10_000L

    /** The frame at [atMs] of [uri], upright, as a JPEG kept with the edit; null if it couldn't be read. */
    suspend fun still(context: Context, uri: Uri, atMs: Long): File? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val frame = upright(r, r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@withContext null)
            val out = File(File(context.filesDir, "studio-stills").apply { mkdirs() }, "still-${UUID.randomUUID().toString().take(8)}.jpg")
            out.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            frame.recycle()
            out
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * [lenMs] of [uri] from [fromMs], played backwards, as a video kept with the edit (no sound).
     * Slow: every frame is read, then they're put together in reverse.
     */
    suspend fun reverse(context: Context, uri: Uri, fromMs: Long, lenMs: Long, onProgress: (Int) -> Unit = {}): File {
        val len = lenMs.coerceAtMost(MAX_REVERSE_MS)
        val work = File(context.cacheDir, "studio-reverse-${UUID.randomUUID().toString().take(6)}").apply { mkdirs() }
        try {
            val frames = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    val list = ArrayList<File>()
                    val step = 1000L / 30
                    var t = len
                    var n = 0
                    while (t >= 0) {
                        val f = r.getScaledFrameAtTime((fromMs + t) * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 1080, 1920)
                        if (f != null) {
                            val up = upright(r, f)
                            val out = File(work, "f${n++}.jpg")
                            out.outputStream().use { up.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                            up.recycle()
                            list += out
                        }
                        onProgress(((len - t) * 70 / len.coerceAtLeast(1)).toInt())
                        t -= step
                    }
                    list
                } finally {
                    runCatching { r.release() }
                }
            }
            require(frames.isNotEmpty()) { "Couldn't read the clip's frames" }
            val items = frames.map { f ->
                EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(f)).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(1000L / 30).build())
                    .setFrameRate(30)
                    .build()
            }
            val out = File(File(context.filesDir, "studio-reversed").apply { mkdirs() }, "rev-${UUID.randomUUID().toString().take(8)}.mp4")
            export(context, Composition.Builder(EditedMediaItemSequence(items)).build(), out)
            onProgress(100)
            return out
        } finally {
            work.deleteRecursively()
        }
    }

    /** [fromMs]..[toMs] of [file] as its own video (a Story part). */
    suspend fun cut(context: Context, file: File, fromMs: Long, toMs: Long, out: File) {
        val item = MediaItem.Builder().setUri(Uri.fromFile(file))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromMs).setEndPositionMs(toMs).build())
            .build()
        export(context, Composition.Builder(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(item).build()))).build(), out)
    }

    /** A video's width, height (as shown, upright) and frame rate; null when it can't be read. */
    fun info(context: Context, uri: Uri): Triple<Int, Int, Float>? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            var w = f.getInteger(MediaFormat.KEY_WIDTH)
            var h = f.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) } else 30f
            Triple(w, h, fps)
        } finally {
            ex.release()
        }
    }.getOrNull()

    /** Turns a frame upright when the retriever left it sideways. */
    private fun upright(r: MediaMetadataRetriever, frame: Bitmap): Bitmap {
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val sideways = (rotation == 90 || rotation == 270) && frame.width > frame.height
        if (!sideways) return frame
        val out = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (out != frame) frame.recycle()
        return out
    }

    private suspend fun export(context: Context, composition: Composition, out: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        out.delete()
                        if (cont.isActive) cont.resumeWith(Result.failure(exportException))
                    }
                })
                .build()
            t.start(composition, out.path)
            cont.invokeOnCancellation { t.cancel(); out.delete() }
        }
    }
}
