package com.ridetrack.app.studio

import android.content.Context
import com.ridetrack.app.AppContainer
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One clip as the theme finder sees it: what was said and what Gemini saw in it. */
data class ClipInfo(val momentId: String, val rideId: String, val startMillis: Long, val durationMs: Long, val lines: List<CaptionLine>, val seen: List<String>)

/** A part of a clip for a compilation: [inMs]–[outMs] of [momentId]. */
data class ThemeHit(val momentId: String, val rideId: String, val inMs: Long, val outMs: Long, val atMillis: Long)

/** Something that keeps coming back across rides: a thing seen ("police") or a phrase said ("bhai kya kar raha hai"). */
data class Theme(val label: String, val said: Boolean, val hits: List<ThemeHit>) {
    val rides: Int get() = hits.map { it.rideId }.distinct().size
    val lengthMs: Long get() = hits.sumOf { it.outMs - it.inMs }
    /** "Delhi traffic police, 6 times · 0:48". */
    val title: String get() = if (said) "Every “$label”" else "${label.replaceFirstChar { it.uppercase() }}, ${hits.size} times"
}

/**
 * Themes across rides, for compilations: things seen in at least [least] clips, and phrases said
 * in at least [least] clips, each from 2 rides or more. Most clips first. Pure, unit-tested.
 */
object Themes {
    /** Words that say nothing on their own, in English and Hinglish. */
    private val PLAIN = setOf(
        "the", "a", "an", "and", "is", "it", "to", "of", "in", "on", "i", "you", "we", "this", "that", "so", "ok", "okay", "yes", "no",
        "hai", "ha", "hain", "ki", "ka", "ke", "ko", "se", "me", "mein", "toh", "bhi", "aur", "ye", "yeh", "wo", "woh", "na", "nahi", "kya", "hum", "main", "tha", "thi",
        "road", "bike", "ride", "riding", "motorcycle", "rider", "helmet", "street", "day", "sky", "building", "buildings", "tree", "trees", "car", "cars",
    )
    private const val SEEN_MS = 4_000L
    private const val MAX_HITS = 10

    fun find(clips: List<ClipInfo>, least: Int = 4): List<Theme> {
        val out = ArrayList<Theme>()
        // Seen: one hit per clip, its middle 4 s.
        clips.flatMap { c -> c.seen.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it !in PLAIN }.distinct().map { it to c } }
            .groupBy({ it.first }, { it.second })
            .forEach { (word, cs) ->
                if (cs.size < least || cs.map { it.rideId }.distinct().size < 2) return@forEach
                val hits = cs.sortedBy { it.startMillis }.map { c ->
                    val len = minOf(SEEN_MS, c.durationMs)
                    val a = (c.durationMs - len) / 2
                    ThemeHit(c.momentId, c.rideId, a, a + len, c.startMillis + a)
                }
                out += Theme(word, said = false, hits = hits.takeLast(MAX_HITS))
            }
        // Said: two or three words in a row, said in different clips; the line it's in, with a breath around.
        val phrases = HashMap<String, MutableList<Pair<ClipInfo, CaptionLine>>>()
        clips.forEach { c ->
            c.lines.forEach { l ->
                val w = ClipSearch.words(l.text)
                val grams = (2..3).flatMap { n -> w.windowed(n).filter { g -> g.count { it !in PLAIN } >= 1 && g.none { it.length < 2 } }.map { it.joinToString(" ") } }.distinct()
                grams.forEach { g -> phrases.getOrPut(g) { ArrayList() } += c to l }
            }
        }
        phrases.forEach { (g, uses) ->
            val byClip = uses.distinctBy { it.first.momentId }
            if (byClip.size < least || byClip.map { it.first.rideId }.distinct().size < 2) return@forEach
            val hits = byClip.sortedBy { it.first.startMillis + it.second.startMs }.map { (c, l) ->
                val a = (l.startMs - 300).coerceAtLeast(0)
                ThemeHit(c.momentId, c.rideId, a, (l.endMs + 400).coerceAtMost(c.durationMs), c.startMillis + a)
            }
            out += Theme(g, said = true, hits = hits.takeLast(MAX_HITS))
        }
        // A shorter phrase found in the same clips as a longer one is the same theme: keep the longer.
        val kept = out.filter { t ->
            !t.said || out.none { o -> o.said && o !== t && o.label.contains(t.label) && o.label.length > t.label.length && o.hits.size >= t.hits.size }
        }
        return kept.sortedWith(compareByDescending<Theme> { it.hits.size }.thenByDescending { it.rides }).take(6)
    }

    /** A compilation's script: the hits in time order, the first as the hook. */
    fun script(t: Theme): Script = Script(
        format = PieceFormat.REEL,
        title = t.title,
        why = "From ${t.rides} rides: ${t.hits.size} times",
        lengthSec = ((t.lengthMs + 999) / 1000).toInt().coerceIn(PieceFormat.REEL.minSec, PieceFormat.REEL.maxSec),
        shape = "list",
        hookLine = null,
        postCaption = "${t.title} 😅\n#motovlog #bikelife #ridersofindia",
        sections = t.hits.mapIndexed { i, h -> Section(if (i == 0) SectionKind.HOOK else SectionKind.PEAK, "moment", listOf(ScriptShot(h.momentId, h.inMs, h.outMs))) },
        vibe = Vibe.HYPE,
    )
}

