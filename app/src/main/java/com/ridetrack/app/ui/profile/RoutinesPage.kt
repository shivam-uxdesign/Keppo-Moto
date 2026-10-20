package com.ridetrack.app.ui.profile

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.hud.OverlayPermission
import com.ridetrack.app.routines.Routine
import com.ridetrack.app.routines.RoutineAction
import com.ridetrack.app.routines.RoutinePlace
import com.ridetrack.app.routines.RoutineTrigger
import com.ridetrack.app.routines.Routines
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.Label
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.rides.MyLocation
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch

internal fun routinesSummary(encoded: String?): String {
    val list = Routines.decode(encoded)
    val on = list.count { it.enabled }
    return when {
        list.isEmpty() -> "Music and Maps when a ride starts, and more"
        on == 0 -> "${list.size} set up · all off"
        else -> "$on on" + if (on < list.size) " · ${list.size - on} off" else ""
    }
}

/**
 * Profile › Routines: the list, or one routine being edited ([editing], its id). Every change
 * saves as it's made.
 */
@Composable
internal fun RoutinesPage(editing: String?, onEdit: (String?) -> Unit) {
    val c = appContainer()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val list = Routines.decode(settings?.routines)
    val save: (List<Routine>) -> Unit = { l -> scope.launch { c.settings.setRoutines(Routines.encode(l)) } }
    val r = list.firstOrNull { it.id == editing }
    if (editing != null && r != null) {
        RoutineEditor(r, onChange = { n -> save(list.map { if (it.id == n.id) n else it }) }, onDelete = {
            onEdit(null)
            save(list.filterNot { it.id == r.id })
        })
    } else {
        RoutineList(list, settings != null, save, onEdit)
    }
}

@Composable
private fun RoutineList(list: List<Routine>, loaded: Boolean, save: (List<Routine>) -> Unit, onEdit: (String?) -> Unit) {
    val c = appContainer()
    Text(
        "Things the phone does by itself when a ride starts, ends, or you arrive somewhere. Nothing needs a tap while you ride.",
        style = RtType.caption,
        color = RtColors.TextSecondary,
    )
    Spacer(Modifier.height(RtDimens.md))
    Permissions(list)
    list.forEach { r ->
        RtCard(Modifier.padding(bottom = RtDimens.sm)) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onEdit(r.id) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name.ifBlank { "Routine" }, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    Text(r.summary, style = RtType.caption, color = RtColors.TextSecondary)
                    c.routines.lastRun(r.id)?.let { Text(it, style = RtType.caption, color = if (it.contains("didn't")) RtColors.Warning else RtColors.TextTertiary) }
                }
                Spacer(Modifier.padding(start = RtDimens.sm))
                Switch(
                    checked = r.enabled,
                    onCheckedChange = { on -> save(list.map { if (it.id == r.id) it.copy(enabled = on) else it }) },
                    colors = SwitchDefaults.colors(checkedTrackColor = RtColors.Primary, checkedThumbColor = RtColors.OnPrimary),
                )
            }
        }
    }
    if (loaded && list.isEmpty()) {
        Label("Start with")
        Spacer(Modifier.height(RtDimens.xs))
        Routines.starters().forEach { s ->
            RtCard(Modifier.padding(bottom = RtDimens.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                        Text(s.summary, style = RtType.caption, color = RtColors.TextSecondary)
                    }
                    SecondaryButton("Add", onClick = { save(list + s) })
                }
            }
        }
    }
    Spacer(Modifier.height(RtDimens.xs))
    SecondaryButton("New routine", icon = Icons.Rounded.Add, onClick = {
        val n = Routine(name = "Ride start", trigger = RoutineTrigger.RIDE_START, actions = listOf(RoutineAction.ResumeMusic))
        save(list + n)
        onEdit(n.id)
    })
}

