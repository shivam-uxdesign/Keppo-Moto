package com.ridetrack.app

import com.ridetrack.app.data.EmergencyContact
import com.ridetrack.app.data.MedicalInfo
import com.ridetrack.app.safety.AlertMessage
import com.ridetrack.app.safety.CrashReport
import java.time.ZoneOffset
import java.time.ZonedDateTime
import com.ridetrack.app.ride.GpsLostVideo
import com.ridetrack.app.ride.GpsLostVideo.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class SafetyTest {
    @Test
    fun `contacts round-trip and are capped at three`() {
        val list = listOf(EmergencyContact("Mum", "+91 98450 12345"), EmergencyContact("Ra\thul", "+91 99000\n54321"))
        val back = EmergencyContact.decode(EmergencyContact.encode(list))
        assertEquals(listOf(EmergencyContact("Mum", "+91 98450 12345"), EmergencyContact("Ra hul", "+91 99000 54321")), back)
        val four = (1..4).map { EmergencyContact("C$it", "$it") }
        assertEquals(3, EmergencyContact.decode(EmergencyContact.encode(four)).size)
        assertEquals(emptyList(), EmergencyContact.decode(null))
        // A number with no name is kept, named by its number.
        assertEquals(listOf(EmergencyContact("112", "112")), EmergencyContact.decode("\t112"))
    }

    private fun run(g: GpsLostVideo, from: Long, to: Long, lost: Boolean, ours: Boolean = g.filming, other: Boolean = false): List<Pair<Long, Action>> =
        (from..to step 1_000).map { t -> t to g.onTick(t, lost, ours, other) }.filter { it.second != Action.NONE }

    @Test
    fun `films after 10 s without GPS, until it's back for 5 s`() {
        val g = GpsLostVideo()
        assertEquals(emptyList(), run(g, 0, 5_000, lost = false))
        assertEquals(listOf(16_000L to Action.START), run(g, 6_000, 20_000, lost = true))
        assertEquals(26_000L, g.leadInMillis(26_000) + 6_000)
        assertEquals(listOf(35_000L to Action.STOP), run(g, 30_000, 40_000, lost = false, ours = true))
    }

    @Test
    fun `a short drop-out doesn't film, and a long one stops at 10 minutes`() {
        val g = GpsLostVideo()
        assertEquals(emptyList(), run(g, 0, 8_000, lost = true))
        run(g, 9_000, 20_000, lost = false)
        val actions = run(g, 21_000, 21_000 + 15 * 60_000L, lost = true, ours = true)
        assertEquals(listOf(Action.START, Action.STOP), actions.map { it.second })
        assertEquals(10 * 60_000L, actions[1].first - actions[0].first)
    }

    @Test
    fun `your own video comes first, and a stopped safety video isn't restarted`() {
        val g = GpsLostVideo()
        assertEquals(emptyList(), run(g, 0, 30_000, lost = true, other = true))
        assertEquals(listOf(31_000L to Action.START), run(g, 31_000, 31_000, lost = true))
        // Stopped by hand from the HUD: stays off while GPS is still lost.
        assertEquals(emptyList(), run(g, 32_000, 90_000, lost = true, ours = false))
        run(g, 91_000, 100_000, lost = false)
        assertEquals(listOf(Action.START), run(g, 101_000, 120_000, lost = true, ours = false).map { it.second })
    }

    private val report = CrashReport(
        timeMillis = ZonedDateTime.of(2026, 10, 1, 9, 21, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(),
        latitude = 12.971642, longitude = 77.594561, accuracyM = 8.4, speedBeforeMps = 15.0,
        bikeName = "Pulsar NS200", riderName = "Shivam", batteryPercent = 62,
        medical = MedicalInfo(bloodGroup = "B+", allergies = "penicillin"),
    )

    @Test
    fun `crash text has who, where, when and the medical basics`() {
        assertEquals(
            "Ride Track: possible crash. Shivam on Pulsar NS200 at 9:21 AM, 1 Oct. Last speed 54 km/h. " +
                "Location: https://maps.google.com/?q=12.97164,77.59456 (within 8 m). Battery 62%. Blood group B+, allergies: penicillin.",
            AlertMessage.crash(report, ZoneOffset.UTC),
        )
    }

    @Test
    fun `without GPS or details it still reads`() {
        val bare = report.copy(latitude = null, longitude = null, speedBeforeMps = null, riderName = "", batteryPercent = null, medical = MedicalInfo())
        assertEquals("Ride Track: possible crash. The rider on Pulsar NS200 at 9:21 AM, 1 Oct. Location unknown (no GPS).", AlertMessage.crash(bare, ZoneOffset.UTC))
        assertEquals(true, AlertMessage.crash(report, ZoneOffset.UTC, test = true).startsWith("Ride Track: (test) "))
        // Plain ASCII, so each SMS part holds 160 characters (a B− blood group included).
        val text = AlertMessage.crash(report.copy(medical = MedicalInfo(bloodGroup = "B−")), ZoneOffset.UTC)
        assertEquals(true, text.all { it.code < 128 }, text)
    }
}
