package com.ridetrack.app.ui.detail

import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import com.ridetrack.app.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.moments.momentColor
import com.ridetrack.app.ui.moments.momentTitle
import com.ridetrack.app.ui.theme.RtType

/** Side of the pop-up card; it reaches [POPUP_OVERLAP] below the map onto the timeline. */
val POPUP_SIZE = 148.dp
val POPUP_OVERLAP = 24.dp

/**
 * A moment reached during replay: a small square card at the bottom-left of the map while
 * the replay runs through it at 1×, so the bike stays in view. Tap it to open; tap anywhere
 * else to skip it (nothing is dimmed); Skip all plays straight through.
 */
@Composable
fun MomentPopup(moment: Moment, time: Double, playing: Boolean, onSkip: () -> Unit, onSkipAll: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Skip this moment", onClick = onSkip),
    ) { PopupCard(moment, time, playing, onSkipAll, onOpen, modifier) }
}

@Composable
private fun PopupCard(moment: Moment, time: Double, playing: Boolean, onSkipAll: () -> Unit, onOpen: () -> Unit, modifier: Modifier) {
    val window = remember(moment) { MomentWindow.of(moment) }
    val length = (window.endMillis - window.startMillis).coerceAtLeast(1L)
    val progress = ((time - window.startMillis) / length).toFloat().coerceIn(0f, 1f)
    val shape = RoundedCornerShape(16.dp)
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(POPUP_SIZE)
                .shadow(12.dp, shape)
                .clip(shape)
                .background(Color.Black)
                .border(1.5.dp, momentColor(moment), shape)
                .clickable(role = Role.Button, onClickLabel = "Open moment", onClick = onOpen)
                .semantics { contentDescription = "${momentTitle(moment)}. Tap to open." },
        ) {
            if (moment.kind == MomentKind.CLIP) {
                PopupClip(moment, (time - moment.videoStartMillis).toLong(), playing)
            } else {
                Thumb(moment.file, Modifier.fillMaxSize(), maxEdge = 720)
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(momentColor(moment), CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(momentTitle(moment), style = RtType.caption.copy(fontSize = 11.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                val mark = ((moment.timeMillis - window.startMillis).toFloat() / length).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = r)
                    drawRoundRect(Color.White, size = Size(size.width * progress, size.height), cornerRadius = r)
                    drawCircle(momentColor(moment), 3.dp.toPx(), Offset(size.width * mark, size.height / 2))
                }
            }
        }
        PopupPill("Skip all", onSkipAll, Modifier.padding(start = 8.dp, top = 6.dp))
    }
}

@Composable
private fun PopupPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        style = RtType.caption,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.7f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** The clip, with sound, started where the replay is and running with it; cropped to the square card. */
@Composable
private fun PopupClip(moment: Moment, startAtMs: Long, playing: Boolean) {
    val context = LocalContext.current
    val player = remember(moment.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(moment.file)))
            prepare()
            seekTo(startAtMs.coerceAtLeast(0L))
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, playing) { player.playWhenReady = playing }
    AndroidView(
        factory = { ctx -> LayoutInflater.from(ctx).inflate(R.layout.moment_popup_player, null) as PlayerView },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

