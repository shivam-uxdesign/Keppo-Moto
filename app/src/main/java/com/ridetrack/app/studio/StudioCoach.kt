package com.ridetrack.app.studio

import kotlin.math.roundToInt

/**
 * "Make the next one better": describes a finished Reel for Gemini's tips, and has the app's own
 * tips for when Gemini isn't there. Pure, so it's unit-tested.
 */
object StudioCoach {

    /** A plain description of the Reel and what was left out. */
    fun summary(plan: StudioPlan, o: StudioOptions, all: List<Bit>, d: Direction?, story: Story?, voiceOver: Boolean, music: Boolean): String = buildString {
        appendLine("Vibe ${plan.vibe.label}, ${sec(plan.totalMs)} long (chosen length ${o.lengthSec} s), ${plan.clips.size} clips.")
        appendLine(if (o.intro) "Opens with a 2 s route-and-title card before the first clip." else "Opens straight on the first clip, with the title as a small label.")
        d?.hookLine?.let { appendLine("Hook line on the first frame: \"$it\".") } ?: appendLine("No hook line on the first frame.")
        story?.let { appendLine("Story: ${it.name}.") }
        appendLine(if (d?.endingId != null && plan.clips.lastOrNull()?.bit?.id == d.endingId) "Ends on the rider's sign-off." else "No sign-off line; ends on the stats card.")
        appendLine("Loops back to the start: ${if (o.loopEnd) "yes" else "no"}. Voice-over: ${if (voiceOver) "yes" else "no"}. Music: ${if (music) "the rider's song" else "none"}.")
        appendLine("Clips in order:")
        plan.clips.forEachIndexed { i, s ->
            val said = s.lines.joinToString(" ") { it.text }.ifBlank { "(no talking, riding sound)" }
            appendLine("${i + 1}. ${sec(s.durMs)}, ${s.bit.speedKmh.roundToInt()} km/h${s.bit.fromRide?.let { ", from another ride ($it)" } ?: ""}: \"$said\"")
        }
        val used = plan.clips.map { it.bit.id }.toSet()
        val left = all.filter { it.id !in used && it.talking }.sortedByDescending { it.punch }.take(5)
        if (left.isNotEmpty()) {
            appendLine("Good lines not used:")
            left.forEach { b -> appendLine("- \"${b.lines.joinToString(" ") { it.text }}\"") }
        }
        val silent = all.count { !it.talking }
        appendLine("The ride had ${all.size} usable parts; $silent of them have no talking (wind, engine or traffic only).")
    }

    /** The app's own tips: the most useful 3–4 that apply. */
    fun localTips(plan: StudioPlan, o: StudioOptions, all: List<Bit>, d: Direction?, voiceOver: Boolean): List<Tip> {
        val tips = ArrayList<Tip>()
        if (o.intro) tips += Tip("Most viewers decide in the first 3 seconds. Open on your best line and keep the title as a small label.", TipAction.TITLE_ON_HOOK)
        if (plan.totalMs > 25_000 && (d?.stories?.isNotEmpty() == true)) {
            tips += Tip("Short Reels get watched to the end and rewatched. Try a 15 s cut of \"${d.stories.first().name}\".", TipAction.SHORTER)
        }
        val ending = d?.endingId != null && plan.clips.lastOrNull()?.bit?.id == d.endingId
        if (!ending) tips += Tip("There's no ending line. Say a sign-off as you finish the ride (\"aaj ka top: 68!\"); Studio will close on it.", nextRide = true)
        val silentUsed = plan.clips.count { it.lines.isEmpty() }
        if (silentUsed * 2 >= plan.clips.size && plan.clips.size >= 2) {
            tips += Tip("Half the clips have no talking. Say what you see or feel while filming, even a few words, so the captions carry it.", nextRide = true)
        }
        if (!voiceOver) tips += Tip("Set the scene with 5 seconds of voice-over at the start: where you were going and why.", TipAction.VOICE_OVER)
        tips += Tip("Film 3 seconds of the road ahead and one of the bike next ride, so the Reel can cut between angles.", nextRide = true)
        if (all.count { it.talking } < 2) tips += Tip("Add a good line from another ride to give this one a voice.", TipAction.OTHER_RIDES)
        return tips.take(4)
    }

    private fun sec(ms: Long) = "${(ms / 100) / 10.0} s"
}
