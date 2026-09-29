package com.ridetrack.app.share

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ridetrack.app.AppContainer
import com.ridetrack.app.ui.appViewModel
import com.ridetrack.app.ui.components.ScreenHeader
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtDimens
import com.ridetrack.app.ui.theme.RtType
import com.ridetrack.app.ui.theme.pressScale
import com.ridetrack.app.ui.theme.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShareUiState(
    val loading: Boolean = true,
    val images: Map<ShareStyle, Bitmap> = emptyMap(),
    val fileName: String = "ride",
    val missing: Boolean = false,
    /** Small previews for the layout picker. */
    val thumbs: Map<ShareStyle, Bitmap> = emptyMap(),
)

class ShareRideViewModel(private val c: AppContainer, rideId: String) : ViewModel() {
    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()
    private var data: ShareCardData? = null
    private val renderer by lazy { ShareCardRenderer(c.appContext) }

    /** Full-size render of [style] (only the selected one is kept at full size). */
    fun select(style: ShareStyle) {
        val d = data ?: return
        if (_state.value.images.containsKey(style)) return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { renderer.render(d, style) }
            // Only the selected layout stays at full size; the previous one is left to GC
            // (it may still be on screen during the crossfade).
            _state.update { it.copy(images = mapOf(style to bmp)) }
        }
    }

    init {
        viewModelScope.launch {
            val ride = c.rides.get(rideId)
            if (ride == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            val bike = c.bikes.get(ride.bikeId)
            val track = c.rides.track(rideId)
            val d = ShareCardData.from(ride, bike?.displayName, track.samples)
            data = d
            val slug = ride.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "ride" }
            val first = withContext(Dispatchers.Default) { renderer.render(d, ShareStyle.SOLID) }
            _state.update { ShareUiState(loading = false, images = mapOf(ShareStyle.SOLID to first), fileName = "ridetrack-$slug") }
            // Picker thumbnails, rendered one by one so the screen is usable straight away.
            ShareStyle.entries.forEach { st ->
                val thumb = withContext(Dispatchers.Default) {
                    val full = renderer.render(d, st)
                    Bitmap.createScaledBitmap(full, full.width / 6, full.height / 6, true).also { full.recycle() }
                }
                _state.update { it.copy(thumbs = it.thumbs + (st to thumb)) }
            }
        }
    }
}

/** Strava-style share: a full story card, or a transparent overlay for your own photo. */
@Composable
fun ShareRideScreen(rideId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "share-$rideId") { ShareRideViewModel(it, rideId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var style by remember { mutableStateOf(ShareStyle.SOLID) }
    LaunchedEffect(style, s.loading) { if (!s.loading) vm.select(style) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_200)
            toast = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = RtDimens.screenPadding),
    ) {
        ScreenHeader("Share ride", onBack = onBack)
        LayoutPicker(s.thumbs, style, onSelect = { style = it })
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val image = s.images[style] ?: s.thumbs[style]
            when {
                s.missing -> Text("Ride not found", style = RtType.body, color = RtColors.TextSecondary)
                image == null -> CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp)
                else -> Crossfade(style, label = "share-style") { st ->
                    val bmp = s.images[st] ?: s.thumbs[st]
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, RtColors.Hairline, RoundedCornerShape(20.dp)),
                    ) {
                        // The overlay is previewed on a stand-in "photo" so the transparency is obvious.
                        if (st.transparent) SamplePhoto(Modifier.fillMaxSize())
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(),
                                contentDescription = "${st.label} share graphic preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            toast?.let {
                Text(
                    it,
                    style = RtType.caption,
                    color = RtColors.TextPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .background(RtColors.SurfaceRaised, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Text(
            if (!style.transparent) "A ready-made story card. Share straight to Instagram or anywhere else."
            else "${style.label} · covers ${style.coverage.lowercase()} of a story. Transparent PNG: add your photo in Instagram, then paste this on top as a sticker.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val bmp = s.images[style]
            val name = "${s.fileName}-${style.name.lowercase()}"
            ActionButton("Share", Icons.Outlined.IosShare, primary = true, enabled = bmp != null, modifier = Modifier.weight(1f)) {
                val b = bmp ?: return@ActionButton
                scope.launch { ShareImages.share(context, ShareImages.toCacheUri(context, b, name)) }
            }
            ActionButton("Save", Icons.Outlined.Download, enabled = bmp != null) {
                val b = bmp ?: return@ActionButton
                scope.launch {
                    val ok = ShareImages.saveToPhotos(context, b, name)
                    if (ok) haptics.confirm()
                    toast = if (ok) "Saved to Pictures/Ride Track" else "Couldn't save here. Use Share instead."
                }
            }
            if (style.transparent) {
                ActionButton("Copy", Icons.Outlined.ContentCopy, enabled = bmp != null) {
                    val b = bmp ?: return@ActionButton
                    scope.launch {
                        val ok = ShareImages.copy(context, ShareImages.toCacheUri(context, b, name))
                        if (ok) haptics.tick()
                        toast = if (ok) "Copied. Paste it onto your story" else "Couldn't copy"
                    }
                }
            }
        }
    }
}

/** Thumbnails of every layout with how much of the story each one covers. */
@Composable
private fun LayoutPicker(thumbs: Map<ShareStyle, Bitmap>, selected: ShareStyle, onSelect: (ShareStyle) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(ShareStyle.entries.toList()) { st ->
            val on = st == selected
            Column(
                Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Tab) { onSelect(st) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .width(72.dp)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(10.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) RtColors.Primary else RtColors.Hairline, RoundedCornerShape(10.dp)),
                ) {
                    if (st.transparent) SamplePhoto(Modifier.fillMaxSize())
                    thumbs[st]?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                Text(st.label, style = RtType.caption, color = if (on) RtColors.TextPrimary else RtColors.TextSecondary, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                Text(st.coverage, style = RtType.caption, color = if (on) RtColors.Primary else RtColors.TextTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(RtDimens.primaryButtonHeight)
            .pressScale(interaction)
            .clip(RoundedCornerShape(50))
            .background(if (primary) RtColors.Primary else RtColors.Surface)
            .border(1.dp, if (primary) Color.Transparent else RtColors.Hairline, RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (primary) RtColors.OnPrimary else RtColors.TextPrimary
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = RtType.button, color = fg)
    }
}

/** An abstract dusk-road "photo" behind the overlay preview. */
@Composable
internal fun SamplePhoto(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF3A4A6B), Color(0xFFC9826B), Color(0xFF2B2A30))))
        drawCircle(Color(0x55FFD7A8), radius = size.width * 0.22f, center = Offset(size.width * 0.7f, size.height * 0.36f))
        // Hills and road.
        drawRect(Color(0xFF1E1F26), topLeft = Offset(0f, size.height * 0.58f), size = Size(size.width, size.height * 0.42f))
        val road = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.46f, size.height * 0.58f)
            lineTo(size.width * 0.54f, size.height * 0.58f)
            lineTo(size.width * 0.95f, size.height)
            lineTo(size.width * 0.05f, size.height)
            close()
        }
        drawPath(road, Color(0xFF34343C))
    }
}
