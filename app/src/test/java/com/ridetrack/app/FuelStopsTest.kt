package com.ridetrack.app

import com.ridetrack.app.fuel.Fuel
import com.ridetrack.app.fuel.FuelPrompt
import com.ridetrack.app.fuel.PumpStops
import com.ridetrack.app.fuel.Station
import com.ridetrack.app.fuel.Stations
import com.ridetrack.telemetry.model.TelemetrySample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelStopsTest {
    private class Ride {
        val samples = ArrayList<TelemetrySample>()
        var t = 0L
        fun add(seconds: Int, speed: Double, fix: Boolean = true) = repeat(seconds) {
            samples += TelemetrySample(t, if (fix) 28.6 else null, if (fix) 77.2 else null, speed, null, null, null, null, null, null)
            t += 1_000
        }
    }

    @Test fun `creeping forward in the pump queue and GPS wobble don't break the stop`() {
        val r = Ride()
        r.add(60, 12.0)
        // 40 s waiting, inch up 3 s at 2 m/s, 50 s filling with two GPS jumps to 2.5 m/s, 30 s paying.
        r.add(40, 0.0); r.add(3, 2.0); r.add(25, 0.3); r.add(1, 2.5); r.add(24, 0.2); r.add(1, 2.5); r.add(30, 0.0)
        r.add(60, 12.0)
        val stops = PumpStops.find(r.samples)
        assertEquals(1, stops.size)
        assertTrue(stops[0].toMillis - stops[0].fromMillis in 120_000L..130_000L)
    }

    @Test fun `riding on for real ends it, and a red light is still too short`() {
        val r = Ride()
        r.add(60, 12.0); r.add(40, 0.0); r.add(60, 12.0)
        assertTrue(PumpStops.find(r.samples).isEmpty())
    }

    @Test fun `no GPS under the canopy, the stop is kept at the position from just before`() {
        val r = Ride()
        r.add(60, 12.0)
        r.add(150, 0.0, fix = false)
        r.add(60, 12.0)
        val stop = PumpStops.find(r.samples).single()
        assertTrue(stop.guessed)
        assertEquals(28.6, stop.latitude)
    }

    @Test fun `prompts keep where and how, and older stored ones still read`() {
        val p = FuelPrompt("r", "b", 5, "Indian Oil", 500.0, 28.6, 77.2, sure = false, how = "sms")
        assertEquals(p, Fuel.decodePrompts(Fuel.encodePrompts(listOf(p))).single())
        val old = Fuel.decodePrompts("r|b|5|HP|").single()
        assertTrue(old.sure)
        assertEquals("stop", old.how)
        assertNull(old.latitude)
    }

    @Test fun `stations, the nearest within reach, read from OpenStreetMap`() {
        val list = Stations.parseOverpass(
            """{"elements":[{"type":"node","lat":28.6001,"lon":77.2001,"tags":{"name":"HP Petrol Pump"}},
               {"type":"way","center":{"lat":28.61,"lon":77.21},"tags":{"brand":"Indian Oil"}},
               {"type":"node","lat":28.6,"lon":77.3,"tags":{}}]}""",
        )
        assertEquals(listOf("HP Petrol Pump", "Indian Oil", "Fuel station"), list.map { it.name })
        // 15 m away: found; the others are kilometres away.
        assertEquals("HP Petrol Pump", Stations.nearest(list, 28.6, 77.2, 150.0)?.name)
        assertNull(Stations.nearest(list, 28.65, 77.25, 150.0))
        // 120 m from a big station's pin is still that station.
        assertFalse(Stations.nearest(listOf(Station("Big", 28.6, 77.2)), 28.6011, 77.2, 150.0) == null)
    }
}
