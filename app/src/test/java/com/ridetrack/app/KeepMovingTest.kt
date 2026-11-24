package com.ridetrack.app

import com.ridetrack.app.keepmoving.Destinations
import com.ridetrack.app.keepmoving.LatLon
import com.ridetrack.app.keepmoving.PlaceSearch
import com.ridetrack.app.keepmoving.KeepMode
import com.ridetrack.app.keepmoving.Memory
import com.ridetrack.app.keepmoving.Planner
import com.ridetrack.app.keepmoving.RideWatch
import com.ridetrack.app.keepmoving.SavedPlaces
import com.ridetrack.app.keepmoving.Signals
import com.ridetrack.app.keepmoving.Destination
import com.ridetrack.app.keepmoving.Reroute
import com.ridetrack.app.keepmoving.RouteScore
import com.ridetrack.app.keepmoving.TrafficRoute
import com.ridetrack.app.keepmoving.Speed
import com.ridetrack.app.keepmoving.SpeedInterval
import com.ridetrack.app.keepmoving.TrafficRoutes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepMovingTest {
    private fun near(a: Double, b: Double, tol: Double = 1e-5) = assertTrue(abs(a - b) < tol, "$a vs $b")

    @Test
    fun `Google's example polyline decodes to its three points`() {
        val p = TrafficRoutes.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, p.size)
        near(38.5, p[0].lat); near(-120.2, p[0].lon)
        near(40.7, p[1].lat); near(-120.95, p[1].lon)
        near(43.252, p[2].lat); near(-126.453, p[2].lon)
        assertEquals(emptyList(), TrafficRoutes.decodePolyline(""))
    }

    @Test
    fun `the request asks for two-wheeler routes with traffic on the line, facing the way the bike goes`() {
        val r = TrafficRoutes.request(LatLon(28.6, 77.2), LatLon(28.5, 77.1), TrafficRoutes.TWO_WHEELER, headingDeg = -90.0)
        assertEquals("TWO_WHEELER", r.getString("travelMode"))
        assertEquals("TRAFFIC_AWARE", r.getString("routingPreference"))
        assertEquals("TRAFFIC_ON_POLYLINE", r.getJSONArray("extraComputations").getString(0))
        assertEquals(270, r.getJSONObject("origin").getJSONObject("location").getInt("heading"))
        near(28.5, r.getJSONObject("destination").getJSONObject("location").getJSONObject("latLng").getDouble("latitude"))
        // No heading known: none sent.
        assertFalse(TrafficRoutes.request(LatLon(1.0, 2.0), LatLon(3.0, 4.0), TrafficRoutes.DRIVE).getJSONObject("origin").getJSONObject("location").has("heading"))
    }

    @Test
    fun `an answer is read with its delay and its slow and jammed metres`() {
        // Three points about 111 m apart going north; 0→1 slow, 1→2 jammed (a start of 0 is left out, as Google does).
        val line = encode(listOf(LatLon(28.0, 77.0), LatLon(28.001, 77.0), LatLon(28.002, 77.0)))
        val body = """{"routes":[{"duration":"900s","staticDuration":"600s","distanceMeters":5200,"description":"NH 48",
            "polyline":{"encodedPolyline":"${line.replace("\\", "\\\\")}"},
            "travelAdvisory":{"speedReadingIntervals":[{"endPolylinePointIndex":1,"speed":"SLOW"},{"startPolylinePointIndex":1,"endPolylinePointIndex":2,"speed":"TRAFFIC_JAM"}]}},
            {"duration":"1000s","staticDuration":"1000s","distanceMeters":6000}]}"""
        val routes = TrafficRoutes.parse(body)
        assertEquals(2, routes.size)
        val r = routes[0]
        assertEquals(900, r.durationSec)
        assertEquals(5, r.delayMin)
        assertEquals("NH 48", r.description)
        assertTrue(r.slowM in 105..117, "slow ${r.slowM}")
        assertTrue(r.jamM in 105..117, "jam ${r.jamM}")
        assertEquals(0, routes[1].delayMin)
        assertEquals(0, routes[1].slowM)
        // Only the slow and jammed stretches are kept in the log.
        assertEquals(2, r.json().getJSONArray("intervals").length())
        assertEquals(emptyList(), TrafficRoutes.parse("{}"))
    }

    @Test
    fun `durations and interval ends past the line are handled`() {
        assertEquals(1234, TrafficRoutes.seconds("1234s"))
        assertEquals(0, TrafficRoutes.seconds(""))
        val pts = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.001))
        assertTrue(TrafficRoutes.metres(pts, listOf(SpeedInterval(0, 9, Speed.SLOW)), Speed.SLOW) in 105..117)
        assertEquals(Speed.UNKNOWN, Speed.of("SPEED_UNSPECIFIED"))
    }

    @Test
    fun `two-wheeler not offered is told apart from other errors`() {
        assertTrue(TrafficRoutes.modeNotOffered(400, "Travel mode TWO_WHEELER is not supported in this region"))
        assertFalse(TrafficRoutes.modeNotOffered(403, "API key not valid"))
        assertFalse(TrafficRoutes.modeNotOffered(400, "Invalid destination"))
    }

    @Test
    fun `ride ends close together are one place, the most visited first`() {
        val home = LatLon(28.459, 77.026)
        val office = LatLon(28.535, 77.391)
        val places = Destinations.group(listOf(office, home, LatLon(28.4595, 77.0262), office.copy(lat = office.lat + 0.001), home))
        assertEquals(2, places.size)
        assertEquals(home, places[0])
    }

    @Test
    fun `a MapTiler search answer is read longitude first`() {
        val d = Destinations.parseSearch("""{"features":[{"place_name":"Cyber Hub, Gurugram","center":[77.088,28.495]},{"text":"x"}]}""")
        assertEquals(1, d.size)
        assertEquals("Cyber Hub, Gurugram", d[0].name)
        near(28.495, d[0].at.lat)
        near(77.088, d[0].at.lon)
    }

    @Test
    fun `Google's place suggestions are read with their name and area`() {
        val body = """{"suggestions":[
            {"placePrediction":{"placeId":"ChIJ1","text":{"text":"Cyber Hub, DLF Cyber City, Gurugram"},
              "structuredFormat":{"mainText":{"text":"Cyber Hub"},"secondaryText":{"text":"DLF Cyber City, Gurugram"}}}},
            {"queryPrediction":{"text":{"text":"cyber cafe"}}},
            {"placePrediction":{"placeId":"ChIJ2","text":{"text":"Sector 29"}}},
            {"placePrediction":{"text":{"text":"no id"}}}]}"""
        val s = PlaceSearch.parseSuggestions(body)
        assertEquals(listOf("ChIJ1", "ChIJ2"), s.map { it.placeId })
        assertEquals("Cyber Hub", s[0].name)
        assertEquals("DLF Cyber City, Gurugram", s[0].area)
        assertEquals("Sector 29", s[1].name)
        assertEquals(null, s[1].area)
        assertEquals(emptyList(), PlaceSearch.parseSuggestions("{}"))
    }

    @Test
    fun `a picked place's location is read, and the search asks near you in India`() {
        val at = PlaceSearch.parseLocation("""{"location":{"latitude":28.495,"longitude":77.088}}""")!!
        near(28.495, at.lat); near(77.088, at.lon)
        assertEquals(null, PlaceSearch.parseLocation("{}"))
        val r = PlaceSearch.request("cyber hub", "tok", LatLon(28.6, 77.2))
        assertEquals("tok", r.getString("sessionToken"))
        assertEquals("in", r.getJSONArray("includedRegionCodes").getString(0))
        near(28.6, r.getJSONObject("locationBias").getJSONObject("circle").getJSONObject("center").getDouble("latitude"))
        assertFalse(PlaceSearch.request("x", "tok", null).has("locationBias"))
        assertTrue("sessionToken=tok" in PlaceSearch.detailsUrl("ChIJ1", "tok"))
    }

    @Test
    fun `the road ahead is scored from the navigation's own traffic, within 5 km`() {
        val st = listOf(Reroute.Stretch(1_000, 300, Speed.JAM), Reroute.Stretch(4_800, 600, Speed.SLOW))
        val ahead = Reroute.colours(st, totalM = 10_000, aheadM = Reroute.AHEAD_M)
        assertEquals(300, ahead.jamM)
        assertEquals(200, ahead.slowM) // only the first 200 m of the slow stretch is within 5 km
        assertEquals(10_000 - 900, Reroute.colours(st, 10_000).clearM)
    }

    @Test
    fun `Google is asked only with trouble ahead, and not too often, never in Normal`() {
        val jam = listOf(Reroute.Stretch(500, 200, Speed.JAM))
        val bit = listOf(Reroute.Stretch(500, 100, Speed.SLOW))
        val now = 10_000_000L
        assertTrue(Reroute.shouldLook(jam, 8_000, now, lastAskMs = 0, mode = KeepMode.LESS_TRAFFIC))
        // A little yellow isn't worth a paid look.
        assertFalse(Reroute.shouldLook(bit, 8_000, now, 0, KeepMode.LESS_TRAFFIC))
        // Asked a minute ago.
        assertFalse(Reroute.shouldLook(jam, 8_000, now, lastAskMs = now - 60_000, mode = KeepMode.LESS_TRAFFIC))
        // Normal is Google's own route: never.
        assertFalse(Reroute.shouldLook(jam, 8_000, now, 0, KeepMode.NORMAL))
        // No foot down looks at less trouble (75): 130 m of yellow at 0.62.
        assertTrue(Reroute.shouldLook(listOf(Reroute.Stretch(500, 130, Speed.SLOW)), 8_000, now, 0, KeepMode.NO_FOOT_DOWN))
    }

    @Test
    fun `a declined road isn't offered again unless clearly clearer, and the countdown is 5 s`() {
        assertEquals(5_000L, Reroute.COUNTDOWN_MS)
        val current = (0..20).map { LatLon(28.0 + it * 0.001, 77.0) }
        val other = route(20, SpeedInterval(0, 20, Speed.NORMAL), lon = 77.01).copy(routeToken = "tok")
        val ranked = RouteScore.rank(listOf(other))
        val line = TrafficRoutes.decodePolyline(other.polyline)
        val declined = listOf(Reroute.Declined(line, ranked.first().cost))
        assertEquals(null, Reroute.pick(ranked, current, 520.0, KeepMode.LESS_TRAFFIC, declined))
        // It was much worse when declined: now it's clearly clearer, so it's offered.
        val wasWorse = listOf(Reroute.Declined(line, ranked.first().cost * 3))
        assertEquals(0, Reroute.pick(ranked, current, 520.0, KeepMode.LESS_TRAFFIC, wasWorse)?.index)
    }

    @Test
    fun `a clearer road is picked only when it's clearly better and not the same road`() {
        val current = (0..20).map { LatLon(28.0 + it * 0.001, 77.0) }
        val other = route(20, SpeedInterval(0, 20, Speed.NORMAL), lon = 77.01).copy(routeToken = "tok")
        val sameRoad = route(20, SpeedInterval(0, 20, Speed.NORMAL)).copy(routeToken = "tok2")
        // The current road has ~500 m of red: cost ~ 500 + clear.
        val ranked = RouteScore.rank(listOf(other))
        assertEquals(0, Reroute.pick(ranked, current, currentCost = 520.0)?.index)
        // Not clearly better: stay.
        assertEquals(null, Reroute.pick(ranked, current, currentCost = 20.0))
        // Google's best is the road already ridden: stay.
        assertEquals(null, Reroute.pick(RouteScore.rank(listOf(sameRoad)), current, currentCost = 520.0))
        // No route token: can't be followed, stay.
        assertEquals(null, Reroute.pick(RouteScore.rank(listOf(other.copy(routeToken = null))), current, 520.0))
    }

    @Test
    fun `distance to a line and same road`() {
        val line = listOf(LatLon(28.0, 77.0), LatLon(28.01, 77.0))
        assertTrue(Reroute.distanceToLineM(LatLon(28.005, 77.0), line) < 1.0)
        assertTrue(Reroute.distanceToLineM(LatLon(28.005, 77.001), line) in 90.0..110.0)
        assertTrue(Reroute.sameRoad(line, listOf(LatLon(28.001, 77.0), LatLon(28.009, 77.0002))))
        assertFalse(Reroute.sameRoad(line, listOf(LatLon(28.001, 77.01), LatLon(28.009, 77.01))))
    }

    @Test
    fun `OpenStreetMap signals are read, and a junction on the route counts once`() {
        val sig = Signals.parse("""{"elements":[{"type":"node","id":1,"lat":28.005,"lon":77.0001},{"type":"node","id":2,"lat":28.0051,"lon":77.0002},{"type":"node","id":3,"lat":28.005,"lon":77.01},{"type":"way"}]}""")
        assertEquals(3, sig.size)
        val line = listOf(LatLon(28.0, 77.0), LatLon(28.01, 77.0))
        // Nodes 1 and 2 are one junction beside the route; node 3 is 1 km away.
        assertEquals(1, Signals.count(line, sig))
        assertEquals(0, Signals.count(line, emptyList()))
        // Tiles: a 20 km plan is a handful; a country-wide box is refused.
        assertTrue(Signals.tiles(listOf(LatLon(28.40, 77.00), LatLon(28.55, 77.15))).size in 9..25)
        assertEquals(emptyList(), Signals.tiles(listOf(LatLon(20.0, 70.0), LatLon(30.0, 80.0))))
        assertTrue("traffic_signals" in Signals.query(0, 1, 0, 1))
    }

    @Test
    fun `jams on a route are found in order, with ways round from other routes first`() {
        // 30 steps north; red from 5 to 9 (~440 m) and a short red 20 to 21 (~110 m, skipped).
        val r = route(30, SpeedInterval(0, 5, Speed.NORMAL), SpeedInterval(5, 9, Speed.JAM), SpeedInterval(20, 21, Speed.JAM))
        val jams = Planner.jams(r, Planner.MIN_JAM_M)
        assertEquals(1, jams.size)
        assertTrue(jams[0].lengthM in 420..460)
        val line = TrafficRoutes.decodePolyline(r.polyline)
        // Another Google route ~500 m east around the jam.
        val other = (0..30).map { LatLon(28.0 + it * 0.001, 77.005) }
        val cands = Planner.candidates(jams[0], line, listOf(other), own = listOf(LatLon(28.007, 76.996)))
        assertEquals("Google's other route", cands.first().source)
        assertTrue(cands.any { it.source == "Your own ride" })
        assertTrue(cands.count { it.source == "A side road" } == 2)
        // Side points are ~700 m off the road, one each side.
        val sides = Planner.side(jams[0])
        assertTrue(sides.all { Reroute.distanceToLineM(it, line) in 650.0..750.0 })
        // Ordered along the route.
        val ordered = Planner.order(listOf(LatLon(28.02, 77.0), LatLon(28.005, 77.0)), line)
        near(28.005, ordered.first().lat)
    }

    @Test
    fun `a route that goes into a lane and back out at a detour point is caught, a U-turn elsewhere isn't`() {
        val via = LatLon(28.005, 77.004)
        // Out east to the point and back along the same lane, then on north.
        val spur = listOf(LatLon(28.005, 77.0)) + (1..8).map { LatLon(28.005, 77.0 + it * 0.0005) } +
            (7 downTo 0).map { LatLon(28.005, 77.0 + it * 0.0005) } + (1..10).map { LatLon(28.005 + it * 0.001, 77.0) }
        assertTrue(Planner.hasSpur(spur, listOf(via)))
        val straight = (0..30).map { LatLon(28.0 + it * 0.001, 77.0) }
        assertFalse(Planner.hasSpur(straight, listOf(via)))
        // The same doubling-back far from any detour point (a U-turn under a flyover): fine.
        assertFalse(Planner.hasSpur(spur, listOf(LatLon(28.1, 77.1))))
    }

    @Test
    fun `saved places are used again, newest first, 20 kept, Google locations expire`() {
        var list = emptyList<SavedPlaces.Place>()
        list = SavedPlaces.remember(list, Destination("Office", LatLon(28.495, 77.088)), "DLF Cyber City", now = 1_000)
        list = SavedPlaces.remember(list, Destination("Home", LatLon(28.459, 77.026)), "Sector 29", now = 2_000)
        // Within 150 m of Office: the same place, used again.
        list = SavedPlaces.remember(list, Destination("x", LatLon(28.4955, 77.0882)), "ignored", now = 3_000)
        assertEquals(2, list.size)
        assertEquals("DLF Cyber City", list.first().label)
        assertEquals(2, list.first().uses)
        repeat(25) { i -> list = SavedPlaces.remember(list, Destination("p$i", LatLon(27.0 + i * 0.01, 77.0)), "P$i", now = 10_000L + i) }
        assertEquals(SavedPlaces.MAX, list.size)
        // A Google place keeps its ID; its location goes after 30 days.
        val g = SavedPlaces.remember(emptyList(), Destination("Cyber Hub", LatLon(28.495, 77.088), placeId = "ChIJ1"), "DLF Cyber City", now = 0)
        val later = SavedPlaces.expire(g, now = SavedPlaces.GOOGLE_KEEP_MS + 1)
        assertEquals(null, later.first().at)
        assertEquals("ChIJ1", later.first().placeId)
        // A dropped pin (no Google ID) is the rider's own: kept.
        val own = SavedPlaces.remember(emptyList(), Destination("Pin", LatLon(28.0, 77.0)), "Pin", now = 0)
        assertEquals(LatLon(28.0, 77.0), SavedPlaces.expire(own, SavedPlaces.GOOGLE_KEEP_MS * 2).first().at)
    }

    @Test
    fun `ride warnings come at 1_5 km and 500 m, with the way round's side`() {
        val line = (0..30).map { LatLon(28.0 + it * 0.001, 77.0) }
        val st = listOf(Reroute.Stretch(1_200, 400, Speed.JAM))
        val t = RideWatch.firstTrouble(st, line, KeepMode.LESS_TRAFFIC)!!
        assertEquals(1, RideWatch.stage(t.inM))
        assertEquals(2, RideWatch.stage(500))
        assertEquals(0, RideWatch.stage(3_000))
        // Yellow only counts in No foot down.
        val slow = listOf(Reroute.Stretch(800, 500, Speed.SLOW))
        assertEquals(null, RideWatch.firstTrouble(slow, line, KeepMode.LESS_TRAFFIC))
        assertTrue(RideWatch.firstTrouble(slow, line, KeepMode.NO_FOOT_DOWN) != null)
        // Heading north, a point to the west is on the left.
        assertEquals("left", RideWatch.side(LatLon(28.0, 77.0), LatLon(28.01, 77.0), LatLon(28.005, 76.995)))
        assertEquals("right", RideWatch.side(LatLon(28.0, 77.0), LatLon(28.01, 77.0), LatLon(28.005, 77.005)))
        val way = RideWatch.wayRound(t, line, line.first(), listOf(LatLon(28.012, 76.994)))
        assertEquals("left", way)
        assertEquals("Jam in one and a half kilometres. A way round on your left.", RideWatch.voice(t, 1, way))
        assertTrue(RideWatch.title(t).startsWith("Jam in 1.2 km"))
    }

    /** A ride north along lon 77.0 at [speeds] (m/s, one fix a second, ~1 m per m/s), from [t0]. */
    private fun ride(t0: Long, speeds: List<Double>): List<Memory.Fix> {
        var lat = 28.0
        return speeds.mapIndexed { i, v ->
            val f = Memory.Fix(t0 + i * 1000L, LatLon(lat, 77.0), v, 0.0)
            lat += v / 111_320.0
            f
        }
    }

    private val utc = java.util.TimeZone.getTimeZone("UTC")
    /** Monday 24 Nov 2026, 09:00 UTC. */
    private val monday9 = 1_795_510_800_000L

    @Test
    fun `a ride is learned per cell and hour, without the parking minutes or rest stops`() {
        // 2 min parking, 5 min at 10 m/s, a 6-minute rest stop, 3 min creeping at 1 m/s (a jam), 2 min parking.
        val speeds = List(120) { 0.0 } + List(300) { 10.0 } + List(360) { 0.0 } + List(180) { 1.0 } + List(120) { 0.0 }
        val m = Memory.learn(ride(monday9, speeds), utc)
        val total = m.values.fold(Memory.Stat.EMPTY) { a, s -> a + s }
        // The moving 5 min and the 3 min at 1 m/s: the rest stop and both ends are left out.
        assertTrue(total.secs in 470.0..490.0, "secs ${total.secs}")
        assertTrue(total.stopped in 170.0..190.0, "stopped ${total.stopped}")
        assertTrue(m.keys.all { it.bucket == 9 && it.dir == 0 })
        assertTrue(m.values.all { it.rides == 1 })
        assertEquals(33, Memory.bucket(monday9 - 2 * 86_400_000L, utc))
        assertEquals(listOf(23, 0, 1), Memory.near(0))
        assertEquals(listOf(47, 24, 25), Memory.near(24))
    }

    @Test
    fun `your record makes Google's green where you stop cost more, and its yellow where you move cost less`() {
        // Five rides stopped-and-going (under 5 km/h) through a stretch Google shows green.
        val slow = (0 until 5).map { Memory.learn(ride(monday9 + it * 86_400_000L * 7, List(120) { 0.0 } + List(400) { 1.0 } + List(120) { 0.0 }), utc) }
        val stats = HashMap<Memory.Cell, Memory.Stat>()
        slow.forEach { r -> r.forEach { (k, v) -> stats[k] = (stats[k] ?: Memory.Stat.EMPTY) + v } }
        val green = route(4, SpeedInterval(0, 4, Speed.NORMAL))
        assertTrue(Memory.adjust(stats, green, KeepMode.LESS_TRAFFIC, 9) > 50, "green where you stop costs more")
        // Five rides moving through a stretch Google shows yellow.
        val fast = (0 until 5).map { Memory.learn(ride(monday9 + it * 86_400_000L * 7, List(120) { 0.0 } + List(60) { 12.0 } + List(120) { 0.0 }), utc) }
        val stats2 = HashMap<Memory.Cell, Memory.Stat>()
        fast.forEach { r -> r.forEach { (k, v) -> stats2[k] = (stats2[k] ?: Memory.Stat.EMPTY) + v } }
        val yellow = route(4, SpeedInterval(0, 4, Speed.SLOW))
        assertTrue(Memory.adjust(stats2, yellow, KeepMode.LESS_TRAFFIC, 9) < -20, "yellow where you move costs less")
        // A different hour (21:00): nothing known, no change.
        near(0.0, Memory.adjust(stats, green, KeepMode.LESS_TRAFFIC, 21))
        // Your record warns ahead ("usually slow here") and fast cells make ways round.
        assertTrue(Memory.troubleAhead(stats, TrafficRoutes.decodePolyline(green.polyline), 9) != null)
        assertTrue(Memory.goodPoints(stats2, 9).isNotEmpty())
        assertTrue(Memory.goodPoints(stats, 9).isEmpty())
    }

    @Test
    fun `your own yellow is learned from the traffic log, starting from 1 in 5`() {
        near(0.2, Memory.yellowChance(0, 0))
        // 10 yellow stretches ridden, 8 of them stopped in: well above 1 in 5.
        assertTrue(Memory.yellowChance(10, 8) > 0.45)
        // One log check with a yellow stretch 0 to 3 (~330 m) ahead; the ride goes through it, stopping 20 s.
        val line = (0..10).map { LatLon(28.0 + it * 0.001, 77.0) }
        val check = org.json.JSONObject().put("type", "check").put("t", monday9)
            .put("routes", org.json.JSONArray().put(org.json.JSONObject().put("polyline", encode(line))
                .put("intervals", org.json.JSONArray().put(org.json.JSONArray().put(0).put(3).put("SLOW")))))
        val through = ride(monday9, List(30) { 8.0 } + List(20) { 0.0 } + List(40) { 8.0 })
        assertEquals(1 to 1, Memory.yellowFromLog(listOf(check.toString()), through))
        val noStop = ride(monday9, List(60) { 8.0 })
        assertEquals(1 to 0, Memory.yellowFromLog(listOf(check.toString()), noStop))
        // Once learned, Less traffic uses it, and No foot down adds its margin on top.
        try {
            KeepMode.personalYellow = 0.5
            near(0.5, KeepMode.LESS_TRAFFIC.yellowChance)
            near(0.85, KeepMode.NO_FOOT_DOWN.yellowChance)
        } finally {
            KeepMode.personalYellow = null
        }
    }

    @Test
    fun `signals load for the box around the trip, small enough for one fetch`() {
        val b = Planner.box(LatLon(28.459, 77.026), LatLon(28.495, 77.088))
        assertTrue(b[0].lat < 28.459 && b[1].lon > 77.088)
        // A 20 km trip's box is within OpenStreetMap's tile limit.
        assertTrue(Signals.tiles(b).isNotEmpty())
        assertTrue(Planner.SIGNALS_WAIT_MS <= 4_000L)
    }

    /** A straight route north of [n] steps of about 111 m, with [intervals]; Google's distance is the line's. */
    private fun route(n: Int, vararg intervals: SpeedInterval, lon: Double = 77.0): TrafficRoute {
        val pts = (0..n).map { LatLon(28.0 + it * 0.001, lon) }
        return TrafficRoute(600, 600, (n * 111.2).toInt(), null, encode(pts), 0, 0, intervals.toList())
    }

    @Test
    fun `yellow costs about a third of red, from the rider's 1 in 5`() {
        near(0.32, RouteScore.SLOW_PER_M)
        assertTrue(RouteScore.SLOW_PER_M / RouteScore.JAM_PER_M in 0.3..0.35)
    }

    @Test
    fun `a short red stretch loses to a longer yellow one`() {
        // 10 steps each: 3 red (~330 m) vs 8 yellow (~890 m), the rest clear.
        val red = route(10, SpeedInterval(0, 3, Speed.JAM), SpeedInterval(3, 10, Speed.NORMAL))
        val yellow = route(10, SpeedInterval(0, 8, Speed.SLOW), SpeedInterval(8, 10, Speed.NORMAL), lon = 77.01)
        val ranked = RouteScore.rank(listOf(red, yellow))
        assertEquals(1, ranked.first().index)
    }

    @Test
    fun `No foot down takes a clear route up to 3x longer, Less traffic up to 2x`() {
        val shortJam = route(10, SpeedInterval(0, 3, Speed.JAM), SpeedInterval(3, 10, Speed.NORMAL))
        val longClear = route(29, SpeedInterval(0, 29, Speed.NORMAL), lon = 77.01)
        assertEquals(1, RouteScore.rank(listOf(shortJam, longClear), KeepMode.NO_FOOT_DOWN).first().index)
        // 2.9x is too long for Less traffic.
        val less = RouteScore.rank(listOf(shortJam, longClear), KeepMode.LESS_TRAFFIC)
        assertEquals(0, less.first().index)
        assertTrue(less.last().tooLong)
        val tooLong = route(31, SpeedInterval(0, 31, Speed.NORMAL), lon = 77.02)
        val r = RouteScore.rank(listOf(shortJam, tooLong), KeepMode.NO_FOOT_DOWN)
        assertEquals(0, r.first().index)
        assertTrue(r.last().tooLong)
    }

    @Test
    fun `No foot down counts yellow as likely feet down, and signals`() {
        near(0.6175, KeepMode.NO_FOOT_DOWN.slowPerM, 1e-4)
        near(RouteScore.SLOW_PER_M, KeepMode.LESS_TRAFFIC.slowPerM)
        val c = com.ridetrack.app.keepmoving.Colours(clearM = 1000, slowM = 0, jamM = 0, unknownM = 0)
        near(5.0 + 3 * 60.0, RouteScore.cost(c, KeepMode.NO_FOOT_DOWN, signals = 3), 1e-6)
        near(5.0, RouteScore.cost(c, KeepMode.LESS_TRAFFIC, signals = 3), 1e-6)
        assertFalse(KeepMode.NORMAL.scored)
        assertEquals(KeepMode.LESS_TRAFFIC, KeepMode.of("nonsense"))
    }

    @Test
    fun `of two clear routes the shorter wins, and no data scores as slow`() {
        val short = route(10, SpeedInterval(0, 10, Speed.NORMAL))
        val long = route(14, SpeedInterval(0, 14, Speed.NORMAL), lon = 77.01)
        assertEquals(1, RouteScore.rank(listOf(long, short)).first().index)
        // No intervals at all: every metre is "no data".
        val c = RouteScore.colours(route(10))
        assertTrue(c.unknownM in 1050..1170, "unknown ${c.unknownM}")
        near(RouteScore.SLOW_PER_M * c.unknownM, RouteScore.cost(c), 1e-6)
    }

    @Test
    fun `a route is cut into one-colour stretches for the map, gaps as no data`() {
        val s = RouteScore.stretches(route(6, SpeedInterval(0, 2, Speed.NORMAL), SpeedInterval(2, 4, Speed.JAM)))
        assertEquals(listOf(Speed.NORMAL, Speed.JAM, Speed.UNKNOWN), s.map { it.speed })
        // Neighbouring stretches share their joining point, so the line has no gaps.
        assertEquals(s[0].points.last(), s[1].points.first())
        assertEquals(7, s.sumOf { it.points.size } - (s.size - 1))
    }

    /** The standard polyline encoding, to make test answers. */
    private fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var pLat = 0
        var pLon = 0
        fun put(v: Int) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f)) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toChar())
        }
        points.forEach { p ->
            val lat = Math.round(p.lat * 1e5).toInt()
            val lon = Math.round(p.lon * 1e5).toInt()
            put(lat - pLat); put(lon - pLon)
            pLat = lat; pLon = lon
        }
        return sb.toString()
    }
}
