package com.ridetrack.app

import com.ridetrack.app.ride.RideNames
import com.ridetrack.app.ride.RidePlaceNames
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RidePlacesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val start = ZonedDateTime.of(2026, 10, 13, 18, 20, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `a ride is named by its day and where it went`() {
        assertEquals("13 October | Bandra to Lonavala", RidePlaceNames.name(start, "Bandra", "Lonavala", zone, Locale.UK))
        assertEquals("13 October | Around Bandra", RidePlaceNames.name(start, "Bandra", "bandra", zone, Locale.UK))
        assertEquals("13 October | Lonavala", RidePlaceNames.name(start, null, "Lonavala", zone, Locale.UK))
        assertNull(RidePlaceNames.name(start, " ", null, zone, Locale.UK))
    }

    @Test
    fun `only the app's own name is replaced`() {
        assertTrue(RidePlaceNames.isAuto(RideNames.forStart(start, zone, Locale.UK), start, zone, Locale.UK))
        assertFalse(RidePlaceNames.isAuto("Sunset run with Arjun", start, zone, Locale.UK))
    }
}
