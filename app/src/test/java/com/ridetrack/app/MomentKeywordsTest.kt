package com.ridetrack.app

import com.ridetrack.app.moments.MomentKeyword
import com.ridetrack.app.moments.MomentKeywords
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.TelemetrySample
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MomentKeywordsTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun s(t: Long, kmh: Double, lean: Double? = null, g: Double? = null, heading: Double? = null, acc: Double? = 5.0, lat: Double? = 28.57, lon: Double? = 77.36) =
        TelemetrySample(t, lat, lon, kmh / 3.6, null, heading, g, null, lean, acc)

    /** Stopped, then 0 to 80 km/h in 8 s, a cruise, then braking 80 to 0 in 4 s. */
    private fun sprintAndStop(): List<TelemetrySample> {
        val out = ArrayList<TelemetrySample>()
        var t = 1_000_000L
        repeat(3) { out += s(t, 0.0); t += 1_000 }
        for (i in 1..8) { out += s(t, i * 10.0, g = 0.3 + i * 0.02); t += 1_000 }
        repeat(3) { out += s(t, 80.0 + (it % 2)); t += 1_000 }
        for (i in 1..4) { out += s(t, 80.0 - i * 20.0, g = -0.7); t += 1_000 }
        return out
    }

    @Test
    fun `a speed-up and a slow-down are found as runs`() {
        val runs = MomentKeywords.runs(sprintAndStop())
        assertEquals(2, runs.size)
        val up = runs.first { it.rising }
        assertEquals(0.0, up.fromKmh, 0.1)
        assertEquals(80.0, up.toKmh, 0.1)
        val down = runs.first { !it.rising }
        assertTrue(down.toKmh < 1)
        assertEquals(0.7, down.peakG!!, 0.01)
    }

    @Test
    fun `small wobbles and small changes don't make runs`() {
        val flat = (0..20).map { s(1_000L * it, 50.0 + (it % 3)) }
        assertTrue(MomentKeywords.runs(flat).isEmpty())
        val gentle = (0..10).map { s(1_000L * it, 40.0 + it) }
        assertTrue(MomentKeywords.runs(gentle).isEmpty())
    }

    @Test
    fun `a slow steady rise still counts from where it started`() {
        val rise = (0..30).map { s(1_000L * it, 30.0 + it * 1.5) }
        val run = MomentKeywords.runs(rise).single()
        assertEquals(31.5, run.fromKmh, 0.1)
        assertEquals(28_000L, run.endMillis - run.startMillis)
    }

    @Test
    fun `the keywords read as speeds and time, with the benchmarks`() {
        val samples = sprintAndStop()
        val keys = MomentKeywords.ride(samples, samples.first().timeMillis, samples.last().timeMillis, 15, emptySet(), "back", "Sector 70", miles = false, zone = ist)
        val texts = keys.map { it.text }
        assertTrue("0–80 km/h in 8.0 s" in texts, texts.toString())
        assertTrue(texts.any { it.startsWith("0–60 km/h in 6.") }, texts.toString())
        assertTrue(texts.any { it.startsWith("Braking 80–0 km/h in") }, texts.toString())
        assertTrue(texts.any { it.startsWith("Braking 60–0 km/h in") }, texts.toString())
        assertTrue("Top 81 km/h" in texts)
        assertTrue("Road" in texts && "Sector 70" in texts)
        assertEquals("0.5 g", keys.first { it.text.startsWith("0–80") }.detail)
    }

    @Test
    fun `lean counts from the rider's own setting, with its side`() {
        val samples = (0..10).map { s(1_000L * it, 50.0, lean = if (it == 5) -26.4 else 5.0) }
        val keys = MomentKeywords.ride(samples, 0, 10_000, 20, emptySet(), null, null, false, ist)
        assertTrue(keys.any { it.text == "Lean 26° left" && it.value == 26.4 })
        val stricter = MomentKeywords.ride(samples, 0, 10_000, 30, emptySet(), null, null, false, ist)
        assertTrue(stricter.none { it.text.startsWith("Lean") })
    }

    @Test
    fun `miles show mph and the mph sprints`() {
        val samples = sprintAndStop()
        val texts = MomentKeywords.ride(samples, samples.first().timeMillis, samples.last().timeMillis, 15, emptySet(), null, null, miles = true, zone = ist).map { it.text }
        assertTrue(texts.any { it.startsWith("0–50 mph in") }, texts.toString())
        assertTrue(texts.any { it.startsWith("0–30 mph in") }, texts.toString())
        assertTrue("Top 50 mph" in texts, texts.toString())
    }

    @Test
    fun `a stopped clip says so, and weak GPS says about`() {
        val still = (0..5).map { s(1_000L * it, 0.0) }
        assertTrue(MomentKeywords.ride(still, 0, 5_000, 15, setOf(RideEventType.ENGINE_REV), null, null, false, ist).map { it.text }.containsAll(listOf("Stopped", "Rev", "Selfie")))
        val weak = sprintAndStop().map { it.copy(gpsAccuracyM = 30.0) }
        assertTrue(MomentKeywords.ride(weak, 0, Long.MAX_VALUE, 15, emptySet(), null, null, false, ist).any { it.text.startsWith("about 0–80") })
    }

    @Test
    fun `a hairpin is a 150 degree turn within 20 s`() {
        val turn = (0..10).map { s(1_000L * it, 25.0, heading = (it * 18.0) % 360) }
        assertEquals("Right hairpin", MomentKeywords.hairpin(turn))
        val bend = (0..10).map { s(1_000L * it, 25.0, heading = 90.0 - it * 5.0) }
        assertNull(MomentKeywords.hairpin(bend))
    }

    @Test
    fun `sunrise, day and night where the clip was filmed`() {
        // Noida, 20 Oct 2026: about 06:25 sunrise.
        assertEquals("Sunrise", MomentKeywords.timeOfDay(28.57, 77.36, 1792458000000L, ist)) // 06:30 IST
        assertEquals("Day", MomentKeywords.timeOfDay(28.57, 77.36, 1792477800000L, ist)) // 12:00 IST
        assertEquals("Night", MomentKeywords.timeOfDay(28.57, 77.36, 1792512600000L, ist)) // 21:40 IST
    }

    @Test
    fun `removed keywords stay removed, own ones stay, and they round-trip`() {
        val first = listOf(MomentKeyword("Top 80 km/h", MomentKeyword.Kind.RIDE, 80.0, "km/h"), MomentKeyword("Road", MomentKeyword.Kind.RIDE))
        var list = MomentKeywords.remove(first, "road")
        list = MomentKeywords.add(list, "Tunnel run")
        list = MomentKeywords.merge(list, listOf(MomentKeyword("Road", MomentKeyword.Kind.RIDE), MomentKeyword("Top 82 km/h", MomentKeyword.Kind.RIDE, 82.0)))
        assertEquals(listOf("Top 82 km/h", "Tunnel run"), MomentKeywords.shown(list).map { it.text }.sorted())
        assertEquals(list, MomentKeywords.decode(MomentKeywords.encode(list)))
        assertTrue(MomentKeywords.decode("not json").isEmpty())
    }

    @Test
    fun `b-roll is a steady road shot with nobody talking`() {
        val steady = (0..8).map { s(1_000L * it, 60.0).copy(longitudinalG = 0.02, lateralG = 0.03) }
        assertTrue(MomentKeywords.bRoll(steady, "back", emptySet(), 8_000))
        assertFalse(MomentKeywords.bRoll(steady, null, emptySet(), 8_000))
        assertFalse(MomentKeywords.bRoll(steady, "back", setOf(RideEventType.VOICE), 8_000))
        assertFalse(MomentKeywords.bRoll(steady, "back", emptySet(), 3_000))
        val bumpy = steady.mapIndexed { i, x -> x.copy(lateralG = if (i % 2 == 0) 0.5 else -0.4) }
        assertFalse(MomentKeywords.bRoll(bumpy, "back", emptySet(), 8_000))
    }

    @Test
    fun `sound keywords from what was said and heard`() {
        val k = MomentKeywords.sound(listOf("tunnel", "bhai", "speed", "extra"), listOf("laugh", "scream", "unknown"), shouted = true).map { it.text }
        assertEquals(listOf("Talking", "tunnel", "bhai", "speed", "Laugh", "Scream"), k)
        assertEquals(listOf("Shouted"), MomentKeywords.sound(emptyList(), emptyList(), shouted = true).map { it.text })
    }

    @Test
    fun `a shout is near full volume and far above the usual level`() {
        val rate = 1_000
        val quiet = ShortArray(rate * 3) { (if (it % 2 == 0) 300 else -300).toShort() }
        assertFalse(MomentKeywords.loud(quiet, rate))
        val shout = quiet.copyOf().also { for (i in 1_000 until 1_200) it[i] = (if (i % 2 == 0) 30_000 else -30_000).toShort() }
        assertTrue(MomentKeywords.loud(shout, rate))
    }

    @Test
    fun `search matches keywords, words said and numbers`() {
        val list = listOf(MomentKeyword("Lean 32° right", MomentKeyword.Kind.RIDE, 32.0, "°"), MomentKeyword("Scream", MomentKeyword.Kind.SOUND))
        assertTrue(MomentKeywords.matches(list, null, "lean 30"))
        assertFalse(MomentKeywords.matches(list, null, "lean 35"))
        assertTrue(MomentKeywords.matches(list, null, "scream"))
        assertTrue(MomentKeywords.matches(list, "kya view hai", "view"))
        assertFalse(MomentKeywords.matches(list, null, "laugh"))
    }
}
