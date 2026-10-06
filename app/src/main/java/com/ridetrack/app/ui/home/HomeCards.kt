package com.ridetrack.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RouteThumbnail
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlin.math.abs

/**
 * Will everything record? One quiet line when it will; otherwise each problem with its fix,
 * and the other checks as chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadinessCard(checks: List<ReadyCheck>, onFix: (ReadyFix) -> Unit, modifier: Modifier = Modifier) {
    if (checks.isEmpty()) return
    val problems = checks.filter { it.state == CheckState.PROBLEM }
    if (problems.isEmpty()) {
        Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).background(RtColors.Ok.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = RtColors.Ok, modifier = Modifier.size(13.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(
                Readiness.summary(checks),
                style = RtType.caption,
                color = RtColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    RtCard(modifier) {
        Label("Before you ride")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            problems.forEach { p ->
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(RtColors.Error.copy(alpha = 0.08f))
                        .border(1.dp, RtColors.Error.copy(alpha = 0.25f), shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(p.problem ?: p.label, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    p.detail?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
                    val fix = p.fix
                    if (fix != null && p.fixLabel != null) {
                        Text(
                            p.fixLabel,
                            style = RtType.bodyStrong,
                            color = RtColors.Primary,
                            modifier = Modifier.padding(top = 4.dp).clickable(role = Role.Button) { onFix(fix) },
                        )
                    }
                }
            }
        }
        val rest = checks - problems.toSet()
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rest.forEach { CheckChip(it) }
            }
        }
    }
}

@Composable
private fun CheckChip(c: ReadyCheck) {
    val ok = c.state == CheckState.OK
    Row(
        Modifier
            .background(RtColors.SurfaceRaised, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ok) "✓" else "…", style = RtType.caption, color = if (ok) RtColors.Ok else RtColors.Warning)
        Spacer(Modifier.width(5.dp))
        Text(c.label, style = RtType.caption, color = if (ok) RtColors.TextSecondary else RtColors.Warning)
    }
}

/** A stop at a petrol pump after a ride: was it a fill-up? */
@Composable
fun FuelPromptCard(prompt: com.ridetrack.app.fuel.FuelPrompt, onAdd: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    RtCard(modifier) {
        Label("Fuel")
        Text(
            "Filled up at ${prompt.station}?",
            style = RtType.bodyStrong,
            color = RtColors.TextPrimary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            Format.timeOfDay(prompt.timeMillis) + (prompt.amount?.let { " · ${com.ridetrack.app.fuel.Fuel.money(it)} on your card" } ?: ""),
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Add fill-up", onAdd, Modifier.weight(1f))
            SecondaryButton("Not a fill-up", onDismiss, Modifier.weight(1f))
        }
    }
}

/** Quick switches above Start: Moments, and "Film when I speak" while Moments is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MomentsToggles(moments: Boolean, voice: Boolean, onMoments: (Boolean) -> Unit, onVoice: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip("Moments", moments, Icons.Outlined.Videocam) { onMoments(!moments) }
        if (moments) ToggleChip("Film when I speak", voice, Icons.Outlined.Mic) { onVoice(!voice) }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (on) RtColors.Primary.copy(alpha = 0.16f) else RtColors.Surface)
            .border(1.dp, if (on) RtColors.Primary.copy(alpha = 0.5f) else RtColors.Hairline, shape)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) RtColors.Primary else RtColors.TextTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = RtType.caption, color = if (on) RtColors.TextPrimary else RtColors.TextSecondary)
        Spacer(Modifier.width(6.dp))
        Text(if (on) "On" else "Off", style = RtType.caption, color = if (on) RtColors.Primary else RtColors.TextTertiary)
    }
}

/**
 * The latest ride, compact: route thumbnail on the left, name and details on the right, the
 * numbers underneath. [justRode] adds Share and Open, and a close.
 */
