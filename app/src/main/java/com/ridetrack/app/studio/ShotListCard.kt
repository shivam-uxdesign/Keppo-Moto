package com.ridetrack.app.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType

/** Home, before a ride: what the Studio coach asked you to film for the next Reel. Tick them off yourself. */
@Composable
fun ShotListCard(prefs: StudioPrefs, modifier: Modifier = Modifier) {
    val shots by prefs.shots.collectAsStateWithLifecycle()
    if (shots.isEmpty()) return
    RtCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FOR YOUR NEXT REEL", style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.weight(1f))
                Text(
                    if (shots.all { it.done }) "Done" else "Clear",
                    style = RtType.button,
                    color = RtColors.Primary,
                    modifier = Modifier.clickable(role = Role.Button) { prefs.clear() }.padding(4.dp),
                )
            }
            shots.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { prefs.toggle(s.text) },
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        if (s.done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        contentDescription = if (s.done) "Filmed" else "Not filmed yet",
                        tint = if (s.done) RtColors.Primary else RtColors.TextTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        s.text,
                        style = RtType.body.copy(textDecoration = if (s.done) TextDecoration.LineThrough else null),
                        color = if (s.done) RtColors.TextTertiary else RtColors.TextPrimary,
                    )
                }
            }
        }
    }
}
