package com.ridetrack.app.moments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.ridetrack.telemetry.moments.EncodedSample
import com.ridetrack.telemetry.moments.RollingBuffer
import java.io.File
import java.nio.ByteBuffer

/** Writes clips and photos to disk. Blocking; call off the main thread. */
object ClipWriter {
    private const val THUMB_EDGE = 480

    /**
     * Muxes samples into an MP4, writing video and audio interleaved in time order.
     * Returns the clip length in ms, or null if there was no video.
     */
    fun writeMp4(
        file: File,
        videoFormat: MediaFormat,
        audioFormat: MediaFormat?,
        video: List<EncodedSample>,
        audio: List<EncodedSample>,
        rotationDegrees: Int,
    ): Long? {
        if (video.isEmpty()) return null
        val base = video.first().wallMicros
        val audioInClip = if (audioFormat != null) audio.filter { it.wallMicros >= base } else emptyList()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(((rotationDegrees % 360) + 360) % 360)
            val vTrack = muxer.addTrack(videoFormat)
            val aTrack = if (audioFormat != null && audioInClip.isNotEmpty()) muxer.addTrack(audioFormat) else -1
            muxer.start()
            val info = MediaCodec.BufferInfo()
            var lastV = -1L
            var lastA = -1L
            for ((isVideo, s) in RollingBuffer.interleave(video, if (aTrack >= 0) audioInClip else emptyList())) {
                val last = if (isVideo) lastV else lastA
                val pts = maxOf(s.wallMicros - base, last + 1)
                info.set(0, s.data.size, pts, if (s.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(if (isVideo) vTrack else aTrack, ByteBuffer.wrap(s.data), info)
                if (isVideo) lastV = pts else lastA = pts
            }
            muxer.stop()
            return (video.last().wallMicros - base) / 1000
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            runCatching { muxer.release() }
        }
    }

    /** Frame at [atMillis] into the clip, upright and scaled down, saved as JPEG. */
    fun videoThumbnail(video: File, atMillis: Long, out: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.path)
            val frame = r.getFrameAtTime(atMillis * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return false
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // Some devices already apply the rotation to the frame; only rotate if it's still sideways.
            val sideways = (rotation == 90 || rotation == 270) && frame.width > frame.height
            val upright = if (sideways) rotate(frame, rotation) else frame
            saveJpeg(scale(upright, THUMB_EDGE), out, 85)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Bakes the EXIF orientation of a captured JPEG into its pixels (so every viewer shows
     * it upright) and writes a small thumbnail next to it.
     */
    fun finishPhoto(photo: File, thumb: File): Boolean = try {
        val orientation = ExifInterface(photo.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val mirrored = orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        val full = BitmapFactory.decodeFile(photo.path) ?: throw IllegalStateException("undecodable")
        var upright = if (degrees != 0) rotate(full, degrees) else full
        if (mirrored) upright = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { preScale(-1f, 1f) }, true)
        if (degrees != 0 || mirrored) saveJpeg(upright, photo, 92)
        saveJpeg(scale(upright, THUMB_EDGE), thumb, 85)
        true
    } catch (e: Exception) {
        false
    }

    /** Decodes an image scaled to about [maxEdge] (for viewers and map pins). */
    fun load(file: File, maxEdge: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun rotate(b: Bitmap, degrees: Int): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun scale(b: Bitmap, edge: Int): Bitmap {
        val f = edge.toFloat() / maxOf(b.width, b.height)
        if (f >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun saveJpeg(b: Bitmap, out: File, quality: Int) {
        out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
