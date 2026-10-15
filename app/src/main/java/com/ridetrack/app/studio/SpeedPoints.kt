package com.ridetrack.app.studio

/** One point of a clip's speed curve: [at] 0..1 through the clip's part, [speed] 0.25–4×. */
data class SpeedPoint(val at: Float, val speed: Float)

/**
 * Speed ramps drawn as points: the speed glides in a straight line from one point to the next.
 * The renderer plays them as short steady steps ([steps]), the same shape the fixed ramps use.
 * The ride presets put the slow and fast parts where the ride's own moments are. Pure.
 */
object SpeedPoints {
    const val MIN = 0.25f
    const val MAX = 4f
    /** Steps the curve is played in: enough that a glide looks smooth. */
    private const val STEPS = 24

    /** The speed at [f] (0..1) through the part. */
    fun at(points: List<SpeedPoint>, f: Float): Float {
        if (points.isEmpty()) return 1f
        if (f <= points.first().at) return points.first().speed
        if (f >= points.last().at) return points.last().speed
        val b = points.indexOfFirst { it.at >= f }
        val p1 = points[b - 1]
        val p2 = points[b]
        val u = (f - p1.at) / (p2.at - p1.at).coerceAtLeast(1e-4f)
        return p1.speed + (p2.speed - p1.speed) * u
    }

    /** As (end fraction, speed) steps, for [Speed] and the renderer. */
    fun steps(points: List<SpeedPoint>): List<Pair<Float, Float>> =
        (1..STEPS).map { k -> k / STEPS.toFloat() to at(points, (k - 0.5f) / STEPS) }

    /** Sorted, inside 0..1 and the speed range, one point per place; the ends always there. */
    fun clean(points: List<SpeedPoint>): List<SpeedPoint> {
        val inside = points.map { SpeedPoint(it.at.coerceIn(0f, 1f), it.speed.coerceIn(MIN, MAX)) }.sortedBy { it.at }
        val out = ArrayList<SpeedPoint>()
        inside.forEach { p -> if (out.isNotEmpty() && p.at - out.last().at < 0.005f) out[out.size - 1] = p else out += p }
        if (out.isEmpty()) return listOf(SpeedPoint(0f, 1f), SpeedPoint(1f, 1f))
        if (out.first().at > 0f) out.add(0, SpeedPoint(0f, out.first().speed))
        if (out.last().at < 1f) out += SpeedPoint(1f, out.last().speed)
        return out
    }

    /** Ramps that follow the ride. */
    enum class Preset(val label: String) {
        LEAN("Slow at the lean"),
        DOWNSHIFT("Slow at the downshift"),
        STRAIGHTS("Fast straights"),
        PUNCH("Punch in"),
    }

    /**
     * [preset]'s points for a clip part starting [inMs] into its recording and [sourceMs] long,
     * with the ride's [marks] (ms into the recording). Null when the clip has no such moment.
     */
    fun preset(preset: Preset, marks: List<RideMark>, inMs: Long, sourceMs: Long): List<SpeedPoint>? {
        if (sourceMs <= 0) return null
        fun f(ms: Long) = ((ms - inMs).toFloat() / sourceMs)
        fun s(sec: Double) = (sec * 1000 / sourceMs).toFloat()
        val inside = marks.filter { it.clipMs in inMs..inMs + sourceMs }
        return when (preset) {
            Preset.LEAN -> inside.firstOrNull { it.kind == RideMark.Kind.LEAN }?.let { m ->
                val t = f(m.clipMs)
                clean(listOf(SpeedPoint(0f, 1f), SpeedPoint(t - s(1.0), 1f), SpeedPoint(t - s(0.4), 0.5f), SpeedPoint(t + s(0.4), 0.5f), SpeedPoint(t + s(1.0), 1f), SpeedPoint(1f, 1f)))
            }
            Preset.DOWNSHIFT -> inside.filter { it.kind == RideMark.Kind.DOWNSHIFT }.takeIf { it.isNotEmpty() }?.let { ds ->
                clean(listOf(SpeedPoint(0f, 1f), SpeedPoint(1f, 1f)) + ds.flatMap { m ->
                    val t = f(m.clipMs)
                    listOf(SpeedPoint(t - s(0.8), 1f), SpeedPoint(t - s(0.3), 0.5f), SpeedPoint(t + s(0.3), 0.5f), SpeedPoint(t + s(0.8), 1f))
                })
            }
            // Nothing happening: twice as fast; around each moment, back to normal.
            Preset.STRAIGHTS -> clean(listOf(SpeedPoint(0f, 2f), SpeedPoint(1f, 2f)) + inside.flatMap { m ->
                val t = f(m.clipMs)
                listOf(SpeedPoint(t - s(2.0), 2f), SpeedPoint(t - s(1.5), 1f), SpeedPoint(t + s(1.5), 1f), SpeedPoint(t + s(2.0), 2f))
            })
            // The classic Reel ramp: normal, very slow on the moment, normal again.
            Preset.PUNCH -> {
                val t = inside.firstOrNull()?.let { f(it.clipMs) } ?: 0.5f
                clean(listOf(SpeedPoint(0f, 1f), SpeedPoint(t - s(0.6), 1f), SpeedPoint(t - s(0.2), 0.3f), SpeedPoint(t + s(0.4), 0.3f), SpeedPoint(t + s(0.8), 1f), SpeedPoint(1f, 1f)))
            }
        }
    }
}
