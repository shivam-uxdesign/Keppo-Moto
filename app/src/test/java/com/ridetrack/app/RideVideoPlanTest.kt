package com.ridetrack.app

import com.ridetrack.app.share.RideVideoPlan
import com.ridetrack.app.share.VideoMoment
import com.ridetrack.app.share.VideoPart
import com.ridetrack.telemetry.model.TelemetrySample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RideVideoPlanTest {
    private val min = 60_000L
    private fun clip(atMin: Double, id: String = "c$atMin") = VideoMoment(id, (atMin * min).toLong(), true, (atMin * min).toLong() - 10_000, 20_000)
    private fun photo(atMin: Double) = VideoMoment("p$atMin", (atMin * min).toLong(), false)

    @Test
    fun `length is map time over speed plus the clips`() {
        // 7 min at 30x = 14 s of map, plus two 5 s clips and a 1 s photo.
        val p = RideVideoPlan.of(30 * min, 37 * min, 30, 5, listOf(clip(33.0), clip(35.0), photo(35.5)))
        assertEquals(14_000, p.mapMillis)
        assertEquals(14_000 + 5_000 + 5_000 + 1_000, p.totalMillis)
        assertEquals(2, p.clipCount)
        assertEquals(listOf("Map", "Hold", "Map", "Hold", "Map", "Hold", "Map"), p.parts.map { it.javaClass.simpleName })
    }

    @Test
    fun `moments outside the trim, or clips off, add nothing`() {
        val moments = listOf(clip(10.0), clip(33.0))
        assertEquals(1, RideVideoPlan.of(30 * min, 37 * min, 60, 10, moments).clipCount)
        val off = RideVideoPlan.of(30 * min, 37 * min, 60, 0, moments)
        assertEquals(0, off.clipCount)
        assertEquals(7_000, off.totalMillis)
    }

    @Test
    fun `a clip plays the part around its event, inside the file`() {
        val m = VideoMoment("x", 100_000, true, 90_000, 20_000) // event 10 s into a 20 s clip
        assertEquals(7_500L to 12_500L, RideVideoPlan.clipWindow(m, 5))
        assertEquals(0L to 20_000L, RideVideoPlan.clipWindow(m, RideVideoPlan.FULL_CLIP))
        val early = VideoMoment("y", 91_000, true, 90_000, 20_000) // event 1 s in
        assertEquals(0L to 10_000L, RideVideoPlan.clipWindow(early, 10))
    }

    @Test
    fun `ride time follows the map, and holds show the moment`() {
        val c = clip(33.0)
        val p = RideVideoPlan.of(30 * min, 37 * min, 60, 5, listOf(c))
        assertEquals(30 * min, p.rideTimeAt(0))
        // 3 min at 60x = 3 s of map before the clip.
        val (part, _) = p.at(3_500)!!
        assertTrue(part is VideoPart.Hold)
        assertEquals(c.videoStartMillis + 7_500 + 500, p.rideTimeAt(3_500))
        assertEquals(37 * min, p.rideTimeAt(p.totalMillis))
    }

    @Test
    fun `best stretch finds the fastest five minutes`() {
        val samples = (0 until 1_800).map { s ->
            TelemetrySample(s * 1_000L, null, null, if (s in 900 until 1_200) 25.0 else 8.0, null, null, null, null, null, null)
        }
        val r = assertNotNull(RideVideoPlan.bestStretch(samples, 5 * min))
        assertEquals(900_000L, r.first)
    }

    @Test
    fun `around moments wraps the busiest cluster`() {
        val moments = listOf(clip(5.0), clip(33.0), clip(35.0), clip(39.0), photo(20.0))
        val r = assertNotNull(RideVideoPlan.aroundMoments(moments, 7 * min, 0, 44 * min))
        assertTrue(33 * min in r && 39 * min in r)
    }
}
