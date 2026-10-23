package com.ridetrack.telemetry

import com.ridetrack.telemetry.obd.Elm327
import com.ridetrack.telemetry.obd.EngineWatch
import com.ridetrack.telemetry.obd.FaultCodes
import com.ridetrack.telemetry.obd.GearLearner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObdTest {
    @Test
    fun `the adapter's answers become values, with or without spaces, echoes and prompts`() {
        assertEquals(1726.0, Elm327.value("41 0C 1A F8 \r\r>", Elm327.Pid.RPM))
        assertEquals(1726.0, Elm327.value("SEARCHING...\r410C1AF8\r\r>", Elm327.Pid.RPM))
        assertEquals(64.0, Elm327.value("41 0D 40", Elm327.Pid.SPEED))
        assertEquals(90.0, Elm327.value("41 05 82", Elm327.Pid.COOLANT))
        assertEquals(100.0, Elm327.value("41 11 FF", Elm327.Pid.THROTTLE))
        assertEquals(14.6, Elm327.value("41 42 39 08", Elm327.Pid.MODULE_VOLTAGE)!!, 0.01)
        assertNull(Elm327.value("NO DATA\r>", Elm327.Pid.RPM))
        // K-line: the first answer starts with the wake-up; it's still the answer.
        assertEquals(1726.0, Elm327.value("BUS INIT: ...OK\r41 0C 1A F8\r>", Elm327.Pid.RPM))
        assertNull(Elm327.value("BUS INIT: ...ERROR\r>", Elm327.Pid.RPM))
        assertNull(Elm327.value("41 0C 1A", Elm327.Pid.RPM))
    }

    @Test
    fun `voltage reads as the adapter shows it`() {
        assertEquals(12.8, Elm327.voltage("12.8V\r\r>"))
        assertEquals(14.6, Elm327.voltage("14.6V"))
        assertNull(Elm327.voltage("?"))
    }

    @Test
    fun `supported readings come from the bitmask`() {
        // 0100 → BE 3E B8 11: 01,03,04,05,06,07,0B,0C,0D,0E,0F,10,11,13,15,1C,20
        val s = Elm327.supported("41 00 BE 3E B8 11", 0x00)
        assertTrue(0x0C in s && 0x0D in s && 0x05 in s && 0x11 in s, "$s")
        assertTrue(0x02 !in s && 0x0A !in s, "$s")
        assertEquals(setOf(0x42, 0x46), Elm327.supported("41 40 44 00 00 00", 0x40))
    }

    @Test
    fun `fault codes from K-line and CAN answers`() {
        assertEquals(listOf("P0133"), Elm327.codes("43 01 33 00 00 00 00\r>"))
        assertEquals(listOf("P0133", "C0035"), Elm327.codes("43 02 01 33 40 35"))
        assertEquals(emptyList(), Elm327.codes("43 00 00 00 00 00 00"))
        assertEquals(emptyList(), Elm327.codes("NO DATA"))
        assertEquals("U0100", Elm327.dtc("C100"))
        assertEquals("Oxygen sensor slow to respond", FaultCodes.describe("P0133"))
        assertEquals("Ignition or misfire", FaultCodes.describe("P0399"))
        assertTrue(FaultCodes.describe("P1A00").contains("Bajaj"))
    }

    @Test
    fun `problems are said in words, and a missing reading is not one`() {
        assertTrue(Elm327.problem("UNABLE TO CONNECT\r>")!!.contains("ECU"))
        assertNull(Elm327.problem("NO DATA"))
        assertNull(Elm327.problem("41 0C 1A F8"))
    }

    @Test
    fun `the engine runs once the voltage holds above 13 point 2 V, as on the rider Pulsar`() {
        val w = EngineWatch()
        assertEquals(EngineWatch.State.KEY_ON, w.onLink(true))
        assertEquals(EngineWatch.State.KEY_ON, w.onReading(0, 12.8, null))
        // Cranking dips, then the alternator charges.
        assertEquals(EngineWatch.State.KEY_ON, w.onReading(500, 10.9, null))
        assertEquals(EngineWatch.State.KEY_ON, w.onReading(1_000, 14.6, null))
        assertEquals(EngineWatch.State.KEY_ON, w.onReading(3_000, 14.5, null))
        assertEquals(EngineWatch.State.RUNNING, w.onReading(4_100, 14.6, null))
        // Idle wobble doesn't stop it; key off drops the link.
        assertEquals(EngineWatch.State.RUNNING, w.onReading(5_000, 13.0, null))
        assertEquals(EngineWatch.State.OFF, w.onLink(false))
    }

    @Test
    fun `RPM says it at once`() {
        val w = EngineWatch()
        w.onLink(true)
        assertEquals(EngineWatch.State.RUNNING, w.onReading(0, 12.6, 1_350.0))
        assertEquals(EngineWatch.State.KEY_ON, w.onReading(1_000, 12.6, 0.0))
    }

    @Test
    fun `gears are learned from steady riding, then each reading finds its gear`() {
        // A 6-speed: rpm per km/h in each gear.
        val truth = listOf(150.0, 105.0, 80.0, 65.0, 56.0, 50.0)
        val g = GearLearner()
        val random = java.util.Random(3)
        repeat(30) { lap ->
            truth.forEachIndexed { i, r ->
                val speed = 20.0 + i * 12 + random.nextDouble() * 5
                repeat(12) { g.onReading(r * speed * (1 + random.nextGaussian() * 0.005), speed) }
            }
        }
        assertEquals(6, g.ratios.size, "${g.ratios}")
        g.ratios.zip(truth).forEach { (got, want) -> assertTrue(kotlin.math.abs(got - want) / want < 0.03, "${g.ratios}") }
        assertEquals(3, g.onReading(80.0 * 50, 50.0))
        assertEquals(0, g.onReading(2_500.0, 0.0))
        // A ratio between gears (clutch slipping) isn't a gear.
        assertNull(g.onReading(90.0 * 40, 40.0))
        // The next ride knows them at once.
        assertEquals(6, GearLearner(g.ratios).onReading(50.0 * 90, 90.0))
    }
}
