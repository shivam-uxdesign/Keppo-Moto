package com.ridetrack.app.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every user-visible telemetry value is formatted here. Unknown values render as [DASH],
 * never as zero.
 */
object Format {
    const val DASH = "--"

    enum class LeanSide { LEFT, RIGHT, UPRIGHT }

    fun speedKmh(mps: Double?): String = mps?.let { (it * 3.6).roundToInt().toString() } ?: DASH

    fun speedWithUnit(mps: Double?): String = mps?.let { "${speedKmh(it)} km/h" } ?: DASH

    fun distanceValue(meters: Double): String {
        val km = meters / 1000.0
        return if (km < 100) String.format(Locale.US, "%.1f", km) else String.format(Locale.US, "%,.0f", km)
    }

    fun distance(meters: Double): String = "${distanceValue(meters)} km"

    fun distanceOrDash(meters: Double?): String = meters?.let(::distance) ?: DASH

    /** Live ride clock: 38:12 or 1:02:45. */
    fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Summary duration: 52 min, 1h 42m. */
    fun duration(millis: Long?): String {
        if (millis == null) return DASH
        val totalMin = (millis / 60_000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            millis in 1..59_999 -> "<1 min"
            else -> "$m min"
        }
    }

    fun leanSide(deg: Double?): LeanSide? = when {
        deg == null -> null
        deg >= 1.0 -> LeanSide.RIGHT
        deg <= -1.0 -> LeanSide.LEFT
        else -> LeanSide.UPRIGHT
    }

    /** "18° R", "12° L", "0°" or [DASH]. Direction is spelled out, not only colour-coded. */
    fun lean(deg: Double?): String {
        if (deg == null) return DASH
        val v = abs(deg).roundToInt()
        return when (leanSide(deg)) {
            LeanSide.RIGHT -> "$v° R"
            LeanSide.LEFT -> "$v° L"
            else -> "0°"
        }
    }

    fun leanMagnitude(deg: Double?): String = deg?.let { "${abs(it).roundToInt()}°" } ?: DASH

    /** Signed G, e.g. "+0.32 G" / "-0.41 G". */
    fun gSigned(g: Double?): String = g?.let { String.format(Locale.US, "%+.2f G", it) } ?: DASH

    fun g(g: Double?): String = g?.let { String.format(Locale.US, "%.2f G", abs(it)) } ?: DASH

    fun heading(deg: Double?): String {
        if (deg == null) return DASH
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((deg % 360) + 360) % 360 / 45.0).roundToInt() % 8
        return "${deg.roundToInt()}° ${dirs[idx]}"
    }

    /** "6,240" — thousands grouped so it reads at a glance. */
    fun rpm(rpm: Double?): String = rpm?.let { String.format(Locale.US, "%,d", (it / 10).roundToInt() * 10) } ?: DASH

    /** Gear 0 is neutral. */
    fun gear(gear: Int?): String = when (gear) {
        null -> DASH
        0 -> "N"
        else -> gear.toString()
    }

    fun altitude(m: Double?): String = m?.let { "${it.roundToInt()} m" } ?: DASH

    fun accuracy(m: Double?): String = m?.let { "±${it.roundToInt()} m" } ?: "Accuracy unavailable"

    fun coordinates(lat: Double?, lon: Double?): String =
        if (lat == null || lon == null) "Location unavailable" else String.format(Locale.US, "%.5f, %.5f", lat, lon)

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    /** "26 Sep · 7:42 PM" (year added when not the current year). */
    fun rideDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        val date = if (t.year == LocalDate.now(zone).year) dayMonth.format(t) else dayMonthYear.format(t)
        return "$date · ${time.format(t)}"
    }

    fun timeOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        time.format(Instant.ofEpochMilli(millis).atZone(zone))
}
