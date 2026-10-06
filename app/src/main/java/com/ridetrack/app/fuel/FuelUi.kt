package com.ridetrack.app.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * A fill-up: the ₹ paid and the price per litre (remembered from last time) give the litres,
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
) {
    var amountText by remember { mutableStateOf(amount?.let(::plain).orEmpty()) }
    var priceText by remember { mutableStateOf(price?.let(::plain).orEmpty()) }
    var litresText by remember { mutableStateOf("") }
    val a = amountText.toDoubleOrNull()
    val p = priceText.toDoubleOrNull()?.takeIf { it > 0 }
    val auto = if (a != null && p != null) a / p else null
    val litres = litresText.toDoubleOrNull() ?: auto
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                station?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
                OutlinedTextField(
                    amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                    label = { Text("Paid (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    priceText, { priceText = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                    label = { Text("Price per litre (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    litresText, { litresText = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                    label = { Text("Litres") },
                    placeholder = { auto?.let { Text(String.format(Locale.US, "%.2f", it)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                Text("Full tank. Litres are worked out from the amount and price; type them to correct it.", style = RtType.caption, color = RtColors.TextTertiary)
            }
        },
        confirmButton = {
            TextButton(onClick = { litres?.let { onSave(it, a, p) } }, enabled = litres != null && litres > 0.2) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = RtColors.SurfaceRaised,
    )
}

private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
