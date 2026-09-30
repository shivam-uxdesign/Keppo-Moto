package com.ridetrack.app

import com.ridetrack.app.ui.components.ChartWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChartWindowTest {
    private fun assertWindow(start: Float, end: Float, w: ChartWindow) {
        assertEquals(start, w.start, 1e-4f)
        assertEquals(end, w.end, 1e-4f)
    }

    @Test
    fun `zoom keeps the anchor point still and respects the minimum span`() {
        val w = ChartWindow.Full.zoom(4f, anchor = 0.5f, minSpan = 0.01f)
        assertWindow(0.375f, 0.625f, w)
        assertTrue(w.isZoomed)
        assertWindow(0.45f, 0.55f, ChartWindow.Full.zoom(100f, anchor = 0.5f, minSpan = 0.1f))
        assertFalse(w.zoom(0.01f, anchor = 0.5f, minSpan = 0.01f).isZoomed)
    }

    @Test
    fun `windows stay inside the ride`() {
        assertWindow(0.8f, 1f, ChartWindow.of(0.95f, 0.2f))
        assertWindow(0f, 0.2f, ChartWindow.around(0.02f, 0.2f))
        assertWindow(0f, 0.2f, ChartWindow.of(0.1f, 0.2f).pan(-0.5f))
    }

    @Test
    fun `following the playhead moves a zoomed window only when it leaves`() {
        val w = ChartWindow(0.2f, 0.3f)
        assertEquals(w, w.follow(0.25f))
        assertWindow(0.48f, 0.58f, w.follow(0.5f))
        assertEquals(ChartWindow.Full, ChartWindow.Full.follow(0.9f))
    }
}
