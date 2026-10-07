package com.ridetrack.app.studio

/** Kinds of Reel one ride can give. */
enum class IdeaKind { STORY, HIGHLIGHTS, HOOK, SPEED_RUN, BLOOPERS }

/**
 * One Reel a ride could make: [plan] is ready to render, [options] are the choices it was planned
 * with (the rider can still change them). [story] indexes Gemini's stories for [IdeaKind.STORY].
 */
data class ReelIdea(
    val kind: IdeaKind,
    val title: String,
    val blurb: String,
    val options: StudioOptions,
    val plan: StudioPlan,
    val story: Int? = null,
    /** The bits picked first, in the planner's sense of a story. */
    val pick: List<String> = emptyList(),
) {
    val key: String get() = kind.name + (story ?: "")
    val opening: ClipSegment? get() = plan.clips.firstOrNull()
}

/** Several Reels from one ride. Pure, so it's unit-tested. Clips may be reused across ideas. */
object ReelIdeas {
    private val OOPS = Regex(
        "\\b(oops|arre|areh|oh no|shit|damn|sorry|haha+|lol|gir|wait wait|kya hua|abe|oh god|whoa|wtf|fuck\\w*|bc|yaar nahi)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Bits with Gemini's scores applied; the hook ranks top when there's no story. */
    fun scored(bits: List<Bit>, d: Direction?, story: Boolean): List<Bit> =
        bits.map { b -> b.copy(punch = if (b.id == d?.hookId && !story) 10f else d?.punch?.get(b.id) ?: b.punch) }

    /**
     * Plans an idea of [kind] with options [o] (the rider may have changed them since): [pick] are
     * its bits (a story's, the hook's with the best line first, the fastest, the bloopers).
     */
    fun planFor(kind: IdeaKind, pick: List<String>, bits: List<Bit>, d: Direction?, o: StudioOptions): StudioPlan = when (kind) {
        IdeaKind.STORY -> StudioPlanner.plan(scored(bits, d, pick.isNotEmpty()), o, pick, d?.endingId)
        IdeaKind.HIGHLIGHTS -> StudioPlanner.plan(scored(bits, d, false), o, emptyList(), d?.endingId)
        IdeaKind.HOOK -> StudioPlanner.plan(scored(bits, d, false).filter { it.id in pick }.map { if (it.id == pick.first()) it.copy(punch = 20f) else it }, o)
        IdeaKind.SPEED_RUN -> StudioPlanner.plan(scored(bits, d, true).map { b -> if (b.id in pick) b.copy(punch = (b.speedKmh / 10).toFloat()) else b }, o, pick)
        IdeaKind.BLOOPERS -> StudioPlanner.plan(bits, o, pick)
    }

    /**
     * The ideas for these [bits]: each of Gemini's stories (or one "The story" waiting on Gemini when
     * [geminiPending]), Highlights, the 15-second hook, a speed run and bloopers when there are any.
     */
    fun ideas(bits: List<Bit>, d: Direction?, base: StudioOptions, geminiPending: Boolean = false): List<ReelIdea> {
        if (bits.size < 2) return emptyList()
        val out = ArrayList<ReelIdea>()
        fun add(kind: IdeaKind, title: String, blurb: (StudioPlan) -> String, o: StudioOptions, pick: List<String>, story: Int? = null, min: Int = 2) {
            val plan = planFor(kind, pick, bits, d, o)
            if (plan.clips.size >= min) out += ReelIdea(kind, title, blurb(plan), o, plan, story, pick)
        }
        val stories = d?.stories.orEmpty()
        stories.forEachIndexed { i, st ->
            add(IdeaKind.STORY, st.name.replaceFirstChar { it.uppercase() }, { "One idea, told by ${it.clips.size} clips" }, base.copy(teaser = true, lengthSec = 30), st.ids, i)
        }
        if (stories.isEmpty() && geminiPending) {
            add(IdeaKind.STORY, "The story", { "Gemini finds what this ride was about" }, base.copy(teaser = true, lengthSec = 30), emptyList(), 0)
        }
        add(IdeaKind.HIGHLIGHTS, "Highlights", { "The best of the ride" }, base.copy(teaser = true, lengthSec = 30), emptyList())
        val s = scored(bits, d, false)
        s.filter { it.talking }.maxByOrNull { it.punch }?.let { best ->
            val before = s.filter { it.atMillis + 500 < best.atMillis }.maxByOrNull { it.atMillis }
            val after = s.filter { it.atMillis > best.atMillis + 500 }.minByOrNull { it.atMillis }
            add(
                IdeaKind.HOOK, "The 15-second hook", { "Your best line, made to loop" },
                base.copy(lengthSec = 15, intro = false, outro = false, loopEnd = true, teaser = false), listOfNotNull(best, before, after).map { it.id }, min = 1,
            )
        }
        val fast = bits.filter { it.speedKmh >= 25 }.sortedByDescending { it.speedKmh }.take(6)
        if (fast.size >= 2) {
            add(
                IdeaKind.SPEED_RUN, "Speed run", { "Top ${fast.first().speedKmh.toInt()} km/h, the fastest moments" },
                base.copy(vibe = Vibe.HYPE, lengthSec = if (fast.size >= 4) 30 else 15, outro = true, teaser = false), fast.map { it.id },
            )
        }
        val oops = bits.filter { b -> b.lines.any { OOPS.containsMatchIn(it.text) } }
        if (oops.isNotEmpty()) {
            add(
                IdeaKind.BLOOPERS, "Bloopers", { "${oops.size} moment${if (oops.size > 1) "s" else ""} that didn't go to plan" },
                base.copy(vibe = Vibe.VLOG, lengthSec = if (oops.size >= 3) 30 else 15, teaser = false), oops.map { it.id }, min = 1,
            )
        }
        return out
    }
}
