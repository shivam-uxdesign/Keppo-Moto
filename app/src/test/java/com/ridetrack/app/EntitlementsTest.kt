package com.ridetrack.app

import androidx.test.core.app.ApplicationProvider
import com.ridetrack.app.pro.Entitlements
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EntitlementsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `everyone is Pro in the beta, and test builds can act as a free rider`() {
        val e = Entitlements(context)
        e.actAsFree = false
        assertTrue(e.isPro)
        e.actAsFree = true
        assertFalse(e.isPro)
        assertFalse(e.pro.value)
        // Remembered.
        assertFalse(Entitlements(context).isPro)
        e.actAsFree = false
        assertTrue(Entitlements(context).isPro)
    }
}
