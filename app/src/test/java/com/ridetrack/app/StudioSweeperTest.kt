package com.ridetrack.app

import com.ridetrack.app.studio.StudioSweeper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StudioSweeperTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 100L * 24 * 3600 * 1000
    private val old = now - StudioSweeper.MIN_AGE_MS - 1

    private fun file(path: String, at: Long = old, text: String = "x") =
        File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText(text); setLastModified(at) }

    @Test fun deletesOnlyOldFilesNoEditUses() {
        val used = file("studio-stills/still-aaaa1111.jpg")
        val unused = file("studio-stills/still-bbbb2222.jpg")
        val fresh = file("studio-reversed/rev-cccc3333.mp4", at = now - 1000)
        val oldRev = file("studio-reversed/rev-dddd4444.mp4")
        val voiceUsed = file("studio/voice-r1-123.pcm")
        val voiceUnused = file("studio/voice-r1-456.pcm")
        val other = file("studio/notes.txt")
        file("reels/abc/project.json", text = """{"still":"/data/files/studio-stills/still-aaaa1111.jpg"}""")
        file("studio-drafts/ride-1.json", text = """{"file":"voice-r1-123.pcm"}""")

        assertEquals(3, StudioSweeper.sweep(tmp.root, now))
        assertTrue(used.exists()); assertTrue(fresh.exists()); assertTrue(voiceUsed.exists()); assertTrue(other.exists())
        assertFalse(unused.exists()); assertFalse(oldRev.exists()); assertFalse(voiceUnused.exists())
    }

    @Test fun nothingToDoWithoutStudioFolders() {
        assertEquals(0, StudioSweeper.sweep(tmp.root, now))
    }
}
