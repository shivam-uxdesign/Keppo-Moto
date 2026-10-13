package com.ridetrack.app.studio

/**
 * Filters for ride footage, the rider's own setting (separate from the style): they change the
 * video's colour, under every graphic. New ones are only ever added at the end (saved by name).
 */
enum class RideFilter(val label: String) {
    NONE("None"),
    ASPHALT("Asphalt"),
    GOLDEN("Golden hour"),
    MONSOON("Monsoon"),
    NIGHT("Night ride"),
    DUST("Dust"),
    GHAT("Ghat green"),
    TEAL_ORANGE("Teal & orange"),
    GRIT("Grit"),
    RED_ONLY("Red only"),
}

/** Each filter as colour maths. Pure, so it's unit-tested and the same for the preview and the made video. */
object RideFilters {
    /**
     * What a filter does: saturation, contrast (about mid-grey), each channel's gain, a lift
     * (added to every channel, 0..1), and a per-channel shift (0..1). Red only also keeps reds
     * in colour and greys the rest (a shader, see [keepsRed]).
     */
    data class Grade(
        val sat: Float = 1f,
        val contrast: Float = 1f,
        val r: Float = 1f,
        val g: Float = 1f,
        val b: Float = 1f,
        val lift: Float = 0f,
        val dr: Float = 0f,
        val dg: Float = 0f,
        val db: Float = 0f,
    )

    fun grade(f: RideFilter): Grade = when (f) {
        RideFilter.NONE -> Grade()
        // Cool and crisp: dark roads, cool shadows.
        RideFilter.ASPHALT -> Grade(sat = 0.75f, contrast = 1.18f, r = 0.96f, b = 1.06f, db = 0.02f)
        // Warm sunset light.
        RideFilter.GOLDEN -> Grade(sat = 1.12f, contrast = 1.05f, r = 1.12f, g = 1.03f, b = 0.84f, dr = 0.03f, dg = 0.01f)
        // Wet rides: muted teal, soft.
        RideFilter.MONSOON -> Grade(sat = 0.7f, contrast = 0.94f, r = 0.88f, g = 1.04f, b = 1.04f, lift = 0.02f, dg = 0.02f, db = 0.03f)
        // After dark: deep blue, punchy lights.
        RideFilter.NIGHT -> Grade(sat = 1.15f, contrast = 1.22f, r = 0.92f, g = 0.95f, b = 1.12f, lift = -0.05f, dr = -0.02f, dg = -0.02f)
        // Highways and the high desert: faded and warm.
        RideFilter.DUST -> Grade(sat = 0.62f, contrast = 0.84f, r = 1.08f, b = 0.86f, lift = 0.06f, dr = 0.02f)
        // Lush hills and forest: greens up, a little contrast.
        RideFilter.GHAT -> Grade(sat = 1.15f, contrast = 1.06f, r = 0.94f, g = 1.08f, b = 0.96f)
        // The action-cam look: warm skin and lights, teal shadows.
        RideFilter.TEAL_ORANGE -> Grade(sat = 1.1f, contrast = 1.12f, r = 1.08f, g = 0.98f, b = 0.92f, dg = 0.02f, db = 0.05f)
        // Hard black and white.
        RideFilter.GRIT -> Grade(sat = 0f, contrast = 1.35f)
        // Black and white with a bit of bite (the shader puts the reds back).
        RideFilter.RED_ONLY -> Grade(sat = 0f, contrast = 1.2f)
    }

    /** Whether [f] keeps red things in colour (a red jacket, a red tank). */
    fun keepsRed(f: RideFilter) = f == RideFilter.RED_ONLY

    /**
     * The filter at [amount] (0 = none, 1 = full) as a 4×5 colour matrix in Android's layout
     * (rows R, G, B, A; the last column an offset in 0..255).
     */
    fun matrix(f: RideFilter, amount: Float): FloatArray {
        val k = amount.coerceIn(0f, 1f)
        val g = grade(f)
        // Saturation (Rec. 709 luminance), then gains, then contrast about mid-grey, then lift and shifts.
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val s = g.sat
        val sat = arrayOf(
            floatArrayOf(lr * (1 - s) + s, lg * (1 - s), lb * (1 - s)),
            floatArrayOf(lr * (1 - s), lg * (1 - s) + s, lb * (1 - s)),
            floatArrayOf(lr * (1 - s), lg * (1 - s), lb * (1 - s) + s),
        )
        val gains = floatArrayOf(g.r, g.g, g.b)
        val shifts = floatArrayOf(g.dr, g.dg, g.db)
        val out = FloatArray(20)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val full = sat[row][col] * gains[row] * g.contrast
                val id = if (row == col) 1f else 0f
                out[row * 5 + col] = id + (full - id) * k
            }
            val offset = (0.5f * (1 - g.contrast) + g.lift + shifts[row]) * 255f
            out[row * 5 + 4] = offset * k + 0f // no "minus zero" at strength 0
        }
        out[18] = 1f
        return out
    }

    /**
     * The same as a 4×4 matrix for Media3 (column-major; the last column an offset in 0..1),
     * applied to (r, g, b, 1).
     */
    fun media3(f: RideFilter, amount: Float): FloatArray {
        val m = matrix(f, amount)
        return floatArrayOf(
            m[0], m[5], m[10], 0f,
            m[1], m[6], m[11], 0f,
            m[2], m[7], m[12], 0f,
            m[4] / 255f, m[9] / 255f, m[14] / 255f, 1f,
        )
    }
}
