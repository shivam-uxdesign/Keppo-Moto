package com.ridetrack.app

import com.ridetrack.app.fuel.FuelFill
import com.ridetrack.app.widget.HomeWidgetData
import com.ridetrack.app.widget.Readiness
import com.ridetrack.app.widget.WidgetSize
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.RideStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeWidgetDataTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(date: String, hm: String = "07:00") = Instant.parse("${date}T$hm:00+05:30".let { java.time.OffsetDateTime.parse(it).toInstant().toString() }).toEpochMilli()
    private fun ride(id: String, date: String, km: Double, topKmh: Double? = null, left: Double? = null, right: Double? = null, demo: Boolean = false) = Ride(
        id, "b1", "Ride $id", RideStatus.COMPLETED, if (demo) DataSourceKind.DEMO else DataSourceKind.PHONE, at(date), at(date) + 3_600_000,
        RideStats(distanceM = km * 1000, maxSpeedMps = topKmh?.div(3.6), maxLeftLeanDeg = left, maxRightLeanDeg = right),
    )

    @Test fun `each widget picks its design from the home-screen size`() {
        assertEquals(WidgetSize(1, 1), WidgetSize.of(70, 100))
        assertEquals(WidgetSize(1, 1), WidgetSize.of(110, 110))
        assertEquals(WidgetSize(2, 1), WidgetSize.of(180, 110))
        assertEquals(WidgetSize(2, 2), WidgetSize.of(180, 220))
        assertEquals(WidgetSize(4, 1), WidgetSize.of(320, 100))
        assertEquals(WidgetSize(4, 2), WidgetSize.of(320, 220))
        assertEquals(WidgetSize(4, 3), WidgetSize.of(320, 330))
    }

    @Test fun `readiness names the one thing to fix first, or says all set`() {
        fun r(location: Boolean = true, gps: Boolean = true, mic: String? = "DJI Mic · USB-C", micOn: Boolean = true, free: Long = 50_000_000_000, battery: Int = 80, charging: Boolean = false, crash: Boolean = true, contacts: Int = 1) =
            Readiness.of(location, gps, mic, micOn, free, battery, charging, crash, contacts)
        assertTrue(r().ok)
        assertEquals("All set", r().short)
        assertEquals("Mic off", r(micOn = false).short)
        assertEquals("Turn on DJI Mic", r(micOn = false).fix)
        assertEquals("GPS off", r(gps = false, micOn = false).short)
        assertEquals("Location off", r(location = false, gps = false).short)
        assertEquals("Storage low", r(free = 1_200_000_000).short)
        assertEquals("Battery 12%", r(battery = 12).short)
        assertTrue(r(battery = 12, charging = true).ok)
        assertEquals("No crash contacts", r(contacts = 0).short)
        assertTrue(r(crash = false, contacts = 0).ok)
        // Automatic or the phone mic: nothing to plug in.
        assertTrue(r(mic = null, micOn = false).ok)
    }

    @Test fun `fuel left is the usual tank less what's been ridden since the last fill`() {
        val fills = listOf("2026-10-01", "2026-10-06", "2026-10-11", "2026-10-16").mapIndexed { k, d -> FuelFill("f$k", "b1", at(d, "06:00"), 10.0, null, null) }
        // 300, 280 and 320 km between fills; 170 km since the last.
        val rides = listOf("2026-10-02" to 300.0, "2026-10-07" to 280.0, "2026-10-12" to 320.0, "2026-10-17" to 170.0).map { (d, km) -> at(d) to km * 1000 }
        assertEquals(130.0, HomeWidgetData.rangeKm(fills, rides)!!, 0.01)
        assertNull(HomeWidgetData.rangeKm(fills.take(2), rides))
    }

    @Test fun `records come from real rides, and the newest ride's records are marked new`() {
        val rides = listOf(
            ride("a", "2026-10-06", 212.0, 118.0, left = 41.0),
            ride("b", "2026-10-06", 36.0),
            ride("c", "2026-10-19", 60.0, 104.0, right = 33.0),
            ride("d", "2026-10-20", 45.4, 121.0, left = 38.0),
            ride("demo", "2026-10-20", 900.0, 300.0, demo = true),
        )
        val recs = HomeWidgetData.records(rides, ist).associateBy { it.label }
        assertEquals("121", recs.getValue("TOP SPEED").value)
        assertTrue(recs.getValue("TOP SPEED").fresh)
        assertEquals("41°", recs.getValue("LEAN LEFT").value)
        assertTrue(!recs.getValue("LEAN LEFT").fresh)
        assertEquals("33°", recs.getValue("LEAN RIGHT").value)
        assertEquals("212", recs.getValue("LONGEST RIDE").value)
        assertEquals("248", recs.getValue("BEST DAY").value)
        assertEquals("2 rides", recs.getValue("BEST DAY").context.substringAfter(" · "))
        assertTrue(recs.getValue("BEST WEEK").context.startsWith("Week of 5 Oct"))
    }

    @Test fun `the calendar shades days by distance, and a streak runs to yesterday until you ride today`() {
        val days = HomeWidgetData.dayKm(listOf(ride("a", "2026-10-17", 18.0), ride("b", "2026-10-18", 212.0), ride("c", "2026-10-19", 60.0)), ist)
        assertEquals(3, HomeWidgetData.streak(days, LocalDate.of(2026, 10, 20)))
        assertEquals(0, HomeWidgetData.streak(days, LocalDate.of(2026, 10, 22)))
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0.0, 18.0, 60.0, 120.0, 212.0).map(HomeWidgetData::shade))
    }

    @Test fun `sunrise and sunset in Delhi on 20 Oct, worked out on the phone`() {
        val (rise, set) = assertNotNull(HomeWidgetData.sunTimes(28.61, 77.21, LocalDate.of(2026, 10, 20)))
        val r = Instant.ofEpochMilli(rise).atZone(ist).toLocalTime()
        val s = Instant.ofEpochMilli(set).atZone(ist).toLocalTime()
        // Published: about 06:28 and 17:44.
        assertTrue(abs(r.toSecondOfDay() - 6 * 3600 - 28 * 60) < 10 * 60, "sunrise $r")
        assertTrue(abs(s.toSecondOfDay() - 17 * 3600 - 44 * 60) < 10 * 60, "sunset $s")
    }

    @Test fun `a ride card reads like the prototype`() {
        val c = HomeWidgetData.ride(ride("d", "2026-10-20", 45.4, 121.0, left = 38.0, right = 22.0), 12, ist)
        assertEquals("TUE", c.day)
        assertEquals("Tue 20 Oct", c.whenText)
        assertEquals("45.4", c.km)
        assertEquals("1:00", c.time)
        assertEquals("121", c.top)
        assertEquals("38°", c.lean)
        assertTrue(c.leanLeft)
    }
}
