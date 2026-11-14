package com.ridetrack.app

import com.ridetrack.app.diag.FreezeWatch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreezeWatchTest {
    @Test fun `a long sleep of the watchdog itself is the app being paused, not a freeze`() {
        assertFalse(FreezeWatch.paused(1_050))
        assertFalse(FreezeWatch.paused(2_900))
        assertTrue(FreezeWatch.paused(42_000))
    }
}
