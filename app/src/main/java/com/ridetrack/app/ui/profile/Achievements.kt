package com.ridetrack.app.ui.profile

import com.ridetrack.app.ui.format.Units
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A badge's tier as it levels up. */
enum class Tier(val label: String, val points: Int, val argb: Long) {
    BRONZE("Bronze", 1, 0xFFCD7F4A),
    SILVER("Silver", 2, 0xFFC9CDD4),
    GOLD("Gold", 3, 0xFFF2C14E),
    PLATINUM("Platinum", 4, 0xFF9FE3E0),
    DIAMOND("Diamond", 5, 0xFFB9A7FF),
}

/** Which drawing a badge carries. */
enum class BadgeArt { ROAD, DAY, CLOCK, WEEKEND, CALENDAR, SUNRISE, MOON, FUEL, WRENCH, CLAPPER, SERIES, PIN, NEW_ROAD, MOUNTAIN, HAIRPIN, MAP, RAIN, SNOW, MONTHS, CAKE, ODOMETER, CAMERA, STORYBOARD, SNEEZE, SCREAM, FIREWORK, REPEAT, TANK }

/**
 * One badge: one level ([levels] has one target) or tiered (Bronze up to Diamond, one target per
 * tier). [reachedAt] and [rideIds] say when (and on which ride) each level was reached.
 * Secret badges show as "?" until earned.
 */
data class Achievement(
    val id: String,
    val group: String,
    val title: String,
    val detail: String,
    val art: BadgeArt,
    val levels: List<Double>,
    val progress: Double,
    val unit: String,
    val reachedAt: List<Long?> = List(levels.size) { null },
    val rideIds: List<String?> = List(levels.size) { null },
    val secret: Boolean = false,
) {
    /** How many levels are reached (0 = not earned yet). */
    val level: Int get() = levels.count { progress >= it }
    val earned: Boolean get() = level > 0
    val tiered: Boolean get() = levels.size > 1
    val tier: Tier? get() = if (tiered && earned) Tier.entries[(level - 1).coerceAtMost(Tier.entries.size - 1)] else null
    /** The next target, null once it's at the top. */
    val next: Double? get() = levels.getOrNull(level)
    val target: Double get() = next ?: levels.last()
    /** How far to the next level (1 when done). */
    val fraction: Float get() {
        val n = next ?: return 1f
        val from = levels.getOrNull(level - 1) ?: 0.0
        return ((progress - from) / (n - from)).coerceIn(0.0, 1.0).toFloat()
    }
    val earnedAt: Long? get() = reachedAt.getOrNull(level - 1)
    val rideId: String? get() = rideIds.getOrNull(level - 1)
    /** Points toward the rider's level. */
    val points: Int get() = when {
        !earned -> 0
        tiered -> (1..level).sumOf { Tier.entries[(it - 1).coerceAtMost(4)].points }
        secret -> 3
        else -> 2
    }
    /** "62 km to go", "1 more weekend"; null once done. */
    fun left(): String? {
        val n = next ?: return null
        val gap = n - progress
        return if (unit.isEmpty()) "Not yet" else "${amount(gap)} ${unit} to go"
    }
}

/** What the badges are worked out from, all already in the app. */
data class AchievementInputs(
    val rides: List<Ride>,
    val reelTimes: List<Long>,
    val seriesEpisodes: Int,
    val fillTimes: List<Long>,
    val careReminders: Int,
    val crashAlertsReady: Boolean,
    val mountCalibrated: Boolean,
    /** Per ride: what its samples and places say (see [RideFacts]); missing = not worked out yet. */
    val facts: Map<String, RideFacts> = emptyMap(),
    /** Clips, with when they were filmed and their keywords (lower case). */
    val moments: List<MomentFact> = emptyList(),
    val bikes: List<BikeFact> = emptyList(),
    val seriesCount: Int = 0,
    val seriesWithEpisodes: Int = 0,
    val storyboardsFilled: Int = 0,
)

