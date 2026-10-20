package com.ridetrack.app.studio.storyboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridetrack.app.AppContainer
import com.ridetrack.app.share.ShareImages
import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.PhoneVideos
import com.ridetrack.app.studio.TimelineEdits
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.moments.Thumb
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Putting footage into a template's slot. */
object TemplateFill {
    /**
     * [seg] in place of shot [shotId]'s placeholder (or suggestion). When it's shorter than the slot,
     * the next shots move up and so do the texts after it. The shot stops being "suggested",
     * unless [suggested] (Studio's own pick, badged until the rider keeps it).
     */
    suspend fun apply(c: AppContainer, reelId: String, shotId: String, seg: ClipSegment, suggested: Boolean = false) {
        c.reels.update(reelId) { p ->
            val i = p.plan.segments.indexOfFirst { it is ClipSegment && (TemplateBuilder.shotIdOf(it.bit) == shotId || it.bit.momentId == filledBy(p, shotId)) }
            if (i < 0) return@update p
            val old = p.plan.segments[i] as ClipSegment
            val start = p.plan.startOf(i)
            val delta = seg.durMs - old.durMs
            val texts = p.plan.texts.map { t -> if (t.startMs >= start + old.durMs) t.copy(startMs = t.startMs + delta, endMs = t.endMs + delta) else t }
            val plan = TimelineEdits.normalize(p.plan.copy(segments = p.plan.segments.toMutableList().also { it[i] = seg.copy(section = old.section, hook = old.hook, text = old.text) }, texts = texts))
            val info = p.template?.let { it.copy(suggested = if (suggested) it.suggested + shotId else it.suggested - shotId, filled = it.filled + (shotId to seg.bit.momentId)) }
            p.copy(plan = plan, durationMs = plan.totalMs, template = info)
        }
        ToFilmList.sync(c)
    }

    /** The rider keeps Studio's pick for [shotId]: the badge goes. */
    suspend fun keep(c: AppContainer, reelId: String, shotId: String) {
        c.reels.update(reelId) { p -> p.copy(template = p.template?.let { it.copy(suggested = it.suggested - shotId) }) }
    }

    /**
     * After a ride: every template's empty shots that footage can fill get the strongest new match,
     * badged as suggested. Must-record shots and clips already in the template are left alone.
     */
    suspend fun suggestOpen(c: AppContainer) {
        val templates = c.reels.reels.value.filter { it.template != null && it.deletedAt == null && it.script != null && TemplateBuilder.placeholders(it.plan).isNotEmpty() }
        if (templates.isEmpty()) return
        val cands = Candidates.all(c)
        templates.forEach { p ->
            val used = p.plan.clips.map { it.bit.momentId }.toMutableSet()
            TemplateBuilder.placeholders(p.plan).forEach { id ->
                val b = Placeholders.find(p.script!!, id)?.board ?: return@forEach
                val best = Suggest.rank(b, cands, 1, exclude = used).firstOrNull()?.takeIf { it.second >= Suggest.THRESHOLD + 2 } ?: return@forEach
                val seg = Candidates.segment(c, best.first, b) ?: return@forEach
                used += best.first.momentId
                apply(c, p.id, id, seg, suggested = true)
            }
        }
    }

    /** The clip that fills [shotId] now, when it's filled. */
    private fun filledBy(p: com.ridetrack.app.studio.ReelProject, shotId: String): String? = p.template?.filled?.get(shotId)

    /** A phone video (or a recorded shot) fitted into [b]'s slot. */
    fun phoneSegment(clip: com.ridetrack.app.studio.PhoneClip, b: Storyboard): ClipSegment {
        val fit = AutoTrim.fit(clip.durationMs, b.durationMs, null, engineFirst = b.audio == "engine")
        val bit = Bit("${clip.id}#t", clip.id, clip.durationMs, fit.inMs, fit.inMs + (fit.durMs * fit.speed).toLong(), clip.startMillis, emptyList(), 0.0, 5f, source = clip.uri.toString(), camera = if ("front" in b.camera.lowercase()) null else "back")
        return ClipSegment(bit, fit.inMs, fit.durMs, emptyList(), hook = false, speed = fit.speed)
    }
}

/**
 * Fill a shot: its instruction, then the closest clips from your rides, any moment, a phone video,
 * or Record (off-bike shots: the instruction, a 3-2-1, then the camera). Shots filmed while riding
 * go on the to-film list and the ride pop-up instead.
 */
