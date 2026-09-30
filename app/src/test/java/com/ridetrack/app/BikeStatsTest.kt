package com.ridetrack.app

import com.ridetrack.app.ui.home.BikeStats
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.FuelType
import com.ridetrack.telemetry.model.MountOrientation
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.RideStatus
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BikeStatsTest {
    private val bike = Bike(
        id = "ns200", make = "Bajaj", model = "Pulsar NS200", year = 2022, displacementCc = 200, weightKg = null,
        fuelType = FuelType.PETROL, mountOrientation = MountOrientation.PORTRAIT, calibration = null, createdAtMillis = 1_000,
    )

    private fun at(month: Int, day: Int) = ZonedDateTime.of(2025, month, day, 8, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun ride(start: Long, stats: RideStats, bikeId: String = "ns200", source: DataSourceKind = DataSourceKind.PHONE) = Ride(
        id = "r$start$bikeId", bikeId = bikeId, name = "", status = RideStatus.COMPLETED, source = source,
        startTimeMillis = start, endTimeMillis = start + 3_600_000, stats = stats,
    )

    @Test
    fun `lifetime numbers from the bike's real rides`() {
        val rides = listOf(
            ride(at(3, 14), RideStats(distanceM = 32_900.0, movingMillis = 3_000_000, maxSpeedMps = 26.0, maxLeftLeanDeg = 43.0, maxRightLeanDeg = 31.0, maxBrakeG = -0.52)),
            ride(at(5, 2), RideStats(distanceM = 118_000.0, movingMillis = 9_000_000, maxSpeedMps = 31.1, maxLeftLeanDeg = 38.0, maxRightLeanDeg = 38.0, maxBrakeG = -0.71)),
            ride(at(1, 1), RideStats(distanceM = 500_000.0, maxSpeedMps = 60.0), source = DataSourceKind.DEMO),
            ride(at(2, 1), RideStats(distanceM = 9_000.0), bikeId = "ktm"),
        )
        val s = BikeStats.from(bike, rides, moments = 36)
        assertEquals(2, s.rides)
        assertEquals(12_000_000, s.ridingMillis)
        assertEquals(150_900.0, s.distanceM, 1e-9)
        assertEquals(31.1, s.topSpeedMps!!, 1e-9)
        assertEquals(43.0, s.maxLeftLeanDeg!!, 1e-9)
        assertEquals(38.0, s.maxRightLeanDeg!!, 1e-9)
        assertEquals(0.71, s.hardestBrakeG!!, 1e-9)
        assertEquals(118_000.0, s.longestRideM!!, 1e-9)
        assertEquals(36, s.moments)
        assertEquals(at(3, 14), s.sinceMillis)
        assertEquals("since Mar 2025", BikeStats.sinceLabel(s.sinceMillis, ZoneOffset.UTC))
    }

    @Test
    fun `a new bike has no numbers yet`() {
        val s = BikeStats.from(bike, emptyList(), moments = 0)
        assertEquals(0, s.rides)
        assertNull(s.topSpeedMps)
        assertNull(s.longestRideM)
        assertEquals(1_000, s.sinceMillis)
    }
}
