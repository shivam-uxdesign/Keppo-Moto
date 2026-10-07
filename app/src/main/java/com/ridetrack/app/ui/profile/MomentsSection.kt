package com.ridetrack.app.ui.profile

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import com.ridetrack.app.moments.MicChoice
import com.ridetrack.app.moments.MicType
import com.ridetrack.app.moments.Microphones
import android.Manifest
import kotlin.math.roundToInt
import com.ridetrack.telemetry.moments.RollingBuffer
import com.ridetrack.app.moments.AudioEncoder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.MomentSettings
import com.ridetrack.app.data.PhotoInterval
import com.ridetrack.app.data.VideoQuality
import com.ridetrack.app.data.VoiceSensitivity
import com.ridetrack.telemetry.moments.BackgroundLevel
import com.ridetrack.telemetry.moments.SpeechGate
import com.ridetrack.app.moments.SileroVad
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberUpdatedState
import com.ridetrack.app.sensors.Permissions
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SectionHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import java.util.Locale

/** Moments' sub-pages: rarely changed settings, one tap deeper. */
enum class MomentsSub(val title: String) {
    WHAT("What to film"),
    TRANSCRIBE("Write down what I say"),
    STORAGE("Storage"),
    MORE("More settings"),
}

/**
 * Opt-in background capture of clips and photos. One short screen of five rows; the rest is on
 * [MomentsSub] pages, and the explanations are in the "How Moments works" sheet ([showHow]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MomentsSection(
    s: ProfileUiState,
    vm: ProfileViewModel,
    showHeader: Boolean = true,
    sub: MomentsSub? = null,
    onSub: (MomentsSub?) -> Unit = {},
    showHow: Boolean = false,
    onHowDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val m = s.settings.moments
    var explain by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var testQueued by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshMomentsStorage() }
    val editable = !s.rideActive

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val camera = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (camera) vm.setMoments(m.copy(enabled = true)) else denied = true
    }

    if (showHeader) SectionHeader("Moments")
    // While riding, one banner instead of a note on every row.
    if (s.rideActive) {
        Text(
            "Settings are locked while riding. The voice sensitivity can still be tuned (More settings).",
            style = RtType.caption,
            color = RtColors.Warning,
            modifier = Modifier.fillMaxWidth().background(RtColors.Warning.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp),
        )
        Spacer(Modifier.height(RtDimens.sm))
    }
    when (sub) {
        null -> RtCard {
            val mic = MicChoice.decode(m.mic)
            ToggleRow(
                "Capture moments",
                if (m.enabled) "${m.clipSeconds} s clips · ${if (mic.type == MicType.AUTO) "best mic connected" else mic.label}" else "Off",
                m.enabled,
                onChange = { on -> if (!on) vm.setMoments(m.copy(enabled = false)) else explain = true },
                enabled = editable,
            )
            if (m.enabled) {
                Divider()
                SubRow("What to film", whatSummary(m)) { onSub(MomentsSub.WHAT) }
                Divider()
                ToggleRow("Film when I speak", if (m.voice) "On · ${m.voiceSensitivity.label}" else "Off", m.voice, { vm.setMoments(m.copy(voice = it)) })
                Divider()
                SubRow("Write down what I say", transcribeSummary(m)) { onSub(MomentsSub.TRANSCRIBE) }
            }
            Divider()
            SubRow("Storage", "${formatBytes(s.momentsBytes)} · Manage") { onSub(MomentsSub.STORAGE) }
            if (m.enabled) {
                Divider()
                SubRow("More settings", "Clip length, photos, video quality, microphone, voice level") { onSub(MomentsSub.MORE) }
            }
            if (BuildConfig.DEBUG && s.rideActive && vm.canTestClip) {
                // Beta: checks the whole clip path in ~15 s (camera → buffer → MP4 → ride page).
                TextButton(onClick = {
                    vm.testClip()
                    testQueued = true
                }, enabled = !testQueued) {
                    Text(if (testQueued) "Test clip queued: check the ride page after it ends" else "Save a test clip now (beta)", color = RtColors.Primary)
                }
            }
        }
        MomentsSub.WHAT -> RtCard {
            ToggleRow("Hard braking", "At ${gText(m.brakeG)} or more", m.braking, { vm.setMoments(m.copy(braking = it)) }, enabled = editable)
            if (m.braking) ValueMenu("Strength", MomentSettings.BRAKE_CHOICES, m.brakeG, ::gText, editable) { vm.setMoments(m.copy(brakeG = it)) }
            Divider()
            ToggleRow("Strong acceleration", "At ${gText(m.accelG)} or more", m.acceleration, { vm.setMoments(m.copy(acceleration = it)) }, enabled = editable)
            if (m.acceleration) ValueMenu("Strength", MomentSettings.ACCEL_CHOICES, m.accelG, ::gText, editable) { vm.setMoments(m.copy(accelG = it)) }
            Divider()
            ToggleRow("Deep lean", "Past ${m.leanDeg}° · needs a calibrated mount", m.lean, { vm.setMoments(m.copy(lean = it)) }, enabled = editable)
            if (m.lean) ValueMenu("Angle", MomentSettings.LEAN_CHOICES, m.leanDeg, { "$it°" }, editable) { vm.setMoments(m.copy(leanDeg = it)) }
            Divider()
            ToggleRow("When I speak", "From 10 s before you talk until 5 s after", m.voice, { vm.setMoments(m.copy(voice = it)) })
            if (m.twoMics) {
                Divider()
                ToggleRow("Revs and exhaust pops", "From the engine mic (two mics). Studio can open a piece on one.", m.engineMoments, { vm.setMoments(m.copy(engineMoments = it)) }, enabled = editable)
            }
        }
        MomentsSub.TRANSCRIBE -> RtCard { TranscribeSettings(m) { vm.setMoments(it) } }
        MomentsSub.STORAGE -> RtCard {
            var reels by remember { mutableStateOf<Long?>(null) }
            val store = com.ridetrack.app.ui.appContainer().reels
            LaunchedEffect(Unit) { reels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.bytes() } }
            InfoRow("Moments", formatBytes(s.momentsBytes))
            InfoRow("Reels", formatBytes(reels))
            Text("Kept on this phone; nothing is deleted automatically.", style = RtType.caption, color = RtColors.TextTertiary)
            val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
            TextButton(onClick = { nav(com.ridetrack.app.ui.nav.Routes.REELS) }) { Text("Manage Reels", color = RtColors.Primary) }
            if ((s.momentsBytes ?: 0) > 0) {
                TextButton(onClick = { confirmDelete = true }, enabled = editable) { Text("Delete all moments", color = RtColors.Error) }
            }
        }
        MomentsSub.MORE -> RtCard {
            ValueMenu("Clip length", MomentSettings.CLIP_CHOICES, m.clipSeconds, { "$it s before & after" }, editable) { vm.setMoments(m.copy(clipSeconds = it)) }
            Divider()
            ValueMenu("Photos", PhotoInterval.entries, m.photos, { if (it == PhotoInterval.OFF) it.label else "Every ${it.label}" }, editable) { vm.setMoments(m.copy(photos = it)) }
            Text("Plus one photo at each stop longer than a minute.", style = RtType.caption, color = RtColors.TextTertiary)
            Divider()
            ValueMenu("Video quality", VideoQuality.entries, m.quality, { it.label }, editable) { vm.setMoments(m.copy(quality = it)) }
            Divider()
            Spacer(Modifier.height(RtDimens.sm))
            MicPicker(m.mic, editable, m.headsetMic) { vm.setMoments(m.copy(mic = it)) }
            ToggleRow(
                "Allow Bluetooth headset mic",
                "Off: Automatic never records from a Bluetooth headset (your music keeps playing).",
                m.headsetMic,
                { vm.setMoments(m.copy(headsetMic = it)) },
                enabled = editable,
            )
            Divider()
            ToggleRow(
                "Two mics",
                "With a two-transmitter receiver (DJI Mic Mini in mono): your voice and the engine are kept as separate sounds.",
                m.twoMics,
                { vm.setMoments(m.copy(twoMics = it)) },
                enabled = editable,
            )
            if (m.twoMics && !s.rideActive) TwoMicTest(m.mic, m.swapMics) { vm.setMoments(m.copy(swapMics = it)) }
            if (m.voice) {
                Divider()
                Spacer(Modifier.height(RtDimens.sm))
                Label("Voice level")
                // Tunable during a ride: watch the pop-up's meter and adjust.
                VoiceLevel(m.voiceSensitivity, m.mic, s.rideActive) { vm.setMoments(m.copy(voiceSensitivity = it)) }
            }
        }
    }

    if (showHow) MomentsExplainer(onContinue = null, onDismiss = onHowDismiss)
    if (explain) {
        MomentsExplainer(
            onContinue = {
                explain = false
                permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            },
            onDismiss = { explain = false },
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text("Camera access is off") },
            text = { Text("Moments needs the camera. You can allow it in Settings; sound also needs the microphone.") },
            confirmButton = {
                TextButton(onClick = {
                    denied = false
                    Permissions.openAppSettings(context)
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { denied = false }) { Text("Not now") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all moments?") },
            text = { Text("Every clip and photo from all rides moves to Recently deleted, where you can restore them for 30 days. Rides themselves are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteAllMoments()
                }) { Text("Delete", color = RtColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentsExplainer(onContinue: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RtColors.SurfaceRaised,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(RtDimens.md),
        ) {
            Text("How Moments works", style = RtType.headline, color = RtColors.TextPrimary)
            Bullet("During a ride, the selfie camera and microphone keep the last few seconds in memory. Nothing is saved unless something happens.")
            Bullet("On hard braking, strong acceleration or a deep lean, the seconds before and after are saved as a clip. You choose how strong each one must be, and how often a photo is taken.")
            Bullet("No camera screen opens. The ride screen and pop-up show a small CAM dot, and REC while a moment is saved. Android also shows its green camera dot.")
            Bullet("Clips stay on this phone (about 12 MB each at 720p) until you delete them. It uses more battery, and the phone may get warm on the mount; Moments pauses itself if it gets hot.")
            Bullet("Film when I speak starts a clip when you talk (a voice, not horns or engines). Write down what I say turns your words into text with Gemini after the ride.")
            Bullet("While filming your own video from the pop-up, the flip button switches to the back camera for the road ahead.")
            if (onContinue != null) PrimaryButton("Continue", onContinue, large = true)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = RtDimens.md)) {
                Text(if (onContinue != null) "Not now" else "Close", style = RtType.button, color = RtColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = RtType.body, color = RtColors.TextSecondary)
}

@Composable
private fun Pick(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f),
            selectedLabelColor = RtColors.Primary,
            labelColor = RtColors.TextSecondary,
        ),
    )
}

internal fun formatBytes(b: Long?): String = when {
    b == null -> "…"
    b < 1_000_000 -> "none"
    b < 1_000_000_000 -> "${b / 1_000_000} MB"
    else -> String.format(Locale.US, "%.1f GB", b / 1e9)
}

private fun whatSummary(m: MomentSettings): String =
    listOfNotNull("Braking".takeIf { m.braking }, "acceleration".takeIf { m.acceleration }, "lean".takeIf { m.lean }, "when I speak".takeIf { m.voice })
        .joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Nothing yet" }

@Composable
private fun transcribeSummary(m: MomentSettings): String {
    if (!m.transcribe) return "Off"
    val wait = com.ridetrack.app.transcribe.rememberGeminiWait()
    return if (wait.captionsBlocked) "On · free again at ${com.ridetrack.app.transcribe.GeminiQuota.clock(wait.captionsAt!!)}" else "On" + if (m.transcribeWifiOnly) " · Wi-Fi only" else ""
}

@Composable
private fun Divider() = HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))

/** A row that opens a sub-page. */
@Composable
private fun SubRow(title: String, summary: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(summary, style = RtType.caption, color = RtColors.TextSecondary)
        }
        androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = RtColors.TextTertiary)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = RtType.body, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(value, style = RtType.body, color = RtColors.TextSecondary)
    }
}

