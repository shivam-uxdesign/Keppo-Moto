package com.ridetrack.app.ui.home

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.ridetrack.app.hud.OverlayPermission
import com.ridetrack.app.moments.MicChoice
import com.ridetrack.app.moments.MicType
import com.ridetrack.app.moments.Microphones
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.ui.components.GeoPoint
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.fuel.Fuel
import com.ridetrack.app.fuel.FuelPrompt
import com.ridetrack.app.ride.RideContinuation
import com.ridetrack.app.ride.RideNames
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.common.Odometer
import com.ridetrack.app.ui.common.RideTotals
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.state.RideState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

enum class GpsReadiness { READY, PERMISSION_NEEDED, DISABLED, NO_HARDWARE }

data class HomeUiState(
    val loading: Boolean = true,
    val bike: Bike? = null,
    val bikes: List<Bike> = emptyList(),
    val hasBikes: Boolean = false,
    val starting: Boolean = false,
    val demoMode: Boolean = false,
    val rideState: RideState = RideState.Idle,
    val totals: RideTotals? = null,
    val unfinished: Ride? = null,
    /** The unfinished ride stopped recently enough to carry on as the same ride. */
    val unfinishedRecent: Boolean = false,
    val gps: GpsReadiness = GpsReadiness.READY,
    val sensors: SensorAvailability = SensorAvailability(accelerometer = true, gyroscope = true, magnetometer = true),
    /** This week, for the one-line summary. */
    val week: PeriodStats? = null,
    /** Current odometer per bike id, in km; null = not set. */
    val odometers: Map<String, Double?> = emptyMap(),
    /** "Ridden today" etc. per bike id. */
    val lastRidden: Map<String, String> = emptyMap(),
    /** Pre-ride checks, problems first. */
    val checks: List<ReadyCheck> = emptyList(),
    /** The latest ride, its route and moments. */
    val lastRide: LastRide? = null,
    /** The latest ride just finished: it leads Home, with Share, until the next ride or it's closed. */
    val justRode: Boolean = false,
    /** Reminders for the selected bike, most due first. */
    val care: List<CareStatus> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    /** Moments and "film when I speak", for the quick toggles above Start. */
    val momentsOn: Boolean = false,
    val voiceOn: Boolean = false,
    /** Lifetime numbers per bike id, for the back of the card. */
    val bikeStats: Map<String, BikeStats> = emptyMap(),
    /** A fresh install: offer to bring rides back from Google Drive. */
    val offerRestore: Boolean = false,
)

data class LastRide(val ride: Ride, val route: List<GeoPoint>, val moments: List<Moment>)

/** Phone state that can change while Home is open; read again on resume and every few seconds. */
data class Environment(
    val gps: GpsReadiness,
    val sensors: SensorAvailability,
    val mics: List<MicChoice> = emptyList(),
    val cameraAllowed: Boolean = true,
    val overlayAllowed: Boolean = true,
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    val freeBytes: Long? = null,
)

private data class Extras(
    val environment: Environment,
    val starting: Boolean,
    val lastRide: LastRide?,
    val momentCounts: Map<String, Int>,
    val unfinished: Pair<Ride, Long>?,
)

/** Fuel on Home: a pump stop to confirm, the last price paid, and the last ride's fuel. */
data class HomeFuel(val prompt: FuelPrompt? = null, val lastPrice: Double? = null, val lastRide: String? = null)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val fuel: StateFlow<HomeFuel> = combine(c.fuel.fills, c.fuel.prompts, c.rides.observeCompleted()) { fills, prompts, rides ->
        val last = rides.filter { it.source != DataSourceKind.DEMO }.maxByOrNull { it.startTimeMillis }
        val estimate = last?.let { r -> c.fuel.mileage(r.bikeId, fills, rides).estimate(r.stats.distanceM / 1000) }
        HomeFuel(
            prompt = prompts.maxByOrNull { it.timeMillis },
            lastPrice = fills.sortedBy { it.timeMillis }.lastOrNull { it.pricePerLitre != null }?.pricePerLitre,
            lastRide = estimate?.let { (l, cost) -> "≈${String.format(java.util.Locale.US, "%.1f", l)} L" + (cost?.let { " · ${Fuel.money(it)}" } ?: "") },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeFuel())

