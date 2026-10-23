package com.ridetrack.app.obd

import java.net.URLDecoder
import java.net.URLEncoder

/** The OBD adapter paired with a bike, and the gears learned on that bike (RPM per km/h, 1st first). */
data class ObdAdapter(
    val bikeId: String,
    val address: String,
    val name: String,
    val gears: List<Double> = emptyList(),
    /** How to reach this bike's ECU when the adapter's own search can't (found by "Try every protocol"). */
    val setup: List<String> = emptyList(),
)

/** Saving adapters as text: one per line, "bike|address|name|gear ratios". Pure. */
object ObdAdapters {
    fun encode(list: List<ObdAdapter>): String = list.joinToString("\n") { a ->
        listOf(a.bikeId, a.address, a.name, a.gears.joinToString(",") { "%.2f".format(java.util.Locale.US, it) }, a.setup.joinToString(";")).joinToString("|") { e(it) }
    }

    fun decode(value: String?): List<ObdAdapter> = value.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        runCatching {
            val f = line.split("|").map { d(it) }
            ObdAdapter(f[0], f[1], f[2], f.getOrNull(3).orEmpty().split(",").mapNotNull { it.toDoubleOrNull() }, f.getOrNull(4).orEmpty().split(";").filter { it.isNotBlank() })
        }.getOrNull()
    }

    /** [list] with [bikeId]'s adapter set to [adapter] (null = forget it). */
    fun with(list: List<ObdAdapter>, bikeId: String, adapter: ObdAdapter?): List<ObdAdapter> =
        list.filterNot { it.bikeId == bikeId } + listOfNotNull(adapter)

    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun d(s: String) = URLDecoder.decode(s, "UTF-8")
}
