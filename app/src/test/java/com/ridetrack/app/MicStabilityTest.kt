package com.ridetrack.app

import com.ridetrack.app.moments.MicStability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicStabilityTest {
    @Test
    fun `a mic back after one drop is used after 10 s`() {
        val m = MicStability()
        m.onSeen(0, true)
        m.onSeen(5_000, false)
        m.onSeen(8_000, true)
        assertFalse(m.stable(17_000))
        assertTrue(m.stable(18_000))
    }

    @Test
    fun `a flapping mic needs a minute`() {
        // Monday's ride: the receiver dropped at 0 s, 11 s and 112 s.
        val m = MicStability()
        m.onSeen(0, true)
        m.onSeen(1_000, false)
        m.onSeen(2_000, true)
        m.onSeen(11_000, false)
        m.onSeen(64_000, true)
        m.onSeen(112_000, false)
        m.onSeen(113_000, true)
        assertTrue(m.flapping)
        assertFalse(m.stable(123_000))
        assertTrue(m.stable(173_000))
    }

    @Test
    fun `old drops are forgotten`() {
        val m = MicStability()
        m.onSeen(0, true)
        m.onSeen(1_000, false)
        m.onSeen(2_000, true)
        m.onSeen(3_000, false)
        m.onSeen(4_000, true)
        m.onSeen(5_000, false)
        m.onSeen(200_000, true)
        assertFalse(m.flapping)
        assertTrue(m.stable(210_000))
    }

    @Test
    fun `reconnect switches at once, but only to a connected mic`() {
        val m = MicStability()
        m.onSeen(0, false)
        m.force()
        assertFalse(m.stable(0))
        m.onSeen(1_000, true)
        m.force()
        assertTrue(m.stable(1_000))
    }
}
