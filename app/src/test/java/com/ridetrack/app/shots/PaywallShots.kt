package com.ridetrack.app.shots

import com.github.takahirom.roborazzi.captureRoboImage
import com.ridetrack.app.ui.pro.PaywallContent
import com.ridetrack.app.ui.pro.PaywallPlan
import com.ridetrack.app.ui.theme.RideTrackTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the paywall with India's prices (as Play would give them), and before Play has answered (build/shots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class PaywallShots {
    private val india = listOf(
        PaywallPlan("yearly", "₹1,999", 1_999_000_000, yearly = true, trialDays = 7),
        PaywallPlan("monthly", "₹299", 299_000_000, yearly = false, trialDays = 7),
    )

    @Test fun india() = captureRoboImage("build/shots/paywall-india.png") {
        RideTrackTheme(dark = true) { PaywallContent(india, null, {}, {}, {}) }
    }

    @Test fun noPlay() = captureRoboImage("build/shots/paywall-no-play.png") {
        RideTrackTheme(dark = true) { PaywallContent(emptyList(), null, {}, {}, {}) }
    }
}
