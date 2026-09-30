package com.ridetrack.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ridetrack.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Rear view of bike and rider, pre-rendered every 3° from 45° left to 45° right. */
private val LEAN_FRAMES = intArrayOf(
    R.drawable.bike_lean_00,
    R.drawable.bike_lean_01,
    R.drawable.bike_lean_02,
    R.drawable.bike_lean_03,
    R.drawable.bike_lean_04,
    R.drawable.bike_lean_05,
    R.drawable.bike_lean_06,
    R.drawable.bike_lean_07,
    R.drawable.bike_lean_08,
    R.drawable.bike_lean_09,
    R.drawable.bike_lean_10,
    R.drawable.bike_lean_11,
    R.drawable.bike_lean_12,
    R.drawable.bike_lean_13,
    R.drawable.bike_lean_14,
    R.drawable.bike_lean_15,
    R.drawable.bike_lean_16,
    R.drawable.bike_lean_17,
    R.drawable.bike_lean_18,
    R.drawable.bike_lean_19,
    R.drawable.bike_lean_20,
    R.drawable.bike_lean_21,
    R.drawable.bike_lean_22,
    R.drawable.bike_lean_23,
    R.drawable.bike_lean_24,
    R.drawable.bike_lean_25,
    R.drawable.bike_lean_26,
    R.drawable.bike_lean_27,
    R.drawable.bike_lean_28,
    R.drawable.bike_lean_29,
    R.drawable.bike_lean_30,
)
private const val FRAME_STEP_DEG = 3f
private const val MAX_LEAN_DEG = 45f

/** Where the tyres touch the ground in each frame, as a fraction of its height. */
const val BIKE_CONTACT_Y = 0.86f

/**
 * The bike in the 3D follow view, leaning with the ride: the nearest pre-rendered frame, turned
 * by the last degree or two so the lean changes smoothly rather than in 3° steps.
 */
@Composable
fun LeanBike(leanDeg: Float, modifier: Modifier = Modifier, size: Dp = 132.dp) {
    val res = LocalContext.current.resources
    val frames by produceState<List<ImageBitmap>?>(null) {
        value = withContext(Dispatchers.IO) { LEAN_FRAMES.map { ImageBitmap.imageResource(res, it) } }
    }
    Canvas(modifier.size(size)) {
        val f = frames ?: return@Canvas
        val lean = leanDeg.coerceIn(-MAX_LEAN_DEG, MAX_LEAN_DEG)
        val k = ((lean + MAX_LEAN_DEG) / FRAME_STEP_DEG).roundToInt().coerceIn(0, f.lastIndex)
        val rest = lean - (k * FRAME_STEP_DEG - MAX_LEAN_DEG)
        val img = f[k]
        val px = this.size.width.roundToInt()
        rotate(rest, pivot = Offset(this.size.width / 2f, this.size.height * BIKE_CONTACT_Y)) {
            drawImage(img, dstSize = IntSize(px, px), filterQuality = FilterQuality.Medium)
        }
    }
}