/** What a routine needs from Android, asked for only when one of them is used. */
@Composable
private fun Permissions(list: List<Routine>) {
    val context = LocalContext.current
    val on = list.filter { it.enabled }
    val opens = on.any { r -> r.actions.any { it is RoutineAction.OpenApp || it == RoutineAction.OpenMaps } }
    val dnd = on.any { r -> RoutineAction.DoNotDisturb in r.actions }
    val needOverlay = opens && !OverlayPermission.isGranted(context)
    val needDnd = dnd && context.getSystemService<NotificationManager>()?.isNotificationPolicyAccessGranted != true
    if (!needOverlay && !needDnd) return
    RtCard(Modifier.padding(bottom = RtDimens.sm)) {
        if (needOverlay) {
            Text("Let routines open apps", style = RtType.bodyStrong, color = RtColors.Warning)
            Text("When a ride starts from the widget or the quick tile, Android only lets Keppo Moto open Maps if its pop-up is allowed. Otherwise you'll get a notification to tap.", style = RtType.caption, color = RtColors.TextSecondary)
            Spacer(Modifier.height(RtDimens.xs))
            SecondaryButton("Allow pop-up", onClick = { OverlayPermission.request(context) })
        }
        if (needOverlay && needDnd) Spacer(Modifier.height(RtDimens.md))
        if (needDnd) {
            Text("Allow Do Not Disturb", style = RtType.bodyStrong, color = RtColors.Warning)
            Text("Android asks once before an app may turn Do Not Disturb on and off.", style = RtType.caption, color = RtColors.TextSecondary)
            Spacer(Modifier.height(RtDimens.xs))
            SecondaryButton("Allow", onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(r: Routine, onChange: (Routine) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var name by remember(r.id) { mutableStateOf(r.name) }
    var adding by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    RtCard {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; onChange(r.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(RtDimens.md))
        Label("When")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
            RoutineTrigger.entries.forEach { t ->
                Pick(t.short, r.trigger == t) {
                    onChange(r.copy(trigger = t, actions = if (t == RoutineTrigger.ARRIVE) r.actions else r.actions - RoutineAction.EndRide))
                }
            }
        }
        if (r.trigger == RoutineTrigger.ARRIVE) PlaceEditor(r, onChange)
    }

    Spacer(Modifier.height(RtDimens.sm))
    RtCard {
        Label("Do, in this order")
        if (r.actions.isEmpty()) Text("No steps yet", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(vertical = 8.dp))
        r.actions.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = RtColors.Outline.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(end = RtDimens.sm))
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = RtType.body, color = RtColors.TextPrimary)
                    hint(a)?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
                    if (a is RoutineAction.MediaVolume) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
                            listOf(30, 50, 70, 100).forEach { p ->
                                Pick("$p%", a.percent == p) { onChange(r.copy(actions = r.actions.mapIndexed { k, x -> if (k == i) RoutineAction.MediaVolume(p) else x })) }
                            }
                        }
                    }
                }
                if (i > 0) {
                    IconButton(onClick = { onChange(r.copy(actions = r.actions.toMutableList().apply { add(i - 1, removeAt(i)) })) }) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move ${a.label} up", tint = RtColors.TextSecondary)
                    }
                }
                IconButton(onClick = { onChange(r.copy(actions = r.actions.filterIndexed { k, _ -> k != i })) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove ${a.label}", tint = RtColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(RtDimens.xs))
        SecondaryButton("Add a step", icon = Icons.Rounded.Add, onClick = { adding = true })
    }

    Spacer(Modifier.height(RtDimens.md))
    Row(horizontalArrangement = Arrangement.spacedBy(RtDimens.sm)) {
        PrimaryButton("Test now", icon = Icons.Rounded.PlayArrow, onClick = { scope.launch { c.routines.test(r) } }, enabled = r.actions.isNotEmpty())
        TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = RtColors.Error) }
    }
    if (r.actions.any { it is RoutineAction.OpenApp || it == RoutineAction.OpenMaps }) {
        Spacer(Modifier.height(RtDimens.xs))
        Text("Test now opens apps over this screen; come back with Back.", style = RtType.caption, color = RtColors.TextTertiary)
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a step") },
            text = {
                Column {
                    (Routines.choices(r.trigger) - r.actions.filter { it !is RoutineAction.MediaVolume }.toSet()).forEach { a ->
                        Choice(a.label, hint(a)) { adding = false; onChange(r.copy(actions = r.actions + a)) }
                    }
                    Choice("Open an app…", "Any app on this phone") { adding = false; pickingApp = true }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    if (pickingApp) {
        val apps = remember {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != context.packageName }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }
        AlertDialog(
            onDismissRequest = { pickingApp = false },
            title = { Text("Open an app") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.first }) { (pkg, label) ->
                        Choice(label, null) { pickingApp = false; onChange(r.copy(actions = r.actions + RoutineAction.OpenApp(pkg, label))) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingApp = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${r.name}\"?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = RtColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditor(r: Routine, onChange: (Routine) -> Unit) {
    val context = LocalContext.current
    var placeName by remember(r.id) { mutableStateOf(r.place?.name ?: "Home") }
    var locating by remember { mutableStateOf(false) }
    Spacer(Modifier.height(RtDimens.md))
    Label("Where")
    val p = r.place
    if (p != null) {
        Text(
            "${p.name} · within ${p.radiusM} m",
            style = RtType.body,
            color = RtColors.TextPrimary,
        )
        Text("It runs once you've been away and then stop there for 20 s.", style = RtType.caption, color = RtColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(RtDimens.xs)) {
            listOf(100, 150, 300).forEach { m -> Pick("$m m", p.radiusM == m) { onChange(r.copy(place = p.copy(radiusM = m))) } }
        }
    } else {
        Text("Stand at the place (home, office) and save it.", style = RtType.caption, color = RtColors.TextSecondary)
    }
    Spacer(Modifier.height(RtDimens.xs))
    OutlinedTextField(
        value = placeName,
        onValueChange = { placeName = it; p?.let { onChange(r.copy(place = it.copy(name = placeName))) } },
        label = { Text("Place name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(RtDimens.xs))
    if (!MyLocation.allowed(context)) {
        Text("Allow location for Keppo Moto to save a place.", style = RtType.caption, color = RtColors.Warning)
    } else {
        SecondaryButton(if (locating) "Finding you…" else if (p == null) "Save where I am now" else "Move it to where I am now", onClick = {
            locating = true
            MyLocation.get(context) { ll ->
                locating = false
                onChange(r.copy(place = RoutinePlace(placeName.ifBlank { "Here" }, ll.latitude, ll.longitude, p?.radiusM ?: 150)))
            }
        })
    }
}

@Composable
private fun Pick(text: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text) },
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RtColors.PrimaryContainer),
)

@Composable
private fun Choice(title: String, sub: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = RtType.body, color = RtColors.TextPrimary)
        sub?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary) }
    }
}

private fun hint(a: RoutineAction): String? = when (a) {
    RoutineAction.ResumeMusic -> "Whatever you played last: YouTube Music, Spotify…"
    RoutineAction.PauseMusic -> "Pauses what's playing"
    RoutineAction.OpenMaps -> "Google Maps"
    RoutineAction.DoNotDisturb -> "Turned back off when the ride ends"
    is RoutineAction.MediaVolume -> "Put back when the ride ends"
    RoutineAction.EndRide -> "Stops recording and saves the ride"
    is RoutineAction.OpenApp -> null
}
