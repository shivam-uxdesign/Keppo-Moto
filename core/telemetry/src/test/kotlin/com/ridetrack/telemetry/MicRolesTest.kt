package com.ridetrack.telemetry

import com.ridetrack.telemetry.moments.LiftGuard
import com.ridetrack.telemetry.moments.MicRoleCheck
import com.ridetrack.telemetry.moments.SpeechGate
import com.ridetrack.telemetry.moments.TwoMicLift
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicRolesTest {
    @Test
    fun voiceHeardOnTheEngineSideMeansSwapped() {
        val check = MicRoleCheck()
        // 5 s of talking that only the "Engine" side hears, 1 s heard on the "Voice" side.
        repeat(100) { check.onChunk(50, voice = 0.1f, engine = 0.9f) }
        repeat(20) { check.onChunk(50, voice = 0.8f, engine = 0.1f) }
        assertEquals(MicRoleCheck.Verdict.SWAPPED, check.verdict)
    }

    @Test
    fun voiceOnTheVoiceSideIsRight() {
        val check = MicRoleCheck()
        repeat(100) { check.onChunk(50, voice = 0.9f, engine = 0.2f) }
        assertEquals(MicRoleCheck.Verdict.RIGHT, check.verdict)
    }

    @Test
    fun bothSidesHearingItSaysNothing() {
        val check = MicRoleCheck()
        // A loud voice reaches both mics: no evidence either way.
        repeat(200) { check.onChunk(50, voice = 0.9f, engine = 0.9f) }
        // Engine noise: neither side voice-like.
        repeat(200) { check.onChunk(50, voice = 0.05f, engine = 0.1f) }
        assertEquals(MicRoleCheck.Verdict.UNKNOWN, check.verdict)
    }

    @Test
    fun liftWithoutAnyVoiceIsDistrustedAfter30s() {
        val guard = LiftGuard()
        var tripped = -1L
        var t = 0L
        while (t < 40_000) {
            if (guard.onChunk(t, voice = 0.05f, speaking = true) && tripped < 0) tripped = t
            t += 50
        }
        assertEquals(30_000L, tripped)
        assertFalse(guard.allowed(40_000))
        assertTrue(guard.allowed(30_000 + 5 * 60_000))
    }

    @Test
    fun realTalkingKeepsTheLift() {
        val guard = LiftGuard()
        var t = 0L
        // A minute of talking with a voice-like chunk every 10 s (shouting in wind).
        while (t < 60_000) {
            val voice = if (t % 10_000 == 0L) 0.5f else 0.1f
            assertFalse(guard.onChunk(t, voice, speaking = true))
            t += 50
        }
        assertTrue(guard.allowed(t))
    }

    @Test
    fun swappedMicsRevvingNoLongerFilmsForever() {
        // Swapped roles: the back-seat mic is read as the "voice" side. Each throttle opening
        // makes it stand out from the helmet mic by 10 dB for 3 s of every 4 s.
        val gate = SpeechGate()
        val lift = TwoMicLift()
        val guard = LiftGuard()
        var t = 0L
        var speakingAt60s = true
        var speakingAt45s = false
        var speakingAt10s = false
        while (t <= 60_000) {
            val revving = (t / 1_000) % 4 != 0L
            val voiceDb = if (revving) -20f else -30f
            val l = lift.onLevels(t, voiceDb, -30f)
            val speaking = gate.onLevel(t, 0f, 50, 6f, voice = 0.05f, liftDb = if (guard.allowed(t)) l else null)
            guard.onChunk(t, 0.05f, speaking)
            if (t == 10_500L) speakingAt10s = speaking
            if (t == 45_000L) speakingAt45s = speaking
            if (t == 60_000L) speakingAt60s = speaking
            t += 50
        }
        // Before the guard, revving reads as talking...
        assertTrue(speakingAt10s)
        // ...and after 30 s of it with no voice, it stops counting.
        assertFalse(speakingAt45s)
        assertFalse(speakingAt60s)
    }
}
