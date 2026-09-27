package com.ridetrack.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ridetrack.app.data.BikePhotos
import com.ridetrack.app.ui.theme.RtColors

/** The bike's photo (cropped to fill), or a quiet motorcycle glyph when there is none. */
@Composable
fun BikeImage(photoFile: String?, modifier: Modifier = Modifier, maxEdge: Int = 800, iconSize: Dp = 28.dp) {
    val context = LocalContext.current
    var bitmap by remember(photoFile) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoFile) {
        bitmap = photoFile?.let { BikePhotos.load(context, it, maxEdge)?.asImageBitmap() }
    }
    Box(modifier.background(RtColors.SurfaceRaised), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.TwoWheeler, contentDescription = null, tint = RtColors.TextTertiary, modifier = Modifier.size(iconSize))
        }
    }
}
