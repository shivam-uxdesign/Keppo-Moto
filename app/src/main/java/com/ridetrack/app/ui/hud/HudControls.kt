package com.ridetrack.app.ui.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.ridetrack.app.data.HudLayout
import com.ridetrack.app.data.HudSettings
import com.ridetrack.app.data.HudSize
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlin.math.roundToInt

/** Quick controls shown under the pop-up after a long-press. */
@Composable
fun HudControls(
    settings: HudSettings,
    onLayout: (HudLayout) -> Unit,
    onSize: (HudSize) -> Unit,
    onOpacity: (Int) -> Unit,
    onHide: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(300.dp)
            .background(RtColors.SurfaceRaised, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pop-up", style = RtType.headline, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close controls", tint = RtColors.TextSecondary)
            }
        }
        Text("LAYOUT", style = RtType.label, color = RtColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudLayout.entries.forEach { l ->
                Segment(l.label, settings.layout == l, Modifier.weight(1f)) { onLayout(l) }
            }
        }
        Text("SIZE", style = RtType.label, color = RtColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudSize.entries.forEach { s ->
                Segment(s.label.take(1), settings.size == s, Modifier.weight(1f)) { onSize(s) }
            }
        }
        var opacity by remember(settings.opacity) { mutableFloatStateOf(settings.opacity.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OPACITY", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
            Text("${opacity.roundToInt()}%", style = RtType.metricS.copy(fontSize = RtType.caption.fontSize), color = RtColors.TextPrimary)
        }
        Slider(
            value = opacity,
            onValueChange = { opacity = it },
            onValueChangeFinished = { onOpacity(opacity.roundToInt()) },
            valueRange = HudSettings.MIN_OPACITY.toFloat()..HudSettings.MAX_OPACITY.toFloat(),
            steps = 7,
            colors = SliderDefaults.colors(thumbColor = RtColors.Primary, activeTrackColor = RtColors.Primary, inactiveTrackColor = RtColors.Outline),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onHide,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, RtColors.Outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RtColors.TextPrimary),
            ) { Text("HIDE", style = RtType.button) }
            Button(
                onClick = onOpenApp,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RtColors.Primary, contentColor = RtColors.OnPrimary),
            ) { Text("OPEN APP", style = RtType.button) }
        }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) RtColors.Primary else RtColors.TextSecondary
    Box(
        modifier
            .height(44.dp)
            .background(if (selected) RtColors.Primary.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) RtColors.Primary else RtColors.Outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = RtType.caption, color = color, maxLines = 1)
    }
}

/** "Drag here to hide" target shown at the bottom of the screen while dragging. */
@Composable
fun HudDismissTarget(active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
        Box(
            Modifier
                .size(if (active) 76.dp else 64.dp)
                .background(if (active) RtColors.Error else RtColors.SurfaceRaised.copy(alpha = 0.95f), CircleShape)
                .border(2.dp, if (active) RtColors.Error else RtColors.TextPrimary.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = RtColors.TextPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (active) "Release to hide" else "Drag here to hide",
            style = RtType.body,
            color = RtColors.TextPrimary,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
