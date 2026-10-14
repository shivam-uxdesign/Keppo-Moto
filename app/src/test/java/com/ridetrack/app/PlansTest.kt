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

class PaywallTermsTest {
    private val yearly = com.ridetrack.app.ui.pro.PaywallPlan("yearly", "₹1,999", 1_999_000_000, yearly = true, trialDays = 7)
    private val monthly = com.ridetrack.app.ui.pro.PaywallPlan("monthly", "₹299", 299_000_000, yearly = false, trialDays = 7)

    @Test
    fun `the yearly saving is worked out from Play's prices`() {
        // 12 × ₹299 = ₹3,588; ₹1,999 is 44% less.
        assertEquals(44, com.ridetrack.app.ui.pro.saving(yearly, monthly))
        assertNull(com.ridetrack.app.ui.pro.saving(yearly, null))
    }

    @Test
    fun `the terms say the price, the trial and how to cancel`() {
        val t = com.ridetrack.app.ui.pro.terms(yearly)
        kotlin.test.assertTrue(t.startsWith("Free for 7 days, then ₹1,999 a year."))
        kotlin.test.assertTrue("cancel in Google Play" in t)
        kotlin.test.assertTrue(com.ridetrack.app.ui.pro.terms(monthly.copy(trialDays = null)).startsWith("₹299 a month."))
    }
}
