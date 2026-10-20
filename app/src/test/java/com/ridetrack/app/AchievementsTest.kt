package com.ridetrack.app

import com.ridetrack.app.ui.format.UnitSystem
import com.ridetrack.app.ui.format.Units
import com.ridetrack.app.ui.profile.AchievementInputs
import com.ridetrack.app.ui.profile.Achievements
import com.ridetrack.app.ui.profile.MomentFact
import com.ridetrack.app.ui.profile.RideFacts
import com.ridetrack.app.ui.profile.RideFactsStore
import com.ridetrack.app.ui.profile.Tier
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.RideStatus
import com.ridetrack.telemetry.model.TelemetrySample
import java.time.LocalDate
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

    private fun inputs(rides: List<Ride>, facts: Map<String, RideFacts> = emptyMap(), moments: List<MomentFact> = emptyList(), fills: List<Long> = emptyList()) =
        AchievementInputs(rides, emptyList(), 0, fills, 0, crashAlertsReady = true, mountCalibrated = false, facts = facts, moments = moments)

    @Test fun `distance levels up through the tiers, on the ride that crossed each`() {
        val rides = listOf(ride("a", at(2026, 10, 3, 9), 60.0), ride("b", at(2026, 10, 4, 9), 50.0), ride("c", at(2026, 10, 6, 9), 400.0), ride("d", at(2026, 10, 5, 9), 500.0, demo = true))
        val dist = Achievements.of(inputs(rides), zone, at(2026, 10, 14, 12)).first { it.id == "dist" }
        assertEquals(2, dist.level)
        assertEquals(Tier.SILVER, dist.tier)
        assertEquals("c", dist.rideId)
        assertEquals(at(2026, 10, 4, 9), dist.reachedAt[0])
        assertEquals(1_000.0, dist.next)
        assertEquals("490 km to go", dist.left())
    }

    @Test fun `long days, weekends, sunrise and the setup checklist`() {
        val rides = listOf(
            ride("1", at(2026, 10, 3, 6), 120.0), ride("2", at(2026, 10, 3, 14), 90.0),
            ride("3", at(2026, 10, 10, 9), 20.0), ride("4", at(2026, 10, 17, 9), 20.0), ride("5", at(2026, 10, 24, 21), 20.0, hours = 4.5),
        )
        val i = inputs(rides)
        val list = Achievements.of(i, zone, at(2026, 10, 25, 12)).associateBy { it.id }
        assertEquals(2, list.getValue("day").level)
        assertTrue(list.getValue("weekends").earned)
        assertEquals("1", list.getValue("sunrise").rideId)
        assertEquals("5", list.getValue("night").rideId)
        assertEquals(2, list.getValue("hours").level)
        assertEquals(listOf(true, false), Achievements.setup(i).map { it.second })
    }

    @Test fun `exploring counts new places, new roads after the first ride, height and hairpins`() {
        val rides = listOf(ride("a", at(2026, 1, 1, 9), 10.0), ride("b", at(2026, 2, 1, 9), 10.0))
        val facts = mapOf(
            "a" to RideFacts(maxAltitudeM = 300.0, hairpins = 4, cells = mapOf("1" to 5_000.0, "2" to 5_000.0), places = listOf("Noida", "Delhi"), states = listOf("Uttar Pradesh")),
            "b" to RideFacts(maxAltitudeM = 1_200.0, hairpins = 7, cells = mapOf("2" to 5_000.0, "3" to 120_000.0), places = listOf("Delhi", "Mussoorie"), states = listOf("Uttarakhand")),
        )
        val list = Achievements.of(inputs(rides, facts), zone, at(2026, 3, 1, 9)).associateBy { it.id }
        assertEquals(3.0, list.getValue("places").progress)
        assertEquals(120.0, list.getValue("roads").progress, 0.01)
        assertTrue(list.getValue("roads").earned)
        assertEquals("b", list.getValue("altitude").rideId)
        assertEquals(11.0, list.getValue("hairpins").progress)
        assertEquals(2.0, list.getValue("states").progress)
        assertTrue(list.getValue("states").earned)
    }

    @Test fun `secret badges come from keywords and dates`() {
        val rides = listOf(ride("a", at(2025, 12, 31, 23), 10.0, hours = 2.0), ride("b", at(2026, 12, 31, 9), 10.0))
        val moments = listOf(MomentFact("a", at(2025, 12, 31, 23), setOf("sneeze", "talking")))
        val fills = listOf(at(2026, 5, 1, 8), at(2026, 5, 1, 12), at(2026, 5, 1, 18))
        val list = Achievements.of(inputs(rides, moments = moments, fills = fills), zone, at(2027, 1, 2, 9)).associateBy { it.id }
        assertTrue(list.getValue("secret-sneeze").earned)
        assertFalse(list.getValue("secret-scream").earned)
        assertTrue(list.getValue("secret-midnight").earned)
        assertTrue(list.getValue("secret-again").earned)
        assertTrue(list.getValue("secret-tanks").earned)
        assertTrue(list.getValue("secret-sneeze").secret)
    }

    @Test fun `the rider's level comes from badge points`() {
        val none = Achievements.level(Achievements.of(inputs(emptyList()), zone))
        assertEquals("Rookie", none.name)
        assertEquals(6, none.to)
        val rides = (1..40).map { ride("r$it", at(2026, 1, 1, 9) + it * 86_400_000L * 7, 150.0) }
        val lvl = Achievements.level(Achievements.of(inputs(rides), zone, at(2026, 12, 1, 9)))
        // Distance to Platinum (10), riding days and a long day (1 each), monsoon (2): 14 points.
        assertEquals("Rider", lvl.name, "points ${lvl.points}")
    }

    @Test fun `no speed or lean goals or records`() {
        val i = inputs(listOf(ride("a", at(2026, 1, 1, 9), 10.0)))
        val ids = Achievements.of(i, zone).map { it.id + it.title.lowercase() }
        assertTrue(ids.none { "speed" in it || "lean" in it || "km/h" in it })
        assertTrue(Achievements.records(i, zone).none { "speed" in it.title.lowercase() || "lean" in it.title.lowercase() })
        assertNull(Achievements.of(inputs(emptyList()), zone).firstOrNull { it.earned && it.id == "dist" })
    }

    @Test fun `the riding year and the weekend streak`() {
        val rides = listOf(ride("a", at(2026, 10, 3, 9), 20.0), ride("b", at(2026, 10, 3, 15), 10.0), ride("c", at(2026, 10, 10, 9), 5.0))
        val year = Achievements.ridingYear(rides, 2026, zone)
        assertEquals(30.0, year.getValue(LocalDate.of(2026, 10, 3)), 0.01)
        assertEquals(2, year.size)
        assertEquals(2 to 2, Achievements.streak(rides, zone, at(2026, 10, 12, 9)))
        assertEquals(0 to 2, Achievements.streak(rides, zone, at(2026, 11, 20, 9)))
    }

    @Test fun `a ride's facts are its highest point with a good fix, hairpins and ground covered`() {
        fun s(t: Long, lat: Double, heading: Double, alt: Double, acc: Double = 5.0) = TelemetrySample(t, lat, 77.0, 8.0, alt, heading, null, null, null, acc)
        val samples = (0..30).map { s(it * 1_000L, 28.0 + it * 0.0005, (it * 18.0) % 360, 200.0 + it, if (it == 30) 80.0 else 5.0) }
        val f = RideFactsStore.of(samples)
        assertEquals(229.0, f.maxAltitudeM)
        assertTrue(f.hairpins >= 2)
        assertTrue(f.cells.size >= 3)
        assertTrue(f.cells.values.sum() in 1_500.0..1_800.0, "${f.cells.values.sum()}")
    }
}
