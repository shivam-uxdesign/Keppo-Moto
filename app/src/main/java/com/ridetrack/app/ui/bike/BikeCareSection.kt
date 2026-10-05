package com.ridetrack.app.ui.bike

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.home.BikeCare
import com.ridetrack.app.ui.home.CareItem
import com.ridetrack.app.ui.home.CareRow
import com.ridetrack.app.ui.home.CareStatus
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.Bike

/** Reminders for [bike] (chain lube, oil…) that count down by km and/or days; shown on Home. */
@Composable
fun BikeCareSection(
    bike: Bike,
    odometerKm: Double?,
    care: List<CareStatus>,
    onSave: (CareItem) -> Unit,
    onDelete: (String) -> Unit,
    onDone: (CareItem) -> Unit,
) {
    var editing by remember { mutableStateOf<CareItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    SectionHeader("Bike care · ${bike.displayName}")
    RtCard {
        if (odometerKm == null) {
            Text(
                "Set this bike's odometer (Edit) so km reminders can count down with your rides.",
                style = RtType.caption,
                color = RtColors.Warning,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (care.isEmpty()) {
            Text("No reminders yet. Add chain lube, oil change, tyre pressure or a service.", style = RtType.caption, color = RtColors.TextSecondary)
        }
        care.forEachIndexed { i, st ->
            if (i > 0) HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 10.dp))
            Column(Modifier.clickable(role = Role.Button) { editing = st.item }) {
                CareRow(st) {
                    TextButton(onClick = { onDone(st.item) }) { Text("Done", color = RtColors.Primary) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Add reminder", { adding = true }, icon = Icons.Outlined.Add)
    }
    if (adding || editing != null) {
        CareDialog(
            initial = editing,
            onSave = { name, km, days ->
                val e = editing
                onSave(e?.copy(name = name, everyKm = km, everyDays = days) ?: BikeCare.new(bike.id, name, km, days, odometerKm, System.currentTimeMillis()))
                adding = false
                editing = null
            },
            onDelete = editing?.let { e -> { onDelete(e.id); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CareDialog(
    initial: CareItem?,
    onSave: (name: String, everyKm: Int?, everyDays: Int?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var km by remember { mutableStateOf(initial?.everyKm?.toString().orEmpty()) }
    var days by remember { mutableStateOf(initial?.everyDays?.toString().orEmpty()) }
    val kmVal = km.toIntOrNull()?.takeIf { it in 1..100_000 }
    val daysVal = days.toIntOrNull()?.takeIf { it in 1..3_650 }
    val valid = name.isNotBlank() && (kmVal != null || daysVal != null) && (km.isBlank() || kmVal != null) && (days.isBlank() || daysVal != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add reminder" else "Edit reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initial == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BikeCare.PRESETS.forEach { p ->
                            FilterChip(
                                selected = name == p.name,
                                onClick = {
                                    name = p.name
                                    km = p.everyKm?.toString().orEmpty()
                                    days = p.everyDays?.toString().orEmpty()
                                },
                                label = { Text(p.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        km, { km = it.filter(Char::isDigit).take(6) }, label = { Text("Every km") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        days, { days = it.filter(Char::isDigit).take(4) }, label = { Text("Every days") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                Text("Whichever comes first. Counts from today; tap Done each time you do it.", style = RtType.caption, color = RtColors.TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), kmVal, daysVal) }, enabled = valid) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = RtColors.Error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        containerColor = RtColors.SurfaceRaised,
    )
}
