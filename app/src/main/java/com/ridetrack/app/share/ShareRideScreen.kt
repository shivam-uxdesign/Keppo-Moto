package com.ridetrack.app.share

import android.graphics.Bitmap
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    /** Full-size renders by [OverlayChoice.key] (only the selected one is kept). */
    val images: Map<String, Bitmap> = emptyMap(),
    val fileName: String = "ride",
    val missing: Boolean = false,
    /** Small previews for the layout picker, by [OverlayChoice.key]. */
    val thumbs: Map<String, Bitmap> = emptyMap(),
)

class ShareRideViewModel(private val c: AppContainer, rideId: String) : ViewModel() {
    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()
    private var data: ShareCardData? = null
    /** The moment layouts on a ride show its top-speed instant. */
    private var instant: MomentOverlay? = null
    private val renderer by lazy { ShareCardRenderer(c.appContext) }
    private val momentRenderer by lazy { MomentShareRenderer(c.appContext) }

    private fun render(choice: OverlayChoice): Bitmap? = when (choice) {
        is OverlayChoice.Ride -> data?.let { renderer.render(it, choice.style) }
        is OverlayChoice.Moment -> instant?.let { momentRenderer.overlayOnly(1080, 1920, it, MomentField.DEFAULT, choice.layout) }
    }

    /** Full-size render of [choice] (only the selected one is kept at full size). */
    fun select(choice: OverlayChoice) {
        if (data == null || _state.value.images.containsKey(choice.key)) return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { render(choice) } ?: return@launch
            _state.update { it.copy(images = mapOf(choice.key to bmp)) }
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
            instant = topSpeedInstant(ride, track.samples)
            val slug = ride.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "ride" }
            val firstChoice = OverlayChoice.all.first()
            val first = withContext(Dispatchers.Default) { render(firstChoice) }
            _state.update { ShareUiState(loading = false, images = listOfNotNull(first?.let { firstChoice.key to it }).toMap(), fileName = "ridetrack-$slug") }
            // Picker thumbnails, rendered one by one so the screen is usable straight away.
            OverlayChoice.all.forEach { choice ->
                val thumb = withContext(Dispatchers.Default) {
                    render(choice)?.let { full -> Bitmap.createScaledBitmap(full, full.width / 6, full.height / 6, true).also { full.recycle() } }
                } ?: return@forEach
                _state.update { it.copy(thumbs = it.thumbs + (choice.key to thumb)) }
            }
        }
    }
}

/** The ride's top-speed instant, for the moment layouts. */
private fun topSpeedInstant(ride: com.ridetrack.telemetry.model.Ride, samples: List<com.ridetrack.telemetry.model.TelemetrySample>): MomentOverlay? {
    val top = samples.maxByOrNull { it.speedMps ?: -1.0 } ?: return null
    val route = samples.mapNotNull { s -> s.latitude?.let { la -> s.longitude?.let { lo -> la to lo } } }
        .let { pts -> if (pts.size <= 400) pts else pts.filterIndexed { i, _ -> i % (pts.size / 400 + 1) == 0 } }
    return MomentOverlay(
        eventTypes = emptySet(),
        eventValue = null,
        timeText = com.ridetrack.app.ui.format.Format.timeOfDay(top.timeMillis),
        dateText = com.ridetrack.app.ui.format.Format.rideDate(top.timeMillis).substringBefore(" ·"),
        rideName = ride.name,
        point = com.ridetrack.telemetry.moments.telemetryAt(samples, top.timeMillis),
        route = route,
        demo = ride.source == com.ridetrack.telemetry.model.DataSourceKind.DEMO,
    )
}