@Composable
fun LastRideCard(
    last: LastRide,
    bikeName: String?,
    /** "≈0.6 L · ₹57", once the fuel log knows the bike's mileage. */
    fuelLine: String?,
    /** The bike's route colour. */
    routeColor: androidx.compose.ui.graphics.Color,
    justRode: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReel: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val r = last.ride
    RtCard(modifier, onClick = if (justRode && last.moments.size < 2) null else onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(RtColors.SurfaceRaised),
            ) {
                RouteThumbnail(last.route, Modifier.fillMaxSize().padding(8.dp), color = routeColor)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(if (justRode) "Just now" else "Last ride", Modifier.weight(1f))
                    if (justRode) {
                        Icon(
                            Icons.Outlined.Close, contentDescription = "Close",
                            tint = RtColors.TextTertiary,
                            modifier = Modifier.size(18.dp).clickable(role = Role.Button, onClick = onClose),
                        )
                    }
                }
                Text(
                    r.name,
                    style = RtType.bodyStrong,
                    color = RtColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(Format.rideDate(r.startTimeMillis), style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1)
                val details = listOfNotNull(
                    bikeName,
                    fuelLine,
                    last.moments.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "moment" else "moments"}" },
                ).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(details, style = RtType.caption, color = RtColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val lean = listOfNotNull(r.stats.maxLeftLeanDeg, r.stats.maxRightLeanDeg).maxOfOrNull { abs(it) }
        Row(Modifier.padding(top = 14.dp)) {
            Num(Format.distanceValue(r.stats.distanceM), "km")
            Num(Format.duration(r.stats.movingMillis), "riding")
            Num(Format.speedKmh(r.stats.maxSpeedMps), "top km/h")
            Num(Format.leanMagnitude(lean), "max lean")
        }
        if (justRode) {
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // With clips, the Reel leads; the card itself still opens the ride.
                if (last.moments.size >= 2) {
                    PrimaryButton("Make a Reel", onReel, Modifier.weight(1f), icon = Icons.Outlined.Movie)
                    SecondaryButton("Share ride", onShare, Modifier.weight(1f))
                } else {
                    PrimaryButton("Share ride", onShare, Modifier.weight(1f), icon = Icons.Outlined.IosShare)
                    SecondaryButton("Open ride", onOpen, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Num(value: String, label: String) {
    Column(Modifier.weight(1f)) {
        Text(value, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1)
        Text(label, style = RtType.caption, color = RtColors.TextTertiary, maxLines = 1)
    }
}

/** Odometer and the rider's reminders for the selected bike, most due first. */
@Composable
fun BikeCareCard(bikeName: String, odometerKm: Double?, care: List<CareStatus>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    RtCard(modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Bike care", Modifier.weight(1f))
            Text(
                listOfNotNull(bikeName, odometerKm?.let { BikeCare.km(it) }).joinToString(" · "),
                style = RtType.caption,
                color = RtColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (care.isEmpty()) {
            Text(
                "Set reminders for chain lube, oil and service. They count down with every ride.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@RtCard
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            care.take(3).forEach { CareRow(it) }
        }
    }
}

@Composable
fun CareRow(st: CareStatus, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).background(RtColors.SurfaceRaised, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Build, contentDescription = null, tint = if (st.due) RtColors.Warning else RtColors.TextSecondary, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(st.item.name, style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(st.text, style = RtType.caption, color = if (st.due) RtColors.Warning else RtColors.TextSecondary)
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(RtColors.SurfaceRaised),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(st.used.coerceIn(0.02f, 1f))
                        .height(5.dp)
                        .background(if (st.used >= 0.8f) RtColors.Warning else RtColors.Primary, RoundedCornerShape(3.dp)),
                )
            }
        }
        trailing()
    }
}

@Composable
fun MilestonesCard(milestones: List<Milestone>, modifier: Modifier = Modifier) {
    if (milestones.isEmpty()) return
    RtCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            milestones.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).background(RtColors.Warning.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Star, contentDescription = null, tint = RtColors.Warning, modifier = Modifier.size(17.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(m.title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                        Text(m.detail, style = RtType.caption, color = RtColors.TextSecondary)
                    }
                }
            }
        }
    }
}

/** "This week  1 ride · 29.8 km · 44 min". */
@Composable
fun WeekLine(week: PeriodStats, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("This week", style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(
            if (week.rides == 0) "No rides yet"
            else "${week.rides} ${if (week.rides == 1) "ride" else "rides"} · ${Format.distance(week.distanceM)} · ${Format.duration(week.movingMillis)}",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
    }
}
