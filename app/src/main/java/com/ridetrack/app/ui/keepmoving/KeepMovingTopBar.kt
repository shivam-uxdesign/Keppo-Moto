package com.ridetrack.app.ui.keepmoving

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.keepmoving.NavUpdatesService
import com.ridetrack.app.keepmoving.Planner
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.theme.RtType

/**
 * While Keep moving guides: a bar at the top of every app screen but the directions (like a phone's
 * "call in progress" bar). It shows the next turn, Google's arrow and instruction (shown on screen,
 * never read aloud by the app), turns amber for a jam warning or a clearer-road offer, and a tap
 * goes back to the directions.
 */
@Composable
fun KeepMovingTopBar(onOpen: () -> Unit) {
    val guide = appContainer().keepMoving
    val active by guide.active.collectAsStateWithLifecycle()
    if (!active) return
    val next by NavUpdatesService.next.collectAsStateWithLifecycle()
    val warning by guide.warning.collectAsStateWithLifecycle()
    val offer by guide.offer.collectAsStateWithLifecycle()
    val alert = offer != null || warning != null
    val bg = if (alert) Color(0xFFFBBF24) else Color(0xFF0F5132)
    val fg = if (alert) Color(0xFF1C1300) else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = "Back to directions", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val n = next
        when {
            alert -> Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
            n?.arrow != null -> Image(n.arrow.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp))
            else -> Icon(Icons.Outlined.Directions, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        val text = when {
            offer != null -> "A clearer road · Tap to keep yours or switch"
            warning != null -> "${warning!!.title} · Tap for directions"
            n != null -> listOfNotNull(
                n.inMeters?.let { Planner.km(it) },
                n.instruction.ifBlank { n.road },
            ).joinToString(" · ") + " · Tap for directions"
            else -> "Keep moving · Tap for directions"
        }
        Text(text, style = RtType.bodyStrong, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
