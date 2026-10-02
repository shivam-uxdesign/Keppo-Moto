package com.ridetrack.app

import com.ridetrack.app.journal.JournalTree
import com.ridetrack.app.journal.JournalTree.Doc
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalTreeTest {
    @Test
    fun `ids round-trip`() {
        listOf(Doc.Root, Doc.Ride("r1"), Doc.File("r1", "a1b2.mp4")).forEach { d ->
            assertEquals(d, JournalTree.parse(JournalTree.id(d)))
        }
    }

    @Test
    fun `ids that try to leave a ride folder are refused`() {
        assertNull(JournalTree.parse("file:r1/../r2/x.mp4"))
        assertNull(JournalTree.parse("file:../x"))
        assertNull(JournalTree.parse("file:r1/.."))
        assertNull(JournalTree.parse("ride:a/b"))
        assertNull(JournalTree.parse("file:r1"))
        assertNull(JournalTree.parse("something"))
    }

    @Test
    fun `ride folders are dated and safe`() {
        val t = 1_790_000_000_000L // 2026-09-21 UTC
        assertEquals("2026-09-21 Sunday at Nahan", JournalTree.rideFolderName(t, "Sunday at Nahan", ZoneOffset.UTC))
        assertEquals("2026-09-21 Delhi to Agra", JournalTree.rideFolderName(t, "Delhi/to:Agra", ZoneOffset.UTC))
        assertEquals("2026-09-21", JournalTree.rideFolderName(t, "  ", ZoneOffset.UTC))
    }

    @Test
    fun `a ride folder lists data, route, then moments`() {
        val kids = JournalTree.rideChildren("r1", listOf("a.mp4", "a.jpg", "a.mp4", "ride.json", "../x"))
        assertEquals(listOf("ride.json", "route.png", "a.mp4", "a.jpg"), kids.map { it.name })
    }

    @Test
    fun `children belong to their own parent only`() {
        assertTrue(JournalTree.isChild("root", "ride:r1"))
        assertTrue(JournalTree.isChild("root", "file:r1/a.mp4"))
        assertTrue(JournalTree.isChild("ride:r1", "file:r1/a.mp4"))
        assertFalse(JournalTree.isChild("ride:r1", "file:r2/a.mp4"))
        assertFalse(JournalTree.isChild("file:r1/a.mp4", "file:r1/a.mp4"))
        assertFalse(JournalTree.isChild("root", "root"))
    }

    @Test
    fun `mime types`() {
        assertEquals("application/json", JournalTree.mime("ride.json"))
        assertEquals("video/mp4", JournalTree.mime("a.mp4"))
        assertEquals("image/jpeg", JournalTree.mime("a.jpg"))
        assertEquals("image/png", JournalTree.mime("route.png"))
    }
}
