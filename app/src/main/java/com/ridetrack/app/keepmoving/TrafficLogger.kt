package com.ridetrack.app.keepmoving

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.moments.MomentRepository
import com.ridetrack.app.ride.RideSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Where the rider is riding to, for the traffic log. */
data class Destination(val name: String, val at: LatLon)

/**
 * Keep moving, step 0 (test builds only): during a ride with a destination set, Google's route
 * there with live traffic is fetched every [EVERY_MS] and saved in the ride's folder
 * ([LOG_FILE], one JSON line per check). Nothing changes on the riding screen: the log is read
 * afterwards, against what the rider actually met, to see whether Google's traffic is good enough.
 */
class TrafficLogger(
    private val context: Context,
    private val session: RideSessionManager,
    private val moments: MomentRepository,
    private val scope: CoroutineScope,
    private val key: String = BuildConfig.GOOGLE_ROUTES_KEY,
) {
    private val prefs = context.getSharedPreferences("keep-moving", Context.MODE_PRIVATE)

    /** The build has a Routes key and is a test build: the log can run. */
    val available: Boolean get() = BuildConfig.TEST_BUILD && key.isNotBlank()

    private val _destination = MutableStateFlow(readDestination())
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    private val _on = MutableStateFlow(prefs.getBoolean(ON, true))
    /** The rider wants rides logged (with a destination set). */
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val _status = MutableStateFlow(prefs.getString(STATUS, null))
    /** The last check in plain words ("10:42 · 34 min with traffic (+6) · 1.2 km slow"). */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Checks sent this month (each is one Routes request Google counts). */
    val checksThisMonth: Int get() = if (prefs.getString(MONTH, null) == month()) prefs.getInt(MONTH_COUNT, 0) else 0

    fun setOn(v: Boolean) {
        _on.value = v
        prefs.edit().putBoolean(ON, v).apply()
    }

    fun setDestination(d: Destination?) {
        _destination.value = d
        prefs.edit().apply {
            if (d == null) remove(DEST) else putString(DEST, JSONObject().put("name", d.name).put("lat", d.at.lat).put("lon", d.at.lon).toString())
        }.apply()
    }

    fun watch() {
        if (!BuildConfig.TEST_BUILD) return
        scope.launch {
            session.active.map { it?.rideId }.distinctUntilChanged().collectLatest { rideId ->
                if (rideId != null) runCatching { log(rideId) }
            }
        }
    }

    /** Runs for the whole ride (cancelled when it ends). */
    private suspend fun log(rideId: String) {
        if (!available || !_on.value) return
        val file = File(moments.dir(rideId), LOG_FILE)
        var mode = TrafficRoutes.TWO_WHEELER
        var started = false
        while (true) {
            val dest = _destination.value ?: return
            val f = session.frame.value
            val lat = f?.latitude
            val lon = f?.longitude
            if (lat != null && lon != null) {
                val here = LatLon(lat, lon)
                if (!started) {
                    started = true
                    append(file, JSONObject().put("type", "start").put("t", System.currentTimeMillis()).put("to", dest.name).put("toLat", dest.at.lat).put("toLon", dest.at.lon))
                }
                if (TrafficRoutes.distanceM(here, dest.at) < ARRIVED_M) {
                    append(file, JSONObject().put("type", "arrived").put("t", System.currentTimeMillis()))
                    say("Arrived at ${dest.name}: the log stopped")
                    return
                }
                if (todayCount() >= DAILY_CAP) {
                    say("Stopped for today: $DAILY_CAP checks (the cap that keeps the cost safe)")
                    return
                }
                val line = JSONObject().put("type", "check").put("t", System.currentTimeMillis())
                    .put("lat", lat).put("lon", lon).put("kmh", f.speedMps?.let { it * 3.6 } ?: JSONObject.NULL)
                val (status, body) = withContext(Dispatchers.IO) { post(TrafficRoutes.request(here, dest.at, mode, f.headingDeg)) }
                // Only requests that reached Google count (they're the ones it bills).
                if (status != -1) count()
                if (mode == TrafficRoutes.TWO_WHEELER && TrafficRoutes.modeNotOffered(status, body)) {
                    // Two-wheeler isn't offered here: car routes instead, said in the log.
                    mode = TrafficRoutes.DRIVE
                    append(file, line.put("error", "two-wheeler not offered, using car routes"))
                    continue
                }
                line.put("mode", mode).put("http", status)
                if (status == 200) {
                    val routes = runCatching { TrafficRoutes.parse(body) }.getOrDefault(emptyList())
                    line.put("routes", JSONArray().apply { routes.forEach { put(it.json()) } })
                    say(summary(routes))
                } else {
                    line.put("error", body.take(400))
                    say(problem(status, body))
                }
                append(file, line)
            }
            delay(EVERY_MS)
        }
    }

    private fun summary(routes: List<TrafficRoute>): String {
        val r = routes.firstOrNull() ?: return "${clock()} · Google had no route"
        val km = { m: Int -> "%.1f km".format(m / 1000.0) }
        return listOfNotNull(
            clock(),
            "${(r.durationSec / 60.0).toInt()} min with traffic" + if (r.delayMin > 0) " (+${r.delayMin})" else "",
            km(r.slowM).takeIf { r.slowM > 0 }?.let { "$it slow" },
            km(r.jamM).takeIf { r.jamM > 0 }?.let { "$it jammed" },
            "${routes.size} routes".takeIf { routes.size > 1 },
        ).joinToString(" · ")
    }

    private fun problem(status: Int, body: String): String = "${clock()} · " + when (status) {
        -1 -> "no connection"
        403 -> "Google refused the key (${if ("not authorized" in body.lowercase() || "blocked" in body.lowercase()) "check its restrictions" else "403"})"
        429 -> "Google's limit for now"
        else -> "Google said $status"
    }

    private fun say(s: String) {
        _status.value = s
        prefs.edit().putString(STATUS, s).apply()
    }

    /** POSTs [body]; the HTTP status and answer (status -1 when Google can't be reached). */
    private fun post(body: JSONObject): Pair<Int, String> = runCatching {
        val c = URL(TrafficRoutes.URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Goog-Api-Key", key)
            c.setRequestProperty("X-Goog-FieldMask", TrafficRoutes.FIELD_MASK)
            // A key restricted to this Android app is checked against these two.
            c.setRequestProperty("X-Android-Package", context.packageName)
            certSha1()?.let { c.setRequestProperty("X-Android-Cert", it) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            c.disconnect()
        }
    }.getOrElse { -1 to (it.message ?: it.javaClass.simpleName) }

    /** The SHA-1 of the key this app is signed with, as Google's key restrictions want it. */
    private fun certSha1(): String? = runCatching {
        val pm = context.packageManager
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return null
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    private fun append(file: File, line: JSONObject) {
        runCatching { file.appendText(line.toString() + "\n") }
    }

    private fun count() {
        val m = month()
        val d = LocalDate.now().toString()
        prefs.edit()
            .putString(MONTH, m).putInt(MONTH_COUNT, (if (prefs.getString(MONTH, null) == m) prefs.getInt(MONTH_COUNT, 0) else 0) + 1)
            .putString(DAY, d).putInt(DAY_COUNT, todayCount() + 1)
            .apply()
    }

    private fun todayCount(): Int = if (prefs.getString(DAY, null) == LocalDate.now().toString()) prefs.getInt(DAY_COUNT, 0) else 0

    private fun month() = LocalDate.now().toString().take(7)
    private fun clock() = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun readDestination(): Destination? = runCatching {
        val o = JSONObject(prefs.getString(DEST, null) ?: return null)
        Destination(o.getString("name"), LatLon(o.getDouble("lat"), o.getDouble("lon")))
    }.getOrNull()

    companion object {
        const val LOG_FILE = "traffic-log.jsonl"
        /** How often Google is asked during a ride: about 30 checks an hour. */
        const val EVERY_MS = 2 * 60_000L
        /** Within this of the destination, the ride has arrived and the log stops. */
        const val ARRIVED_M = 300.0
        /** At most this many checks a day, whatever happens (about 10 hours of riding). */
        const val DAILY_CAP = 300
        private const val ON = "on"
        private const val DEST = "destination"
        private const val STATUS = "status"
        private const val MONTH = "month"
        private const val MONTH_COUNT = "month_count"
        private const val DAY = "day"
        private const val DAY_COUNT = "day_count"
    }
}
