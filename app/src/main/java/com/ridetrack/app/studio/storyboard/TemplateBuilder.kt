package com.ridetrack.app.studio.storyboard

import android.net.Uri
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.MomentKeywords
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.studio.Bit
import com.ridetrack.app.studio.ClipSegment
import com.ridetrack.app.studio.ReelProject
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.Segment
import com.ridetrack.app.studio.StudioPlan
import com.ridetrack.app.studio.StudioText
import com.ridetrack.app.studio.TemplateInfo
import com.ridetrack.app.studio.TextItem
import com.ridetrack.app.studio.Vibe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A storyboard made into a Reel template: every shot a placeholder picture (a still on the video
 * track, for the shot's exact length), section titles and on-screen texts on the Text row, the
 * music note kept with it. It's an ordinary Reel in the editor; placeholders are found again by
 * their "ph:<shot id>" clip ids.
 */
object TemplateBuilder {
    const val PREFIX = "ph:"

    fun isPlaceholder(b: Bit): Boolean = b.momentId.startsWith(PREFIX)
    fun shotIdOf(b: Bit): String? = b.momentId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    /** The stand-in clip for a placeholder: its picture as the source, nothing to play. */
    fun bitFor(shotId: String, b: Storyboard, png: File, at: Long): Bit =
        Bit("$PREFIX$shotId#0", "$PREFIX$shotId", b.durationMs, 0, b.durationMs, at, emptyList(), 0.0, 5f, source = Uri.fromFile(png).toString(), camera = if ("front" in b.camera.lowercase()) null else "back")

    /**
     * The template's plan (pure apart from the files it names): a placeholder per shot, or the
     * footage in [filled] (shot id → segment) for shots already filled.
     */
    fun plan(script: Script, pictures: Map<String, File>, vibe: Vibe, at: Long, filled: Map<String, ClipSegment> = emptyMap()): StudioPlan {
        val segs = ArrayList<Segment>()
        val texts = ArrayList<TextItem>()
        val startOf = HashMap<String, Long>()
        var t = 0L
        script.sections.forEachIndexed { k, sec ->
            val sectionStart = t
            sec.shots.forEach { sh ->
                val id = sh.id ?: return@forEach
                val b = sh.board ?: return@forEach
                startOf[id] = t
                val seg = (filled[id]?.copy(section = k, hook = segs.isEmpty())
                    ?: pictures[id]?.let { png -> ClipSegment(bitFor(id, b, png, at + segs.size), 0, b.durationMs, emptyList(), hook = segs.isEmpty(), section = k, still = png.absolutePath) }
                    ?: return@forEach).let { it.copy(lines = VoiceLines.segmentLines(b, it.lines, it.durMs)) }
                segs += seg
                t += seg.durMs
            }
            sec.text?.let { txt -> if (t > sectionStart) texts += TextItem("sec-$k", sectionStart, minOf(t, sectionStart + 3_000), txt) }
        }
        script.texts.forEachIndexed { i, x ->
            val s = startOf[x.shotId] ?: return@forEachIndexed
            texts += TextItem("txt-$i", s, minOf(t, s + x.durMs), x.text)
        }
        return StudioPlan(segs, vibe, texts = texts.filter { it.endMs > it.startMs }, openerLifted = true)
    }

    /** Templates whose placeholder pictures are missing (restored from a backup) get them drawn again. */
    suspend fun redraw(c: AppContainer) {
        c.reels.reels.value.filter { it.template != null && it.script != null }.forEach { p ->
            if (p.plan.clips.any { isPlaceholder(it.bit) && it.still?.let { s -> !File(s).isFile } == true }) {
                runCatching { pictures(c.reels.dir(p.id), p.script!!, 0xFF69C8CB.toInt()) }
            }
        }
    }

    /** Placeholders still in [plan], by shot id. */
    fun placeholders(plan: StudioPlan): List<String> = plan.clips.mapNotNull { shotIdOf(it.bit) }

    /** Draws every shot's placeholder into the template's folder. */
    suspend fun pictures(dir: File, script: Script, accent: Int): Map<String, File> = withContext(Dispatchers.Default) {
        Placeholders.shots(script).associate { (sec, sh) ->
            val id = sh.id!!
            val f = File(dir, "ph-$id.png")
            if (!f.isFile) {
                val bmp = PlaceholderArt.draw(Placeholders.number(script, id), sec.text ?: sec.kind.label, sh.board!!, accent)
                PlaceholderArt.save(bmp, f)
                bmp.recycle()
            }
            id to f
        }
    }

    /**
     * Makes (or remakes, for [existing]) the template from [script]. Footage per [clips]: the best
     * matches fill their slots, marked to confirm (AUTO; FRESH only with footage filmed since the
     * template was made), or nothing goes in by itself (SUGGEST); [rideId]: only that ride's clips.
     * Remaking keeps the footage already in shots that are still there, and the voice-over takes
     * of lines that didn't change (moved to where their shot now starts).
     */
    suspend fun make(c: AppContainer, chatId: String, script: Script, version: Int, clips: ClipSource, existing: String? = null, rideId: String? = null): ReelProject {
        val id = existing ?: c.reels.newId()
        val old = existing?.let { c.reels.get(it) }
        val dir = c.reels.dir(id).apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("ph-") }?.forEach { it.delete() }
        val pics = pictures(dir, script, 0xFF69C8CB.toInt())
        val now = System.currentTimeMillis()
        val made = old?.createdAt ?: now
        val ids = Placeholders.shots(script).mapNotNull { it.second.id }.toSet()
        // Footage already in the template stays with its shot.
        val kept = HashMap<String, ClipSegment>()
        old?.template?.filled?.forEach { (shot, moment) ->
            if (shot !in ids) return@forEach
            old.plan.segments.filterIsInstance<ClipSegment>().firstOrNull { it.bit.momentId == moment }?.let { kept[shot] = it }
        }
        // The strongest matches go in at once, with a badge until the rider confirms them.
        val cands = if (clips.fillsByItself) Candidates.all(c, clips, made, rideId) else emptyList()
        val suggested = HashMap<String, ClipSegment>()
        Placeholders.shots(script).forEach { (_, sh) ->
            val b = sh.board ?: return@forEach
            if (sh.id in kept) return@forEach
            val best = Suggest.rank(b, cands, 1, exclude = (suggested.values + kept.values).map { it.bit.momentId }.toSet()).firstOrNull() ?: return@forEach
            if (best.second < Suggest.THRESHOLD + 2) return@forEach
            Candidates.segment(c, best.first, b)?.let { suggested[sh.id!!] = it }
        }
        val plan = plan(script, pics, c.studio.options.vibe, now, kept + suggested)
        val filled = kept.mapValues { it.value.bit.momentId } + suggested.mapValues { it.value.bit.momentId }
        val takes = old?.let { keptTakes(c, it, script, plan, filled) }.orEmpty()
        val p = ReelProject(
            id = id, rideId = null, createdAt = made, updatedAt = now,
            title = script.title, series = "", episode = 0, hookLine = "", postCaption = "", story = script.musicNote?.let { "Music: $it" },
            options = c.studio.options.copy(outro = false, map = false), musicUri = null, musicName = null,
            plan = plan, takes = emptyList(), tips = emptyList(), durationMs = plan.totalMs, script = script,
            template = TemplateInfo(
                chatId, version, Placeholders.shots(script).size, suggested.keys + old?.template?.suggested.orEmpty().filter { it in kept }, filled,
                clips = clips, captions = old?.template?.captions ?: CaptionSource.WRITTEN,
            ),
        )
        val saved = c.reels.save(p, null, takes)
        // The cover: the first placeholder.
        pics.values.firstOrNull()?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.let { c.reels.writeCover(id, it); it.recycle() } }
        return saved
    }

    /** [old]'s voice-over takes whose line is the same in [script], moved to where that shot starts in [plan]. */
    private fun keptTakes(c: AppContainer, old: ReelProject, script: Script, plan: com.ridetrack.app.studio.StudioPlan, filled: Map<String, String>): List<com.ridetrack.app.studio.VoiceTake> {
        val was = old.script ?: return emptyList()
        val oldFilled = old.template?.filled.orEmpty()
        val oldStart = VoiceLines.lines(was).mapNotNull { (id, line) -> VoiceLines.shotStart(old.plan, oldFilled, id)?.let { it to (id to line) } }.toMap()
        val newLines = VoiceLines.lines(script).toMap()
        return c.reels.takes(old).mapNotNull { t ->
            val (id, line) = oldStart[t.startMs] ?: return@mapNotNull null
            if (newLines[id] != line) return@mapNotNull null
            VoiceLines.shotStart(plan, filled, id)?.let { t.copy(startMs = it) }
        }
    }
}

