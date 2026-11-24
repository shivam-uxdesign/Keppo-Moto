package com.ridetrack.app.keepmoving

import android.content.Context
import com.ridetrack.app.data.RideRepository
import com.ridetrack.app.moments.MomentRepository
import com.ridetrack.telemetry.model.DataSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Step 4: the rider's own traffic memory, kept on the phone ([FILE] in the app's files, never
 * uploaded). Past rides are read once; each new ride is learned when it's saved (demo rides
 * aren't). Also learns the rider's own yellow from rides with a traffic log, and hands it to
 * [KeepMode] for scoring.
 */
class TrafficMemory(
    private val context: Context,
    private val rides: RideRepository,
    private val moments: MomentRepository,
    private val scope: CoroutineScope,
) {
    /** What it knows, in plain numbers, for Profile. */
    data class Summary(val rides: Int, val cells: Int, val yellowTrips: Int, val yellowStops: Int) {
        val yellowChance: Double get() = Memory.yellowChance(yellowTrips, yellowStops)
    }

    private val lock = Mutex()
    @Volatile private var stats: Map<Memory.Cell, Memory.Stat> = emptyMap()
    private val learned = HashSet<String>()
    private var yellowTrips = 0
    private var yellowStops = 0
    private val _summary = MutableStateFlow(Summary(0, 0, 0, 0))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    /** The memory as it is now (empty until it's loaded). */
    fun snapshot(): Map<Memory.Cell, Memory.Stat> = stats

    fun start() {
        scope.launch(Dispatchers.IO) {
            lock.withLock { load() }
            rides.observeCompleted().collect { list ->
                val fresh = list.filter { it.id !in learned && it.source == DataSourceKind.PHONE }
                if (fresh.isNotEmpty()) lock.withLock { fresh.forEach { learn(it.id) }; save() }
            }
        }
    }

    private suspend fun learn(rideId: String) {
        runCatching {
            val fixes = rides.track(rideId).samples.mapNotNull { s ->
                val lat = s.latitude ?: return@mapNotNull null
                val lon = s.longitude ?: return@mapNotNull null
                if ((s.gpsAccuracyM ?: 0.0) > 30) return@mapNotNull null
                Memory.Fix(s.timeMillis, LatLon(lat, lon), s.speedMps, s.headingDeg)
            }
            val add = Memory.learn(fixes)
            val merged = HashMap(stats)
            add.forEach { (k, v) -> merged[k] = (merged[k] ?: Memory.Stat.EMPTY) + v }
            stats = merged
            File(moments.dir(rideId), TrafficLogger.LOG_FILE).takeIf { it.exists() }?.let { log ->
                val (t, st) = Memory.yellowFromLog(log.readLines(), fixes)
                yellowTrips += t
                yellowStops += st
            }
        }
        learned += rideId
        publish()
    }

    private fun publish() {
        _summary.value = Summary(learned.size, stats.size, yellowTrips, yellowStops)
        // Less traffic and No foot down score yellow with the rider's own number from now on.
        KeepMode.personalYellow = if (yellowTrips > 0) Memory.yellowChance(yellowTrips, yellowStops) else null
    }

    private fun file() = File(context.filesDir, FILE)

    private fun load() {
        runCatching {
            val f = file()
            if (!f.exists()) return
            val map = HashMap<Memory.Cell, Memory.Stat>()
            f.bufferedReader().useLines { lines ->
                lines.forEach { l ->
                    val p = l.split('\t')
                    when (p.firstOrNull()) {
                        "R" -> learned += p.drop(1)
                        "Y" -> { yellowTrips = p[1].toInt(); yellowStops = p[2].toInt() }
                        "C" -> map[Memory.Cell(p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt())] =
                            Memory.Stat(p[5].toDouble(), p[6].toDouble(), p[7].toDouble(), p[8].toDouble(), p[9].toInt())
                    }
                }
            }
            stats = map
        }
        publish()
    }

    private fun save() {
        runCatching {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.bufferedWriter().use { w ->
                w.write("R\t" + learned.joinToString("\t") + "\n")
                w.write("Y\t$yellowTrips\t$yellowStops\n")
                stats.forEach { (k, v) ->
                    w.write("C\t${k.row}\t${k.col}\t${k.dir}\t${k.bucket}\t${n(v.secs)}\t${n(v.stopped)}\t${n(v.crawl)}\t${n(v.speedSecs)}\t${v.rides}\n")
                }
            }
            tmp.renameTo(file())
        }
    }

    /** Numbers written the same way whatever the phone's language. */
    private fun n(x: Double) = String.format(java.util.Locale.US, "%.1f", x)

    companion object {
        const val FILE = "traffic-memory.tsv"
    }
}