/** Finding themes in the rider's clips and making a compilation of one. */
object Compilations {
    private fun prefs(context: Context) = context.getSharedPreferences("studio_compilations", Context.MODE_PRIVATE)

    fun dismissed(context: Context): Set<String> = prefs(context).getStringSet("dismissed", emptySet()).orEmpty()

    fun dismiss(context: Context, label: String) = prefs(context).edit().putStringSet("dismissed", dismissed(context) + label).apply()

    /** Themes in every clip on the phone that weren't dismissed or made already. Reads files: call off the main thread. */
    suspend fun themes(c: AppContainer): List<Theme> = withContext(Dispatchers.IO) {
        val clips = c.moments.all().filter { it.kind == MomentKind.CLIP && it.file.isFile && (it.durationMillis ?: 0) >= 1_500 }.map { m ->
            ClipInfo(m.id, m.rideId, m.videoStartMillis, m.durationMillis ?: 0, StudioText.load(m.file).orEmpty(), ClipSeen.load(m.file).orEmpty())
        }
        val made = c.reels.reels.value.filter { it.deletedAt == null }.map { it.title }.toSet()
        val gone = dismissed(c.appContext)
        Themes.find(clips).filter { it.label !in gone && it.title !in made }
    }

    /**
     * Makes [t] in the background: hosted by its newest ride, the other rides' clips borrowed.
     * False when the clips can't be found.
     */
    suspend fun make(c: AppContainer, t: Theme): Boolean {
        val host = t.hits.maxByOrNull { it.atMillis }?.rideId ?: return false
        val engine = StudioEngine(c, host)
        if (engine.load() != null) return false
        val all = c.moments.all().associateBy { it.id }
        val rides = c.rides.observeCompleted().first().associateBy { it.id }
        t.hits.map { it.momentId }.distinct().forEach { id ->
            val m: Moment = all[id] ?: return@forEach
            if (engine.clips.any { it.id == id }) return@forEach
            engine.borrowed[id] = m
            val dur = m.durationMillis ?: 0
            engine.extras[id] = Bit(
                id = "$id@0", momentId = id, clipDurationMs = dur, inMs = 0, outMs = dur, atMillis = m.videoStartMillis,
                lines = StudioText.load(m.file).orEmpty(), speedKmh = 0.0, punch = 5f,
                fromRide = rides[m.rideId]?.name, camera = m.camera,
            )
        }
        engine.refreshBits()
        val s = StudioState(title = t.title, options = c.studio.options, musicUri = c.studio.music?.first?.let(android.net.Uri::parse), musicName = c.studio.music?.second)
        val pc = engine.pieces(listOf(Themes.script(t))).firstOrNull() ?: return false
        val job = engine.job(pc, s) ?: return false
        c.reelMaker.enqueue(listOf(job))
        return true
    }
}
