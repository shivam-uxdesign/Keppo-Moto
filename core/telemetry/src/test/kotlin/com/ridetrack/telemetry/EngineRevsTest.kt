package com.ridetrack.telemetry

import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.moments.EngineRevs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineRevsTest {
    private fun run(levels: List<Float>, chunk: Long = 50): List<Long> {
        val d = EngineRevs()
        return levels.mapIndexedNotNull { i, db -> d.onLevel(i * chunk, db, chunk)?.also { assertEquals(RideEventType.ENGINE_REV, it.type) }?.timeMillis }
    }

    @Test
    fun `a rev well above the riding drone is a moment`() {
        val drone = List(200) { -30f }
        val rev = List(10) { -14f }
        assertEquals(listOf(10_000L), run(drone + rev + drone))
    }

    @Test
    fun `a click too short, or the drone rising slowly, is not`() {
        val drone = List(200) { -30f }
        assertTrue(run(drone + listOf(-10f, -10f) + drone).isEmpty())
        // Speeding up: the engine gets louder over many seconds.
        assertTrue(run(List(600) { i -> -30f + i * 0.03f }).isEmpty())
    }

    @Test
    fun `one long blat is one moment, and they rest between`() {
        val drone = List(200) { -30f }
        val blat = List(60) { -12f }
        assertEquals(1, run(drone + blat + drone).size)
        // Two revs 5 s apart: the second is inside the rest.
        val two = drone + List(10) { -14f } + List(100) { -30f } + List(10) { -14f } + drone
        assertEquals(1, run(two).size)
    }
}