    /** Saves a fill-up from a pump stop prompt. */
    fun addFill(prompt: FuelPrompt, litres: Double, amount: Double?, price: Double?) {
        viewModelScope.launch {
            c.fuel.add(Fuel.newFill(prompt.bikeId, prompt.timeMillis, litres, amount, price, prompt.station, if (prompt.amount != null) "sms" else "typed"))
            c.fuel.dismiss(prompt)
        }
    }

    fun dismissFuel(prompt: FuelPrompt) {
        viewModelScope.launch { c.fuel.dismiss(prompt) }
    }

    private val environment = MutableStateFlow(readEnvironment())
    private val starting = MutableStateFlow(false)
    /** The newest unfinished ride and when it was last written. */
    private val unfinishedInfo = MutableStateFlow<Pair<Ride, Long>?>(null)

    /** The latest ride (demo rides only in demo mode), with its route and moments. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lastRide = combine(c.rides.observeCompleted(), c.settings.settings.map { it.demoMode }.distinctUntilChanged()) { rides, demo ->
        rides.filter { demo || it.source != DataSourceKind.DEMO }.maxByOrNull { it.startTimeMillis }
    }
        .distinctUntilChanged()
        .flatMapLatest { ride ->
            if (ride == null) {
                flowOf(null)
            } else {
                val route = flow { emit(c.routes.route(ride.id)) }.flowOn(Dispatchers.IO).onStart { emit(emptyList()) }
                combine(route, c.moments.observe(ride.id).onStart { emit(emptyList()) }) { r, m -> LastRide(ride, r, m) }
            }
        }
        .onStart { emit(null) }

    val state: StateFlow<HomeUiState> = combine(
        c.bikes.observeBikes(),
        c.settings.settings,
        c.rides.observeCompleted(),
        combine(c.rides.observeInProgress(), c.session.active, c.session.state) { inProgress, active, rideState ->
            Triple(inProgress.firstOrNull { it.id != active?.rideId }, rideState, active)
        },
        combine(environment, starting, lastRide, c.moments.observeCountsByBike(), unfinishedInfo) { e, s, l, m, u -> Extras(e, s, l, m, u) },
    ) { bikes, settings, rides, (unfinished, rideState, _), (env, isStarting, last, momentCounts, unfinishedAt) ->
        val bike = bikes.firstOrNull { it.id == settings.selectedBikeId } ?: bikes.firstOrNull()
        val totals = RideTotals.from(rides)
        val now = ZonedDateTime.now()
        val real = rides.filter { it.source != DataSourceKind.DEMO }
        val nowMillis = System.currentTimeMillis()
        val odometers = bikes.associate { it.id to Odometer.readingKm(it, rides) }
        val ended = last?.ride?.let { it.endTimeMillis ?: it.startTimeMillis }
        val inputs = ReadyInputs(
            demo = settings.demoMode,
            gps = env.gps,
            calibrated = bike?.calibration != null,
            momentsOn = settings.moments.enabled,
            micChoice = MicChoice.decode(settings.moments.mic),
            mics = env.mics,
            cameraAllowed = env.cameraAllowed,
            hudOn = settings.hud.enabled,
            overlayAllowed = env.overlayAllowed,
            batteryPct = env.batteryPct,
            charging = env.charging,
            freeBytes = env.freeBytes,
            rideMinutes = env.batteryPct?.takeIf { !env.charging }?.let { pct ->
                c.batteryWatch.learnedPerHour(settings.moments.enabled)?.let { rate -> (pct / rate * 60).toInt() }
            },
        )
        HomeUiState(
            loading = false,
            bike = bike,
            bikes = bikes,
            starting = isStarting,
            hasBikes = bikes.isNotEmpty(),
            offerRestore = bikes.isEmpty() && rides.isEmpty() && !settings.backup.restoreCardDismissed,
            demoMode = settings.demoMode,
            rideState = rideState,
            totals = totals.takeIf { it.rideCount > 0 },
            unfinished = unfinished,
            unfinishedRecent = unfinished != null && unfinishedAt?.first?.id == unfinished.id &&
                nowMillis - unfinishedAt.second < RideContinuation.CONTINUE_WINDOW_MILLIS,
            gps = env.gps,
            sensors = env.sensors,
            week = HomeStats.from(rides, now).week,
            odometers = odometers,
            lastRidden = bikes.associate { b -> b.id to lastRiddenLabel(real.filter { it.bikeId == b.id }.maxOfOrNull { it.startTimeMillis }, now) },
            checks = Readiness.checks(inputs),
            lastRide = last,
            justRode = last != null && ended != null && nowMillis - ended < JUST_RODE_MILLIS && settings.homeDismissedRide != last.ride.id,
            care = bike?.let { b -> BikeCare.sorted(BikeCare.decode(settings.bikeCare).filter { it.bikeId == b.id }, odometers[b.id], nowMillis) }.orEmpty(),
            milestones = Milestones.of(rides, nowMillis),
            momentsOn = settings.moments.enabled,
            voiceOn = settings.moments.voice,
            bikeStats = bikes.associate { b -> b.id to BikeStats.from(b, rides, momentCounts[b.id] ?: 0) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Closes the "just after a ride" card; the ride stays on Home as the last ride. */
    fun dismissJustRode(rideId: String) {
        viewModelScope.launch { c.settings.dismissHomeRide(rideId) }
    }

