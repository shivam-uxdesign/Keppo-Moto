package com.ridetrack.app.ui.bike

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.obd.ObdAdapter
import com.ridetrack.app.obd.ObdState
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.components.StatTile
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.obd.Elm327
import com.ridetrack.telemetry.obd.EngineWatch
import com.ridetrack.telemetry.obd.FaultCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Bike › OBD: pair the bike's adapter, test it (what the bike answers, live), read and clear
 * fault codes, the alert limits, and a report to share. Leaving the screen stops the test
 * (a ride keeps its own connection).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObdScreen(bikeId: String, onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val state by c.obd.state.collectAsStateWithLifecycle()
    val log by c.obd.log.collectAsStateWithLifecycle()
    var adapter by remember { mutableStateOf<ObdAdapter?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(settings?.obdAdapters) {
        adapter = c.obd.adapterFor(bikeId)
        loaded = true
    }
    DisposableEffect(bikeId) { onDispose { c.obd.stopTest() } }
    val testing = state.bikeId == bikeId && state.phase != ObdState.Phase.IDLE

    val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val granted = { permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (granted()) picking = true }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader("OBD", onBack = onBack)
        Text(
            "Your bike's diagnostic adapter: engine RPM and gear in rides and on the HUD, fault codes in plain words, and a warning if the engine runs hot or the battery stops charging.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
        )
        Spacer(Modifier.height(RtDimens.md))

        // ---- Adapter ----
        RtCard {
            Label("Adapter")
            val a = adapter
            if (a == null) {
                Text(if (loaded) "None paired yet. Turn the key on so the adapter has power, then pick it." else "…", style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.padding(vertical = 6.dp))
                PrimaryButton("Find my adapter", onClick = { if (granted()) picking = true else ask.launch(permissions) })
            } else {
                Text(a.name, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                Text(a.address + (state.linkKind?.let { " · $it" } ?: ""), style = RtType.caption, color = RtColors.TextSecondary)
                Row {
                    TextButton(onClick = { if (granted()) picking = true else ask.launch(permissions) }) { Text("Change", color = RtColors.Primary) }
                    TextButton(onClick = { scope.launch { c.obd.forget(bikeId) } }) { Text("Forget", color = RtColors.TextSecondary) }
                }
            }
        }

        // ---- Test my bike ----
        if (adapter != null) {
            Spacer(Modifier.height(RtDimens.sm))
            RtCard {
                Label("Test my bike")
                Text(statusLine(state, testing), style = RtType.body, color = if (state.phase == ObdState.Phase.FAILED || state.phase == ObdState.Phase.NO_ECU) RtColors.Warning else RtColors.TextPrimary)
                state.message?.takeIf { testing }?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
                listOfNotNull(state.adapter, state.protocol?.let { "Protocol: $it" }).takeIf { testing && it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = RtType.caption, color = RtColors.TextTertiary)
                }
                Spacer(Modifier.height(RtDimens.sm))
                if (testing) {
                    val v = state.values
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.sm), verticalArrangement = Arrangement.spacedBy(RtDimens.sm)) {
                        Tile("Battery", state.volts?.let { "%.1f".format(Locale.US, it) } ?: "–", "V")
                        Tile("Engine", when (state.engine) { EngineWatch.State.RUNNING -> "On"; EngineWatch.State.KEY_ON -> "Off"; else -> "–" }, null)
                        Tile("Gear", state.gear?.let { if (it == 0) "N" else "$it" } ?: "–", null)
                        (listOf(Elm327.Pid.RPM, Elm327.Pid.SPEED) + state.supported.filterNot { it == Elm327.Pid.RPM || it == Elm327.Pid.SPEED }).forEach { p ->
                            if (p in state.supported) Tile(p.label, v[p]?.let { fmt(p, it) } ?: "…", p.unit)
                        }
                    }
                    if (state.phase == ObdState.Phase.NO_ECU || state.searching != null) {
                        Spacer(Modifier.height(RtDimens.sm))
                        val searching = state.searching
                        if (searching != null) {
                            Text(searching, style = RtType.body, color = RtColors.Primary)
                            Text("Keep the key on. Each try takes up to 10 s.", style = RtType.caption, color = RtColors.TextSecondary)
                        } else {
                            Text(
                                "Many bikes talk on the K-line, which the adapter's own search often misses. Try each way by hand: CAN at every speed, then K-line with each start-up and the usual ECU addresses. Whatever works is remembered.",
                                style = RtType.caption,
                                color = RtColors.TextSecondary,
                            )
                            Spacer(Modifier.height(RtDimens.xs))
                            PrimaryButton("Try every protocol (about 2 min)", onClick = { c.obd.searchProtocols() })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Gears learned: ${state.gearsLearned} of 6" + if (state.gearsLearned < 6) " (ride steadily in each gear)" else "", style = RtType.caption, color = RtColors.TextSecondary)
                    Row {
                        SecondaryButton("Stop", onClick = { c.obd.stopTest() })
                        Spacer(Modifier.width(RtDimens.sm))
                        TextButton(onClick = { scope.launch { c.obd.relearnGears(bikeId) } }) { Text("Relearn gears", color = RtColors.TextSecondary) }
                    }
                } else {
                    Text("Key on (engine on or off), then connect. It shows what your bike reports, live.", style = RtType.caption, color = RtColors.TextSecondary)
                    Spacer(Modifier.height(RtDimens.xs))
                    PrimaryButton("Connect", onClick = { if (granted()) c.obd.test(bikeId) else ask.launch(permissions) })
                }
            }

            // ---- Fault codes ----
            Spacer(Modifier.height(RtDimens.sm))
            RtCard {
                Label("Fault codes")
                val codes = state.codes
                when {
                    !testing -> Text("Connect to read them.", style = RtType.caption, color = RtColors.TextSecondary)
                    codes == null -> Text("Not read yet.", style = RtType.caption, color = RtColors.TextSecondary)
                    codes.isEmpty() -> Text("None: no warning-light codes stored.", style = RtType.body, color = RtColors.Ok)
                    else -> codes.forEach { code ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(code, style = RtType.bodyStrong, color = RtColors.Warning)
                            Text(FaultCodes.describe(code), style = RtType.caption, color = RtColors.TextSecondary)
                        }
                    }
                }
                if (testing && state.phase == ObdState.Phase.CONNECTED) Row {
                    TextButton(onClick = { c.obd.readCodes() }) { Text("Read again", color = RtColors.Primary) }
                    if (!codes.isNullOrEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear codes", color = RtColors.Error) }
                }
            }

            // ---- Alerts ----
            Spacer(Modifier.height(RtDimens.sm))
            RtCard {
                Label("Warn me during a ride")
                val s = settings
                if (s != null) {
                    Text("Engine hotter than", style = RtType.caption, color = RtColors.TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                        listOf(100, 105, 110, 115).forEach { t ->
                            Pick("$t °C", s.obdCoolantAlertC == t) { scope.launch { c.settings.setObdAlerts(t, s.obdLowVoltTenths) } }
                        }
                    }
                    Text("Battery below, with the engine running", style = RtType.caption, color = RtColors.TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                        listOf(120, 122, 124).forEach { t ->
                            Pick("%.1f V".format(Locale.US, t / 10.0), s.obdLowVoltTenths == t) { scope.launch { c.settings.setObdAlerts(s.obdCoolantAlertC, t) } }
                        }
                    }
                    Text("A new fault code during a ride is always shown.", style = RtType.caption, color = RtColors.TextTertiary)
                }
            }

            // ---- Report ----
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(RtDimens.sm))
                TextButton(onClick = {
                    val text = (listOf("Keppo Moto OBD test · ${adapter?.name}") + log).joinToString("\n")
                    runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share OBD test").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Share test report", color = RtColors.Primary) }
            }
        }
        Spacer(Modifier.height(RtDimens.lg))
    }

    if (picking) AdapterPicker(onPick = { d, name ->
        picking = false
        scope.launch { c.obd.pair(bikeId, d.address, name) }
    }, onDismiss = { picking = false })

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear fault codes?") },
        text = { Text("This turns the warning light off. If the fault is still there it comes back. The service centre can still see it was cleared.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; c.obd.clearCodes() }) { Text("Clear", color = RtColors.Error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

private fun statusLine(s: ObdState, testing: Boolean): String = if (!testing) "Not connected" else when (s.phase) {
    ObdState.Phase.CONNECTING -> "Connecting… (finding the bike's protocol can take 10 s)"
    ObdState.Phase.CONNECTED -> "Connected · ${s.supported.size} readings from the bike"
    ObdState.Phase.NO_ECU -> "Adapter connected · bike not answering (voltage only)"
    ObdState.Phase.FAILED -> "Couldn't connect"
    ObdState.Phase.IDLE -> "Not connected"
}

private fun fmt(p: Elm327.Pid, v: Double): String = when (p) {
    Elm327.Pid.MODULE_VOLTAGE -> "%.1f".format(Locale.US, v)
    else -> v.toInt().toString()
}

@Composable
private fun Tile(label: String, value: String, unit: String?) {
    StatTile(label, value, Modifier.width(96.dp), unit = unit)
}

@Composable
private fun Pick(text: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text) },
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RtColors.PrimaryContainer),
)

