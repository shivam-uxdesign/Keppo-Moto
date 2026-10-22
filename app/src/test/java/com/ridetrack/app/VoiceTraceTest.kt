package com.ridetrack.app

import com.ridetrack.app.moments.TraceAudio
import com.ridetrack.app.moments.TwoMicKind
import com.ridetrack.app.moments.VoiceChunk
import com.ridetrack.app.moments.VoiceTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceTraceTest {
    @Test
    fun `every row has every column, one mic or two`() {
        val f = File.createTempFile("voice-trace", ".csv").apply { delete() }
        val t = VoiceTrace(f)
        t.row(TraceAudio(1_000, -40f, -42f, 0.3f, 0.97, 0.001f, -20f, -22f), VoiceChunk(1_000, -30f, -38f, 46, 0.1f, 2f, 4f), 64.4f, 0.6f, 0.05f, TwoMicKind.TWO, true)
        t.row(TraceAudio(1_046, Float.NaN, null, null, null, 0f, null, null), null, null, 0f, null, null, false)
        t.flush()
        val lines = f.readLines()
        val columns = VoiceTrace.HEADER.split(',').size
        assertEquals(VoiceTrace.HEADER, lines[0])
        assertEquals(3, lines.size)
        lines.drop(1).forEach { assertEquals(it, columns, it.split(',').size) }
        assertTrue(lines[1], lines[1].startsWith("1000,46,64.4,-30.0,-38.0,0.10,0.30,-40.0,-42.0,-20.0,-22.0,0.05,TWO,0.97,2.0,4.0,0.60,0.10,1"))
        f.delete()
    }
}