/** One ride's facts for badges: its highest point, hairpins, the ground it covered and its places. */
data class RideFacts(
    val maxAltitudeM: Double? = null,
    val hairpins: Int = 0,
    /** ~500 m grid cells it went through, with the metres ridden in each. */
    val cells: Map<String, Double> = emptyMap(),
    val places: List<String> = emptyList(),
    val states: List<String> = emptyList(),
)

data class MomentFact(val rideId: String, val atMillis: Long, val keywords: Set<String>)

data class BikeFact(val id: String, val name: String, val odometerKm: Double?)

/** A fact, not a goal (no speed or lean): longest ride, highest point… */
data class Record(val title: String, val value: String, val rideId: String?, val atMillis: Long?)

/** The rider's level from badge points. */
data class RiderLevel(val name: String, val points: Int, val from: Int, val to: Int?) {
    val fraction: Float get() = to?.let { ((points - from).toFloat() / (it - from)).coerceIn(0f, 1f) } ?: 1f
}

/**
 * The badges, earned and still to reach: distance, long days, riding often, early and late,
 * exploring, seasons, time with the bike, the bike, moments, Reels, and secret ones. Deliberately
 * no goals or records for speed or lean: they'd reward riding harder. Pure, unit-tested.
 */
object Achievements {
    val DISTANCES = listOf(100.0, 500.0, 1_000.0, 5_000.0, 10_000.0)
    val DAYS = listOf(100.0, 200.0, 400.0)
    val LEVELS = listOf("Rookie" to 0, "Rider" to 6, "Tourer" to 18, "Road Captain" to 40, "Legend" to 80)
    /** A grid cell is about 550 m: rounded to 0.005°. */
    const val CELL = 200.0

