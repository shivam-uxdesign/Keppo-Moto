package com.ridetrack.app.studio.storyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.studio.PhoneVideos
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Framing guides: the box a shot's subject should fill, as a share of the frame. Pure. */
object FramingGuide {
    /** Width and height of the guide box as fractions of the frame, for [f]; null = thirds only (wide). */
    fun box(f: Framing): Pair<Float, Float>? = when (f) {
        Framing.EXTREME -> 0.55f to 0.22f
        Framing.CLOSE -> 0.6f to 0.45f
        Framing.MEDIUM -> 0.75f to 0.7f
        Framing.WIDE -> null
    }

    /** Whether the shot wants the selfie camera. */
    fun front(b: Storyboard): Boolean = "front" in b.camera.lowercase() || "selfie" in b.camera.lowercase()

    /** How long to film: the shot plus a second on each side for Studio to trim. */
    fun filmMs(b: Storyboard): Long = b.durationMs + 2_000
}

/**
 * Shoot mode: the app's own camera for a group of shots set up the same way. Each shot shows
 * what to film and a framing guide, its Say line as a teleprompter, then 3-2-1 and a ring
 * counting its length (a second more each side); Keep puts it straight into the template,
 * Retake tries again, and the next shot follows.
 */
@SuppressLint("MissingPermission")
@Composable
fun ShootModeScreen(reelId: String, shotIds: List<String>, onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val reels by c.reels.reels.collectAsStateWithLifecycle()
    val project = reels.firstOrNull { it.id == reelId } ?: return
    val script = project.script ?: return
    val scope = rememberCoroutineScope()
    var at by remember { mutableIntStateOf(0) }
    val id = shotIds.getOrNull(at)
    val b = id?.let { Placeholders.find(script, it)?.board }
    var granted by remember { mutableStateOf(has(context, Manifest.permission.CAMERA) && has(context, Manifest.permission.RECORD_AUDIO)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.all { it } }
    LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    val controller = remember { LifecycleCameraController(context).apply { setEnabledUseCases(CameraController.VIDEO_CAPTURE) } }
    var front by remember(id) { mutableStateOf(b?.let { FramingGuide.front(it) } ?: false) }
    LaunchedEffect(front, granted) {
        if (!granted) return@LaunchedEffect
        controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching { controller.bindToLifecycle(lifecycle) }
    }
    DisposableEffect(Unit) { onDispose { runCatching { controller.unbind() } } }
    var countdown by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var take by remember(id) { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun record() {
        val shot = b ?: return
        val file = File(c.reels.dir(reelId), "shot-$id-${System.currentTimeMillis()}.mp4").apply { parentFile?.mkdirs() }
        note = null
        scope.launch {
            for (n in 3 downTo 1) { countdown = n; delay(1_000) }
            countdown = 0
            val rec = runCatching {
                controller.startRecording(FileOutputOptions.Builder(file).build(), AudioConfig.create(true), ContextCompat.getMainExecutor(context)) { e ->
                    if (e is VideoRecordEvent.Finalize) {
                        recording = null
                        if (e.hasError() && !file.isFile) note = "Couldn't save the take (${e.error}). Try again." else take = file
                    }
                }
            }.onFailure { note = "The camera isn't free (${it.message ?: "busy"}). If a ride is recording, stop it first." }.getOrNull() ?: return@launch
            recording = rec
            val len = FramingGuide.filmMs(shot)
            val start = System.currentTimeMillis()
            while (recording != null) {
                val t = System.currentTimeMillis() - start
                progress = (t.toFloat() / len).coerceIn(0f, 1f)
                if (t >= len) { rec.stop(); break }
                delay(50)
            }
            progress = 0f
        }
    }

    fun keep() {
        val f = take ?: return
        val shot = b ?: return
        val sid = id ?: return
        busy = true
        scope.launch {
            val clip = withContext(Dispatchers.IO) { PhoneVideos.read(context, Uri.fromFile(f)) }
            if (clip != null) TemplateFill.apply(c, reelId, sid, TemplateFill.phoneSegment(clip, shot))
            ToFilmList.sync(c)
            busy = false
            take = null
            if (at < shotIds.lastIndex) at++ else onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView({ ctx -> PreviewView(ctx).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } }, Modifier.fillMaxSize())
        // The framing guide: thirds, and the box the subject should fill.
        b?.let { shot ->
            Canvas(Modifier.fillMaxSize()) {
                val line = Color.White.copy(alpha = 0.35f)
                for (k in 1..2) {
                    drawLine(line, Offset(size.width * k / 3, 0f), Offset(size.width * k / 3, size.height), 1.5f)
                    drawLine(line, Offset(0f, size.height * k / 3), Offset(size.width, size.height * k / 3), 1.5f)
                }
                FramingGuide.box(shot.framingKind)?.let { (w, h) ->
                    val bw = size.width * w
                    val bh = size.height * h
                    drawRoundRect(
                        Color(0xFF69C8CB), Offset((size.width - bw) / 2, size.height * 0.42f - bh / 2), Size(bw, bh),
                        androidx.compose.ui.geometry.CornerRadius(24f), style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 16f))),
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { recording?.stop(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("Shot ${id?.let { Placeholders.number(script, it).trimStart('0') } ?: ""} · ${at + 1} of ${shotIds.size}", style = RtType.caption, color = RtColors.TextSecondary)
                    Text(b?.action ?: "All filmed", style = RtType.bodyStrong, color = Color.White, maxLines = 2)
                    b?.let { Text("${it.framingKind.label} · ${it.camera} · ${it.mount} · ${seconds(it.durationMs)} s", style = RtType.caption, color = RtColors.TextSecondary) }
                }
                IconButton(onClick = { front = !front }, enabled = recording == null) { Icon(Icons.Rounded.Cameraswitch, "Switch camera", tint = Color.White) }
            }
            // The teleprompter: the line to say, big, near the lens.
            b?.say?.let { say ->
                Text(
                    say, style = RtType.headline, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xB3000000)).padding(14.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (countdown > 0) Text("$countdown", style = RtType.display, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.weight(1f))
            note?.let { Text(it, style = RtType.caption, color = RtColors.Warning, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
            if (!granted) Text("Shoot mode needs the camera and microphone.", style = RtType.body, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
            Box(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(20.dp), contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(color = RtColors.Primary)
                    take != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("Retake", { take?.delete(); take = null; record() }, Modifier.width(140.dp))
                        PrimaryButton(if (at < shotIds.lastIndex) "Keep · next" else "Keep · done", ::keep, Modifier.width(160.dp))
                    }
                    b == null -> PrimaryButton("Done", onBack)
                    else -> Box(
                        Modifier.size(84.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                            .clickable(role = Role.Button, enabled = granted && recording == null && countdown == 0, onClickLabel = "Film this shot") { record() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (recording != null) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(76.dp), color = RtColors.Recording, strokeWidth = 6.dp)
                        Box(Modifier.size(if (recording != null) 28.dp else 64.dp).clip(if (recording != null) RoundedCornerShape(6.dp) else CircleShape).background(RtColors.Recording))
                    }
                }
            }
            if (b != null && take == null && recording == null) Text(
                "3-2-1, then it films ${seconds(FramingGuide.filmMs(b))} s: a second more each side for Studio to trim.",
                style = RtType.caption, color = RtColors.TextSecondary, textAlign = TextAlign.Center, fontWeight = FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().background(Color(0x99000000)).padding(bottom = 12.dp),
            )
        }
    }
}

private fun has(context: android.content.Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
