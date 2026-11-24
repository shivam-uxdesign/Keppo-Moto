package com.ridetrack.app

import com.ridetrack.app.keepmoving.Destinations
import com.ridetrack.app.keepmoving.LatLon
import com.ridetrack.app.keepmoving.PlaceSearch
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
    fun `Google is asked only with trouble ahead, and not too often`() {
        val jam = listOf(Reroute.Stretch(500, 200, Speed.JAM))
        val bit = listOf(Reroute.Stretch(500, 100, Speed.SLOW))
        val now = 10_000_000L
        assertTrue(Reroute.shouldLook(jam, 8_000, now, lastAskMs = 0, keptUntilMs = 0, lastSwitchMs = 0))
        // A little yellow isn't worth a paid look.
        assertFalse(Reroute.shouldLook(bit, 8_000, now, 0, 0, 0))
        // Asked a minute ago; kept the road; just switched.
        assertFalse(Reroute.shouldLook(jam, 8_000, now, lastAskMs = now - 60_000, keptUntilMs = 0, lastSwitchMs = 0))
        assertFalse(Reroute.shouldLook(jam, 8_000, now, 0, keptUntilMs = now + 1, lastSwitchMs = 0))
        assertFalse(Reroute.shouldLook(jam, 8_000, now, 0, 0, lastSwitchMs = now - 60_000))
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
    fun `a clear route up to 3x longer beats a short jammed one, but not past 3x`() {
        val shortJam = route(10, SpeedInterval(0, 3, Speed.JAM), SpeedInterval(3, 10, Speed.NORMAL))
        val longClear = route(29, SpeedInterval(0, 29, Speed.NORMAL), lon = 77.01)
        assertEquals(1, RouteScore.rank(listOf(shortJam, longClear)).first().index)
        val tooLong = route(31, SpeedInterval(0, 31, Speed.NORMAL), lon = 77.02)
        val r = RouteScore.rank(listOf(shortJam, tooLong))
        assertEquals(0, r.first().index)
        assertTrue(r.last().tooLong)
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
