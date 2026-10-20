package com.ridetrack.app.studio.storyboard

import java.util.Locale

/**
 * Fitting footage into a shot's slot. A longer clip is trimmed around its event (or its middle);
 * a shorter one is slowed a little to fit, and if it's much shorter the slot shrinks. A shot whose
 * engine sound leads is never slowed (the pitch would drop). Pure, unit-tested.
 */
object AutoTrim {
    /** Where to start in the clip, how fast to play it, and how long it shows. [slowed] shows a badge. */
    data class Fit(val inMs: Long, val speed: Float, val durMs: Long, val slowed: Boolean, val shrunk: Boolean)

    const val GENTLE = 0.85f
    const val MOST = 0.7f

    /** [clipMs] of footage into a [slotMs] slot, centred on [centreMs] (ms into the clip) when known. */
    fun fit(clipMs: Long, slotMs: Long, centreMs: Long? = null, engineFirst: Boolean = false): Fit {
        if (clipMs >= slotMs) {
            val centre = (centreMs ?: (clipMs / 2)).coerceIn(0, clipMs)
            val start = (centre - slotMs / 2).coerceIn(0, clipMs - slotMs)
            return Fit(start, 1f, slotMs, slowed = false, shrunk = false)
        }
        if (engineFirst) return Fit(0, 1f, clipMs, slowed = false, shrunk = true)
        val ratio = clipMs.toFloat() / slotMs
        return when {
            ratio >= GENTLE -> Fit(0, ratio, slotMs, slowed = false, shrunk = false)
            ratio >= MOST -> Fit(0, ratio, slotMs, slowed = true, shrunk = false)
            else -> Fit(0, MOST, (clipMs / MOST).toLong(), slowed = true, shrunk = true)
        }
    }
}

/** A clip that could fill a shot, described the way matching needs. */
data class Candidate(
    val momentId: String,
    val rideId: String?,
    val durationMs: Long,
    /** Event types, lower case with spaces ("strong acceleration", "voice"). */
    val events: Set<String>,
    /** Keywords and words said, lower case. */
    val words: Set<String>,
    /** "back" for the road camera, null for the selfie camera, "phone" for a gallery video. */
    val camera: String?,
    val hasEngine: Boolean,
    /** Where in the clip its event is (ms), for trimming around it. */
    val eventAtMs: Long? = null,
)

/** Clips from every ride ranked for a shot, best first. Must-record shots get none. Pure, unit-tested. */
object Suggest {
    /** How well [c] fits [b]; below [THRESHOLD] it isn't offered. */
    fun score(b: Storyboard, c: Candidate): Double {
        if (b.match.mustRecord) return 0.0
        var s = 0.0
        val events = b.match.events.count { e -> c.events.any { it.contains(e) || e.contains(it) } }
        val words = b.match.words.count { w -> c.words.any { it.contains(w) } }
        s += events * 3 + words * 2
        // A shot that names what it needs isn't filled by a clip that has none of it.
        if ((b.match.events.isNotEmpty() || b.match.words.isNotEmpty()) && events + words == 0) s -= 3
        // Words in the action itself ("tunnel", "rain") count a little too.
        actionWords(b.action).forEach { w -> if (w in c.words) s += 0.5 }
        val front = "front" in b.camera.lowercase(Locale.US)
        when {
            c.camera == "phone" -> Unit
            front && c.camera == null -> s += 2
            !front && c.camera == "back" -> s += 2
            else -> s -= 2
        }
        val need = maxOf(b.match.minMs, (b.durationMs * AutoTrim.MOST).toLong())
        s += if (c.durationMs >= need) 1.0 else -2.0
        if (b.match.engine || b.audio == "engine") s += if (c.hasEngine) 1.0 else -3.0
        if (b.audio == "voice") s += if ("voice" in c.events || "talking" in c.words) 2.0 else -1.0
        return s
    }

    fun rank(b: Storyboard, all: List<Candidate>, n: Int = 3, exclude: Set<String> = emptySet()): List<Pair<Candidate, Double>> =
        if (b.match.mustRecord) emptyList()
        else all.filter { it.momentId !in exclude }.map { it to score(b, it) }.filter { it.second >= THRESHOLD }.sortedByDescending { it.second }.take(n)

    const val THRESHOLD = 3.0

    private val STOP = setOf("the", "and", "your", "you", "with", "into", "from", "that", "this", "then", "while", "over", "past", "shot", "camera")

    private fun actionWords(a: String) = a.lowercase(Locale.US).split(Regex("[^a-z]+")).filter { it.length >= 4 && it !in STOP }.toSet()
}

/** The to-film list: unfilled shots grouped by where the phone goes, so one setup films several. Pure. */
object ToFilm {
    data class Group(val where: String, val shots: List<Pair<String, Storyboard>>, val riding: Boolean)

    fun groups(shots: List<Pair<String, Storyboard>>): List<Group> =
        shots.groupBy { (_, b) -> place(b) }.map { (where, list) -> Group(where, list, list.first().second.riding) }
            .sortedWith(compareBy<Group> { it.riding }.thenBy { it.where })

    /** "Tripod at the tunnel entrance", "Handlebar mount, back camera". */
    fun place(b: Storyboard): String {
        val mount = b.mount.trim().replaceFirstChar { it.uppercase() }
        val cam = b.camera.lowercase(Locale.US)
        val side = when { "front" in cam -> "front camera"; "back" in cam -> "back camera"; else -> b.camera }
        return if (b.riding || "handlebar" in mount.lowercase(Locale.US)) "${if ("mount" in mount.lowercase(Locale.US)) mount else "$mount mount"}, $side" else mount
    }
}
