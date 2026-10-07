package com.ridetrack.app.studio

/** One place a search found: a line said in a clip (or something seen in it), with where it is. */
data class SearchHit(
    val momentId: String,
    val rideId: String,
    val rideName: String,
    val atMillis: Long,
    /** The line, ms from the clip's start; for something seen, the whole clip. */
    val line: CaptionLine,
    /** True when it matched what Gemini saw in the clip rather than what was said. */
    val seen: Boolean = false,
)

/** Forgiving search over what was said (Hinglish in English letters) and what was seen. Pure, unit-tested. */
object ClipSearch {
    /** "Paaani!" → "pani": lower case, letters only, doubled letters once. */
    fun normal(word: String): String {
        val letters = word.lowercase().filter { it.isLetterOrDigit() }
        val sb = StringBuilder()
        letters.forEach { if (sb.isEmpty() || sb.last() != it) sb.append(it) }
        return sb.toString()
    }

    fun words(text: String): List<String> = text.split(Regex("[^\\p{L}\\p{N}']+")).map(::normal).filter { it.isNotEmpty() }

    /** Every word of [query] is in [text] (as a word or the start of one). */
    fun matches(query: String, text: String): Boolean {
        val q = words(query)
        if (q.isEmpty()) return false
        val t = words(text)
        return q.all { w -> t.any { it.startsWith(w) } }
    }

    /**
     * Searches [lines] (each clip's lines) and [seen] (each clip's seen words); best first: lines
     * that match whole words, then the rest, newest first.
     */
    fun search(
        query: String,
        lines: Map<String, List<CaptionLine>>,
        seen: Map<String, List<String>>,
        meta: (String) -> Triple<String, String, Long>?,
    ): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        lines.forEach { (id, ls) ->
            val m = meta(id) ?: return@forEach
            ls.filter { matches(query, it.text) }.forEach { out += SearchHit(id, m.first, m.second, m.third + it.startMs, it) }
        }
        seen.forEach { (id, tags) ->
            val m = meta(id) ?: return@forEach
            if (matches(query, tags.joinToString(" "))) out += SearchHit(id, m.first, m.second, m.third, CaptionLine(0, 0, "Seen: " + tags.take(6).joinToString(", ")), seen = true)
        }
        val q = words(query)
        return out.sortedWith(compareByDescending<SearchHit> { h -> !h.seen && q.all { w -> words(h.line.text).contains(w) } }.thenByDescending { it.atMillis })
    }

    /** What Gemini is asked about a few frames of each clip. */
    fun seenPrompt(n: Int): String =
        "These are frames from $n motorcycle ride clips, two frames per clip, in order (clip 1 first). For each clip, list up to 8 short words for what can be seen " +
            "(places, weather, vehicles, animals, landmarks, road, people, food, signs), lower case English. " +
            "Reply with JSON only: {\"clips\":[[\"rain\",\"flyover\",\"truck\"],[...]]} with exactly $n lists."

    fun parseSeen(reply: String, n: Int): List<List<String>>? = runCatching {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        val arr = org.json.JSONObject(reply.substring(a, b + 1)).getJSONArray("clips")
        (0 until n).map { i -> arr.optJSONArray(i)?.let { x -> (0 until x.length()).map { x.getString(it).trim().lowercase() }.filter { it.isNotEmpty() }.take(8) } ?: emptyList() }
    }.getOrNull()
}
