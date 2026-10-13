package com.ridetrack.app.studio

/**
 * The colour a video gets, as numbers: each style's grade and a clip's own colour. The made video
 * turns them into Media3 effects; the editor's preview into one colour matrix, so both match.
 * Pure, so it's unit-tested.
 */
object Looks {
    /**
     * One colour step, in Media3's terms: [bright] added (−1..1), [contrast] (−1..1, about mid-grey),
     * [sat] and [light] (HSL, −100..100), red and blue gains, and a black [lift] (0..1).
     */
    data class Spec(
        val bright: Float = 0f,
        val contrast: Float = 0f,
        val sat: Float = 0f,
        val light: Float = 0f,
        val r: Float = 1f,
        val b: Float = 1f,
        val lift: Float = 0f,
    )

    /** Each style's colour look. */
    fun grade(v: Vibe): Spec = when (v) {
        Vibe.HYPE -> Spec(contrast = 0.1f, sat = 16f, r = 1.04f, b = 0.96f)
        Vibe.CINE -> Spec(contrast = 0.12f, sat = -20f, light = -3f, r = 0.96f, b = 1.05f)
        Vibe.CHILL -> Spec(sat = -12f, light = 4f, r = 1.06f, b = 0.92f)
        Vibe.VLOG -> Spec(contrast = 0.05f, sat = 8f)
        Vibe.FILM -> Spec(contrast = -0.06f, sat = -10f, r = 1.05f, b = 0.92f, lift = 0.04f)
        Vibe.EDITORIAL -> Spec(contrast = 0.08f, sat = -8f)
        Vibe.NATIVE -> Spec()
        // Crisp and a touch cool, so Speedtrap's red reads.
        Vibe.SPEEDTRAP -> Spec(contrast = 0.1f, sat = -6f, r = 0.98f, b = 1.03f)
    }

    /** A clip's own colour (exposure, contrast, saturation, warmth). */
    fun clip(c: ClipColor): Spec = Spec(
        bright = c.exposure * 0.35f,
        contrast = c.contrast * 0.4f,
        sat = c.saturation * 40f,
        r = 1f + 0.12f * c.warmth,
        b = 1f - 0.12f * c.warmth,
    )

    /** Media3's contrast factor for [c]. */
    fun contrastFactor(c: Float) = (1 + c) / (1.0001f - c)

    /**
     * A segment's whole look as one 4×5 colour matrix (Android's layout; offsets 0..255): the
     * style's grade (unless the clip turned it off), the clip's colour, then the rider's filter.
     */
    fun matrix(vibe: Vibe, clip: ClipColor?, filter: RideFilter, amount: Float): FloatArray {
        var m = IDENTITY
        if (clip == null || clip.look) m = then(m, specMatrix(grade(vibe)))
        if (clip != null && !clip.plain) m = then(m, specMatrix(clip(clip)))
        if (filter != RideFilter.NONE && !RideFilters.keepsRed(filter)) m = then(m, RideFilters.matrix(filter, amount))
        return m
    }

    /** [s] as a colour matrix, in the made video's order: brightness, contrast, saturation and lightness, gains, lift. */
    fun specMatrix(s: Spec): FloatArray {
        var m = IDENTITY
        if (s.bright != 0f) m = then(m, scaleOffset(1f, 1f, 1f, s.bright * 255f))
        if (s.contrast != 0f) {
            val k = contrastFactor(s.contrast)
            m = then(m, scaleOffset(k, k, k, 127.5f * (1 - k)))
        }
        if (s.sat != 0f) m = then(m, saturation(1f + s.sat / 100f))
        if (s.light != 0f) m = then(m, scaleOffset(1f, 1f, 1f, s.light / 100f * 255f))
        if (s.r != 1f || s.b != 1f) m = then(m, scaleOffset(s.r, 1f, s.b, 0f))
        if (s.lift != 0f) m = then(m, scaleOffset(1 - s.lift, 1 - s.lift, 1 - s.lift, s.lift * 255f))
        return m
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    private fun scaleOffset(r: Float, g: Float, b: Float, o: Float) = floatArrayOf(r, 0f, 0f, 0f, o, 0f, g, 0f, 0f, o, 0f, 0f, b, 0f, o, 0f, 0f, 0f, 1f, 0f)

    private fun saturation(s: Float): FloatArray {
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        return floatArrayOf(
            lr * (1 - s) + s, lg * (1 - s), lb * (1 - s), 0f, 0f,
            lr * (1 - s), lg * (1 - s) + s, lb * (1 - s), 0f, 0f,
            lr * (1 - s), lg * (1 - s), lb * (1 - s) + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** [a] then [b] (b applied to a's result). */
    fun then(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var v = 0f
                for (k in 0 until 4) v += b[row * 5 + k] * a[k * 5 + col]
                if (col == 4) v += b[row * 5 + 4]
                out[row * 5 + col] = v
            }
        }
        return out
    }
}
