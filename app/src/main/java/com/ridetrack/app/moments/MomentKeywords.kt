package com.ridetrack.app.moments

import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.TelemetrySample
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A word to find a moment by. [kind] says where it came from and colours its chip; [value] and
 * [unit] let searches like "lean 30" match numbers; [detail] is shown small beside it (the peak
 * g next to a speed run). [removed] keeps a keyword the rider took off from coming back.
 */
data class MomentKeyword(
    val text: String,
    val kind: Kind,
    val value: Double? = null,
    val unit: String? = null,
    val detail: String? = null,
    val removed: Boolean = false,
) {
    enum class Kind { RIDE, SOUND, SCENE, OWN }

    /** Lower-case, for matching. */
    val key: String get() = text.lowercase(Locale.US)
}

/**
 * Keywords for moments. Ride keywords come from the ride's own samples (lean from the rider's
 * lean setting, speed runs, top speed, turns, time of day, camera); sound and picture keywords
 * are added later by [withSound] / [withScene]. Pure, unit-tested; English only.
 */
object MomentKeywords {
    /** A speed change has to be at least this big to count as a run (km/h). */
    const val MIN_RUN_KMH = 20.0
    /** Wobbles smaller than this don't break a run (km/h). */
    private const val WOBBLE_KMH = 2.0
    private const val MPS_TO_KMH = 3.6
    private const val KMH_PER_MPH = 1.609344

    /** One speed-up or slow-down: from [fromKmh] to [toKmh] between two times, with the hardest g in it. */
    data class Run(val fromKmh: Double, val toKmh: Double, val startMillis: Long, val endMillis: Long, val peakG: Double?, val weakGps: Boolean) {
        val rising: Boolean get() = toKmh > fromKmh
        val seconds: Double get() = (endMillis - startMillis) / 1000.0
    }

    /** The speed (km/h) at [t], straight between the samples around it. */
    private fun speedAt(pts: List<Pair<Long, Double>>, t: Long): Double? {
        val i = pts.indexOfFirst { it.first >= t }
        if (i < 0) return null
        if (i == 0) return pts[0].second
        val (t0, v0) = pts[i - 1]
        val (t1, v1) = pts[i]
        return if (t1 == t0) v1 else v0 + (v1 - v0) * (t - t0) / (t1 - t0).toDouble()
    }

    /** When the speed first crosses [kmh] between [from] and [to] (indexes), straight between samples. */
    private fun crossing(pts: List<Pair<Long, Double>>, from: Int, to: Int, kmh: Double): Long? {
        for (i in from until to) {
            val (t0, v0) = pts[i]
            val (t1, v1) = pts[i + 1]
            if ((v0 - kmh) * (v1 - kmh) <= 0 && v0 != v1) return t0 + ((kmh - v0) / (v1 - v0) * (t1 - t0)).toLong()
            if (v0 == kmh) return t0
        }
        return null
    }