/** A setting with one value shown as a chip; tapping it opens a small menu of the choices. */
@Composable
private fun <T> ValueMenu(title: String, choices: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(title, style = RtType.body, color = if (enabled) RtColors.TextPrimary else RtColors.TextTertiary, modifier = Modifier.weight(1f))
        Box {
            Pick(label(selected) + "  ▾", true, enabled) { open = true }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = RtColors.SurfaceRaised) {
                choices.forEach { c ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label(c), color = if (c == selected) RtColors.Primary else RtColors.TextPrimary) },
                        onClick = { open = false; onPick(c) },
                    )
                }
            }
        }
    }
}

private fun gText(g: Double): String = String.format(java.util.Locale.US, "%.1f G", g)

/** A row of chips for a trigger's strength; the lower the value, the more often it fires. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(choices: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onPick: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RtDimens.xs),
        modifier = Modifier.padding(start = 4.dp, bottom = RtDimens.xs),
    ) {
        choices.forEach { c -> Pick(label(c), c == selected, enabled) { onPick(c) } }
    }
}

/**
 * Which microphone records clip audio. Automatic (the default) takes the best one connected:
 * a USB-C receiver (DJI Mic), then a wired mic, then the Bluetooth headset, then the phone.
 * A specific mic that isn't connected stays selected; rides use the phone mic until it's back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicPicker(saved: String?, enabled: Boolean, headsetAllowed: Boolean, onPick: (String?) -> Unit) {
    val context = LocalContext.current
    var scan by remember { mutableIntStateOf(0) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scan++ }
    val available = remember(scan) { Microphones.available(context) }
    val current = MicChoice.decode(saved)
    val options = listOf(MicChoice.AUTO) + if (current in available || current.type == MicType.AUTO) available else available + current
    Label("Microphone")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
        options.forEach { choice ->
            val connected = choice.type == MicType.AUTO || choice in available
            Pick(if (connected) choice.label else "${choice.label} (not connected)", choice == current, enabled) {
                onPick(if (choice.type == MicType.AUTO) null else choice.encode())
            }
        }
        Pick("Look for mics", false, enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                scan++
            }
        }
    }
    Text(
        when (current.type) {
            MicType.AUTO -> if (headsetAllowed) {
                "Uses a USB-C mic (like the DJI receiver) when it's plugged in, else your Bluetooth headset, else the phone. " +
                    "A Bluetooth headset can't play music while its mic records, so plug in the USB-C mic to keep your music."
            } else {
                "Uses a USB-C mic (like the DJI receiver) when it's plugged in, else the phone. Never your Bluetooth headset, so your music keeps playing."
            }
            MicType.BLUETOOTH -> "Music on this headset stops while its mic is recording (Bluetooth can't do both). For music and recording together, use a USB-C mic."
            MicType.PHONE -> "Records from the phone's own mic, even when other mics are connected."
            else -> "Clips record from this mic while it's connected; otherwise the phone mic (never your headset, so your music keeps playing)."
        },
        style = RtType.caption,
        color = RtColors.TextSecondary,
    )
}

/**
 * The two-mic test: with the receiver plugged in, a level bar per transmitter, so the rider
 * sees which is the voice and which the engine (and swaps them), and whether they're really two.
 */
