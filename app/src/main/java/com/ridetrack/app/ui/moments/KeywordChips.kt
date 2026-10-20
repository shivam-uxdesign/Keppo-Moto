package com.ridetrack.app.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ridetrack.app.moments.MomentKeyword
import com.ridetrack.app.moments.MomentKeywords
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType

/**
 * A moment's keywords as chips, coloured by where they came from. ✕ takes one off (it won't come
 * back); "+ Keyword" adds the rider's own, typed or picked from ones used before ([recent]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordChips(list: List<MomentKeyword>?, recent: List<String>, onRemove: (String) -> Unit, onAdd: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    val shown = MomentKeywords.shown(list.orEmpty())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { k ->
            val color = keywordColor(k.kind)
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k.text, style = RtType.caption, color = Color.White)
                k.detail?.let { Text(" · $it", style = RtType.caption, color = Color.White.copy(alpha = 0.6f)) }
                Icon(
                    Icons.Rounded.Close, "Remove ${k.text}", tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 2.dp).size(20.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onRemove(k.text) }.padding(3.dp),
                )
            }
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).clickable(role = Role.Button) { adding = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Text(if (list == null) " Keyword (working them out…)" else " Keyword", style = RtType.caption, color = Color.White)
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a keyword") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(text, { text = it.take(40) }, placeholder = { Text("e.g. Tunnel, Night ride, Bloopers") }, singleLine = true)
                    val picks = recent.filter { r -> shown.none { it.key == r.lowercase() } }.take(8)
                    if (picks.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        picks.forEach { p ->
                            Text(
                                p, style = RtType.caption, color = RtColors.TextPrimary,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(RtColors.SurfaceRaised).clickable(role = Role.Button) { onAdd(p); adding = false }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onAdd(text); adding = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
