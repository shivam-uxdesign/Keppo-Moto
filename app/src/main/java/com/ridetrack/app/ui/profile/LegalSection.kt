package com.ridetrack.app.ui.profile

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the privacy policy and terms live (filled in with the store pack; hidden while empty). */
object LegalLinks {
    const val PRIVACY = ""
    const val TERMS = ""
}

/** The policy and terms links, open-source licences, and deleting everything. */
@Composable
internal fun LegalSection() {
    val context = LocalContext.current
    var licences by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    SectionHeader("Legal")
    RtCard {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (LegalLinks.PRIVACY.isNotBlank()) LinkRow("Privacy policy") { open(context, LegalLinks.PRIVACY) }
            if (LegalLinks.TERMS.isNotBlank()) LinkRow("Terms of use") { open(context, LegalLinks.TERMS) }
            LinkRow(if (licences) "Open-source licences ⌃" else "Open-source licences ›") { licences = !licences }
            if (licences) Licences()
        }
    }
    Spacer(Modifier.height(RtDimens.sm))
    SecondaryButton("Delete all my data", onClick = { deleting = true }, modifier = Modifier.fillMaxWidth(), contentColor = RtColors.Error)
    Text(
        "Removes every ride, moment, Reel and setting from this phone, and closes the app. Your Google Drive backup and Keppo Journal keep their copies (delete them there). A Pro subscription is cancelled in Google Play, not here.",
        style = RtType.caption,
        color = RtColors.TextTertiary,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (deleting) DeleteAllDialog { deleting = false }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = RtType.body,
        color = RtColors.Primary,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
    )
}

private fun open(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The licences shipped in assets/licenses (fonts, the voice detector, the map), and the libraries'. */
@Composable
private fun Licences() {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            context.assets.list("licenses").orEmpty().sorted().map { name ->
                name.substringBeforeLast('.') to runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            "Keppo Moto is built with open-source software, including AndroidX and Jetpack Compose, Media3 (ExoPlayer and Transformer), Room, Kotlin and kotlinx.coroutines " +
                "(Apache License 2.0), MapLibre Native (BSD 2-Clause), ONNX Runtime (MIT), and Firebase and Google Play services (Google's terms). " +
                "Map data © OpenStreetMap contributors (ODbL).",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        files.forEach { (name, text) ->
            Text(name, style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(text, style = RtType.caption, color = RtColors.TextTertiary)
        }
    }
}

/** Two steps, as nothing can bring it back: explain, then type DELETE. */
@Composable
private fun DeleteAllDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete all your data?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Every ride, moment, Reel, bike and setting on this phone goes, for good. The app then closes.")
                OutlinedTextField(typed, { typed = it }, label = { Text("Type DELETE to confirm") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = typed.trim().equals("DELETE", ignoreCase = true),
                onClick = { context.getSystemService<ActivityManager>()?.clearApplicationUserData() },
            ) { Text("Delete everything", color = RtColors.Error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
