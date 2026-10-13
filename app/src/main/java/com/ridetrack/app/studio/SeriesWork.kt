package com.ridetrack.app.studio

import com.ridetrack.app.AppContainer
import com.ridetrack.app.transcribe.FirebaseTranscriber
import com.ridetrack.app.transcribe.GeminiNet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Finding and making series episodes from a ride (after the ride, on its Studio page, or from a folder). */
object SeriesWork {
    /** What one series found in a ride: its episodes (scripts), or nothing that fits. */
    data class Found(val series: Series, val episodes: List<Script>)

    /**
     * Asks Gemini, once per active series and ride, for episodes from [engine]'s ride (one request
     * each); they're added to the ride's suggestions. [only] limits it to one series. Stops at
     * Gemini's free limit or without internet (the rest are tried another time).
     */
    suspend fun find(c: AppContainer, engine: StudioEngine, rideId: String, only: String? = null): List<Found> {
        if (!c.transcripts.available) return emptyList()
        val cd = engine.card ?: return emptyList()
        val todo = c.series.active().filter { (only == null || it.id == only) && (only != null || !c.series.tried(rideId, it.id)) }
        if (todo.isEmpty()) return emptyList()
        val fs = engine.footage()
        if (fs.isEmpty()) return emptyList()
        val out = ArrayList<Found>()
        for (s in todo) {
            val episodes = try {
                val perf = Posting.performance(c.reels.reels.value.filter { it.seriesId == s.id })
                var raw: String? = null
                val scripts = engine.gemini().scripts(
                    ScriptWriter.seriesPrompt(s, cd.title, cd.subtitle, fs, engine.style(), perf, c.styles.forSuggestions()), fs,
                    onAnswer = { raw = it },
                ) { r ->
                    // An empty list is Gemini saying nothing fits: not an error.
                    if (!Regex("\"pieces\"\\s*:\\s*\\[\\s*]").containsMatchIn(r)) c.errors.record("Studio series", "Gemini's answer for “${s.name}” couldn't be read", null, r.take(4_000))
                }
                c.series.markTried(rideId, s.id)
                if (raw == null) emptyList() else scripts.take(if (s.perRide == 0) 4 else s.perRide).map { SeriesText.asEpisode(it, s) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseTranscriber.Busy) {
                c.errors.warn("Studio series", "Gemini's free limit is used up: “${s.name}” is looked for next time", e)
                break
            } catch (e: Exception) {
                if (GeminiNet.isUnreachable(e)) { c.errors.warn("Studio series", "No internet: “${s.name}” is looked for next time", e); break }
                c.errors.record("Studio series", "Couldn't look for “${s.name}” in the ride", e)
                continue
            }
            out += Found(s, episodes)
        }
        val found = out.flatMap { it.episodes }
        if (found.isNotEmpty()) {
            // Added to the ride's suggestions, with titles of their own.
            val plan = engine.loadPlan().orEmpty()
            val titles = plan.map { it.title }.toMutableSet()
            val named = found.map { sc -> generateSequence(sc.title) { "$it ·" }.first { it !in titles }.also { titles += it }.let { sc.copy(title = it) } }
            engine.savePlan(plan + named)
            return out.map { f -> f.copy(episodes = named.filter { it.series == f.series.id }.filter { e -> f.episodes.any { it.sections == e.sections } }) }
        }
        return out
    }

    /** Makes [episodes] (titles from the ride's plan) in the background; how many were queued. */
    fun make(c: AppContainer, engine: StudioEngine, episodes: List<Script>, service: Boolean): Int {
        if (episodes.isEmpty()) return 0
        val s = StudioState(
            title = engine.card?.title.orEmpty(), options = c.studio.options,
            musicUri = c.studio.music?.first?.let(android.net.Uri::parse), musicName = c.studio.music?.second,
        )
        val made = engine.madeIdeas()
        val jobs = engine.pieces(episodes).filter { it.script.title !in made }.mapNotNull { engine.job(it, s) }
        c.reelMaker.enqueue(jobs, service)
        return jobs.size
    }

    /**
     * "Make next episode": the newest rides (last 3 weeks) [s] hasn't looked at yet, newest first,
     * until one has an episode; it's made. What to tell the rider.
     */
    suspend fun makeNext(c: AppContainer, s: Series): String {
        if (!c.transcripts.available) return "Gemini isn't in this build"
        val since = System.currentTimeMillis() - 21 * 86_400_000L
        val rides = c.rides.observeCompleted().first().filter { it.startTimeMillis >= since }.sortedByDescending { it.startTimeMillis }
        val todo = rides.filter { !c.series.tried(it.id, s.id) }
        if (todo.isEmpty()) return if (rides.isEmpty()) "No rides in the last 3 weeks" else "Every recent ride was looked at already. After your next ride Gemini looks again."
        for (r in todo.take(5)) {
            val engine = StudioEngine(c, r.id)
            if (engine.load() != null) { c.series.markTried(r.id, s.id); continue }
            val found = find(c, engine, r.id, only = s.id).firstOrNull() ?: return "Couldn't reach Gemini; try again later"
            if (found.episodes.isNotEmpty()) {
                val n = make(c, engine, found.episodes, service = true)
                return "Found ${found.episodes.size} in \u201c${engine.card?.title ?: r.name}\u201d: making $n. They'll be here when they're done."
            }
        }
        return "Nothing in your recent rides fits \u201c${s.name}\u201d yet."
    }

    /**
     * "Use its look for the series": [p]'s look becomes the series' style, and every unposted
     * episode is made again in it. How many are made again.
     */
    suspend fun useLook(c: AppContainer, p: ReelProject): Int {
        val s = c.series.get(p.seriesId) ?: return 0
        val st = Styles.fromReel(p.plan, p.options, s.styleId?.takeIf { id -> c.styles.get(id)?.builtIn == false } ?: c.styles.newId(), "${s.name} look")
        c.styles.save(st)
        c.series.save(s.copy(styleId = st.id))
        val others = c.reels.reels.value.filter { it.seriesId == s.id && it.deletedAt == null && !it.posted && it.id != p.id }
        var n = 0
        val again = others.map { o ->
            o.copy(plan = Styles.apply(o.plan, st) { "lk${n++}" }, options = Styles.options(o.options, st), updatedAt = System.currentTimeMillis())
        }
        return c.reelMaker.remake(again)
    }

    /** "Make these a series": a new series from [reels] (their format, look and length), each an episode in the order made. */
    suspend fun fromReels(c: AppContainer, reels: List<ReelProject>, name: String): Series {
        val sorted = reels.sortedBy { it.createdAt }
        val sc = sorted.firstNotNullOfOrNull { it.script }
        val format = sc?.format ?: PieceFormat.REEL
        val secs = sorted.map { (it.durationMs / 1000).toInt() }
        val s = Series(
            id = c.series.newId(), name = name, brief = SeriesText.briefFrom(sorted), format = format,
            minSec = (secs.minOrNull() ?: format.minSec).coerceIn(format.minSec, format.maxSec),
            maxSec = (secs.maxOrNull() ?: format.maxSec).coerceIn(format.minSec, format.maxSec),
            styleId = sc?.style, nextEpisode = sorted.size + 1, createdAt = System.currentTimeMillis(),
        )
        c.series.save(s)
        sorted.forEachIndexed { i, p -> c.reels.update(p.id) { it.copy(seriesId = s.id, series = s.name, episode = i + 1) } }
        return s
    }
}
