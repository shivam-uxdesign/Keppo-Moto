package com.ridetrack.app.keepmoving

import android.app.Activity
import android.app.Application
import com.google.android.libraries.navigation.CustomRoutesOptions
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationRoadStretchRenderingData
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import com.ridetrack.app.ride.RideSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Keep moving, steps 2 and 3 (test builds): Google's Navigation SDK guides along the route the
 * rider picked (its route token), with Google's arrows and voice; meanwhile the road ahead is
 * watched and, when a clearly clearer road appears ([Reroute]), it's offered with a 10-second
 * "Keep this road", then switched to.
 */
class KeepMovingGuide(
    private val app: Application,
    private val session: RideSessionManager,
    private val log: TrafficLogger,
    private val scope: CoroutineScope,
) {
    /** A clearer road on offer; switched to at [atMs] unless the rider keeps theirs. */
    data class Offer(val route: Ranked, val atMs: Long, val savedJamM: Int, val savedSlowM: Int)

    private val _active = MutableStateFlow(false)
    /** Directions are running. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)
    /** The last thing the guide did, in plain words ("Switched to a clearer road · 0.8 km less jam"). */
    val note: StateFlow<String?> = _note.asStateFlow()

    var destination: Destination? = null
        private set

    @Volatile private var navigator: Navigator? = null
    private var watch: Job? = null
    private var lastAskMs = 0L
    private var lastSwitchMs = 0L
    private var keptUntilMs = 0L

    init {
        // The ride ended: so do its directions.
        scope.launch {
            session.active.map { it != null }.distinctUntilChanged().collect { riding ->
                if (!riding && _active.value) withContext(Dispatchers.Main) { stop("Ride ended: directions stopped") }
            }
        }
    }

    /**
     * Google's terms screen (once), then directions along [route] to [dest]. Returns null when
     * guidance started, or what went wrong in plain words.
     */
    suspend fun start(activity: Activity, dest: Destination, route: TrafficRoute): String? {
        if (!NavigationApi.areTermsAccepted(app)) {
            val ok = suspendCancellableCoroutine { c ->
                NavigationApi.showTermsAndConditionsDialog(activity, "Keppo Moto") { accepted -> if (c.isActive) c.resume(accepted) }
            }
            if (!ok) return "Directions need Google's terms accepted"
        }
        val nav = navigator ?: suspendCancellableCoroutine<Result<Navigator>> { c ->
            NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(n: Navigator) { if (c.isActive) c.resume(Result.success(n)) }
                override fun onError(code: Int) { if (c.isActive) c.resume(Result.failure(IllegalStateException(errorText(code)))) }
            })
        }.getOrElse { return it.message }
        navigator = nav
        destination = dest
        val status = follow(nav, dest, route)
        if (status != Navigator.RouteStatus.OK) return "Google couldn't start directions (${status.name.lowercase().replace('_', ' ')})"
        nav.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
        nav.startGuidance()
        nav.addArrivalListener { e -> if (e.isFinalDestination) stop("Arrived at ${dest.name}") }
        _active.value = true
        _note.value = null
        lastSwitchMs = System.currentTimeMillis()
        watch?.cancel()
        watch = scope.launch { watchAhead() }
        return null
    }

    /** Ends directions (the ride itself goes on). */
    fun stop(note: String? = null) {
        watch?.cancel()
        watch = null
        _offer.value = null
        navigator?.let { n ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { n.stopGuidance() }
                runCatching { n.clearDestinations() }
            }
        }
        _active.value = false
        _note.value = note
    }

    /** The rider keeps the road they're on: no offers for a while. */
    fun keepThisRoad() {
        _offer.value = null
        keptUntilMs = System.currentTimeMillis() + Reroute.KEEP_FOR_MS
        _note.value = "Kept your road"
    }

    /** Switch to the offered road now, without waiting for the countdown. */
    fun switchNow() {
        val o = _offer.value ?: return
        scope.launch { switchTo(o) }
    }

    /** Follows [route] by its token (exactly that road), or Google's own two-wheeler route without one. */
    private suspend fun follow(nav: Navigator, dest: Destination, route: TrafficRoute?): Navigator.RouteStatus {
        val waypoint = Waypoint.builder().setLatLng(dest.at.lat, dest.at.lon).setTitle(dest.name).build()
        val mode = if (log.lastMode == TrafficRoutes.DRIVE) RoutingOptions.TravelMode.DRIVING else RoutingOptions.TravelMode.TWO_WHEELER
        val token = route?.routeToken
        val future = withContext(Dispatchers.Main) {
            if (token != null) {
                nav.setDestinations(listOf(waypoint), CustomRoutesOptions.builder().setRouteToken(token).setTravelMode(mode).build())
            } else {
                nav.setDestination(waypoint, RoutingOptions().travelMode(mode))
            }
        }
        return suspendCancellableCoroutine { c -> future.setOnResultListener { s -> if (c.isActive) c.resume(s) } }
    }

    /** While guiding: score the road ahead from the navigation's own traffic; look for better only when it's bad. */
    private suspend fun watchAhead() {
        while (_active.value) {
            delay(30_000)
            val nav = navigator ?: return
            val o = _offer.value
            if (o != null) {
                if (System.currentTimeMillis() >= o.atMs) switchTo(o)
                continue
            }
            val (line, stretches) = withContext(Dispatchers.Main) { currentRoad(nav) } ?: continue
            val total = line.zipWithNext { a, b -> TrafficRoutes.distanceM(a, b) }.sum().toInt()
            val now = System.currentTimeMillis()
            if (!Reroute.shouldLook(stretches, total, now, lastAskMs, keptUntilMs, lastSwitchMs)) continue
            val f = session.frame.value ?: continue
            val lat = f.latitude ?: continue
            val lon = f.longitude ?: continue
            val dest = destination ?: continue
            lastAskMs = now
            val answer = log.routes(LatLon(lat, lon), dest.at, f.headingDeg)
            if (answer.status != 200) continue
            val ranked = RouteScore.rank(answer.routes)
            val current = Reroute.colours(stretches, total)
            val pick = Reroute.pick(ranked, line, RouteScore.cost(current)) ?: continue
            _offer.value = Offer(
                pick,
                atMs = System.currentTimeMillis() + Reroute.COUNTDOWN_MS,
                savedJamM = (current.jamM - pick.colours.jamM).coerceAtLeast(0),
                savedSlowM = (current.slowM - pick.colours.slowM).coerceAtLeast(0),
            )
            // Check back right after the countdown.
            delay(Reroute.COUNTDOWN_MS)
            _offer.value?.let { if (System.currentTimeMillis() >= it.atMs) switchTo(it) }
        }
    }

    private suspend fun switchTo(o: Offer) {
        if (_offer.value != o) return
        _offer.value = null
        val nav = navigator ?: return
        val dest = destination ?: return
        val status = follow(nav, dest, o.route.route)
        lastSwitchMs = System.currentTimeMillis()
        _note.value = if (status == Navigator.RouteStatus.OK) {
            "Switched to a clearer road" + listOfNotNull(
                km(o.savedJamM).takeIf { o.savedJamM > 0 }?.let { "$it less jam" },
                km(o.savedSlowM).takeIf { o.savedSlowM > 0 }?.let { "$it less slow" },
            ).joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" }
        } else {
            "Couldn't switch roads (${status.name.lowercase().replace('_', ' ')})"
        }
        if (status == Navigator.RouteStatus.OK) runCatching { withContext(Dispatchers.Main) { nav.startGuidance() } }
    }

    /** The road being followed now: its line and the navigation's traffic on it. */
    private fun currentRoad(nav: Navigator): Pair<List<LatLon>, List<Reroute.Stretch>>? = runCatching {
        val seg = nav.currentRouteSegment ?: return null
        val line = seg.latLngs.map { LatLon(it.latitude, it.longitude) }
        if (line.size < 2) return null
        val stretches = seg.trafficData?.roadStretchRenderingDataList.orEmpty().map { s ->
            Reroute.Stretch(
                s.offsetMeters,
                s.lengthMeters,
                when (s.style) {
                    NavigationRoadStretchRenderingData.Style.TRAFFIC_JAM -> Speed.JAM
                    NavigationRoadStretchRenderingData.Style.SLOWER_TRAFFIC -> Speed.SLOW
                    else -> Speed.UNKNOWN
                },
            )
        }
        line to stretches
    }.getOrNull()

    private fun km(m: Int) = if (m >= 1000) "%.1f km".format(m / 1000.0) else "${(m / 10) * 10} m"

    private fun errorText(code: Int) = when (code) {
        NavigationApi.ErrorCode.NOT_AUTHORIZED -> "The key isn't allowed to use the Navigation SDK: check it's ticked on the key"
        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "Directions need Google's terms accepted"
        NavigationApi.ErrorCode.NETWORK_ERROR -> "No connection for directions"
        NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "Directions need location permission"
        else -> "Directions couldn't start ($code)"
    }
}
