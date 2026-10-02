package com.ridetrack.app

import com.ridetrack.app.share.RouteImages
import com.ridetrack.app.share.SharePoint
import com.ridetrack.app.ui.common.momentsLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideCardTest {
    @Test
    fun `moments line says how many, and nothing for none`() {
        assertNull(momentsLine(0))
        assertEquals("1 moment captured", momentsLine(1))
        assertEquals("7 moments captured", momentsLine(7))
    }

    @Test
    fun `map bounds pad the route on every side`() {
        val route = listOf(SharePoint(30.0, 77.0, null), SharePoint(30.1, 77.2, null))
        val (n, e, s, w) = RouteImages.paddedBox(route).toList()
        assertTrue(n > 30.1 && s < 30.0 && e > 77.2 && w < 77.0)
        assertEquals(0.1 * 1.36, n - s, 1e-9)
        assertEquals(0.2 * 1.36, e - w, 1e-9)
    }

    @Test
    fun `a ride that barely moved still gets a few hundred metres of map`() {
        val route = listOf(SharePoint(30.0, 77.0, null), SharePoint(30.0, 77.0, null))
        val (n, e, s, w) = RouteImages.paddedBox(route).toList()
        assertTrue(n - s > 0.004 && e - w > 0.004)
    }
}
