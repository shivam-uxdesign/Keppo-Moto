package com.ridetrack.app.ui.home

import com.ridetrack.app.ui.format.Units
import com.ridetrack.app.ui.format.Format
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** A best or total the latest ride just set, e.g. "New top speed: 117.8 km/h". */
data class Milestone(val title: String, val detail: String)

object Milestones {
    /** Shown for a week after the ride that set them. */
    const val SHOW_MILLIS = 7 * 86_400_000L
    val DISTANCES_KM = listOf(100, 250, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000)

    /** What the latest real ride set, against every ride before it. */
    fun of(rides: List<Ride>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Milestone> {
        val real = rides.filter { it.source != DataSourceKind.DEMO }.sortedBy { it.startTimeMillis }
        val latest = real.lastOrNull() ?: return emptyList()
        if (now - (latest.endTimeMillis ?: latest.startTimeMillis) > SHOW_MILLIS) return emptyList()
        val before = real.dropLast(1)
        if (before.isEmpty()) return emptyList()
        val out = ArrayList<Milestone>()

        val top = latest.stats.maxSpeedMps
        val prevTop = before.filter { it.stats.maxSpeedMps != null }.maxByOrNull { it.stats.maxSpeedMps!! }
        if (top != null && prevTop != null && top > prevTop.stats.maxSpeedMps!!) {
            out += Milestone(
                "New top speed: ${kmh1(top)} ${Units.speedUnit}",
                "${latest.name} · was ${kmh1(prevTop.stats.maxSpeedMps!!)} on ${day(prevTop.startTimeMillis, now, zone)}",
            )
        }
        val lean = maxLean(latest)
        val prevLean = before.mapNotNull(::maxLean).maxOrNull()
        if (lean != null && prevLean != null && lean > prevLean + 0.5) {
            out += Milestone("Deepest lean yet: ${lean.toInt()}°", "${latest.name} · was ${prevLean.toInt()}°")
        }
        val prevLongest = before.maxOf { it.stats.distanceM }
        if (latest.stats.distanceM > prevLongest && latest.stats.distanceM >= 1_000) {
            out += Milestone("Longest ride yet: ${Format.distance(latest.stats.distanceM)}", "${latest.name} · was ${Format.distance(prevLongest)}")
        }
        // The same round numbers in the rider's unit: 1,000 km, or 1,000 mi.
        val totalBefore = Units.distance(before.sumOf { it.stats.distanceM })
        val total = totalBefore + Units.distance(latest.stats.distanceM)
        DISTANCES_KM.lastOrNull { it > totalBefore && it <= total }?.let { n ->
            out += Milestone("${String.format(Locale.US, "%,d", n)} ${Units.distanceUnit} ridden", "Across ${real.size} rides with Keppo Moto")
        }
        return out
    }

    private fun maxLean(r: Ride): Double? = listOfNotNull(r.stats.maxLeftLeanDeg, r.stats.maxRightLeanDeg).maxOrNull()

    private fun kmh1(mps: Double) = String.format(Locale.US, "%.1f", Units.speed(mps))

    /** "Saturday" within the week, else the date. */
    private fun day(millis: Long, now: Long, zone: ZoneId) =
        if (now - millis < 6 * 86_400_000L) Instant.ofEpochMilli(millis).atZone(zone).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else Format.rideDate(millis, zone)
}