    fun setMoments(on: Boolean) = editMoments { it.copy(enabled = on) }

    fun setVoice(on: Boolean) = editMoments { it.copy(voice = on) }

    private fun editMoments(change: (com.ridetrack.app.data.MomentSettings) -> com.ridetrack.app.data.MomentSettings) {
        viewModelScope.launch { c.settings.setMoments(change(c.settings.settings.first().moments)) }
    }

    fun dismissRestore() {
        viewModelScope.launch { c.settings.dismissRestoreCard() }
    }

    fun selectBike(id: String) {
        viewModelScope.launch { c.settings.setSelectedBike(id) }
    }

    /** Call when returning to the screen: permissions/GPS may have changed in Settings. */
    fun refreshEnvironment() {
        environment.value = readEnvironment()
    }

    init {
        checkUnfinished()
    }

    /** Saves unfinished rides too old to carry on; remembers the newest for "Continue". */
    fun checkUnfinished() {
        viewModelScope.launch {
            if (c.session.state.value.isActive) return@launch
            c.continuation.saveStale(RideContinuation.CONTINUE_WINDOW_MILLIS)
            unfinishedInfo.value = c.continuation.latest()
        }
    }

    /** Carries on the unfinished ride as the same ride. */
    fun continueUnfinished(onStarted: () -> Unit) {
        if (starting.value) return
        starting.value = true
        viewModelScope.launch {
            val id = c.continuation.continueIfRecent(RideContinuation.CONTINUE_WINDOW_MILLIS, "rider tapped Continue")
            starting.value = false
            if (id != null) onStarted() else checkUnfinished()
        }
    }

    private fun readEnvironment(): Environment {
        val ctx = c.appContext
        val inv = c.sensorInventory
        val gps = when {
            !inv.hasGpsHardware() -> GpsReadiness.NO_HARDWARE
            !Permissions.hasFineLocation(ctx) -> GpsReadiness.PERMISSION_NEEDED
            !inv.isGpsEnabled() -> GpsReadiness.DISABLED
            else -> GpsReadiness.READY
        }
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return Environment(
            gps = gps,
            sensors = inv.availability(),
            mics = runCatching { Microphones.available(ctx).filter { it.type != MicType.PHONE } }.getOrDefault(emptyList()),
            cameraAllowed = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            overlayAllowed = OverlayPermission.isGranted(ctx),
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = plugged,
            freeBytes = runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrNull(),
        )
    }

    /** Starts recording immediately on [bike]; GPS status is handled on the live screen. */
    fun startRide(bike: Bike, onStarted: () -> Unit) {
        if (starting.value) return
        starting.value = true
        viewModelScope.launch {
            c.settings.setSelectedBike(bike.id)
            // A new ride: anything left unfinished is saved as it is.
            c.continuation.saveStale(0L)
            val id = c.session.startNow(bike)
            starting.value = false
            if (id != null || c.session.state.value.isActive) onStarted()
        }
    }

    fun saveUnfinished(ride: Ride) {
        viewModelScope.launch { c.rides.recover(ride, RideNames.forStart(ride.startTimeMillis)) }
    }

    fun discardUnfinished(ride: Ride) {
        viewModelScope.launch {
            c.trash.deleteRide(ride.id)
        }
    }

    private companion object {
        /** How long a finished ride leads Home. */
        const val JUST_RODE_MILLIS = 12 * 3_600_000L
    }
}
