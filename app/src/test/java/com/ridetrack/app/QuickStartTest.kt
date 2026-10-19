package com.ridetrack.app

import android.content.Intent
import android.net.Uri
import com.ridetrack.app.ride.QuickStart
import com.ridetrack.app.widget.WidgetState
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.FuelType
import com.ridetrack.telemetry.model.MountOrientation
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class QuickStartTest {
    private fun bike(id: String) = Bike(id, "Bajaj", id, null, null, null, FuelType.PETROL, MountOrientation.PORTRAIT, null, 0L)

    @Test
    fun `the widget, shortcut, tile and Assistant link all ask for a start`() {
        assertTrue(QuickStart.isAsk(Intent(QuickStart.ACTION)))
        assertTrue(QuickStart.isAsk(Intent(Intent.ACTION_VIEW, Uri.parse("keppomoto://ride/start?exercise=ride"))))
        assertFalse(QuickStart.isAsk(Intent(Intent.ACTION_VIEW, Uri.parse("keppomoto://ride/other"))))
        assertFalse(QuickStart.isAsk(Intent(Intent.ACTION_MAIN)))
        assertFalse(QuickStart.isAsk(null))
    }

    @Test
    fun `a quick start rides the selected bike, else the first`() {
        val garage = listOf(bike("a"), bike("b"))
        assertEquals("b", QuickStart.bike(garage, "b")?.id)
        assertEquals("a", QuickStart.bike(garage, "gone")?.id)
        assertNull(QuickStart.bike(emptyList(), null))
    }

    @Test
    fun `the widget says what's ready`() {
        assertEquals("Wireless Mic Rx · USB-C connected · GPS on", WidgetState.readiness("Wireless Mic Rx · USB-C", gpsOn = true, locationAllowed = true))
        assertEquals("No external mic · GPS off", WidgetState.readiness(null, gpsOn = false, locationAllowed = true))
        assertEquals("No external mic · Location not allowed", WidgetState.readiness(null, gpsOn = true, locationAllowed = false))
    }
}
