package com.ridetrack.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.app.diag.ErrorLog
import com.ridetrack.app.moments.MomentRepository
import com.ridetrack.app.ride.RideSessionManager
import com.ridetrack.telemetry.obd.Elm327
import com.ridetrack.telemetry.obd.EngineWatch
import com.ridetrack.telemetry.obd.FaultCodes
import com.ridetrack.telemetry.obd.GearLearner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the OBD screen and the HUD show. */
data class ObdState(
    val bikeId: String? = null,
    val phase: Phase = Phase.IDLE,
    val linkKind: String? = null,
    val adapter: String? = null,
    val protocol: String? = null,
    val supported: List<Elm327.Pid> = emptyList(),
    val values: Map<Elm327.Pid, Double> = emptyMap(),
    val volts: Double? = null,
    val engine: EngineWatch.State = EngineWatch.State.OFF,
    val gear: Int? = null,
    val gearsLearned: Int = 0,
    val codes: List<String>? = null,
    val message: String? = null,
) {
    enum class Phase { IDLE, CONNECTING, CONNECTED, NO_ECU, FAILED }
}

/**
 * The bike's OBD adapter: connects for the test screen, and by itself for a ride on a bike that
 * has one; reads RPM fast, the rest slowly, and the adapter's voltage each second; works out the
 * gear; feeds the ride; warns when the engine runs hot, the battery doesn't charge, or a new
 * fault code appears. Reconnects quietly when the link drops. Read-only except "Clear codes".
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is asked for on the OBD screen before pairing.
class ObdManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val session: RideSessionManager,
    private val moments: MomentRepository,
    private val errors: ErrorLog,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** This session's exchanges, newest last (the test report). */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var job: Job? = null
    private var client: ObdClient? = null
    private val lock = Mutex()
    private var testing = false
    private var rideFile: File? = null

    fun watch() {
        scope.launch {
            session.active.map { it?.rideId to it?.bikeId }.distinctUntilChanged().collect { (rideId, bikeId) ->
                if (rideId != null && bikeId != null && adapterFor(bikeId) != null) {
                    rideFile = if (BuildConfig.TEST_BUILD) File(moments.dir(rideId), LOG_FILE) else null
                    start(bikeId)
                } else {
                    rideFile = null
                    if (!testing) stop()
                }
            }
        }
    }

    suspend fun adapterFor(bikeId: String): ObdAdapter? = ObdAdapters.decode(settings.settings.first().obdAdapters).firstOrNull { it.bikeId == bikeId }

    suspend fun pair(bikeId: String, address: String, name: String) {
        val list = ObdAdapters.decode(settings.settings.first().obdAdapters)
        val old = list.firstOrNull { it.bikeId == bikeId }
        // The same adapter keeps the gears it learned on this bike.
        settings.setObdAdapters(ObdAdapters.encode(ObdAdapters.with(list, bikeId, ObdAdapter(bikeId, address, name, if (old?.address == address) old.gears else emptyList()))))
    }

    suspend fun forget(bikeId: String) {
        stop()
        settings.setObdAdapters(ObdAdapters.encode(ObdAdapters.with(ObdAdapters.decode(settings.settings.first().obdAdapters), bikeId, null)))
    }

    suspend fun relearnGears(bikeId: String) {
        val list = ObdAdapters.decode(settings.settings.first().obdAdapters)
        val a = list.firstOrNull { it.bikeId == bikeId } ?: return
        settings.setObdAdapters(ObdAdapters.encode(ObdAdapters.with(list, bikeId, a.copy(gears = emptyList()))))
        learner = GearLearner()
        _state.value = _state.value.copy(gearsLearned = 0, gear = null)
    }

    /** The test screen: connect now (and stay connected until [stopTest]). */
    fun test(bikeId: String) {
        testing = true
        start(bikeId)
    }

    fun stopTest() {
        testing = false
        if (session.active.value?.bikeId != _state.value.bikeId) stop()
    }

    fun readCodes() = scope.launch {
        val c = client ?: return@launch
        val codes = lock.withLock { runCatching { c.codes() }.getOrNull() }
        _state.value = _state.value.copy(codes = codes ?: emptyList())
    }

    fun clearCodes() = scope.launch {
        val c = client ?: return@launch
        val ok = lock.withLock { runCatching { c.clearCodes() }.getOrDefault(false) }
        note(if (ok) "Fault codes cleared" else "The bike didn't clear the codes")
        if (ok) _state.value = _state.value.copy(codes = emptyList())
    }

    private fun start(bikeId: String) {
        if (job?.isActive == true && _state.value.bikeId == bikeId) return
        job?.cancel()
        _log.value = emptyList()
        job = scope.launch { run(bikeId) }
    }

    fun stop() {
        job?.cancel()
        job = null
        client?.close()
        client = null
        _state.value = ObdState()
    }

    private var learner = GearLearner()
    private val watch = EngineWatch()
    private var savedRatios = 0

    private suspend fun run(bikeId: String) {
        val adapter = adapterFor(bikeId) ?: return
        learner = GearLearner(adapter.gears)
        savedRatios = adapter.gears.size
        _state.value = ObdState(bikeId = bikeId, phase = ObdState.Phase.CONNECTING, gearsLearned = adapter.gears.size)
        var announcedCodes: Set<String>? = null
        while (scope.isActive) {
            val bt = context.getSystemService<BluetoothManager>()?.adapter
            val device = runCatching { bt?.getRemoteDevice(adapter.address) }.getOrNull()
            if (device == null || bt?.isEnabled != true) {
                fail("Bluetooth is off")
                delay(RETRY_MS)
                continue
            }
            var c: ObdClient? = null
            for (link in ObdLink.forDevice(context, device)) {
                val tryClient = ObdClient(link, ::log)
                if (tryClient.connect()) { c = tryClient; break }
                tryClient.close()
            }
            if (c == null) {
                watch.onLink(false)
                fail("Can't reach ${adapter.name}: key on, and the adapter in reach?")
                delay(RETRY_MS)
                continue
            }
            client = c
            watch.onLink(true)
            _state.value = _state.value.copy(
                phase = if (c.ecu) ObdState.Phase.CONNECTED else ObdState.Phase.NO_ECU,
                linkKind = c.kind, adapter = c.adapter, protocol = c.protocol,
                supported = Elm327.Pid.entries.filter { it.code in c.supported },
                engine = watch.state,
                message = if (c.ecu) null else (c.problem ?: "Connected to the adapter, but the bike's ECU didn't answer: voltage only"),
            )
            if (c.ecu && announcedCodes == null) {
                val codes = lock.withLock { runCatching { c.codes() }.getOrNull() }
                _state.value = _state.value.copy(codes = codes)
                announcedCodes = codes?.toSet()
                if (!codes.isNullOrEmpty() && session.active.value != null) alert("Engine fault code", codes.joinToString(", ") { "$it: ${FaultCodes.describe(it)}" })
            }
            try {
                poll(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("link lost: ${e.message}")
            }
            c.close()
            client = null
            watch.onLink(false)
            _state.value = _state.value.copy(phase = ObdState.Phase.CONNECTING, engine = EngineWatch.State.OFF, message = "Link lost: reconnecting")
            delay(RETRY_MS)
        }
    }

    private suspend fun poll(c: ObdClient) {
        val slow = Elm327.Pid.entries.filter { it.code in c.supported && it != Elm327.Pid.RPM && it != Elm327.Pid.SPEED }
        var slowAt = 0
        var lastVolts = 0L
        var lastSlow = 0L
        var hotSince: Long? = null
        var lowSince: Long? = null
        val warned = HashSet<String>()
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val (rpm, speed) = lock.withLock { c.read(Elm327.Pid.RPM) to c.read(Elm327.Pid.SPEED) }
            var volts = _state.value.volts
            if (now - lastVolts >= 1_000) {
                volts = lock.withLock { c.voltage() } ?: volts
                lastVolts = now
            }
            val values = _state.value.values.toMutableMap()
            rpm?.let { values[Elm327.Pid.RPM] = it }
            speed?.let { values[Elm327.Pid.SPEED] = it }
            if (slow.isNotEmpty() && now - lastSlow >= 2_000 / slow.size.coerceAtLeast(1)) {
                val p = slow[slowAt++ % slow.size]
                lock.withLock { c.read(p) }?.let { values[p] = it }
                lastSlow = now
            }
            val engine = watch.onReading(now, volts, rpm)
            val roadSpeed = speed ?: session.frame.value?.speedMps?.let { it * 3.6 }
            val gear = learner.onReading(rpm, roadSpeed)
            if (learner.ratios.size != savedRatios) saveGears()
            _state.value = _state.value.copy(values = values, volts = volts, engine = engine, gear = gear, gearsLearned = learner.ratios.size)
            if (session.active.value?.bikeId == _state.value.bikeId) {
                session.engine(rpm, gear)
                // Alerts during a ride: each once, after holding a while.
                val s = settings.settings.first()
                val coolant = values[Elm327.Pid.COOLANT]
                hotSince = if (coolant != null && coolant >= s.obdCoolantAlertC) hotSince ?: now else null
                if (hotSince != null && now - hotSince >= 20_000 && warned.add("hot")) alert("Engine running hot", "Coolant ${coolant!!.toInt()} °C. Stop and let it cool if it keeps rising.")
                lowSince = if (engine == EngineWatch.State.RUNNING && volts != null && volts * 10 < s.obdLowVoltTenths) lowSince ?: now else null
                if (lowSince != null && now - lowSince >= 30_000 && warned.add("volt")) alert("Battery not charging", "%.1f V with the engine running: the charging system may be weak.".format(Locale.US, volts))
            }
            delay(if (rpm == null) 250 else 50)
        }
    }

    private suspend fun saveGears() {
        val bikeId = _state.value.bikeId ?: return
        val list = ObdAdapters.decode(settings.settings.first().obdAdapters)
        val a = list.firstOrNull { it.bikeId == bikeId } ?: return
        savedRatios = learner.ratios.size
        settings.setObdAdapters(ObdAdapters.encode(ObdAdapters.with(list, bikeId, a.copy(gears = learner.ratios))))
        log("gears learned: ${learner.ratios.joinToString(" · ") { "%.0f".format(Locale.US, it) }} rpm per km/h")
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(phase = ObdState.Phase.FAILED, message = message, engine = EngineWatch.State.OFF)
    }

    private fun note(message: String) {
        _state.value = _state.value.copy(message = message)
        log(message)
    }

    private fun log(line: String) {
        val stamped = "${TIME.format(Date())}  $line"
        _log.value = (_log.value + stamped).takeLast(200)
        rideFile?.let { f -> runCatching { f.appendText(stamped + "\n") } }
    }

    private fun alert(title: String, text: String) {
        log("alert: $title · $text")
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, RideTrackApp.RIDE_ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(ALERT_ID + (title.hashCode() and 0xff), n)
    }

    companion object {
        const val LOG_FILE = "obd-log.txt"
        private const val RETRY_MS = 5_000L
        private const val ALERT_ID = 7_500
        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