/** Strava-style share: a full story card, or a transparent overlay for your own photo. */
@Composable
fun ShareRideScreen(rideId: String, onBack: () -> Unit, startMode: String? = null) {
    val vm = appViewModel(key = "share-$rideId") { ShareRideViewModel(it, rideId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val choices = OverlayChoice.all
    val pager = rememberPagerState { choices.size }
    val style = choices[pager.currentPage]
    LaunchedEffect(pager.settledPage, s.loading) { if (!s.loading) vm.select(choices[pager.settledPage]) }
    var toast by remember { mutableStateOf<String?>(null) }
    // Story card, Reel (Keppo Studio) or 3D video.
    var mode by rememberSaveable { mutableStateOf(if (startMode == "reel") ShareMode.REEL else ShareMode.CARD) }
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
        ModeSwitch(mode) { mode = it }
        Spacer(Modifier.height(12.dp))
        if (mode == ShareMode.VIDEO) {
            RideVideoPanel(rideId, Modifier.weight(1f))
            return@Column
        }
        if (mode == ShareMode.REEL) {
            com.ridetrack.app.studio.StudioPanel(rideId, Modifier.weight(1f))
            return@Column
        }
        LayoutPicker(s.thumbs, style, onSelect = { c -> scope.launch { pager.animateScrollToPage(choices.indexOf(c)) } })
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                s.missing -> Text("Ride not found", style = RtType.body, color = RtColors.TextSecondary)
                s.loading -> CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp)
                // Swipe left or right to change the overlay.
                else -> HorizontalPager(pager, Modifier.fillMaxSize(), key = { choices[it].key }) { page ->
                    val st = choices[page]
                    val bmp = s.images[st.key] ?: s.thumbs[st.key]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, RtColors.Hairline, RoundedCornerShape(20.dp)),
                        ) {
                            // Overlays are previewed on a stand-in "photo" so the transparency is obvious.
                            if (st.transparent) SamplePhoto(Modifier.fillMaxSize())
                            if (bmp != null) {
                                Image(bmp.asImageBitmap(), "${st.label} share graphic preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            } else {
                                CircularProgressIndicator(color = RtColors.Primary, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center))
                            }
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
            "${style.label} · ${pager.currentPage + 1} of ${choices.size} · swipe for more\n" +
                if (!style.transparent) "A ready-made story card. Share straight to Instagram or anywhere else."
                else "${style.note}. Transparent PNG: add your photo in Instagram, then paste this on top as a sticker.",
            style = RtType.caption,
            color = RtColors.TextSecondary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val bmp = s.images[style.key]
            val name = "${s.fileName}-${style.key}"
            ActionButton("Share", Icons.Outlined.IosShare, primary = true, enabled = bmp != null, modifier = Modifier.weight(1f)) {
                val b = bmp ?: return@ActionButton
                scope.launch { ShareImages.share(context, ShareImages.toCacheUri(context, b, name)) }
            }
            ActionButton("Save", Icons.Outlined.Download, enabled = bmp != null) {
                val b = bmp ?: return@ActionButton
                scope.launch {
                    val ok = ShareImages.saveToPhotos(context, b, name)
                    if (ok) haptics.confirm()
                    toast = if (ok) "Saved to Pictures/Keppo Moto" else "Couldn't save here. Use Share instead."
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

/** Story card (an image), a Reel from the ride's clips, or the ride as a 3D video. */
private enum class ShareMode(val label: String) { CARD("Story card"), REEL("Reel"), VIDEO("3D video") }

@Composable
private fun ModeSwitch(mode: ShareMode, onChange: (ShareMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(RtColors.Surface)
            .border(1.dp, RtColors.Hairline, RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        ShareMode.entries.forEach { v ->
            val label = v.label
            val on = v == mode
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) RtColors.SurfaceRaised else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = RtType.button, color = if (on) RtColors.TextPrimary else RtColors.TextSecondary)
            }
        }
    }
}

/** Thumbnails of every layout with how much of the story each one covers. */
@Composable
internal fun LayoutPicker(thumbs: Map<String, Bitmap>, selected: OverlayChoice, onSelect: (OverlayChoice) -> Unit, sampleBehind: Boolean = true) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(selected) { listState.animateScrollToItem((OverlayChoice.all.indexOf(selected) - 1).coerceAtLeast(0)) }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(OverlayChoice.all) { st ->
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
                    if (st.transparent && sampleBehind) SamplePhoto(Modifier.fillMaxSize())
                    thumbs[st.key]?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                Text(st.label, style = RtType.caption, color = if (on) RtColors.TextPrimary else RtColors.TextSecondary, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                Text(
                    when (st) {
                        is OverlayChoice.Ride -> st.style.coverage
                        is OverlayChoice.Moment -> "Instant"
                    },
                    style = RtType.caption, color = if (on) RtColors.Primary else RtColors.TextTertiary, maxLines = 1,
                )
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
