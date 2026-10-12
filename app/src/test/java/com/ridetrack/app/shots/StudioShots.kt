package com.ridetrack.app.shots

import android.net.Uri
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import com.ridetrack.app.studio.IdeaCard
import com.ridetrack.app.studio.StudioHomeActions
import com.ridetrack.app.studio.StudioHomeContent
import com.ridetrack.app.studio.StudioRide
import com.ridetrack.app.ui.theme.RideTrackTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws Studio's screens with sample rides, to compare with the prototype (build/shots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class StudioShots {
    private val none = object : StudioHomeActions {
        override fun hide(rideId: String, title: String) = Unit
        override fun unhide(rideId: String, title: String) = Unit
        override fun makeAll(rideId: String, done: (Int) -> Unit) = Unit
        override fun sendAllToJournal(rideId: String, done: (Int) -> Unit) = Unit
        override fun picked(uris: List<Uri>, openRide: (String) -> Unit, openPhone: () -> Unit) = Unit
    }

    private val day = 86_400_000L
    private val now = 1_760_000_000_000L
    private val rides = listOf(
        StudioRide(
            id = "r1", name = "Tuesday evening ride", startMillis = now, endMillis = now + 3_600_000, distanceM = 29_800.0, clips = 71, talking = 24, thumbs = emptyList(),
            ideas = listOf(
                IdeaCard("Paani Nahi Piya", "Reel", "Three hours, no water: your words carry it, the flyover at sunset ends it.", 18, null, made = false, fromClips = false),
                IdeaCard("Tooooo!", "Short", "The best reaction of the ride, looped.", 8, null, made = true, fromClips = false),
            ),
        ),
        StudioRide(id = "r2", name = "Lonavala Sunday", startMillis = now - 2 * day, endMillis = now - 2 * day + 7_200_000, distanceM = 120_000.0, clips = 40, talking = 9, thumbs = emptyList()),
    )

    @Composable
    private fun Home(dark: Boolean) = RideTrackTheme(dark = dark) {
        StudioHomeContent(none, rides, emptySet(), 3, {}, {}, {}, {}, {}, {}, { _, _, _ -> })
    }

    @Test fun home() = captureRoboImage("build/shots/home-dark.png") { Home(true) }

    @Test fun homeLight() = captureRoboImage("build/shots/home-light.png") { Home(false) }
}
