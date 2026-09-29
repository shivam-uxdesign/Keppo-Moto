package com.ridetrack.app.moments

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.ridetrack.telemetry.moments.EncodedSample
import com.ridetrack.telemetry.moments.RollingBuffer

/**
 * H.264 encoder fed by the camera through [inputSurface]. Encoded frames go straight into
 * the [RollingBuffer], stamped with wall-clock time (camera timestamps use an unspecified
 * clock, so the offset is measured once per encoder from the first frame).
 */
class VideoEncoder(
    size: Size,
    bitrate: Int,
    private val buffer: RollingBuffer,
    /** Called (on the encoder thread) with the output format, and once on the first frame. */
    private val onFormat: (MediaFormat) -> Unit = {},
    private val onFirstFrame: () -> Unit = {},
) {
    private val thread = HandlerThread("moments-video").apply { start() }
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface

    @Volatile
    var format: MediaFormat? = null
        private set

    private var offsetMicros: Long? = null

    /** Encoded frames so far, and when the latest one arrived (wall ms); for the watchdog. */
    @Volatile var frames = 0L
        private set
    @Volatile var lastFrameMillis = 0L
        private set

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            // A keyframe every second: clips can start within 1 s of the requested time.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            val out = codec.getOutputBuffer(index)
                            if (out != null) {
                                val bytes = ByteArray(info.size)
                                out.position(info.offset)
                                out.get(bytes, 0, info.size)
                                val offset = offsetMicros ?: (System.currentTimeMillis() * 1000 - info.presentationTimeUs).also { offsetMicros = it }
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                buffer.addVideo(EncodedSample(info.presentationTimeUs + offset, key, bytes))
                                lastFrameMillis = System.currentTimeMillis()
                                if (frames++ == 0L) onFirstFrame()
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: IllegalStateException) {
                        // Codec released while a callback was in flight.
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Video encoder error", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    this@VideoEncoder.format = format
                    onFormat(format)
                }
            },
            Handler(thread.looper),
        )
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        inputSurface.release()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "MomentsVideo"
    }
}
