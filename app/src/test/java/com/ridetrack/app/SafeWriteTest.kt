package com.ridetrack.app.studio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafeWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun replacesTheFileAndLeavesNothingBehind() {
        val f = File(tmp.root, "a/series.json")
        f.writeAtomically("old")
        f.writeAtomically("new")
        assertEquals("new", f.readText())
        assertEquals(listOf("series.json"), f.parentFile!!.list()!!.toList())
    }
}
