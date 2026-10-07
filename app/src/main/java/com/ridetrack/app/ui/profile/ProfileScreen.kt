package com.ridetrack.app.ui.profile

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.AppTheme
import com.ridetrack.app.data.LiveMetric
import com.ridetrack.app.data.PhotoInterval
import com.ridetrack.app.data.Settings
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.InfoRow
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.components.StatTile
import com.ridetrack.app.ui.components.TwoColumn
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

/** The pages behind Profile's rows. */
enum class ProfilePage(val route: String, val title: String) {
    STATISTICS("statistics", "Statistics"),
    RIDING("riding", "Riding"),
    MOMENTS("moments", "Moments"),
    SAFETY("safety", "Safety"),
    SYNC("sync", "Journal & backup"),
    APPEARANCE("appearance", "Appearance"),
    DEVELOPER("developer", "Developer"),
    ABOUT("about", "About & privacy"),
    ERRORS("errors", "Error log"),
    ;

    companion object {
        fun of(route: String?) = entries.firstOrNull { it.route == route }
    }
}

/**
 * Profile: your totals at a glance, then one row per area, each with a line saying how
 * it's set. The settings themselves live on the page each row opens.
 */
@Composable
fun ProfileScreen(onOpenPage: (ProfilePage) -> Unit, onOpenHudSettings: () -> Unit, onOpenRecentlyDeleted: () -> Unit) {
    val vm = appViewModel { ProfileViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val st = s.settings

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader("Profile")

        val t = s.totals
        RtCard(onClick = { onOpenPage(ProfilePage.STATISTICS) }) {
            if (t == null) {
                Text(if (s.loading) " " else "Your totals appear after your first ride", style = RtType.body, color = RtColors.TextSecondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Total(t.rideCount.toString(), "rides", Modifier.weight(1f))
                    Total(Format.distanceValue(t.distanceM), "km", Modifier.weight(1f))
                    Total(Format.duration(t.movingMillis), "riding", Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "All statistics", tint = RtColors.TextSecondary)
                }
            }
        }

        Group("Ride") {
            NavRow(Icons.Outlined.TwoWheeler, "Riding", ridingSummary(st)) { onOpenPage(ProfilePage.RIDING) }
            Line()
            NavRow(Icons.Outlined.PictureInPicture, "Pop-up HUD", if (st.hud.enabled) "On · ${st.hud.layout.label}" else "Off", onOpenHudSettings)
            Line()
            NavRow(Icons.Outlined.Videocam, "Moments", momentsSummary(st)) { onOpenPage(ProfilePage.MOMENTS) }
            Line()
            NavRow(Icons.Outlined.HealthAndSafety, "Safety", safetySummary(st)) { onOpenPage(ProfilePage.SAFETY) }
        }

        Group("Your data") {
            NavRow(Icons.Outlined.CloudSync, "Journal & backup", syncSummary(st)) { onOpenPage(ProfilePage.SYNC) }
            Line()
            NavRow(Icons.Outlined.DeleteOutline, "Recently deleted", "Kept for 30 days", onOpenRecentlyDeleted)
        }

        Group("App") {
            NavRow(Icons.Outlined.Palette, "Appearance", st.appTheme.label) { onOpenPage(ProfilePage.APPEARANCE) }
            Line()
            NavRow(Icons.Outlined.Code, "Developer", if (st.demoMode) "Demo mode on" else "Demo mode, testing") { onOpenPage(ProfilePage.DEVELOPER) }
            Line()
            NavRow(Icons.Outlined.Info, "About & privacy", "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TAG})") { onOpenPage(ProfilePage.ABOUT) }
            Line()
            val errorCount = com.ridetrack.app.ui.appContainer().errors.entries.collectAsStateWithLifecycle().value.size
            NavRow(Icons.Outlined.BugReport, "Error log", if (errorCount == 0) "No errors" else "$errorCount recorded · tap to send") { onOpenPage(ProfilePage.ERRORS) }
        }
        Spacer(Modifier.height(RtDimens.lg))
    }
}

/** One of Profile's pages. */
@Composable
fun ProfilePageScreen(page: ProfilePage, onBack: () -> Unit) {
    val vm = appViewModel { ProfileViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader(page.title, onBack = onBack)
        when (page) {
            ProfilePage.STATISTICS -> StatisticsPage(s)
            ProfilePage.RIDING -> RidingPage(s, vm)
            ProfilePage.MOMENTS -> MomentsSection(s, vm, showHeader = false)
            ProfilePage.SAFETY -> SafetySection(s, vm, showHeader = false)
            ProfilePage.SYNC -> {
                JournalSection(s, vm)
                BackupSection(s, vm)
            }
            ProfilePage.APPEARANCE -> AppearancePage(s, vm)
            ProfilePage.DEVELOPER -> DeveloperPage(s, vm)
            ProfilePage.ABOUT -> AboutPage()
            ProfilePage.ERRORS -> ErrorLogPage()
        }
        Spacer(Modifier.height(RtDimens.lg))
    }
}

// ---- Summaries (one line under each row) ---------------------------------------------------

