package com.ridetrack.app.studio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType

/**
 * Studio's bottom sheet: rises from the bottom over a fading backdrop; tap the backdrop, swipe
 * the sheet down or press Back to close it. Put it last in a full-size Box.
 */
@Composable
fun StudioSheet(visible: Boolean, onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (visible) BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = "Close", onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320)) { it },
            exit = slideOutVertically(tween(240)) { it },
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(RtColors.Surface)
                    .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The grabber: swipe down on it (or the title) to close.
                Column(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        var pulled = 0f
                        detectVerticalDragGestures(onDragStart = { pulled = 0f }, onDragEnd = { if (pulled > 60f) onDismiss() }) { _, dy -> pulled += dy }
                    },
                ) {
                    Box(Modifier.padding(top = 10.dp, bottom = 12.dp).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(RtColors.Outline).align(Alignment.CenterHorizontally))
                    title?.let { Text(it, style = RtType.title, color = RtColors.TextPrimary, modifier = Modifier.padding(bottom = 8.dp)) }
                }
                content()
            }
        }
    }
}

/** One choice in a [StudioSheet]: a line of text, red when it removes something. */
@Composable
fun SheetRow(label: String, detail: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp)) {
        Text(label, style = RtType.body, color = if (danger) RtColors.Error else RtColors.TextPrimary)
        detail?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
    }
}

/** A round icon button for Studio's top bars (44 dp, so it's easy to hit). */
@Composable
fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(if (primary) RtColors.Primary else RtColors.SurfaceRaised)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = label, tint = if (primary) RtColors.OnPrimary else RtColors.TextPrimary, modifier = Modifier.size(22.dp))
    }
}
