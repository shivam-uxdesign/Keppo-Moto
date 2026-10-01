package com.ridetrack.app.backup

import com.ridetrack.app.data.db.BikeEntity
import com.ridetrack.app.data.db.EventEntity
import com.ridetrack.app.data.db.MomentEntity
import com.ridetrack.app.data.db.RideEntity
import com.ridetrack.app.data.db.SampleEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One ride as it lives in the backup: the ride row, its events and its moments. */
data class RideBundle(val ride: RideEntity, val events: List<EventEntity>, val moments: List<MomentEntity>)

/**
 * The files Keppo Moto keeps in Drive, as text. Pure: no Android, no I/O beyond the given
 * streams, so it is unit-tested on the JVM. `ride.json` is the shared Keppo ride format (v1)
 * that Keppo Journal reads too, so keep its fields stable and only ever add new ones.
 */
object BackupFormat {
    const val RIDE_FORMAT = "keppo.ride"
    const val RIDE_VERSION = 1

    // ---- ride.json -------------------------------------------------------------------------

    fun rideJson(b: RideBundle, bikeName: String?): String {
        val r = b.ride
        val o = JSONObject()
            .put("format", RIDE_FORMAT).put("v", RIDE_VERSION)
            .put("id", r.id).put("name", r.name).put("bikeId", r.bikeId).put("bikeName", bikeName ?: JSONObject.NULL)
            .put("status", r.status).put("source", r.source)
            .put("startTimeMillis", r.startTimeMillis).put("endTimeMillis", r.endTimeMillis.orNull()).put("lastUpdateMillis", r.lastUpdateMillis)
            .put("stats", JSONObject()
                .put("distanceM", r.distanceM.orNull()).put("movingMillis", r.movingMillis).put("stoppedMillis", r.stoppedMillis)
                .put("maxSpeedMps", r.maxSpeedMps.orNull()).put("maxAccelG", r.maxAccelG.orNull()).put("maxBrakeG", r.maxBrakeG.orNull())
                .put("peakG", r.peakG.orNull()).put("maxLeftLeanDeg", r.maxLeftLeanDeg.orNull()).put("maxRightLeanDeg", r.maxRightLeanDeg.orNull())
                .put("avgLeanDeg", r.avgLeanDeg.orNull()).put("stopCount", r.stopCount).put("leftTurns", r.leftTurns).put("rightTurns", r.rightTurns)
                .put("brakeEvents", r.brakeEvents).put("accelEvents", r.accelEvents).put("leanEvents", r.leanEvents))
            .put("events", JSONArray().apply {
                b.events.forEach { e ->
                    put(JSONObject().put("type", e.type).put("timeMillis", e.timeMillis).put("latitude", e.latitude.orNull())
                        .put("longitude", e.longitude.orNull()).put("speedMps", e.speedMps.orNull()).put("value", e.value.orNull()))
                }
            })
            .put("moments", JSONArray().apply {
                b.moments.forEach { m ->
                    put(JSONObject().put("id", m.id).put("kind", m.kind).put("types", m.types).put("timeMillis", m.timeMillis)
                        .put("latitude", m.latitude.orNull()).put("longitude", m.longitude.orNull()).put("speedMps", m.speedMps.orNull())
                        .put("peakValue", m.peakValue.orNull()).put("file", m.file).put("thumbFile", m.thumbFile ?: JSONObject.NULL)
                        .put("durationMillis", m.durationMillis.orNull()).put("starred", m.starred)
                        .put("clipStartMillis", m.clipStartMillis.orNull()).put("source", m.source ?: JSONObject.NULL))
                }
            })
        return o.toString(1)
    }

