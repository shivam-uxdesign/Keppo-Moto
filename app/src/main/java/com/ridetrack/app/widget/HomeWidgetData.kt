package com.ridetrack.app.widget

import com.ridetrack.app.fuel.FuelFill
import com.ridetrack.app.moments.MomentKeywords
import com.ridetrack.app.ui.common.Odometer
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.format.Units
import com.ridetrack.app.ui.home.BikeCare
import com.ridetrack.app.ui.home.CareItem
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/** The home-screen grid size a widget is shown at: 1, 2 or 4 columns by 1, 2 or 3 rows. */
data class WidgetSize(val cols: Int, val rows: Int) {
    companion object {
        /** From the launcher's sizes (dp): the portrait width and height. */
        fun of(widthDp: Int, heightDp: Int): WidgetSize = WidgetSize(
            cols = when { widthDp <= 0 -> 4; widthDp < 100 -> 1; widthDp < 220 -> 2; else -> 4 },
            rows = when { heightDp <= 0 -> 2; heightDp < 130 -> 1; heightDp < 260 -> 2; else -> 3 },
        )
    }
}

/**
 * Before a ride: everything that would spoil the recording, by exception. "All set", or the one
 * thing to fix first (and how). Riding is never blocked by it.
 */
data class Readiness(val ok: Boolean, val problem: String?, val fix: String?) {
    /** One short line: "All set" or "Mic off". */
    val short: String get() = if (ok) "All set" else problem.orEmpty()

    companion object {
        val ALL_SET = Readiness(true, null, null)

        fun of(
            locationAllowed: Boolean,
            gpsOn: Boolean,
            /** The external mic the rider chose (not Automatic or the phone), and whether it's connected. */
            chosenMic: String?,
            micConnected: Boolean,
            freeBytes: Long?,
            batteryPercent: Int?,
            charging: Boolean,
            crashAlertOn: Boolean,
            contacts: Int,
        ): Readiness = when {
            !locationAllowed -> Readiness(false, "Location off", "Allow location for Keppo Moto")
            !gpsOn -> Readiness(false, "GPS off", "Turn on location")
            chosenMic != null && !micConnected -> Readiness(false, "Mic off", "Turn on ${chosenMic.substringBefore(" · ")}")
            freeBytes != null && freeBytes < LOW_STORAGE -> Readiness(false, "Storage low", String.format(Locale.US, "%.1f GB free · clear old clips", freeBytes / 1e9))
            batteryPercent != null && batteryPercent < LOW_BATTERY && !charging -> Readiness(false, "Battery $batteryPercent%", "Charge the phone or plug it in on the bike")
            crashAlertOn && contacts == 0 -> Readiness(false, "No crash contacts", "Add someone to text in Safety")
            else -> ALL_SET
        }

        const val LOW_STORAGE = 2_000_000_000L
        const val LOW_BATTERY = 20
    }
}

/** One bike on the before-ride widgets. [careLevel]: 0 fine, 1 soon, 2 due. */
data class BikeCard(
    val id: String,
    val name: String,
    val odometer: String?,
    val color: Int,
    val photo: String?,
    val fuel: String?,
    val care: String?,
    val careShort: String?,
    val careLevel: Int,
)

/** A ride on the after-ride widgets. */
data class RideCard(
    val id: String,
    val day: String,
    val whenText: String,
    val name: String,
    val km: String,
    val time: String,
    val top: String?,
    val lean: String?,
    val leanLeft: Boolean,
    val moments: Int,
)

/** A record: a fact from your rides, with where it came from. [fresh]: set by the latest ride. */
data class RecordCard(val label: String, val short: String, val value: String, val unit: String, val context: String, val rideId: String?, val fresh: Boolean, val lean: Char? = null)

/** Shots of a Storyboard still to film, grouped by where the phone goes. */
data class FilmToday(val reelId: String, val title: String, val done: Int, val total: Int, val groups: List<Pair<String, String>>)

