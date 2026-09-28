package com.ridetrack.app.ui.moments

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.moments.ClipWriter
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** "Moments" row under a ride's map: thumbnails in ride order, a dot for what happened. */
@Composable
fun MomentStrip(moments: List<Moment>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (moments.isEmpty()) return
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Moments")
            Spacer(Modifier.width(8.dp))
            Text("${moments.size}", style = RtType.caption, color = RtColors.TextTertiary)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(moments, key = { it.id }) { m -> MomentTile(m) { onOpen(m.id) } }
        }
    }
}

@Composable
private fun MomentTile(m: Moment, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(84.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(RtColors.Surface)
            .border(1.dp, RtColors.Hairline, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${momentTitle(m)}, ${momentSubtitle(m)}" },
    ) {
        Thumb(m.thumb, Modifier.fillMaxSize())
        // What happened.
        Box(
            Modifier
                .padding(7.dp)
                .size(10.dp)
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .padding(1.5.dp)
                .background(momentColor(m), CircleShape),
        )
        if (m.starred) {
            Icon(
                Icons.Rounded.Star, contentDescription = null, tint = RtColors.Warning,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp),
            )
        }
        if (m.kind == MomentKind.CLIP) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                Text(Format.clock(m.durationMillis ?: 0), color = Color.White, fontSize = 10.sp, style = RtType.caption)
            }
        }
    }
}

@Composable
fun Thumb(file: File?, modifier: Modifier, maxEdge: Int = 320) {
    var bmp by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bmp = file?.let { f -> withContext(Dispatchers.IO) { runCatching { ClipWriter.load(f, maxEdge)?.asImageBitmap() }.getOrNull() } }
    }
    val b = bmp
    if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = modifier)
    else Box(modifier.background(RtColors.SurfaceRaised))
}
