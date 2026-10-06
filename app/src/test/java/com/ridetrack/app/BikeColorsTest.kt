package com.ridetrack.app

import androidx.compose.ui.graphics.Color
import com.ridetrack.app.ui.common.BikeColors
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.FuelType
import com.ridetrack.telemetry.model.MountOrientation
import kotlin.test.Test
import kotlin.test.assertEquals

class BikeColorsTest {
    private fun bike(id: String, created: Long, color: Int? = null) =
        Bike(id, "Bajaj", id, null, null, null, FuelType.PETROL, MountOrientation.PORTRAIT, null, created, routeColor = color)

    @Test
    fun `defaults go by garage order, the first bike keeps teal`() {
        val a = bike("a", 100)
        val b = bike("b", 200)
        val c = bike("c", 300)
        val garage = listOf(c, a, b)
        assertEquals(BikeColors.PALETTE[0], BikeColors.of(a, garage))
        assertEquals(BikeColors.PALETTE[1], BikeColors.of(b, garage))
        assertEquals(BikeColors.PALETTE[2], BikeColors.of(c, garage))
    }

    @Test
    fun `the rider's pick wins`() {
        val a = bike("a", 100, color = 0xFFF87171.toInt())
        assertEquals(Color(0xFFF87171), BikeColors.of(a, listOf(a)))
    }
}
