package com.ridetrack.app

import com.ridetrack.app.ui.common.Odometer
import com.ridetrack.app.ui.home.HomeStats
import com.ridetrack.app.ui.home.SpeedTrace
import com.ridetrack.app.ui.home.lastRiddenLabel
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.FuelType
import com.ridetrack.telemetry.model.MountOrientation
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.RideStatus
import com.ridetrack.telemetry.model.TelemetrySample
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeStatsTest {
    // Tuesday 29 September 2026, 10:00 UTC.
    private val now = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, ZoneOffset.UTC)

    private fun at(month: Int, day: Int, hour: Int = 8) =
        ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun ride(
        start: Long,
        km: Double,
        bikeId: String = "ns200",
        source: DataSourceKind = DataSourceKind.PHONE,
        topMps: Double? = null,
        left: Double? = null,
        right: Double? = null,
    ) = Ride(
        id = "r$start$bikeId$km", bikeId = bikeId, name = "", status = RideStatus.COMPLETED, source = source,
        startTimeMillis = start, endTimeMillis = start + 3_600_000,
        stats = RideStats(distanceM = km * 1000, movingMillis = 3_000_000, maxSpeedMps = topMps, maxLeftLeanDeg = left, maxRightLeanDeg = right),
    )

    private fun bike(odometer: Double?, setAt: Long?) = Bike(
        id = "ns200", make = "Bajaj", model = "Pulsar NS200", year = 2022, displacementCc = 200, weightKg = null,
        fuelType = FuelType.PETROL, mountOrientation = MountOrientation.PORTRAIT, calibration = null, createdAtMillis = 0,
        odometerKm = odometer, odometerSetAtMillis = setAt,
    )

    @Test
    fun `odometer adds real rides on the bike since it was set`() {
        val rides = listOf(
            ride(at(9, 1), 10.0), // before the reading: already counted in it
            ride(at(9, 13), 24.8),
            ride(at(9, 29), 32.9),
            ride(at(9, 20), 50.0, source = DataSourceKind.DEMO),
            ride(at(9, 21), 70.0, bikeId = "ktm"),
        )
        assertEquals(18_457.7, Odometer.readingKm(bike(18_400.0, at(9, 12)), rides)!!, 1e-6)
        assertNull(Odometer.readingKm(bike(null, null), rides))
    }

    @Test
    fun `periods split by day, monday week and month, demo rides excluded`() {
        val rides = listOf(
            ride(at(9, 29), 32.9, topMps = 94 / 3.6, left = 43.0, right = 30.0),
            ride(at(9, 22), 64.9, topMps = 98 / 3.6, right = 50.0),
            ride(at(9, 6), 21.4),
            ride(at(9, 28), 99.0, source = DataSourceKind.DEMO),
            ride(at(8, 30), 40.0),
        )
        val s = HomeStats.from(rides, now)
        assertEquals(32_900.0, s.today.distanceM, 1e-6)
        assertEquals(94.0, s.today.topSpeedMps!! * 3.6, 1e-6)
        assertEquals(-43.0, s.today.maxLeanDeg!!, 1e-6)
        assertEquals(1, s.week.rides)
        assertEquals(7, s.week.days.size)
        assertEquals(1, s.week.todayIndex)
        assertEquals("51% of last week · 64.9 km", s.week.comparison)
        assertEquals(3, s.month.rides)
        assertEquals(119_200.0, s.month.distanceM, 1e-6)
        assertEquals(50.0, s.month.maxLeanDeg!!, 1e-6)
        assertEquals(30, s.month.days.size)
        assertEquals(28, s.month.todayIndex)
        assertEquals("Day 29 of 30 · on pace for 123 km", s.month.comparison)
    }

    @Test
    fun `today compares with the usual riding day`() {
        val usual = listOf(ride(at(9, 20), 40.0), ride(at(9, 22), 60.0))
        assertEquals("No ride yet today", HomeStats.from(usual, now).today.comparison)
        assertEquals("33% of your usual riding day", HomeStats.from(usual + ride(at(9, 29), 16.5), now).today.comparison)
        assertNull(HomeStats.from(listOf(ride(at(9, 29), 16.5)), now).today.comparison)
    }

    @Test
    fun `speed trace averages samples into buckets`() {
        val samples = (0..9).map { i ->
            TelemetrySample(i * 1000L, null, null, i.toDouble(), null, null, null, null, null, null)
        }
        val t = SpeedTrace.from(samples, buckets = 4, isToday = true)!!
        assertEquals(4, t.speedsMps.size)
        assertEquals(8.5, t.speedsMps.last(), 1e-6)
        assertEquals(3, t.peakIndex)
        assertNull(SpeedTrace.from(samples.take(1), buckets = 4, isToday = true))
    }

    @Test
    fun `last ridden labels`() {
        assertEquals("Ridden today", lastRiddenLabel(at(9, 29), now))
        assertEquals("Ridden yesterday", lastRiddenLabel(at(9, 28), now))
        assertEquals("12 days ago", lastRiddenLabel(at(9, 17), now))
        assertEquals("Not ridden yet", lastRiddenLabel(null, now))
    }
}
