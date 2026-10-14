package com.ridetrack.app.ui.bike

import com.ridetrack.app.ui.format.Units
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridetrack.app.fuel.FillUpDialog
import com.ridetrack.app.fuel.Fuel
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.Bike
import java.util.Locale

/**
 * Fuel for [bike]: mileage from full-tank fill-ups, recent fills, adding one by hand, and the
 * switch to read the card SMS at petrol pumps (off by default; asks for SMS access when on).
 */
@Composable
fun FuelSection(bike: Bike, fuel: BikeFuel, vm: BikesViewModel) {
    var adding by remember { mutableStateOf(false) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setReadSms(granted)
    }
    val m = fuel.mileage[bike.id]
    val fills = fuel.fills[bike.id].orEmpty()
    SectionHeader("Fuel · ${bike.displayName}")
    RtCard {
        val kmpl = m?.kmPerLitre
        Text(
            if (kmpl != null) "${String.format(Locale.US, "%.1f", Units.economy(kmpl))} ${Units.economyUnit}" else "Mileage after two fill-ups",
            style = RtType.bodyStrong,
            color = RtColors.TextPrimary,
        )
        Text(
            when {
                kmpl != null -> listOfNotNull(
                    m.tanks.lastOrNull()?.let { "last tank ${String.format(Locale.US, "%.1f", Units.economy(it.kmPerLitre))}" },
                    m.costPerKm?.let { "${com.ridetrack.app.fuel.FuelRegion.current.precise(Units.fromKm(1.0).let { k -> it / k })} per ${Units.distanceUnit}" },
                    "${m.tanks.size} ${if (m.tanks.size == 1) "tank" else "tanks"}",
                ).joinToString(" · ")
                else -> "You fill the tank each time, so ${Units.economyUnit} is the distance ridden since the last fill ÷ the fuel. Fuel stops show up on Home after a ride."
            },
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        fills.take(4).forEach { f ->
            HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${String.format(Locale.US, "%.2f", Units.volume(f.litres))} ${Units.volumeUnit}" + (f.amount?.let { " · ${Fuel.money(it)}" } ?: ""),
                        style = RtType.body,
                        color = RtColors.TextPrimary,
                    )
                    Text(
                        listOfNotNull(Format.rideDate(f.timeMillis), f.station).joinToString(" · "),
                        style = RtType.caption,
                        color = RtColors.TextTertiary,
                    )
                }
                TextButton(onClick = { vm.deleteFill(f.id) }) { Text("Remove", color = RtColors.TextSecondary) }
            }
        }
        val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
        TextButton(onClick = { nav(com.ridetrack.app.ui.nav.Routes.fuel(bike.id)) }) {
            Text("See all fuel" + fuel.prompts[bike.id].orEmpty().size.let { if (it > 0) " · $it to check" else "" }, color = RtColors.Primary)
        }
        Spacer(Modifier.height(4.dp))
        SecondaryButton("Add fill-up", { adding = true }, icon = Icons.Outlined.Add)
        HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                val region = com.ridetrack.app.fuel.FuelRegion.current
                Text("Read card SMS at fuel stops" + if (region.india) "" else " · Beta", style = RtType.body, color = RtColors.TextPrimary)
                Text(
                    "Reads the amount from your bank's card message, only around a fuel stop, then looks up today's petrol price for the city and saves the fill by itself (Undo in the notification). Needs SMS access; off by default." +
                        if (region.india) "" else " Beta in your country: bank messages vary, so some may not be read. What didn't work is noted (never the message) so it can be improved.",
                    style = RtType.caption,
                    color = RtColors.TextSecondary,
                )
            }
            Switch(
                checked = fuel.readSms,
                onCheckedChange = { on -> if (on) smsPermission.launch(Manifest.permission.READ_SMS) else vm.setReadSms(false) },
            )
        }
    }
    if (adding) {
        FillUpDialog(
            title = "Add fill-up",
            station = "Now · ${bike.displayName}",
            amount = null,
            price = fuel.lastPrice,
            onSave = { litres, amount, price ->
                vm.addFill(bike.id, litres, amount, price)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}
