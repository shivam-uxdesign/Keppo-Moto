package com.ridetrack.app.ui.common

import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Personal totals, computed from real (non-demo) completed rides only. */
data class RideTotals(
    val rideCount: Int,
    val distanceM: Double,
    val movingMillis: Long,
    val longestRideM: Double?,
    val maxSpeedMps: Double?,
    val totalStops: Int,
    val distanceThisMonthM: Double,
    val ridesThisMonth: Int,
    val movingThisMonthMillis: Long,
    val distanceTodayM: Double = 0.0,
    val ridesToday: Int = 0,
    val movingTodayMillis: Long = 0,
) {
    val averageRideM: Double? get() = if (rideCount > 0) distanceM / rideCount else null

    companion object {
        fun from(rides: List<Ride>, zone: ZoneId = ZoneId.systemDefault()): RideTotals {
            val real = rides.filter { it.source != DataSourceKind.DEMO }
            val month = YearMonth.now(zone)
            val thisMonth = real.filter { YearMonth.from(Instant.ofEpochMilli(it.startTimeMillis).atZone(zone)) == month }
            val today = LocalDate.now(zone)
            val todays = thisMonth.filter { Instant.ofEpochMilli(it.startTimeMillis).atZone(zone).toLocalDate() == today }
            return RideTotals(
                rideCount = real.size,
                distanceM = real.sumOf { it.stats.distanceM },
                movingMillis = real.sumOf { it.stats.movingMillis },
                longestRideM = real.maxOfOrNull { it.stats.distanceM },
                maxSpeedMps = real.mapNotNull { it.stats.maxSpeedMps }.maxOrNull(),
                totalStops = real.sumOf { it.stats.stopCount },
                distanceThisMonthM = thisMonth.sumOf { it.stats.distanceM },
                ridesThisMonth = thisMonth.size,
                movingThisMonthMillis = thisMonth.sumOf { it.stats.movingMillis },
                distanceTodayM = todays.sumOf { it.stats.distanceM },
                ridesToday = todays.size,
                movingTodayMillis = todays.sumOf { it.stats.movingMillis },
            )
        }
    }
}
