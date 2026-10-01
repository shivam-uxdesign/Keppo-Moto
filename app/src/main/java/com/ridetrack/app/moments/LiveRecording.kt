package com.ridetrack.app.moments

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ridetrack.telemetry.moments.EncodedSample
import com.ridetrack.telemetry.moments.LiveTimeline
import com.ridetrack.telemetry.moments.RollingBuffer
import java.io.File
import java.nio.ByteBuffer

/**
 * A video written to disk while it's filmed, so it can run for minutes (the clip buffer
 * only holds ~45 s). Fed the buffered lead-in first, then every new encoded sample.
 * Pausing leaves no gap in the file. Thread-safe: encoders write from their own threads.
 */
class LiveRecording(val file: File, videoFormat: MediaFormat, audioFormat: MediaFormat?, rotationDegrees: Int) {
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val timeline = LiveTimeline()
    private val info = MediaCodec.BufferInfo()
    private val videoTrack: Int
    private val audioTrack: Int
    private var closed = false
    private var wroteVideo = false

    /** Wall time of the first frame in the file. */
    @Volatile var firstFrameMillis: Long? = null
        private set

    /** A write failed (disk full, bad sample); the file is finished at the next stop. */
    @Volatile var failed: Throwable? = null
        private set

    init {
        muxer.setOrientationHint(((rotationDegrees % 360) + 360) % 360)
        videoTrack = muxer.addTrack(videoFormat)
        audioTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
        muxer.start()
    }

    /** The buffered lead-in, in time order. */
    fun seed(video: List<EncodedSample>, audio: List<EncodedSample>) {
        RollingBuffer.interleave(video, audio).forEach { (isVideo, s) -> write(isVideo, s) }
    }

    @Synchronized
    fun write(isVideo: Boolean, s: EncodedSample) {
        if (closed || failed != null) return
        val pts = if (isVideo) timeline.video(s.wallMicros, s.keyFrame) else if (audioTrack >= 0) timeline.audio(s.wallMicros) else null
        if (pts == null) return
        if (isVideo && firstFrameMillis == null) firstFrameMillis = s.wallMicros / 1000
        info.set(0, s.data.size, pts, if (s.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        try {
            muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, ByteBuffer.wrap(s.data), info)
            if (isVideo) wroteVideo = true
        } catch (e: Exception) {
            failed = e
        }
    }

    @Synchronized fun pause() = timeline.pause()

    @Synchronized fun resume() = timeline.resume()

    /** Closes the file; its length in ms, or null (and no file) when no video got in. */
    @Synchronized
    fun finish(): Long? {
        if (closed) return null
        closed = true
        val ok = wroteVideo && runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        if (!ok) {
            file.delete()
            return null
        }
        return timeline.lengthMicros / 1000
    }
}
