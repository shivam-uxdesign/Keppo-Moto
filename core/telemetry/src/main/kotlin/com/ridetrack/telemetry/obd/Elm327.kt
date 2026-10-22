package com.ridetrack.telemetry.obd

import java.util.Locale

/**
 * Talking to an ELM327 OBD adapter: the commands, and reading its answers. Pure; the Bluetooth
 * link (classic or BLE) only carries the text. Answers end with the ">" prompt; with echo,
 * spaces and headers off, a reading looks like "41 0C 1A F8" or "410C1AF8".
 */
object Elm327 {
    /** Set up the adapter: reset, no echo, no line feeds, no spaces, no headers, find the bike's protocol itself. */
    val INIT = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")

    /** The adapter's own supply voltage (the bike's battery): works on any bike, even one whose ECU says nothing. */
    const val VOLTAGE = "ATRV"
    /** Which protocol it found (e.g. "ISO 15765-4 (CAN 11/500)", "ISO 14230-4 (KWP FAST)"). */
    const val PROTOCOL = "ATDP"
    const val READ_CODES = "03"
    const val CLEAR_CODES = "04"

    /** Live readings from mode 01, with how to turn their bytes into values. */
    enum class Pid(val code: Int, val label: String, val unit: String, val bytes: Int, val value: (IntArray) -> Double) {
        LOAD(0x04, "Engine load", "%", 1, { it[0] * 100.0 / 255 }),
        COOLANT(0x05, "Coolant", "°C", 1, { it[0] - 40.0 }),
        MAP(0x0B, "Intake pressure", "kPa", 1, { it[0].toDouble() }),
        RPM(0x0C, "RPM", "rpm", 2, { (it[0] * 256 + it[1]) / 4.0 }),
        SPEED(0x0D, "Speed", "km/h", 1, { it[0].toDouble() }),
        TIMING(0x0E, "Ignition timing", "°", 1, { it[0] / 2.0 - 64 }),
        INTAKE_TEMP(0x0F, "Intake air", "°C", 1, { it[0] - 40.0 }),
        THROTTLE(0x11, "Throttle", "%", 1, { it[0] * 100.0 / 255 }),
        RUN_TIME(0x1F, "Engine running", "s", 2, { (it[0] * 256 + it[1]).toDouble() }),
        FUEL_LEVEL(0x2F, "Fuel level", "%", 1, { it[0] * 100.0 / 255 }),
        MODULE_VOLTAGE(0x42, "ECU voltage", "V", 2, { (it[0] * 256 + it[1]) / 1000.0 }),
        AMBIENT(0x46, "Air outside", "°C", 1, { it[0] - 40.0 }),
        OIL_TEMP(0x5C, "Oil temperature", "°C", 1, { it[0] - 40.0 }),
        ;

        val command: String get() = "01%02X".format(Locale.US, code)

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code }
        }
    }

    /** "Which readings do you have?": 0100 covers 01–20, 0120 covers 21–40, 0140 covers 41–60. */
    val SUPPORT_QUERIES = listOf(0x00, 0x20, 0x40)

    /** The answer, minus the prompt, echoes, "SEARCHING..." and blank lines; null when the adapter or bike said no. */
    fun clean(raw: String): String? {
        val lines = raw.replace(">", "").split('\r', '\n').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", ignoreCase = true) && !it.equals("OK", ignoreCase = true) }
        if (lines.isEmpty()) return null
        if (lines.any { l -> NO_ANSWER.any { l.contains(it, ignoreCase = true) } }) return null
        return lines.joinToString("\n")
    }

    /** Why there was no answer, in words; null when there was one. */
    fun problem(raw: String): String? {
        val t = raw.uppercase(Locale.US)
        return when {
            "UNABLE TO CONNECT" in t -> "The adapter can't reach the bike's ECU (key on? cable seated?)"
            "NO DATA" in t -> null // the bike doesn't have this reading: not a fault
            "CAN ERROR" in t || "BUS ERROR" in t || "BUS INIT" in t -> "The bike's diagnostic line didn't answer (${raw.trim()})"
            "STOPPED" in t -> "The adapter was interrupted"
            t.trim() == "?" -> "The adapter didn't understand the command"
            else -> null
        }
    }

    /** The adapter's voltage from "12.8V" (null if unreadable). */
    fun voltage(raw: String): Double? = clean(raw)?.let { Regex("""(\d{1,2}(?:\.\d{1,2})?)\s*V""", RegexOption.IGNORE_CASE).find(it) }?.groupValues?.get(1)?.toDoubleOrNull()

    /** The data bytes of a mode 01 answer for [pid] ("41 0C 1A F8" → [0x1A, 0xF8]); null if it isn't one. */
    fun bytes(raw: String, pid: Int): IntArray? {
        val text = clean(raw) ?: return null
        val prefix = "41%02X".format(Locale.US, pid)
        // With several ECUs answering, take the first line that is this reading.
        for (line in text.lines()) {
            val hex = line.replace(" ", "").uppercase(Locale.US)
            val at = hex.indexOf(prefix)
            if (at < 0) continue
            val data = hex.substring(at + prefix.length)
            val out = data.chunked(2).mapNotNull { it.takeIf { b -> b.length == 2 }?.toIntOrNull(16) }
            if (out.isNotEmpty()) return out.toIntArray()
        }
        return null
    }

    /** The value of [pid] from its answer, or null. */
    fun value(raw: String, pid: Pid): Double? = bytes(raw, pid.code)?.takeIf { it.size >= pid.bytes }?.let(pid.value)

    /** Which PIDs a "supported" answer (0100 / 0120 / 0140) says the bike has. */
    fun supported(raw: String, base: Int): Set<Int> {
        val b = bytes(raw, base)?.takeIf { it.size >= 4 } ?: return emptySet()
        val bits = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        return (1..32).filter { i -> bits and (1L shl (32 - i)) != 0L }.map { base + it }.toSet()
    }

    /**
     * Fault codes from a mode 03 answer ("43 01 33 00 00 00 00" → P0133). Each code is two bytes;
     * pairs of zeros are padding. With CAN, the byte after 43 is the count.
     */
    fun codes(raw: String): List<String> {
        val text = clean(raw) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (line in text.lines()) {
            var hex = line.replace(" ", "").uppercase(Locale.US)
            val at = hex.indexOf("43")
            if (at < 0) continue
            hex = hex.substring(at + 2)
            // CAN adds a count byte: an odd number of bytes left means it's there.
            if ((hex.length / 2) % 2 == 1) hex = hex.substring(2)
            hex.chunked(4).filter { it.length == 4 && it != "0000" }.forEach { out += dtc(it) }
        }
        return out.toList()
    }

    /** "0133" → "P0133": the first two bits pick the system (P, C, B, U). */
    fun dtc(fourHex: String): String {
        val first = fourHex[0].digitToInt(16)
        val system = "PCBU"[first shr 2]
        return "$system${first and 3}${fourHex.substring(1)}"
    }

    private val NO_ANSWER = listOf("NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS INIT", "STOPPED", "ERROR")
}
