package com.ridetrack.app

import com.ridetrack.app.ride.TriggeredVideo
import com.ridetrack.app.ride.TriggeredVideo.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TriggeredVideoTest {
    @Test
    fun `keeps filming while events keep coming, stops after the last one`() {
        val v = TriggeredVideo()
        v.start(now = 115_000, holdUntil = 125_000)
        assertEquals(Action.NONE, v.onTick(120_000, running = true))
        v.extend(130_000)
        v.extend(128_000) // never shortens
        assertEquals(Action.NONE, v.onTick(129_000, running = true))
        assertEquals(Action.STOP, v.onTick(130_001, running = true))
        assertFalse(v.filming)
    }

    @Test
    fun `capped at 10 minutes`() {
        val v = TriggeredVideo()
        v.start(0, holdUntil = 10)
        v.extend(Long.MAX_VALUE)
        assertEquals(Action.NONE, v.onTick(599_999, running = true))
        assertEquals(Action.STOP, v.onTick(600_000, running = true))
    }

    @Test
    fun `stopped by hand is left alone, never started falls back`() {
        val byHand = TriggeredVideo()
        byHand.start(0, holdUntil = 60_000)
        byHand.onTick(2_000, running = true)
        assertEquals(Action.NONE, byHand.onTick(9_000, running = false))
        assertFalse(byHand.filming)

        val never = TriggeredVideo()
        never.start(0, holdUntil = 60_000)
        assertEquals(Action.NONE, never.onTick(5_000, running = false))
        assertEquals(Action.FAILED, never.onTick(9_000, running = false))
    }
}
