package com.ridetrack.app

import com.ridetrack.app.ui.format.UnitSystem
import com.ridetrack.app.ui.format.Units
import com.ridetrack.app.ui.profile.AchievementInputs
import com.ridetrack.app.ui.profile.Achievements
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.RideStatus
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AchievementsTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @AfterTest fun reset() { Units.system = UnitSystem.METRIC }

    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun ride(id: String, start: Long, km: Double, hours: Double = 1.0, demo: Boolean = false) = Ride(
        id = id, bikeId = "b", name = "Ride $id", status = RideStatus.COMPLETED, source = if (demo) DataSourceKind.DEMO else DataSourceKind.PHONE,
        startTimeMillis = start, endTimeMillis = start + (hours * 3_600_000).toLong(), stats = RideStats(distanceM = km * 1000),
    )

    private fun inputs(rides: List<Ride>) = AchievementInputs(rides, emptyList(), 0, emptyList(), 0, crashAlertsReady = true, mountCalibrated = false)

    @Test fun `distance is earned on the ride that crossed it, demo rides don't count`() {
        val rides = listOf(ride("a", at(2026, 10, 3, 9), 60.0), ride("b", at(2026, 10, 4, 9), 50.0), ride("d", at(2026, 10, 5, 9), 500.0, demo = true))
        val list = Achievements.of(inputs(rides), zone, at(2026, 10, 14, 12)).associateBy { it.id }
        assertTrue(list.getValue("dist-100").earned)
        assertEquals("b", list.getValue("dist-100").rideId)
        assertFalse(list.getValue("dist-500").earned)
        assertEquals(110.0, list.getValue("dist-500").progress, 0.01)
    }

    @Test fun `long days, weekends, sunrise and setup`() {
        // Saturdays 3, 10, 17, 24 Oct: four weekends in a row; one 210 km day; a 6 am start.
        val rides = listOf(
            ride("1", at(2026, 10, 3, 6), 120.0), ride("2", at(2026, 10, 3, 14), 90.0),
            ride("3", at(2026, 10, 10, 9), 20.0), ride("4", at(2026, 10, 17, 9), 20.0), ride("5", at(2026, 10, 24, 21), 20.0, hours = 4.5),
        )
        val list = Achievements.of(inputs(rides), zone, at(2026, 10, 25, 12)).associateBy { it.id }
        assertTrue(list.getValue("day-200").earned)
        assertFalse(list.getValue("day-400").earned)
        assertTrue(list.getValue("weekends-4").earned)
        assertEquals("1", list.getValue("sunrise").rideId)
        assertEquals("5", list.getValue("night").rideId)
        assertTrue(list.getValue("hours-4").earned)
        assertTrue(list.getValue("crash").earned)
        assertFalse(list.getValue("mount").earned)
    }

    @Test fun `no speed or lean goals`() {
        val ids = Achievements.of(inputs(emptyList()), zone).map { it.id + it.title.lowercase() }
        assertTrue(ids.none { "speed" in it || "lean" in it || "km/h" in it })
        assertNull(Achievements.of(inputs(emptyList()), zone).firstOrNull { it.earned && it.id.startsWith("dist") })
    }
}