/** Pure: what the widgets show, from the app's data. Unit-tested. */
object HomeWidgetData {
    private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun bikes(bikes: List<Bike>, rides: List<Ride>, care: List<CareItem>, fills: List<FuelFill>, colors: (Bike) -> Int, now: Long): List<BikeCard> =
        bikes.map { b ->
            val odo = Odometer.readingKm(b, rides)
            val next = BikeCare.sorted(care.filter { it.bikeId == b.id }, odo, now).firstOrNull()
            val range = rangeKm(fills.filter { it.bikeId == b.id }, rides.filter { it.bikeId == b.id && it.source != DataSourceKind.DEMO }.map { it.startTimeMillis to it.stats.distanceM })
            BikeCard(
                id = b.id,
                name = b.displayName.ifBlank { "Your bike" },
                odometer = odo?.let { "${Format.odometer(it)} ${Units.distanceUnit}" },
                color = colors(b),
                photo = b.photoFile,
                fuel = range?.let { if (it <= 20) "Fill up soon" else "~${Units.fromKm(it).roundToInt()} ${Units.distanceUnit} in the tank" },
                care = next?.let { "${it.item.name} · ${it.text.replaceFirstChar { c -> c.lowercase() }}" },
                careShort = next?.let { "${it.item.name.lowercase(Locale.ENGLISH)} ${if (it.due) "due" else it.text.removePrefix("Due ").lowercase(Locale.ENGLISH)}" },
                careLevel = when { next == null -> 0; next.due -> 2; next.used >= 0.8f -> 1; else -> 0 },
            )
        }

    /**
     * Km left in the tank: the usual distance between fill-ups (from 2 or more) less what's been
     * ridden since the last one. Null when there isn't enough to tell.
     */
    fun rangeKm(fills: List<FuelFill>, rides: List<Pair<Long, Double>>): Double? {
        val sorted = fills.sortedBy { it.timeMillis }
        if (sorted.size < 3) return null
        fun km(from: Long, to: Long) = rides.filter { it.first > from && it.first < to }.sumOf { it.second } / 1000
        val gaps = sorted.zipWithNext().map { (a, b) -> km(a.timeMillis, b.timeMillis) }.filter { it > 0 }.sorted()
        if (gaps.size < 2) return null
        val usual = gaps[gaps.size / 2]
        return (usual - km(sorted.last().timeMillis, Long.MAX_VALUE)).coerceAtLeast(0.0)
    }

    /** Real, finished rides, newest first. */
    fun realRides(rides: List<Ride>): List<Ride> =
        rides.filter { it.source != DataSourceKind.DEMO && it.status == RideStatus.COMPLETED }.sortedByDescending { it.startTimeMillis }

    fun ride(r: Ride, moments: Int, zone: ZoneId = ZoneId.systemDefault()): RideCard {
        val d = Instant.ofEpochMilli(r.startTimeMillis).atZone(zone)
        val left = (r.stats.maxLeftLeanDeg ?: 0.0) >= (r.stats.maxRightLeanDeg ?: 0.0)
        val lean = (if (left) r.stats.maxLeftLeanDeg else r.stats.maxRightLeanDeg)?.takeIf { it > 0 }
        val mins = ((r.durationMillis ?: 0L) / 60_000).toInt()
        return RideCard(
            id = r.id,
            day = d.dayOfWeek.name.take(3),
            whenText = DAY_FMT.format(d),
            name = r.name.ifBlank { "Ride" },
            km = dist(r.stats.distanceM),
            time = "${mins / 60}:${String.format(Locale.US, "%02d", mins % 60)}",
            top = r.stats.maxSpeedMps?.let { Units.speed(it).roundToInt().toString() },
            lean = lean?.let { "${it.roundToInt()}°" },
            leanLeft = left,
            moments = moments,
        )
    }