    fun parseRide(json: String): RideBundle {
        val o = JSONObject(json)
        require(o.optString("format") == RIDE_FORMAT) { "Not a Keppo ride" }
        val id = o.getString("id")
        val s = o.getJSONObject("stats")
        val ride = RideEntity(
            id = id, bikeId = o.getString("bikeId"), name = o.getString("name"), status = o.getString("status"),
            source = o.getString("source"), startTimeMillis = o.getLong("startTimeMillis"), endTimeMillis = o.longOrNull("endTimeMillis"),
            lastUpdateMillis = o.getLong("lastUpdateMillis"), distanceM = s.doubleOrNull("distanceM") ?: 0.0, movingMillis = s.getLong("movingMillis"),
            stoppedMillis = s.getLong("stoppedMillis"), maxSpeedMps = s.doubleOrNull("maxSpeedMps"), maxAccelG = s.doubleOrNull("maxAccelG"),
            maxBrakeG = s.doubleOrNull("maxBrakeG"), peakG = s.doubleOrNull("peakG"), maxLeftLeanDeg = s.doubleOrNull("maxLeftLeanDeg"),
            maxRightLeanDeg = s.doubleOrNull("maxRightLeanDeg"), avgLeanDeg = s.doubleOrNull("avgLeanDeg"), stopCount = s.getInt("stopCount"),
            leftTurns = s.getInt("leftTurns"), rightTurns = s.getInt("rightTurns"), brakeEvents = s.getInt("brakeEvents"),
            accelEvents = s.getInt("accelEvents"), leanEvents = s.getInt("leanEvents"),
        )
        val events = o.optJSONArray("events").objects().map { e ->
            EventEntity(rideId = id, type = e.getString("type"), timeMillis = e.getLong("timeMillis"), latitude = e.doubleOrNull("latitude"),
                longitude = e.doubleOrNull("longitude"), speedMps = e.doubleOrNull("speedMps"), value = e.doubleOrNull("value"))
        }
        val moments = o.optJSONArray("moments").objects().map { m ->
            MomentEntity(
                id = m.getString("id"), rideId = id, kind = m.getString("kind"), types = m.optString("types"), timeMillis = m.getLong("timeMillis"),
                latitude = m.doubleOrNull("latitude"), longitude = m.doubleOrNull("longitude"), speedMps = m.doubleOrNull("speedMps"),
                peakValue = m.doubleOrNull("peakValue"), file = m.getString("file"), thumbFile = m.stringOrNull("thumbFile"),
                durationMillis = m.longOrNull("durationMillis"), starred = m.optBoolean("starred"),
                clipStartMillis = m.longOrNull("clipStartMillis"), source = m.stringOrNull("source"),
            )
        }
        return RideBundle(ride, events, moments)
    }

    // ---- samples.jsonl.gz: one compact array per sample ---------------------------------------

    fun writeSamples(samples: List<SampleEntity>, out: OutputStream) {
        OutputStreamWriter(GZIPOutputStream(out), Charsets.UTF_8).use { w ->
            samples.forEach { s ->
                val a = JSONArray().put(s.timeMillis).put(s.latitude.orNull()).put(s.longitude.orNull()).put(s.speedMps.orNull())
                    .put(s.altitudeM.orNull()).put(s.headingDeg.orNull()).put(s.longitudinalG.orNull()).put(s.lateralG.orNull())
                    .put(s.leanDeg.orNull()).put(s.gpsAccuracyM.orNull()).put(s.rpm.orNull()).put(s.gear.orNull())
                w.write(a.toString()); w.write("\n")
            }
        }
    }