@Composable
private fun TwoMicTest(mic: String?, swapped: Boolean, onSwap: (Boolean) -> Unit) {
    val context = LocalContext.current
    var left by remember { mutableStateOf<Float?>(null) }
    var right by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf("") }
    val canListen = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val device = remember(mic) { Microphones.resolve(context, MicChoice.decode(mic), allowHeadset = false)?.takeIf { Microphones.typeOf(it) == MicType.USB } }
    DisposableEffect(device, canListen) {
        val meter = if (device != null && canListen) {
            runCatching {
                AudioEncoder(context, RollingBuffer(2_000_000L), device, twoMics = true, onChannels = { l, r -> left = l; right = r })
            }.getOrNull()
        } else {
            null
        }
        val check = Thread {
            while (meter != null && !Thread.currentThread().isInterrupted) {
                status = when {
                    !meter.recordingTwo -> "This receiver sends one channel: one mic."
                    !meter.twoKnown -> "Talk, then rev: listening…"
                    meter.twoDifferent -> "Two different mics: voice and engine are kept apart."
                    else -> "Both sides sound the same: set the receiver to mono (two transmitters), or it's one mic."
                }
                runCatching { Thread.sleep(400) }.onFailure { return@Thread }
            }
        }.apply { start() }
        onDispose {
            check.interrupt()
            meter?.release()
            left = null
            right = null
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = RtDimens.xs), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            !canListen -> Text("Allow the microphone to test the mics.", style = RtType.caption, color = RtColors.TextSecondary)
            device == null -> Text("Plug in the USB-C receiver to test the two mics.", style = RtType.caption, color = RtColors.TextSecondary)
            else -> {
                ChannelBar(if (swapped) "Engine" else "Voice", "Transmitter 1", left)
                ChannelBar(if (swapped) "Voice" else "Engine", "Transmitter 2", right)
                Text(status, style = RtType.caption, color = RtColors.TextSecondary)
                ToggleRow("Swap voice and engine", "If the bars move the wrong way round when you talk.", swapped, onSwap)
            }
        }
    }
}

