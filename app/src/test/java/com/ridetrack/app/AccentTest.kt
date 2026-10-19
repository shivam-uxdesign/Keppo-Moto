package com.ridetrack.app

import androidx.compose.ui.graphics.toArgb
import com.ridetrack.app.ui.theme.Accent
import com.ridetrack.app.ui.theme.DarkPalette
import com.ridetrack.app.ui.theme.LightPalette
import com.ridetrack.app.ui.theme.withAccent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccentTest {
    private fun bike(id: String, created: Long, color: Int) = com.ridetrack.telemetry.model.Bike(
        id, "Bajaj", id, null, null, null, com.ridetrack.telemetry.model.FuelType.PETROL,
        com.ridetrack.telemetry.model.MountOrientation.PORTRAIT, null, created, routeColor = color,
    )
    private val darkBg = DarkPalette.background.toArgb()
    private val lightBg = LightPalette.background.toArgb()

    @Test
    fun `teal and no bike keep the designed palettes`() {
        assertSame(DarkPalette, DarkPalette.withAccent(null))
        assertSame(LightPalette, LightPalette.withAccent(Accent.TEAL))
    }

    @Test
    fun `any colour becomes readable on both backgrounds`() {
        listOf(0xFF1E3A8A, 0xFFFDE047, 0xFF111111, 0xFFFFFFFF, 0xFFE4572E, 0xFF7C3AED).map { it.toInt() }.forEach { c ->
            val dark = DarkPalette.withAccent(c).primary.toArgb()
            val light = LightPalette.withAccent(c).primary.toArgb()
            assertTrue(Accent.contrast(dark, darkBg) >= Accent.TARGET, Accent.hex(c))
            assertTrue(Accent.contrast(light, lightBg) >= Accent.TARGET, Accent.hex(c))
        }
    }

    @Test
    fun `a colour that already reads well is used as picked`() {
        val orange = 0xFFF59E0B.toInt()
        assertEquals(orange, Accent.readable(orange, darkBg))
        // On the light background the same orange needs a darker shade of itself.
        val onLight = Accent.readable(orange, lightBg)
        assertTrue(onLight != orange && Accent.toHsl(onLight)[2] < Accent.toHsl(orange)[2])
        assertTrue(kotlin.math.abs(Accent.toHsl(onLight)[0] - Accent.toHsl(orange)[0]) < 3f)
    }

    @Test
    fun `text on the accent is whichever reads better`() {
        listOf(0xFF69C8CB, 0xFF1E3A8A, 0xFFFDE047).map { it.toInt() }.forEach { c ->
            assertTrue(Accent.contrast(Accent.onAccent(c), c) >= 4.5, Accent.hex(c))
        }
    }

    @Test
    fun `hex codes read and write`() {
        assertEquals(0xFFE4572E.toInt(), Accent.parseHex("#E4572E"))
        assertEquals(0xFFEE5577.toInt(), Accent.parseHex("e57"))
        assertNull(Accent.parseHex("#12345"))
        assertNull(Accent.parseHex("zzzzzz"))
        assertEquals("#E4572E", Accent.hex(0xFFE4572E.toInt()))
    }

    @Test
    fun `the app follows the selected bike, else the first`() {
        val a = bike("a", 1, 0xFFE4572E.toInt())
        val b = bike("b", 2, 0xFF7C3AED.toInt())
        assertEquals(0xFF7C3AED.toInt(), com.ridetrack.app.ui.common.BikeColors.appAccent(listOf(a, b), "b"))
        assertEquals(0xFFE4572E.toInt(), com.ridetrack.app.ui.common.BikeColors.appAccent(listOf(a, b), null))
        assertNull(com.ridetrack.app.ui.common.BikeColors.appAccent(emptyList(), null))
    }
}
