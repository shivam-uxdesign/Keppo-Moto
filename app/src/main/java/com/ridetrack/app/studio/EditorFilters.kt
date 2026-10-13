package com.ridetrack.app.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlin.math.roundToInt

/** [src] with filter [f] at full strength, for its thumbnail (Red only keeps the reds, as the video does). */
internal fun filtered(src: Bitmap, f: RideFilter): Bitmap {
    if (RideFilters.keepsRed(f)) {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val k = RideFilters.grade(f).contrast
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val mx = maxOf(r, g, b)
            val sat = if (mx > 0f) (mx - minOf(r, g, b)) / mx else 0f
            val red = smooth(0.08f, 0.22f, r - maxOf(g, b)) * smooth(0.3f, 0.5f, sat)
            val l = ((0.2126f * r + 0.7152f * g + 0.0722f * b - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
            fun ch(v: Float) = ((l + (v - l) * red) * 255).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, filterPaint(f, 1f))
    return out
}

private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/** A paint that draws through filter [f] at [amount] (the simple preview's picture; Red only shows as black and white there). */
internal fun filterPaint(f: RideFilter, amount: Float): Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
    colorFilter = ColorMatrixColorFilter(RideFilters.matrix(f, amount))
}

/**
 * Filters: the rider's own setting, on the video under every graphic. Shown in the tools' place,
 * under the video, so each one shows on it as it's tapped. Each tile is the frame at the playhead
 * through that filter, with its name; the one on has a thick frame, a tick and its name lit.
 */
@Composable
internal fun FilterPanel(vm: StudioViewModel, options: StudioOptions, onPicked: (RideFilter) -> Unit, onDone: () -> Unit) {
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) { thumb = vm.filterFrame() ?: sampleFrame() }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filter", style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                if (options.filter == RideFilter.NONE) "None" else "${options.filter.label} · ${(options.filterAmount * 100).roundToInt()}%",
                style = RtType.caption,
                color = RtColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Done",
                style = RtType.button,
                color = RtColors.Primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onDone).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(RideFilter.entries) { f ->
                val on = f == options.filter
                val img = remember(thumb, f) { thumb?.let { filtered(it, f) } }
                Column(
                    Modifier.width(68.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = f.label) {
                            if (!on) { vm.setOptions(reload = false) { it.copy(filter = f) }; onPicked(f) }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(width = 68.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised)
                            .then(if (on) Modifier.border(3.dp, RtColors.Primary, RoundedCornerShape(10.dp)) else Modifier.border(1.dp, RtColors.Hairline, RoundedCornerShape(10.dp))),
                    ) {
                        img?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        if (on) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).clip(CircleShape).background(RtColors.Primary),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Check, contentDescription = "On", tint = Color.Black, modifier = Modifier.size(13.dp)) }
                        }
                    }
                    Text(
                        f.label,
                        style = if (on) RtType.caption.copy(fontWeight = FontWeight.SemiBold) else RtType.caption,
                        color = if (on) RtColors.Primary else RtColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (options.filter != RideFilter.NONE) {
            LabelledSlider(
                "Strength", "${(options.filterAmount * 100).roundToInt()}%", options.filterAmount, 0f..1f,
                { v -> vm.setOptions(reload = false) { it.copy(filterAmount = v) } },
            ) {}
        }
    }
}

/** A stand-in frame for the tiles when the playhead's can't be read: sky, trees, road and a red bike. */
private fun sampleFrame(): Bitmap {
    val w = 144
    val h = 256
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.5f, 0xFF6FA8DC.toInt(), 0xFFF3D9A4.toInt(), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h * 0.5f, p)
    p.shader = null
    p.color = 0xFF3F6B3A.toInt(); c.drawRect(0f, h * 0.42f, w.toFloat(), h * 0.52f, p)
    p.color = 0xFF55585C.toInt(); c.drawRect(0f, h * 0.52f, w.toFloat(), h.toFloat(), p)
    p.color = 0xFFE8E2D0.toInt(); c.drawRect(w * 0.47f, h * 0.6f, w * 0.53f, h * 0.7f, p)
    p.color = 0xFFC62828.toInt(); c.drawRoundRect(w * 0.3f, h * 0.72f, w * 0.7f, h * 0.84f, 8f, 8f, p)
    p.color = 0xFF111111.toInt(); c.drawCircle(w * 0.33f, h * 0.86f, 11f, p); c.drawCircle(w * 0.67f, h * 0.86f, 11f, p)
    return b
}

/** The preview's Red only filter on the picture: a GPU shader where the phone has them (Android 13+). */
internal object PreviewEffects {
    private const val SHADER = """
uniform shader content;
uniform float amount;
uniform float contrast;
half4 main(float2 xy) {
  half4 c = content.eval(xy);
  float l = dot(float3(c.rgb), float3(0.2126, 0.7152, 0.0722));
  float g = clamp((l - 0.5) * contrast + 0.5, 0.0, 1.0);
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  float sat = mx > 0.0 ? (mx - mn) / mx : 0.0;
  float red = smoothstep(0.08, 0.22, c.r - max(c.g, c.b)) * smoothstep(0.3, 0.5, sat);
  float3 f = mix(float3(g), float3(c.rgb), red);
  return half4(half3(mix(float3(c.rgb), f, amount)), c.a);
}
"""

    /** Turns Red only on [v] at [amount] (0 = off); older phones show the picture as it is. */
    fun redOnly(v: android.view.View, amount: Float) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (amount <= 0f) { v.setRenderEffect(null); return }
        val shader = android.graphics.RuntimeShader(SHADER).apply {
            setFloatUniform("amount", amount)
            setFloatUniform("contrast", RideFilters.grade(RideFilter.RED_ONLY).contrast)
        }
        v.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
    }
}
