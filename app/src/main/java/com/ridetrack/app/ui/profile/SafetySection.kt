package com.ridetrack.app.ui.profile

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.CrashSensitivity
import com.ridetrack.app.data.EmergencyContact
import com.ridetrack.app.data.MedicalInfo
import com.ridetrack.app.data.SafetySettings
import com.ridetrack.app.safety.SmsStatus
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType

/**
 * Crash alert and emergency contacts, medical info, and the GPS-lost video. Text fields
 * keep their own state while typing and save as they go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SafetySection(s: ProfileUiState, vm: ProfileViewModel) {
    val context = LocalContext.current
    val safety = s.settings.safety
    val save: (SafetySettings) -> Unit = vm::setSafety
    var typing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var smsDenied by remember { mutableStateOf(false) }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val picked = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) EmergencyContact(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null }
        }.getOrNull()
        if (picked != null && picked.phone.isNotBlank()) save(safety.copy(contacts = (safety.contacts + picked).take(EmergencyContact.MAX)))
    }
    // SMS to send the alert; phone state to pick a SIM when SMS is "ask every time";
    // calls so the alert screen's Call button rings straight away.
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.SEND_SMS] == true || vm.canSendSms()
        smsDenied = !granted
        if (granted) save(safety.copy(crashDetection = true))
    }
    val askPermissions = { smsPermission.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)) }

    SectionHeader("Safety")
    RtCard {
        Text(
            "If it looks like you've crashed, Ride Track asks if you're OK. No answer in 30 seconds and it texts your contacts where you are.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )

        Spacer(Modifier.height(RtDimens.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Emergency contacts", Modifier.weight(1f))
            Text("${safety.contacts.size} of ${EmergencyContact.MAX}", style = RtType.caption, color = RtColors.TextTertiary)
        }
        safety.contacts.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(RtColors.SurfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
                    Text(c.name.take(1).uppercase(), style = RtType.bodyStrong, color = RtColors.TextPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 1)
                    Text(c.phone, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1)
                }
                if (i > 0) {
                    IconButton(onClick = {
                        val l = safety.contacts.toMutableList()
                        l.add(i - 1, l.removeAt(i))
                        save(safety.copy(contacts = l))
                    }) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move ${c.name} up", tint = RtColors.TextSecondary) }
                }
                IconButton(onClick = { save(safety.copy(contacts = safety.contacts.filterIndexed { k, _ -> k != i })) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove ${c.name}", tint = RtColors.TextSecondary)
                }
            }
        }
        if (safety.contacts.size < EmergencyContact.MAX) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                SecondaryButton("From contacts", onClick = {
                    runCatching { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                })
                SecondaryButton("Type a number", onClick = { typing = true })
            }
        }

        Spacer(Modifier.height(RtDimens.md))
        var name by rememberSaveable { mutableStateOf(safety.riderName) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40); save(safety.copy(riderName = name.trim())) },
            label = { Text("Your name (for the message)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(RtDimens.md))
        Label("Medical info · optional")
        Text("Shown on the crash screen for anyone helping, and added to the message.", style = RtType.caption, color = RtColors.TextTertiary)
        Spacer(Modifier.height(RtDimens.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MedicalInfo.BLOOD_GROUPS.forEach { b ->
                val on = safety.medical.bloodGroup == b
                FilterChip(
                    selected = on,
                    onClick = { save(safety.copy(medical = safety.medical.copy(bloodGroup = if (on) null else b))) },
                    label = { Text(b) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RtColors.Error.copy(alpha = 0.18f),
                        selectedLabelColor = RtColors.Error,
                        labelColor = RtColors.TextSecondary,
                    ),
                )
            }
        }
        var allergies by rememberSaveable { mutableStateOf(safety.medical.allergies) }
        OutlinedTextField(
            value = allergies,
            onValueChange = { allergies = it.take(80); save(safety.copy(medical = safety.medical.copy(allergies = allergies))) },
            label = { Text("Allergies") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = RtDimens.xs),
        )
        var notes by rememberSaveable { mutableStateOf(safety.medical.notes) }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it.take(120); save(safety.copy(medical = safety.medical.copy(notes = notes))) },
            label = { Text("Notes (medication, conditions)") },
            modifier = Modifier.fillMaxWidth().padding(top = RtDimens.xs),
        )

        Spacer(Modifier.height(RtDimens.sm))
        ToggleRow(
            "Crash detection",
            when {
                smsDenied -> "Needs permission to send SMS, so it can text your contacts."
                safety.contacts.isEmpty() -> "Add a contact first: an alert with nobody to text can't help."
                else -> "A hard impact, then the bike down or not moving. Not on demo rides."
            },
            safety.crashDetection,
            { on ->
                when {
                    !on -> save(safety.copy(crashDetection = false))
                    vm.canSendSms() && vm.hasCallPermissions() -> save(safety.copy(crashDetection = true))
                    else -> askPermissions()
                }
            },
            enabled = safety.contacts.isNotEmpty() || safety.crashDetection,
        )
        if (safety.crashDetection) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sensitivity", style = RtType.body, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                CrashSensitivity.entries.forEach { level ->
                    FilterChip(
                        selected = safety.sensitivity == level,
                        onClick = { save(safety.copy(sensitivity = level)) },
                        label = { Text(level.label) },
                        modifier = Modifier.padding(start = 6.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f), selectedLabelColor = RtColors.Primary),
                    )
                }
            }
            val nm = context.getSystemService<NotificationManager>()
            if (Build.VERSION.SDK_INT >= 34 && nm != null && !nm.canUseFullScreenIntent()) {
                Text(
                    "Allow full-screen alerts, so the countdown shows even when the phone is locked ›",
                    style = RtType.caption,
                    color = RtColors.Warning,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .clickable(role = Role.Button) {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                            }
                        },
                )
            }
        }
        ToggleRow(
            "Film when GPS is lost",
            if (s.settings.moments.enabled) "Records until GPS is back (up to 10 min), saved as a moment."
            else "Needs Moments on (below): it uses the Moments camera.",
            safety.gpsLostVideo,
            { save(safety.copy(gpsLostVideo = it)) },
        )

        Spacer(Modifier.height(RtDimens.sm))
        SecondaryButton(
            "Send test message",
            onClick = {
                if (!vm.canSendSms() || !vm.hasCallPermissions()) {
                    askPermissions()
                } else {
                    result = "Sending…"
                    vm.sendTestAlert { outcomes ->
                        result = outcomes.joinToString("\n") { (c, st) ->
                            when (st) {
                                SmsStatus.Sent -> "✓ Sent to ${c.name}"
                                is SmsStatus.Failed -> "✗ ${c.name}: ${st.reason}"
                                SmsStatus.Sending -> "${c.name}: no answer yet"
                            }
                        }
                    }
                }
            },
            enabled = safety.contacts.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            result ?: "Your contacts get a text marked “(test)”.",
            style = RtType.caption,
            color = if (result != null) RtColors.Primary else RtColors.TextTertiary,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (BuildConfig.DEBUG) {
            TextButton(onClick = vm::simulateCrash) { Text("Simulate crash (debug)", color = RtColors.Warning) }
        }
    }

    if (typing) {
        var cName by remember { mutableStateOf("") }
        var cPhone by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Emergency contact") },
            text = {
                Column {
                    OutlinedTextField(cName, { cName = it.take(40) }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        cPhone, { cPhone = it.take(20) }, label = { Text("Phone number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        save(safety.copy(contacts = (safety.contacts + EmergencyContact(cName.trim().ifEmpty { cPhone.trim() }, cPhone.trim())).take(EmergencyContact.MAX)))
                        typing = false
                    },
                    enabled = cPhone.count { it.isDigit() } >= 3,
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
}
