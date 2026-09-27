package com.ridetrack.telemetry

import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.processing.EventContext
import com.ridetrack.telemetry.processing.EventDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventDetectorTest {
    private fun ctx(ms: Long, speed: Double = 15.0) = EventContext(ms, 12.0, 77.0, speed)

    @Test
    fun `sustained deceleration is one hard brake with peak value`() {
        val d = EventDetector()
        val events = buildList {
            for (i in 0 until 50) {
                val g = when (i) {
                    in 10..40 -> -0.6 - (if (i == 25) 0.2 else 0.0)
                    else -> 0.0
                }
                addAll(d.onDynamics(ctx(i * 20L), g, 0.0))
            }
        }
        assertEquals(1, events.size)
        assertEquals(RideEventType.HARD_BRAKE, events[0].type)
        assertEquals(-0.8, events[0].value!!, 1e-9)
        assertEquals(200L, events[0].timeMillis) // context from when braking began
    }

    @Test
    fun `ordinary city braking is not a hard brake`() {
        val d = EventDetector()
        val events = buildList {
            for (i in 0 until 60) addAll(d.onDynamics(ctx(i * 20L), if (i in 10..50) -0.4 else 0.0, 0.0))
        }
        assertTrue(events.none { it.type == RideEventType.HARD_BRAKE })
    }

    @Test
    fun `short spike is not an event`() {
        val d = EventDetector()
        val events = buildList {
            for (i in 0 until 30) addAll(d.onDynamics(ctx(i * 20L), if (i in 5..8) -0.9 else 0.0, 0.0))
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `significant lean keeps its direction`() {
        val d = EventDetector()
        val events = buildList {
            for (i in 0 until 100) addAll(d.onDynamics(ctx(i * 20L), 0.0, if (i in 10..80) -32.0 else 0.0))
        }
        assertEquals(RideEventType.SIGNIFICANT_LEAN, events.single().type)
        assertEquals(-32.0, events.single().value!!, 1e-9)
    }

    @Test
    fun `heading sweep is a turn in the right direction`() {
        val d = EventDetector()
        val headings = listOf(0.0, 0.0, 15.0, 35.0, 60.0, 85.0, 90.0, 90.0, 90.0, 90.0, 90.0)
        val events = headings.flatMapIndexed { i, h -> d.onHeading(ctx(i * 1000L), h) }
        assertEquals(listOf(RideEventType.RIGHT_TURN), events.map { it.type })
        assertEquals(90.0, events.single().value!!, 1e-9)

        val left = listOf(90.0, 70.0, 40.0, 10.0, 0.0, 0.0, 0.0, 0.0)
            .flatMapIndexed { i, h -> d.onHeading(ctx(20_000 + i * 1000L), h) } + d.flush()
        assertEquals(listOf(RideEventType.LEFT_TURN), left.map { it.type })
    }

    @Test
    fun `no turns detected at walking pace`() {
        val d = EventDetector()
        val events = listOf(0.0, 45.0, 90.0, 135.0, 180.0)
            .flatMapIndexed { i, h -> d.onHeading(ctx(i * 1000L, speed = 1.0), h) } + d.flush()
        assertTrue(events.isEmpty())
    }
}
