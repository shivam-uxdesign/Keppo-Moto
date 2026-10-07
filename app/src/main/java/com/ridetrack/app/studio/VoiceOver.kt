package com.ridetrack.app.studio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A voice-over take: raw 16-bit mono PCM in [file], starting [startMs] into the Reel; [lines] its captions (from its own start). */
data class VoiceTake(val startMs: Long, val durMs: Long, val file: File, val lines: List<CaptionLine> = emptyList())

/** Records the rider's voice-over with the phone's microphone (the Reel plays muted meanwhile). */
class VoiceRecorder {
    @Volatile private var running = false

    /** Records into [file] until [stop]; returns the length in ms. Call from a coroutine; needs the mic permission. */
    @SuppressLint("MissingPermission")
    suspend fun record(file: File): Long = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        val buf = ByteArray(maxOf(min, 4096))
        var bytes = 0L
        running = true
        try {
            rec.startRecording()
            file.outputStream().buffered().use { out ->
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) { out.write(buf, 0, n); bytes += n }
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        bytes / 2 * 1000 / RATE
    }

    fun stop() { running = false }

    companion object {
        const val RATE = 44_100

        /** One take as a WAV file (for Gemini's captions). */
        fun takeWav(take: VoiceTake, out: File): File = mix(listOf(take.copy(startMs = 0)), take.durMs, out)

        /** All takes laid on one silent track as long as the Reel, each at its start; a later take wins where they overlap. */
        fun mix(takes: List<VoiceTake>, totalMs: Long, out: File): File {
            val n = (totalMs * RATE / 1000).toInt()
            val pcm = ShortArray(n)
            for (t in takes) {
                val raw = runCatching { t.file.readBytes() }.getOrNull() ?: continue
                val sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val at = (t.startMs * RATE / 1000).toInt()
                val len = minOf(sb.remaining(), n - at)
                for (i in 0 until len) pcm[at + i] = sb.get(i)
            }
            RandomAccessFile(out, "rw").use { f ->
                f.setLength(0)
                val data = n * 2
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
                h.put("data".toByteArray()).putInt(data)
                f.write(h.array())
                val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
                body.asShortBuffer().put(pcm)
                f.write(body.array())
            }
            return out
        }
    }
}
