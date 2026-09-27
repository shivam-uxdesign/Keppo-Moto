package com.ridetrack.app

import com.ridetrack.app.ride.RideNames
import com.ridetrack.app.ui.format.Format
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun `unknown values are dashes, never zero`() {
        assertEquals(Format.DASH, Format.speedKmh(null))
        assertEquals(Format.DASH, Format.lean(null))
        assertEquals(Format.DASH, Format.gSigned(null))
        assertEquals(Format.DASH, Format.g(null))
        assertEquals(Format.DASH, Format.altitude(null))
        assertEquals("Accuracy unavailable", Format.accuracy(null))
        assertEquals(Format.DASH, Format.duration(null))
    }

    @Test
    fun `real zero is shown as zero`() {
        assertEquals("0", Format.speedKmh(0.0))
        assertEquals("0°", Format.lean(0.3))
        assertEquals("+0.00 G", Format.gSigned(0.0))
    }

    @Test
    fun `lean direction is spelled out`() {
        assertEquals("18° R", Format.lean(18.2))
        assertEquals("12° L", Format.lean(-11.6))
    }

    @Test
    fun `speed distance and durations`() {
        assertEquals("72", Format.speedKmh(20.0))
        assertEquals("24.6 km", Format.distance(24_560.0))
        assertEquals("38:12", Format.clock(38 * 60_000L + 12_000L))
        assertEquals("1:02:45", Format.clock(3_765_000L))
        assertEquals("52 min", Format.duration(52 * 60_000L))
        assertEquals("1h 42m", Format.duration(102 * 60_000L))
        assertEquals("+0.32 G", Format.gSigned(0.321))
        assertEquals("-0.41 G", Format.gSigned(-0.41))
    }

    @Test
    fun `ride names follow time of day`() {
        val sundayEvening = LocalDateTime.of(2026, 9, 27, 18, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Sunday Evening Ride", RideNames.forStart(sundayEvening, ZoneOffset.UTC, Locale.US))
        val mondayMorning = LocalDateTime.of(2026, 9, 28, 7, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Monday Morning Ride", RideNames.forStart(mondayMorning, ZoneOffset.UTC, Locale.US))
    }

    @Test
    fun `rpm and gear read cleanly and unknown stays a dash`() {
        assertEquals("6,240", Format.rpm(6_238.0))
        assertEquals(Format.DASH, Format.rpm(null))
        assertEquals("N", Format.gear(0))
        assertEquals("4", Format.gear(4))
        assertEquals(Format.DASH, Format.gear(null))
    }
}
