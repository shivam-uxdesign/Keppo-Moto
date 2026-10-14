package com.ridetrack.telemetry.moments

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Horns and bumps in a stretch of engine sound, so a Reel can turn them down. Pure.
 *
 * A horn is a steady, sharp tone (one pitch, or two for a two-tone horn) that starts suddenly
 * and isn't one of the engine's own pitches, which follow the revs. A bump is a bang across all
 * pitches that dies away within a quarter of a second while the engine carries on.
 */
object EngineNoise {
    enum class Kind { HORN, BUMP }

    /** [startMs]..[endMs] into the sound; [freqsHz]: a horn's pitches (empty for a bump). */
    data class Noise(val kind: Kind, val startMs: Long, val endMs: Long, val freqsHz: List<Float> = emptyList())

    private const val FRAME = 512
    private const val HOP = 256

    /**
     * The horns and bumps in [pcm] (mono, [rate] Hz; about 8 kHz is plenty). [enginePitchAt]: the
     * engine's pitch (Hz) [ms] in, 0 or null where unknown; its own pitches aren't horns.
     */
    fun find(pcm: ShortArray, rate: Int, enginePitchAt: (Long) -> Float? = { null }): List<Noise> {
        if (pcm.size < FRAME * 4 || rate <= 0) return emptyList()
        val frames = (pcm.size - FRAME) / HOP + 1
        val hopMs = HOP * 1000.0 / rate
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        val binHz = rate.toDouble() / FRAME
        val lo = (300 / binHz).toInt().coerceAtLeast(1)
        val hi = (2_000 / binHz).toInt().coerceAtMost(FRAME / 2 - 1)
        val energyDb = FloatArray(frames)
        val bandDb = FloatArray(frames)
        val tone = arrayOfNulls<List<Float>>(frames)
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        for (f in 0 until frames) {
            var e = 0.0
            for (i in 0 until FRAME) {
                val x = pcm[f * HOP + i] / 32768.0
                e += x * x
                re[i] = x * window[i]
                im[i] = 0.0
            }
            energyDb[f] = (10 * log10(e / FRAME + 1e-12)).toFloat()
            fft(re, im)
            val mag = DoubleArray(hi - lo + 1) { k -> sqrt(re[lo + k] * re[lo + k] + im[lo + k] * im[lo + k]) }
            bandDb[f] = (20 * log10(mag.sum() / mag.size + 1e-12)).toFloat()
            val median = mag.sorted()[mag.size / 2] + 1e-9
            // Sharp peaks well above the band's floor (a horn stands 20 dB out; an engine's broad hum doesn't).
            val peaks = mag.indices.filter { k ->
                mag[k] / median >= TONE_RATIO && (k == 0 || mag[k] >= mag[k - 1]) && (k == mag.size - 1 || mag[k] >= mag[k + 1])
            }.sortedByDescending { mag[it] }.take(2).map { k -> ((lo + k) * binHz).toFloat() }
            val engine = enginePitchAt((f * hopMs).toLong())?.takeIf { it > 0f }
            tone[f] = peaks.filter { p -> engine == null || !isHarmonic(p, engine) }.takeIf { it.isNotEmpty() }
        }
        val horns = horns(tone, bandDb, hopMs)
        val bumps = bumps(energyDb, hopMs).filter { b -> horns.none { h -> b.startMs < h.endMs && b.endMs > h.startMs } }
        return (horns + bumps).sortedBy { it.startMs }
    }

    private fun horns(tone: Array<List<Float>?>, bandDb: FloatArray, hopMs: Double): List<Noise> {
        val out = ArrayList<Noise>()
        var f = 0
        while (f < tone.size) {
            val first = tone[f]?.first()
            if (first == null) { f++; continue }
            var end = f
            var misses = 0
            var g = f + 1
            while (g < tone.size && misses <= 1) {
                val p = tone[g]
                if (p != null && p.any { abs(it - first) / first <= SAME_PITCH }) { end = g; misses = 0 } else misses++
                g++
            }
            val lengthMs = (end - f + 1) * hopMs
            val before = (f - (1_000 / hopMs).toInt()).coerceAtLeast(0) until f
            val onset = if (before.isEmpty()) 99f else bandDb[f] - before.map { bandDb[it] }.sorted()[before.count() / 2]
            if (lengthMs >= MIN_HORN_MS && onset >= HORN_ONSET_DB) {
                val freqs = (f..end).flatMap { tone[it].orEmpty() }
                    .groupBy { (it / 10).roundToInt() }.values.sortedByDescending { it.size }.take(2).map { it.average().toFloat() }
                out += Noise(Kind.HORN, (f * hopMs).toLong() - EDGE_MS, ((end + 1) * hopMs).toLong() + EDGE_MS, freqs)
            }
            f = end + 1
        }
        return out
    }

    private fun bumps(energyDb: FloatArray, hopMs: Double): List<Noise> {
        val out = ArrayList<Noise>()
        val back = (300 / hopMs).toInt().coerceAtLeast(3)
        val settle = (BUMP_MAX_MS / hopMs).toInt().coerceAtLeast(2)
        var f = back
        while (f < energyDb.size) {
            val base = (f - back until f).map { energyDb[it] }.sorted()[back / 2]
            if (energyDb[f] - base >= BUMP_JUMP_DB) {
                val calm = (f + 1..(f + settle).coerceAtMost(energyDb.size - 1)).firstOrNull { energyDb[it] - base <= BUMP_SETTLE_DB }
                if (calm != null) {
                    out += Noise(Kind.BUMP, (f * hopMs).toLong() - EDGE_MS, ((calm + 1) * hopMs).toLong() + EDGE_MS)
                    f = calm + 1
                    continue
                }
            }
            f++
        }
        return out
    }

    /** [p] is one of the engine's pitches: a whole multiple of its firing pitch. */
    private fun isHarmonic(p: Float, engine: Float): Boolean {
        val n = (p / engine).roundToInt()
        return n in 1..24 && abs(p - n * engine) / p <= 0.03f
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val t = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }

    private const val TONE_RATIO = 10.0
    private const val SAME_PITCH = 0.04f
    private const val MIN_HORN_MS = 250.0
    private const val HORN_ONSET_DB = 6f
    private const val BUMP_JUMP_DB = 12f
    private const val BUMP_SETTLE_DB = 4f
    private const val BUMP_MAX_MS = 250.0
    /** A little either side, so the fix starts before the noise and ends after it. */
    private const val EDGE_MS = 40L
}
