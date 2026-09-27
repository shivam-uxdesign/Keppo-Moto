package com.ridetrack.app.ui.bike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.ridetrack.app.ui.components.BikeImage
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.Chip
import com.ridetrack.app.ui.components.EmptyState
import com.ridetrack.app.ui.components.InfoRow
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.Bike
import java.util.Locale

@Composable
fun BikeScreen(onAddBike: () -> Unit, onEditBike: (String) -> Unit, onCalibrate: (String) -> Unit) {
    val vm = appViewModel { BikesViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Bike?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader("My bikes")
        if (!s.loading && s.bikes.isEmpty()) {
            EmptyState(
                title = "No bikes yet",
                message = "Add your motorcycle. Every ride is saved against a bike.",
                icon = Icons.Outlined.TwoWheeler,
                action = { PrimaryButton("Add bike", onAddBike, icon = Icons.Outlined.Add) },
            )
        }
        s.bikes.forEach { bike ->
            BikeCard(
                bike = bike,
                selected = bike.id == s.selectedId,
                onSelect = { vm.select(bike.id) },
                onEdit = { onEditBike(bike.id) },
                onCalibrate = { onCalibrate(bike.id) },
                onDelete = { confirmDelete = bike },
            )
            Spacer(Modifier.height(RtDimens.cardSpacing))
        }
        if (s.bikes.isNotEmpty()) {
            SecondaryButton("Add bike", onAddBike, icon = Icons.Outlined.Add)
        }

        SectionHeader("Phone sensors")
        RtCard {
            InfoRow("GPS", if (s.hasGps) "Available" else "Unavailable")
            Line()
            InfoRow("Accelerometer", if (s.sensors.accelerometer) "Available" else "Unavailable")
            Line()
            InfoRow("Gyroscope", if (s.sensors.gyroscope) "Available" else "Unavailable · lean angle disabled")
            Line()
            InfoRow("Compass", if (s.sensors.magnetometer) "Available" else "Unavailable")
        }

        SectionHeader("Connected devices")
        RtCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bluetooth, contentDescription = null, tint = RtColors.TextTertiary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(RtDimens.sm))
                Column(Modifier.weight(1f)) {
                    Text("OBD", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text("Not connected", style = RtType.caption, color = RtColors.TextSecondary)
                }
                Chip("Coming later", RtColors.TextTertiary)
            }
            Spacer(Modifier.height(RtDimens.sm))
            Text(
                "Bluetooth OBD support is planned: RPM and gear (rev meter), temperatures, fuel, fault codes. " +
                    "The rev meter and gear readout appear automatically once an adapter reports them. Try it now with Demo mode in Profile.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(RtDimens.lg))
    }

    confirmDelete?.let { bike ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${bike.displayName}?") },
            text = { Text("Bikes with recorded rides can't be deleted, so your ride history stays intact.") },
            confirmButton = { TextButton(onClick = { vm.delete(bike.id); confirmDelete = null }) { Text("Delete", color = RtColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
    s.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
}

@Composable
private fun BikeCard(bike: Bike, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onCalibrate: () -> Unit, onDelete: () -> Unit) {
    RtCard(onClick = onSelect) {
        if (bike.photoFile != null) {
            BikeImage(
                bike.photoFile,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.height(RtDimens.md))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bike.photoFile == null) {
                Icon(Icons.Outlined.TwoWheeler, contentDescription = null, tint = if (selected) RtColors.Primary else RtColors.TextSecondary)
                Spacer(Modifier.width(RtDimens.sm))
            }
            Column(Modifier.weight(1f)) {
                Text(bike.displayName, style = RtType.headline, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(
                    bike.year?.toString(),
                    bike.displacementCc?.let { "$it cc" },
                    bike.weightKg?.let { "$it kg" },
                    bike.redlineRpm?.let { "redline ${Format.rpm(it.toDouble())}" },
                    bike.fuelType.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) },
                ).joinToString(" · ")
                Text(details, style = RtType.caption, color = RtColors.TextSecondary)
            }
            if (selected) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = "Current bike", tint = RtColors.Primary)
            }
        }
        Spacer(Modifier.height(RtDimens.md))
        InfoRow("Phone mount", bike.mountOrientation.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) })
        InfoRow(
            "Calibration",
            bike.calibration?.let { "Calibrated · ${Format.rideDate(it.createdAtMillis)}" } ?: "Automatic on your next ride",
            valueColor = if (bike.calibration == null) RtColors.TextSecondary else RtColors.TextPrimary,
        )
        Spacer(Modifier.height(RtDimens.sm))
        Row {
            TextButton(onClick = onCalibrate) { Text(if (bike.calibration == null) "Calibrate manually" else "Recalibrate", color = RtColors.Primary) }
            TextButton(onClick = onEdit) { Text("Edit", color = RtColors.TextPrimary) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Delete", color = RtColors.TextSecondary) }
        }
    }
}

@Composable
private fun Line() = HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))
