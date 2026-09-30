package com.ridetrack.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.Ride
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ride dynamics on one line: lean left, lean right, hardest braking, strongest acceleration.
 * Each entry with a known moment is tappable ([onLeft] etc.) to show it on the timeline.
 */
@Composable
fun RideDynamics(
    ride: Ride,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onBrake: (() -> Unit)? = null,
    onAccel: (() -> Unit)? = null,
) {
    val s = ride.stats
    SectionHeader("Ride dynamics") {
        if (listOf(onLeft, onRight, onBrake, onAccel).any { it != null }) {
            Text("Tap to see it on the timeline", style = RtType.caption, color = RtColors.TextTertiary)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DynamicsEntry("Lean left", s.maxLeftLeanDeg?.let { "${it.roundToInt()}" }, "°", RtColors.Left, onLeft, Modifier.weight(1f))
        DynamicsEntry("Lean right", s.maxRightLeanDeg?.let { "${it.roundToInt()}" }, "°", RtColors.Right, onRight, Modifier.weight(1f))
        DynamicsEntry("Braking", s.maxBrakeG?.let { String.format(Locale.US, "%.2f", kotlin.math.abs(it)) }, " G", RtColors.Brake, onBrake, Modifier.weight(1f))
        DynamicsEntry("Accel", s.maxAccelG?.let { String.format(Locale.US, "%.2f", kotlin.math.abs(it)) }, " G", RtColors.Accel, onAccel, Modifier.weight(1f))
    }
    Spacer(Modifier.height(RtDimens.xs))
    Text(
        "Lean and G-force are estimated from phone sensors and are not certified measurements.",
        style = RtType.caption,
        color = RtColors.TextTertiary,
    )
}

@Composable
private fun DynamicsEntry(label: String, value: String?, unit: String, color: Color, onClick: (() -> Unit)?, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .height(74.dp)
            .clip(shape)
            .background(RtColors.Surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show on timeline", onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(label, style = RtType.caption.copy(fontSize = 10.sp), color = RtColors.TextSecondary, maxLines = 1)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value ?: Format.DASH, style = RtType.metricS.copy(fontSize = 21.sp, fontWeight = FontWeight.Light), color = if (value != null) color else RtColors.TextTertiary, maxLines = 1)
            if (value != null) Text(unit, style = RtType.caption.copy(fontSize = 10.sp), color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}
