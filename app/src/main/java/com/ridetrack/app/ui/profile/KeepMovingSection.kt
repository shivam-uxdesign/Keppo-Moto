package com.ridetrack.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.keepmoving.Destination
import com.ridetrack.app.keepmoving.Destinations
import com.ridetrack.app.keepmoving.LatLon
import com.ridetrack.app.keepmoving.TrafficLogger
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay

/**
 * Keep moving, step 0 (test builds): where the next ride is going, and whether Google's traffic
 * along the way is logged with it. The log goes into the ride's export as [TrafficLogger.LOG_FILE].
 */
@Composable
fun KeepMovingSection() {
    val c = appContainer()
    val log = c.trafficLog
    val on by log.on.collectAsStateWithLifecycle()
    val dest by log.destination.collectAsStateWithLifecycle()
    val status by log.status.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }

    SectionHeader("Keep moving")
    RtCard {
        if (!log.available) {
            Text("This build has no Google Routes key", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(
                "Add GOOGLE_ROUTES_KEY to the environment settings, then ask for a new build.",
                style = RtType.caption,
                color = RtColors.TextSecondary,
            )
            return@RtCard
        }
        val navigate = com.ridetrack.app.ui.nav.LocalNavigate.current
        com.ridetrack.app.ui.components.PrimaryButton(
            "Plan a Keep moving ride",
            onClick = { navigate(com.ridetrack.app.ui.nav.Routes.KEEP_MOVING) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        ToggleRow(
            "Log traffic on my rides",
            "Every 2 minutes, Google's route to where you're going, with its traffic, is saved with the ride. Nothing changes on the riding screen.",
            on,
            log::setOn,
        )
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Choose where you're riding to") { choosing = true }.padding(vertical = 12.dp),
        ) {
            Text("Riding to", style = RtType.caption, color = RtColors.TextSecondary)
            Text(dest?.name ?: "Choose a place ›", style = RtType.bodyStrong, color = if (dest == null) RtColors.Primary else RtColors.TextPrimary)
        }
        status?.let { Text("Last check: $it", style = RtType.caption, color = RtColors.TextSecondary) }
        Text(
            "${log.checksThisMonth} checks this month (stops at ${TrafficLogger.DAILY_CAP} a day). The log is in the ride's export, for comparing with what you met on the road; it's deleted after that, as Google's terms ask.",
            style = RtType.caption,
            color = RtColors.TextTertiary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (choosing) {
        DestinationPicker(
            onPick = { log.setDestination(it); choosing = false },
            onClear = if (dest != null) ({ log.setDestination(null); choosing = false }) else null,
            onClose = { choosing = false },
        )
    }
}

/** Where recent rides ended (tap one), or a search. */
@Composable
internal fun DestinationPicker(onPick: (Destination) -> Unit, onClear: (() -> Unit)?, onClose: () -> Unit) {
    val c = appContainer()
    var recent by remember { mutableStateOf<List<Destination>?>(null) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Destination>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { recent = runCatching { Destinations.recentEnds(c.rides, c.places) }.getOrDefault(emptyList()) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { found = emptyList(); return@LaunchedEffect }
        delay(400)
        searching = true
        val near = c.session.frame.value?.let { f -> f.latitude?.let { lat -> f.longitude?.let { LatLon(lat, it) } } } ?: recent?.firstOrNull()?.at
        found = Destinations.search(query, near)
        searching = false
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Riding to") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    placeholder = { Text("Search a place", style = RtType.body, color = RtColors.TextTertiary) },
                    singleLine = true,
                    textStyle = RtType.body.copy(color = RtColors.TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (searching) CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).height(20.dp))
                val list = if (query.trim().length >= 2) found else recent.orEmpty()
                if (query.trim().length < 2) {
                    Text("WHERE YOUR RIDES ENDED", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(vertical = 6.dp))
                    if (recent == null) CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).height(20.dp))
                    else if (recent!!.isEmpty()) Text("No rides yet: search a place above.", style = RtType.caption, color = RtColors.TextSecondary)
                } else if (!searching && found.isEmpty()) {
                    Text("Nothing found", style = RtType.caption, color = RtColors.TextSecondary)
                }
                list.forEach { d ->
                    Text(
                        d.name,
                        style = RtType.body,
                        color = RtColors.TextPrimary,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(d) }.padding(vertical = 10.dp),
                    )
                }
                onClear?.let { clear ->
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("No destination (stop logging)", onClick = clear)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = RtColors.Primary) } },
    )
}
