package com.ridetrack.app.moments

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD (MIT, github.com/snakers4/silero-vad): how likely a 32 ms window of 16 kHz
 * audio is a human voice, 0..1. Horns, engines, wind and breathing score near 0. Runs on the
 * phone, about 1 ms per window. Not thread-safe: one per audio stream.
 */
class SileroVad private constructor(private val env: OrtEnvironment, private val session: OrtSession) {
    private val state = FloatArray(2 * 128)

    /** [window]: VoiceWindows.CONTEXT + VoiceWindows.WINDOW samples. */
    @Synchronized
    fun probability(window: FloatArray): Float = runCatching {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, window.size.toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16_000L)), longArrayOf()).use { sr ->
                    session.run(mapOf("input" to x, "state" to st, "sr" to sr)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val p = (r[0].value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST")
                        val next = r[1].value as Array<Array<FloatArray>>
                        next[0][0].copyInto(state, 0)
                        next[1][0].copyInto(state, 128)
                        p
                    }
                }
            }
        }
    }.getOrElse {
        Log.w(TAG, "voice detector failed", it)
        1f
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "SileroVad"
        private const val MODEL = "silero_vad.onnx"

        /** Null if the model can't be loaded (then loudness alone decides). */
        fun load(context: Context): SileroVad? = runCatching {
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            SileroVad(env, env.createSession(bytes, opts))
        }.onFailure { Log.e(TAG, "couldn't load the voice detector", it) }.getOrNull()
    }
}
