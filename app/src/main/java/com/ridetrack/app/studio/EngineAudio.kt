package com.ridetrack.app.studio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

/**
 * A clip's engine sound as revs: decoded to mono at about 8 kHz (plenty for an engine's pitch),
 * read with [Revs], and kept next to the clip (".revs.json") so it's worked out once.
 */
object EngineAudio {
    private const val TARGET_RATE = 8_000

    /** The engine's pitch every [Revs.HOP_MS] for the clip at [clip] (its engine file if it has one, else its own sound). */
    fun pitches(context: Context, clip: File?, engine: File?, source: Uri?): FloatArray? {
        val cacheOf = clip ?: return decodeAndRead(context, engine, source)
        val cache = File(cacheOf.parentFile, cacheOf.nameWithoutExtension + ".revs.json")
        read(cache)?.let { return it }
        val p = decodeAndRead(context, engine?.takeIf { it.isFile } ?: clip, null) ?: return null
        runCatching { cache.writeText(JSONObject().put("hopMs", Revs.HOP_MS).put("hz", JSONArray(p.map { it.toDouble() })).toString()) }
        return p
    }

    private fun read(f: File): FloatArray? = runCatching {
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        if (o.optLong("hopMs") != Revs.HOP_MS) return null
        val a = o.getJSONArray("hz")
        FloatArray(a.length()) { a.getDouble(it).toFloat() }
    }.getOrNull()

    private fun decodeAndRead(context: Context, file: File?, uri: Uri?): FloatArray? {
        val (pcm, rate) = decode(context, file, uri) ?: return null
        return Revs.pitches(pcm, rate)
    }

    /** The first audio track as mono 16-bit samples at about [TARGET_RATE] Hz, and that rate; null without sound. */
    fun decode(context: Context, file: File?, uri: Uri?): Pair<ShortArray, Int>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            when {
                file != null -> ex.setDataSource(file.path)
                uri != null -> ex.setDataSource(context, uri, null)
                else -> return null
            }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(mime).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()
            val out = ShortList()
            var step = (rate / TARGET_RATE).coerceAtLeast(1)
            var acc = 0
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    c.outputFormat.let { f -> rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    step = (rate / TARGET_RATE).coerceAtLeast(1)
                } else if (o >= 0) {
                    val sb = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    // Mix to mono and average every [step] samples down (a simple low-pass as it goes).
                    val frames = sb.remaining() / channels.coerceAtLeast(1)
                    for (fIdx in 0 until frames) {
                        var s = 0
                        for (ch in 0 until channels) s += sb.get(fIdx * channels + ch)
                        acc += s / channels.coerceAtLeast(1); count++
                        if (count == step) { out.add((acc / step).toShort()); acc = 0; count = 0 }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (out.size > MAX_SAMPLES) break
                }
            }
            return out.toArray() to rate / step
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    /** A growable list of samples (no boxing). */
    private class ShortList {
        private var a = ShortArray(TARGET_RATE * 10)
        var size = 0
            private set

        fun add(v: Short) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = v
        }

        fun toArray(): ShortArray = a.copyOf(size)
    }

    private const val TIMEOUT_US = 10_000L
    /** At most ten minutes of sound. */
    private const val MAX_SAMPLES = TARGET_RATE * 600
}
