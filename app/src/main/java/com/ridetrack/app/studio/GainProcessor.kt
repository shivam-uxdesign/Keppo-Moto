package com.ridetrack.app.studio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sets the volume over time: [gainAt] gets µs since this item's sound started and returns 0..1.
 * Used to mute the title and stats, soften riding noise, and dip the song while the rider talks.
 */
@UnstableApi
class GainProcessor(private val gainAt: (Long) -> Float) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val ch = fmt.channelCount
        val float = fmt.encoding == C.ENCODING_PCM_FLOAT
        val n = inputBuffer.remaining() / ((if (float) 4 else 2) * ch)
        val out = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            val g = gainAt((frames + i) * 1_000_000L / fmt.sampleRate)
            repeat(ch) {
                if (float) out.putFloat(inputBuffer.getFloat() * g)
                else out.putShort((inputBuffer.getShort() * g).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        frames += n
        out.flip()
    }

    override fun onReset() {
        frames = 0
    }

    companion object {
        /** [level] inside [ranges] (ms), 1 outside, with 150 ms ramps. */
        fun ducking(ranges: List<LongRange>, level: Float, rampMs: Long = 150): (Long) -> Float = { us ->
            val ms = us / 1000
            var g = 1f
            for (r in ranges) {
                val k = when {
                    ms in r -> 1f
                    ms < r.first && r.first - ms < rampMs -> 1f - (r.first - ms).toFloat() / rampMs
                    ms > r.last && ms - r.last < rampMs -> 1f - (ms - r.last).toFloat() / rampMs
                    else -> 0f
                }
                g = minOf(g, 1f - (1f - level) * k)
            }
            g
        }
    }
}
