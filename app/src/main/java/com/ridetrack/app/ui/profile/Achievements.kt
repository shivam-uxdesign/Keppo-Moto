package com.ridetrack.app.ui.profile

import com.ridetrack.app.ui.format.Units
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * One achievement: earned (when, and on which ride where there is one) or the way to it.
 * [progress]..[target] in [unit] ("km", "rides", "Reels"…); earned when progress reaches target.
 */
data class Achievement(
    val id: String,
    val group: String,
    val title: String,
    val detail: String,
    val progress: Double,
    val target: Double,
    val unit: String,
    val earnedAt: Long? = null,
    val rideId: String? = null,
) {
    val earned: Boolean get() = progress >= target
    val fraction: Float get() = (progress / target).coerceIn(0.0, 1.0).toFloat()
}

/** What the achievements are worked out from: everything is already in the app. */
data class AchievementInputs(
    val rides: List<Ride>,
    val reelTimes: List<Long>,
    val seriesEpisodes: Int,
    val fillTimes: List<Long>,
    val careReminders: Int,
    val crashAlertsReady: Boolean,
    val mountCalibrated: Boolean,
)

/**
 * The achievements, earned and still to reach. Riding distance, long days, riding often,
 * early and late rides, looking after the bike, Reels, and setting up safety. Deliberately no
 * goals for speed or lean: they'd reward riding harder. Pure, unit-tested.
 */
object Achievements {
    val DISTANCES = listOf(100, 500, 1_000, 5_000, 10_000)
    val DAYS = listOf(100, 200, 400)

    fun of(i: AchievementInputs, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): List<Achievement> {
        val rides = i.rides.filter { it.source != DataSourceKind.DEMO }.sortedBy { it.startTimeMillis }
        val u = Units.distanceUnit
        val out = ArrayList<Achievement>()

        // Distance: the ride that crossed each total.
        var total = 0.0
        val crossed = HashMap<Int, Ride>()
        rides.forEach { r ->
            val before = total
            total += Units.distance(r.stats.distanceM)
            DISTANCES.filter { it > before && it <= total }.forEach { crossed[it] = r }
        }
        DISTANCES.forEach { d ->
            out += Achievement("dist-$d", "Distance", "${fmt(d)} $u ridden", "Every ride counts, on any bike", minOf(total, d.toDouble()), d.toDouble(), u, crossed[d]?.startTimeMillis, crossed[d]?.id)
        }

        // Long days: distance ridden in one day.
        val byDay = rides.groupBy { day(it.startTimeMillis, zone) }
        val bestDay = byDay.maxByOrNull { (_, rs) -> rs.sumOf { Units.distance(it.stats.distanceM) } }
        val bestDayDist = bestDay?.value?.sumOf { Units.distance(it.stats.distanceM) } ?: 0.0
        DAYS.forEach { d ->
            val first = byDay.entries.sortedBy { it.key }.firstOrNull { (_, rs) -> rs.sumOf { Units.distance(it.stats.distanceM) } >= d }
            out += Achievement("day-$d", "Long days", "A ${fmt(d)} $u day", "In one day, breaks and all", minOf(bestDayDist, d.toDouble()), d.toDouble(), u, first?.value?.first()?.startTimeMillis, first?.value?.first()?.id)
        }
        val longest = rides.maxOfOrNull { it.durationMillis ?: 0L } ?: 0L
        val fourHours = rides.firstOrNull { (it.durationMillis ?: 0L) >= 4 * HOUR }
        out += Achievement("hours-4", "Long days", "A 4-hour ride", "One ride, from start to end", minOf(longest / HOUR.toDouble(), 4.0), 4.0, "hours", fourHours?.startTimeMillis, fourHours?.id)

        // Riding often.
        val weekends = weekendStreak(rides, zone)
        out += Achievement("weekends-4", "Riding often", "4 weekends in a row", "A ride on each weekend", minOf(weekends.first.toDouble(), 4.0), 4.0, "weekends", weekends.second)
        val year = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().year
        val daysThisYear = byDay.keys.filter { it.year == year }.sorted()
        out += Achievement("days-30", "Riding often", "30 riding days in $year", "Days with at least one ride", minOf(daysThisYear.size.toDouble(), 30.0), 30.0, "days",
            daysThisYear.getOrNull(29)?.let { d -> byDay[d]?.first()?.startTimeMillis })

        // Early and late.
        val sunrise = rides.firstOrNull { hour(it.startTimeMillis, zone) in 5..6 }
        out += Achievement("sunrise", "Early and late", "Sunrise ride", "Start a ride between 5 and 7 am", if (sunrise != null) 1.0 else 0.0, 1.0, "", sunrise?.startTimeMillis, sunrise?.id)
        val night = rides.firstOrNull { hour(it.startTimeMillis, zone) >= 20 || hour(it.startTimeMillis, zone) < 4 }
        out += Achievement("night", "Early and late", "Night ride", "Start a ride after 8 pm", if (night != null) 1.0 else 0.0, 1.0, "", night?.startTimeMillis, night?.id)

        // The bike.
        val fills = i.fillTimes.sorted()
        out += Achievement("fills-10", "The bike", "10 fill-ups logged", "Your mileage gets more exact with each", minOf(fills.size.toDouble(), 10.0), 10.0, "fill-ups", fills.getOrNull(9))
        out += Achievement("care", "The bike", "First service reminder", "Chain, oil or service, on the Bike tab", minOf(i.careReminders.toDouble(), 1.0), 1.0, "")

        // Reels.
        val reels = i.reelTimes.sorted()
        listOf(1, 10, 50).forEach { n ->
            out += Achievement("reels-$n", "Reels", if (n == 1) "First Reel" else "$n Reels", "Made in Studio", minOf(reels.size.toDouble(), n.toDouble()), n.toDouble(), "Reels", reels.getOrNull(n - 1))
        }
        out += Achievement("series", "Reels", "First series episode", "A Reel in one of your series", minOf(i.seriesEpisodes.toDouble(), 1.0), 1.0, "")

        // Ready to ride.
        out += Achievement("crash", "Ready to ride", "Crash alerts set up", "On, with someone to text", if (i.crashAlertsReady) 1.0 else 0.0, 1.0, "")
        out += Achievement("mount", "Ready to ride", "Mount calibrated", "For lean angles you can trust", if (i.mountCalibrated) 1.0 else 0.0, 1.0, "")
        return out
    }

    /** The longest run of weekends in a row with a ride (capped at 4), and when it reached 4. */
    private fun weekendStreak(rides: List<Ride>, zone: ZoneId): Pair<Int, Long?> {
        val weekendRides = rides.filter { Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate().dayOfWeek.let { d -> d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY } }
        val weeks = weekendRides.groupBy { Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)) }
            .toSortedMap()
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        var reached: Long? = null
        weeks.forEach { (sat, rs) ->
            run = if (prev != null && ChronoUnit.WEEKS.between(prev, sat) == 1L) run + 1 else 1
            prev = sat
            if (run > best) best = run
            if (run == 4 && reached == null) reached = rs.first().startTimeMillis
        }
        return minOf(best, 4) to reached
    }

    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun hour(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).hour
    private fun fmt(n: Int) = String.format(Locale.US, "%,d", n)
    private const val HOUR = 3_600_000L
}
