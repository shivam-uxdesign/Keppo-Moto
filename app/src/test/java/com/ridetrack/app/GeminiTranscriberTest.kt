package com.ridetrack.app

import com.ridetrack.app.transcribe.GeminiTranscriber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeminiTranscriberTest {
    @Test
    fun `picks the newest stable flash model`() {
        val names = listOf(
            "models/gemini-1.5-flash", "models/gemini-2.5-flash", "models/gemini-2.5-flash-lite", "models/gemini-2.5-pro",
            "models/gemini-3.0-flash-preview", "models/gemini-2.0-flash", "models/gemini-2.5-flash-image", "models/text-embedding-004",
        )
        assertEquals("models/gemini-2.5-flash", GeminiTranscriber.chooseModel(names))
        assertNull(GeminiTranscriber.chooseModel(listOf("models/gemini-2.5-pro")))
    }

    @Test
    fun `no speech is stored empty, and quotes or labels are dropped`() {
        assertEquals("", GeminiTranscriber.cleanTranscript("[no speech]"))
        assertEquals("", GeminiTranscriber.cleanTranscript("  [No speech]\n"))
        assertEquals("bhai ye road mast hai", GeminiTranscriber.cleanTranscript("Transcript: \"bhai ye road mast hai\""))
    }
}
