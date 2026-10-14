package com.ridetrack.app.studio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.ridetrack.telemetry.moments.EngineNoise
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cleans an engine track: each horn's own pitches (and their first overtone) taken out with
 * narrow notch filters for as long as it sounds, so the engine around them stays; each bump
 * softened with a short dip. [noises] are in ms into the source, which this item starts [inMs]
 * into; [choices] says what to do with each (by its start; default [NoiseChoice.REDUCE]).
 */
@UnstableApi
class EngineCleanProcessor(
    private val noises: List<EngineNoise.Noise>,
    private val choices: Map<Long, NoiseChoice>,
    private val inMs: Long,
) : BaseAudioProcessor() {
    private var frames = 0L
    /** Per noise, per channel: its notch filters (made when the format is known). */
    private var filters: List<List<List<Notch>>> = emptyList()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val fmt = inputAudioFormat
        filters = noises.map { n ->
            List(fmt.channelCount) {
                n.freqsHz.flatMap { listOf(it, it * 2) }.filter { it > 40f && it < fmt.sampleRate / 2.2f }.map { Notch(fmt.sampleRate, it) }
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (filters.size != noises.size) onFlush()
        val fmt = inputAudioFormat
        val ch = fmt.channelCount
        val float = fmt.encoding == C.ENCODING_PCM_FLOAT
        val n = inputBuffer.remaining() / ((if (float) 4 else 2) * ch)
        val out = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            val ms = inMs + (frames + i) * 1000L / fmt.sampleRate
            // The noise sounding now (if any), and how far into its fade-in/out.
            var active = -1
            var w = 0f
            for (k in noises.indices) {
                val nz = noises[k]
                val wk = weight(ms, nz.startMs, nz.endMs)
                if (wk > w) { w = wk; active = k }
            }
            val choice = if (active >= 0) choices[noises[active].startMs] ?: NoiseChoice.REDUCE else NoiseChoice.KEEP
            repeat(ch) { c ->
                val x = if (float) inputBuffer.getFloat() else inputBuffer.getShort() / 32768f
                val y = when {
                    active < 0 || choice == NoiseChoice.KEEP -> x
                    choice == NoiseChoice.MUTE -> x * (1 - w)
                    noises[active].kind == EngineNoise.Kind.BUMP -> x * (1 - BUMP_DIP * w)
                    else -> {
                        var f = x
                        filters.getOrNull(active)?.getOrNull(c)?.forEach { f = it.step(f) }
                        x + (f - x) * w
                    }
                }
                if (float) out.putFloat(y) else out.putShort((y * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        frames += n
        out.flip()
    }

    override fun onReset() {
        frames = 0
        filters = emptyList()
    }

    /** 0..1: inside [from, to] with [RAMP_MS] fades at both ends. */
    private fun weight(ms: Long, from: Long, to: Long): Float = when {
        ms < from - RAMP_MS || ms > to + RAMP_MS -> 0f
        ms < from -> 1f - (from - ms).toFloat() / RAMP_MS
        ms > to -> 1f - (ms - to).toFloat() / RAMP_MS
        else -> 1f
    }

    /** A narrow notch (RBJ cookbook), Q [Q]. */
    private class Notch(rate: Int, hz: Float) {
        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        init {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * Q)
            val a0 = 1 + alpha
            b0 = (1 / a0).toFloat(); b1 = (-2 * cos(w) / a0).toFloat(); b2 = (1 / a0).toFloat()
            a1 = (-2 * cos(w) / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
        }

        fun step(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private companion object {
        const val Q = 6.0
        const val RAMP_MS = 60L
        /** A bump is turned down to a fifth: gone as a thud, the engine still there. */
        const val BUMP_DIP = 0.8f
    }
}