@Composable
private fun ChannelBar(role: String, name: String, db: Float?) {
    Column {
        Text("$role · $name", style = RtType.caption, color = RtColors.TextPrimary)
        BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp).background(RtColors.Outline.copy(alpha = 0.5f), RoundedCornerShape(4.dp))) {
            // -60 dBFS (quiet) to 0 (loudest).
            val f = db?.let { ((it + 60f) / 60f).coerceIn(0f, 1f) } ?: 0f
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(RtColors.Primary, RoundedCornerShape(4.dp)))
        }
    }
}

/**
 * How far the mic is above the background noise right now, against the sensitivity margin,
 * and the sensitivity choice. During a ride the level comes from Moments; otherwise the
 * chosen mic is opened just while this is on screen, with the same background tracking and
 * 1.5 s rule as the ride, so "Films now" here means it would film on the bike.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceLevel(sensitivity: VoiceSensitivity, mic: String?, rideActive: Boolean, onSensitivity: (VoiceSensitivity) -> Unit) {
    val context = LocalContext.current
    val hub = com.ridetrack.app.ui.appContainer().momentsHub
    val rideLevel by hub.micLevel.collectAsStateWithLifecycle()
    val rideSpeaking by hub.speaking.collectAsStateWithLifecycle()
    var localLevel by remember { mutableStateOf<Float?>(null) }
    var localSpeaking by remember { mutableStateOf(false) }
    val margin by rememberUpdatedState(sensitivity.marginDb)
    val canListen = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    DisposableEffect(rideActive, mic, canListen) {
        val background = BackgroundLevel()
        val gate = SpeechGate()
        val vad = if (!rideActive && canListen) SileroVad.load(context) else null
        val meter = if (!rideActive && canListen) {
            runCatching {
                val device = Microphones.resolve(context, MicChoice.decode(mic))
                AudioEncoder(context, RollingBuffer(2_000_000L), device, vad = vad, onLevel = { t, db, len, voice ->
                    val above = db - background.onLevel(t, db)
                    localSpeaking = gate.onLevel(t, above, len, margin, voice)
                    localLevel = above
                })
            }.getOrNull()
        } else {
            null
        }
        onDispose {
            meter?.release()
            vad?.close()
            localLevel = null
            localSpeaking = false
        }
    }
    val level = if (rideActive) rideLevel else localLevel
    val speaking = if (rideActive) rideSpeaking else localSpeaking
    val loud = level != null && level >= sensitivity.marginDb
    Column(Modifier.fillMaxWidth().padding(bottom = RtDimens.xs)) {
        Text(
            when {
                !canListen -> "Allow the microphone to see the level."
                level == null -> "Films at +${sensitivity.marginDb.roundToInt()} dB over the background, held about 1.5 s"
                speaking -> "Films now · +${level.roundToInt()} dB over the background"
                else -> "+${level.roundToInt()} dB over the background · films at +${sensitivity.marginDb.roundToInt()} dB, held about 1.5 s"
            },
            style = RtType.caption,
            color = if (speaking) RtColors.Primary else RtColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp).background(RtColors.Outline.copy(alpha = 0.5f), RoundedCornerShape(4.dp))) {
            val f = level?.let { (it / VOICE_METER_MAX).coerceIn(0f, 1f) } ?: 0f
            Box(
                Modifier.fillMaxWidth(f).fillMaxHeight().background(
                    when {
                        speaking -> RtColors.Primary
                        loud -> RtColors.Primary.copy(alpha = 0.5f)
                        else -> RtColors.TextTertiary
                    },
                    RoundedCornerShape(4.dp),
                ),
            )
            // The margin.
            Box(Modifier.offset(x = maxWidth * (sensitivity.marginDb / VOICE_METER_MAX)).width(2.dp).fillMaxHeight().background(RtColors.TextPrimary))
        }
        Spacer(Modifier.height(10.dp))
        Text("Sensitivity", style = RtType.caption, color = RtColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
            VoiceSensitivity.entries.forEach { v -> Pick(v.label, sensitivity == v, true) { onSensitivity(v) } }
        }
        Text(
            "Strict needs clear, firm talking; Normal also hears quieter speech. Either way it has to sound like a voice, so horns, engines and wind don't film, and it keeps up with the wind as your speed changes.",
            style = RtType.caption,
            color = RtColors.TextTertiary,
        )
    }
}

/**
 * "Write down what I say": talking clips are transcribed with Gemini (through the app's Firebase
 * project) after the ride. Test builds show the App Check debug token to register once.
 */
