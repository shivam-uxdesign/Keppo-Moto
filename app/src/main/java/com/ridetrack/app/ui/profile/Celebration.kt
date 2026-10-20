package com.ridetrack.app.ui.profile

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RideDark
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlin.random.Random

/**
 * Which badge levels the rider has already been shown, so each is celebrated once. The first time
 * it's used, everything already earned counts as shown (no flood of old badges).
 */
class BadgeSeen(context: Context) {
    private val prefs = context.getSharedPreferences("badges_seen", Context.MODE_PRIVATE)

    private fun keys(list: List<Achievement>) = list.filter { it.earned }.map { "${it.id}:${it.level}" }

    /** Earned levels not shown yet; empty (and all marked) on the very first look. */
    fun fresh(list: List<Achievement>): List<Achievement> {
        val seen = prefs.getStringSet(KEY, null)
        if (seen == null) {
            prefs.edit().putStringSet(KEY, keys(list).toSet()).apply()
            return emptyList()
        }
        return list.filter { it.earned && "${it.id}:${it.level}" !in seen }
    }

    fun markShown(list: List<Achievement>) {
        val seen = prefs.getStringSet(KEY, emptySet()).orEmpty()
        prefs.edit().putStringSet(KEY, seen + keys(list)).apply()
    }

    private companion object { const val KEY = "seen" }
}

/** After a ride: "New badge", the medallion with a shine, and confetti. Never shown while riding. */
@Composable
fun BadgeCelebration(badges: List<Achievement>, onSeeAll: () -> Unit, onDone: () -> Unit) {
    if (badges.isEmpty()) return
    var index by remember { mutableStateOf(0) }
    val a = badges[index.coerceIn(0, badges.size - 1)]
    var shareOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RideDark {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                Confetti(Modifier.fillMaxSize())
                val pop = remember(a.id) { Animatable(0.4f) }
                LaunchedEffect(a.id) { pop.animateTo(1f, tween(500)) }
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (badges.size > 1) "New badge · ${index + 1} of ${badges.size}" else "New badge", style = RtType.label, color = RtColors.Primary)
                    BadgeMedallion(a, 170.dp, Modifier.scale(pop.value), shine = rememberTilt())
                    Text(a.title, style = RtType.title, color = Color.White, textAlign = TextAlign.Center)
                    Text(listOfNotNull(a.tier?.label, a.detail).joinToString(" · "), style = RtType.body, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                    PrimaryButton(if (index < badges.size - 1) "Next" else "Share", {
                        if (index < badges.size - 1) index++ else shareOpen = true
                    }, Modifier.fillMaxWidth())
                    SecondaryButton("See all", { onSeeAll() }, Modifier.fillMaxWidth())
                    SecondaryButton("Done", onDone, Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (shareOpen) BadgeSheet(a, onClose = { shareOpen = false })
}

/** Confetti falling in the app's colour and the tier colours, for a few seconds. */
@Composable
private fun Confetti(modifier: Modifier) {
    val colors = listOf(RtColors.Primary) + Tier.entries.map { Color(it.argb) }
    val pieces = remember { List(70) { Triple(Random.nextFloat(), Random.nextFloat() * -0.6f, Random.nextFloat()) } }
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < 4_000L) withFrameNanos { t = (it - start) / 1_000_000 }
    }
    Canvas(modifier) {
        if (t >= 4_000L) return@Canvas
        val s = t / 1000f
        pieces.forEachIndexed { i, (x, y0, spin) ->
            val y = (y0 + s * (0.25f + spin * 0.25f)) * size.height
            val px = x * size.width + kotlin.math.sin(s * 3f + i) * 18f
            rotate(s * 360f * spin, Offset(px, y)) {
                drawRect(colors[i % colors.size], Offset(px, y), Size(10f, 16f))
            }
        }
    }
}
