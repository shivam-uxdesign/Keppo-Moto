package com.ridetrack.app.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.ridetrack.app.routines.Routine
import com.ridetrack.app.routines.RoutineAction
import com.ridetrack.app.routines.RoutinePlace
import com.ridetrack.app.routines.RoutineTrigger
import com.ridetrack.app.ui.profile.RoutineEditor
import com.ridetrack.app.ui.profile.RoutineList
import com.ridetrack.app.ui.theme.RideTrackTheme
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Profile › Routines: the list and a routine open in the editor (build/shots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp-xxhdpi", application = android.app.Application::class)
class RoutineShots {
    private val start = Routine(id = "s", name = "Ride start", triggers = listOf(RoutineTrigger.RIDE_START), actions = listOf(
        RoutineAction.ResumeMusic, RoutineAction.MediaVolume(70), RoutineAction.Wait(10), RoutineAction.OpenMaps,
    ))
    private val home = Routine(
        id = "h", name = "Home", triggers = listOf(RoutineTrigger.RIDE_END, RoutineTrigger.ARRIVE),
        place = RoutinePlace("Home", 28.45, 77.02, 150), actions = listOf(RoutineAction.PauseMusic, RoutineAction.EndRide),
    )

    @Test fun list() = captureRoboImage("build/shots/routines-list.png") {
        RideTrackTheme(dark = true) {
            Column(Modifier.fillMaxSize().background(RtColors.Background).padding(RtDimens.screenPadding)) {
                RoutineList(listOf(start, home.copy(enabled = false)), true, { if (it == "s") "Ran at 18:02 · didn't: Open Maps" else null }, {}, {})
            }
        }
    }

    @Test fun empty() = captureRoboImage("build/shots/routines-empty.png") {
        RideTrackTheme(dark = true) {
            Column(Modifier.fillMaxSize().background(RtColors.Background).padding(RtDimens.screenPadding)) {
                RoutineList(emptyList(), true, { null }, {}, {})
            }
        }
    }

    @Test fun editor() = captureRoboImage("build/shots/routines-editor.png") {
        RideTrackTheme(dark = true) {
            Column(Modifier.fillMaxSize().background(RtColors.Background).verticalScroll(rememberScrollState()).padding(RtDimens.screenPadding)) {
                RoutineEditor(home.copy(actions = home.actions + start.actions), {}, {}, {}, {})
            }
        }
    }
}
