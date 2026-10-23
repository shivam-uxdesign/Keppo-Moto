package com.ridetrack.app

import com.ridetrack.app.moments.MicRouting
import com.ridetrack.app.moments.MicType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicRoutingTest {
    @Test
    fun `the phone mic recording while the receiver is still there is moved back`() {
        val r = MicRouting()
        assertTrue(r.shouldReopen(MicType.USB, MicType.PHONE, askedConnected = true, nowMillis = 0))
        assertEquals(1, r.reopens)
    }

    @Test
    fun `a Bluetooth headset taking over from the receiver is moved back too`() {
        val r = MicRouting()
        assertTrue(r.shouldReopen(MicType.USB, MicType.BLUETOOTH, askedConnected = true, nowMillis = 0))
    }

    @Test
    fun `nothing to do when the right mic records, the receiver is gone, or the phone mic was asked for`() {
        val r = MicRouting()
        assertFalse(r.shouldReopen(MicType.USB, MicType.USB, true, 0))
        assertFalse(r.shouldReopen(MicType.USB, null, true, 0))
        assertFalse(r.shouldReopen(MicType.USB, MicType.PHONE, askedConnected = false, nowMillis = 0))
        assertFalse(r.shouldReopen(MicType.PHONE, MicType.PHONE, true, 0))
        assertEquals(0, r.reopens)
    }

    @Test
    fun `it is moved back at most once every 30 s`() {
        val r = MicRouting()
        assertTrue(r.shouldReopen(MicType.USB, MicType.PHONE, true, 1_000))
        assertFalse(r.shouldReopen(MicType.USB, MicType.PHONE, true, 20_000))
        assertTrue(r.shouldReopen(MicType.USB, MicType.PHONE, true, 31_000))
        assertEquals(2, r.reopens)
    }
}
