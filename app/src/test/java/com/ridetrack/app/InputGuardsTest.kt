package com.ridetrack.app

import com.ridetrack.app.backup.BackupNames
import com.ridetrack.app.ui.nav.OPEN_RIDE_SHAPE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputGuardsTest {
    @Test fun openRideAcceptsWhatTheAppSends() {
        val ride = "3f2b6c1e-9a4d-4e2b-8c1a-0d9e7f6a5b4c"
        listOf(ride, "studio:$ride", "studio:$ride#a1b2c3d4-e5f6", "studio:#a1b2c3d4-e5f6", "review:a1b2c3d4-e5f6").forEach {
            assertTrue(it, OPEN_RIDE_SHAPE.matches(it))
        }
    }

    @Test fun openRideRefusesOddIds() {
        listOf("x/../y", "a?b=c", "studio:a#b/c", "a b", "x".repeat(300)).forEach {
            assertFalse(it, OPEN_RIDE_SHAPE.matches(it))
        }
    }

    @Test fun backupNamesStayInTheirFolder() {
        assertTrue(BackupNames.safe("clip-0012.mp4"))
        listOf("", "..", ".", "../x", "a/b", "a\\b").forEach { assertFalse(it, BackupNames.safe(it)) }
    }
}
