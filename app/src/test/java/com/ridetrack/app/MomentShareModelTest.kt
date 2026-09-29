package com.ridetrack.app

import com.ridetrack.app.share.MomentField
import com.ridetrack.app.share.MomentOverlay
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.moments.TelemetryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MomentShareModelTest {
    private fun overlay(types: Set<RideEventType>, lean: Double?, g: Double?, lat: Double? = 1.0) = MomentOverlay(
        eventTypes = types, eventValue = null, timeText = "5:42 PM", dateText = "Sun 27 Sep", rideName = "Ride",
        point = TelemetryPoint(0, 12.0, lean, g, null, lat, if (lat == null) null else 2.0),
        route = listOf(1.0 to 2.0, 1.1 to 2.1), demo = false,
    )

    @Test
    fun `rows appear only when switched on and there is data`() {
        val all = MomentField.DEFAULT
        val photo = overlay(emptySet(), lean = 1.0, g = 0.05)
        assertFalse(photo.shows(MomentField.EVENT, all)) // a photo has no event
        assertFalse(photo.shows(MomentField.LEAN, all)) // a wobble isn't lean
        assertFalse(photo.shows(MomentField.G_FORCE, all)) // noise isn't G
        assertTrue(photo.shows(MomentField.SPEED, all))
        assertFalse(photo.shows(MomentField.SPEED, all - MomentField.SPEED))

        val brake = overlay(setOf(RideEventType.HARD_BRAKE), lean = -24.0, g = -0.1)
        assertTrue(brake.shows(MomentField.G_FORCE, all)) // shown for a brake moment even when small now
        assertTrue(brake.shows(MomentField.LEAN, all))
        assertEquals("Hard braking", brake.eventLabel)
    }

    @Test
    fun `no map without a position`() {
        assertFalse(overlay(emptySet(), null, null, lat = null).shows(MomentField.MAP, MomentField.DEFAULT))
        assertTrue(overlay(emptySet(), null, null).shows(MomentField.MAP, MomentField.DEFAULT))
    }
}