@Composable
private fun TranscribeSettings(m: MomentSettings, onChange: (MomentSettings) -> Unit) {
    val context = LocalContext.current
    val t = com.ridetrack.app.ui.appContainer().transcripts
    val status by t.status.collectAsStateWithLifecycle()
    ToggleRow(
        "Write down what I say",
        "After the ride, what you say in talking clips is written out in Hinglish (English letters) with Google's Gemini. " +
            "The clips' sound is sent to Google for this; on the free tier Google may use it to improve its products.",
        m.transcribe,
        { on ->
            onChange(m.copy(transcribe = on))
            if (on) t.schedule(m.transcribeWifiOnly)
        },
        enabled = t.available,
    )
    if (!t.available) {
        Text("Not available in this build (it needs the app's Firebase setup).", style = RtType.caption, color = RtColors.TextTertiary)
        return
    }
    if (!m.transcribe) return
    Column(Modifier.fillMaxWidth().padding(bottom = RtDimens.xs), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleRow("Only on Wi-Fi", "Uses no mobile data.", m.transcribeWifiOnly, { onChange(m.copy(transcribeWifiOnly = it)) })
        val wait = com.ridetrack.app.transcribe.rememberGeminiWait()
        val waitLine = wait.line.takeIf { wait.captionsBlocked }
        Text(
            when {
                waitLine != null -> waitLine + if (status.waiting > 0) " · ${status.waiting} clips waiting" else ""
                status.lastError != null -> status.lastError!!
                status.waiting > 0 -> "${status.waiting} clips waiting to be written out."
                else -> "Talking clips are written out after each ride (clips from before this was turned on are skipped)."
            },
            style = RtType.caption,
            color = if (status.lastError != null || waitLine != null) RtColors.Warning else RtColors.TextTertiary,
        )
        // Test builds only: the App Check debug token, added once in the Firebase console.
        com.ridetrack.app.transcribe.AppCheckSetup.debugToken(context)?.let { token ->
            Text("App Check test token (add it once in Firebase › App Check › Apps › Manage debug tokens):", style = RtType.caption, color = RtColors.TextSecondary)
            Text(token, style = RtType.caption, color = RtColors.TextPrimary)
            Pick("Copy token", false, true) {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("App Check debug token", token))
            }
        }
    }
}

/** dB over the background at the right end of the meter. */
private const val VOICE_METER_MAX = 24f

