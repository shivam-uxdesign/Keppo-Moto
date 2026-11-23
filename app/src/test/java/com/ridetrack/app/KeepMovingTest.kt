package com.ridetrack.app

import com.ridetrack.app.keepmoving.Destinations
import com.ridetrack.app.keepmoving.LatLon
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
