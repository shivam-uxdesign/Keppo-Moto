package com.ridetrack.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ridetrack.app.share.KeppoWordmark as Mark
import com.ridetrack.app.ui.theme.RtColors

/** The drawn "keppo moto" wordmark; [height] is the k's height (the descenders hang below it). */
@Composable
fun KeppoWordmark(modifier: Modifier = Modifier, height: Dp = 16.dp) {
    val keppo = RtColors.TextPrimary.toArgb()
    val moto = RtColors.Primary.toArgb()
    Canvas(
        modifier
            .width(height * (Mark.width(1f)))
            .height(height * 1.5f)
            .semantics { contentDescription = "Keppo Moto" },
    ) {
        val h = height.toPx()
        drawIntoCanvas { Mark.draw(it.nativeCanvas, 0f, h, h, keppo, moto) }
    }
}
