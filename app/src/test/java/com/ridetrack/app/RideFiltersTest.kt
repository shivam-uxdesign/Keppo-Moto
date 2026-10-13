package com.ridetrack.app

import com.ridetrack.app.studio.RideFilter
import com.ridetrack.app.studio.RideFilters
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideFiltersTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** [m] applied to an RGB colour (0..255). */
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) = (0 until 3).map { row -> m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4] }

    @Test
    fun `no filter, or none of it, leaves the picture alone`() {
        assertTrue(RideFilters.matrix(RideFilter.NONE, 1f).contentEquals(identity))
        RideFilter.entries.forEach { f -> assertTrue(RideFilters.matrix(f, 0f).contentEquals(identity), "$f at 0") }
    }

    @Test
    fun `grit is black and white, golden hour is warmer, night ride is bluer`() {
        val grit = apply(RideFilters.matrix(RideFilter.GRIT, 1f), 200f, 80f, 40f)
        assertTrue(abs(grit[0] - grit[1]) < 0.01f && abs(grit[1] - grit[2]) < 0.01f, "$grit")
        val gold = apply(RideFilters.matrix(RideFilter.GOLDEN, 1f), 128f, 128f, 128f)
        assertTrue(gold[0] > gold[2], "$gold")
        val night = apply(RideFilters.matrix(RideFilter.NIGHT, 1f), 128f, 128f, 128f)
        assertTrue(night[2] > night[0], "$night")
    }

    @Test
    fun `strength blends between none and full, and Media3's matrix is the same maths`() {
        val half = RideFilters.matrix(RideFilter.DUST, 0.5f)
        val full = RideFilters.matrix(RideFilter.DUST, 1f)
        for (i in half.indices) assertTrue(abs(half[i] - (identity[i] + full[i]) / 2) < 1e-4f, "entry $i")
        val m3 = RideFilters.media3(RideFilter.DUST, 1f)
        assertEquals(full[0], m3[0]); assertEquals(full[1], m3[4]); assertEquals(full[4] / 255f, m3[12])
        assertTrue(RideFilters.keepsRed(RideFilter.RED_ONLY))
    }
}
