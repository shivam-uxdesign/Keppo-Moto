package com.ridetrack.app

import com.ridetrack.app.studio.PostDay
import com.ridetrack.app.studio.ReelJson
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.StudioOptions
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.Vibe
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class PostDayTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 13, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val day = 86_400_000L

    private fun reel(id: String, agoDays: Double, posted: Boolean = false, seriesId: String? = null, ep: Int = 1, postedAt: Long? = null) = ReelProject(
        id, "r", now - (agoDays * day).toLong(), 0, id, "S", ep, "", "", null, StudioOptions(), null, null, StudioPlan(emptyList(), Vibe.CHILL), emptyList(), emptyList(), 20_000,
        posted = posted, seriesId = seriesId, postedAt = postedAt,
    )

    @Test
    fun `ready videos line up oldest first, skipped ones last, episodes in order`() {
        val reels = listOf(
            reel("new", 0.5),
            reel("old", 3.0),
            reel("shared", 2.0, posted = true),
            reel("stale", 40.0),
            reel("ep2", 2.5, seriesId = "s", ep = 2), // made before Ep 1 (made again later)
            reel("ep1", 1.0, seriesId = "s", ep = 1),
            reel("skip", 4.0),
        )
        val q = PostDay.queue(reels, mapOf("skip" to now), now)
        assertEquals(listOf("old", "ep1", "ep2", "new", "skip"), q.map { it.id })
    }

    @Test
    fun `the usual posting hour is the middle of the last shares, 7 PM before any`() {
        fun at(h: Int, d: Int) = ZonedDateTime.of(2026, 10, d, h, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(19, PostDay.usualHour(emptyList(), zone))
        assertEquals(21, PostDay.usualHour(listOf(at(21, 1), at(20, 2), at(22, 3), at(21, 4), at(9, 5)), zone))
        // Today at 9 PM is still ahead at noon; at 10 PM it's tomorrow's.
        assertEquals(ZonedDateTime.of(2026, 10, 13, 21, 0, 0, 0, zone).toInstant().toEpochMilli(), PostDay.nextAt(now, 21, zone))
        val late = ZonedDateTime.of(2026, 10, 13, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 10, 14, 21, 0, 0, 0, zone).toInstant().toEpochMilli(), PostDay.nextAt(late, 21, zone))
    }

    @Test
    fun `when a reel was shared is kept`() {
        val p = reel("x", 1.0, posted = true, postedAt = now)
        assertEquals(now, ReelJson.read(ReelJson.write(p))?.postedAt)
    }
}
