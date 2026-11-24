package com.ridetrack.app.ui.keepmoving

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.keepmoving.Destination
import com.ridetrack.app.keepmoving.Destinations
import com.ridetrack.app.keepmoving.LatLon
import com.ridetrack.app.keepmoving.PlaceSearch
import com.ridetrack.app.keepmoving.SavedPlaces
import com.ridetrack.app.keepmoving.Suggestion
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keep moving's first step when starting a ride: your saved places and where your rides ended
 * (one tap), Google's search below, and Just ride for a ride without directions this once.
 * [autoStartSeconds]: a hands-free start (widget, tile, "Hey Google", voice) rides plain if
 * nothing is tapped in time.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhereTo(here: LatLon?, autoStartSeconds: Int?, onPick: (Destination) -> Unit, onJustRide: () -> Unit, onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by c.savedPlaces.places.collectAsStateWithLifecycle()
    var recent by remember { mutableStateOf<List<Destination>?>(null) }
    var query by remember { mutableStateOf("") }
    var google by remember { mutableStateOf<List<Suggestion>?>(null) }
    var found by remember { mutableStateOf<List<Destination>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(PlaceSearch.newSession()) }
    var editing by remember { mutableStateOf<SavedPlaces.Place?>(null) }
    // Hands-free start: count down unless the rider touches anything.
    var left by remember { mutableIntStateOf(autoStartSeconds ?: 0) }
    var touched by remember { mutableStateOf(autoStartSeconds == null) }
    LaunchedEffect(touched) {
        if (touched) return@LaunchedEffect
        while (left > 0) {
            delay(1_000)
            left--
        }
        if (!touched) onJustRide()
    }
    LaunchedEffect(Unit) {
        val ends = runCatching { Destinations.recentEnds(c.rides, c.places) }.getOrDefault(emptyList())
        // Ride ends that are already saved places show once (as the saved place).
        recent = ends.filter { e -> saved.none { s -> s.at != null && com.ridetrack.app.keepmoving.TrafficRoutes.distanceM(s.at, e.at) < SavedPlaces.SAME_M } }
    }
    LaunchedEffect(query) {
        failed = false
        if (query.trim().length < 3) { google = null; found = emptyList(); return@LaunchedEffect }
        delay(400)
        searching = true
        google = PlaceSearch.suggest(context, query, session, here)
        found = if (google == null) Destinations.search(query, here) else emptyList()
        searching = false
    }
    fun pickSaved(p: SavedPlaces.Place) {
        touched = true
        p.destination()?.let { onPick(it); return }
        // A Google place whose kept location is over 30 days old: fetch it again by its ID.
        val id = p.placeId ?: return
        locating = p.id
        scope.launch {
            val at = PlaceSearch.locateId(context, id)
            locating = null
            if (at != null) {
                c.savedPlaces.located(p.id, at)
                onPick(Destination(p.label, at, placeId = id))
            } else {
                failed = true
            }
        }
    }
    fun pickGoogle(s: Suggestion) {
        touched = true
        if (locating != null) return
        locating = s.placeId
        scope.launch {
            val at = PlaceSearch.locate(context, s, session)
            locating = null
            session = PlaceSearch.newSession()
            if (at != null) onPick(Destination(s.name, at, fromGoogle = true, placeId = s.placeId)) else failed = true
        }
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()) {
        ScreenHeader("Where to?", subtitle = "Keep moving finds the clearest road", onBack = onBack, modifier = Modifier.padding(horizontal = RtDimens.xs))
        OutlinedTextField(
            value = query,
            onValueChange = { touched = true; query = it.take(80) },
            placeholder = { Text("Search a place", style = RtType.body, color = RtColors.TextTertiary) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = RtColors.TextSecondary) },
            singleLine = true,
            textStyle = RtType.body.copy(color = RtColors.TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
            modifier = Modifier.fillMaxWidth().padding(horizontal = RtDimens.screenPadding),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = RtDimens.screenPadding, vertical = 8.dp)) {
            if (failed) Text("Couldn't get that place from Google: try again.", style = RtType.caption, color = RtColors.Warning, modifier = Modifier.padding(vertical = 6.dp))
            val typing = query.trim().length >= 3
            if (typing) {
                if (searching) CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).size(20.dp))
                google?.forEach { s -> Row(s.name, s.area, Icons.Outlined.Place, busy = locating == s.placeId, onClick = { pickGoogle(s) }) }
                found.forEach { d -> Row(d.name, null, Icons.Outlined.Place, onClick = { touched = true; onPick(d) }) }
                if (!searching && google.orEmpty().isEmpty() && found.isEmpty()) Text("Nothing found", style = RtType.caption, color = RtColors.TextSecondary)
                // Google's terms: its results carry its name when shown without its map.
                if (!google.isNullOrEmpty()) Text("Results from Google Maps", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
            } else {
                if (saved.isNotEmpty()) {
                    Label("SAVED")
                    saved.forEach { p ->
                        Row(
                            p.label,
                            if (p.uses > 1) "${p.uses} rides" else null,
                            Icons.Outlined.Place,
                            busy = locating == p.id,
                            onClick = { pickSaved(p) },
                            onLongClick = { touched = true; editing = p },
                        )
                    }
                    Text("Long-press a saved place to rename or remove it.", style = RtType.caption, color = RtColors.TextTertiary, modifier = Modifier.padding(bottom = 8.dp))
                }
                Label("WHERE YOUR RIDES ENDED")
                when {
                    recent == null -> CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).size(20.dp))
                    recent!!.isEmpty() -> Text("Nothing yet: search a place above.", style = RtType.caption, color = RtColors.TextSecondary)
                    else -> recent!!.forEach { d -> Row(d.name, null, Icons.Outlined.History, onClick = { touched = true; onPick(d) }) }
                }
            }
        }
        Column(Modifier.padding(horizontal = RtDimens.screenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!touched && left > 0) {
                Text("Starting without directions in $left s", style = RtType.caption, color = RtColors.TextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            SecondaryButton("Just ride", onClick = { touched = true; onJustRide() }, modifier = Modifier.fillMaxWidth())
        }
    }

    editing?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.label) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Saved place") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text("Name") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RtColors.Primary, unfocusedBorderColor = RtColors.Hairline, cursorColor = RtColors.Primary),
                )
            },
            confirmButton = { TextButton(onClick = { c.savedPlaces.rename(p.id, name); editing = null }) { Text("Save", color = RtColors.Primary) } },
            dismissButton = { TextButton(onClick = { c.savedPlaces.remove(p.id); editing = null }) { Text("Remove", color = RtColors.Error) } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = RtType.label, color = RtColors.TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Row(name: String, detail: String?, icon: ImageVector, busy: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = RtColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = RtType.body, color = RtColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (busy) CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.height(2.dp))
}
