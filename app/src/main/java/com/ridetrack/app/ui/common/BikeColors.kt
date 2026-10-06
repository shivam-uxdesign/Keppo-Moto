package com.ridetrack.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ridetrack.telemetry.model.Bike

/**
 * Each bike's route colour: the rider's pick, or by its place in the garage (oldest first),
 * so the first bike keeps the app's teal. Chosen to stand apart on the dark map.
 */
object BikeColors {
    val PALETTE: List<Color> = listOf(
        Color(0xFF69C8CB), // teal
        Color(0xFFF59E0B), // amber
        Color(0xFFF472B6), // pink
        Color(0xFFA78BFA), // violet
        Color(0xFFA3E635), // lime
        Color(0xFFFB923C), // orange
        Color(0xFF38BDF8), // sky
        Color(0xFFF87171), // red
    )

    fun of(bike: Bike, garage: List<Bike>): Color = bike.routeColor?.let { Color(it) } ?: default(bike, garage)

    /** The colour a bike gets when the rider hasn't picked one. */
    fun default(bike: Bike, garage: List<Bike>): Color {
        val order = garage.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))
        val i = order.indexOfFirst { it.id == bike.id }.coerceAtLeast(0)
        return PALETTE[i % PALETTE.size]
    }

    /** Bike id → colour, for a whole garage. */
    fun all(garage: List<Bike>): Map<String, Color> = garage.associate { it.id to of(it, garage) }

    fun argb(c: Color): Int = c.toArgb()
}
