package com.ridetrack.app.ui.bike

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.fuel.FillUpDialog
import com.ridetrack.app.fuel.Fuel
import com.ridetrack.app.fuel.FuelFill
import com.ridetrack.app.fuel.FuelPrompt
import com.ridetrack.app.fuel.FuelRegion
import com.ridetrack.app.fuel.FuelSummary
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.format.Units
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything about one bike's fuel: mileage and how it's going, this month and all time, the
 * fill-ups waiting for an answer (and stops the app isn't sure about), and every fill-up with
 * when it was and how it got into the log. Tap a fill-up to correct or delete it.
 */
@Composable
fun FuelScreen(bikeId: String, onBack: () -> Unit) {
    val vm = appViewModel { BikesViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val fuel by vm.fuel.collectAsStateWithLifecycle()
    val bike = s.bikes.firstOrNull { it.id == bikeId }
    val fills = fuel.fills[bikeId].orEmpty()
    val m = fuel.mileage[bikeId]
    val prompts = fuel.prompts[bikeId].orEmpty()
    var editing by remember { mutableStateOf<FuelFill?>(null) }
    var answering by remember { mutableStateOf<FuelPrompt?>(null) }
    var adding by remember { mutableStateOf(false) }
    val region = FuelRegion.current

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = RtDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(RtDimens.md),
    ) {
        ScreenHeader("Fuel" + (bike?.displayName?.let { " · $it" } ?: ""), onBack = onBack)

        // Mileage first: what most riders come here for.
        RtCard {
            val avg = m?.kmPerLitre
            Text("MILEAGE", style = RtType.label, color = RtColors.TextTertiary)
            Text(
                avg?.let { "${"%.1f".format(Locale.US, Units.economy(it))} ${Units.economyUnit} on average" } ?: "Mileage after two fill-ups",
                style = RtType.headline, color = RtColors.TextPrimary,
            )
            val summary = m?.let { FuelSummary.of(fills, it) }
            val lines = listOfNotNull(
                m?.tanks?.lastOrNull()?.let { "Last tank ${"%.1f".format(Locale.US, Units.economy(it.kmPerLitre))} ${Units.economyUnit}" },
                summary?.trend?.let { t ->
                    val pct = abs(t * 100).roundToInt()
                    if (pct < 3) "about the same as usual" else if (t > 0) "$pct% better than usual" else "$pct% worse than usual"
                },
                m?.costPerKm?.let { "${region.precise(it / Units.fromKm(1.0))} per ${Units.distanceUnit}" },
            )
            if (lines.isNotEmpty()) Text(lines.joinToString(" · "), style = RtType.body, color = RtColors.TextSecondary)
            if (avg == null) {
                Text("You fill the tank each time, so mileage is the distance since the last fill ÷ the fuel put in.", style = RtType.caption, color = RtColors.TextTertiary)
            }
        }

        // Totals.
        RtCard {
            val sum = FuelSummary.of(fills, m ?: Fuel.mileage(emptyList(), emptyList()))
            Row(Modifier.fillMaxWidth()) {
                Totals("This month", sum.monthLitres, sum.monthMoney, sum.monthFills, Modifier.weight(1f))
                Totals("All time", sum.allLitres, sum.allMoney, sum.allFills, Modifier.weight(1f))
            }
        }

        // Waiting for an answer, then the stops the app isn't sure about.
        val sure = prompts.filter { it.sure }
        val unsure = prompts.filter { !it.sure }
        if (sure.isNotEmpty()) {
            SectionHeader("Did you fill up?")
            RtCard { sure.forEachIndexed { i, p -> if (i > 0) Divider(); PromptRow(p, { answering = p }, { vm.dismissPrompt(p) }) } }
        }
        if (unsure.isNotEmpty()) {
            SectionHeader("Not sure")
            Text("Stops of a few minutes with no fuel station on the map. If one was a fill-up, add it: the place is remembered for next time.", style = RtType.caption, color = RtColors.TextSecondary)
            RtCard { unsure.forEachIndexed { i, p -> if (i > 0) Divider(); PromptRow(p, { answering = p }, { vm.dismissPrompt(p) }) } }
        }

        // Every fill-up, newest first, by month.
        SectionHeader("Fill-ups")
        SecondaryButton("Add fill-up", { adding = true }, icon = Icons.Outlined.Add)
        if (fills.isEmpty()) {
            Text("None yet. Fuel stops show up after a ride, or add one here.", style = RtType.body, color = RtColors.TextSecondary)
        }
        fills.groupBy { month(it.timeMillis) }.forEach { (label, inMonth) ->
            Text(label.uppercase(), style = RtType.label, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
            RtCard {
                inMonth.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    FillRow(f) { editing = f }
                }
            }
        }
        Spacer(Modifier.height(RtDimens.lg))
    }

    editing?.let { f ->
        FillUpDialog(
            title = "Fill-up · ${dateTime(f.timeMillis)}",
            station = listOfNotNull(f.station, how(f.source)).joinToString(" · "),
            amount = f.amount,
            price = f.pricePerLitre,
            litres = f.litres,
            onSave = { litres, amount, price -> vm.updateFill(f.copy(litres = litres, amount = amount, pricePerLitre = price)); editing = null },
            onDismiss = { editing = null },
            onDelete = { vm.deleteFill(f.id); editing = null },
        )
    }
    answering?.let { p ->
        FillUpDialog(
            title = "Fill-up",
            station = "${if (p.sure) p.station else "Your fuel stop"} · ${dateTime(p.timeMillis)}",
            amount = p.amount,
            price = fuel.lastPrice,
            onSave = { litres, amount, price -> vm.confirmPrompt(p, litres, amount, price); answering = null },
            onDismiss = { answering = null },
        )
    }
    if (adding) {
        FillUpDialog(
            title = "Add fill-up",
            station = "Now · ${bike?.displayName.orEmpty()}",
            amount = null,
            price = fuel.lastPrice,
            onSave = { litres, amount, price -> vm.addFill(bikeId, litres, amount, price); adding = false },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun Totals(label: String, litres: Double, money: Double, count: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = RtType.label, color = RtColors.TextTertiary)
        Text("${"%.1f".format(Locale.US, Units.volume(litres))} ${Units.volumeUnit}", style = RtType.bodyStrong, color = RtColors.TextPrimary)
        Text(
            listOfNotNull(money.takeIf { it > 0 }?.let { Fuel.money(it) }, "$count fill-up${if (count == 1) "" else "s"}").joinToString(" · "),
            style = RtType.caption, color = RtColors.TextSecondary,
        )
    }
}

@Composable
private fun PromptRow(p: FuelPrompt, onAdd: () -> Unit, onNo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (p.sure) p.station else "A stop of a few minutes", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(
                listOfNotNull(dateTime(p.timeMillis), p.amount?.let { "${Fuel.money(it)} on your card" }, found(p.how)).joinToString(" · "),
                style = RtType.caption, color = RtColors.TextSecondary,
            )
        }
        TextButton(onClick = onNo) { Text("No", color = RtColors.TextSecondary) }
        TextButton(onClick = onAdd) { Text("Add", color = RtColors.Primary) }
    }
}

