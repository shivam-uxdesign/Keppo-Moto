package com.ridetrack.app.studio.storyboard

import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.studio.Script
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The board played in real time before anything is filmed: each shot's sketch for its length,
 * its on-screen text, and its captions phrase by phrase (timed by their words, or by the
 * recording when there is one). Pure parts unit-tested.
 */
object Animatic {
    /** One shot on the animatic's timeline. */
    data class Frame(val shotId: String, val number: String, val section: String, val startMs: Long, val durMs: Long, val board: Storyboard)

    fun frames(s: Script): List<Frame> {
        var t = 0L
        return Placeholders.shots(s).map { (sec, sh) ->
            Frame(sh.id!!, Placeholders.number(s, sh.id), sec.text ?: sec.kind.label, t, sh.board!!.durationMs, sh.board).also { t += it.durMs }
        }
    }

    /** The frame showing at [ms]. */
    fun at(frames: List<Frame>, ms: Long): Frame? = frames.lastOrNull { it.startMs <= ms } ?: frames.firstOrNull()

    /** The caption phrase showing at [ms] into frame [f] (its words spread over the shot, or over the line's length when shorter). */
    fun caption(f: Frame, ms: Long): String? {
        val words = f.board.captionWords ?: return null
        val span = Health.sayMs(f.board.say).takeIf { it > 0 && f.board.caption == Captions.SAY }?.coerceAtMost(f.durMs) ?: f.durMs
        val into = ms - f.startMs
        return VoiceLines.written(words, span).firstOrNull { into >= it.startMs && into < it.endMs }?.text
    }

    /** The on-screen text showing at [ms]. */
    fun text(s: Script, f: Frame, ms: Long): String? = s.texts.firstOrNull { it.shotId == f.shotId && ms - f.startMs < it.durMs }?.text
}

@Composable
fun AnimaticScreen(chatId: String, onBack: () -> Unit) {
    val c = appContainer()
    val chats by c.storyboard.chats.collectAsStateWithLifecycle()
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val chat = chats.firstOrNull { it.id == chatId } ?: return
    val s = chat.script ?: return
    val frames = remember(s) { Animatic.frames(s) }
    val total = frames.sumOf { it.durMs }.coerceAtLeast(1)
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    val pictures = remember(s) { HashMap<String, Bitmap>() }
    // The voice-over, when the template has takes of the lines as they are now.
    val template = reels.filter { it.id in chat.templateIds && it.deletedAt == null }.maxByOrNull { it.updatedAt }
    val voice = remember(template?.updatedAt) { mutableStateOf<File?>(null) }
    LaunchedEffect(template?.updatedAt) {
        val t = template ?: return@LaunchedEffect
        if (t.takes.isEmpty() || TemplateSync.behind(chat, t) != null) return@LaunchedEffect
        voice.value = withContext(Dispatchers.IO) { com.ridetrack.app.studio.VoiceRecorder.mix(c.reels.takes(t), t.plan.totalMs, File(c.appContext.cacheDir, "animatic-voice.wav")) }
    }
    val player = remember(voice.value) { voice.value?.let { f -> runCatching { MediaPlayer().apply { setDataSource(f.path); prepare() } }.getOrNull() } }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    LaunchedEffect(playing, player) {
        if (!playing) { runCatching { player?.pause() }; return@LaunchedEffect }
        if (pos >= total) pos = 0
        player?.let { runCatching { it.seekTo(pos.toInt()); it.start() } }
        var last = withFrameMillis { it }
        while (playing) {
            val now = withFrameMillis { it }
            pos = player?.takeIf { it.isPlaying }?.currentPosition?.toLong() ?: (pos + (now - last))
            last = now
            if (pos >= total) { pos = total; playing = false; runCatching { player?.pause() } }
        }
    }
    val f = Animatic.at(frames, pos)
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = RtType.bodyStrong, color = Color.White)
                Text(if (voice.value != null) "Animatic · with your voice-over" else "Animatic · captions timed by their words", style = RtType.caption, color = RtColors.TextSecondary)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(20.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClickLabel = if (playing) "Pause" else "Play") { playing = !playing }) {
                f?.let { fr ->
                    val bmp = pictures.getOrPut(fr.shotId) { PlaceholderArt.draw(fr.number, fr.section, fr.board, 0xFF69C8CB.toInt()) }
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Animatic.text(s, fr, pos)?.let { t ->
                        Text(t, style = RtType.headline, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    Animatic.caption(fr, pos)?.let { t ->
                        Text(t, style = RtType.bodyStrong, color = Color.White, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                if (!playing) Icon(if (pos >= total) Icons.Rounded.Replay else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).width(64.dp).height(64.dp))
            }
        }
        // The timeline: one block per shot, the playhead's shot lit.
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                frames.forEach { fr ->
                    val on = fr.shotId == f?.shotId
                    Box(Modifier.weight(fr.durMs.toFloat()).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(if (on) RtColors.Primary else RtColors.SurfaceRaised)
                        .clickable(role = Role.Button, onClickLabel = "Go to shot ${fr.number.trimStart('0')}") { pos = fr.startMs; runCatching { player?.seekTo(pos.toInt()) } })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f?.let { "#${it.number.trimStart('0')} · ${it.board.action}" } ?: "", style = RtType.caption, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text("${seconds(pos)} / ${seconds(total)} s", style = RtType.caption, color = RtColors.TextSecondary)
                IconButton(onClick = { playing = !playing }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White) }
            }
        }
    }
}
