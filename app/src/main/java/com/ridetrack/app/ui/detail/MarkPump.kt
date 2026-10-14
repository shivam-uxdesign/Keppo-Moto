package com.ridetrack.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType

/** Your petrol pumps on the map while marking one. */
val PumpColor = Color(0xFFF59E0B)

/** Over the map while marking a pump: what to do, and Cancel. */
@Composable
fun MarkPumpBanner(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(RtColors.SurfaceRaised.copy(alpha = 0.95f), RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text("Tap where the petrol pump is", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text("Zoom in to place it well. Orange dots are pumps you've marked before.", style = RtType.caption, color = RtColors.TextSecondary)
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/** Naming the pump just tapped, and whether this ride's fill-up was there. */
@Composable
fun MarkPumpDialog(suggested: String, onSave: (name: String, filled: Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    var filled by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Petrol pump here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Name") })
                Row(Modifier.clickable { filled = !filled }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = filled, onCheckedChange = { filled = it })
                    Text("I filled up here on this ride", style = RtType.body, color = RtColors.TextPrimary)
                }
                Text(
                    "The app remembers this pump: stopping here on later rides asks if you filled up, even offline." +
                        if (filled) " This ride's fill-up waits on Home and the Fuel page for the amount." else "",
                    style = RtType.caption,
                    color = RtColors.TextSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), filled) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = RtColors.SurfaceRaised,
    )
}
