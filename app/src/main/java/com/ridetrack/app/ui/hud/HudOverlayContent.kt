package com.ridetrack.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.sp
import com.ridetrack.app.moments.MomentSource
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.rememberHaptics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ridetrack.app.data.HudLayout
import com.ridetrack.app.data.HudSettings
import com.ridetrack.app.data.HudSize
import com.ridetrack.app.hud.HudData
import com.ridetrack.app.ui.theme.RideDark

/** Callbacks from the pop-up's quick controls. */
interface HudControlActions {
    fun setLayout(layout: HudLayout)
    fun setSize(size: HudSize)
    fun setOpacity(percent: Int)
    fun hideForRide()
    fun openApp()
    fun closeControls()
    fun recordVideo()
    fun pauseVideo()
    fun resumeVideo()
    fun stopVideo()
    fun reconnectMic()
}

@Composable
fun HudOverlayContent(
    data: HudData,
    settings: HudSettings,
    collapsed: Boolean,
    controlsOpen: Boolean,
    actions: HudControlActions,
    viewfinder: ImageBitmap? = null,
    onButtonsTop: (Float) -> Unit = {},
) {
    RideDark {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ScaledBy(settings.size.scale) {
                val video = data.video
                when {
                    collapsed && !controlsOpen -> HudBubble(data, settings)
                    // A chain of events keeps the normal card: its top row counts the video.
                    video != null && video.source != MomentSource.EVENT -> ViewfinderCard(data, video, viewfinder)
                    else -> HudCard(data, settings)
                }
            }
            if (!collapsed && !controlsOpen) {
                ScaledBy(settings.size.scale) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VideoButtons(data, actions, Modifier.onGloballyPositioned { onButtonsTop(it.positionInRoot().y) })
                        data.savedNote?.let { SavedNote(it) }
                    }
                }
            } else {
                SideEffect { onButtonsTop(Float.MAX_VALUE) }
            }
            if (controlsOpen) {
                Spacer(Modifier.height(8.dp))
                HudControls(
                    settings = settings,
                    onLayout = actions::setLayout,
                    onSize = actions::setSize,
                    onOpacity = actions::setOpacity,
                    onHide = actions::hideForRide,
                    onOpenApp = actions::openApp,
                    onClose = actions::closeControls,
                    missingMic = data.micFallback,
                    onReconnectMic = actions::reconnectMic,
                )
            }
        }
    }
}

/** Scales dp and sp together so the card's layout size changes, not just its drawing. */
@Composable
fun ScaledBy(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale), content = content)
}

/**
 * Under the card: a record button that films a video (saved as a moment); while filming,
 * Pause/Resume and Stop. A GPS-lost video stops by itself, so it only offers Stop.
 */
@Composable
private fun VideoButtons(data: HudData, actions: HudControlActions, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val video = data.video
    Row(modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (video == null) {
            RoundButton("Record a video moment", Color(0xE60C0C0E), border = Color.White, borderWidth = 3.dp, size = 60.dp, onClick = { haptics.confirm(); actions.recordVideo() }) {
                Box(Modifier.size(22.dp).background(Color(0xFFFF3B3B), CircleShape))
            }
        } else {
            if (video.source == MomentSource.MANUAL) {
                RoundButton(
                    if (video.paused) "Resume filming" else "Pause filming",
                    if (video.paused) RtColors.Primary else Color(0xE60C0C0E),
                    onClick = { haptics.tick(); if (video.paused) actions.resumeVideo() else actions.pauseVideo() },
                ) {
                    Icon(
                        if (video.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = if (video.paused) RtColors.OnPrimary else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            RoundButton("Stop and save the video", Color(0xE60C0C0E), onClick = { haptics.confirm(); actions.stopVideo() }) {
                Box(Modifier.size(18.dp).background(RtColors.Error, RoundedCornerShape(4.dp)))
            }
        }
    }
}

@Composable
private fun SavedNote(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xEB0C0C0E))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun RoundButton(
    label: String,
    background: Color,
    border: Color = Color.White.copy(alpha = 0.12f),
    borderWidth: Dp = 1.dp,
    size: Dp = 56.dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(background)
            .border(borderWidth, border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}
