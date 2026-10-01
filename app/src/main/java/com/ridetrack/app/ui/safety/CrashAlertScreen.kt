package com.ridetrack.app.ui.safety

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.data.EmergencyContact
import com.ridetrack.app.data.MedicalInfo
import com.ridetrack.app.safety.AlertMessage
import com.ridetrack.app.safety.CrashAlertState
import com.ridetrack.app.safety.CrashAlerts
import com.ridetrack.app.ui.theme.RtColors
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

private val AlertRed = Color(0xFFFF4D4D)

@Composable
fun CrashAlertScreen(alerts: CrashAlerts, onClose: () -> Unit) {
    val state by alerts.state.collectAsStateWithLifecycle()
    CrashAlertContent(
        state = state,
        onCancel = alerts::cancel,
        onSendNow = alerts::sendNow,
        onAllClear = alerts::allClear,
        onClose = onClose,
    )
}

@Composable
fun CrashAlertContent(state: CrashAlertState?, onCancel: () -> Unit, onSendNow: () -> Unit, onAllClear: () -> Unit, onClose: () -> Unit) {
    // Back must never send or skip the alert by accident: it means "I'm OK" while counting.
    BackHandler { if (state is CrashAlertState.Countdown) onCancel() else onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0B))) {
        when (state) {
            is CrashAlertState.Countdown -> Countdown(state, onCancel, onSendNow)
            is CrashAlertState.Sent -> Sent(state, onAllClear, onClose)
            is CrashAlertState.Cancelled -> Cancelled(onClose)
            null -> LaunchedEffect(Unit) { onClose() }
        }
    }
}

@Composable
private fun Countdown(s: CrashAlertState.Countdown, onCancel: () -> Unit, onSendNow: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.deadlineMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val left = ((s.deadlineMillis - now) / 1000.0).coerceIn(0.0, CrashAlerts.COUNTDOWN_MILLIS / 1000.0)
    val total = CrashAlerts.COUNTDOWN_MILLIS / 1000.0
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF6B0F1A), Color(0xFF2A0509), Color(0xFF0A0A0B)), center = Offset(540f, 500f), radius = 1400f)),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFFFD166), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("POSSIBLE CRASH", fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Text("Are you OK?", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                s.report.speedBeforeMps?.let { "Hard impact at ${(it * 3.6).roundToInt()} km/h, then the bike stopped." } ?: "Hard impact, then the bike stopped.",
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Box(Modifier.size(180.dp).semantics { contentDescription = "${ceil(left).toInt()} seconds until the alert is sent" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawCircle(Color.Black.copy(alpha = 0.25f))
                    drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(AlertRed, -90f, (360 * left / total).toFloat(), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${ceil(left).toInt()}", fontSize = 68.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                    Text("seconds", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (s.contacts.isEmpty()) "No emergency contacts are set, so nothing will be sent. Add them in Profile › Safety."
                else "Then your location goes to ${names(s.contacts)} by SMS.",
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            MedicalCard(s.report.riderName, s.report.medical, Modifier.padding(top = 14.dp))
            Spacer(Modifier.weight(1f))
            // Cancel is the big, easy target; sending is the deliberate one.
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFF5F5F7))
                    .clickable(role = Role.Button, onClickLabel = "Cancel the alert", onClick = onCancel)
                    .semantics { contentDescription = "I'm OK, cancel. Nothing will be sent." },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("I'm OK · Cancel", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0A0A0B))
                Text("Nothing will be sent", fontSize = 13.sp, color = Color(0xFF4A4A52))
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AlertRed.copy(alpha = 0.12f))
                    .border(1.5.dp, AlertRed.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button, enabled = s.contacts.isNotEmpty(), onClick = onSendNow),
                contentAlignment = Alignment.Center,
            ) {
                Text("Send now", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF8A8A).copy(alpha = if (s.contacts.isEmpty()) 0.4f else 1f))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MedicalCard(name: String, m: MedicalInfo, modifier: Modifier = Modifier) {
    if (m.isEmpty) return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(AlertRed), contentAlignment = Alignment.Center) {
            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(listOfNotNull(name.takeIf { it.isNotBlank() }, m.bloodGroup?.let { "Blood group $it" }).joinToString(" · ").ifEmpty { "Medical info" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            val rest = listOfNotNull(m.allergies.takeIf { it.isNotBlank() }?.let { "Allergies: $it" }, m.notes.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (rest.isNotEmpty()) Text(rest, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun Sent(s: CrashAlertState.Sent, onAllClear: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(AlertRed, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(if (s.delivered.values.any { it }) "Alert sent" else "Alert not sent", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        if (s.allClearSent) "You told them you're OK" else "They'll get one update if you're moved",
                        fontSize = 12.sp, color = RtColors.TextSecondary,
                    )
                }
            }
            s.contacts.forEach { c ->
                val ok = s.delivered[c.phone] == true
                Row(
                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF141416)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).background(Color(0xFF26262A), CircleShape), contentAlignment = Alignment.Center) {
                        Text(c.name.take(1).uppercase(), fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 15.sp, color = Color.White)
                        Text(
                            (if (ok) "✓ Sent · " else "Not sent · ") + c.phone,
                            fontSize = 12.sp, color = if (ok) RtColors.Ok else RtColors.Warning, maxLines = 1,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(RtColors.Ok)
                            .clickable(role = Role.Button, onClickLabel = "Call ${c.name}") {
                                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) { Text("Call", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF04210F)) }
                }
            }
            Text("MESSAGE", fontSize = 11.sp, letterSpacing = 1.sp, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 18.dp))
            Text(
                s.message,
                fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFDCE6F5),
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)).background(Color(0xFF1F2A3A)).padding(12.dp),
            )
            val lat = s.report.latitude
            val lon = s.report.longitude
            if (lat != null && lon != null) {
                Text(
                    "Open the location in Maps",
                    fontSize = 13.sp, color = RtColors.Primary,
                    modifier = Modifier.padding(top = 10.dp).clickable(role = Role.Button) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AlertMessage.mapsLink(lat, lon))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        BigButton(if (s.allClearSent) "Close" else "I'm OK · tell them", onClick = if (s.allClearSent) onClose else onAllClear, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Cancelled(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(76.dp).background(RtColors.Ok.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = RtColors.Ok, modifier = Modifier.size(38.dp))
        }
        Text("Glad you're OK", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 18.dp))
        Text("Nothing was sent. The ride keeps recording.", fontSize = 14.sp, color = RtColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        BigButton("Back to ride", onClick = onClose, modifier = Modifier.width(200.dp))
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF5F5F7)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0A0A0B)) }
}

private fun names(contacts: List<EmergencyContact>): String = when (contacts.size) {
    1 -> contacts[0].name
    2 -> "${contacts[0].name} and ${contacts[1].name}"
    else -> contacts.dropLast(1).joinToString(", ") { it.name } + " and " + contacts.last().name
}
