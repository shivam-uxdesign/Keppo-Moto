package com.ridetrack.app.keepmoving

import android.app.Activity
import android.app.Application
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.google.android.libraries.navigation.CustomRoutesOptions
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationRoadStretchRenderingData
import com.google.android.libraries.navigation.NavigationUpdatesOptions
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
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Keep moving during the ride (test builds): Google's Navigation SDK guides along the planned road
 * (its route token, through any detour points), with Google's arrows and voice. Keppo watches the
 * road ahead with the navigation's own traffic (free) and, per the 24 Nov plan:
 * 1. warns at ~1.5 km and ~500 m before trouble, with a way round when one is known (our own voice);
 * 2. if the rider turns off, Google re-plans for free and the new road is watched the same way;
 * 3. only when the rider didn't turn (or Google's re-plan heads back into the jam) does it pay to
 *    ask Google for routes, and offers a clearly clearer one with a 5-second "Keep this road".
 * Normal mode is Google's own route and isn't watched.
 */
class KeepMovingGuide(
    private val app: Application,
    private val session: RideSessionManager,
    private val log: TrafficLogger,
    private val scope: CoroutineScope,
    /** Step 4: the rider's own traffic memory. */
    private val memory: () -> Map<Memory.Cell, Memory.Stat> = { emptyMap() },
) {
    /** A clearer road on offer; switched to at [atMs] unless the rider keeps theirs. */
    data class Offer(val route: Ranked, val atMs: Long, val savedJamM: Int, val savedSlowM: Int, val declined: Reroute.Declined)

    /** A warning on screen (and said once per stage). */
    data class Warning(val title: String, val detail: String, val stage: Int)

    private val _active = MutableStateFlow(false)
    /** Directions are running. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    private val _warning = MutableStateFlow<Warning?>(null)
    val warning: StateFlow<Warning?> = _warning.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)
    /** The last thing the guide did, in plain words. */
    val note: StateFlow<String?> = _note.asStateFlow()

    var destination: Destination? = null
        private set
    var mode: KeepMode = KeepMode.LESS_TRAFFIC
        private set

    @Volatile private var navigator: Navigator? = null
    private var watch: Job? = null
    private var lastAskMs = 0L
    private val declined = ArrayList<Reroute.Declined>()
    /** Detour points still ahead (the navigation stops at each; we carry on by ourselves). */
    private val via = ArrayList<LatLon>()
    /** Known ways round (the plan's other detour points, the rider's own rides). */
    private var ways: List<LatLon> = emptyList()
    /** Warnings already given: jam key → highest stage said. */
    private val warned = HashMap<String, Int>()
    /** The rider left Google's route (their own turn): watch the new road straight away. */
    @Volatile private var turned = false
    /** Paid looks already made for this jam (one per jam, unless Google re-plans back into it). */
    private val looked = HashSet<String>()
    private var tts: TextToSpeech? = null

    init {
        // The ride ended: so do its directions.
        scope.launch {
            session.active.map { it != null }.distinctUntilChanged().collect { riding ->
                if (!riding && _active.value) withContext(Dispatchers.Main) { stop("Ride ended: directions stopped") }
            }
        }
    }

    /**
     * Google's terms screen (once), then directions to [dest]: along [option] (Keppo's planned road,
     * with its detour points) or, in Normal or without a plan, Google's own route. Returns null when
     * guidance started, or what went wrong in plain words.
     */
    suspend fun start(activity: Activity, dest: Destination, option: Planner.Option?, mode: KeepMode, knownWays: List<LatLon> = emptyList()): String? {
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
        if (navigator == null) listen(nav)
        navigator = nav
        destination = dest
        this.mode = mode
        via.clear()
        option?.via?.let { via += it }
        ways = knownWays + (option?.via.orEmpty())
        warned.clear()
        looked.clear()
        declined.clear()
        val status = follow(nav, dest, option?.ranked?.route?.takeIf { mode.scored }, via)
        if (status != Navigator.RouteStatus.OK) return "Google couldn't start directions (${status.name.lowercase().replace('_', ' ')})"
        nav.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
        nav.startGuidance()
        // The next turn for the top bar on the app's other screens.
        runCatching {
            nav.registerServiceForNavUpdates(
                app.packageName,
                NavUpdatesService::class.java.name,
                NavigationUpdatesOptions.builder().setNumNextStepsToPreview(1)
                    .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.BITMAP)
                    .setDisplayMetrics(app.resources.displayMetrics).build(),
            )
        }
        _active.value = true
        _note.value = null
        _warning.value = null
        _offer.value = null
        watch?.cancel()
        // Every mode is watched for free warnings and suggestions; only Less traffic and No foot
        // down ever pay for a look (Normal is purely Google's route).
        watch = scope.launch { watchAhead() }
        return null
    }

    /** Arrivals, and the rider's own turns, from the navigator (set up once). */
    private fun listen(nav: Navigator) {
        nav.addArrivalListener { e ->
            if (e.isFinalDestination) {
                stop("Arrived at ${destination?.name ?: "your destination"}")
            } else {
                // A detour point: not a stop for the rider. Carry on to the next.
                if (via.isNotEmpty()) via.removeAt(0)
                runCatching { nav.continueToNextDestination(); nav.startGuidance() }
            }
        }
        nav.addReroutingListener {
            // The rider left the route (their own left, right or U-turn): Google re-plans for free.
            if (_active.value) {
                turned = true
                _warning.value = null
                _note.value = "Google re-planned from your turn"
            }
        }
    }

    /** Ends directions (the ride itself goes on). */
    fun stop(note: String? = null) {
        watch?.cancel()
        watch = null
        _offer.value = null
        _warning.value = null
        navigator?.let { n ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { n.unregisterServiceForNavUpdates() }
                runCatching { n.stopGuidance() }
                runCatching { n.clearDestinations() }
            }
        }
        NavUpdatesService.clear()
        _active.value = false
        _note.value = note
    }

    /** The rider keeps the road they're on. No cooldown: watching goes on, but this road isn't offered again unless it gets clearly clearer. */
    fun keepThisRoad() {
        val o = _offer.value ?: return
        _offer.value = null
        declined += o.declined
        _note.value = "Kept your road"
    }

    /** Switch to the offered road now, without waiting for the countdown. */
    fun switchNow() {
        val o = _offer.value ?: return
        scope.launch { switchTo(o) }
    }

    /** Follows [route] by its token (exactly that road, through [through]), or Google's own route without one. */
    private suspend fun follow(nav: Navigator, dest: Destination, route: TrafficRoute?, through: List<LatLon>): Navigator.RouteStatus {
        val target = Waypoint.builder().setLatLng(dest.at.lat, dest.at.lon).setTitle(dest.name).build()
        val stops = through.mapIndexed { i, p -> Waypoint.builder().setLatLng(p.lat, p.lon).setTitle("Way round ${i + 1}").build() }
        val travel = if (log.lastMode == TrafficRoutes.DRIVE) RoutingOptions.TravelMode.DRIVING else RoutingOptions.TravelMode.TWO_WHEELER
        val token = route?.routeToken
        val future = withContext(Dispatchers.Main) {
            if (token != null) {
                // The same waypoints the token was made with (the Navigation SDK 7.2 asks for them).
                nav.setDestinations(stops + target, CustomRoutesOptions.builder().setRouteToken(token).setTravelMode(travel).build())
            } else {
                nav.setDestination(target, RoutingOptions().travelMode(travel))
            }
        }
        return suspendCancellableCoroutine { c -> future.setOnResultListener { s -> if (c.isActive) c.resume(s) } }
    }

    /** While guiding: warn first (free), the paid look and switch only as the backstop. */
    private suspend fun watchAhead() {
        while (_active.value) {
            delay(10_000)
            val nav = navigator ?: return
            val pending = _offer.value
            if (pending != null) {
                if (System.currentTimeMillis() >= pending.atMs) switchTo(pending)
                continue
            }
            val (line, stretches) = withContext(Dispatchers.Main) { currentRoad(nav) } ?: continue
            val f = session.frame.value
            val here = f?.latitude?.let { lat -> f.longitude?.let { LatLon(lat, it) } } ?: line.first()
            val mem = memory()
            val bucket = Memory.bucket(System.currentTimeMillis())
            val google = RideWatch.firstTrouble(stretches, line, mode)
            // Google shows nothing yet, but your own rides say it's usually slow here at this hour.
            val usual = if (google == null) Memory.troubleAhead(mem, line, bucket) else null
            val trouble = google ?: usual
            val justTurned = turned
            turned = false
            if (trouble == null) {
                _warning.value = null
                if (justTurned) _note.value = "Google re-planned from your turn · the new road is clear"
                continue
            }
            val key = RideWatch.key(trouble)
            val stage = RideWatch.stage(trouble.inM)
            // Known ways round: the plan's other detours, and roads where you move well at this hour.
            val way = RideWatch.wayRound(trouble, line, here, ways + Memory.goodPoints(mem, bucket))
            val (title, detail, voice) = when {
                !mode.scored && way != null -> Triple(RideWatch.title(trouble), RideWatch.smootherDetail(way), RideWatch.smootherVoice(way))
                usual != null -> Triple(RideWatch.usualTitle(trouble), RideWatch.usualDetail(trouble, way), RideWatch.usualVoice(way))
                else -> Triple(RideWatch.title(trouble), RideWatch.detail(trouble, way), RideWatch.voice(trouble, stage, way))
            }
            _warning.value = Warning(title, detail, stage)
            if ((warned[key] ?: 0) < stage) {
                warned[key] = stage
                say(voice)
            }
            // Normal never pays: Google re-plans for free if you take the suggestion.
            if (!mode.scored) continue
            // The backstop: the jam is near and the rider hasn't turned off, or Google's re-plan after
            // their turn heads straight back into it.
            val backInto = justTurned && trouble.inM <= RideWatch.FAR_M
            val due = stage == 2 && key !in looked
            if (!(due || backInto)) continue
            val now = System.currentTimeMillis()
            if (now - lastAskMs < Reroute.MIN_ASK_GAP_MS) continue
            val dest = destination ?: continue
            looked += key
            lastAskMs = now
            val answer = log.routes(here, dest.at, f?.headingDeg)
            if (answer.status != 200) continue
            val total = line.zipWithNext { a, b -> TrafficRoutes.distanceM(a, b) }.sum().toInt()
            val current = Reroute.colours(stretches, total)
            val currentCost = RouteScore.cost(current, mode)
            val ranked = RouteScore.rank(answer.routes, mode)
            val pick = Reroute.pick(ranked, line, currentCost, mode, declined) ?: continue
            _warning.value = null
            _offer.value = Offer(
                pick,
                atMs = System.currentTimeMillis() + Reroute.COUNTDOWN_MS,
                savedJamM = (current.jamM - pick.colours.jamM).coerceAtLeast(0),
                savedSlowM = (current.slowM - pick.colours.slowM).coerceAtLeast(0),
                declined = Reroute.Declined(TrafficRoutes.decodePolyline(pick.route.polyline), pick.cost),
            )
            say(RideWatch.SWITCH_VOICE)
            delay(Reroute.COUNTDOWN_MS)
            _offer.value?.let { if (System.currentTimeMillis() >= it.atMs) switchTo(it) }
        }
    }

    private suspend fun switchTo(o: Offer) {
        if (_offer.value != o) return
        _offer.value = null
        val nav = navigator ?: return
        val dest = destination ?: return
        via.clear()
        val status = follow(nav, dest, o.route.route, emptyList())
        _note.value = if (status == Navigator.RouteStatus.OK) {
            "Switched to a clearer road" + listOfNotNull(
                Planner.km(o.savedJamM).takeIf { o.savedJamM > 0 }?.let { "$it less jam" },
                Planner.km(o.savedSlowM).takeIf { o.savedSlowM > 0 }?.let { "$it less slow" },
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

    /** Our own voice line, through the same audio route as navigation guidance (the headset). */
    private fun say(text: String) {
        val t = tts
        if (t != null) {
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "keep-moving-${System.nanoTime()}")
            return
        }
        tts = TextToSpeech(app) { ok ->
            if (ok == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                tts?.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "keep-moving-first")
            }
        }
    }

    private fun errorText(code: Int) = when (code) {
        NavigationApi.ErrorCode.NOT_AUTHORIZED -> "The key isn't allowed to use the Navigation SDK: check it's ticked on the key"
        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "Directions need Google's terms accepted"
        NavigationApi.ErrorCode.NETWORK_ERROR -> "No connection for directions"
        NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "Directions need location permission"
        else -> "Directions couldn't start ($code)"
    }
}
