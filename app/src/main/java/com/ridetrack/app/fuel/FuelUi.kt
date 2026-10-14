package com.ridetrack.app.fuel

import com.ridetrack.app.ui.format.Units
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import java.util.Locale

/**
 * A fill-up: the amount paid and the price per litre (per gallon in the US) (remembered from last time) give the litres,
 * which can be corrected. The tank is always filled, so that's all mileage needs.
 */
@Composable
fun FillUpDialog(
    title: String,
    station: String?,
    amount: Double?,
    price: Double?,
    onSave: (litres: Double, amount: Double?, price: Double?) -> Unit,
    onDismiss: () -> Unit,
    /** Editing a fill: its fuel (litres), shown in the rider's volume. */
    litres: Double? = null,
    /** Editing: also a Delete. */
    onDelete: (() -> Unit)? = null,
) {
    var amountText by remember { mutableStateOf(amount?.let(::plain).orEmpty()) }
    // The dialog works in the rider's volume (litres, or US gallons); saved in litres.
    var priceText by remember { mutableStateOf(price?.let { plain(Units.pricePerVolume(it)) }.orEmpty()) }
    var litresText by remember { mutableStateOf(litres?.let { plain(Units.volume(it)) }.orEmpty()) }
    val a = number(amountText)
    val p = number(priceText)?.takeIf { it > 0 }
    val auto = if (a != null && p != null) a / p else null
    val volume = number(litresText) ?: auto
    val gallons = Units.volumeUnit == "gal"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                station?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
                OutlinedTextField(
                    amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(9) },
                    label = { Text("Paid (${FuelRegion.current.symbol})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    priceText, { priceText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(8) },
                    label = { Text("Price per ${if (gallons) "gallon" else "litre"} (${FuelRegion.current.symbol})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    litresText, { litresText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                    label = { Text(if (gallons) "Gallons" else "Litres") },
                    placeholder = { auto?.let { Text(String.format(Locale.US, "%.2f", it)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                Text("Full tank. ${if (gallons) "Gallons" else "Litres"} are worked out from the amount and price; type them to correct it.", style = RtType.caption, color = RtColors.TextTertiary)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { volume?.let { onSave(Units.toLitres(it), a, p?.let(Units::pricePerLitre)) } },
                enabled = volume != null && Units.toLitres(volume) > 0.2,
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { del -> TextButton(onClick = del) { Text("Delete", color = RtColors.Error) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        containerColor = RtColors.SurfaceRaised,
    )
}

/** "45,50" or "45.50" (a comma as the decimal point, as much of the world types it). */
private fun number(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