    fun of(i: AchievementInputs, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): List<Achievement> {
        val rides = i.rides.filter { it.source != DataSourceKind.DEMO }.sortedBy { it.startTimeMillis }
        val u = Units.distanceUnit
        val out = ArrayList<Achievement>()

        // Distance: the ride that crossed each total.
        out += stepped("dist", "Distance", "Distance ridden", "Every ride counts, on any bike", BadgeArt.ROAD, DISTANCES, u, rides.map { Triple(it.startTimeMillis, Units.distance(it.stats.distanceM), it.id) })

        // Long days: the best day's distance.
        val byDay = rides.groupBy { day(it.startTimeMillis, zone) }.toSortedMap()
        val dayTotals = byDay.map { (_, rs) -> Triple(rs.first().startTimeMillis, rs.sumOf { Units.distance(it.stats.distanceM) }, rs.first().id) }
        out += best("day", "Long days", "Long day", "In one day, breaks and all", BadgeArt.DAY, DAYS, u, dayTotals)
        out += best("hours", "Long days", "Long ride", "One ride, from start to end", BadgeArt.CLOCK, listOf(2.0, 4.0, 6.0), "hours",
            rides.map { Triple(it.startTimeMillis, (it.durationMillis ?: 0L) / HOUR.toDouble(), it.id) })

        // Riding often.
        out += best("weekends", "Riding often", "Weekends in a row", "A ride on each weekend", BadgeArt.WEEKEND, listOf(4.0, 8.0, 13.0), "weekends", weekendRuns(rides, zone))
        val year = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().year
        val daysThisYear = byDay.filterKeys { it.year == year }
        out += stepped("days", "Riding often", "Riding days in $year", "Days with at least one ride", BadgeArt.CALENDAR, listOf(30.0, 100.0, 200.0), "days",
            daysThisYear.map { (_, rs) -> Triple(rs.first().startTimeMillis, 1.0, rs.first().id) })

        // Early and late.
        out += once("sunrise", "Early and late", "Sunrise ride", "Start a ride between 5 and 7 am", BadgeArt.SUNRISE, rides.firstOrNull { hour(it.startTimeMillis, zone) in 5..6 })
        out += once("night", "Early and late", "Night ride", "Start a ride after 8 pm", BadgeArt.MOON, rides.firstOrNull { hour(it.startTimeMillis, zone) >= 20 || hour(it.startTimeMillis, zone) < 4 })

        // Exploring (from each ride's facts, in ride order).
        val placesSeen = LinkedHashSet<String>()
        val statesSeen = LinkedHashSet<String>()
        val cellsSeen = HashSet<String>()
        val placeSteps = ArrayList<Triple<Long, Double, String>>()
        val stateSteps = ArrayList<Triple<Long, Double, String>>()
        val roadSteps = ArrayList<Triple<Long, Double, String>>()
        rides.forEach { r ->
            val f = i.facts[r.id] ?: return@forEach
            val newPlaces = f.places.count { placesSeen.add(it) }
            if (newPlaces > 0) placeSteps += Triple(r.startTimeMillis, newPlaces.toDouble(), r.id)
            val newStates = f.states.count { statesSeen.add(it) }
            if (newStates > 0) stateSteps += Triple(r.startTimeMillis, newStates.toDouble(), r.id)
            val first = cellsSeen.isEmpty()
            val newM = f.cells.entries.filter { cellsSeen.add(it.key) }.sumOf { it.value }
            // The first ride sets out your home roads: only roads after it count as new.
            if (!first) roadSteps += Triple(r.startTimeMillis, Units.distance(newM), r.id)
        }
        out += stepped("places", "Exploring", "New places", "Towns and areas ridden through", BadgeArt.PIN, listOf(5.0, 25.0, 100.0), "places", placeSteps)
        out += stepped("roads", "Exploring", "New roads", "Roads you'd never ridden before", BadgeArt.NEW_ROAD, listOf(100.0, 500.0, 2_000.0).map { Units.fromKm(it) }, u, roadSteps)
        out += best("altitude", "Exploring", "High up", "The highest point of a ride", BadgeArt.MOUNTAIN, listOf(1_000.0, 2_000.0, 3_000.0), "m",
            rides.mapNotNull { r -> i.facts[r.id]?.maxAltitudeM?.let { Triple(r.startTimeMillis, it, r.id) } })
        out += stepped("hairpins", "Exploring", "Hairpins", "Hairpin turns ridden", BadgeArt.HAIRPIN, listOf(10.0, 50.0, 200.0), "hairpins",
            rides.mapNotNull { r -> i.facts[r.id]?.hairpins?.takeIf { it > 0 }?.let { Triple(r.startTimeMillis, it.toDouble(), r.id) } })
        out += stepped("states", "Exploring", "States", "States or regions ridden in", BadgeArt.MAP, listOf(2.0, 5.0, 10.0), "states", stateSteps)

        // Seasons.
        val monsoon = rides.filter { month(it.startTimeMillis, zone) in listOf(Month.JUNE, Month.JULY, Month.AUGUST, Month.SEPTEMBER) }
            .groupBy { Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).year }.values.maxByOrNull { it.size }.orEmpty()
        out += stepped("monsoon", "Seasons", "Monsoon rider", "10 rides from June to September", BadgeArt.RAIN, listOf(10.0), "rides", monsoon.map { Triple(it.startTimeMillis, 1.0, it.id) })
        out += once("winter", "Seasons", "Winter sunrise", "A sunrise ride in December or January", BadgeArt.SNOW,
            rides.firstOrNull { hour(it.startTimeMillis, zone) in 5..7 && month(it.startTimeMillis, zone) in listOf(Month.DECEMBER, Month.JANUARY) })
        val months = rides.groupBy { Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).year }.values
            .maxByOrNull { rs -> rs.map { month(it.startTimeMillis, zone) }.distinct().size }.orEmpty()
        val firstOfMonth = months.groupBy { month(it.startTimeMillis, zone) }.values.map { it.first() }.sortedBy { it.startTimeMillis }
        out += stepped("months", "Seasons", "Every month", "A ride in each month of a year", BadgeArt.MONTHS, listOf(12.0), "months", firstOfMonth.map { Triple(it.startTimeMillis, 1.0, it.id) })

        // Time with the bike.
        val firstRide = rides.firstOrNull()
        val yearOn = firstRide?.let { f -> rides.firstOrNull { it.startTimeMillis - f.startTimeMillis >= 365L * 86_400_000L } }
        out += once("year", "Time with the bike", "A year of riding", "A ride a year after your first", BadgeArt.CAKE, yearOn)
        i.bikes.forEach { b ->
            val ridden = rides.filter { it.bikeId == b.id }
            val total = maxOf(b.odometerKm ?: 0.0, ridden.sumOf { it.stats.distanceM } / 1000.0)
            out += Achievement(
                "bike-${b.id}", "Time with the bike", b.name.ifBlank { "Your bike" }, "On its odometer, or its rides here", BadgeArt.ODOMETER,
                listOf(1_000.0, 10_000.0, 50_000.0).map { Units.fromKm(it) }, Units.fromKm(total), u,
                rideIds = List(3) { ridden.lastOrNull()?.id },
                reachedAt = listOf(1_000.0, 10_000.0, 50_000.0).map { if (total >= it) ridden.lastOrNull()?.startTimeMillis else null },
            )
        }

        // The bike.
        out += stepped("fills", "The bike", "Fill-ups logged", "Your mileage gets more exact with each", BadgeArt.FUEL, listOf(10.0, 50.0, 100.0), "fill-ups", i.fillTimes.sorted().map { Triple(it, 1.0, null) })
        out += Achievement("care", "The bike", "First service reminder", "Chain, oil or service, on the Bike tab", BadgeArt.WRENCH, listOf(1.0), minOf(i.careReminders.toDouble(), 1.0), "")

        // Moments and Studio.
        val clips = i.moments.sortedBy { it.atMillis }
        out += stepped("moments", "Moments and Studio", "Moments filmed", "Clips from your rides", BadgeArt.CAMERA, listOf(1.0, 100.0, 1_000.0), "clips", clips.map { Triple(it.atMillis, 1.0, it.rideId) })
        out += stepped("reels", "Moments and Studio", "Reels made", "Made in Studio", BadgeArt.CLAPPER, listOf(1.0, 10.0, 50.0), "Reels", i.reelTimes.sorted().map { Triple(it, 1.0, null) })
        out += Achievement("series", "Moments and Studio", "A Reel in every series", "Each of your series has an episode", BadgeArt.SERIES, listOf(1.0),
            if (i.seriesCount > 0 && i.seriesWithEpisodes >= i.seriesCount) 1.0 else 0.0, "")
        out += Achievement("storyboard", "Moments and Studio", "Storyboard filled", "Every shot of a Storyboard template filmed", BadgeArt.STORYBOARD, listOf(1.0), minOf(i.storyboardsFilled.toDouble(), 1.0), "")

        // Secret: shown as "?" until earned.
        fun clipWith(word: String) = clips.firstOrNull { word in it.keywords }
        out += secret("sneeze", "Bless you", "A sneeze caught on a moment", BadgeArt.SNEEZE, clipWith("sneeze")?.let { it.atMillis to it.rideId })
        out += secret("scream", "Aaaaah", "A scream on a moment", BadgeArt.SCREAM, clipWith("scream")?.let { it.atMillis to it.rideId })
        out += secret("midnight", "Midnight rider", "Riding across midnight on New Year's Eve", BadgeArt.FIREWORK,
            rides.firstOrNull { r ->
                val s = Instant.ofEpochMilli(r.startTimeMillis).atZone(zone)
                val e = Instant.ofEpochMilli(r.endTimeMillis ?: r.startTimeMillis).atZone(zone)
                s.monthValue == 12 && s.dayOfMonth == 31 && (e.year > s.year)
            }?.let { it.startTimeMillis to it.id })
        val dates = HashSet<LocalDate>()
        out += secret("again", "Same time next year", "A ride on the same date a year later", BadgeArt.REPEAT,
            rides.firstOrNull { r -> val d = day(r.startTimeMillis, zone); (d.minusYears(1) in dates).also { dates += d } }?.let { it.startTimeMillis to it.id })
        val fillDays = i.fillTimes.groupBy { day(it, zone) }.filterValues { it.size >= 3 }.keys.minOrNull()
        out += secret("tanks", "Full tank, full day", "Three fill-ups in one day", BadgeArt.TANK, fillDays?.let { d -> i.fillTimes.filter { day(it, zone) == d }.max() to null })
        return out
    }

    /** "Ready to ride": set up once, shown as a checklist rather than badges. */
    fun setup(i: AchievementInputs): List<Pair<String, Boolean>> = listOf(
        "Crash alerts on, with someone to text" to i.crashAlertsReady,
        "Mount calibrated, for lean angles you can trust" to i.mountCalibrated,
    )

    fun level(list: List<Achievement>): RiderLevel {
        val points = list.sumOf { it.points }
        val i = LEVELS.indexOfLast { points >= it.second }
        return RiderLevel(LEVELS[i].first, points, LEVELS[i].second, LEVELS.getOrNull(i + 1)?.second)
    }

    /** The badges closest to their next level, for "Next up". */
    fun nextUp(list: List<Achievement>, n: Int = 3): List<Achievement> =
        list.filter { it.next != null && !(it.secret && !it.earned) && it.unit.isNotEmpty() && it.progress > 0 }.sortedByDescending { it.fraction }.take(n)

    /** Facts, not goals: no speed or lean. */
    fun records(i: AchievementInputs, zone: ZoneId = ZoneId.systemDefault()): List<Record> {
        val rides = i.rides.filter { it.source != DataSourceKind.DEMO }
        val out = ArrayList<Record>()
        rides.maxByOrNull { it.stats.distanceM }?.let { out += Record("Longest ride", dist(it.stats.distanceM), it.id, it.startTimeMillis) }
        rides.groupBy { day(it.startTimeMillis, zone) }.maxByOrNull { (_, rs) -> rs.sumOf { it.stats.distanceM } }?.let { (_, rs) ->
            out += Record("Longest day", dist(rs.sumOf { it.stats.distanceM }), rs.first().id, rs.first().startTimeMillis)
        }
        rides.maxByOrNull { it.durationMillis ?: 0L }?.let { r -> out += Record("Longest time riding", com.ridetrack.app.ui.format.Format.duration(r.durationMillis), r.id, r.startTimeMillis) }
        rides.mapNotNull { r -> i.facts[r.id]?.maxAltitudeM?.let { r to it } }.maxByOrNull { it.second }?.let { (r, a) ->
            out += Record("Highest point", "${a.toInt()} m", r.id, r.startTimeMillis)
        }
        farthest(rides, i.facts)?.let { (r, m) -> out += Record("Farthest from home", dist(m), r.id, r.startTimeMillis) }
        i.moments.groupBy { it.rideId }.maxByOrNull { it.value.size }?.let { (id, ms) -> out += Record("Most moments in a ride", "${ms.size}", id, rides.firstOrNull { it.id == id }?.startTimeMillis) }
        weekendRuns(rides, zone).maxOfOrNull { it.second }?.takeIf { it > 0 }?.let { out += Record("Longest weekend streak", "${it.toInt()} weekends", null, null) }
        return out
    }

    /** Distance per day of [year], for the riding-year calendar. */
    fun ridingYear(rides: List<Ride>, year: Int, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> =
        rides.filter { it.source != DataSourceKind.DEMO }.groupBy { day(it.startTimeMillis, zone) }.filterKeys { it.year == year }
            .mapValues { (_, rs) -> Units.distance(rs.sumOf { it.stats.distanceM }) }

    /** Weekends in a row with a ride: the current run and the best. */
    fun streak(rides: List<Ride>, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val runs = weekendRuns(rides.filter { it.source != DataSourceKind.DEMO }, zone)
        val best = runs.maxOfOrNull { it.second }?.toInt() ?: 0
        val thisSat = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
        val last = runs.lastOrNull()
        val lastSat = last?.let { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)) }
        val current = if (lastSat != null && ChronoUnit.WEEKS.between(lastSat, thisSat) <= 1) last.second.toInt() else 0
        return current to best
    }

    // ---- building blocks -------------------------------------------------------------------

    /** A badge reached by adding up steps (time, amount, ride) in order: the step that crossed each level. */
    private fun stepped(id: String, group: String, title: String, detail: String, art: BadgeArt, levels: List<Double>, unit: String, steps: List<Triple<Long, Double, String?>>): Achievement {
        var total = 0.0
        val at = MutableList<Long?>(levels.size) { null }
        val ride = MutableList<String?>(levels.size) { null }
        steps.sortedBy { it.first }.forEach { (t, v, r) ->
            val before = total
            total += v
            levels.forEachIndexed { k, l -> if (l > before && l <= total) { at[k] = t; ride[k] = r } }
        }
        return Achievement(id, group, title, detail, art, levels, total, unit, at, ride)
    }

    /** A badge reached by a single best value (a long day, a high point): the first time each level was reached. */
    private fun best(id: String, group: String, title: String, detail: String, art: BadgeArt, levels: List<Double>, unit: String, values: List<Triple<Long, Double, String?>>): Achievement {
        val sorted = values.sortedBy { it.first }
        val at = levels.map { l -> sorted.firstOrNull { it.second >= l } }
        return Achievement(id, group, title, detail, art, levels, values.maxOfOrNull { it.second } ?: 0.0, unit, at.map { it?.first }, at.map { it?.third })
    }

    private fun once(id: String, group: String, title: String, detail: String, art: BadgeArt, ride: Ride?) =
        Achievement(id, group, title, detail, art, listOf(1.0), if (ride != null) 1.0 else 0.0, "", listOf(ride?.startTimeMillis), listOf(ride?.id))

    private fun secret(id: String, title: String, detail: String, art: BadgeArt, hit: Pair<Long, String?>?) =
        Achievement("secret-$id", "Secret", title, detail, art, listOf(1.0), if (hit != null) 1.0 else 0.0, "", listOf(hit?.first), listOf(hit?.second), secret = true)

    /** Each run of weekends in a row with a ride: (when it reached its length, its length, the ride). */
    private fun weekendRuns(rides: List<Ride>, zone: ZoneId): List<Triple<Long, Double, String?>> {
        val weeks = rides.filter { Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate().dayOfWeek.let { d -> d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY } }
            .groupBy { Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)) }
            .toSortedMap()
        val out = ArrayList<Triple<Long, Double, String?>>()
        var run = 0
        var prev: LocalDate? = null
        weeks.forEach { (sat, rs) ->
            run = if (prev != null && ChronoUnit.WEEKS.between(prev, sat) == 1L) run + 1 else 1
            prev = sat
            out += Triple(rs.first().startTimeMillis, run.toDouble(), rs.first().id)
        }
        return out
    }

    /** Home: the cell most rides start in; the farthest cell any ride reached from it. */
    private fun farthest(rides: List<Ride>, facts: Map<String, RideFacts>): Pair<Ride, Double>? {
        val starts = rides.mapNotNull { r -> facts[r.id]?.cells?.keys?.firstOrNull() }
        val home = starts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let(::cellCentre) ?: return null
        return rides.mapNotNull { r ->
            facts[r.id]?.cells?.keys?.maxOfOrNull { c -> cellCentre(c)?.let { meters(home, it) } ?: 0.0 }?.let { r to it }
        }.maxByOrNull { it.second }
    }

    fun cell(lat: Double, lon: Double): String = String.format(Locale.US, "%.3f,%.3f", Math.round(lat * CELL) / CELL, Math.round(lon * CELL) / CELL)

    private fun cellCentre(c: String): Pair<Double, Double>? = c.split(',').takeIf { it.size == 2 }?.let { p -> p[0].toDoubleOrNull()?.let { a -> p[1].toDoubleOrNull()?.let { a to it } } }

    fun meters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    private fun dist(m: Double) = "${amount(Units.distance(m))} ${Units.distanceUnit}"
    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun hour(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).hour
    private fun month(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).month
    private const val HOUR = 3_600_000L
}

internal fun amount(v: Double): String = if (v >= 100 || v % 1.0 == 0.0) String.format(Locale.US, "%,d", Math.round(v)) else String.format(Locale.US, "%.1f", v)
