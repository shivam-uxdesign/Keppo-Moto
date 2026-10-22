package com.ridetrack.app.obd

import com.ridetrack.telemetry.obd.Elm327

/**
 * One session with an ELM327 over [link]: set it up, find the bike's protocol and what it can
 * read, then answer readings one at a time. [log] gets every exchange worth keeping (tester
 * export and the test report).
 */
class ObdClient(private val link: ObdLink, private val log: (String) -> Unit) {
    /** The adapter's own name and version ("ELM327 v1.5"). */
    var adapter: String? = null
        private set
    /** The bike's protocol as the adapter found it, or null before it did. */
    var protocol: String? = null
        private set
    /** The mode-01 readings the bike says it has. */
    var supported: Set<Int> = emptySet()
        private set
    /** The bike's ECU answered (voltage works without it). */
    var ecu = false
        private set
    /** Why the ECU didn't answer, in words. */
    var problem: String? = null
        private set

    /** Opens the link and sets everything up. False when the adapter itself can't be reached. */
    suspend fun connect(): Boolean {
        runCatching { link.open() }.onFailure {
            log("link (${link.kind}): couldn't connect: ${it.message}")
            return false
        }
        log("link: connected over ${link.kind}")
        Elm327.INIT.forEach { cmd ->
            val r = runCatching { link.send(cmd, if (cmd == "ATZ") 4_000 else 2_000) }.getOrElse { return false.also { _ -> log("$cmd: link lost") } }
            if (cmd == "ATZ") adapter = Elm327.clean(r)?.lines()?.lastOrNull { it.contains("ELM", true) || it.isNotBlank() }
        }
        log("adapter: ${adapter ?: "no name"}")
        // The first question makes the adapter search the protocols (K-line takes several seconds).
        val first = send("0100", 15_000) ?: return true
        ecu = Elm327.clean(first) != null
        problem = Elm327.problem(first)
        if (!ecu) {
            log("ECU: no answer (${first.trim().replace('\r', ' ')}): ${problem ?: "the bike may not support standard OBD"}")
            return true
        }
        supported = Elm327.supported(first, 0x00)
        if (0x20 in supported) send("0120")?.let { supported = supported + Elm327.supported(it, 0x20) }
        if (0x40 in supported) send("0140")?.let { supported = supported + Elm327.supported(it, 0x40) }
        protocol = send(Elm327.PROTOCOL)?.let { Elm327.clean(it) }?.removePrefix("AUTO, ")
        log("ECU: protocol ${protocol ?: "?"} · supports ${supported.sorted().joinToString(" ") { "%02X".format(it) }}")
        log("readings available: ${Elm327.Pid.entries.filter { it.code in supported }.joinToString(", ") { it.label }.ifEmpty { "none of the usual" }}")
        return true
    }

    suspend fun voltage(): Double? = send(Elm327.VOLTAGE, 1_500)?.let(Elm327::voltage)

    /** [pid]'s value, or null (not supported, no data, or no answer). */
    suspend fun read(pid: Elm327.Pid): Double? {
        if (!ecu || pid.code !in supported) return null
        return send(pid.command, 1_500)?.let { Elm327.value(it, pid) }
    }

    suspend fun codes(): List<String>? {
        if (!ecu) return null
        val r = send(Elm327.READ_CODES, 5_000) ?: return null
        return Elm327.codes(r).also { log("fault codes: ${it.ifEmpty { listOf("none") }.joinToString(", ")}") }
    }

    /** Clears the fault codes and the warning light. The only command that changes anything on the bike. */
    suspend fun clearCodes(): Boolean {
        if (!ecu) return false
        val r = send(Elm327.CLEAR_CODES, 5_000) ?: return false
        log("clear codes: ${r.trim()}")
        return r.contains("44")
    }

    fun close() = link.close()

    val kind: String get() = link.kind

    /** Throws when the link is lost (the caller reconnects); null for a timeout. */
    private suspend fun send(cmd: String, timeoutMs: Long = 2_000): String? {
        val r = link.send(cmd, timeoutMs)
        return r.ifBlank { null }
    }
}
