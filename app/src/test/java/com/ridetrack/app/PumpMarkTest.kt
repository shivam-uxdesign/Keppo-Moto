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
}
