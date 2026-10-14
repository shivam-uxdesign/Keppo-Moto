package com.ridetrack.app

import com.ridetrack.app.pro.Plans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlansTest {
    @Test
    fun `a free trial's length is read from Play's period`() {
        assertEquals(7, Plans.days("P7D"))
        assertEquals(7, Plans.days("P1W"))
        assertEquals(30, Plans.days("P1M"))
        assertEquals(365, Plans.days("P1Y"))
        assertNull(Plans.days("1 week"))
    }
}