@Composable
fun FillScreen(reelId: String, shotId: String, onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = c.reels.reels.value.firstOrNull { it.id == reelId }
    val script = project?.script
    val shot = script?.let { Placeholders.find(it, shotId) }
    val b = shot?.board
    var cands by remember { mutableStateOf<List<Pair<Candidate, Double>>?>(null) }
    var all by remember { mutableStateOf<List<com.ridetrack.app.moments.Moment>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    LaunchedEffect(shotId) {
        if (b == null) return@LaunchedEffect
        val list = Candidates.all(c)
        cands = Suggest.rank(b, list, 3)
        all = withContext(Dispatchers.IO) { c.moments.allLive().filter { it.kind == com.ridetrack.app.moments.MomentKind.CLIP && it.file.isFile }.sortedByDescending { it.timeMillis }.take(60) }
    }
    fun done() { busy = false; onBack() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || b == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val clip = withContext(Dispatchers.IO) { PhoneVideos.read(context, uri) }
            if (clip != null) TemplateFill.apply(c, reelId, shotId, TemplateFill.phoneSegment(clip, b))
            done()
        }
    }
    val shotFile = remember { File(c.reels.dir(reelId), "shot-$shotId.mp4") }
    val record = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        if (!ok || b == null || !shotFile.isFile) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val clip = withContext(Dispatchers.IO) { PhoneVideos.read(context, Uri.fromFile(shotFile)) }
            if (clip != null) TemplateFill.apply(c, reelId, shotId, TemplateFill.phoneSegment(clip, b))
            done()
        }
    }
    LaunchedEffect(countdown) {
        if (countdown <= 0) return@LaunchedEffect
        delay(1_000)
        if (countdown == 1) runCatching { record.launch(ShareImages.uriFor(context, shotFile.apply { parentFile?.mkdirs() })) }
        countdown--
    }

    Column(Modifier.fillMaxSize().background(RtColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = RtColors.TextPrimary) }
            Text(if (script != null) "Shot ${Placeholders.number(script, shotId)}" else "Fill a shot", style = RtType.title, color = RtColors.TextPrimary)
        }
        if (b == null) { Text("This shot isn't in the template any more.", style = RtType.body, color = RtColors.TextSecondary, modifier = Modifier.padding(16.dp)); return@Column }
        if (countdown > 0) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(b.action, style = RtType.headline, color = RtColors.TextPrimary, textAlign = TextAlign.Center)
                    Text("${b.framingKind.label} · ${b.camera} · ${b.mount} · ${String.format(java.util.Locale.US, "%.1f s", b.durationMs / 1000.0)}", style = RtType.body, color = RtColors.TextSecondary, textAlign = TextAlign.Center)
                    Text("$countdown", style = RtType.display, color = RtColors.Primary)
                    Text("Film a second more on each side; Studio trims it.", style = RtType.caption, color = RtColors.TextTertiary)
                }
            }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(b.action, style = RtType.headline, color = RtColors.TextPrimary)
            Text("${b.framingKind.label} · ${b.camera} · ${b.mount} · ${String.format(java.util.Locale.US, "%.1f s", b.durationMs / 1000.0)}", style = RtType.caption, color = RtColors.TextSecondary)
            b.say?.let { Text("Say: “$it”", style = RtType.caption, color = RtColors.Primary) }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = RtColors.Primary); Spacer(Modifier.size(8.dp)); Text("Putting it in…", style = RtType.caption, color = RtColors.TextSecondary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.riding) {
                    SecondaryButton("Record on a ride", {}, Modifier.weight(1f), enabled = false)
                } else {
                    PrimaryButton("Record", { countdown = 3 }, Modifier.weight(1f))
                }
                SecondaryButton("Phone video", { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, Modifier.weight(1f))
            }
            if (b.riding) Text("Filmed while riding: it's on your shot list and in the ride pop-up for the first 30 s. Film it with the pop-up's record button, then pick it here from your moments.", style = RtType.caption, color = RtColors.TextSecondary)
            if (b.match.mustRecord) Text("This one needs fresh footage, so there are no suggestions.", style = RtType.caption, color = RtColors.Warning)
            cands?.takeIf { it.isNotEmpty() }?.let { list ->
                Text("CLOSEST FROM YOUR RIDES", style = RtType.label, color = RtColors.TextTertiary)
                list.forEach { (cand, _) -> MomentRow(all.firstOrNull { it.id == cand.momentId }, "Fits this shot") { busy = true; scope.launch { Candidates.segment(c, cand, b)?.let { TemplateFill.apply(c, reelId, shotId, it) }; done() } } }
            }
            Text("ALL YOUR MOMENTS", style = RtType.label, color = RtColors.TextTertiary)
            all.forEach { m ->
                MomentRow(m, Format.rideDate(m.timeMillis)) {
                    busy = true
                    scope.launch {
                        val cand = Candidates.all(c).firstOrNull { it.momentId == m.id }
                        cand?.let { Candidates.segment(c, it, b) }?.let { TemplateFill.apply(c, reelId, shotId, it) }
                        done()
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentRow(m: com.ridetrack.app.moments.Moment?, note: String, onPick: () -> Unit) {
    m ?: return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RtColors.Surface).clickable(role = Role.Button, onClickLabel = "Use this clip", onClick = onPick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(m.thumb, Modifier.size(width = 36.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(((m.durationMillis ?: 0) / 1000).toString() + " s · " + (if (m.camera == "back") "road" else "selfie"), style = RtType.bodyStrong, color = RtColors.TextPrimary)
            Text(com.ridetrack.app.moments.MomentKeywords.shown(m.keywords.orEmpty()).take(4).joinToString(", ") { it.text }.ifBlank { note }, style = RtType.caption, color = RtColors.TextSecondary, maxLines = 2)
        }
    }
}
