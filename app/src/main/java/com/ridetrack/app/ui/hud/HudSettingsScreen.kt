package com.ridetrack.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.data.HudLayout
import com.ridetrack.app.data.HudSettings
import com.ridetrack.app.data.HudSize
import com.ridetrack.app.data.HudTheme
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.ridetrack.app.data.VoiceSensitivity
import com.ridetrack.app.hud.HudData
import com.ridetrack.app.hud.HudStatus
import com.ridetrack.app.hud.OverlayPermission
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class HudSettingsViewModel(private val c: AppContainer) : ViewModel() {
    val hud: StateFlow<HudSettings> = c.settings.settings.map { it.hud }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HudSettings())

    /** "Film when I speak" and its sensitivity: the preview shows the mic meter when it's on. */
    val voice: StateFlow<Pair<Boolean, VoiceSensitivity>> = c.settings.settings.map { (it.moments.enabled && it.moments.voice) to it.moments.voiceSensitivity }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to VoiceSensitivity.STRICT)

    fun setEnabled(v: Boolean) { viewModelScope.launch { c.settings.setHudEnabled(v) } }
    fun setLayout(v: HudLayout) { viewModelScope.launch { c.settings.setHudLayout(v) } }
    fun setSize(v: HudSize) { viewModelScope.launch { c.settings.setHudSize(v) } }
    fun setOpacity(v: Int) { viewModelScope.launch { c.settings.setHudOpacity(v) } }
    fun setTheme(v: HudTheme) { viewModelScope.launch { c.settings.setHudTheme(v) } }
}

/**
 * Sample values for the settings preview only. The preview is labelled PREVIEW and never
 * shown as ride data.
 */
private val previewData = HudData(
    status = HudStatus.RECORDING, stoppedForMillis = null, speedMps = 20.0, leanDeg = 18.0, leanNote = null,
    distanceM = 24_600.0, elapsedMillis = 2_292_000, avgSpeedMps = 11.4, maxSpeedMps = 24.2,
    longitudinalG = 0.32, combinedG = 0.41, maxLeanDeg = 28.0, headingDeg = 212.0, demo = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HudSettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { HudSettingsViewModel(it) }
    val s by vm.hud.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    // A sample mic level that drifts with the "wind", then rises over the margin as if talking.
    val phase by rememberInfiniteTransition(label = "mic").animateFloat(
        0f, 1f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "micPhase",
    )
    val sampleLevel = if (phase < 0.55f) 3f + 3f * kotlin.math.sin(phase * 40f) else voice.second.marginDb + 4f + 2f * kotlin.math.sin(phase * 50f)
    val preview = if (voice.first) {
        previewData.copy(voiceOn = true, micLevelDb = sampleLevel, voiceMarginDb = voice.second.marginDb, speaking = phase >= 0.65f)
    } else {
        previewData
    }
    val context = LocalContext.current
    var granted by remember { mutableStateOf(OverlayPermission.isGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = OverlayPermission.isGranted(context) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader("Pop-up HUD", onBack = onBack)

        Box(
            Modifier
                .fillMaxWidth()
                .height((if (s.layout == HudLayout.MINIMAL) 196.dp else 260.dp) + if (voice.first) 24.dp else 0.dp)
                .background(RtColors.Surface, RoundedCornerShape(RtDimens.cardRadius))
                .border(1.dp, RtColors.SurfaceRaised, RoundedCornerShape(RtDimens.cardRadius)),
        ) {
            Label("Preview", Modifier.align(Alignment.BottomStart).padding(16.dp), color = RtColors.TextTertiary)
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                ScaledBy(minOf(s.size.scale, 1f)) { HudCard(preview, s) }
            }
        }
        Spacer(Modifier.height(RtDimens.cardSpacing))

        RtCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = s.enabled, role = Role.Switch, onValueChange = vm::setEnabled)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show pop-up in other apps", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text(
                        "Appears when you leave Keppo Moto during a ride; hides when you come back.",
                        style = RtType.caption,
                        color = RtColors.TextSecondary,
                    )
                }
                Spacer(Modifier.width(RtDimens.md))
                Switch(
                    checked = s.enabled,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedTrackColor = RtColors.Primary, checkedThumbColor = RtColors.OnPrimary),
                )
            }
            HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Display over other apps", style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text("Android permission", style = RtType.caption, color = RtColors.TextSecondary)
                }
                if (granted) {
                    Box(Modifier.size(8.dp).background(RtColors.Ok, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("Allowed", style = RtType.body, color = RtColors.TextPrimary)
                } else {
                    OutlinedButton(
                        onClick = { OverlayPermission.request(context) },
                        border = androidx.compose.foundation.BorderStroke(1.dp, RtColors.Primary),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("ALLOW", style = RtType.button, color = RtColors.Primary) }
                }
            }
        }

        Label("Appearance", Modifier.padding(top = RtDimens.lg, bottom = RtDimens.sm))
        RtCard {
            Text("Layout", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Spacer(Modifier.height(RtDimens.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs), verticalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                HudLayout.entries.forEach { l -> Choice(l.label, s.layout == l) { vm.setLayout(l) } }
            }
            Text(s.layout.description, style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(RtDimens.lg))
            Text("Size", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Spacer(Modifier.height(RtDimens.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                HudSize.entries.forEach { z -> Choice(z.label, s.size == z) { vm.setSize(z) } }
            }

            Spacer(Modifier.height(RtDimens.lg))
            var opacity by remember(s.opacity) { mutableFloatStateOf(s.opacity.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Background opacity", style = RtType.bodyStrong, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
                Text("${opacity.roundToInt()}%", style = RtType.body, color = RtColors.TextPrimary)
            }
            Slider(
                value = opacity,
                onValueChange = { opacity = it },
                onValueChangeFinished = { vm.setOpacity(opacity.roundToInt()) },
                valueRange = HudSettings.MIN_OPACITY.toFloat()..HudSettings.MAX_OPACITY.toFloat(),
                steps = 7,
                enabled = s.theme == HudTheme.DARK,
                colors = SliderDefaults.colors(thumbColor = RtColors.Primary, activeTrackColor = RtColors.Primary, inactiveTrackColor = RtColors.Outline),
            )

            Spacer(Modifier.height(RtDimens.sm))
            Text("Theme", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Spacer(Modifier.height(RtDimens.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                HudTheme.entries.forEach { t -> Choice(t.label, s.theme == t) { vm.setTheme(t) } }
            }
        }
        Text(
            "Drag the pop-up to move it — it remembers where you left it. Drag it onto the ✕ at the bottom to hide it for this ride. " +
                "Long-press for quick controls; double-tap to shrink it to a speed bubble.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
            modifier = Modifier.padding(top = RtDimens.md, bottom = RtDimens.lg),
        )
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = RtColors.Primary.copy(alpha = 0.18f),
            selectedLabelColor = RtColors.Primary,
            labelColor = RtColors.TextSecondary,
        ),
    )
}
