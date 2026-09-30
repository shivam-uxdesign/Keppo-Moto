package com.ridetrack.app.ui.home

import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A bike's lifetime numbers, for the back of its card. */
data class BikeStats(
    val rides: Int,
    val ridingMillis: Long,
    val distanceM: Double,
    val topSpeedMps: Double?,
    /** Positive degrees. */
    val maxLeftLeanDeg: Double?,
    val maxRightLeanDeg: Double?,
    /** Positive G. */
    val hardestBrakeG: Double?,
    val longestRideM: Double?,
    val moments: Int,
    /** When the bike's first recorded ride was (or when it was added, before any ride). */
    val sinceMillis: Long,
) {
    companion object {
        /** From the completed [rides] (any bike; demo rides are left out) and the bike's moment count. */
        fun from(bike: Bike, rides: List<Ride>, moments: Int): BikeStats {
            val own = rides.filter { it.bikeId == bike.id && it.source != DataSourceKind.DEMO }
            return BikeStats(
                rides = own.size,
                ridingMillis = own.sumOf { it.stats.movingMillis },
                distanceM = own.sumOf { it.stats.distanceM },
                topSpeedMps = own.mapNotNull { it.stats.maxSpeedMps }.maxOrNull(),
                maxLeftLeanDeg = own.mapNotNull { it.stats.maxLeftLeanDeg }.maxOrNull(),
                maxRightLeanDeg = own.mapNotNull { it.stats.maxRightLeanDeg }.maxOrNull(),
                hardestBrakeG = own.mapNotNull { it.stats.maxBrakeG }.minOrNull()?.let { kotlin.math.abs(it) },
                longestRideM = own.maxOfOrNull { it.stats.distanceM }?.takeIf { it > 0 },
                moments = moments,
                sinceMillis = own.minOfOrNull { it.startTimeMillis } ?: bike.createdAtMillis,
            )
        }

        private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

        fun sinceLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            "since ${monthYear.format(Instant.ofEpochMilli(millis).atZone(zone))}"
    }
}
