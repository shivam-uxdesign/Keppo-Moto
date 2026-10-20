package com.ridetrack.app.ui.profile

import com.ridetrack.app.AppContainer
import com.ridetrack.app.studio.writeAtomically
import com.ridetrack.telemetry.model.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Each ride's facts for badges (highest point, hairpins, the ground it covered, its places and
 * states), worked out once from its samples and kept in files/achievements/<rideId>.json.
 * Places need a connection: a ride saved offline is looked up again later.
 */
class RideFactsStore(private val c: AppContainer) {
    private val dir = File(c.appContext.filesDir, "achievements").apply { mkdirs() }
    private fun file(id: String) = File(dir, "$id.json")

    /** Facts for every ride that has them now, working out at most [budget] missing ones first. */
    suspend fun load(rideIds: List<String>, budget: Int = 6): Map<String, RideFacts> = withContext(Dispatchers.IO) {
        var left = budget
        rideIds.mapNotNull { id ->
            val cached = read(id)
            if (cached != null && (cached.second || left <= 0)) return@mapNotNull id to cached.first
            if (left <= 0) return@mapNotNull null
            left--
            runCatching { work(id, cached?.first) }.getOrNull()?.let { id to it }
        }.toMap()
    }

    private suspend fun work(id: String, old: RideFacts?): RideFacts {
        val samples = c.rides.track(id).samples
        val base = old ?: of(samples)
        val ends = samples.filter { it.latitude != null && it.longitude != null }.let { s -> listOfNotNull(s.firstOrNull(), s.lastOrNull()) }
        val places = ends.mapNotNull { p -> runCatching { c.places.placeAt(p.latitude!!, p.longitude!!) }.getOrNull() }.distinct()
        val states = ends.mapNotNull { p -> runCatching { c.places.stateAt(p.latitude!!, p.longitude!!) }.getOrNull() }.distinct()
        val looked = ends.isEmpty() || places.isNotEmpty()
        val facts = base.copy(places = places.ifEmpty { base.places }, states = states.ifEmpty { base.states })
        write(id, facts, looked)
        return facts
    }

    private fun read(id: String): Pair<RideFacts, Boolean>? = runCatching {
        val f = file(id)
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        val cells = o.optJSONObject("cells")?.let { c -> c.keys().asSequence().associateWith { c.getDouble(it) } }.orEmpty()
        RideFacts(
            maxAltitudeM = if (o.has("maxAltitudeM")) o.getDouble("maxAltitudeM") else null,
            hairpins = o.optInt("hairpins"),
            cells = cells,
            places = o.optJSONArray("places").strings(),
            states = o.optJSONArray("states").strings(),
        ) to o.optBoolean("placesLooked", false)
    }.getOrNull()

    private fun write(id: String, f: RideFacts, looked: Boolean) {
        val o = JSONObject()
            .put("hairpins", f.hairpins)
            .put("cells", JSONObject().apply { f.cells.forEach { (k, v) -> put(k, v) } })
            .put("places", JSONArray(f.places))
            .put("states", JSONArray(f.states))
            .put("placesLooked", looked)
        f.maxAltitudeM?.let { o.put("maxAltitudeM", it) }
        runCatching { file(id).writeAtomically(o.toString()) }
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    companion object {
        /** The facts in a ride's samples (pure, tested). */
        fun of(samples: List<TelemetrySample>): RideFacts {
            val s = samples.sortedBy { it.timeMillis }
            // GPS heights are rough; only readings with a decent fix count.
            val alt = s.filter { (it.gpsAccuracyM ?: 0.0) <= 25.0 }.mapNotNull { it.altitudeM }.maxOrNull()
            val cells = LinkedHashMap<String, Double>()
            var prev: TelemetrySample? = null
            s.filter { it.latitude != null && it.longitude != null }.forEach { p ->
                val key = Achievements.cell(p.latitude!!, p.longitude!!)
                val d = prev?.let { a -> Achievements.meters(a.latitude!! to a.longitude!!, p.latitude!! to p.longitude!!) } ?: 0.0
                // Jumps over 1 km between readings are GPS gaps, not riding.
                cells[key] = (cells[key] ?: 0.0) + if (d < 1_000) d else 0.0
                prev = p
            }
            return RideFacts(maxAltitudeM = alt, hairpins = hairpins(s), cells = cells)
        }

        /** Hairpins in a ride: each 150° turn within 20 s, counted once. */
        fun hairpins(s: List<TelemetrySample>): Int {
            var n = 0
            var from = 0
            val moving = s.filter { it.headingDeg != null && (it.speedMps ?: 0.0) > 2.0 }
            while (from < moving.size) {
                var turned = 0.0
                var end = -1
                for (j in from + 1 until moving.size) {
                    if (moving[j].timeMillis - moving[from].timeMillis > 20_000) break
                    var d = moving[j].headingDeg!! - moving[j - 1].headingDeg!!
                    if (d > 180) d -= 360
                    if (d < -180) d += 360
                    turned += d
                    if (kotlin.math.abs(turned) >= 150) { end = j; break }
                }
                if (end >= 0) { n++; from = end + 1 } else from++
            }
            return n
        }
    }
}
