package com.ridetrack.app.studio

import com.ridetrack.telemetry.model.TelemetrySample
import kotlin.math.abs

/** A ride moment in a clip, at [clipMs] (ms into the clip's recording). */
data class RideMark(val kind: Kind, val clipMs: Long, val label: String) {
    enum class Kind(val title: String) { DOWNSHIFT("Downshift"), BRAKING("Hard braking"), TOP("Top speed"), LEAN("Lean") }
}

/** A clip's speed over its whole recording (clip ms → km/h) and its ride moments. */
data class RideTrace(val speed: List<Pair<Long, Float>>, val marks: List<RideMark>)

/**
 * The editor's ride row: for one clip, its speed line and the moments worth finding (downshifts
 * from the engine sound, hard braking, the top speed, a deep lean), over the whole recording
 * so moments outside the trim show too. Pure.
 */
object EditorRide {
    const val BRAKING_G = -0.45
    const val LEAN_DEG = 25.0
    private const val APART_MS = 3_000L
    private const val STEP_MS = 250L

    /** [clipStartMillis]: wall time of the clip's first frame. [downshifts]: ms into the clip. */
    fun trace(samples: List<TelemetrySample>, clipStartMillis: Long, clipDurationMs: Long, downshifts: List<Long> = emptyList()): RideTrace {
        val end = clipStartMillis + clipDurationMs
        val inClip = samples.filter { it.timeMillis in clipStartMillis..end }
        val speed = ArrayList<Pair<Long, Float>>()
        var t = 0L
        var k = 0
        while (t <= clipDurationMs && inClip.isNotEmpty()) {
            val wall = clipStartMillis + t
            while (k < inClip.size - 1 && inClip[k + 1].timeMillis <= wall) k++
            inClip[k].speedMps?.let { speed += t to (it * 3.6).toFloat() }
            t += STEP_MS
        }
        val marks = ArrayList<RideMark>()
        downshifts.filter { it in 0..clipDurationMs }.forEach { marks += RideMark(RideMark.Kind.DOWNSHIFT, it, "Downshift") }
        // Hard braking: the strongest reading of each stretch, at least 3 s apart.
        peaks(inClip.filter { (it.longitudinalG ?: 0.0) <= BRAKING_G }, { -(it.longitudinalG ?: 0.0) }).forEach { s ->
            marks += RideMark(RideMark.Kind.BRAKING, s.timeMillis - clipStartMillis, "Hard braking · ${"%.1f".format(java.util.Locale.US, -(s.longitudinalG ?: 0.0))} g")
        }
        peaks(inClip.filter { abs(it.leanDeg ?: 0.0) >= LEAN_DEG }, { abs(it.leanDeg ?: 0.0) }).forEach { s ->
            val l = s.leanDeg ?: 0.0
            marks += RideMark(RideMark.Kind.LEAN, s.timeMillis - clipStartMillis, "Lean · ${abs(l).toInt()}° ${if (l < 0) "L" else "R"}")
        }
        inClip.filter { it.speedMps != null }.maxByOrNull { it.speedMps!! }?.let { s ->
            val v = s.speedMps ?: 0.0
            if (v > 1.0) marks += RideMark(RideMark.Kind.TOP, s.timeMillis - clipStartMillis, "Top speed · ${(v * 3.6).toInt()} km/h")
        }
        return RideTrace(speed, marks.sortedBy { it.clipMs })
    }

    /** The highest of each run of samples, runs at least [APART_MS] apart. */
    private fun peaks(hits: List<TelemetrySample>, value: (TelemetrySample) -> Double): List<TelemetrySample> {
        val out = ArrayList<TelemetrySample>()
        var run = ArrayList<TelemetrySample>()
        hits.forEach { s ->
            if (run.isNotEmpty() && s.timeMillis - run.last().timeMillis > APART_MS) { run.maxByOrNull(value)?.let { out += it }; run = ArrayList() }
            run += s
        }
        run.maxByOrNull(value)?.let { out += it }
        return out
    }
}
