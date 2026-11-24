package com.ridetrack.app.shots

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ridetrack.app.data.EmergencyContact
import com.ridetrack.app.data.HudLayout
import com.ridetrack.app.data.HudSettings
import com.ridetrack.app.data.MedicalInfo
import com.ridetrack.app.hud.CameraIndicator
import com.ridetrack.app.hud.HudData
import com.ridetrack.app.hud.HudStatus
import com.ridetrack.app.hud.HudVideo
import com.ridetrack.app.moments.TwoMicKind
import com.ridetrack.app.moments.TwoMicLevels
import com.ridetrack.app.safety.CrashAlertState
import com.ridetrack.app.safety.CrashReport
import com.ridetrack.app.studio.IdeaCard
import com.ridetrack.app.studio.StudioHomeActions
import com.ridetrack.app.studio.StudioHomeContent
import com.ridetrack.app.studio.StudioRide
import com.ridetrack.app.ui.home.BikeCareCard
import com.ridetrack.app.ui.home.CareItem
import com.ridetrack.app.ui.home.CareStatus
import com.ridetrack.app.ui.home.Milestone
import com.ridetrack.app.ui.home.MilestonesCard
import com.ridetrack.app.ui.home.MomentsToggles
import com.ridetrack.app.ui.home.Readiness
import com.ridetrack.app.ui.home.ReadinessCard
import com.ridetrack.app.ui.home.ReadyInputs
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
 * Screens for the portfolio case study (docs/plans/case-study.md), one phone frame each at
 * 390 × 844 dp, saved in build/shots/case-study. Sample data only (no real names or numbers).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class CaseStudyShots {
    private fun frame(name: String, live: Boolean = false, content: @Composable () -> Unit) =
        captureRoboImage("build/shots/case-study/$name.png") {
            RideTrackTheme(dark = true) {
                Column(
                    Modifier.fillMaxSize().background(if (live) RtColors.LiveBackground else RtColors.Background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }

    @Test fun home() = frame("home-before-you-ride") {
        Text("Before you ride", color = RtColors.TextPrimary, fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp), modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
        ReadinessCard(Readiness.checks(ReadyInputs(crashOn = true, contacts = 2, overlayAllowed = true, hudOn = true, batteryPct = 86)), {})
        MomentsToggles(moments = true, voice = true, onMoments = {}, onVoice = {}, keepMoving = true, onKeepMoving = {})
        MilestonesCard(listOf(Milestone("New top speed: 118.4 km/h", "Sunday ride · was 112.0 on Saturday"), Milestone("1,000 km ridden", "Across 42 rides with Keppo Moto")))
        val oil = CareItem("1", "b", "Engine oil", everyKm = 3000, doneAtMillis = 0)
        val chain = CareItem("2", "b", "Chain lube", everyKm = 500, doneAtMillis = 0)
        BikeCareCard("Duke 390", 12_480.0, listOf(CareStatus(chain, 0.86f, false, "Due in 70 km"), CareStatus(oil, 0.4f, false, "Due in 1,800 km")), {})
    }

    private fun hud(filming: Boolean, gpsLost: Boolean = false) = HudData(
        status = if (gpsLost) HudStatus.GPS_LOST else HudStatus.RECORDING, stoppedForMillis = null, speedMps = 16.4, leanDeg = 15.0, leanNote = null,
        distanceM = 18_300.0, elapsedMillis = 1_274_000, avgSpeedMps = 9.0, maxSpeedMps = 24.0, longitudinalG = 0.18,
        combinedG = 0.31, maxLeanDeg = 28.0, headingDeg = 40.0, demo = false,
        camera = if (filming) CameraIndicator.REC else CameraIndicator.ON,
        video = if (filming) HudVideo(com.ridetrack.app.moments.MomentSource.MANUAL, starting = false, paused = false, elapsedMillis = 134_000, back = true) else null,
        voiceOn = true, micLevelDb = if (filming) 17f else 6f, speaking = filming,
        twoMics = TwoMicLevels(if (filming) -14f else -32f, -20f, TwoMicKind.TWO), gear = 3, rpm = 6_200.0, redlineRpm = 10_500,
    )

    /** The floating HUD over a map-dark backdrop, riding and filming. */
    @Test fun hud() = captureRoboImage("build/shots/case-study/hud.png") {
        RideTrackTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0D1117)), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HudCard(hud(filming = false), HudSettings(layout = HudLayout.SPORT))
                    HudCard(hud(filming = true), HudSettings(layout = HudLayout.SPORT))
                }
            }
        }
    }

    private val report = CrashReport(System.currentTimeMillis(), 28.55, 77.20, 8.0, 14.0, "Duke 390", "Rider", 64, MedicalInfo("B+", ""))
    private val contacts = listOf(EmergencyContact("Mum", "+91 90000 00001"), EmergencyContact("Arjun", "+91 90000 00002"))

    @Test fun crash() = captureRoboImage("build/shots/case-study/crash-countdown.png") {
        RideTrackTheme(dark = true) {
            Column(Modifier.fillMaxSize().background(RtColors.LiveBackground)) {
                CrashAlertContent(CrashAlertState.Countdown(report, contacts, System.currentTimeMillis() + 21_000), {}, {}, {}, {})
            }
        }
    }

    @Test fun paywall() = frame("paywall") {
        PaywallContent(
            listOf(PaywallPlan("yearly", "₹1,999", 1_999_000_000, true, 7), PaywallPlan("monthly", "₹249", 249_000_000, false, 7)),
            null, {}, {}, {},
        )
    }

    private val none = object : StudioHomeActions {
        override fun hide(rideId: String, title: String) = Unit
        override fun unhide(rideId: String, title: String) = Unit
        override fun makeAll(rideId: String, done: (Int) -> Unit) = Unit
        override fun sendAllToJournal(rideId: String, done: (Int) -> Unit) = Unit
        override fun picked(uris: List<Uri>, openRide: (String) -> Unit, openPhone: () -> Unit) = Unit
        override fun findIdeas(rideId: String, say: (String) -> Unit) = Unit
        override fun ask(rideId: String, text: String, say: (String) -> Unit, written: (String) -> Unit) = Unit
    }

    @Test fun studio() = captureRoboImage("build/shots/case-study/studio-feed.png") {
        val now = 1_763_000_000_000L
        val rides = listOf(
            StudioRide(
                id = "r1", name = "Sunday at Nahan", startMillis = now, endMillis = now + 3_600_000, distanceM = 212_000.0, clips = 71, talking = 24, thumbs = emptyList(),
                ideas = listOf(
                    IdeaCard("Paani Nahi Piya", "Reel", "Three hours, no water: your words carry it, the flyover at sunset ends it.", 18, null, made = false, fromClips = false),
                    IdeaCard("Tooooo!", "Short", "The best reaction of the ride, looped.", 8, null, made = true, fromClips = false),
                ),
            ),
            StudioRide(id = "r2", name = "Lonavala loop", startMillis = now - 172_800_000, endMillis = now - 165_600_000, distanceM = 120_000.0, clips = 40, talking = 9, thumbs = emptyList()),
        )
        RideTrackTheme(dark = true) {
            StudioHomeContent(none, rides, emptySet(), 3, {}, {}, {}, {}, {}, {}, { _, _, _ -> })
        }
    }
}