    /**
     * Speed-ups and slow-downs of at least [MIN_RUN_KMH]. A run starts where the speed starts
     * rising (or falling) and ends where it peaks (or bottoms out); wobbles under 2 km/h don't
     * break it.
     */
    fun runs(samples: List<TelemetrySample>): List<Run> {
        val s = samples.filter { it.speedMps != null }.sortedBy { it.timeMillis }
        if (s.size < 2) return emptyList()
        val kmh = s.map { it.speedMps!! * MPS_TO_KMH }
        val out = ArrayList<Run>()
        var start = 0
        var ext = 0
        var dir = 0
        // Before the first run: the lowest and highest points so far, where a run would start.
        var lo = 0
        var hi = 0
        fun close(runFrom: Int, runTo: Int) {
            if (abs(kmh[runTo] - kmh[runFrom]) < MIN_RUN_KMH) return
            // Trim the level ends (waiting at the lights, cruising at the top) off the run.
            val up = kmh[runTo] > kmh[runFrom]
            val from = (runFrom..runTo).last { if (up) kmh[it] <= kmh[runFrom] + WOBBLE_KMH else kmh[it] >= kmh[runFrom] - WOBBLE_KMH }
            val to = (from..runTo).first { if (up) kmh[it] >= kmh[runTo] - WOBBLE_KMH else kmh[it] <= kmh[runTo] + WOBBLE_KMH }
            if (abs(kmh[to] - kmh[from]) < MIN_RUN_KMH) return
            val part = s.subList(from, to + 1)
            val g = part.mapNotNull { it.longitudinalG?.let(::abs) }.maxOrNull()
            val acc = part.mapNotNull { it.gpsAccuracyM }
            out += Run(kmh[from], kmh[to], s[from].timeMillis, s[to].timeMillis, g, acc.isNotEmpty() && acc.average() > 15.0)
        }
        for (i in 1 until s.size) {
            when (dir) {
                0 -> {
                    if (kmh[i] < kmh[lo]) lo = i
                    if (kmh[i] > kmh[hi]) hi = i
                    when {
                        kmh[i] > kmh[lo] + WOBBLE_KMH -> { dir = 1; start = lo; ext = i }
                        kmh[i] < kmh[hi] - WOBBLE_KMH -> { dir = -1; start = hi; ext = i }
                    }
                }
                1 -> when {
                    kmh[i] >= kmh[ext] -> ext = i
                    kmh[i] < kmh[ext] - WOBBLE_KMH -> {
                        close(start, ext)
                        start = ext; dir = -1; ext = i
                    }
                }
                else -> when {
                    kmh[i] <= kmh[ext] -> ext = i
                    kmh[i] > kmh[ext] + WOBBLE_KMH -> {
                        close(start, ext)
                        start = ext; dir = 1; ext = i
                    }
                }
            }
        }
        if (dir != 0) close(start, ext)
        return out
    }

    /** km/h (or mph) rounded to 5, and "0" below 5. */
    private fun round5(v: Double): Int = if (v < 5) 0 else (v / 5).roundToInt() * 5

    private fun secs(v: Double) = String.format(Locale.US, "%.1f s", v)

    /** "20–85 km/h in 6.1 s" for [run], in mph when [miles]. */
    fun runText(run: Run, miles: Boolean): String {
        val f = if (miles) KMH_PER_MPH else 1.0
        val unit = if (miles) "mph" else "km/h"
        return (if (run.weakGps) "about " else "") + "${round5(run.fromKmh / f)}–${round5(run.toKmh / f)} $unit in ${secs(run.seconds)}"
    }

    /** Standard sprints a run passes: 0–60, 0–100 (0–30, 0–60 mph); braking 60–0, 100–0. */
    fun benchmarks(samples: List<TelemetrySample>, run: Run, miles: Boolean): List<String> {
        val f = if (miles) KMH_PER_MPH else 1.0
        val unit = if (miles) "mph" else "km/h"
        val marks = if (miles) listOf(30.0, 60.0) else listOf(60.0, 100.0)
        val pts = samples.filter { it.speedMps != null && it.timeMillis in run.startMillis..run.endMillis }
            .sortedBy { it.timeMillis }.map { it.timeMillis to it.speedMps!! * MPS_TO_KMH / f }
        if (pts.size < 2) return emptyList()
        val from = run.fromKmh / f
        val to = run.toKmh / f
        val about = if (run.weakGps) "about " else ""
        return marks.mapNotNull { m ->
            if (run.rising && from < 5 && to >= m) {
                val zero = crossing(pts, 0, pts.size - 1, 5.0)?.let { t -> pts.lastOrNull { it.first <= t && it.second < 5 }?.first ?: t } ?: pts.first().first
                val at = crossing(pts, 0, pts.size - 1, m) ?: return@mapNotNull null
                "${about}0–${m.toInt()} $unit in ${secs((at - zero) / 1000.0)}"
            } else if (!run.rising && from >= m && to < 5) {
                val at = crossing(pts, 0, pts.size - 1, m) ?: return@mapNotNull null
                val stop = pts.firstOrNull { it.first >= at && it.second < 5 }?.first ?: pts.last().first
                "$about${m.toInt()}–0 $unit in ${secs((stop - at) / 1000.0)}"
            } else {
                null
            }
        }
    }

