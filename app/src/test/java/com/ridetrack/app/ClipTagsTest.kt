package com.ridetrack.app

import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipTags
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class ClipTagsTest {
    @Test
    fun `tags say how it was filmed and the words that matter`() {
        val lines = listOf(
            CaptionLine(0, 2_000, "Bhai yeh flyover dekho, flyover pe traffic nahi hai"),
            CaptionLine(2_000, 4_000, "Rain aa rahi hai, rain mein ride"),
        )
        val tags = ClipTags.of(lines, camera = null, topKmh = 72)
        assertEquals(listOf("selfie", "talking", "fast"), tags.take(3))
        assertTrue("flyover" in tags)
        assertTrue("rain" in tags)
        assertTrue("bhai" !in tags && "dekho" !in tags)
    }

    @Test
    fun `a silent road shot`() {
        assertEquals(listOf("road"), ClipTags.of(emptyList(), camera = "back", topKmh = 30))
    }
}
