package com.ridetrack.app

import com.ridetrack.app.obd.ObdAdapter
import com.ridetrack.app.obd.ObdAdapters
import com.ridetrack.app.obd.ObdClient
import com.ridetrack.app.obd.ObdLink
import com.ridetrack.telemetry.obd.Elm327
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ObdClientTest {
    /** A pretend ELM327 on a K-line bike: answers like the real thing, prompts stripped as the links do. */
    private class FakeElm(private val ecu: Boolean = true, private val dropAfter: Int = Int.MAX_VALUE) : ObdLink {
        override val kind = "Bluetooth classic"
        val sent = ArrayList<String>()
        override suspend fun open() = Unit
        override fun close() = Unit
        override suspend fun send(command: String, timeoutMs: Long): String {
            sent += command
            if (sent.size > dropAfter) throw IOException("link closed")
            return when (command) {
                "ATZ" -> "\r\rELM327 v1.5\r\r"
                "ATRV" -> "14.6V\r"
                "ATDP" -> "AUTO, ISO 14230-4 (KWP FAST)\r"
                "0100" -> if (ecu) "SEARCHING...\r41 00 BE 3E B8 11\r" else "SEARCHING...\rUNABLE TO CONNECT\r"
                "0120" -> "41 20 80 00 00 01\r"
                "0140" -> "41 40 40 00 00 00\r"
                "010C" -> "41 0C 1A F8\r"
                "010D" -> "41 0D 40\r"
                "0105" -> "41 05 82\r"
                "0142" -> "41 42 39 08\r"
                "03" -> "43 01 33 00 00 00 00\r"
                "04" -> "44\r"
                else -> if (command.startsWith("AT")) "OK\r" else "NO DATA\r"
            }
        }
    }

    @Test
    fun `sets the adapter up, finds the protocol and what the bike reads`() = runBlocking {
        val link = FakeElm()
        val log = ArrayList<String>()
        val c = ObdClient(link) { log += it }
        assertTrue(c.connect())
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100", "0120", "0140", "ATDP"), link.sent)
        assertTrue(c.ecu)
        assertEquals("ELM327 v1.5", c.adapter)
        assertEquals("ISO 14230-4 (KWP FAST)", c.protocol)
        assertTrue(0x0C in c.supported && 0x42 in c.supported && 0x21 in c.supported)
        assertEquals(1726.0, c.read(Elm327.Pid.RPM))
        assertEquals(64.0, c.read(Elm327.Pid.SPEED))
        assertEquals(90.0, c.read(Elm327.Pid.COOLANT))
        assertEquals(14.6, c.voltage())
        // Not supported: not asked.
        val before = link.sent.size
        assertNull(c.read(Elm327.Pid.FUEL_LEVEL))
        assertEquals(before, link.sent.size)
        assertEquals(listOf("P0133"), c.codes())
        assertTrue(c.clearCodes())
        assertTrue(log.any { it.contains("readings available") })
    }

    @Test
    fun `a bike that doesn't answer still gives the voltage`() = runBlocking {
        val c = ObdClient(FakeElm(ecu = false)) {}
        assertTrue(c.connect())
        assertFalse(c.ecu)
        assertTrue(c.problem!!.contains("ECU"))
        assertEquals(14.6, c.voltage())
        assertNull(c.read(Elm327.Pid.RPM))
        assertNull(c.codes())
    }

    @Test(expected = IOException::class)
    fun `a dropped link throws so the manager reconnects`() = runBlocking<Unit> {
        val c = ObdClient(FakeElm(dropAfter = 10)) {}
        c.connect()
        repeat(5) { c.read(Elm327.Pid.RPM) }
    }

    /** A K-line bike on ECU address 11 that the adapter's own search misses. */
    private class KLineBike : ObdLink {
        override val kind = "Bluetooth classic"
        val sent = ArrayList<String>()
        private var proto = "0"
        private var header = ""
        override suspend fun open() = Unit
        override fun close() = Unit
        override suspend fun send(command: String, timeoutMs: Long): String {
            sent += command
            when {
                command.startsWith("ATSP") -> proto = command.removePrefix("ATSP")
                command.startsWith("ATSH") -> header = command.removePrefix("ATSH")
            }
            return when (command) {
                "ATZ" -> "ELM327 v2.1\r"
                "ATDP" -> "ISO 14230-4 (KWP FAST)\r"
                "0100" -> if (proto == "5" && header == "8111F1") "BUS INIT: ...OK\r41 00 98 18 80 11\r" else if (proto == "5") "BUS INIT: ...OK\rNO DATA\r" else "UNABLE TO CONNECT\r"
                "010C" -> if (proto == "5" && header == "8111F1") "41 0C 0F A0\r" else "NO DATA\r"
                else -> "OK\r"
            }
        }
    }

    @Test
    fun `trying every protocol finds a K-line bike on its own address, and next time goes straight there`() = runBlocking {
        val bike = KLineBike()
        val log = ArrayList<String>()
        val c = ObdClient(bike) { log += it }
        assertTrue(c.connect())
        assertFalse(c.ecu)
        val steps = ArrayList<String>()
        val found = c.search { _, _, label -> steps += label }
        assertEquals("K-line fast start, ECU 11", found?.label)
        assertTrue(c.ecu)
        assertEquals(1000.0, c.read(Elm327.Pid.RPM))
        assertTrue(steps.first().startsWith("CAN"))
        assertTrue(log.any { it.contains("try K-line fast start, any ECU: BUS INIT: ...OK NO DATA") })
        // Remembered: the next connection uses it at once.
        val next = KLineBike()
        val c2 = ObdClient(next) {}
        assertTrue(c2.connect(found!!.setup))
        assertTrue(c2.ecu)
        assertEquals(listOf("ATPC", "ATSP5", "ATSH8111F1", "0100"), next.sent.drop(6).take(4))
    }

    @Test
    fun `adapters and learned gears survive saving`() {
        val list = listOf(ObdAdapter("bike-1", "00:1D:A5:68:98:8B", "OBDII | v1.5", listOf(150.0, 105.0, 80.0), listOf("ATPC", "ATSP5", "ATSH8111F1")), ObdAdapter("b2", "AA:BB", "V-LINK"))
        assertEquals(list, ObdAdapters.decode(ObdAdapters.encode(list)))
        assertEquals(listOf(list[1]), ObdAdapters.with(list, "bike-1", null))
    }
}
