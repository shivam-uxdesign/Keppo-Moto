package com.ridetrack.app.ui.common

import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.GpsOff
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SensorsOff
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType
import kotlin.math.abs
import kotlin.math.roundToInt

data class EventPresentation(val title: String, val detail: String?, val icon: ImageVector, val color: Color)

/** Icon + colour + text for each event, so nothing relies on colour alone. */
@Composable
@ReadOnlyComposable
fun RideEvent.presentation(): EventPresentation {
    val speed = speedMps?.let { Format.speedWithUnit(it) }
    return when (type) {
        RideEventType.START -> EventPresentation("Ride started", null, Icons.Outlined.PlayCircle, RtColors.Ok)
        RideEventType.END -> EventPresentation("Ride ended", null, Icons.Outlined.Flag, RtColors.TextPrimary)
        RideEventType.STOP -> EventPresentation("Stopped", null, Icons.Outlined.PauseCircle, RtColors.Paused)
        RideEventType.HARD_BRAKE -> EventPresentation(
            "Hard brake", listOfNotNull(Format.gSigned(value), speed?.let { "at $it" }).joinToString(" · "),
            Icons.AutoMirrored.Outlined.TrendingDown, RtColors.Brake,
        )
        RideEventType.STRONG_ACCELERATION -> EventPresentation(
            "Strong acceleration", listOfNotNull(Format.gSigned(value), speed?.let { "from $it" }).joinToString(" · "),
            Icons.AutoMirrored.Outlined.TrendingUp, RtColors.Accel,
        )
        RideEventType.SIGNIFICANT_LEAN -> EventPresentation(
            "Significant lean", listOfNotNull(Format.lean(value), speed).joinToString(" · "),
            Icons.Outlined.TwoWheeler, if ((value ?: 0.0) < 0) RtColors.Left else RtColors.Right,
        )
        RideEventType.LEFT_TURN -> EventPresentation("Left turn", value?.let { "${abs(it).roundToInt()}°" }, Icons.Outlined.TurnLeft, RtColors.Left)
        RideEventType.RIGHT_TURN -> EventPresentation("Right turn", value?.let { "${abs(it).roundToInt()}°" }, Icons.Outlined.TurnRight, RtColors.Right)
        RideEventType.SHARP_DIRECTION_CHANGE -> EventPresentation(
            "Sharp direction change", listOfNotNull(value?.let { "${abs(it).roundToInt()}°" }, speed).joinToString(" · "),
            Icons.AutoMirrored.Outlined.AltRoute, RtColors.Warning,
        )
        RideEventType.GPS_SIGNAL_LOST -> EventPresentation("GPS signal lost", null, Icons.Outlined.GpsOff, RtColors.Warning)
        RideEventType.GPS_SIGNAL_RESTORED -> EventPresentation("GPS signal restored", null, Icons.Outlined.GpsFixed, RtColors.TextSecondary)
        RideEventType.SENSOR_DEGRADED -> EventPresentation("Motion sensor accuracy degraded", null, Icons.Outlined.SensorsOff, RtColors.Warning)
        RideEventType.BREAK_START -> EventPresentation("Break", null, Icons.Outlined.LocalCafe, RtColors.Paused)
        RideEventType.BREAK_END -> EventPresentation("Back on the bike", null, Icons.Outlined.PlayCircle, RtColors.Ok)
        RideEventType.MANUAL_PAUSE -> EventPresentation("Paused", null, Icons.Outlined.PauseCircle, RtColors.Warning)
        RideEventType.MANUAL_RESUME -> EventPresentation("Resumed", null, Icons.Outlined.PlayCircle, RtColors.Ok)
    }
}

val RideEventType.isTurn: Boolean get() = this == RideEventType.LEFT_TURN || this == RideEventType.RIGHT_TURN
