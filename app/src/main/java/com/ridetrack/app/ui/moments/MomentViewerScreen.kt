package com.ridetrack.app.ui.moments

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.common.sampleAt
import com.ridetrack.app.ui.components.Stat
import com.ridetrack.app.ui.components.StatRow
import com.ridetrack.app.ui.components.leanColor
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.telemetry.model.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ViewerState(val loading: Boolean = true, val moments: List<Moment> = emptyList(), val samples: List<TelemetrySample> = emptyList())

class MomentViewerViewModel(private val c: AppContainer, rideId: String) : ViewModel() {
    private val samples = MutableStateFlow<List<TelemetrySample>>(emptyList())

    val state: StateFlow<ViewerState> = combine(c.moments.observe(rideId), samples) { m, s -> ViewerState(false, m, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerState())

    init {
        viewModelScope.launch { samples.value = c.rides.track(rideId).samples }
    }

    fun toggleStar(m: Moment) {
        viewModelScope.launch { c.moments.setStarred(m.id, !m.starred) }
    }

    fun delete(m: Moment) {
        viewModelScope.launch { c.moments.delete(m) }
    }
}

/** Full-screen moments of one ride; swipe between them. Clips loop, photos sit still. */
@Composable
fun MomentViewerScreen(rideId: String, startId: String?, onBack: () -> Unit, onShareMoment: (String) -> Unit) {
    val vm = appViewModel(key = "moments-$rideId") { MomentViewerViewModel(it, rideId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf<Moment?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_000)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (s.loading) return@Box
        if (s.moments.isEmpty()) {
            LaunchedEffect(Unit) { onBack() }
            return@Box
        }
        val initial = s.moments.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = initial) { s.moments.size }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { s.moments[it].id }) { page ->
            val m = s.moments[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (m.kind == MomentKind.CLIP) Clip(m, playing = pager.currentPage == page)
                else Thumb(m.file, Modifier.fillMaxSize().padding(vertical = 80.dp), maxEdge = 1600)
            }
        }
        val current = s.moments.getOrNull(pager.currentPage) ?: return@Box

        // Top: back, position, star.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
            Spacer(Modifier.weight(1f))
            Text("${pager.currentPage + 1} / ${s.moments.size}", style = RtType.caption, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.weight(1f))
            RoundIcon(if (current.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (current.starred) "Unstar" else "Star", tint = if (current.starred) RtColors.Warning else Color.White) {
                vm.toggleStar(current)
            }
        }

        // Bottom: what happened, the numbers at that moment, actions.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(momentColor(current), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(momentTitle(current), style = RtType.bodyStrong, color = Color.White)
                    Text(Format.timeOfDay(current.timeMillis), style = RtType.caption, color = Color.White.copy(alpha = 0.7f))
                }
            }
            val sample = s.samples.sampleAt(current.timeMillis)
            StatRow(
                listOf(
                    Stat("Speed", Format.speedKmh(sample?.speedMps ?: current.speedMps), "km/h"),
                    Stat("Lean", Format.lean(sample?.leanDeg), color = leanColor(sample?.leanDeg)),
                    Stat("G", sample?.combinedG?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: Format.DASH, color = RtColors.GForce),
                ),
                style = RtType.metricM,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Share = the styled graphic with details; Original = the plain file.
                Action(Icons.Outlined.IosShare, "Share") { onShareMoment(current.id) }
                Action(Icons.Outlined.Share, "Original") { share(context, current) }
                Action(Icons.Outlined.Download, "Save") {
                    scope.launch {
                        val ok = saveToGallery(context, current)
                        toast = if (ok) "Saved to your gallery" else "Couldn't save"
                    }
                }
                Spacer(Modifier.weight(1f))
                Action(Icons.Outlined.Delete, "Delete") { confirmDelete = current }
            }
        }
        toast?.let {
            Text(
                it,
                style = RtType.caption,
                color = RtColors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(RtColors.SurfaceRaised, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (m.kind == MomentKind.CLIP) "Delete this clip?" else "Delete this photo?") },
            text = { Text("It will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(m)
                    confirmDelete = null
                }) { Text("Delete", color = RtColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            containerColor = RtColors.SurfaceRaised,
        )
    }
}

@Composable
private fun Clip(m: Moment, playing: Boolean) {
    val context = LocalContext.current
    val view = remember(m.id) {
        VideoView(context).apply {
            setVideoPath(m.file.path)
            setOnPreparedListener { mp: MediaPlayer -> mp.isLooping = true }
        }
    }
    LaunchedEffect(playing) { if (playing) view.start() else view.pause() }
    DisposableEffect(view) { onDispose { view.stopPlayback() } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = RtType.caption, color = Color.White)
    }
}

private fun mime(m: Moment) = if (m.kind == MomentKind.CLIP) "video/mp4" else "image/jpeg"

private fun share(context: Context, m: Moment) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", m.file)
        val send = Intent(Intent.ACTION_SEND).setType(mime(m)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share moment"))
    }
}

/** Copies into Movies/ or Pictures/Ride Track (no permission needed on Android 10+). */
private suspend fun saveToGallery(context: Context, m: Moment): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
    val video = m.kind == MomentKind.CLIP
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, m.file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime(m))
        put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Ride Track")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values) ?: return@withContext false
    runCatching {
        resolver.openOutputStream(uri)?.use { out -> m.file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }.onFailure { resolver.delete(uri, null, null) }.isSuccess
}
