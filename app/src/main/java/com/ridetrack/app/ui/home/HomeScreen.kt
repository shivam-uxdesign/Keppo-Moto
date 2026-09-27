package com.ridetrack.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.BikeImage
import com.ridetrack.app.ui.components.Chip
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.Stat
import com.ridetrack.app.ui.components.StatRow
import com.ridetrack.app.ui.components.StatusIndicator
import com.ridetrack.app.ui.components.StatusLevel
import com.ridetrack.app.ui.components.breathingGlow
import com.ridetrack.app.ui.components.riseIn
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.pressScale
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.Ride
import java.time.LocalTime

@Composable
fun HomeScreen(
    onRideStarted: () -> Unit,
    onReturnToRide: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenBikes: () -> Unit,
    onAddBike: () -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshEnvironment() }
    var confirmDiscard by remember { mutableStateOf<Ride?>(null) }
    var pickBike by remember { mutableStateOf(false) }
    var pendingBike by remember { mutableStateOf<Bike?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val bike = pendingBike
        pendingBike = null
        vm.refreshEnvironment()
        if (Permissions.hasFineLocation(context)) {
            if (bike != null) vm.startRide(bike, onRideStarted)
        } else {
            permissionDenied = true
        }
    }

    // Start → (pick bike) → (location permission, first time only) → recording.
    fun go(bike: Bike) {
        if (!s.demoMode && !Permissions.hasFineLocation(context)) {
            pendingBike = bike
            permissionLauncher.launch(Permissions.rideStartPermissions())
        } else {
            vm.startRide(bike, onRideStarted)
        }
    }
    val onStart: () -> Unit = {
        if (s.bikes.size > 1) pickBike = true else s.bike?.let(::go)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RtDimens.screenPaddingWide),
    ) {
        Header(s, onOpenProfile, onOpenBikes)
        Spacer(Modifier.height(28.dp))

        s.unfinished?.let { ride ->
            UnfinishedRideCard(ride, onSave = { vm.saveUnfinished(ride) }, onDiscard = { confirmDiscard = ride })
            Spacer(Modifier.height(RtDimens.md))
        }

        ReadyCard(s, onStart, onReturnToRide, onAddBike, Modifier.riseIn(0))

        if (!s.loading) {
            val t = s.totals
            if (t != null) {
                Spacer(Modifier.height(32.dp))
                Label("Today", Modifier.riseIn(1))
                Spacer(Modifier.height(14.dp))
                StatRow(
                    listOf(
                        Stat("Distance", Format.distanceValue(t.distanceTodayM), "km"),
                        Stat("Rides", t.ridesToday.toString()),
                        Stat("Moving", Format.duration(t.movingTodayMillis)),
                    ),
                    modifier = Modifier.riseIn(1),
                    style = RtType.metricL,
                )
                Spacer(Modifier.height(28.dp))
                Label("This month", Modifier.riseIn(2))
                Spacer(Modifier.height(14.dp))
                StatRow(
                    listOf(
                        Stat("Distance", Format.distanceValue(t.distanceThisMonthM), "km"),
                        Stat("Rides", t.ridesThisMonth.toString()),
                        Stat("Moving", Format.duration(t.movingThisMonthMillis)),
                    ),
                    modifier = Modifier.riseIn(2),
                    style = RtType.metricL,
                )
            } else if (s.hasBikes) {
                EmptyState(
                    title = "No rides yet",
                    message = "Start your first ride to begin building your riding history.",
                    icon = Icons.Outlined.Route,
                    modifier = Modifier.padding(top = RtDimens.lg),
                )
            }
        }
        Spacer(Modifier.height(RtDimens.lg))
    }

    confirmDiscard?.let { ride ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard unfinished ride?") },
            text = { Text("The recorded route and telemetry for this ride will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.discardUnfinished(ride); confirmDiscard = null }) { Text("Discard", color = RtColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Keep") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }

    if (pickBike) {
        BikePickerSheet(
            bikes = s.bikes,
            lastUsedId = s.bike?.id,
            onPick = { bike ->
                pickBike = false
                go(bike)
            },
            onDismiss = { pickBike = false },
        )
    }

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            title = { Text("Location is needed to record") },
            text = { Text("Ride Track uses GPS for speed, distance and your route. Allow location in Settings, or try Demo mode in Profile.") },
            confirmButton = {
                TextButton(onClick = {
                    permissionDenied = false
                    Permissions.openAppSettings(context)
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { permissionDenied = false }) { Text("Not now") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

@Composable
private fun Header(s: HomeUiState, onOpenProfile: () -> Unit, onOpenBikes: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpenBikes, role = Role.Button),
        ) {
            Text(greeting(), style = RtType.title.copy(fontSize = RtType.headline.fontSize * 1.18f), color = RtColors.TextPrimary)
            val bike = s.bike
            val sub = when {
                bike == null -> "No bike yet"
                s.demoMode -> "${bike.displayName} · demo mode"
                s.bikes.size > 1 -> "${s.bikes.size} bikes · last ridden ${bike.displayName}"
                else -> bike.displayName
            }
            Text(sub, style = RtType.body, color = RtColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (s.demoMode) DemoBadge(Modifier.padding(end = RtDimens.xs))
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(44.dp)
                .pressScale(interaction)
                .clip(CircleShape)
                .background(RtColors.Surface)
                .border(1.dp, RtColors.Hairline, CircleShape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpenProfile)
                .semantics { contentDescription = "Profile and settings" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ReadyCard(s: HomeUiState, onStartRide: () -> Unit, onReturnToRide: () -> Unit, onAddBike: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(RtDimens.heroRadius)
    // With several bikes the picker shows the photos; with one, the card wears it.
    val photo = s.bike?.photoFile?.takeIf { s.bikes.size <= 1 && !s.rideState.isActive }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RtColors.Surface)
            .border(1.dp, RtColors.Hairline, shape),
    ) {
        if (photo != null) {
            Box {
                BikeImage(photo, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                // Fade the photo into the card so the text below sits on a calm surface.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to RtColors.Surface)),
                )
            }
        }
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            ReadyCardBody(s, onStartRide, onReturnToRide, onAddBike)
        }
    }
}

@Composable
private fun ReadyCardBody(s: HomeUiState, onStartRide: () -> Unit, onReturnToRide: () -> Unit, onAddBike: () -> Unit) {
    when {
        s.rideState.isActive -> {
            Chip("Ride in progress", RtColors.Ok)
            Text("Recording", style = RtType.hero, color = RtColors.TextPrimary)
            PrimaryButton("Return to ride", onReturnToRide, large = true)
        }
        !s.loading && !s.hasBikes -> {
            Column {
                Text("Add your motorcycle", style = RtType.hero, color = RtColors.TextPrimary)
                Text(
                    "Every ride is saved against a bike. Add yours to start recording.",
                    style = RtType.body,
                    color = RtColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            PrimaryButton("Add bike", onAddBike, large = true, icon = Icons.Outlined.TwoWheeler)
        }
        else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (s.demoMode) {
                    StatusIndicator("GPS", "simulated", StatusLevel.WARNING)
                } else {
                    // Only what's knowable before recording: permission and the location switch.
                    val (text, level) = when (s.gps) {
                        GpsReadiness.READY -> "on" to StatusLevel.OK
                        GpsReadiness.PERMISSION_NEEDED -> "asks when you start" to StatusLevel.WARNING
                        GpsReadiness.DISABLED -> "off" to StatusLevel.ERROR
                        GpsReadiness.NO_HARDWARE -> "unavailable" to StatusLevel.ERROR
                    }
                    StatusIndicator("GPS", text, level)
                }
                val (motion, motionLevel) = when {
                    s.demoMode -> "simulated" to StatusLevel.WARNING
                    s.sensors.canEstimateLean -> "ready" to StatusLevel.OK
                    s.sensors.accelerometer -> "no gyroscope" to StatusLevel.WARNING
                    else -> "unavailable" to StatusLevel.ERROR
                }
                StatusIndicator("Sensors", motion, motionLevel)
            }
            Column {
                Text("Ready to ride", style = RtType.hero, color = RtColors.TextPrimary)
                Text(
                    if (s.bikes.size > 1) "Pick a bike and go. You'll be told if GPS can't get a fix."
                    else "Tap start and go. You'll be told if GPS can't get a fix.",
                    style = RtType.body,
                    color = RtColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            PrimaryButton(
                if (s.starting) "Starting…" else "Start ride", onStartRide,
                modifier = Modifier.breathingGlow(RtColors.Primary, enabled = s.bike != null && !s.starting),
                large = true, icon = Icons.Rounded.PlayArrow, haptic = true, enabled = s.bike != null && !s.starting,
            )
        }
    }
}

@Composable
private fun UnfinishedRideCard(ride: Ride, onSave: () -> Unit, onDiscard: () -> Unit) {
    val shape = RoundedCornerShape(RtDimens.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RtColors.SurfaceRaised)
            .border(1.dp, RtColors.Warning.copy(alpha = 0.3f), shape)
            .padding(20.dp),
    ) {
        Chip("Unfinished ride", RtColors.Warning)
        Spacer(Modifier.height(RtDimens.sm))
        Text(
            "A ride started ${Format.rideDate(ride.startTimeMillis)} was interrupted before it was saved. " +
                "${Format.distance(ride.stats.distanceM)} was recorded.",
            style = RtType.body,
            color = RtColors.TextPrimary,
        )
        Spacer(Modifier.height(RtDimens.md))
        Row {
            SecondaryButton("Discard", onDiscard, Modifier.weight(1f), contentColor = RtColors.Error)
            Spacer(Modifier.width(RtDimens.sm))
            PrimaryButton("Save ride", onSave, Modifier.weight(1f))
        }
    }
}

/** One tap starts the ride; the last-used bike is first and highlighted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BikePickerSheet(bikes: List<Bike>, lastUsedId: String?, onPick: (Bike) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RtColors.SurfaceRaised,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text("Which bike?", style = RtType.headline, color = RtColors.TextPrimary)
            Text(
                "Recording starts as soon as you pick.",
                style = RtType.body,
                color = RtColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )
            bikes.sortedByDescending { it.id == lastUsedId }.forEach { bike ->
                val last = bike.id == lastUsedId
                val interaction = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .pressScale(interaction)
                        .clip(shape)
                        .background(if (last) RtColors.Primary.copy(alpha = 0.10f) else RtColors.Surface)
                        .border(1.dp, if (last) RtColors.Primary.copy(alpha = 0.45f) else RtColors.Hairline, shape)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPick(bike) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BikeImage(bike.photoFile, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), maxEdge = 240, iconSize = 22.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bike.displayName, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val details = listOfNotNull(
                            "Last used".takeIf { last },
                            bike.year?.toString(),
                            bike.displacementCc?.let { "$it cc" },
                        ).joinToString(" · ")
                        if (details.isNotEmpty()) {
                            Text(details, style = RtType.caption, color = if (last) RtColors.Primary else RtColors.TextSecondary)
                        }
                    }
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Start ride on ${bike.displayName}",
                        tint = if (last) RtColors.Primary else RtColors.TextSecondary,
                    )
                }
            }
        }
    }
}
