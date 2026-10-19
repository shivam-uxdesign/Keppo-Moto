package com.ridetrack.app.moments

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * What's in the picture (Sky, Road, Mountain…), from Google's on-phone labeller on three frames
 * of a clip. Free and offline; only confident labels are kept.
 */
object SceneLabels {
    private const val MIN_CONFIDENCE = 0.75f
    /** Labels that say nothing useful about a ride clip. */
    private val SKIP = setOf("Vehicle", "Car", "Motorcycle", "Helmet", "Wheel", "Tire", "Jacket", "Selfie", "Screenshot", "Font", "Darkness")

    suspend fun of(clip: File): List<String> {
        val frames = frames(clip)
        if (frames.isEmpty()) return emptyList()
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build())
        return try {
            val seen = LinkedHashMap<String, Float>()
            frames.forEach { b ->
                runCatching { labeler.process(InputImage.fromBitmap(b, 0)).await() }.getOrNull().orEmpty().forEach { l ->
                    if (l.text !in SKIP) seen[l.text] = maxOf(seen[l.text] ?: 0f, l.confidence)
                }
                b.recycle()
            }
            seen.entries.sortedByDescending { it.value }.take(5).map { it.key }
        } finally {
            labeler.close()
        }
    }

    private fun frames(clip: File): List<Bitmap> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(clip.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return emptyList()
            listOf(0.25, 0.5, 0.75).mapNotNull { f ->
                r.getScaledFrameAtTime((ms * f * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }
}
