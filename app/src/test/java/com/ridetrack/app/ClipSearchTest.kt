package com.ridetrack.app

import com.ridetrack.app.studio.CaptionLine
import com.ridetrack.app.studio.ClipSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipSearchTest {
    @Test
    fun `Hinglish spellings find each other`() {
        assertTrue(ClipSearch.matches("pani", "Bhai paaani nahi piya"))
        assertTrue(ClipSearch.matches("bhaai", "bhai dekho"))
        assertTrue(ClipSearch.matches("fly", "What a flyover!"))
        assertFalse(ClipSearch.matches("rain", "brain"))
        assertTrue(ClipSearch.matches("paani piya", "Bhai paaani nahi piya"))
        assertFalse(ClipSearch.matches("paani chai", "Bhai paaani nahi piya"))
    }

    @Test
    fun `lines and seen things, whole words first`() {
        val lines = mapOf("a" to listOf(CaptionLine(1_000, 2_000, "Rain aa gayi"), CaptionLine(3_000, 4_000, "rainy day")), "b" to listOf(CaptionLine(0, 1_000, "kuch nahi")))
        val seen = mapOf("b" to listOf("rain", "truck"))
        val meta = { id: String -> Triple("r1", "Monday ride", if (id == "a") 1_000_000L else 2_000_000L) }
        val hits = ClipSearch.search("rain", lines, seen, meta)
        assertEquals(listOf("Rain aa gayi", "Seen: rain, truck", "rainy day"), hits.map { it.line.text })
        assertEquals(1_001_000L, hits.first().atMillis)
    }

    @Test
    fun `seen words read from Gemini's answer`() {
        assertEquals(listOf(listOf("rain", "flyover"), emptyList()), ClipSearch.parseSeen("""{"clips":[["Rain"," flyover"]]}""", 2))
    }
}
