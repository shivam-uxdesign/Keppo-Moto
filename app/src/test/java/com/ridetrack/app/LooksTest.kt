package com.ridetrack.app

import com.ridetrack.app.studio.ClipColor
import com.ridetrack.app.studio.Looks
import com.ridetrack.app.studio.RideFilter
import com.ridetrack.app.studio.RideFilters
import com.ridetrack.app.studio.Vibe
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

class LooksTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) = (0 until 3).map { row -> m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4] }
    private fun close(a: FloatArray, b: FloatArray) = a.indices.all { abs(a[it] - b[it]) < 1e-3f }

    @Test
    fun `native with no colour and no filter leaves the picture alone`() {
        assertTrue(close(Looks.matrix(Vibe.NATIVE, ClipColor(), RideFilter.NONE, 1f), identity))
    }

    @Test
    fun `a style's grade shows, unless the clip turned it off`() {
        val film = apply(Looks.matrix(Vibe.FILM, null, RideFilter.NONE, 1f), 0f, 0f, 0f)
        // Film lifts its blacks.
        assertTrue(film.all { it > 5f }, "$film")
        val off = Looks.matrix(Vibe.FILM, ClipColor(look = false), RideFilter.NONE, 1f)
        assertTrue(close(off, identity))
    }

    @Test
    fun `the clip's warmth and the filter go on top of the grade, in order`() {
        val warm = apply(Looks.matrix(Vibe.NATIVE, ClipColor(warmth = 1f), RideFilter.NONE, 1f), 128f, 128f, 128f)
        assertTrue(warm[0] > warm[2], "$warm")
        val withFilter = Looks.matrix(Vibe.NATIVE, ClipColor(), RideFilter.GRIT, 1f)
        assertTrue(close(withFilter, Looks.then(identity, RideFilters.matrix(RideFilter.GRIT, 1f))))
        // Red only is a shader, not part of the matrix.
        assertTrue(close(Looks.matrix(Vibe.NATIVE, ClipColor(), RideFilter.RED_ONLY, 1f), identity))
    }
}
