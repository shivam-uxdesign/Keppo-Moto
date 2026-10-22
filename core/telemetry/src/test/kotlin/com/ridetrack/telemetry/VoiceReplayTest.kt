package com.ridetrack.telemetry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceReplayTest {
    /** A synthetic trace: 20 chunks a second, wind rising with speed, talking in [talk] (s). */
    private fun trace(seconds: Int, talk: List<IntRange>): String {
        val sb = StringBuilder("t_ms,chunk_ms,speed_kmh,level_db,background_db,voice,voice_dewind,high_db,engine_high_db,left_db,right_db,engine_voice,two_mics,independence,lift_db,high_lift_db,wind,clipped_pct,speaking\n")
        var t = 1_000_000L
        repeat(seconds * 20) { i ->
            val s = i / 20
            val talking = talk.any { s in it }
            val wind = (-40f + s * 0.1f).coerceAtMost(-25f)
            val level = if (talking && i % 10 < 8) wind + 20f else wind + (i % 3)
            sb.append("$t,50,${s % 90},$level,$wind,${if (talking) 0.9 else 0.05},,,,,,,,,,,0.5,0,0\n")
            t += 50
        }
        return sb.toString()
    }

    @Test
    fun `the scorecard counts talk caught, false starts and filming in silence`() {
        val rows = VoiceReplay.load(trace(120, listOf(20..30, 70..75)))
        val s = VoiceReplay.score(rows, VoiceReplay.talk("20-31, 70-76"))
        assertEquals(2, s.caught, "$s")
        assertEquals(0, s.falseStarts, "$s")
        assertTrue(s.silentFilmingPct <= 10, "$s")
        assertEquals(0, s.longestEmptyVideoS, "$s")
    }

    @Test
    fun `a ride with no talking scores every start as false`() {
        // Talk the marks don't know about: each start is false and its video empty.
        val rows = VoiceReplay.load(trace(60, listOf(20..25)))
        val s = VoiceReplay.score(rows, VoiceReplay.talk(""))
        assertEquals(1, s.falseStarts, "$s")
        assertTrue(s.longestEmptyVideoS in 6..12, "$s")
    }

    @Test
    fun `older traces without some columns still load`() {
        val rows = VoiceReplay.load("t_ms,level_db,background_db,voice,speaking\n1000,-30,-40,0.8,1\n1046,-31,-40,0.7,0\n")
        assertEquals(2, rows.size)
        assertEquals(-30f, rows[0].levelDb)
        assertTrue(rows[0].speaking)
    }

    /** Every real ride in resources/voice-rides (trace + the rider's marks), scored with today's rules. */
    @Test
    fun `scorecard for the rider's test rides`() {
        val dir = javaClass.getResource("/voice-rides")?.let { File(it.toURI()) } ?: return
        dir.listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name }.forEach { csv ->
            val marks = File(dir, csv.nameWithoutExtension + ".talk").takeIf { it.exists() }?.readText()?.trim() ?: return@forEach
            println("VOICE ${csv.nameWithoutExtension}: ${VoiceReplay.score(VoiceReplay.load(csv.readText()), VoiceReplay.talk(marks))}")
        }
    }
}
