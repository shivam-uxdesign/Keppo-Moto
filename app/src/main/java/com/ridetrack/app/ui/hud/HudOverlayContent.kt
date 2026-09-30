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
import com.ridetrack.app.hud.HudStatus
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
import com.ridetrack.app.ui.theme.RideTrackTheme

/** Callbacks from the pop-up's quick controls. */
interface HudControlActions {
    fun setLayout(layout: HudLayout)
    fun setSize(size: HudSize)
    fun setOpacity(percent: Int)
    fun hideForRide()
    fun openApp()
    fun closeControls()
    fun pauseRide()
    fun resumeRide()
    fun stopRide()
    fun startRide()
}

@Composable
fun HudOverlayContent(
    data: HudData,
    settings: HudSettings,
    collapsed: Boolean,
    controlsOpen: Boolean,
    actions: HudControlActions,
    onButtonsTop: (Float) -> Unit = {},
) {
    RideTrackTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ScaledBy(settings.size.scale) {
                if (collapsed && !controlsOpen) HudBubble(data, settings) else HudCard(data, settings)
            }
            if (!collapsed && !controlsOpen) {
                ScaledBy(settings.size.scale) {
                    RideButtons(data, actions, Modifier.onGloballyPositioned { onButtonsTop(it.positionInRoot().y) })
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

/** Under the card: Pause/Resume and Stop while riding; a record button between rides. */
@Composable
private fun RideButtons(data: HudData, actions: HudControlActions, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Row(modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        when (data.status) {
            HudStatus.IDLE -> RoundButton("Start recording a ride", Color(0xE60C0C0E), border = Color.White, borderWidth = 3.dp, size = 60.dp, onClick = { haptics.confirm(); actions.startRide() }) {
                Box(Modifier.size(22.dp).background(Color(0xFFFF3B3B), CircleShape))
            }
            else -> {
                val paused = data.status == HudStatus.PAUSED
                RoundButton(
                    if (paused) "Resume ride" else "Pause ride",
                    if (paused) RtColors.Primary else Color(0xE60C0C0E),
                    onClick = { haptics.tick(); if (paused) actions.resumeRide() else actions.pauseRide() },
                ) {
                    Icon(
                        if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = if (paused) RtColors.OnPrimary else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                RoundButton("Stop and save the ride", Color(0xE60C0C0E), onClick = { haptics.confirm(); actions.stopRide() }) {
                    Box(Modifier.size(18.dp).background(RtColors.Error, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
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
