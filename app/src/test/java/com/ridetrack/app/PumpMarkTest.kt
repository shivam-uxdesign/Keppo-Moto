package com.ridetrack.app

import androidx.test.core.app.ApplicationProvider
import com.ridetrack.app.fuel.StationFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PumpMarkTest {
    @Test fun `a pump marked on the map is found at later stops, offline, and can be forgotten`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val finder = StationFinder(context)
        finder.remember("HP, Baner Road", 18.5590, 73.7868)
        // A stop 50 m away, on a later ride: matched from the phone alone.
        assertEquals("HP, Baner Road", StationFinder(context).nearKept(18.5594, 73.7868)?.name)
        // Marking again close by replaces it rather than adding a second one.
        finder.remember("HP Baner", 18.5591, 73.7868)
        assertEquals(listOf("HP Baner"), finder.mine().map { it.name })
        val p = finder.mine().single()
        finder.forget(p.latitude, p.longitude)
        assertNull(StationFinder(context).nearKept(18.5594, 73.7868))
    }

    @Test fun `riding away from a 2-minute stop is caught as it happens, a red light isn't`() {
        val w = com.ridetrack.app.fuel.LivePumpWatch()
        var t = 0L
        fun ride(seconds: Int, speed: Double?, lat: Double?, step: Double = 0.0): com.ridetrack.app.fuel.Stop? {
            var found: com.ridetrack.app.fuel.Stop? = null
            var la = lat
            repeat(seconds * 5) {
                w.feed(t, speed, la, la?.let { 73.78 })?.let { found = it }
                t += 200
                la = la?.plus(step)
            }
            return found
        }
        assertNull(ride(30, 12.0, 18.55, 0.00001))
        // A red light: 40 s, then off.
        assertNull(ride(40, 0.0, 18.56))
        assertNull(ride(20, 12.0, 18.56, 0.00001))
        // At the pump: 50 s, creep 3 s, no GPS under the canopy for 70 s, then ride away.
        assertNull(ride(50, 0.2, 18.57))
        assertNull(ride(3, 2.5, 18.57))
        assertNull(ride(70, null, null))
        val stop = ride(15, 10.0, 18.5701, 0.00002)
        assertEquals(18.57, stop!!.latitude, 0.0001)
        assertEquals(123_000.0, (stop.toMillis - stop.fromMillis).toDouble(), 1_000.0)
    }
}
