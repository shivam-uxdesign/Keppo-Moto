package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.SpeechGate
import kotlin.math.roundToInt

/**
 * Replays a ride's voice-trace.csv (exported by tester builds) through the speech rules and
 * scores them against when the rider says they talked: the yardstick every change to voice start
 * has to beat (docs/plans/voice-audit.md, Phase 0). Test tooling only.
 */
object VoiceReplay {
    class Row(
        val t: Long,
        val chunkMs: Long,
        val speedKmh: Float?,
        val levelDb: Float?,
        val backgroundDb: Float?,
        val voice: Float?,
        val voiceDewind: Float?,
        val liftDb: Float?,
        val highLiftDb: Float?,
        val wind: Float?,
        val speaking: Boolean,
    )

    /** Reads voice-trace.csv (by column name, so added columns don't break old files). */
    fun load(csv: String): List<Row> {
        val lines = csv.lines().filter { it.isNotBlank() }
        val head = lines.first().split(',').withIndex().associate { (i, n) -> n to i }
        fun List<String>.f(name: String) = head[name]?.let { getOrNull(it) }?.toFloatOrNull()
        return lines.drop(1).mapNotNull { l ->
            val c = l.split(',')
            val t = c.getOrNull(head.getValue("t_ms"))?.toLongOrNull() ?: return@mapNotNull null
            Row(t, c.f("chunk_ms")?.toLong() ?: 46L, c.f("speed_kmh"), c.f("level_db"), c.f("background_db"), c.f("voice"), c.f("voice_dewind"),
                c.f("lift_db"), c.f("high_lift_db"), c.f("wind"), c.f("speaking") == 1f)
        }
    }

    /** The rider's marks: "10-15, 24-60" (seconds from the trace's start), or "" for a ride with no talking. */
    fun talk(marks: String): List<LongRange> = marks.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { m ->
        val (a, b) = m.split('-').map { it.trim().toLong() }
        a * 1000 until b * 1000
    }

    /** Whether a row counts toward "speaking": the rules under test feed the gate. */
    fun interface Rules {
        fun speaking(gate: SpeechGate, r: Row): Boolean
    }

    /** Today's rules at a sensitivity: loudness margin, two-mic lift and 1–4 kHz lift thresholds. */
    fun current(marginDb: Float = 16f, liftDb: Float = 6f, highDb: Float = 3f) = Rules { gate, r ->
        val wind = r.wind ?: 0f
        gate.onLevel(
            r.t, (r.levelDb ?: -90f) - (r.backgroundDb ?: -90f), r.chunkMs,
            com.ridetrack.telemetry.moments.VoiceInWind.margin(marginDb, wind), r.voice ?: 1f,
            com.ridetrack.telemetry.moments.VoiceInWind.voiceMin(wind), r.liftDb, r.highLiftDb, liftDb, highDb,
        )
    }

    class Score(
        /** Talking stretches (2 s or more) during which it was speaking at some point. */
        val caught: Int,
        val stretches: Int,
        /** Speaking started with no talking within 1 s before to 5 s after. */
        val falseStarts: Int,
        /** Share of silent time (away from any talking by 3 s) spent filming. */
        val silentFilmingPct: Int,
        /** The longest video with no talking in it (s), with the app's 5 s hold and 5 s joins. */
        val longestEmptyVideoS: Int,
        val minutes: Double,
    ) {
        override fun toString() =
            "talk caught $caught/$stretches · false starts $falseStarts (${"%.1f".format(java.util.Locale.US, falseStarts / minutes * 10)} per 10 min) · " +
                "filming in silence $silentFilmingPct% · longest empty video ${longestEmptyVideoS}s · ${"%.1f".format(java.util.Locale.US, minutes)} min"
    }

    fun score(rows: List<Row>, talk: List<LongRange>, rules: Rules = current(), holdMs: Long = 5_000, joinMs: Long = 5_000): Score {
        if (rows.isEmpty()) return Score(0, 0, 0, 0, 0, 0.0)
        val t0 = rows.first().t
        val gate = SpeechGate()
        fun rel(t: Long) = t - t0
        fun inTalk(t: Long) = talk.any { rel(t) in it }
        fun near(t: Long) = talk.any { r -> rel(t) in (r.first - 3_000) until (r.last + 3_000) }
        val stretches = talk.filter { it.last - it.first >= 2_000 }
        val caught = HashSet<LongRange>()
        var falseStarts = 0
        var silent = 0L
        var silentFilming = 0L
        // Filming: from each speaking start to [holdMs] after its last loud moment; videos closer than [joinMs] are one.
        val videos = ArrayList<LongArray>() // start, end, had talk (1/0)
        var until = Long.MIN_VALUE
        var lastHandled = Long.MIN_VALUE
        rows.forEach { r ->
            val was = gate.speaking
            val now = rules.speaking(gate, r)
            if (now) stretches.filter { rel(r.t) in it }.forEach { caught += it }
            if (now && !was && talk.none { tr -> rel(r.t) in (tr.first - 1_000) until (tr.last + 5_000) }) falseStarts++
            val last = gate.lastSpeechMillis
            if (last != null && last > lastHandled) {
                lastHandled = last
                val end = last + holdMs
                val v = videos.lastOrNull()
                if (v != null && r.t <= v[1] + joinMs) v[1] = maxOf(v[1], end) else videos += longArrayOf(r.t, end, 0)
                until = end
            }
            videos.lastOrNull()?.let { v -> if (r.t in v[0]..v[1] && inTalk(r.t)) v[2] = 1 }
            if (!near(r.t)) {
                silent += r.chunkMs
                if (r.t <= until) silentFilming += r.chunkMs
            }
        }
        val longestEmpty = videos.filter { it[2] == 0L }.maxOfOrNull { it[1] - it[0] } ?: 0L
        return Score(
            caught.size, stretches.size, falseStarts,
            if (silent == 0L) 0 else (silentFilming * 100 / silent).toInt(),
            (longestEmpty / 1000.0).roundToInt(),
            (rows.last().t - t0) / 60_000.0,
        )
    }
}
