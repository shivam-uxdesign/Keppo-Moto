package com.ridetrack.app.studio

import com.ridetrack.app.ui.format.Units
import android.net.Uri
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import com.ridetrack.app.moments.MomentSource
import com.ridetrack.app.transcribe.AppCheckSetup
import com.ridetrack.app.transcribe.ClipAudio
import com.ridetrack.app.transcribe.FirebaseTranscriber
import com.ridetrack.app.transcribe.GeminiNet
import com.ridetrack.app.ui.format.Format
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.TelemetrySample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

/** What asking for suggestions came to. */
sealed interface Suggested {
    /** Scripts to show; [why] is set when the app wrote them itself. */
    data class Scripts(val scripts: List<Script>, val why: String? = null) : Suggested
    /** Gemini couldn't be reached (no internet, a blocked address). */
    data class Unreachable(val error: Throwable) : Suggested
    /** Gemini's free limit is used up. */
    data object Busy : Suggested
}

/** What reading the clips' words came to: a note for the rider, and whether Gemini couldn't be reached. */
data class CaptionsRead(val note: String?, val unreachable: Throwable?, val missed: Int)

/**
 * A ride's Studio work without a screen: its clips, captions, suggestions and what the renderer
 * needs. Used by the Studio screen and by the work that runs after a ride, in the background.
 */
class StudioEngine(private val c: AppContainer, val rideId: String) {
    val phoneOnly = rideId == PHONE_STUDIO
    var clips: List<Moment> = emptyList()
        private set
    var moments: List<Moment> = emptyList()
        private set
    /** Videos from the phone's gallery, by id. */
    val phone = LinkedHashMap<String, PhoneClip>()
    /** Clips from other rides the rider added (their files are needed to render). */
    val borrowed = HashMap<String, Moment>()
    /** Clips from other rides or Saved clips added to a script, by moment id: the whole clip's bit. */
    val extras = LinkedHashMap<String, Bit>()
    var samples: List<TelemetrySample> = emptyList()
        private set
    var card: RideCard? = null
    var bits: List<Bit> = emptyList()
        private set
    /** Clips the rider left out. */
    @Volatile var excluded: Set<String> = emptySet()
    /** Set when reading was skipped or failed this time, so Make doesn't try again straight away. */
    var captionsTried = false

