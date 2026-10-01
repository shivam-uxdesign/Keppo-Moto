package com.ridetrack.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.LiveMetric
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.InfoRow
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.components.StatTile
import com.ridetrack.app.ui.components.TwoColumn
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onOpenHudSettings: () -> Unit) {
    val vm = appViewModel { ProfileViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader("Profile")

        SectionHeader("Statistics")
        val t = s.totals
        if (t == null) {
            if (!s.loading) {
                EmptyState(
                    "No statistics yet",
                    "Your totals will appear here after your first recorded ride. Demo rides are not counted.",
                    icon = Icons.Outlined.QueryStats,
                )
            }
        } else {
            TwoColumn(
                left = { StatTile("Total rides", t.rideCount.toString(), it) },
                right = { StatTile("Total distance", Format.distanceValue(t.distanceM), it, unit = "km") },
            )
            Spacer(Modifier.height(RtDimens.cardSpacing))
            TwoColumn(
                left = { StatTile("Riding time", Format.duration(t.movingMillis), it) },
                right = { StatTile("Longest ride", t.longestRideM?.let(Format::distanceValue) ?: Format.DASH, it, unit = "km") },
            )
            Spacer(Modifier.height(RtDimens.cardSpacing))
            TwoColumn(
                left = { StatTile("Max speed", Format.speedKmh(t.maxSpeedMps), it, unit = "km/h") },
                right = { StatTile("Total stops", t.totalStops.toString(), it) },
            )
            Spacer(Modifier.height(RtDimens.cardSpacing))
            RtCard {
                InfoRow("Distance this month", Format.distance(t.distanceThisMonthM))
                Line()
                InfoRow("Rides this month", t.ridesThisMonth.toString())
                Line()
                InfoRow("Average ride distance", Format.distanceOrDash(t.averageRideM))
            }
        }

        SectionHeader("Riding")
        RtCard {
            ToggleRow(
                "Auto pause",
                "Show the ride as paused when you've been stopped for a few seconds. The ride never ends on its own.",
                s.settings.autoPause,
                vm::setAutoPause,
            )
            Line()
            ToggleRow("G-force indicator", "Show the G-force dot on the live ride screen.", s.settings.showGForceIndicator, vm::setGIndicator)
            Line()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenHudSettings)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Pop-up HUD", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text(
                        if (s.settings.hud.enabled) "On · ${s.settings.hud.layout.label}" else "Off",
                        style = RtType.caption,
                        color = RtColors.TextSecondary,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = RtColors.TextSecondary)
            }
            Line()
            Spacer(Modifier.height(RtDimens.sm))
            Label("Live ride metrics")
            Spacer(Modifier.height(RtDimens.xxs))
            Text(
                "Choose up to ${LiveMetric.MAX_VISIBLE} extra metrics. Speed, lean, distance, average speed and ride time are always shown.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Spacer(Modifier.height(RtDimens.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs), verticalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                LiveMetric.entries.forEach { m ->
                    val selected = m in s.settings.liveMetrics
                    FilterChip(
                        selected = selected,
                        onClick = { vm.toggleMetric(m) },
                        enabled = selected || s.settings.liveMetrics.size < LiveMetric.MAX_VISIBLE,
                        label = { Text(m.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f),
                            selectedLabelColor = RtColors.Primary,
                            labelColor = RtColors.TextSecondary,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(RtDimens.sm))
        }

        SafetySection(s, vm)

        MomentsSection(s, vm)

        SectionHeader("Developer")
        RtCard {
            ToggleRow(
                "Demo mode",
                if (s.rideActive) "Can't be changed during a ride."
                else "Record simulated rides to try the app without riding. Demo rides are labelled DEMO and excluded from your statistics.",
                s.settings.demoMode,
                vm::setDemoMode,
                enabled = !s.rideActive,
            )
            if (s.settings.demoMode) {
                ToggleRow(
                    "Simulated OBD",
                    "Demo rides also send engine RPM and gear, so you can try the rev meter on the ride screen and pop-up.",
                    s.settings.demoObd,
                    vm::setDemoObd,
                    enabled = !s.rideActive,
                )
            }
        }

        if (BuildConfig.DEBUG) BetaExportSection()

        SectionHeader("Privacy")
        RtCard {
            Text(
                "Your rides are stored only on this phone. Ride Track has no account and no cloud sync, and recording works fully offline. " +
                    "Map backgrounds are downloaded from OpenStreetMap/CARTO tile servers when you view a map.",
                style = RtType.body,
                color = RtColors.TextSecondary,
            )
            Spacer(Modifier.height(RtDimens.sm))
            Text(
                "Lean angle and G-force are estimates from phone sensors, not certified measurements. Crash detection is a best-effort aid: it can miss a crash or raise a false alarm, and it doesn't replace calling emergency services.",
                style = RtType.body,
                color = RtColors.TextSecondary,
            )
        }

        SectionHeader("About")
        RtCard {
            InfoRow("Version", BuildConfig.VERSION_NAME)
        }
        Spacer(Modifier.height(RtDimens.lg))
    }
}

@Composable
internal fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RtType.bodyStrong, color = if (enabled) RtColors.TextPrimary else RtColors.TextTertiary)
            Text(description, style = RtType.caption, color = RtColors.TextSecondary)
        }
        Spacer(Modifier.width(RtDimens.md))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = RtColors.Primary, checkedThumbColor = RtColors.OnPrimary),
        )
    }
}

@Composable
private fun Line() = HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))
