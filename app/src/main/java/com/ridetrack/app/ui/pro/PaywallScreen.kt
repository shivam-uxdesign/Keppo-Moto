package com.ridetrack.app.ui.pro

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.pro.Billing
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.KeppoWordmark
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A plan as the paywall shows it (from [Billing.Plan]; a plain copy so the screen can be previewed). */
data class PaywallPlan(val id: String, val price: String, val priceMicros: Long, val yearly: Boolean, val trialDays: Int?)

/**
 * The Pro paywall. Honest by design (Play's subscription rules and fair pricing): the price, that
 * the free week turns into a paid plan unless cancelled, how to cancel, and an easy close. No
 * made-up "was" prices or countdowns.
 */
@Composable
fun PaywallScreen(onClose: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plans by c.billing.plans.collectAsStateWithLifecycle()
    val pro by c.entitlements.pro.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    // Prices come from Play: ask again when the screen opens.
    LaunchedEffect(Unit) { c.billing.refresh() }
    // Paid (or restored): done.
    LaunchedEffect(pro) { if (pro && c.entitlements.subscribed) onClose() }
    PaywallContent(
        plans = plans.map { PaywallPlan(it.basePlanId, it.price, it.priceMicros, it.yearly, it.trialDays) },
        note = note,
        onBuy = { id ->
            val plan = plans.firstOrNull { it.basePlanId == id }
            val activity = context.findActivity()
            if (plan == null || activity == null || !c.billing.buy(activity, plan)) note = "Google Play couldn't open the payment. Try again in a moment."
        },
        onRestore = {
            note = "Checking with Google Play…"
            scope.launch {
                note = when (c.billing.refresh()) {
                    true -> "Pro restored."
                    false -> "No Keppo Moto Pro subscription on this Google account."
                    null -> "Couldn't reach Google Play. Check you're signed in and online, then try again."
                }
            }
        },
        onClose = onClose,
    )
}

@Composable
fun PaywallContent(plans: List<PaywallPlan>, note: String?, onBuy: (String) -> Unit, onRestore: () -> Unit, onClose: () -> Unit) {
    var chosen by remember(plans) { mutableStateOf(plans.firstOrNull { it.yearly }?.id ?: plans.firstOrNull()?.id) }
    val plan = plans.firstOrNull { it.id == chosen }
    val monthly = plans.firstOrNull { !it.yearly }
    Column(
        Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = RtColors.TextSecondary) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = RtDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(RtDimens.md),
        ) {
            KeppoWordmark(height = 18.dp)
            Text("Turn every ride into a Reel", style = RtType.hero, color = RtColors.TextPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = RtDimens.sm)) {
                Benefit(Icons.Rounded.Movie, "Reels any time", "Ideas, edits and styles from every ride, as many as you like.")
                Benefit(Icons.Rounded.AutoAwesome, "Ready when you park", "Your best moments made into Reels after each ride.")
                Benefit(Icons.Rounded.ClosedCaption, "Captions written for you", "What you say in your clips, written out in your language.")
                Benefit(Icons.Rounded.LocalGasStation, "Petrol price looked up", "Fill-ups from your card SMS saved without typing the price.")
            }
            if (plans.isEmpty()) {
                Text(
                    "Prices come from Google Play. Install Keppo Moto from the Play Store, signed in to Google, to subscribe.",
                    style = RtType.body,
                    color = RtColors.TextSecondary,
                )
            }
            plans.forEach { p ->
                PlanCard(p, selected = p.id == chosen, saving = if (p.yearly) saving(p, monthly) else null) { chosen = p.id }
            }
        }
        Column(Modifier.padding(horizontal = RtDimens.screenPadding, vertical = RtDimens.sm), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                if (plan?.trialDays != null) "Start your free week" else "Subscribe",
                onClick = { plan?.let { onBuy(it.id) } },
                enabled = plan != null,
                large = true,
                modifier = Modifier.fillMaxWidth(),
            )
            plan?.let { p ->
                Text(terms(p), style = RtType.caption, color = RtColors.TextTertiary)
            }
            note?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
            Text(
                "Restore purchase",
                style = RtType.button,
                color = RtColors.Primary,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable(role = Role.Button, onClick = onRestore).padding(8.dp),
            )
        }
    }
}

@Composable
private fun Benefit(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(RtColors.Primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = RtColors.Primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(detail, style = RtType.caption, color = RtColors.TextSecondary)
        }
    }
}

@Composable
private fun PlanCard(p: PaywallPlan, selected: Boolean, saving: Int?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().background(RtColors.Surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) RtColors.Primary else RtColors.Hairline, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (p.yearly) "Yearly" else "Monthly", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                saving?.let {
                    Text(
                        "Save $it%",
                        style = RtType.label,
                        color = RtColors.OnPrimary,
                        modifier = Modifier.background(RtColors.Primary, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                listOfNotNull(p.trialDays?.let { "$it days free, then" }, if (p.yearly) "${p.price} a year" else "${p.price} a month").joinToString(" "),
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
        }
        Box(
            Modifier.size(22.dp).border(2.dp, if (selected) RtColors.Primary else RtColors.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(12.dp).background(RtColors.Primary, CircleShape)) }
    }
}

/** How much less the yearly plan costs than 12 months, as a whole percentage (null when it's no saving). */
internal fun saving(yearly: PaywallPlan, monthly: PaywallPlan?): Int? {
    val m = monthly?.priceMicros?.takeIf { it > 0 } ?: return null
    val pct = ((1 - yearly.priceMicros.toDouble() / (m * 12)) * 100).roundToInt()
    return pct.takeIf { it > 0 }
}

/** The plain terms under the button. */
internal fun terms(p: PaywallPlan): String {
    val price = if (p.yearly) "${p.price} a year" else "${p.price} a month"
    val start = p.trialDays?.let { "Free for $it days, then $price." } ?: "$price."
    return "$start Renews automatically until you cancel in Google Play › Subscriptions. Cancel at least a day before it renews and you won't be charged."
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