    /**
     * The records board: top speed, lean each way, longest ride, best day, best week. A record is
     * [RecordCard.fresh] when the newest ride set it.
     */
    fun records(rides: List<Ride>, zone: ZoneId = ZoneId.systemDefault()): List<RecordCard> {
        val real = realRides(rides)
        if (real.isEmpty()) return emptyList()
        val newest = real.first().id
        fun ctx(r: Ride) = "${DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(r.startTimeMillis).atZone(zone))} · ${r.name.ifBlank { "Ride" }}"
        val out = ArrayList<RecordCard>()
        real.filter { it.stats.maxSpeedMps != null }.maxByOrNull { it.stats.maxSpeedMps!! }?.let { r ->
            out += RecordCard("TOP SPEED", "TOP", Units.speed(r.stats.maxSpeedMps!!).roundToInt().toString(), Units.speedUnit, ctx(r), r.id, r.id == newest)
        }
        real.filter { (it.stats.maxLeftLeanDeg ?: 0.0) > 0 }.maxByOrNull { it.stats.maxLeftLeanDeg!! }?.let { r ->
            out += RecordCard("LEAN LEFT", "LEAN L", "${r.stats.maxLeftLeanDeg!!.roundToInt()}°", "left", ctx(r), r.id, r.id == newest, 'L')
        }
        real.filter { (it.stats.maxRightLeanDeg ?: 0.0) > 0 }.maxByOrNull { it.stats.maxRightLeanDeg!! }?.let { r ->
            out += RecordCard("LEAN RIGHT", "LEAN R", "${r.stats.maxRightLeanDeg!!.roundToInt()}°", "right", ctx(r), r.id, r.id == newest, 'R')
        }
        real.maxByOrNull { it.stats.distanceM }?.let { r ->
            out += RecordCard("LONGEST RIDE", "LONGEST", dist(r.stats.distanceM), Units.distanceUnit, ctx(r), r.id, r.id == newest)
        }
        real.groupBy { day(it.startTimeMillis, zone) }.maxByOrNull { (_, rs) -> rs.sumOf { it.stats.distanceM } }?.let { (d, rs) ->
            out += RecordCard("BEST DAY", "BEST DAY", dist(rs.sumOf { it.stats.distanceM }), Units.distanceUnit,
                "${DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(d)} · ${rs.size} ride${if (rs.size > 1) "s" else ""}", rs.first().id, rs.any { it.id == newest })
        }
        real.groupBy { day(it.startTimeMillis, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }.maxByOrNull { (_, rs) -> rs.sumOf { it.stats.distanceM } }?.let { (w, rs) ->
            out += RecordCard("BEST WEEK", "WEEK", dist(rs.sumOf { it.stats.distanceM }), Units.distanceUnit,
                "Week of ${DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(w)} · ${rs.size} ride${if (rs.size > 1) "s" else ""}", null, rs.any { it.id == newest })
        }
        return out
    }

    /** Distance (km) ridden on each day. */
    fun dayKm(rides: List<Ride>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> =
        realRides(rides).groupBy { day(it.startTimeMillis, zone) }.mapValues { (_, rs) -> rs.sumOf { it.stats.distanceM } / 1000 }

    /** Days in a row with a ride, up to [today] (a day off today doesn't break it yet). */
    fun streak(days: Map<LocalDate, Double>, today: LocalDate): Int {
        var d = if (days.containsKey(today)) today else today.minusDays(1)
        var n = 0
        while (days.containsKey(d)) { n++; d = d.minusDays(1) }
        return n
    }

    /** How the calendar shades a day: 0 none, 1–4 by distance. */
    fun shade(km: Double): Int = when { km <= 0 -> 0; km < 30 -> 1; km < 80 -> 2; km < 150 -> 3; else -> 4 }

    /**
     * Sunrise and sunset (epoch ms) at a place on [date], from the sun's position: no internet.
     * Null at the poles' all-day or all-night.
     */
    fun sunTimes(lat: Double, lon: Double, date: LocalDate): Pair<Long, Long>? {
        // Solar noon, near enough: 12:00 UTC shifted by the longitude.
        val noon = date.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() - (lon / 15.0 * 3_600_000).toLong()
        fun up(t: Long) = MomentKeywords.sunElevation(lat, lon, t) > -0.833
        if (!up(noon)) return null
        fun edge(from: Long, step: Long): Long? {
            var t = from
            repeat(24 * 60 / 2) { if (!up(t + step)) return t; t += step }
            return null
        }
        val rise = edge(noon, -120_000L) ?: return null
        val set = edge(noon, 120_000L) ?: return null
        return rise to set
    }

    fun dist(m: Double): String {
        val v = Units.distance(m)
        return if (v >= 100) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)
    }

    private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
