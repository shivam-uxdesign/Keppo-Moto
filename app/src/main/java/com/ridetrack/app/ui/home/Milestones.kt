package com.ridetrack.app.ui.home

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
                "New top speed: ${kmh1(top)} km/h",
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
        val totalBefore = before.sumOf { it.stats.distanceM } / 1000
        val total = totalBefore + latest.stats.distanceM / 1000
        DISTANCES_KM.lastOrNull { it > totalBefore && it <= total }?.let { km ->
            out += Milestone("${String.format(Locale.US, "%,d", km)} km ridden", "Across ${real.size} rides with Keppo Moto")
        }
        return out
    }

    private fun maxLean(r: Ride): Double? = listOfNotNull(r.stats.maxLeftLeanDeg, r.stats.maxRightLeanDeg).maxOrNull()

    private fun kmh1(mps: Double) = String.format(Locale.US, "%.1f", mps * 3.6)

    /** "Saturday" within the week, else the date. */
    private fun day(millis: Long, now: Long, zone: ZoneId) =
        if (now - millis < 6 * 86_400_000L) Instant.ofEpochMilli(millis).atZone(zone).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else Format.rideDate(millis, zone)
}
