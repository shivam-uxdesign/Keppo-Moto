package com.ridetrack.app.ui.bike

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.components.BikeImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.FuelType
import com.ridetrack.telemetry.model.MountOrientation
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BikeEditScreen(bikeId: String?, onDone: () -> Unit) {
    val vm = appViewModel(key = "bike-edit-${bikeId ?: "new"}") { BikeEditViewModel(it, bikeId) }
    val f by vm.form.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pickPhoto(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader(if (vm.isNew) "Add bike" else "Edit bike", onBack = onDone)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            PhotoSlot(
                f.photoFile,
                busy = f.photoBusy,
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = vm::removePhoto,
            )
            Spacer(Modifier.height(RtDimens.sm))
            Field("Make", f.make, "e.g. Bajaj", { v -> vm.update { it.copy(make = v) } }, capitalize = true)
            Field("Model", f.model, "e.g. Pulsar NS200", { v -> vm.update { it.copy(model = v) } }, capitalize = true)
            Row(horizontalArrangement = Arrangement.spacedBy(RtDimens.sm)) {
                Field("Year", f.year, "Optional", { v -> vm.update { it.copy(year = v.filter(Char::isDigit).take(4)) } }, Modifier.weight(1f), number = true, error = f.yearError)
                Field("Engine (cc)", f.displacementCc, "Optional", { v -> vm.update { it.copy(displacementCc = v.filter(Char::isDigit).take(4)) } }, Modifier.weight(1f), number = true, error = f.ccError)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(RtDimens.sm)) {
                Field("Weight (kg)", f.weightKg, "Optional", { v -> vm.update { it.copy(weightKg = v.filter(Char::isDigit).take(4)) } }, Modifier.weight(1f), number = true, error = f.weightError)
                Field("Redline (rpm)", f.redlineRpm, "Optional", { v -> vm.update { it.copy(redlineRpm = v.filter(Char::isDigit).take(5)) } }, Modifier.weight(1f), number = true, error = f.redlineError)
            }
            Text(
                "Redline marks the red zone on the rev meter, which appears once an OBD adapter is connected.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier.padding(top = RtDimens.xs),
            )

            Spacer(Modifier.height(RtDimens.md))
            Label("Fuel type")
            Spacer(Modifier.height(RtDimens.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                FuelType.entries.forEach { t -> Choice(t.name.pretty(), f.fuelType == t) { vm.update { it.copy(fuelType = t) } } }
            }
            Spacer(Modifier.height(RtDimens.lg))
            Label("Phone mounting")
            Spacer(Modifier.height(RtDimens.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                MountOrientation.entries.forEach { o -> Choice(o.name.pretty(), f.mountOrientation == o) { vm.update { it.copy(mountOrientation = o) } } }
            }
            Spacer(Modifier.height(RtDimens.xs))
            Text(
                "Mount the phone with its screen facing you. No setup needed: the mount calibrates itself on your first straight stretch of road, or tap \"Calibrate now\" when a ride starts.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Spacer(Modifier.height(RtDimens.lg))
        }
        PrimaryButton(
            if (vm.isNew) "Add bike" else "Save",
            onClick = { vm.save { onDone() } },
            enabled = f.isValid,
            large = true,
        )
        Spacer(Modifier.height(RtDimens.md))
    }
}

@Composable
private fun PhotoSlot(photoFile: String?, busy: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(RtDimens.heroRadius)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(top = RtDimens.xs)
            .clip(shape)
            .border(1.dp, RtColors.Hairline, shape)
            .clickable(role = Role.Button, onClickLabel = "Choose bike photo", onClick = onPick),
    ) {
        BikeImage(photoFile, Modifier.fillMaxSize(), maxEdge = 1200, iconSize = 40.dp)
        if (busy) {
            CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp).align(Alignment.Center))
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhotoPill(if (photoFile == null) "Add photo" else "Change photo", Icons.Outlined.AddAPhoto, onPick)
            if (photoFile != null) PhotoPill("Remove", null, onRemove)
        }
    }
}

@Composable
private fun PhotoPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(RtColors.Background.copy(alpha = 0.72f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = RtColors.TextPrimary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = RtType.caption, color = RtColors.TextPrimary)
    }
}

private fun String.pretty() = lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    capitalize: Boolean = false,
    error: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = RtColors.TextTertiary) },
        isError = error,
        supportingText = if (error) ({ Text("Check this value") }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            imeAction = ImeAction.Next,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = RtColors.Primary,
            unfocusedBorderColor = RtColors.Outline,
            focusedLabelColor = RtColors.Primary,
            cursorColor = RtColors.Primary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = RtDimens.xs),
    )
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
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
