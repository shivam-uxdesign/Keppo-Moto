package com.ridetrack.app.studio

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ridetrack.app.AppContainer
import com.ridetrack.app.share.ShareImages
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ready videos full screen, one after another, playing by themselves: swipe up for the next, Share
 * (the caption is copied), Edit, or swipe left to skip it today. All that's waiting to be posted,
 * from [start] on; or only [rideId]'s.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(start: String?, rideId: String?, onBack: () -> Unit, onEdit: (ReelProject) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by c.reels.reels.collectAsStateWithLifecycle()
    // The line as it was when opened, so pages don't jump while sharing.
    val list = remember {
        val reels = c.reels.reels.value
        val line = if (rideId != null) reels.filter { it.rideId == rideId && it.deletedAt == null && !it.posted }.sortedBy { it.createdAt } else c.postPlan.queue(reels)
        val first = line.firstOrNull { it.id == start } ?: start?.let { id -> reels.firstOrNull { it.id == id && it.deletedAt == null } }
        (listOfNotNull(first) + line.filter { it.id != first?.id }).filter { c.reels.video(it.id).isFile }
    }
    var done by remember { mutableStateOf(mapOf<String, String>()) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing waiting to be posted", style = RtType.title, color = Color.White)
                Text("Videos Studio makes after your rides show up here.", style = RtType.body, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                Text("‹ Back", style = RtType.button, color = RtColors.Primary, modifier = Modifier.padding(top = 24.dp).clickable(role = Role.Button, onClick = onBack).padding(8.dp))
            }
            return@Box
        }
        val pager = rememberPagerState { list.size }
        VerticalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }) { page ->
            val p = all.firstOrNull { it.id == list[page].id } ?: list[page]
            val skipPx = with(LocalDensity.current) { 110.dp.toPx() }
            var drag by remember { mutableStateOf(0f) }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { translationX = drag; alpha = 1f - (-drag / (skipPx * 3)).coerceIn(0f, 0.6f) }
                    .pointerInput(p.id) {
                        // Swipe left: not today (it goes to the back of the line), on to the next.
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (drag < -skipPx) {
                                    c.postPlan.skip(p.id)
                                    done = done + (p.id to "Skipped for today")
                                    scope.launch { pager.animateScrollToPage((page + 1).coerceAtMost(list.lastIndex)) }
                                }
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        ) { ch, d -> ch.consume(); drag = (drag + d).coerceAtMost(0f) }
                    },
            ) {
                val file = c.reels.video(p.id)
                if (page == pager.settledPage && file.isFile) FullPlayer(file) else Thumb(c.reels.cover(p.id).takeIf { it.isFile }, Modifier.fillMaxSize().aspectRatio(9f / 16f).align(Alignment.Center), maxEdge = 1080)
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (p.seriesId != null) Text("${p.series} · Ep ${p.episode}", style = RtType.caption, color = RtColors.Primary)
                    Text(p.title, style = RtType.headline, color = Color.White, maxLines = 2)
                    if (p.postCaption.isNotBlank()) Text(p.postCaption, style = RtType.caption, color = Color.White.copy(alpha = 0.75f), maxLines = 2)
                    done[p.id]?.let { Text(it, style = RtType.caption, color = RtColors.Primary) }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button("Share", primary = true, modifier = Modifier.weight(1f)) {
                            scope.launch { if (sharePost(context, c, p)) done = done + (p.id to "Shared · caption copied") }
                        }
                        Button("Edit", primary = false, modifier = Modifier.width(96.dp)) { onEdit(p) }
                    }
                    Text(
                        if (page < list.lastIndex) "Swipe up for the next · swipe left to skip it today" else "That's all that's waiting",
                        style = RtType.caption,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
        }
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = RtType.title, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack).padding(horizontal = 12.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            Text("${pager.currentPage + 1} of ${list.size}", style = RtType.caption, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/** The video filling the screen, looping, muted only by the phone. */
@Composable
private fun FullPlayer(file: File) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.playWhenReady = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { player.playWhenReady = true }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = false } },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = "Play or pause") { player.playWhenReady = !player.playWhenReady },
    )
}

/**
 * Shares [p]'s video with its caption copied (Instagram asks for the caption after the video): no
 * longer a draft, "How did it do?" tomorrow, and a series episode counts as used. False if its video is gone.
 */
internal suspend fun sharePost(context: Context, c: AppContainer, p: ReelProject): Boolean {
    val file = c.reels.video(p.id).takeIf { it.isFile } ?: return false
    if (p.postCaption.isNotBlank()) {
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Caption", p.postCaption))
    }
    ShareImages.share(context, ShareImages.uriFor(context, file), "video/mp4")
    if (!p.posted) PostedReminderWorker.schedule(context, p.id)
    c.reels.markPosted(p.id)
    p.seriesId?.let { c.series.used(it, p.episode) }
    return true
}

/** Home, below the bike card: today's post, to share or swap for the next one. */
@Composable
fun TodayPostCard(modifier: Modifier = Modifier) {
    val c = appContainer()
    if (!c.postPlan.on) return
    val nav = com.ridetrack.app.ui.nav.LocalNavigate.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by c.reels.reels.collectAsStateWithLifecycle()
    var swaps by remember { mutableStateOf(0) }
    val p = remember(all, swaps) { c.postPlan.today(all) } ?: return
    val waiting = remember(all, swaps) { c.postPlan.queue(all).size }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(RtColors.Surface)
            .clickable(role = Role.Button, onClickLabel = "Watch") { nav(com.ridetrack.app.ui.nav.Routes.review(p.id)) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(RtColors.SurfaceRaised)) {
            Thumb(c.reels.cover(p.id).takeIf { it.isFile }, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("TODAY'S POST", style = RtType.label, color = RtColors.Primary)
            if (p.seriesId != null) Text("${p.series} · Ep ${p.episode}", style = RtType.caption, color = RtColors.TextSecondary, maxLines = 1)
            Text(p.title, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 2)
            Text(if (waiting > 1) "${waiting - 1} more waiting" else "The last one waiting", style = RtType.caption, color = RtColors.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Share", style = RtType.button, color = RtColors.Primary, modifier = Modifier.clickable(role = Role.Button) { scope.launch { sharePost(context, c, p) } }.padding(vertical = 4.dp))
                if (waiting > 1) Text("Swap", style = RtType.button, color = RtColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { c.postPlan.swap(all); swaps++ }.padding(vertical = 4.dp))
            }
        }
    }
}
