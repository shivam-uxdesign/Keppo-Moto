package com.ridetrack.app

import com.ridetrack.app.studio.ExportPreset
import com.ridetrack.app.studio.Exports
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportsTest {
    @Test
    fun `a size estimate from the bitrate and length`() {
        // 10 Mbps + sound for 30 s ≈ 38 MB.
        assertEquals("≈ 38 MB", Exports.sizeLabel(Exports.sizeBytes(ExportPreset.INSTAGRAM.spec, 30_000)))
        assertEquals("≈ 12 MB", Exports.sizeLabel(Exports.sizeBytes(ExportPreset.WHATSAPP.spec, 30_000)))
    }

    @Test
    fun `a story is cut into 60 s parts, never ending on a sliver`() {
        assertEquals(listOf(0L..45_000L), Exports.parts(45_000, 60_000))
        assertEquals(listOf(0L..60_000L, 60_000L..90_000L), Exports.parts(90_000, 60_000))
        // 121 s: not 60 + 60 + 1, but 60 + 30.5 + 30.5.
        assertEquals(listOf(0L..60_000L, 60_000L..90_500L, 90_500L..121_000L), Exports.parts(121_000, 60_000))
    }

    @Test
    fun `4K and 60 fps only when every clip has them`() {
        assertEquals(false to false, Exports.choices(listOf(Triple(1080, 1920, 30f), Triple(2160, 3840, 60f))))
        assertEquals(true to true, Exports.choices(listOf(Triple(2160, 3840, 60f), Triple(3840, 2160, 59.94f))))
    }
}