    fun readSamples(rideId: String, input: InputStream): List<SampleEntity> =
        BufferedReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8)).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val a = JSONArray(line)
                SampleEntity(
                    rideId = rideId, timeMillis = a.getLong(0), latitude = a.dOrNull(1), longitude = a.dOrNull(2), speedMps = a.dOrNull(3),
                    altitudeM = a.dOrNull(4), headingDeg = a.dOrNull(5), longitudinalG = a.dOrNull(6), lateralG = a.dOrNull(7),
                    leanDeg = a.dOrNull(8), gpsAccuracyM = a.dOrNull(9), rpm = a.dOrNull(10), gear = if (a.isNull(11)) null else a.getInt(11),
                )
            }.toList()
        }

    // ---- garage/bikes.json -----------------------------------------------------------------

    fun bikesJson(bikes: List<BikeEntity>): String = JSONObject().put("v", 1).put("bikes", JSONArray().apply {
        bikes.forEach { b ->
            put(JSONObject().put("id", b.id).put("make", b.make).put("model", b.model).put("year", b.year.orNull())
                .put("displacementCc", b.displacementCc.orNull()).put("weightKg", b.weightKg.orNull()).put("fuelType", b.fuelType)
                .put("mountOrientation", b.mountOrientation).put("calibUpX", b.calibUpX.orNull()).put("calibUpY", b.calibUpY.orNull())
                .put("calibUpZ", b.calibUpZ.orNull()).put("calibratedAtMillis", b.calibratedAtMillis.orNull()).put("createdAtMillis", b.createdAtMillis)
                .put("redlineRpm", b.redlineRpm.orNull()).put("photoFile", b.photoFile ?: JSONObject.NULL)
                .put("odometerKm", b.odometerKm.orNull()).put("odometerSetAtMillis", b.odometerSetAtMillis.orNull()))
        }
    }).toString(1)

    fun parseBikes(json: String): List<BikeEntity> = JSONObject(json).optJSONArray("bikes").objects().map { b ->
        BikeEntity(
            id = b.getString("id"), make = b.getString("make"), model = b.getString("model"), year = b.intOrNull("year"),
            displacementCc = b.intOrNull("displacementCc"), weightKg = b.intOrNull("weightKg"), fuelType = b.getString("fuelType"),
            mountOrientation = b.getString("mountOrientation"), calibUpX = b.doubleOrNull("calibUpX"), calibUpY = b.doubleOrNull("calibUpY"),
            calibUpZ = b.doubleOrNull("calibUpZ"), calibratedAtMillis = b.longOrNull("calibratedAtMillis"), createdAtMillis = b.getLong("createdAtMillis"),
            redlineRpm = b.intOrNull("redlineRpm"), photoFile = b.stringOrNull("photoFile"), odometerKm = b.doubleOrNull("odometerKm"),
            odometerSetAtMillis = b.longOrNull("odometerSetAtMillis"),
        )
    }

    // ---- settings.json: DataStore preferences, typed ---------------------------------------

    /** A stored preference: its key and value (Boolean, Int, Long, Float, Double, String or Set<String>). */
    data class Pref(val key: String, val value: Any)

    fun settingsJson(prefs: List<Pref>): String = JSONObject().put("v", 1).put("prefs", JSONObject().apply {
        prefs.sortedBy { it.key }.forEach { p ->
            val (t, v) = when (val x = p.value) {
                is Boolean -> "b" to x
                is Int -> "i" to x
                is Long -> "l" to x
                is Float -> "f" to x.toDouble()
                is Double -> "d" to x
                is String -> "s" to x
                is Set<*> -> "ss" to JSONArray(x.map { it.toString() }.sorted())
                else -> return@forEach
            }
            put(p.key, JSONObject().put("t", t).put("v", v))
        }
    }).toString(1)

    fun parseSettings(json: String): List<Pref> {
        val prefs = JSONObject(json).optJSONObject("prefs") ?: return emptyList()
        return prefs.keys().asSequence().mapNotNull { key ->
            val e = prefs.getJSONObject(key)
            val v: Any = when (e.getString("t")) {
                "b" -> e.getBoolean("v")
                "i" -> e.getInt("v")
                "l" -> e.getLong("v")
                "f" -> e.getDouble("v").toFloat()
                "d" -> e.getDouble("v")
                "s" -> e.getString("v")
                "ss" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> return@mapNotNull null
            }
            Pref(key, v)
        }.sortedBy { it.key }.toList()
    }

    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    // ---- helpers ---------------------------------------------------------------------------

    private fun Any?.orNull(): Any = if (this == null || (this is Double && (isNaN() || isInfinite()))) JSONObject.NULL else this
    private fun JSONObject.doubleOrNull(k: String): Double? = if (isNull(k)) null else optDouble(k).takeUnless { it.isNaN() }
    private fun JSONObject.longOrNull(k: String): Long? = if (isNull(k)) null else optLong(k)
    private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k)) null else optInt(k)
    private fun JSONObject.stringOrNull(k: String): String? = if (isNull(k)) null else optString(k)
    private fun JSONArray.dOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