    /** Loads the ride; returns why there's nothing to work with, or null. */
    suspend fun load(): String? {
        if (phoneOnly) {
            card = RideCard(title = "My videos", subtitle = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(java.time.LocalDate.now()), route = emptyList(), stats = emptyList())
            return null
        }
        val ride = c.rides.get(rideId) ?: return "Ride not found"
        samples = withContext(Dispatchers.IO) { c.rides.track(rideId).samples }
        moments = c.moments.forRide(rideId)
        clips = moments.filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) >= 1_500 }.sortedBy { it.videoStartMillis }
        card = rideCard(ride.name, ride.startTimeMillis, ride.stats.distanceM, ride.durationMillis ?: 0, ride.stats.maxSpeedMps, moments.size)
        refreshBits()
        return null
    }

    suspend fun refreshBits() {
        bits = withContext(Dispatchers.IO) { buildBits() }
    }

    // ---- clips ------------------------------------------------------------------------------

    /** The clips as the script writer sees them: short keys in filming order, how each looks, what was said. */
    fun footage(): List<Footage> {
        val out = excluded
        val list = clips.filter { it.id !in out }.map { m ->
            val dur = m.durationMillis ?: 0
            Footage(
                key = "", momentId = m.id, durationMs = dur, startMillis = m.videoStartMillis,
                look = if (m.camera == "back") "road" else "selfie",
                lines = StudioText.load(m.file).orEmpty(),
                kmhMax = (0..(dur / 1000).toInt()).maxOfOrNull { s -> speedAt(m.videoStartMillis + s * 1000L) }?.coerceAtLeast(0) ?: 0,
                events = m.types.filter { it != RideEventType.VOICE }.joinToString { it.name.lowercase().replace('_', ' ') },
                label = Format.timeOfDay(m.videoStartMillis),
            )
        } + phone.values.filter { it.id !in out }.map { p ->
            Footage(
                key = "", momentId = p.id, durationMs = p.durationMs, startMillis = p.startMillis, look = "phone",
                lines = StudioText.load(PhoneVideos.captionKey(c.appContext, p.id)).orEmpty(),
                kmhMax = (0..(p.durationMs / 1000).toInt()).maxOfOrNull { s -> speedAt(p.startMillis + s * 1000L) }?.coerceAtLeast(0) ?: 0,
                label = "phone video",
            )
        }
        val added = extras.values.filter { b -> list.none { it.momentId == b.momentId } }.map { b ->
            Footage(
                key = "", momentId = b.momentId, durationMs = b.clipDurationMs, startMillis = b.atMillis - b.inMs,
                look = if (b.source != null) "saved" else "other ride",
                // The whole clip's words (a bit holds only its own part's).
                lines = borrowed[b.momentId]?.let { StudioText.load(it.file) } ?: b.lines,
                kmhMax = b.speedKmh.toInt(),
                label = b.fromRide ?: "",
            )
        }
        return (list + added).sortedBy { it.startMillis }.mapIndexed { i, f -> f.copy(key = "c${i + 1}") }
    }

    private fun buildBits(): List<Bit> {
        val out = excluded
        return clips.filter { it.id !in out }.flatMap { m ->
            val dur = m.durationMillis ?: 0
            val lines = StudioText.load(m.file).orEmpty()
            val focus = (m.timeMillis - m.videoStartMillis).coerceIn(0, dur)
            StudioPlanner.bitsOf(m.id, m.videoStartMillis, dur, lines, { t -> speedAt(t).coerceAtLeast(0).toDouble() }, focus).map { it.copy(camera = m.camera) }
        } + phone.values.filter { it.id !in out }.flatMap { p ->
            PhoneVideos.bits(p, StudioText.load(PhoneVideos.captionKey(c.appContext, p.id)).orEmpty()) { t -> speedAt(t).coerceAtLeast(0).toDouble() }
        } + extras.values
    }

    /** Every clip's video by moment id: moments' files and gallery videos. */
    fun files(): Map<String, Uri> =
        (clips + borrowed.values).associate { it.id to Uri.fromFile(it.file) } + phone.values.associate { it.id to it.uri }

    /** km/h at [wall]; -1 when there's no speed for that moment (a gap in GPS): no badge then. */
    fun speedAt(wall: Long): Int = StudioNumbers.speedAt(samples, wall)

    // ---- captions ---------------------------------------------------------------------------

    private fun unread(): List<CaptionJob> {
        val out = excluded
        // Every clip is read once: the rider's voice and clips filmed on purpose first.
        val moments = clips.filter { it.id !in out && StudioText.load(it.file) == null }
            .sortedByDescending { (if (RideEventType.VOICE in it.types) 4 else 0) + (if (it.source == MomentSource.MANUAL) 2 else 0) + (if (!it.transcript.isNullOrBlank()) 1 else 0) }
            .map { m ->
                CaptionJob(m.file.name, m.file, { f -> ClipAudio.extract(m.file, f) }) { lines ->
                    // The words also become the clip's transcript (shown in the ride and the clip viewer).
                    if (m.transcript == null) c.moments.setTranscript(m.id, lines.joinToString(" ") { it.text })
                }
            }
        // Phone videos are filmed on purpose: always worth reading, first.
        val gallery = phone.values.filter { it.id !in out && StudioText.load(PhoneVideos.captionKey(c.appContext, it.id)) == null }
            .map { p -> CaptionJob("phone ${p.id}", PhoneVideos.captionKey(c.appContext, p.id), { f -> ClipAudio.extract(c.appContext, p.uri, f) }) {} }
        return gallery + moments
    }

    /** Where a clip's words are kept: the moment's file, or the gallery video's key; null when unknown. */
    fun captionKey(momentId: String): File? =
        (clips + borrowed.values).firstOrNull { it.id == momentId }?.file ?: phone[momentId]?.let { PhoneVideos.captionKey(c.appContext, it.id) }

    /** Forgets a clip's words, so the next reading reads it again. */
    fun forget(momentId: String) {
        captionKey(momentId)?.let { StudioText.linesFile(it).delete() }
        captionsTried = false
    }

    /** Clips whose words haven't been read yet. */
    fun unreadCount(): Int = unread().size

    /**
     * Asks Gemini for timed lines for every clip not read yet, several clips per request (the
     * free tier allows few requests a day). Each clip is read once; its words are kept with it.
     */
    suspend fun readCaptions(onProgress: (String) -> Unit = {}, skip: () -> Boolean = { false }): CaptionsRead = withContext(Dispatchers.IO) {
        val todo = unread()
        if (todo.isEmpty()) return@withContext CaptionsRead(null, null, 0)
        val g = gemini()
        var missed = 0
        var note: String? = null
        var unreachable: Throwable? = null
        var done = 0
        val drops = GeminiNet.drops.get()
        // Fewer clips per request on mobile data (slow uploads, more drops); halved again when Gemini answers too slowly.
        var most = if (GeminiNet.metered(c.appContext)) METERED_BATCH else BATCH
        val queue = ArrayDeque(todo.chunked(most))
        batches@ while (queue.isNotEmpty()) {
            if (skip()) break
            val batch = queue.removeFirst()
            if (batch.size > most) { batch.chunked(most).asReversed().forEach { queue.addFirst(it) }; continue }
            onProgress("${done + 1}–${done + batch.size} of ${todo.size} clips")
            val audios = batch.mapIndexed { k, j -> j to File(c.appContext.cacheDir, "studio-cap-$rideId-$done-$k.m4a") }
            try {
                val usable = audios.filter { (j, f) -> j.extract(f) && f.length() <= MAX_AUDIO_BYTES / 2 }
                audios.filter { it !in usable }.forEach { (j, _) -> StudioText.save(j.key, emptyList()) }
                val results = if (usable.isEmpty()) emptyList() else g.captionsBatch(usable.map { it.second })
                usable.forEachIndexed { i, (j, _) ->
                    val lines = results.getOrNull(i)
                    if (lines == null) { missed++; return@forEachIndexed }
                    StudioText.save(j.key, lines)
                    j.saved(lines)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Too slow for this connection: the same clips in smaller requests (one clip can't be split).
                if ((e as? com.ridetrack.app.transcribe.GeminiUnreachable)?.timedOut == true && batch.size > 1) {
                    most = (batch.size / 2).coerceAtLeast(1)
                    batch.chunked(most).asReversed().forEach { queue.addFirst(it) }
                    continue@batches
                }
                val what = "Couldn't read ${batch.size} clips (${batch.joinToString { it.name }})"
                // No internet is the phone's state, not a fault: a warning, and they're read next time.
                if (GeminiNet.isUnreachable(e)) c.errors.warn("Studio captions", "$what: no internet. They're read next time.", e) else c.errors.record("Studio captions", what, e)
                missed += batch.size
                if (GeminiNet.isUnreachable(e)) unreachable = e
                note = when {
                    GeminiNet.isUnreachable(e) -> "Captions were skipped. " + GeminiNet.message(e)
                    AppCheckSetup.isRejected(e) -> "Captions: Firebase didn't accept this phone (App Check). Add the debug token from Profile › Moments in Firebase."
                    e is FirebaseTranscriber.Busy -> "Captions were skipped: Gemini's free limit is used up. Remix once it's free again."
                    else -> "Captions: couldn't read $missed clip${if (missed > 1) "s" else ""} (${e.message?.take(80)})."
                }
                // Out of allowance, refused or unreachable: more requests won't help now.
                if (AppCheckSetup.isRejected(e) || e is FirebaseTranscriber.Busy || GeminiNet.isUnreachable(e)) break@batches
            } finally {
                audios.forEach { it.second.delete() }
            }
            done += batch.size
        }
        val dropped = GeminiNet.drops.get() - drops
        if (dropped > 0) c.errors.warn("Studio captions", "The connection dropped $dropped time${if (dropped > 1) "s" else ""} while reading; those clips were sent again when it came back")
        if (missed > 0 || skip()) captionsTried = true
        buildBits().also { bits = it }
        CaptionsRead(note, unreachable, missed)
    }

    /** One clip whose words Gemini reads: [key] is where its captions are kept. */
    private class CaptionJob(val name: String, val key: File, val extract: (File) -> Boolean, val saved: suspend (List<CaptionLine>) -> Unit)

    fun gemini() = StudioGemini(preferred = { c.transcripts.model }, onWorking = { c.transcripts.model = it })

    /** What Studio has learnt about the rider's style (see Your style). */
    fun style(): StyleContext? = c.style.context()

    // ---- suggestions ------------------------------------------------------------------------

    val planFile: File get() = File(File(c.appContext.filesDir, "studio-plans").apply { mkdirs() }, "$rideId.json")

    /** The saved suggestions; null when there are none yet. */
    suspend fun loadPlan(): List<Script>? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(planFile.readText()).getJSONArray("pieces") }.getOrNull()?.let { a ->
            (0 until a.length()).mapNotNull { ScriptJson.read(a.optJSONObject(it)) }
        }
    }

    /** Keeps the suggestions, with Gemini's answer as it came and what the checks changed (for Studio details). */
    suspend fun savePlan(scripts: List<Script>, answer: String? = null, why: String? = null) = withContext(Dispatchers.IO) {
        val fs = footage()
        val o = c.studio.options
        val old = runCatching { JSONObject(planFile.readText()) }.getOrNull()
        // New suggestions keep the series episodes found before (they're found once per ride and series).
        val kept = if (scripts.any { it.series != null }) emptyList() else old?.optJSONArray("pieces")?.let { a ->
            (0 until a.length()).mapNotNull { ScriptJson.read(a.optJSONObject(it)) }.filter { it.series != null }
        }.orEmpty()
        val all = kept + scripts
        val fixes = JSONArray().apply {
            all.forEach { sc -> put(JSONArray(ScriptWriter.toPlan(sc, fs, bits, sc.vibe ?: o.vibe, o).fixes)) }
        }
        planFile.writeAtomically(
            JSONObject()
                .put("pieces", JSONArray().apply { all.forEach { put(ScriptJson.write(it)) } })
                .put("fixes", fixes)
                .put("at", System.currentTimeMillis())
                .apply {
                    (answer ?: old?.optString("answer")?.takeIf { it.isNotEmpty() })?.let { put("answer", it.take(60_000)) }
                    why?.let { put("why", it) }
                }
                .toString(),
        )
    }

    /** Gemini's answer and the fixes kept with the suggestions, for Studio details. */
    fun planDetails(): String? = runCatching {
        val o = JSONObject(planFile.readText())
        buildString {
            o.optString("why").takeIf { it.isNotEmpty() }?.let { appendLine("Suggestions: $it") }
            o.optJSONArray("fixes")?.let { a ->
                for (i in 0 until a.length()) a.optJSONArray(i)?.takeIf { it.length() > 0 }?.let { f ->
                    appendLine("Piece ${i + 1} fixes: " + (0 until f.length()).joinToString { f.optString(it) })
                }
            }
            o.optString("answer").takeIf { it.isNotEmpty() }?.let { appendLine("Gemini's answer:"); appendLine(it.take(12_000)) }
        }
    }.getOrNull()

    /** Gemini's last content-plan answer as it came; null when there was none. */
    @Volatile var lastAnswer: String? = null
        private set

    /** Each clip's engine pitch (from its engine sound, else its own), by moment id, and the bike's range across them. */
    @Volatile private var revs: Map<String, FloatArray> = emptyMap()
    @Volatile private var revRange: ClosedFloatingPointRange<Float>? = null

    @Volatile private var revsDone = false

    /** Works out the revs of every clip from its sound (cached next to each clip, so it's slow only once). */
    suspend fun analyseRevs() = withContext(Dispatchers.IO) { analyseRevsNow() }

    @Synchronized
    private fun analyseRevsNow() {
        if (revsDone) return
        val m = clips.mapNotNull { mo -> EngineAudio.pitches(c.appContext, mo.file, mo.engineFile.takeIf { it.isFile }, null)?.let { mo.id to it } }.toMap()
        revs = m
        revRange = Revs.range(m.values.toList())
        revsDone = true
    }

    /**
     * How hard the engine revs (0..1 of this bike's range) [ms] into clip [momentId]; null where
     * unknown. With [wait], works the revs out first if they aren't yet (off the main thread only).
     */
    fun revAt(momentId: String, ms: Long, wait: Boolean = false): Float? {
        if (wait && !revsDone) analyseRevsNow()
        val r = revRange ?: return null
        return revs[momentId]?.let { Revs.level(it, r, ms) }
    }

    /** Each clip's engine sound (the DJI's second channel), by moment id, where it was recorded. */
    fun engineFiles(): Map<String, String> = clips.filter { it.engineFile.isFile }.associate { it.id to android.net.Uri.fromFile(it.engineFile).toString() }

    /**
     * Gemini's ideas, each of the top 3 with a second hook (a take under its card), then up to 2
     * Sound on pieces from the fastest stretches without words. No Gemini request.
     */
    private fun withSoundOn(fs: List<Footage>, list: List<Script>): List<Script> {
        val titles = list.map { it.title }.toMutableSet()
        val out = ArrayList<Script>()
        var hooks = 0
        list.forEach { sc ->
            out += sc
            if (hooks < 3) SecondHook.of(sc)?.takeIf { it.title !in titles }?.let { out += it; titles += it.title; hooks++ }
        }
        return out + SoundOn.scripts(fs).filter { it.title !in titles && list.none { s -> s.shape == SoundOn.SHAPE } }
    }

    /** The suggestions as pieces to show and make (each turned into a plan by the app's checks). */
    fun pieces(scripts: List<Script>, o: StudioOptions = c.studio.options): List<ContentPiece> {
        val fs = footage()
        return scripts.mapIndexedNotNull { i, sc ->
            val st = sc.style?.let(c.styles::get)
            val planned = ScriptWriter.toPlan(sc, fs, bits, st?.base ?: sc.vibe ?: o.vibe, o)
            if (planned.plan.clips.isEmpty()) return@mapIndexedNotNull null
            // A long reel the clips couldn't fill to about a minute isn't one: left out.
            if (sc.format == PieceFormat.LONG && planned.plan.totalMs < ScriptWriter.LONG_MIN_MS) return@mapIndexedNotNull null
            // The style Gemini picked from the rider's own: its whole look on the piece.
            var n = 0
            var plan = st?.let { Styles.apply(planned.plan, it) { "s${i}-${n++}" } } ?: planned.plan
            // Sound on: the engine's sound only, and the speed.
            if (sc.shape == SoundOn.SHAPE) plan = SoundOn.apply(plan, engineFiles()) { "so${i}-${n++}" }
            ContentPiece("p$i", sc, plan, planned.plannedMs, planned.fixes)
        }
    }

    /**
     * Asks Gemini what to make from the ride (one request). Reads no captions: call
     * [readCaptions] first. Without Gemini, or when its answer is unusable, the app suggests itself.
     */
    suspend fun suggest(title: String, all: Boolean = false): Suggested {
        val fs = withContext(Dispatchers.Default) { footage() }
        val cd = card ?: return Suggested.Scripts(emptyList())
        if (!c.transcripts.available) return Suggested.Scripts(localPieces(fs, "Made by the app (Gemini isn't in this build)", title), "Gemini isn't in this build")
        var answer: String? = null
        lastAnswer = null
        return try {
            val scripts = gemini().scripts(ScriptWriter.planPrompt(cd.title, cd.subtitle, fs, style(), PieceFormat.entries, c.styles.forSuggestions(), Posting.performance(c.reels.reels.value), all), fs, onAnswer = { answer = it }) { raw ->
                c.errors.record("Studio content plan", "Gemini's answer couldn't be read", null, raw.take(4_000))
            }
            lastAnswer = answer
            if (scripts.isEmpty()) {
                val why = "Gemini's answer couldn't be read"
                val local = localPieces(fs, "Made by the app: $why", title)
                Suggested.Scripts(withSoundOn(fs, if (all) withEvery(fs, local) else local), why).also { savePlan(it.scripts, answer, why) }
            } else {
                // What did well comes first (learnt from the views the rider noted).
                val list = Posting.rank(withSoundOn(fs, if (all) withEvery(fs, scripts) else scripts), Posting.boosts(c.reels.reels.value))
                savePlan(list, answer)
                Suggested.Scripts(list)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            c.errors.record("Studio content plan", "Gemini couldn't suggest content", e)
            when {
                GeminiNet.isUnreachable(e) -> Suggested.Unreachable(e)
                e is FirebaseTranscriber.Busy -> Suggested.Busy
                else -> {
                    val why = "Gemini had a problem (${e.message?.take(60)})"
                    Suggested.Scripts(localPieces(fs, "Made by the app: $why", title), why).also { savePlan(it.scripts, null, why) }
                }
            }
        }
    }

    /**
     * Show all: after Gemini's ideas, a piece for every other part of the clips (each take of
     * what was said, each riding shot as a caption clip). Gemini writes the caption clips' lines
     * in one more request; without it they're listed with no text, to write by hand.
     */
    private suspend fun withEvery(fs: List<Footage>, ideas: List<Script>): List<Script> {
        val extra = ScriptWriter.everyMoment(fs, ideas)
        val byId = fs.associateBy { it.momentId }
        val blank = extra.filter { it.format == PieceFormat.CAPTION && it.onScreen.isEmpty() }.take(30)
        val keyed = blank.mapIndexedNotNull { i, sc -> byId[sc.sections.first().shots.first().clip]?.let { "k${i + 1}" to it } }
        val jokes = if (keyed.isEmpty() || !c.transcripts.available) emptyMap() else
            runCatching { gemini().jokes(ScriptWriter.jokesPrompt(keyed)) }.onFailure { c.errors.warn("Studio content plan", "Gemini couldn't write the caption clips' lines", it) }.getOrDefault(emptyMap())
        val withLines = extra.map { sc ->
            val i = blank.indexOf(sc)
            jokes["k${i + 1}"]?.takeIf { i >= 0 }?.let { sc.copy(onScreen = it, title = it.first().take(40)) } ?: sc
        }
        return ideas + withLines
    }

    /** The app's own suggestions: a Reel as long as the footage fills well, and a 15 s Short. */
    fun localPieces(fs: List<Footage>, reason: String, title: String): List<Script> {
        val good = ScriptWriter.summary(fs).goodLengthSec
        return listOfNotNull(
            ScriptWriter.local(fs, PieceFormat.REEL, good, title, reason),
            ScriptWriter.local(fs, PieceFormat.SHORT, 15, title, reason)?.takeIf { good > 15 },
        )
    }

    // ---- making -----------------------------------------------------------------------------

    /** What the renderer needs for [plan] with the choices in [s]. */
    /** [waitForRevs]: the made video and the exact preview (drawn off the main thread) wait for the revs; the simple preview doesn't. */
    fun renderInput(plan: StudioPlan, s: StudioState, baseCard: RideCard, voice: File?, waitForRevs: Boolean = true): RenderInput {
        val ride = samples.toList()
        return RenderInput(
            // Hidden layers, text and stickers stay out of the video.
            plan = plan.shown(),
            files = files(),
            card = baseCard.copy(title = s.title.ifBlank { baseCard.title }),
            options = s.options,
            speedAt = { StudioNumbers.speedAt(ride, it) },
            leanAt = { StudioNumbers.leanAt(ride, it) },
            // Only the styles with the redline light need the revs (working them out takes a while).
            revAt = if (plan.vibe.spec.redline) { id, ms -> revAt(id, ms, wait = waitForRevs) } else { _, _ -> null },
            brand = c.styles.brand.value.takeIf { !it.empty },
            clockAt = { Format.timeOfDay(it).lowercase(Locale.getDefault()) },
            music = s.musicUri,
            opener = StudioArt.Opener(
                label = if (s.options.introOn) s.label.takeIf { s.series.isNotBlank() } else s.label,
                hookLine = s.hookLine.ifBlank { null },
                // Film's date label: the day of the ride.
                date = ride.firstOrNull()?.timeMillis?.let { java.text.SimpleDateFormat("d MMM yyyy", Locale.US).format(java.util.Date(it)) },
            ),
            voice = voice,
            voiceLines = s.takes.flatMap { t -> t.lines.map { it.copy(startMs = it.startMs + t.startMs, endMs = it.endMs + t.startMs) } },
        )
    }

    /**
     * What the renderer needs for a Reel still waiting to be made (picked up after the app was
     * closed): loads the ride and finds its clips; null when they're gone.
     */
    suspend fun inputFor(p: ReelProject): RenderInput? {
        if (load() != null) return null
        val missing = p.plan.clips.filter { it.bit.source == null }.map { it.bit.momentId }.filter { id -> clips.none { it.id == id } }.toSet()
        if (missing.isNotEmpty()) c.moments.all().filter { it.id in missing }.forEach { borrowed[it.id] = it }
        val have = files()
        if (p.plan.clips.any { it.bit.source == null && it.bit.momentId !in have }) return null
        val s = StudioState(
            title = p.title, series = p.series, episode = p.episode, hookLine = p.hookLine, options = p.options,
            musicUri = p.musicUri?.let(Uri::parse), musicName = p.musicName,
        )
        return renderInput(p.plan, s, card ?: return null, voice = null)
    }

    /** A suggestion as a job for [ReelMaker]: the Reel to save and what to render. */
    fun job(pc: ContentPiece, s: StudioState): MakeJob? {
        val cd = card ?: return null
        val sc = pc.script
        val now = System.currentTimeMillis()
        // A series episode: the series' look, label and caption, and the next episode number (taken now).
        val series = c.series.get(sc.series)
        val st = (series?.styleId ?: sc.style)?.let(c.styles::get)?.also { c.styles.used(it.id) }
        val ep = series?.let { c.series.takeEpisode(it.id) }
        var n = 0
        val plan = if (series?.styleId != null && st != null && sc.style != st.id) Styles.apply(pc.plan, st) { "se${n++}" } else pc.plan
        val opts = st?.let { Styles.options(s.options, it) } ?: s.options.copy(vibe = sc.vibe ?: s.options.vibe)
        val own = sc.postCaption ?: fallbackCaption()
        val project = ReelProject(
            id = c.reels.newId(), rideId = rideId.takeIf { !phoneOnly }, createdAt = now, updatedAt = now,
            title = sc.title, series = series?.name ?: s.series, episode = ep ?: s.episode, hookLine = sc.hookLine ?: sc.sections.firstOrNull()?.text.orEmpty(),
            postCaption = series?.let { SeriesText.caption(it, ep ?: 1, own) } ?: own, story = null, options = opts,
            musicUri = s.musicUri?.toString(), musicName = s.musicName,
            plan = plan, takes = emptyList(), tips = emptyList(), durationMs = plan.totalMs, idea = sc.title, script = sc,
            seriesId = series?.id,
        )
        val state = s.copy(title = sc.title, hookLine = project.hookLine, options = project.options, series = project.series, episode = project.episode)
        return MakeJob(project, renderInput(plan, state, cd, voice = null))
    }

    /** The suggestions already made into Reels (by their title). */
    fun madeIdeas(): Set<String> =
        c.reels.reels.value.filter { it.rideId == rideId.takeIf { !phoneOnly } && it.deletedAt == null }.mapNotNull { it.idea }.toSet()

    fun fallbackCaption(): String {
        val cd = card ?: return ""
        return "${cd.title} 🏍️\n${cd.subtitle}\n\n#motovlog #bikelife #motorcycle #keppomoto"
    }

    private fun rideCard(name: String, start: Long, distanceM: Double, durMs: Long, topMps: Double?, moments: Int): RideCard {
        val zone = ZoneId.systemDefault()
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(start).atZone(zone))
        // The ride's saved stats can cover only part of it (a ride that survived the app dying):
        // take whichever is bigger, the stats or what the samples show, so the card agrees with the badges.
        val fromSamples = StudioNumbers.summary(samples)
        val km = String.format(Locale.US, "%.1f", Units.distance(maxOf(distanceM, fromSamples.distanceM)))
        val min = (maxOf(durMs, fromSamples.movingMs) / 60_000).coerceAtLeast(1)
        val topKmh = maxOf((topMps ?: 0.0) * 3.6, fromSamples.topKmh)
        val pts = samples.filter { it.latitude != null && it.longitude != null }
        val step = (pts.size / 400 + 1).coerceAtLeast(1)
        val route = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }
        val top = (topMps ?: route.maxOfOrNull { it.speedMps ?: 0.0 } ?: 1.0).coerceAtLeast(1.0)
        val norm = if (route.size >= 2) {
            val lat0 = route.map { it.latitude!! }.average()
            val k = cos(Math.toRadians(lat0))
            val xs = route.map { it.longitude!! * k }
            val ys = route.map { -it.latitude!! }
            val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min()).coerceAtLeast(1e-9) / 2
            val cx = (xs.max() + xs.min()) / 2
            val cy = (ys.max() + ys.min()) / 2
            route.indices.map { i -> Triple(((xs[i] - cx) / span).toFloat(), ((ys[i] - cy) / span).toFloat(), ((route[i].speedMps ?: 0.0) / top).toFloat()) }
        } else {
            emptyList()
        }
        return RideCard(
            title = name,
            subtitle = "$day · $km ${Units.distanceUnit} · $min min",
            route = norm,
            stats = listOf(km to Units.distanceUnit, "${Units.fromKmh(topKmh)}" to "top ${Units.speedUnit}", "$min" to "minutes", "$moments" to "moments"),
        )
    }

    private companion object {
        /** Clips read per Gemini request. */
        const val BATCH = 8
        const val METERED_BATCH = 4
        const val MAX_AUDIO_BYTES = 14L * 1024 * 1024
    }
}