/** Clips from every ride, as candidates for a shot. */
object Candidates {
    suspend fun all(c: AppContainer): List<Candidate> = all(c, ClipSource.AUTO, 0)

    /** The clips [clips] allows for a template made at [madeMillis] (FRESH: only those filmed since). */
    suspend fun all(c: AppContainer, clips: ClipSource, madeMillis: Long, rideId: String? = null): List<Candidate> = withContext(Dispatchers.IO) {
        c.moments.allLive().filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) > 0 && clips.allows(it.timeMillis, madeMillis) && (rideId == null || it.rideId == rideId) }.map { m ->
            val said = m.transcript.orEmpty().lowercase().split(Regex("[^a-z0-9']+")).filter { it.length >= 3 }
            Candidate(
                momentId = m.id, rideId = m.rideId, durationMs = m.durationMillis ?: 0,
                events = m.types.map { it.name.lowercase().replace('_', ' ') }.toSet(),
                words = (MomentKeywords.shown(m.keywords.orEmpty()).flatMap { k -> k.key.split(' ') + k.key } + said).toSet(),
                camera = m.camera, hasEngine = m.engineFile.isFile,
                eventAtMs = (m.timeMillis - m.videoStartMillis).takeIf { it in 0..(m.durationMillis ?: 0) },
            )
        }
    }

    /** [cand] fitted into [b]'s slot (around its event; slowed or shrunk when short). */
    suspend fun segment(c: AppContainer, cand: Candidate, b: Storyboard): ClipSegment? = withContext(Dispatchers.IO) {
        val m = c.moments.allLive().firstOrNull { it.id == cand.momentId } ?: return@withContext null
        val fit = AutoTrim.fit(cand.durationMs, b.durationMs, cand.eventAtMs, engineFirst = b.audio == "engine")
        val lines = StudioText.load(m.file).orEmpty()
        val bit = Bit("${m.id}#t", m.id, cand.durationMs, fit.inMs, fit.inMs + (fit.durMs * fit.speed).toLong(), m.videoStartMillis, lines, (m.topSpeedMps ?: 0.0) * 3.6, 5f, camera = m.camera)
        ClipSegment(
            bit, fit.inMs, fit.durMs,
            lines.filter { it.endMs > fit.inMs && it.startMs < fit.inMs + (fit.durMs * fit.speed).toLong() }.map { it.copy(startMs = ((it.startMs - fit.inMs) / fit.speed).toLong(), endMs = ((it.endMs - fit.inMs) / fit.speed).toLong()) },
            hook = false, speed = fit.speed,
        )
    }
}
