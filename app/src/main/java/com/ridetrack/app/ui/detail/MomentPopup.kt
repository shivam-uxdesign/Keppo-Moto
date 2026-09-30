package com.ridetrack.app.ui.detail

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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

/**
 * A moment reached during replay, shown over the map while the replay runs through it at 1×.
 * Tapping outside skips it; tapping the card opens it.
 */
@Composable
fun MomentPopup(moment: Moment, time: Double, playing: Boolean, onSkip: () -> Unit, onOpen: () -> Unit) {
    val window = remember(moment) { MomentWindow.of(moment) }
    val length = (window.endMillis - window.startMillis).coerceAtLeast(1L)
    val progress = ((time - window.startMillis) / length).toFloat().coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Skip this moment", onClick = onSkip),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
                .width(210.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                .clickable(role = Role.Button, onClickLabel = "Open moment", onClick = onOpen)
                .semantics { contentDescription = "${momentTitle(moment)}. Tap to open, tap outside to skip." },
        ) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (moment.kind == MomentKind.CLIP) {
                    PopupClip(moment, (time - moment.videoStartMillis).toLong(), playing)
                } else {
                    Thumb(moment.file, Modifier.fillMaxSize(), maxEdge = 1080)
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(90.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(momentColor(moment), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(momentTitle(moment), style = RtType.caption, color = Color.White, maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    val mark = ((moment.timeMillis - window.startMillis).toFloat() / length).coerceIn(0f, 1f)
                    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = r)
                        drawRoundRect(Color.White, size = Size(size.width * progress, size.height), cornerRadius = r)
                        drawCircle(momentColor(moment), 3.dp.toPx(), Offset(size.width * mark, size.height / 2))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to open", style = RtType.caption.copy(fontSize = 11.sp), color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** The clip, with sound, started where the replay is and running with it. */
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
        factory = { ctx -> PlayerView(ctx).apply { useController = false } },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
