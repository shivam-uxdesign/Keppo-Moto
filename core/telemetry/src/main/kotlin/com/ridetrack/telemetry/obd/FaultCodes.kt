package com.ridetrack.telemetry.obd

/**
 * Fault codes in plain words. Common generic codes are named; any other gets its area from its
 * digits (P01xx fuel and air, P03xx ignition, …). Manufacturer codes (P1xxx) say so. Pure.
 */
object FaultCodes {
    fun describe(code: String): String {
        KNOWN[code]?.let { return it }
        if (code.length != 5) return "Unknown code"
        val system = when (code[0]) { 'P' -> "Engine"; 'C' -> "Chassis (ABS, brakes)"; 'B' -> "Body"; else -> "Network" }
        if (code[1] != '0' && code[1] != '2') return "$system: a Bajaj-specific code (ask the service centre)"
        if (code[0] != 'P') return "$system fault"
        return when (code[2]) {
            '0', '1', '2' -> "Fuel and air metering"
            '3' -> "Ignition or misfire"
            '4' -> "Emissions control"
            '5' -> "Speed, idle control or switches"
            '6' -> "ECU or its wiring"
            '7', '8', '9' -> "Gearbox"
            else -> "Engine fault"
        }
    }

    private val KNOWN = mapOf(
        "P0105" to "Intake pressure sensor circuit",
        "P0106" to "Intake pressure sensor reading out of range",
        "P0110" to "Intake air temperature sensor circuit",
        "P0112" to "Intake air temperature sensor reads too cold (circuit low)",
        "P0113" to "Intake air temperature sensor reads too hot (circuit high)",
        "P0115" to "Engine temperature sensor circuit",
        "P0117" to "Engine temperature sensor circuit low",
        "P0118" to "Engine temperature sensor circuit high",
        "P0120" to "Throttle position sensor circuit",
        "P0122" to "Throttle position sensor circuit low",
        "P0123" to "Throttle position sensor circuit high",
        "P0130" to "Oxygen sensor circuit",
        "P0131" to "Oxygen sensor reads too lean (circuit low)",
        "P0132" to "Oxygen sensor reads too rich (circuit high)",
        "P0133" to "Oxygen sensor slow to respond",
        "P0134" to "Oxygen sensor not responding",
        "P0135" to "Oxygen sensor heater circuit",
        "P0171" to "Running lean (too much air or too little fuel)",
        "P0172" to "Running rich (too much fuel)",
        "P0201" to "Fuel injector circuit",
        "P0230" to "Fuel pump circuit",
        "P0300" to "Misfire",
        "P0301" to "Misfire in cylinder 1",
        "P0335" to "Crankshaft position sensor circuit",
        "P0351" to "Ignition coil circuit",
        "P0500" to "Vehicle speed sensor",
        "P0505" to "Idle control",
        "P0560" to "Battery voltage problem",
        "P0562" to "Battery voltage low",
        "P0563" to "Battery voltage high",
        "P0601" to "ECU memory error",
        "P0650" to "Engine warning light circuit",
        "P1693" to "Bajaj-specific: check at the service centre",
    )
}
