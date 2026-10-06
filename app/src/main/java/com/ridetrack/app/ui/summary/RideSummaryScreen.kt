package com.ridetrack.app.ui.summary

import com.ridetrack.app.ui.theme.LocalRtPalette
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.AnimatedCheck
import com.ridetrack.app.ui.components.CountUpText
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.HairlineDivider
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RouteMap
import com.ridetrack.app.ui.components.MapPin
import com.ridetrack.app.ui.moments.MomentStrip
import com.ridetrack.app.ui.moments.momentColor
import com.ridetrack.app.ui.moments.rememberMoments
import com.ridetrack.app.ui.components.Stat
import com.ridetrack.app.ui.components.StatRow
import com.ridetrack.app.ui.components.riseIn
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.pressScale
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride

@Composable
fun RideSummaryScreen(rideId: String, onDone: () -> Unit, onOpenDetail: () -> Unit, onShare: () -> Unit, onOpenMoment: (String) -> Unit, onMakeReel: () -> Unit = {}) {
    val rt = LocalRtPalette.current
    val moments = rememberMoments(rideId)
    val vm = appViewModel(key = "summary-$rideId") { RideSummaryViewModel(it, rideId) }
    val s by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = RtDimens.screenPaddingWide),
    ) {
        val ride = s.ride
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedCheck()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ride saved", style = RtType.bodyStrong, color = RtColors.Primary)
                if (ride != null) Text(timeRange(ride), style = RtType.caption, color = RtColors.TextSecondary)
            }
            TextButton(onClick = onDone) { Text("Done", style = RtType.body, color = RtColors.TextPrimary) }
        }
        if (ride == null) {
            if (!s.loading) EmptyState("Ride not found", "This ride may have been deleted.")
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(26.dp))
            Column(Modifier.riseIn(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ride.name, style = RtType.title, color = RtColors.TextPrimary, modifier = Modifier.weight(1f, fill = false))
                    if (ride.source == DataSourceKind.DEMO) {
                        Spacer(Modifier.width(10.dp))
                        DemoBadge()
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp)) {
                    CountUpText(ride.stats.distanceM / 1000.0, 1, RtType.display)
                    Text(" km", style = RtType.headline, color = RtColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            StatRow(
                listOf(
                    Stat("Time", Format.duration(ride.durationMillis?.let { (it - ride.stats.breakMillis).coerceAtLeast(0) })),
                    Stat("Avg", Format.speedKmh(ride.stats.avgSpeedMps), "km/h"),
                    Stat("Max", Format.speedKmh(ride.stats.maxSpeedMps), "km/h"),
                ),
                modifier = Modifier.riseIn(1),
            )
            if (ride.stats.breakMillis >= 60_000) {
                Text(
                    "Plus ${Format.duration(ride.stats.breakMillis)} on breaks",
                    style = RtType.caption,
                    color = RtColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp).riseIn(1),
                )
            }

            Spacer(Modifier.height(26.dp))
            RouteMap(
                s.route,
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .riseIn(2),
                animateDraw = true,
                pins = moments.mapNotNull { m -> if (m.latitude != null && m.longitude != null) MapPin(m.id, m.latitude, m.longitude, m.thumb, momentColor(m)) else null },
                onPinClick = onOpenMoment,
            )
            if (moments.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                MomentStrip(moments, onOpen = onOpenMoment, modifier = Modifier.riseIn(3))
            }

            Spacer(Modifier.height(26.dp))
            Column(Modifier.riseIn(3)) {
                Label("Ride dynamics")
                Spacer(Modifier.height(6.dp))
                DynamicsRow("Max lean") {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = rt.left)) { append(Format.lean(ride.stats.maxLeftLeanDeg?.let { -it })) }
                        withStyle(SpanStyle(color = rt.textTertiary)) { append("  ·  ") }
                        withStyle(SpanStyle(color = rt.right)) { append(Format.lean(ride.stats.maxRightLeanDeg)) }
                    }
                }
                HairlineDivider()
                DynamicsRow("Acceleration / braking") {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = rt.accel)) { append(Format.gSigned(ride.stats.maxAccelG)) }
                        withStyle(SpanStyle(color = rt.textTertiary)) { append("  /  ") }
                        withStyle(SpanStyle(color = rt.brake)) { append(Format.gSigned(ride.stats.maxBrakeG)) }
                    }
                }
                HairlineDivider()
                DynamicsRow("Turns · brakes · stops") {
                    buildAnnotatedString {
                        append("${ride.stats.leftTurns + ride.stats.rightTurns} · ${ride.stats.brakeEvents} · ${ride.stats.stopCount}")
                    }
                }
                Text(
                    "Lean and G-force are estimated from phone sensors and are not certified measurements.",
                    style = RtType.caption,
                    color = RtColors.TextTertiary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(RtDimens.buttonHeight + 4.dp)
                    .pressScale(interaction)
                    .clip(CircleShape)
                    .background(RtColors.Surface)
                    .border(1.dp, RtColors.Hairline, CircleShape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onShare() }
                    .semantics { contentDescription = "Share ride" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, tint = RtColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(RtDimens.buttonHeight + 4.dp)
                    .clip(CircleShape)
                    .background(RtColors.Surface)
                    .border(1.dp, RtColors.Hairline, CircleShape)
                    .clickable(role = Role.Button) { onMakeReel() }
                    .semantics { contentDescription = "Make a Reel" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Movie, contentDescription = null, tint = RtColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            PrimaryButton(
                "View details", onOpenDetail,
                modifier = Modifier.weight(1f),
                large = true,
                color = RtColors.Inverse,
                contentColor = RtColors.OnInverse,
            )
        }
    }
}

@Composable
private fun DynamicsRow(label: String, value: () -> androidx.compose.ui.text.AnnotatedString) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RtType.body, color = RtColors.TextSecondary)
        Text(value(), style = RtType.body.copy(fontFeatureSettings = "tnum"), color = RtColors.TextPrimary)
    }
}

private fun timeRange(ride: Ride): String {
    val end = ride.endTimeMillis
    val start = Format.rideDate(ride.startTimeMillis)
    return if (end != null) "$start – ${Format.timeOfDay(end)}" else start
}