    /** The sun's height (degrees) at a place and time; below 0 it's down. Close enough for "sunrise". */
    fun sunElevation(lat: Double, lon: Double, millis: Long): Double {
        val day = millis / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * day) % 360)
        val q = (280.459 + 0.98564736 * day) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * day)
        val decl = asin(sin(e) * sin(l))
        val ra = kotlin.math.atan2(cos(e) * sin(l), cos(l))
        val gmst = (18.697374558 + 24.06570982441908 * day) % 24
        val ha = Math.toRadians(gmst * 15 + lon) - ra
        val la = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(la) * sin(decl) + cos(la) * cos(decl) * cos(ha)))
    }

    /**
     * Good b-roll: the road camera, nobody talking, the bike moving, a steady picture (little
     * jolting) and at least 5 s.
     */
    fun bRoll(part: List<TelemetrySample>, camera: String?, types: Set<RideEventType>, lengthMillis: Long): Boolean {
        if (camera != "back" || RideEventType.VOICE in types || lengthMillis < 5_000 || part.size < 3) return false
        val speeds = part.mapNotNull { it.speedMps }.sorted()
        if (speeds.isEmpty() || speeds[speeds.size / 2] * MPS_TO_KMH < 10) return false
        // Jolting: how much the forward and sideways g swing about their average.
        fun sd(v: List<Double>): Double {
            if (v.size < 3) return 0.0
            val mean = v.average()
            return kotlin.math.sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
        }
        return maxOf(sd(part.mapNotNull { it.longitudinalG }), sd(part.mapNotNull { it.lateralG })) < 0.12
    }

    /** Sunrise, Day, Sunset or Night where and when the clip was filmed. */
    fun timeOfDay(lat: Double?, lon: Double?, millis: Long, zone: ZoneId): String {
        val hour = Instant.ofEpochMilli(millis).atZone(zone).hour
        if (lat == null || lon == null) return when (hour) {
            in 5..6 -> "Sunrise"
            in 7..16 -> "Day"
            in 17..18 -> "Sunset"
            else -> "Night"
        }
        val sun = sunElevation(lat, lon, millis)
        return when {
            sun < -6 -> "Night"
            sun < 8 -> if (hour < 12) "Sunrise" else "Sunset"
            else -> "Day"
        }
    }

    /** A turn of 150° or more within 20 s at riding speed: a hairpin, with its side. */
    fun hairpin(samples: List<TelemetrySample>): String? {
        val s = samples.filter { it.headingDeg != null && (it.speedMps ?: 0.0) > 2.0 }.sortedBy { it.timeMillis }
        for (i in s.indices) {
            var turned = 0.0
            for (j in i + 1 until s.size) {
                if (s[j].timeMillis - s[i].timeMillis > 20_000) break
                var d = s[j].headingDeg!! - s[j - 1].headingDeg!!
                if (d > 180) d -= 360
                if (d < -180) d += 360
                turned += d
                if (abs(turned) >= 150) return if (turned > 0) "Right hairpin" else "Left hairpin"
            }
        }
        return null
    }

    /**
     * Keywords from the ride while the clip was filmed ([fromMillis]..[toMillis]). [leanMinDeg] is
     * the rider's own lean setting; [types] the events that started the clip; [camera] "back" for
     * the road camera; [place] the area's name when known.
     */
    fun ride(
        samples: List<TelemetrySample>,
        fromMillis: Long,
        toMillis: Long,
        leanMinDeg: Int,
        types: Set<RideEventType>,
        camera: String?,
        place: String?,
        miles: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MomentKeyword> {
        val part = samples.filter { it.timeMillis in fromMillis..toMillis }.sortedBy { it.timeMillis }
        val out = ArrayList<MomentKeyword>()
        fun ride(text: String, value: Double? = null, unit: String? = null, detail: String? = null) {
            out += MomentKeyword(text, MomentKeyword.Kind.RIDE, value, unit, detail)
        }
        part.mapNotNull { it.leanDeg }.maxByOrNull { abs(it) }?.takeIf { abs(it) >= leanMinDeg }?.let { lean ->
            ride("Lean ${abs(lean).roundToInt()}° ${if (lean > 0) "right" else "left"}", abs(lean), "°")
        }
        val runs = runs(part)
        runs.filter { it.rising }.maxByOrNull { it.toKmh - it.fromKmh }?.let { r ->
            ride(runText(r, miles), r.toKmh - r.fromKmh, "km/h", r.peakG?.let { String.format(Locale.US, "%.1f g", it) })
            benchmarks(part, r, miles).forEach { ride(it) }
        }
        runs.filter { !it.rising }.maxByOrNull { it.fromKmh - it.toKmh }?.let { r ->
            ride("Braking " + runText(r, miles), r.fromKmh - r.toKmh, "km/h", r.peakG?.let { String.format(Locale.US, "%.1f g", it) })
            benchmarks(part, r, miles).forEach { ride("Braking $it") }
        }
        val top = part.mapNotNull { it.speedMps }.maxOrNull()
        if (top != null) {
            if (top < 2.0) {
                ride("Stopped")
            } else {
                val v = (top * MPS_TO_KMH / if (miles) KMH_PER_MPH else 1.0).roundToInt()
                ride("Top $v ${if (miles) "mph" else "km/h"}", v.toDouble(), if (miles) "mph" else "km/h")
            }
        }
        if (bRoll(part, camera, types, toMillis - fromMillis)) ride("B-roll")
        if (RideEventType.ENGINE_REV in types) ride("Rev")
        hairpin(part)?.let { ride(it) } ?: run {
            if (RideEventType.SHARP_DIRECTION_CHANGE in types) ride("Sharp turn")
            else if (RideEventType.LEFT_TURN in types) ride("Left turn")
            else if (RideEventType.RIGHT_TURN in types) ride("Right turn")
        }
        val at = part.firstOrNull { it.latitude != null } ?: part.firstOrNull()
        ride(timeOfDay(at?.latitude, at?.longitude, fromMillis, zone))
        place?.takeIf { it.isNotBlank() }?.let { ride(it) }
        ride(if (camera == "back") "Road" else "Selfie")
        return out
    }

    /** Keyword text for each sound Gemini may name. */
    private val SOUND_TEXT = mapOf(
        "scream" to "Scream", "shout" to "Shouted", "laugh" to "Laugh", "sneeze" to "Sneeze", "cough" to "Cough",
        "singing" to "Singing", "whistle" to "Whistle", "horn" to "Horn", "siren" to "Siren", "rain" to "Rain", "dog" to "Dog",
    )

    /**
     * Sound keywords: "Talking" and the most-used words when something was said, the sounds
     * heard ([heard], Gemini's names) and "Shouted" when the clip got very loud ([shouted]).
     */
    fun sound(saidWords: List<String>, heard: List<String>, shouted: Boolean): List<MomentKeyword> {
        val out = ArrayList<MomentKeyword>()
        if (saidWords.isNotEmpty()) {
            out += MomentKeyword("Talking", MomentKeyword.Kind.SOUND)
            saidWords.take(3).forEach { out += MomentKeyword(it, MomentKeyword.Kind.SOUND) }
        }
        heard.mapNotNull { SOUND_TEXT[it] }.forEach { t -> if (out.none { it.text == t }) out += MomentKeyword(t, MomentKeyword.Kind.SOUND) }
        if (shouted && out.none { it.text == "Shouted" || it.text == "Scream" }) out += MomentKeyword("Shouted", MomentKeyword.Kind.SOUND)
        return out
    }

    /**
     * A shout: the loudest tenth of a second near full volume (-6 dBFS) and far (20 dB) above
     * the clip's usual level. [pcm] is 16-bit mono at [rate].
     */
    fun loud(pcm: ShortArray, rate: Int): Boolean {
        val win = (rate / 10).coerceAtLeast(1)
        val levels = (0 until pcm.size / win).map { w ->
            var sum = 0.0
            for (i in w * win until (w + 1) * win) sum += pcm[i].toDouble() * pcm[i]
            10 * kotlin.math.log10((sum / win).coerceAtLeast(1.0) / (32768.0 * 32768.0))
        }
        if (levels.size < 5) return false
        val peak = levels.max()
        val usual = levels.sorted()[levels.size / 2]
        return peak > -6 && peak - usual >= 20
    }

    /** [old] with [fresh] keywords of the same kinds replaced; the rider's own and removed ones kept. */
    fun merge(old: List<MomentKeyword>, fresh: List<MomentKeyword>): List<MomentKeyword> {
        val kinds = fresh.map { it.kind }.toSet()
        val removed = old.filter { it.removed }.map { it.key }.toSet()
        val kept = old.filter { it.kind !in kinds || it.removed || it.kind == MomentKeyword.Kind.OWN }
        val added = fresh.filter { it.key !in removed && kept.none { k -> k.key == it.key } }
        return kept + added
    }

    /** Takes [text] off a moment and remembers it, so it doesn't come back. */
    fun remove(list: List<MomentKeyword>, text: String): List<MomentKeyword> {
        val key = text.lowercase(Locale.US)
        return list.map { if (it.key == key) it.copy(removed = true) else it }
    }

    /** Adds the rider's own keyword (or brings a removed one back). */
    fun add(list: List<MomentKeyword>, text: String): List<MomentKeyword> {
        val t = text.trim().take(40)
        if (t.isEmpty()) return list
        val key = t.lowercase(Locale.US)
        if (list.any { it.key == key }) return list.map { if (it.key == key) it.copy(removed = false) else it }
        return list + MomentKeyword(t, MomentKeyword.Kind.OWN)
    }

    /** What's shown: everything not removed. */
    fun shown(list: List<MomentKeyword>): List<MomentKeyword> = list.filter { !it.removed }

    /**
     * Does a moment with [list] (and its words) match [query]? Every word must match a keyword or
     * the words said; "lean 30" also matches leans of 30° or more.
     */
    fun matches(list: List<MomentKeyword>, said: String?, query: String): Boolean {
        val words = query.lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val shown = shown(list)
        val number = words.firstNotNullOfOrNull { it.toDoubleOrNull() }
        val text = words.filter { it.toDoubleOrNull() == null }
        val hay = shown.joinToString(" ") { it.key } + " " + said.orEmpty().lowercase(Locale.US)
        if (!text.all { hay.contains(it) }) return false
        if (number == null) return true
        return shown.any { k -> k.value != null && k.value >= number && (text.isEmpty() || text.any { k.key.contains(it) }) }
    }

    fun encode(list: List<MomentKeyword>): String = JSONArray().apply {
        list.forEach { k ->
            put(JSONObject().apply {
                put("text", k.text)
                put("kind", k.kind.name)
                k.value?.let { put("value", it) }
                k.unit?.let { put("unit", it) }
                k.detail?.let { put("detail", it) }
                if (k.removed) put("removed", true)
            })
        }
    }.toString()

    fun decode(json: String?): List<MomentKeyword> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MomentKeyword(
                    text = text,
                    kind = MomentKeyword.Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: MomentKeyword.Kind.OWN,
                    value = if (o.has("value")) o.optDouble("value") else null,
                    unit = o.optString("unit").takeIf { it.isNotBlank() },
                    detail = o.optString("detail").takeIf { it.isNotBlank() },
                    removed = o.optBoolean("removed", false),
                )
            }
        }.getOrDefault(emptyList())
    }
}
