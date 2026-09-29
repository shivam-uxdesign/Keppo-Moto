package com.ridetrack.app.ui.home

import com.ridetrack.app.ui.format.Format
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.TelemetrySample
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class StatPeriod(val label: String) { TODAY("Today"), WEEK("Week"), MONTH("Month") }

/** One period on the home stats card. */
data class PeriodStats(
    val distanceM: Double,
    val rides: Int,
    val movingMillis: Long,
    val topSpeedMps: Double?,
    /** Largest lean of the period, signed: negative = left. */
    val maxLeanDeg: Double?,
    /** "51% of last week · 64.9 km" and the like; null when there's nothing to compare with. */
    val comparison: String?,
    /** Distance per day (m) for the bar chart; empty for [StatPeriod.TODAY]. */
    val days: List<Double> = emptyList(),
    /** Index of today in [days]. */
    val todayIndex: Int = -1,
)

data class HomeStats(val today: PeriodStats, val week: PeriodStats, val month: PeriodStats) {
    operator fun get(p: StatPeriod): PeriodStats = when (p) {
        StatPeriod.TODAY -> today
        StatPeriod.WEEK -> week
        StatPeriod.MONTH -> month
    }

    companion object {
        /** Real (non-demo) completed rides only, like every other total in the app. */
        fun from(rides: List<Ride>, now: ZonedDateTime): HomeStats {
            val zone = now.zone
            val real = rides.filter { it.source != DataSourceKind.DEMO }
            val today = now.toLocalDate()
            fun day(r: Ride): LocalDate = Instant.ofEpochMilli(r.startTimeMillis).atZone(zone).toLocalDate()
            val byDay: Map<LocalDate, List<Ride>> = real.groupBy(::day)
            fun kmOn(d: LocalDate) = byDay[d].orEmpty().sumOf { it.stats.distanceM }
            fun between(from: LocalDate, toExclusive: LocalDate) = real.filter { day(it) >= from && day(it) < toExclusive }

            // Today, against the rider's usual riding day over the previous 30 days.
            val todays = byDay[today].orEmpty()
            val recentDays = (1..30).map { today.minusDays(it.toLong()) }.map(::kmOn).filter { it > 0 }
            val usual = recentDays.takeIf { it.isNotEmpty() }?.average()
            val todayKm = todays.sumOf { it.stats.distanceM }
            val todayCompare = when {
                usual == null -> null
                todayKm <= 0 -> "No ride yet today"
                else -> "${percent(todayKm, usual)}% of your usual riding day"
            }

            // Week (Monday first), against last week.
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val week = between(monday, monday.plusDays(7))
            val lastWeekKm = between(monday.minusDays(7), monday).sumOf { it.stats.distanceM }
            val weekKm = week.sumOf { it.stats.distanceM }
            val weekCompare = when {
                lastWeekKm > 0 -> "${percent(weekKm, lastWeekKm)}% of last week · ${Format.distance(lastWeekKm)}"
                weekKm > 0 -> "No rides last week"
                else -> null
            }

            // Month, with a straight-line pace to the end of it.
            val first = today.withDayOfMonth(1)
            val length = today.lengthOfMonth()
            val month = between(first, first.plusMonths(1))
            val monthKm = month.sumOf { it.stats.distanceM }
            val dayOfMonth = today.dayOfMonth
            val monthCompare = if (monthKm > 0) {
                "Day $dayOfMonth of $length · on pace for ${Format.distance(monthKm / dayOfMonth * length)}"
            } else {
                "Day $dayOfMonth of $length"
            }

            return HomeStats(
                today = period(todays, todayCompare),
                week = period(week, weekCompare).copy(
                    days = (0 until 7).map { kmOn(monday.plusDays(it.toLong())) },
                    todayIndex = ChronoUnit.DAYS.between(monday, today).toInt(),
                ),
                month = period(month, monthCompare).copy(
                    days = (0 until length).map { kmOn(first.plusDays(it.toLong())) },
                    todayIndex = dayOfMonth - 1,
                ),
            )
        }

        private fun percent(value: Double, of: Double): Int = (value / of * 100).roundToInt()

        private fun period(rides: List<Ride>, comparison: String?): PeriodStats {
            val left = rides.mapNotNull { it.stats.maxLeftLeanDeg }.maxOrNull()
            val right = rides.mapNotNull { it.stats.maxRightLeanDeg }.maxOrNull()
            val lean = when {
                left == null && right == null -> null
                (left ?: -1.0) >= (right ?: -1.0) -> -(left ?: 0.0)
                else -> right
            }
            return PeriodStats(
                distanceM = rides.sumOf { it.stats.distanceM },
                rides = rides.size,
                movingMillis = rides.sumOf { it.stats.movingMillis },
                topSpeedMps = rides.mapNotNull { it.stats.maxSpeedMps }.maxOrNull(),
                maxLeanDeg = lean,
                comparison = comparison,
            )
        }
    }
}

/** A ride's speed over time, reduced to a few dozen points for a small chart. */
data class SpeedTrace(
    val speedsMps: List<Double>,
    val startMillis: Long,
    val endMillis: Long,
    val isToday: Boolean,
) {
    val peakIndex: Int get() = speedsMps.indices.maxByOrNull { speedsMps[it] } ?: -1

    companion object {
        /** Averages samples into [buckets] equal time slices; empty slices carry the last speed. */
        fun from(samples: List<TelemetrySample>, buckets: Int, isToday: Boolean): SpeedTrace? {
            val timed = samples.filter { it.speedMps != null }
            if (timed.size < 2 || buckets < 2) return null
            val start = timed.first().timeMillis
            val end = timed.last().timeMillis
            if (end <= start) return null
            val sums = DoubleArray(buckets)
            val counts = IntArray(buckets)
            for (s in timed) {
                val i = (((s.timeMillis - start).toDouble() / (end - start)) * (buckets - 1)).roundToInt().coerceIn(0, buckets - 1)
                sums[i] += s.speedMps!!
                counts[i]++
            }
            var last = 0.0
            val speeds = (0 until buckets).map { i ->
                if (counts[i] > 0) last = sums[i] / counts[i]
                last
            }
            return SpeedTrace(speeds, start, end, isToday)
        }
    }
}

/** "Ridden today", "Ridden yesterday", "12 days ago" or "Not ridden yet". */
fun lastRiddenLabel(lastStartMillis: Long?, now: ZonedDateTime): String {
    if (lastStartMillis == null) return "Not ridden yet"
    val day = Instant.ofEpochMilli(lastStartMillis).atZone(now.zone).toLocalDate()
    return when (val days = ChronoUnit.DAYS.between(day, now.toLocalDate())) {
        0L -> "Ridden today"
        1L -> "Ridden yesterday"
        else -> "$days days ago"
    }
}
