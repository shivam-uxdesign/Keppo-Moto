package com.ridetrack.app.studio.storyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridetrack.app.studio.Script
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import java.util.Locale

/**
 * The board's own checks, worked out by the app (never Gemini): lines too long for their shot,
 * shots the gear can't film, riding shots planned handheld, over the format's length, talking
 * with captions off. Each with the fix it offers. Pure, unit-tested.
 */
object Health {
    /** One thing to check, the shot it's about, and its fixes (label → the fixed board). */
    data class Issue(val text: String, val shotId: String?, val fixes: List<Pair<String, (Script) -> Script>>)

    /** About how long [say] takes to say: 0.4 s a word plus a breath; 0 for nothing. */
    fun sayMs(say: String?): Long {
        val words = say?.trim()?.split(Regex("\\s+"))?.count { it.isNotEmpty() } ?: 0
        return if (words == 0) 0 else ((words * 0.4 + 0.3) * 1000).toLong()
    }

    /** A length rounded up to the next half second, within a shot's limits. */
    fun halfUp(ms: Long): Long = (((ms + 499) / 500) * 500).coerceIn(StoryboardJson.MIN_MS, StoryboardJson.MAX_MS)

    fun issues(s: Script, gear: Gear): List<Issue> {
        val out = ArrayList<Issue>()
        val owned = gear.items.joinToString(" ") { (it.name + " " + it.detail).lowercase(Locale.US) }
        Placeholders.shots(s).forEach { (_, sh) ->
            val b = sh.board ?: return@forEach
            val id = sh.id ?: return@forEach
            val n = Placeholders.number(s, id).trimStart('0')
            val need = sayMs(b.say)
            if (need > b.durationMs + 200) out += Issue(
                "The line in #$n takes about ${seconds(need)} s to say; the shot is ${seconds(b.durationMs)} s.", id,
                listOf(
                    "Stretch to ${seconds(halfUp(need))} s" to { x -> ShotEdits.edit(x, id) { it.copy(durationMs = halfUp(need)) } },
                ),
            )
            if (b.riding && b.mount.lowercase(Locale.US).let { "hand" in it }) out += Issue(
                "#$n is filmed while riding but planned handheld. Use a fixed mount.", id,
                listOf("Use the handlebar" to { x -> ShotEdits.edit(x, id) { it.copy(mount = "handlebar") } }),
            )
            val m = b.mount.lowercase(Locale.US)
            if (gear.items.isNotEmpty() && "tripod" in m && "tripod" !in owned) out += Issue(
                "#$n needs a tripod, which isn't in your gear.", id,
                listOf("Prop the phone instead" to { x -> ShotEdits.edit(x, id) { it.copy(mount = "phone propped on the bike") } }),
            )
            if (b.say != null && b.caption == Captions.OFF) out += Issue(
                "#$n has a voice-over line but its captions are off.", id,
                listOf("Turn captions on" to { x -> ShotEdits.edit(x, id) { it.copy(caption = Captions.SAY) } }),
            )
        }
        val max = s.format.maxSec * 1000L
        if (s.totalMs > max) out += Issue(
            "The Reel is ${seconds(s.totalMs)} s; a ${s.format.label} can be ${s.format.maxSec} s.", null,
            listOf("Fit it" to { x -> StoryboardEngine.fit(x) }),
        )
        return out
    }
}

/** The health strip on the board: the first thing to check, its fix, and how many more; or "ready". */
@Composable
fun HealthStrip(s: Script, gear: Gear, onEdit: (Script, String) -> Unit) {
    val issues = remember(s, gear) { Health.issues(s, gear) }
    var at by remember(s) { mutableIntStateOf(0) }
    if (issues.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            Icon(Icons.Rounded.CheckCircle, null, tint = RtColors.Ok, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Every line fits its shot. Ready to shoot.", style = RtType.caption, color = RtColors.TextSecondary)
        }
        return
    }
    val i = issues[at.coerceIn(0, issues.lastIndex)]
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RtColors.Surface).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, tint = RtColors.Warning, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Spacer(Modifier.width(8.dp))
            Text(i.text, style = RtType.caption, color = RtColors.TextPrimary, modifier = Modifier.weight(1f))
            if (issues.size > 1) Text(
                "${at + 1} of ${issues.size}", style = RtType.caption, color = RtColors.TextTertiary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = "Next thing to check") { at = (at + 1) % issues.size }.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.padding(start = 22.dp)) {
            i.fixes.forEach { (label, fix) -> TextButton(onClick = { onEdit(fix(s), "Fixed: ${label.lowercase(Locale.US)}") }) { Text(label, fontWeight = FontWeight.SemiBold) } }
        }
    }
}
