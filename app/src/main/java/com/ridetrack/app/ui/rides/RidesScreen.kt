package com.ridetrack.app.ui.rides

import com.ridetrack.app.ui.common.BikeColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.common.RideRow
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

@Composable
fun RidesScreen(onOpenRide: (String) -> Unit, onStartRide: () -> Unit, onOpenMap: () -> Unit) {
    val vm = appViewModel { RidesViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    val bikeNames = s.bikes.associate { it.id to it.displayName }
    // Colour dots only mean something with more than one bike.
    val bikeColors = if (s.bikes.size > 1) BikeColors.all(s.bikes) else emptyMap()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader("Rides", Modifier.padding(horizontal = RtDimens.screenPadding)) {
            if (s.totalRides > 0) {
                IconButton(onClick = onOpenMap) {
                    Icon(Icons.Outlined.Map, contentDescription = "Map of all rides", tint = RtColors.TextPrimary)
                }
                IconButton(onClick = { showFilters = true }) {
                    BadgedBox(badge = { if (s.filter.activeCount > 0) Badge(containerColor = RtColors.Primary) { Text("${s.filter.activeCount}") } }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = "Filter rides", tint = RtColors.TextPrimary)
                    }
                }
            }
        }
        when {
            s.loading -> Unit
            s.totalRides == 0 -> Column(Modifier.padding(RtDimens.screenPadding)) {
                EmptyState(
                    title = "No rides yet",
                    message = "Start your first ride to begin building your riding history.",
                    icon = Icons.Outlined.Route,
                    action = { PrimaryButton("Start ride", onStartRide) },
                )
            }
            s.groups.isEmpty() -> Column(Modifier.padding(RtDimens.screenPadding)) {
                EmptyState(
                    title = "No matching rides",
                    message = "No rides match the current filters.",
                    action = { SecondaryButton("Clear filters", vm::clearFilters) },
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = RtDimens.screenPadding, end = RtDimens.screenPadding, bottom = RtDimens.lg),
                verticalArrangement = Arrangement.spacedBy(RtDimens.cardSpacing),
            ) {
                s.groups.forEach { (group, rides) ->
                    item(key = "header-${group.name}") { SectionHeader(group.label) }
                    items(rides, key = { it.id }) { ride ->
                        RideRow(ride, bikeNames[ride.bikeId], onClick = { onOpenRide(ride.id) }, route = s.routes[ride.id], map = s.maps[ride.id], moments = s.moments[ride.id], bikeColor = bikeColors[ride.bikeId])
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(s, onApply = { vm.setFilter(it); showFilters = false }, onDismiss = { showFilters = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(s: RidesUiState, onApply: (RideFilter) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(s.filter) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RtColors.SurfaceRaised,
    ) {
        Column(
            Modifier
                .padding(horizontal = RtDimens.screenPadding)
                .navigationBarsPadding(),
        ) {
            Text("Filter rides", style = RtType.headline, color = RtColors.TextPrimary)
            FilterGroup("Date") {
                DateFilter.entries.forEach { opt -> Option(opt.label, f.date == opt) { f = f.copy(date = opt) } }
            }
            if (s.bikes.size > 1) {
                FilterGroup("Bike") {
                    Option("All bikes", f.bikeId == null) { f = f.copy(bikeId = null) }
                    s.bikes.forEach { b -> Option(b.displayName, f.bikeId == b.id) { f = f.copy(bikeId = b.id) } }
                }
            }
            FilterGroup("Distance") {
                DistanceFilter.entries.forEach { opt -> Option(opt.label, f.distance == opt) { f = f.copy(distance = opt) } }
            }
            FilterGroup("Duration") {
                DurationFilter.entries.forEach { opt -> Option(opt.label, f.duration == opt) { f = f.copy(duration = opt) } }
            }
            Spacer(Modifier.height(RtDimens.lg))
            PrimaryButton("Apply", { onApply(f) })
            TextButton(onClick = { onApply(RideFilter()) }, modifier = Modifier.padding(vertical = RtDimens.xs)) {
                Text("Reset filters", color = RtColors.TextSecondary)
            }
            Spacer(Modifier.height(RtDimens.md))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(RtDimens.lg))
    Label(title)
    Spacer(Modifier.height(RtDimens.xs))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs), verticalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
        content()
    }
}

@Composable
private fun Option(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f),
            selectedLabelColor = RtColors.Primary,
            labelColor = RtColors.TextSecondary,
        ),
    )
}
