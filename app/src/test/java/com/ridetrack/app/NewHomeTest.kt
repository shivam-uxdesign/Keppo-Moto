package com.ridetrack.app

import com.ridetrack.app.moments.MicChoice
import com.ridetrack.app.moments.MicType
import com.ridetrack.app.ui.home.BikeCare
import com.ridetrack.app.ui.home.CareItem
import com.ridetrack.app.ui.home.CheckState
import com.ridetrack.app.ui.home.GpsReadiness
import com.ridetrack.app.ui.home.Milestones
import com.ridetrack.app.ui.home.ReadyFix
import com.ridetrack.app.ui.home.ReadyInputs
import com.ridetrack.app.ui.home.Readiness
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.RideStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewHomeTest {
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    // Bike care

    private fun item(km: Int? = null, days: Int? = null, doneKm: Double? = 1_000.0, doneAt: Long = now) =
        CareItem("c", "b", "Chain lube", km, days, doneKm, doneAt)

    @Test
    fun `km reminders count down with the odometer`() {
        val s = BikeCare.status(item(km = 500), odometerKm = 1_420.0, now = now)
        assertEquals("Due in 80 km", s.text)
        assertFalse(s.due)
        assertEquals(0.84f, s.used, 0.001f)
        val over = BikeCare.status(item(km = 500), odometerKm = 1_530.0, now = now)
        assertTrue(over.due)
        assertEquals("Due now · 30 km over", over.text)
    }

    @Test
    fun `days reminders, and the closer of km and date wins`() {
        assertEquals("Due in 4 days", BikeCare.status(item(days = 14, doneAt = now - 10 * day), null, now).text)
        assertTrue(BikeCare.status(item(days = 14, doneAt = now - 15 * day), null, now).due)
        // 10 % of the km used, 90 % of the days: the days lead.
        val both = BikeCare.status(item(km = 1_000, days = 10, doneAt = now - 9 * day), 1_100.0, now)
        assertEquals(0.9f, both.used, 0.01f)
    }

    @Test
    fun `km reminders without an odometer wait, then anchor to the first reading`() {
        assertEquals("Set the odometer to count km", BikeCare.status(item(km = 500, doneKm = null), null, now).text)
        val anchored = BikeCare.anchor(listOf(item(km = 500, doneKm = null)), mapOf("b" to 4_812.0))!!
        assertEquals(4_812.0, anchored.single().doneAtKm)
        assertNull(BikeCare.anchor(anchored, mapOf("b" to 4_900.0)))
    }

    @Test
    fun `reminders survive being stored`() {
        val items = listOf(item(km = 500), CareItem("d", "b", "Oil | filter", 3_000, 180, null, now))
        val back = BikeCare.decode(BikeCare.encode(items))
        assertEquals(2, back.size)
        assertEquals(items[0], back[0])
        assertEquals("Oil / filter", back[1].name)
        assertEquals(emptyList(), BikeCare.decode("junk"))
    }

    // Readiness

    @Test
    fun `all fine is one quiet line`() {
        val checks = Readiness.checks(
            ReadyInputs(momentsOn = true, mics = listOf(MicChoice(MicType.USB, "DJI MIC MINI")), batteryPct = 82, freeBytes = 52_000_000_000),
        )
        assertTrue(checks.none { it.state == CheckState.PROBLEM })
        assertEquals("Ready to ride · DJI MIC MINI · GPS · Mount", Readiness.summary(checks))
    }

    @Test
    fun `a chosen mic that isn't plugged in is the problem, first`() {
        val checks = Readiness.checks(
            ReadyInputs(momentsOn = true, micChoice = MicChoice(MicType.USB, "DJI MIC MINI"), mics = emptyList(), batteryPct = 82),
        )
        val first = checks.first()
        assertEquals(CheckState.PROBLEM, first.state)
        assertEquals("DJI MIC MINI not connected", first.problem)
        assertTrue(first.detail!!.contains("phone mic"))
    }

    @Test
    fun `location off, pop-up, camera, battery and storage have fixes or reasons`() {
        val checks = Readiness.checks(
            ReadyInputs(
                gps = GpsReadiness.DISABLED, momentsOn = true, cameraAllowed = false, hudOn = true, overlayAllowed = false,
                batteryPct = 12, freeBytes = 800_000_000,
            ),
        )
        val problems = checks.filter { it.state == CheckState.PROBLEM }
        assertEquals(setOf(ReadyFix.LOCATION_SETTINGS, ReadyFix.APP_SETTINGS, ReadyFix.OVERLAY), problems.mapNotNull { it.fix }.toSet())
        assertTrue(problems.any { it.problem == "Battery 12%" })
        assertTrue(problems.any { it.problem == "Phone storage almost full" })
        // Charging from the bike: low battery is fine.
        assertTrue(Readiness.checks(ReadyInputs(batteryPct = 12, charging = true)).none { it.state == CheckState.PROBLEM })
    }

    // Milestones

    private fun ride(daysAgo: Int, km: Double, topKmh: Double, lean: Double? = null, demo: Boolean = false) = Ride(
        id = "r$daysAgo", bikeId = "b", name = "Ride $daysAgo", status = RideStatus.COMPLETED,
        source = if (demo) DataSourceKind.DEMO else DataSourceKind.PHONE,
        startTimeMillis = now - daysAgo * day, endTimeMillis = now - daysAgo * day + 3_600_000,
        stats = RideStats(distanceM = km * 1000, maxSpeedMps = topKmh / 3.6, maxLeftLeanDeg = lean),
    )

    @Test
    fun `the latest ride's new bests show for a week`() {
        val rides = listOf(ride(10, 40.0, 93.5, 30.0), ride(2, 60.0, 90.0), ride(1, 29.8, 117.8, 35.0))
        val m = Milestones.of(rides, now)
        assertEquals(listOf("New top speed: 117.8 km/h", "Deepest lean yet: 35°"), m.map { it.title })
        assertTrue(m[0].detail.startsWith("Ride 1 · was 93.5 on "))
        assertEquals(emptyList(), Milestones.of(rides, now + 8 * day))
    }

    @Test
    fun `distance totals and longest ride, demo rides ignored`() {
        val rides = listOf(ride(5, 80.0, 120.0), ride(1, 90.0, 60.0), ride(0, 500.0, 200.0, demo = true))
        assertEquals(listOf("Longest ride yet: 90.0 km", "100 km ridden"), Milestones.of(rides, now).map { it.title })
        assertEquals(emptyList(), Milestones.of(listOf(ride(1, 30.0, 90.0)), now))
    }
}
