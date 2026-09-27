package com.ridetrack.app.data.export

import com.ridetrack.app.data.RideTrack
import com.ridetrack.telemetry.model.Ride
import java.time.Instant
import java.util.Locale

/**
 * BETA TOOL — ride data export for testing and tuning. Self-contained: delete the
 * `data/export` package and its two entry points to remove it.
 *
 * Unknown values are never written as zero: JSON uses `null`, CSV leaves the cell empty.
 */
object RideExporter {
    const val SCHEMA = "ridetrack.ride.v1"

    fun json(ride: Ride, bikeName: String?, calibrated: Boolean?, track: RideTrack, appVersion: String): String {
        val s = ride.stats
        val sb = StringBuilder()
        sb.append("{\n")
        field(sb, "schema", str(SCHEMA)); field(sb, "appVersion", str(appVersion))
        field(sb, "id", str(ride.id)); field(sb, "name", str(ride.name))
        field(sb, "bike", str(bikeName)); field(sb, "mountCalibrated", calibrated?.toString() ?: "null")
        field(sb, "source", str(ride.source.name)); field(sb, "status", str(ride.status.name))
        field(sb, "start", str(iso(ride.startTimeMillis))); field(sb, "end", str(ride.endTimeMillis?.let(::iso)))
        sb.append("  \"stats\": {\n")
        val stats = listOf(
            "distanceM" to num(s.distanceM), "durationMs" to num(ride.durationMillis), "movingMs" to num(s.movingMillis),
            "stoppedMs" to num(s.stoppedMillis), "avgSpeedMps" to num(s.avgSpeedMps), "maxSpeedMps" to num(s.maxSpeedMps),
            "maxAccelG" to num(s.maxAccelG), "maxBrakeG" to num(s.maxBrakeG), "peakG" to num(s.peakG),
            "maxLeftLeanDeg" to num(s.maxLeftLeanDeg), "maxRightLeanDeg" to num(s.maxRightLeanDeg), "avgLeanDeg" to num(s.avgLeanDeg),
            "stops" to num(s.stopCount), "leftTurns" to num(s.leftTurns), "rightTurns" to num(s.rightTurns),
            "brakeEvents" to num(s.brakeEvents), "accelEvents" to num(s.accelEvents), "leanEvents" to num(s.leanEvents),
        )
        stats.forEachIndexed { i, (k, v) -> sb.append("    \"").append(k).append("\": ").append(v).append(if (i < stats.lastIndex) ",\n" else "\n") }
        sb.append("  },\n")
        sb.append("  \"events\": [\n")
        track.events.forEachIndexed { i, e ->
            sb.append("    {\"type\": ").append(str(e.type.name)).append(", \"time\": ").append(str(iso(e.timeMillis)))
                .append(", \"lat\": ").append(num(e.latitude)).append(", \"lon\": ").append(num(e.longitude))
                .append(", \"speedMps\": ").append(num(e.speedMps)).append(", \"value\": ").append(num(e.value)).append("}")
                .append(if (i < track.events.lastIndex) ",\n" else "\n")
        }
        sb.append("  ],\n")
        sb.append("  \"samples\": [\n")
        track.samples.forEachIndexed { i, p ->
            sb.append("    {\"time\": ").append(str(iso(p.timeMillis)))
                .append(", \"lat\": ").append(num(p.latitude)).append(", \"lon\": ").append(num(p.longitude))
                .append(", \"speedMps\": ").append(num(p.speedMps)).append(", \"altitudeM\": ").append(num(p.altitudeM))
                .append(", \"headingDeg\": ").append(num(p.headingDeg)).append(", \"longG\": ").append(num(p.longitudinalG))
                .append(", \"latG\": ").append(num(p.lateralG)).append(", \"leanDeg\": ").append(num(p.leanDeg))
                .append(", \"gpsAccuracyM\": ").append(num(p.gpsAccuracyM)).append("}")
                .append(if (i < track.samples.lastIndex) ",\n" else "\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    const val CSV_HEADER = "time_iso,t_s,lat,lon,speed_kmh,altitude_m,heading_deg,long_g,lat_g,lean_deg,gps_accuracy_m"

    fun csv(ride: Ride, track: RideTrack): String {
        val sb = StringBuilder(CSV_HEADER).append('\n')
        track.samples.forEach { p ->
            sb.append(iso(p.timeMillis)).append(',')
                .append(fmt((p.timeMillis - ride.startTimeMillis) / 1000.0, 1)).append(',')
                .append(fmt(p.latitude, 6)).append(',').append(fmt(p.longitude, 6)).append(',')
                .append(fmt(p.speedMps?.times(3.6), 1)).append(',').append(fmt(p.altitudeM, 1)).append(',')
                .append(fmt(p.headingDeg, 0)).append(',').append(fmt(p.longitudinalG, 3)).append(',')
                .append(fmt(p.lateralG, 3)).append(',').append(fmt(p.leanDeg, 1)).append(',')
                .append(fmt(p.gpsAccuracyM, 1)).append('\n')
        }
        return sb.toString()
    }

    /** GPX 1.1; only samples with a GPS fix become track points. */
    fun gpx(ride: Ride, track: RideTrack): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Ride Track\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:rt=\"https://ridetrack.app/gpx/1\">\n")
        sb.append("  <metadata><name>").append(xml(ride.name)).append("</name><time>").append(iso(ride.startTimeMillis)).append("</time></metadata>\n")
        sb.append("  <trk><name>").append(xml(ride.name)).append("</name><trkseg>\n")
        track.samples.filter { it.latitude != null && it.longitude != null }.forEach { p ->
            sb.append("    <trkpt lat=\"").append(fmt(p.latitude, 7)).append("\" lon=\"").append(fmt(p.longitude, 7)).append("\">")
            p.altitudeM?.let { sb.append("<ele>").append(fmt(it, 1)).append("</ele>") }
            sb.append("<time>").append(iso(p.timeMillis)).append("</time>")
            val ext = buildString {
                p.speedMps?.let { append("<rt:speed>").append(fmt(it, 2)).append("</rt:speed>") }
                p.leanDeg?.let { append("<rt:lean>").append(fmt(it, 1)).append("</rt:lean>") }
                p.longitudinalG?.let { append("<rt:longG>").append(fmt(it, 3)).append("</rt:longG>") }
                p.lateralG?.let { append("<rt:latG>").append(fmt(it, 3)).append("</rt:latG>") }
            }
            if (ext.isNotEmpty()) sb.append("<extensions>").append(ext).append("</extensions>")
            sb.append("</trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    fun summaryCsv(rides: List<Pair<Ride, String?>>): String {
        val sb = StringBuilder("id,name,bike,source,start_iso,duration_s,distance_km,moving_s,stopped_s,avg_kmh,max_kmh,max_left_lean_deg,max_right_lean_deg,max_accel_g,max_brake_g,peak_g,stops,left_turns,right_turns,brake_events\n")
        rides.forEach { (r, bike) ->
            val s = r.stats
            sb.append(r.id).append(',').append(csvText(r.name)).append(',').append(csvText(bike)).append(',').append(r.source.name).append(',')
                .append(iso(r.startTimeMillis)).append(',').append(fmt(r.durationMillis?.div(1000.0), 0)).append(',')
                .append(fmt(s.distanceM / 1000.0, 3)).append(',').append(fmt(s.movingMillis / 1000.0, 0)).append(',')
                .append(fmt(s.stoppedMillis / 1000.0, 0)).append(',').append(fmt(s.avgSpeedMps?.times(3.6), 1)).append(',')
                .append(fmt(s.maxSpeedMps?.times(3.6), 1)).append(',').append(fmt(s.maxLeftLeanDeg, 1)).append(',')
                .append(fmt(s.maxRightLeanDeg, 1)).append(',').append(fmt(s.maxAccelG, 3)).append(',')
                .append(fmt(s.maxBrakeG, 3)).append(',').append(fmt(s.peakG, 3)).append(',')
                .append(s.stopCount).append(',').append(s.leftTurns).append(',').append(s.rightTurns).append(',').append(s.brakeEvents).append('\n')
        }
        return sb.toString()
    }

    /** File-name friendly stem, e.g. 2026-09-27_1748_Sunday-Evening-Ride. */
    fun fileStem(ride: Ride): String {
        val t = Instant.ofEpochMilli(ride.startTimeMillis).atZone(java.time.ZoneId.systemDefault())
        val date = String.format(Locale.US, "%04d-%02d-%02d_%02d%02d", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
        val name = ride.name.ifBlank { "Ride" }.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        return "${date}_$name"
    }

    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()
    private fun field(sb: StringBuilder, key: String, value: String) { sb.append("  \"").append(key).append("\": ").append(value).append(",\n") }
    private fun str(v: String?): String = if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun num(v: Number?): String = when (v) {
        null -> "null"
        is Double -> if (v.isNaN() || v.isInfinite()) "null" else v.toString()
        else -> v.toString()
    }
    private fun fmt(v: Double?, decimals: Int): String = v?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: ""
    private fun csvText(v: String?): String = if (v == null) "" else "\"" + v.replace("\"", "\"\"") + "\""
    private fun xml(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
