package com.ridetrack.app.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.RideMark
import com.ridetrack.app.studio.SpeedPanel
import com.ridetrack.app.studio.SpeedPoints
import com.ridetrack.app.ui.theme.RideTrackTheme
import com.ridetrack.app.ui.theme.RtColors
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The editor's Speed panel with a ride ramp (build/shots), to check how it reads. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class EditorPanelShots {
    @Test
    fun speedRamp() {
        val bit = Bit("a#0", "a", 20_000, 0, 20_000, 1_000_000, emptyList(), 40.0, 3f)
        val marks = listOf(RideMark(RideMark.Kind.LEAN, 10_000, "Lean 38°"), RideMark(RideMark.Kind.DOWNSHIFT, 7_000, "Downshift"))
        val pts = SpeedPoints.preset(SpeedPoints.Preset.LEAN, marks, 5_000, 10_000)!!
        val seg = ClipSegment(bit, 5_000, 12_000, emptyList(), hook = true, speedPoints = pts)
        captureRoboImage("build/shots/editor-speed-ramp.png") {
            RideTrackTheme(dark = true) {
                Column(Modifier.fillMaxWidth().background(RtColors.Background)) {
                    SpeedPanel(seg, fromMs = 10_000, onSpeed = {}, onSpeedEnd = {}, onRamp = {}, onReverse = {}, onFreeze = {}, onMute = {}, onBack = {}, marks = marks, playFraction = 0.4f)
                }
            }
        }
    }
}
