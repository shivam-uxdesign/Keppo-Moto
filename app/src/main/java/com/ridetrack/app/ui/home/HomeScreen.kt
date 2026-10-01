package com.ridetrack.app.ui.home

import com.ridetrack.app.ui.components.KeppoWordmark
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.Chip
import com.ridetrack.app.ui.components.DemoBadge
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.StatusIndicator
import com.ridetrack.app.ui.components.StatusLevel
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
    onAddBike: () -> Unit,
    onEditBike: (String) -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshEnvironment() }
    var confirmDiscard by remember { mutableStateOf<Ride?>(null) }
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

    // Slide on a bike's card → (location permission, first time only) → recording.
    fun go(bike: Bike) {
        if (!s.demoMode && !Permissions.hasFineLocation(context)) {
            pendingBike = bike
            permissionLauncher.launch(Permissions.rideStartPermissions())
        } else {
            vm.startRide(bike, onRideStarted)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Header(s, onOpenProfile)
        Spacer(Modifier.height(16.dp))

        s.unfinished?.let { ride ->
            UnfinishedRideCard(ride, onSave = { vm.saveUnfinished(ride) }, onDiscard = { confirmDiscard = ride })
            Spacer(Modifier.height(RtDimens.md))
        }

        val bike = s.bike
        if (bike != null) {
            GarageStack(
                bikes = s.bikes,
                selected = bike,
                odometers = s.odometers,
                lastRidden = s.lastRidden,
                stats = s.bikeStats,
                starting = s.starting,
                rideActive = s.rideState.isActive,
                onSelect = vm::selectBike,
                onStart = ::go,
                onReturnToRide = onReturnToRide,
                onEditBike = onEditBike,
                modifier = Modifier.riseIn(0),
            )
        } else if (!s.loading) {
            if (s.offerRestore) {
                RestoreCard(onRestore = onOpenProfile, onDismiss = vm::dismissRestore, modifier = Modifier.riseIn(0))
                Spacer(Modifier.height(RtDimens.md))
            }
            AddBikeCard(onAddBike, Modifier.riseIn(0))
        }

        if (!s.loading) {
            val stats = s.stats
            if (s.totals != null && stats != null) {
                Spacer(Modifier.height(14.dp))
                StatsCard(stats, s.trace, Modifier.riseIn(1))
            } else if (s.hasBikes) {
                EmptyState(
                    title = "No rides yet",
                    message = "Slide to ride to record your first one. Your stats build up here.",
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

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            title = { Text("Location is needed to record") },
            text = { Text("Keppo Moto uses GPS for speed, distance and your route. Allow location in Settings, or try Demo mode in Profile.") },
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
private fun Header(s: HomeUiState, onOpenProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeppoWordmark(Modifier.padding(bottom = 10.dp))
            Text(greeting(), style = RtType.title.copy(fontSize = 26.sp, lineHeight = 30.sp), color = RtColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (s.demoMode) {
                    StatusIndicator("GPS", "simulated", StatusLevel.WARNING)
                } else {
                    // Only what's knowable before recording: permission and the location switch.
                    val (text, level) = when (s.gps) {
                        GpsReadiness.READY -> "ready" to StatusLevel.OK
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
        }
        if (s.demoMode) DemoBadge(Modifier.padding(end = RtDimens.xs))
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(42.dp)
                .pressScale(interaction)
                .clip(CircleShape)
                .background(RtColors.Surface)
                .border(1.dp, RtColors.Hairline, CircleShape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpenProfile)
                .semantics { contentDescription = "Profile and settings" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.size(19.dp))
        }
    }
}

/** First run: every ride is saved against a bike, so the garage starts with adding one. */
@Composable
private fun AddBikeCard(onAddBike: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RtColors.Surface)
            .border(1.dp, RtColors.Hairline, shape)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column {
            Text("Add your motorcycle", style = RtType.hero, color = RtColors.TextPrimary)
            Text(
                "Every ride is saved against a bike. Add yours, with a photo and its odometer, to start recording.",
                style = RtType.body,
                color = RtColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        PrimaryButton("Add bike", onAddBike, large = true, icon = Icons.Outlined.TwoWheeler)
    }
}

/** First launch on a new phone: rides, bikes and settings can come back from Google Drive (Profile › Backup). */
@Composable
private fun RestoreCard(onRestore: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RtDimens.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RtColors.Surface)
            .border(1.dp, RtColors.Hairline, shape)
            .padding(RtDimens.cardPadding),
    ) {
        Text("Restoring a phone?", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        Text(
            "Bring your rides, moments, bikes and settings back from Google Drive.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
            SecondaryButton("Restore from Drive", onRestore, Modifier.weight(1f))
            SecondaryButton("Not now", onDismiss, Modifier.weight(1f), contentColor = RtColors.TextSecondary)
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
