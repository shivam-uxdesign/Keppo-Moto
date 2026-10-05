package com.ridetrack.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Burns the moment overlay into a clip, with numbers that follow the footage: the overlay
 * is redrawn about 10 times a second from the ride's telemetry at each frame's time.
 * Audio is kept as is. With a [TrimRange] only that part is written; with no overlay
 * ([draw] null) it's the plain clip, cut.
 */
@UnstableApi
class MomentVideoExporter(private val context: Context) {
    /**
     * [draw] paints the overlay for a wall-clock time onto a frame-sized canvas; the clip's
     * first frame was filmed at [videoStartMillis]. Reports 0–100 through [onProgress].
     * Returns the output file.
     */
    suspend fun export(
        input: File,
        output: File,
        videoStartMillis: Long,
        draw: ((canvas: Canvas, width: Int, height: Int, timeMillis: Long) -> Unit)?,
        onProgress: (Int) -> Unit,
        trim: TrimRange? = null,
    ): Result<File> {
        val (w, h) = withContext(Dispatchers.IO) { displaySize(input) } ?: return Result.failure(IllegalStateException("Unreadable clip"))
        output.delete()
        val overlay = draw?.let { paint -> object : BitmapOverlay() {
            // Frames arrive upright at display size (Transformer applies the rotation first),
            // so one frame-sized bitmap covers the video exactly.
            private val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            private val canvas = Canvas(bitmap)
            private var lastBucket = Long.MIN_VALUE
            /** First frame's timestamp: trimmed frames may or may not start at 0, so count from it. */
            private var firstUs: Long? = null

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val base = firstUs ?: presentationTimeUs.also { firstUs = it }
                val sinceStartUs = (presentationTimeUs - base).coerceAtLeast(0)
                val bucket = sinceStartUs / 100_000 // redraw every 100 ms of video
                if (bucket != lastBucket) {
                    lastBucket = bucket
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    paint(canvas, w, h, videoStartMillis + (trim?.startMillis ?: 0L) + sinceStartUs / 1000)
                }
                return bitmap
            }
        } }
        val media = MediaItem.Builder().setUri(android.net.Uri.fromFile(input)).apply {
            if (trim != null && !trim.isWhole) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trim.startMillis)
                        .setEndPositionMs(trim.endMillis)
                        .build(),
                )
            }
        }.build()
        val item = EditedMediaItem.Builder(media).apply {
            if (overlay != null) setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
        }.build()

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Result.success(output))
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            output.delete()
                            if (cont.isActive) cont.resume(Result.failure(exportException))
                        }
                    })
                    .build()
                transformer.start(item, output.path)
                val poll = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                    val holder = ProgressHolder()
                    while (cont.isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        delay(200)
                    }
                }
                cont.invokeOnCancellation {
                    poll.cancel()
                    transformer.cancel()
                    output.delete()
                }
            }
        }
    }

    /** Width × height as the clip is displayed (rotation applied). */
    private fun displaySize(file: File): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 != 0) h to w else w to h
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
