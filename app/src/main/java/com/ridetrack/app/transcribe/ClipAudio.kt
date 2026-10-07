package com.ridetrack.app.transcribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** A clip's sound on its own (AAC in .m4a), a fraction of the video's size, to send for transcription. */
object ClipAudio {
    /** Writes the audio of [video] to [out]; false if the clip has no sound. */
    fun extract(video: File, out: File): Boolean = extract(out) { it.setDataSource(video.path) }

    /** The same for a video from the phone's gallery. */
    fun extract(context: Context, video: Uri, out: File): Boolean = extract(out) { it.setDataSource(context, video, null) }

    private fun extract(out: File, open: (MediaExtractor) -> Unit): Boolean {
        val ex = MediaExtractor()
        try {
            open(ex)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return false
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val dst = mux.addTrack(format)
                mux.start()
                val buf = ByteBuffer.allocate(256 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(dst, buf, info)
                    ex.advance()
                }
                mux.stop()
            } finally {
                runCatching { mux.release() }
            }
            return out.length() > 0
        } finally {
            ex.release()
        }
    }
}
