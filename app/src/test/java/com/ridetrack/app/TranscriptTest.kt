package com.ridetrack.app

import com.ridetrack.app.transcribe.FirebaseTranscriber
import com.ridetrack.app.transcribe.TranscriptText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptTest {
    @Test
    fun `no speech is stored empty, and quotes or labels are dropped`() {
        assertEquals("", TranscriptText.clean("[no speech]"))
        assertEquals("", TranscriptText.clean("  [No speech]\n"))
        assertEquals("bhai ye road mast hai", TranscriptText.clean("Transcript: \"bhai ye road mast hai\""))
    }

    @Test
    fun `a retired model name moves on to the next, other errors don't`() {
        assertTrue(FirebaseTranscriber.isMissingModel(RuntimeException("models/gemini-3-flash is not found for API version v1beta")))
        assertFalse(FirebaseTranscriber.isMissingModel(RuntimeException("App Check token is invalid")))
        assertTrue(FirebaseTranscriber.MODELS.isNotEmpty())
        val retired = RuntimeException(
            "This model models/gemini-2.5-flash is no longer available to new users. Please update your code to use models/gemini-3.8-flash for the latest features and improvements.",
        )
        assertTrue(FirebaseTranscriber.isMissingModel(retired))
        assertEquals("gemini-3.8-flash", FirebaseTranscriber.suggestedModel(retired, "gemini-2.5-flash"))
    }
}