internal fun ridingSummary(s: Settings): String = listOf(
    if (s.autoPause) "Auto pause on" else "Auto pause off",
    if (s.breakMinutes > 0) "breaks after ${s.breakMinutes} min" else "no breaks",
).joinToString(" · ")

internal fun momentsSummary(s: Settings): String {
    val m = s.moments
    if (!m.enabled) return "Off"
    val events = listOf(m.braking, m.acceleration, m.lean).count { it }
    return listOfNotNull(
        "On",
        when (events) {
            0 -> null
            1 -> "1 event"
            else -> "$events events"
        },
        "voice".takeIf { m.voice },
        if (m.photos == PhotoInterval.OFF) null else "photos every ${m.photos.label}",
    ).joinToString(" · ")
}

internal fun safetySummary(s: Settings): String {
    val sf = s.safety
    val contacts = when (sf.contacts.size) {
        0 -> "no contacts"
        1 -> "1 contact"
        else -> "${sf.contacts.size} contacts"
    }
    return if (sf.crashDetection) "Crash detection on · $contacts" else "Crash detection off · $contacts"
}

internal fun syncSummary(s: Settings): String = listOf(
    if (s.journalSharing) "Journal on" else "Journal off",
    when {
        s.backup.email == null -> "Drive not connected"
        s.backup.lastSuccessMillis == null -> "not backed up yet"
        else -> "backed up ${DateUtils.getRelativeTimeSpanString(s.backup.lastSuccessMillis)}"
    },
).joinToString(" · ")

// ---- Pages ---------------------------------------------------------------------------------

@Composable
private fun StatisticsPage(s: ProfileUiState) {
    val t = s.totals
    if (t == null) {
        if (!s.loading) {
            EmptyState(
                "No statistics yet",
                "Your totals will appear here after your first recorded ride. Demo rides are not counted.",
                icon = Icons.Outlined.QueryStats,
            )
        }
        return
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RidingPage(s: ProfileUiState, vm: ProfileViewModel) {
    RtCard {
        ToggleRow(
            "Auto pause",
            "Show the ride as paused when you've been stopped for a few seconds. The ride never ends on its own.",
            s.settings.autoPause,
            vm::setAutoPause,
        )
        Line()
        Column(Modifier.padding(vertical = 12.dp)) {
            Text("Breaks", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(
                "A stop this long counts as a break, kept out of your riding time. Taking the phone off the mount starts a break sooner. It resumes when you ride on.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ChoiceRow(listOf(0, 3, 5, 10), s.settings.breakMinutes, { if (it == 0) "Off" else "$it min" }, enabled = true, onPick = vm::setBreakMinutes)
        }
        Line()
        ToggleRow("G-force indicator", "Show the G-force dot on the live ride screen.", s.settings.showGForceIndicator, vm::setGIndicator)
    }
    SectionHeader("Live ride metrics")
    RtCard {
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
    }
}

@Composable
private fun AppearancePage(s: ProfileUiState, vm: ProfileViewModel) {
    RtCard {
        Text("Theme", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        Text("The ride screen, pop-up HUD and crash alert stay dark either way.", style = RtType.caption, color = RtColors.TextSecondary)
        Spacer(Modifier.height(RtDimens.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
            AppTheme.entries.forEach { t ->
                FilterChip(
                    selected = s.settings.appTheme == t,
                    onClick = { vm.setAppTheme(t) },
                    label = { Text(t.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RtColors.PrimaryContainer,
                        selectedLabelColor = RtColors.TextPrimary,
                        labelColor = RtColors.TextSecondary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DeveloperPage(s: ProfileUiState, vm: ProfileViewModel) {
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
        ToggleRow(
            "Share demo rides with Keppo Journal",
            "For testing: demo rides appear in Keppo Journal (marked DEMO in ride.json). Drive backup still skips them.",
            s.settings.journalShareDemo,
            vm::setJournalShareDemo,
        )
    }
    if (BuildConfig.DEBUG) BetaExportSection()
}

@Composable
private fun AboutPage() {
    RtCard {
        InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TAG})")
    }
    SectionHeader("Privacy")
    RtCard {
        Text(
            "Your rides are stored only on this phone. Keppo Moto has no account, and recording works fully offline. " +
                "Map backgrounds are downloaded from OpenStreetMap/CARTO tile servers when you view a map. " +
                "Drive backup, if you turn it on, goes to your own Google Drive.",
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
}

// ---- Building blocks -----------------------------------------------------------------------

@Composable
private fun Total(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = RtType.metricM, color = RtColors.TextPrimary, maxLines = 1)
        Text(label, style = RtType.caption, color = RtColors.TextSecondary)
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionHeader(title)
    RtCard(contentPadding = PaddingValues(horizontal = RtDimens.cardPadding, vertical = 4.dp), content = content)
}

@Composable
private fun NavRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(RtColors.SurfaceRaised, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = RtColors.TextPrimary, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(RtDimens.sm))
        Column(Modifier.weight(1f)) {
            Text(title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(summary, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = RtColors.TextSecondary)
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
