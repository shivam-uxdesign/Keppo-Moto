package com.ridetrack.app

import com.ridetrack.app.hud.CameraIndicator
import com.ridetrack.app.moments.MomentMerge
import com.ridetrack.app.moments.MomentMerge.Part
import com.ridetrack.app.moments.MomentMerge.Span
import com.ridetrack.app.moments.MomentState
import com.ridetrack.app.moments.MomentStatus
import com.ridetrack.app.moments.PauseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MomentMergeTest {
    @Test
    fun `overlapping clips join, keeping shared footage once`() {
        val g = MomentMerge.plan(listOf(Span("a", 0, 20_000, null), Span("b", 15_000, 20_000, null))).single()
        assertEquals(listOf(Part("a", 0, 20_000), Part("b", 5_000, 20_000)), g)
    }

    @Test
    fun `clips under 5 s apart join, further apart they don't`() {
        val clips = listOf(Span("a", 0, 10_000, null), Span("b", 14_000, 10_000, null), Span("c", 30_000, 10_000, null))
        val groups = MomentMerge.plan(clips)
        assertEquals(listOf(listOf(Part("a", 0, 10_000), Part("b", 0, 10_000))), groups)
    }

    @Test
    fun `a chain of neighbours becomes one, and different cameras never join`() {
        val clips = listOf(
            Span("a", 0, 10_000, null), Span("b", 12_000, 10_000, null), Span("c", 25_000, 10_000, null),
            Span("road", 11_000, 10_000, "back"),
        )
        val groups = MomentMerge.plan(clips)
        assertEquals(listOf(listOf("a", "b", "c")), groups.map { g -> g.map { it.id } })
    }

    @Test
    fun `a clip wholly inside another is joined away`() {
        val g = MomentMerge.plan(listOf(Span("a", 0, 30_000, null), Span("b", 5_000, 10_000, null))).single()
        assertEquals(Part("b", 10_000, 10_000), g[1])
    }

    @Test
    fun `a video being filmed shows red even while the camera rests when stopped`() {
        val resting = MomentState(status = MomentStatus.PAUSED, reason = PauseReason.RIDE_PAUSED)
        assertEquals(CameraIndicator.OFF, CameraIndicator.from(resting))
        assertEquals(CameraIndicator.REC, CameraIndicator.from(resting, filming = true))
        assertTrue(CameraIndicator.from(MomentState(status = MomentStatus.ARMED), filming = true) == CameraIndicator.REC)
    }
}
