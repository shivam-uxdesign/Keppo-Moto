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

    @Test
    fun `the Gemini wait line counts down from the saved reset times`() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val now = java.time.ZonedDateTime.of(2026, 10, 7, 2, 17, 0, 0, zone).toInstant().toEpochMilli()
        val at = now + (3 * 60 + 12) * 60_000L
        val q = com.ridetrack.app.transcribe.GeminiQuota
        kotlin.test.assertEquals("Gemini is free again at 5:29 AM · in 3 h 12 min", q.waitLine(at, at, now, zone))
        kotlin.test.assertEquals("Captions free again at 5:29 AM · in 3 h 12 min", q.waitLine(at, null, now, zone))
        kotlin.test.assertEquals(
            "Captions free again at 5:29 AM · in 3 h 12 min; story and tips at 12:30 PM",
            q.waitLine(at, java.time.ZonedDateTime.of(2026, 10, 7, 12, 30, 0, 0, zone).toInstant().toEpochMilli(), now, zone),
        )
        // Once the time has passed the message goes.
        kotlin.test.assertNull(q.waitLine(at, at, at + 1, zone))
        kotlin.test.assertEquals("12 min", q.left(now + 11 * 60_000L + 5_000, now))
    }

    @Test
    fun `no internet or a blocked address is told apart from Gemini saying no`() {
        val n = com.ridetrack.app.transcribe.GeminiNet
        val dns = RuntimeException("Something unexpected happened.", java.net.UnknownHostException("Unable to resolve host \"firebasevertexai.googleapis.com\""))
        kotlin.test.assertTrue(n.isUnreachable(dns))
        kotlin.test.assertTrue(n.message(dns).startsWith("Can't reach Gemini"))
        kotlin.test.assertTrue(n.isUnreachable(com.ridetrack.app.transcribe.GeminiUnreachable(true, null)))
        kotlin.test.assertTrue(n.message(com.ridetrack.app.transcribe.GeminiUnreachable(true, null)).startsWith("Gemini took too long"))
        kotlin.test.assertTrue(!n.isUnreachable(RuntimeException("Quota exceeded")))
    }
}
