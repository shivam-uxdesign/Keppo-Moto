package com.ridetrack.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.pro.Billing
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch

/**
 * Keppo Moto Pro in Profile: whether the rider has it, Unlock (the paywall), Manage subscription
 * (Play's page) and Restore purchase (asks Play again what this Google account owns).
 */
@Composable
internal fun ProSection(onUnlock: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pro by c.entitlements.pro.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    val subscribed = c.entitlements.subscribed
    RtCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keppo Moto Pro", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    when {
                        subscribed && pro -> "Active"
                        pro -> "Free in the beta"
                        else -> "Free plan"
                    },
                    style = RtType.caption,
                    color = if (pro) RtColors.Primary else RtColors.TextSecondary,
                )
            }
            Text(
                if (pro) "Reels from your rides, captions written for you, and the petrol price looked up."
                else "One free Reel a week. Pro: Reels any time, captions written for you, and the petrol price looked up.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            Row {
                if (!pro) Link("Unlock Pro", onUnlock)
                if (subscribed) {
                    Link("Manage subscription") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Billing.manageUrl(context.packageName))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Link("Restore purchase") {
                    note = "Checking with Google Play…"
                    scope.launch {
                        note = when (c.billing.refresh()) {
                            true -> "Pro restored."
                            false -> "No Keppo Moto Pro subscription on this Google account."
                            null -> "Couldn't reach Google Play. Check you're signed in and online, then try again."
                        }
                    }
                }
            }
            note?.let { Text(it, style = RtType.caption, color = RtColors.TextTertiary) }
        }
    }
}

@Composable
private fun Link(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = RtType.button,
        color = RtColors.Primary,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
    )
}
