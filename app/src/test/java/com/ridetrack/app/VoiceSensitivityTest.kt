package com.ridetrack.app

import com.ridetrack.app.data.VoiceSensitivity
import com.ridetrack.telemetry.moments.HighBandLift
import com.ridetrack.telemetry.moments.VoiceInWind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSensitivityTest {
    @Test
    fun `each step to the right asks more of every speech check`() {
        VoiceSensitivity.entries.zipWithNext().forEach { (a, b) ->
            assertTrue("$a → $b", b.marginDb > a.marginDb && b.liftDb > a.liftDb && b.highDb >= a.highDb)
        }
    }

    @Test
    fun `the middle step is what riders had before`() {
        val balanced = VoiceSensitivity.STRICT
        assertEquals(2, VoiceSensitivity.entries.indexOf(balanced))
        assertEquals(VoiceInWind.LIFT_DB, balanced.liftDb)
        assertEquals(HighBandLift.SPEECH_DB, balanced.highDb)
    }

    @Test
    fun `saved settings from older versions still load`() {
        assertEquals(VoiceSensitivity.NORMAL, VoiceSensitivity.of("LOW"))
        assertEquals(VoiceSensitivity.NORMAL, VoiceSensitivity.of("NORMAL"))
        assertEquals(VoiceSensitivity.STRICT, VoiceSensitivity.of("MEDIUM"))
        assertEquals(VoiceSensitivity.STRICT, VoiceSensitivity.of("HIGH"))
        assertEquals(VoiceSensitivity.STRICT, VoiceSensitivity.of(null))
        assertEquals(VoiceSensitivity.STRICTEST, VoiceSensitivity.of("STRICTEST"))
    }
}