/** Paired (classic) adapters from Android's list, plus BLE ones found nearby. */
@SuppressLint("MissingPermission")
@Composable
private fun AdapterPicker(onPick: (BluetoothDevice, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bt = remember { context.getSystemService<BluetoothManager>()?.adapter }
    val bonded = remember { runCatching { bt?.bondedDevices?.toList() }.getOrNull().orEmpty().sortedByDescending { likely(it.name) } }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val scanner = runCatching { bt?.bluetoothLeScanner }.getOrNull()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                if (d.name != null && found.none { it.address == d.address } && bonded.none { it.address == d.address }) found += d
            }
        }
        runCatching { scanner?.startScan(cb) }
        onDispose { runCatching { scanner?.stopScan(cb) } }
    }
    LaunchedEffect(Unit) { delay(12_000); scanning = false }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick your adapter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (bt?.isEnabled != true) Text("Bluetooth is off: turn it on first.", color = RtColors.Warning, style = RtType.caption)
                if (bonded.isNotEmpty()) {
                    Text("Paired with this phone", style = RtType.caption, color = RtColors.TextSecondary)
                    bonded.forEach { d -> DeviceRow(d) { onPick(d, d.name ?: d.address) } }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (scanning) "Looking for BLE adapters nearby…" else "Found nearby", style = RtType.caption, color = RtColors.TextSecondary)
                found.sortedByDescending { likely(it.name) }.forEach { d -> DeviceRow(d) { onPick(d, d.name ?: d.address) } }
                Spacer(Modifier.height(8.dp))
                Text("Not listed? If it pairs with a PIN (usually 1234 or 0000), pair it in Android's Bluetooth settings first, then come back.", style = RtType.caption, color = RtColors.TextTertiary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceRow(d: BluetoothDevice, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp)) {
        Text(d.name ?: d.address, style = RtType.body, color = if (likely(d.name)) RtColors.TextPrimary else RtColors.TextSecondary)
        Text(d.address + if (likely(d.name)) " · looks like an OBD adapter" else "", style = RtType.caption, color = RtColors.TextTertiary)
    }
    HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.4f))
}

/** Names cheap ELM327 adapters use. */
private fun likely(name: String?): Boolean = name != null && listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "OBDII", "CAR").any { name.uppercase(Locale.US).contains(it) }
