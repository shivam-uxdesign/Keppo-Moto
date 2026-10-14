package com.ridetrack.app.ui.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridetrack.app.pro.FreeReelRule
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch

/**
 * Over Studio once a free rider's weekly Reel is made: when the next one comes, Unlock (the
 * paywall, with its free week) and Restore purchase. It covers the screen and takes the touches,
 * so what's behind (blurred) can be seen but not used.
 */
@Composable
fun StudioLocked(nextAt: Long?, onUnlock: () -> Unit, modifier: Modifier = Modifier, onOpenReels: (() -> Unit)? = null) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(RtDimens.screenPadding).fillMaxWidth().background(RtColors.Surface, RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(48.dp).background(RtColors.Primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = RtColors.Primary)
            }
            Text("This week's free Reel is made", style = RtType.headline, color = RtColors.TextPrimary, textAlign = TextAlign.Center)
            Text(
                (nextAt?.let { "Your next free Reel comes ${FreeReelRule.wait(it, System.currentTimeMillis())}. " } ?: "") +
                    "With Pro, make Reels from every ride, any time.",
                style = RtType.body,
                color = RtColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            PrimaryButton("Unlock Studio · 1 week free", onClick = onUnlock, large = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text(
                "Restore purchase",
                style = RtType.button,
                color = RtColors.Primary,
                modifier = Modifier.clickable(role = Role.Button) {
                    note = "Checking with Google Play…"
                    scope.launch {
                        note = when (c.billing.refresh()) {
                            true -> "Pro restored."
                            false -> "No Keppo Moto Pro subscription on this Google account."
                            null -> "Couldn't reach Google Play. Check you're signed in and online."
                        }
                    }
                }.padding(8.dp),
            )
            note?.let { Text(it, style = RtType.caption, color = RtColors.TextTertiary, textAlign = TextAlign.Center) }
            // The Reels already made stay free to watch and share.
            onOpenReels?.let { open ->
                Text("Your Reels ›", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button, onClick = open).padding(8.dp))
            }
        }
    }
}

/** For a free rider who still has this week's Reel: says so, and that Pro has no limit. */
@Composable
fun FreeReelChip(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "1 free Reel this week · Go Pro ›",
        style = RtType.caption,
        color = RtColors.TextPrimary,
        modifier = modifier.background(RtColors.SurfaceRaised, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onUnlock).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
