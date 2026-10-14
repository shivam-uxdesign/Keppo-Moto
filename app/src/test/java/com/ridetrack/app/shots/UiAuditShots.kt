package com.ridetrack.app.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ridetrack.app.data.EmergencyContact
import com.ridetrack.app.data.HudLayout
import com.ridetrack.app.data.HudSettings
import com.ridetrack.app.data.HudTheme
import com.ridetrack.app.data.MedicalInfo
import com.ridetrack.app.fuel.FuelPrompt
import com.ridetrack.app.hud.CameraIndicator
import com.ridetrack.app.hud.HudData
import com.ridetrack.app.hud.HudStatus
import com.ridetrack.app.moments.TwoMicKind
import com.ridetrack.app.moments.TwoMicLevels
import com.ridetrack.app.safety.CrashAlertState
import com.ridetrack.app.safety.CrashReport
import com.ridetrack.app.safety.SmsStatus
import com.ridetrack.app.ui.home.BikeCareCard
import com.ridetrack.app.ui.home.CareItem
import com.ridetrack.app.ui.home.CareStatus
import com.ridetrack.app.ui.home.FuelPromptCard
import com.ridetrack.app.ui.home.Milestone
import com.ridetrack.app.ui.home.MilestonesCard
import com.ridetrack.app.ui.home.MomentsToggles
import com.ridetrack.app.ui.home.ReadinessCard
import com.ridetrack.app.ui.home.Readiness
import com.ridetrack.app.ui.home.ReadyInputs
import com.ridetrack.app.ui.hud.HudBubble
import com.ridetrack.app.ui.hud.HudCard
import com.ridetrack.app.ui.pro.PaywallContent
import com.ridetrack.app.ui.pro.PaywallPlan
import com.ridetrack.app.ui.safety.CrashAlertContent
import com.ridetrack.app.ui.theme.RideTrackTheme
import com.ridetrack.app.ui.theme.RtColors
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The UI audit's contact sheets (build/shots/ui-*.png): each part side by side as the rider can
 * see it — dark, light, a small phone (320 dp, text 130%) and the largest text (200%).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1720dp-h2600dp-hdpi", application = android.app.Application::class)
class UiAuditShots {
    private data class Variant(val name: String, val dark: Boolean, val widthDp: Int, val fontScale: Float)

    private val variants = listOf(
        Variant("Dark", true, 390, 1f),
        Variant("Light", false, 390, 1f),
        Variant("Small phone · 130%", true, 320, 1.3f),
        Variant("Largest text · 200%", true, 390, 2f),
    )

    private fun sheet(name: String, content: @Composable () -> Unit) = captureRoboImage("build/shots/ui-$name.png") {
        Row(Modifier.background(Color(0xFF2A2A2E)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            variants.forEach { v ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(v.name, color = Color.White, fontSize = 14.sp)
                    val base = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(base.density, v.fontScale)) {
                        RideTrackTheme(dark = v.dark) {
                            Column(
                                Modifier.width(v.widthDp.dp).background(RtColors.Background).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) { content() }
                        }
                    }
                }
            }
        }
    }

    @Test fun home() = sheet("home") {
        ReadinessCard(Readiness.checks(ReadyInputs(crashOn = true, contacts = 0, overlayAllowed = false, hudOn = true, batteryPct = 64)), {})
        ReadinessCard(Readiness.checks(ReadyInputs(crashOn = false, batteryPct = 82)), {})
        MomentsToggles(moments = true, voice = false, onMoments = {}, onVoice = {})
        FuelPromptCard(FuelPrompt("r", "b", 0L, "Indian Oil, MG Road", 500.0), {}, {})
        MilestonesCard(listOf(Milestone("New top speed: 118.4 km/h", "Sunday ride · was 112.0 on Saturday"), Milestone("1,000 km ridden", "Across 42 rides with Keppo Moto")))
        val oil = CareItem("1", "b", "Engine oil", everyKm = 3000, doneAtMillis = 0)
        val chain = CareItem("2", "b", "Chain lube", everyKm = 500, doneAtMillis = 0)
        BikeCareCard("Duke 390", 12_480.0, listOf(CareStatus(chain, 1.1f, true, "Due now · 60 km over"), CareStatus(oil, 0.4f, false, "Due in 1,800 km")), {})
    }

    private val report = CrashReport(System.currentTimeMillis(), 18.52, 73.85, 8.0, 14.0, "Duke 390", "Shivam", 64, MedicalInfo("B+", "Penicillin"))
    private val contacts = listOf(EmergencyContact("Mom", "+91 98765 43210"), EmergencyContact("Arjun", "+91 91234 56789"))

    @Test fun crashCountdown() = sheet("crash-countdown") {
        Column(Modifier.background(RtColors.LiveBackground).height(760.dp)) {
            CrashAlertContent(CrashAlertState.Countdown(report, contacts, System.currentTimeMillis() + 21_000), {}, {}, {}, {})
        }
    }

    @Test fun crashSent() = sheet("crash-sent") {
        Column(Modifier.background(RtColors.LiveBackground).height(760.dp)) {
            CrashAlertContent(
                CrashAlertState.Sent(report, contacts, mapOf(contacts[0].phone to SmsStatus.Sent, contacts[1].phone to SmsStatus.Failed("No signal")), "", System.currentTimeMillis(), retrying = true),
                {}, {}, {}, {},
            )
        }
    }

    @Test fun paywall() = sheet("paywall") {
        PaywallContent(
            listOf(PaywallPlan("yearly", "$64.99", 64_990_000, true, 7), PaywallPlan("monthly", "$9.99", 9_990_000, false, 7)),
            "Checking with Google Play…", {}, {}, {},
        )
    }

    private fun hud(two: Boolean) = HudData(
        status = HudStatus.RECORDING, stoppedForMillis = null, speedMps = 23.0, leanDeg = -18.0, leanNote = null,
        distanceM = 42_300.0, elapsedMillis = 3_720_000, avgSpeedMps = 12.0, maxSpeedMps = 31.0, longitudinalG = 0.21,
        combinedG = 0.38, maxLeanDeg = 32.0, headingDeg = 215.0, demo = false, camera = CameraIndicator.ON,
        voiceOn = true, micLevelDb = 11f, twoMics = if (two) TwoMicLevels(-18f, -9f, TwoMicKind.TWO) else null,
    )

    @Test fun hud() = captureRoboImage("build/shots/ui-hud.png") {
        Row(Modifier.background(Color(0xFF7A8A70)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HudLayout.entries.forEach { l ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(l.label, color = Color.White)
                    RideTrackTheme(dark = true) {
                        HudCard(hud(two = true), HudSettings(layout = l))
                        HudCard(hud(two = false), HudSettings(layout = l, theme = HudTheme.HIGH_CONTRAST))
                        // The phone's largest text, as the pop-up window applies it.
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                            com.ridetrack.app.ui.hud.ScaledBy(1f) { HudCard(hud(two = true), HudSettings(layout = l)) }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Bubble", color = Color.White)
                RideTrackTheme(dark = true) { HudBubble(hud(two = true), HudSettings()) }
            }
        }
    }
}
