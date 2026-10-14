package com.ridetrack.app

import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.format.UnitSystem
import com.ridetrack.app.ui.format.Units
import com.ridetrack.app.ui.rides.DistanceFilter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitsTest {
    @AfterTest fun reset() {
        Units.system = UnitSystem.METRIC
        Units.country = ""
    }

    private fun miles(country: String = "US") {
        Units.system = UnitSystem.IMPERIAL
        Units.country = country
    }

    @Test fun defaultsFromCountry() {
        assertEquals(UnitSystem.IMPERIAL, Units.defaultFor("US"))
        assertEquals(UnitSystem.IMPERIAL, Units.defaultFor("gb"))
        assertEquals(UnitSystem.METRIC, Units.defaultFor("IN"))
        assertEquals(UnitSystem.METRIC, Units.defaultFor(""))
    }

    @Test fun metricIsUnchanged() {
        assertEquals("100", Format.speedValue(100 / 3.6))
        assertEquals("12.3 km", Format.distance(12_300.0))
        assertEquals("km/l", Units.economyUnit)
        assertEquals("L", Units.volumeUnit)
    }

    @Test fun milesEverywhere() {
        miles()
        assertEquals("62", Format.speedValue(100 / 3.6))
        assertEquals("62 mph", Units.kmhText(100.0))
        assertEquals("10.0 mi", Format.distance(16_093.44))
        assertEquals("6,214", Format.odometer(10_000.0))
        assertEquals(10_000.0, Units.toKm(Units.fromKm(10_000.0)), 1e-6)
        assertEquals("< 10 mi", DistanceFilter.SHORT.label)
        assertTrue(16_000.0 in DistanceFilter.SHORT.range && 17_000.0 in DistanceFilter.MEDIUM.range)
    }

    @Test fun fuelInGallonsInTheUsAndLitresInTheUk() {
        miles("US")
        assertEquals("gal", Units.volumeUnit)
        assertEquals(1.0, Units.volume(Units.US_GALLON), 1e-9)
        // 20 km/l is about 47 US mpg.
        assertEquals(47.0, Units.economy(20.0), 0.1)
        assertEquals(1.0 * Units.US_GALLON, Units.pricePerVolume(1.0), 1e-9)
        miles("GB")
        assertEquals("L", Units.volumeUnit)
        assertEquals("mpg", Units.economyUnit)
        // 20 km/l is about 56.5 imperial mpg.
        assertEquals(56.5, Units.economy(20.0), 0.1)
    }
}
