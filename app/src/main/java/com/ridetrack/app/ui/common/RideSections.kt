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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
 * Ride dynamics on one line: lean (left / right), hardest braking, strongest acceleration and
 * top speed. Each entry with a known moment is tappable to show it on the timeline; lean
 * finds the left extreme first, then the right.
 */
@Composable
fun RideDynamics(
    ride: Ride,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onBrake: (() -> Unit)? = null,
    onAccel: (() -> Unit)? = null,
    onTopSpeed: (() -> Unit)? = null,
) {
    val s = ride.stats
    var nextRight by remember { mutableStateOf(false) }
    SectionHeader("Ride dynamics") {
        if (listOf(onLeft, onRight, onBrake, onAccel, onTopSpeed).any { it != null }) {
            Text("Tap to find it", style = RtType.caption, color = RtColors.TextTertiary)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val left = s.maxLeftLeanDeg?.roundToInt()
        val right = s.maxRightLeanDeg?.roundToInt()
        val lean = if (left == null && right == null) null else buildAnnotatedString {
            withStyle(SpanStyle(color = if (left != null) RtColors.Left else RtColors.TextTertiary)) { append(left?.let { "$it°L" } ?: Format.DASH) }
            withStyle(SpanStyle(color = RtColors.TextTertiary)) { append("/") }
            withStyle(SpanStyle(color = if (right != null) RtColors.Right else RtColors.TextTertiary)) { append(right?.let { "$it°R" } ?: Format.DASH) }
        }
        val onLean = when {
            onLeft != null && onRight != null -> { { if (nextRight) onRight() else onLeft(); nextRight = !nextRight } }
            else -> onLeft ?: onRight
        }
        DynamicsEntry("Lean", lean, "", RtColors.Left, onLean, Modifier.weight(1.4f))
        DynamicsEntry("Braking", s.maxBrakeG?.let { AnnotatedString(String.format(Locale.US, "%.2f", kotlin.math.abs(it))) }, " G", RtColors.Brake, onBrake, Modifier.weight(1f))
        DynamicsEntry("Accel", s.maxAccelG?.let { AnnotatedString(String.format(Locale.US, "%.2f", kotlin.math.abs(it))) }, " G", RtColors.Accel, onAccel, Modifier.weight(1f))
        DynamicsEntry("Top speed", s.maxSpeedMps?.let { AnnotatedString(Format.speedKmh(it)) }, " km/h", RtColors.GForce, onTopSpeed, Modifier.weight(1f))
    }
    Spacer(Modifier.height(RtDimens.xs))
    Text(
        "Lean and G-force are estimated from phone sensors and are not certified measurements.",
        style = RtType.caption,
        color = RtColors.TextTertiary,
    )
}

@Composable
private fun DynamicsEntry(label: String, value: AnnotatedString?, unit: String, color: Color, onClick: (() -> Unit)?, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .height(74.dp)
            .clip(shape)
            .background(RtColors.Surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show on timeline", onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp)
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
            Text(value ?: AnnotatedString(Format.DASH), style = RtType.metricS.copy(fontSize = 17.sp, fontWeight = FontWeight.Light), color = if (value != null) color else RtColors.TextTertiary, maxLines = 1)
            if (value != null) Text(unit, style = RtType.caption.copy(fontSize = 10.sp), color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}