@Composable
private fun FillRow(f: FuelFill, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Correct or delete", onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(
                    "${"%.2f".format(Locale.US, Units.volume(f.litres))} ${Units.volumeUnit}",
                    f.amount?.let { Fuel.money(it) },
                    f.pricePerLitre?.let { "${FuelRegion.current.precise(Units.pricePerVolume(it))}/${Units.volumeUnit}" },
                ).joinToString(" · "),
                style = RtType.bodyStrong, color = RtColors.TextPrimary,
            )
            Text(listOfNotNull(dateTime(f.timeMillis), f.station).joinToString(" · "), style = RtType.caption, color = RtColors.TextSecondary)
            Text(how(f.source), style = RtType.caption, color = RtColors.TextTertiary)
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))

/** How a fill-up got into the log, as the rider would say it. */
internal fun how(source: String): String = when (source) {
    "auto" -> "Saved automatically: card SMS and today's price"
    "auto-about" -> "Saved automatically: card SMS and the last known price"
    "sms" -> "From your card SMS, confirmed by you"
    "stop" -> "Confirmed from a fuel stop"
    "live" -> "Confirmed on the spot"
    "button" -> "You tapped Filled up"
    else -> "Added by you"
}

/** How a waiting fill-up was found. */
private fun found(how: String): String = when (how) {
    "sms" -> "found from your card SMS"
    "live" -> "asked on the spot"
    "button" -> "you tapped Filled up"
    "nudge" -> "a tank's distance since the last"
    else -> "found from the stop"
}

private fun dateTime(ms: Long): String =
    DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun month(ms: Long): String =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
