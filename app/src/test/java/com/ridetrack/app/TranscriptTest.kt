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

    @org.junit.Test
    fun `a request cut off by the connection is sent again, other errors are not`() = kotlinx.coroutines.runBlocking {
        val waits = listOf(1L, 1L, 1L)
        var n = 0
        val ok = com.ridetrack.app.studio.steady(5_000, waits) {
            n++
            when (n) {
                1 -> throw java.io.IOException("x", java.net.UnknownHostException("host"))
                2 -> throw RuntimeException("Something unexpected happened.", java.net.SocketException("Software caused connection abort"))
                else -> "fine"
            }
        }
        kotlin.test.assertEquals("fine", ok)
        kotlin.test.assertEquals(3, n)
        var m = 0
        kotlin.test.assertFailsWith<IllegalStateException> { com.ridetrack.app.studio.steady<String>(5_000, waits) { m++; throw IllegalStateException("bad answer") } }
        kotlin.test.assertEquals(1, m)
        // A spent quota is Gemini's answer, not the connection.
        var q = 0
        kotlin.test.assertFailsWith<com.ridetrack.app.transcribe.FirebaseTranscriber.Busy> { com.ridetrack.app.studio.steady<String>(5_000, waits) { q++; throw com.ridetrack.app.transcribe.FirebaseTranscriber.Busy("quota") } }
        kotlin.test.assertEquals(1, q)
    }

    @org.junit.Test
    fun `a connection that never comes back, or a reply too slow, ends as unreachable`() = kotlinx.coroutines.runBlocking {
        var n = 0
        val gone = kotlin.test.assertFailsWith<com.ridetrack.app.transcribe.GeminiUnreachable> {
            com.ridetrack.app.studio.steady<String>(5_000, listOf(1L, 1L)) { n++; throw java.io.EOFException("unexpected end of stream") }
        }
        kotlin.test.assertEquals(3, n)
        kotlin.test.assertTrue(!gone.timedOut)
        var s = 0
        val slow = kotlin.test.assertFailsWith<com.ridetrack.app.transcribe.GeminiUnreachable> {
            com.ridetrack.app.studio.steady<String>(50, listOf(1L)) { s++; kotlinx.coroutines.delay(1_000); "late" }
        }
        kotlin.test.assertTrue(slow.timedOut)
        kotlin.test.assertEquals(1, s) // a slow reply isn't sent again here: the caller sends less
    }

    @org.junit.Test
    fun `timeouts grow with what is sent, and server hiccups are told apart`() {
        kotlin.test.assertEquals(60_000L, com.ridetrack.app.studio.timeoutFor(0))
        // 2 MB of sound on weak mobile data: well over the old flat minute, under the 5-minute cap.
        val twoMb = com.ridetrack.app.studio.timeoutFor(2L * 1024 * 1024)
        kotlin.test.assertTrue(twoMb in 150_000L..200_000L, "$twoMb")
        kotlin.test.assertEquals(300_000L, com.ridetrack.app.studio.timeoutFor(50L * 1024 * 1024))
        kotlin.test.assertTrue(com.ridetrack.app.studio.isServerHiccup(RuntimeException("Server error 503: The model is overloaded")))
        kotlin.test.assertTrue(!com.ridetrack.app.studio.isServerHiccup(RuntimeException("Model not found")))
        val n = com.ridetrack.app.transcribe.GeminiNet
        kotlin.test.assertTrue(n.isDropped(RuntimeException("x", java.net.SocketException("Connection reset"))))
        kotlin.test.assertTrue(!n.isDropped(com.ridetrack.app.transcribe.FirebaseTranscriber.Busy("quota")))
        kotlin.test.assertTrue(!n.isDropped(java.io.FileNotFoundException("clip.m4a")))
        // The failure from the earlier try, kept as suppressed, still counts.
        val e = IllegalStateException("x").apply { addSuppressed(java.net.UnknownHostException("h")) }
        kotlin.test.assertTrue(n.isUnreachable(e))
    }
}
