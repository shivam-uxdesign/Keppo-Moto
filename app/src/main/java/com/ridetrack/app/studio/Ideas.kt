package com.ridetrack.app.studio

/** One card on a ride's Studio page: the idea shown first, and other takes on the same moment. */
data class IdeaGroup<T>(val main: T, val variations: List<T>) {
    val all: List<T> get() = listOf(main) + variations
}

/**
 * Puts ideas that tell the same moment on one card, so the page has no duplicates. Pure, unit-tested.
 * Two ideas are the same moment when they open on the same clip, or share most of their clips, or
 * one was asked for as another take on the other. The first in the list (Gemini's best first) leads
 * its card. A long reel uses most of the ride, so it always has a card of its own.
 */
object IdeaGroups {
    /** How much of their clips two ideas must share (of all the clips either uses). */
    private const val SHARED = 0.6

    fun <T> group(items: List<T>, script: (T) -> Script): List<IdeaGroup<T>> {
        val groups = ArrayList<Pair<T, MutableList<T>>>()
        for (it in items) {
            val sc = script(it)
            // Another take goes on the card of the idea it was asked from, wherever that one is.
            val home = sc.variationOf?.let { t -> groups.firstOrNull { (main, v) -> script(main).title == t || v.any { x -> script(x).title == t } } }
                ?: groups.firstOrNull { (main, _) -> same(script(main), sc) }
            if (home != null) home.second += it else groups += it to ArrayList()
        }
        return groups.map { (m, v) -> IdeaGroup(m, v) }
    }

    fun same(main: Script, other: Script): Boolean {
        if (other.variationOf == main.title) return true
        if (main.format == PieceFormat.LONG || other.format == PieceFormat.LONG) return false
        val a = main.clips
        val b = other.clips
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.first() == b.first()) return true
        val shared = a.count { it in b }
        return shared.toDouble() / (a + b).distinct().size >= SHARED
    }
}

/**
 * What's made by itself after each ride: a long reel, the best moment and your story, each from a
 * different card. Pure, unit-tested.
 */
object TopSlots {
    enum class Slot(val label: String) { LONG("Long reel"), MOMENT("Best moment"), STORY("Your story") }

    /** A caption clip, or a short or story of at most this long, can be the best moment. */
    private const val MOMENT_MS = 30_000L

    /**
     * Up to one card per slot, best first (the order of [groups]); [talks] says whether a script
     * uses the rider's words. With none of the slots filled, the top card alone.
     */
    fun <T> pick(groups: List<IdeaGroup<T>>, script: (T) -> Script, talks: (Script) -> Boolean): List<Pair<Slot, T>> {
        val left = groups.map { it.main }.toMutableList()
        val out = ArrayList<Pair<Slot, T>>()
        fun take(slot: Slot, ok: (Script) -> Boolean) {
            left.firstOrNull { ok(script(it)) }?.let { left.remove(it); out += slot to it }
        }
        take(Slot.LONG) { it.format == PieceFormat.LONG }
        take(Slot.MOMENT) { it.format == PieceFormat.CAPTION || (it.format in setOf(PieceFormat.SHORT, PieceFormat.STORY) && it.totalMs <= MOMENT_MS) }
        take(Slot.STORY) { it.format != PieceFormat.LONG && it.format != PieceFormat.CAPTION && talks(it) }
        if (out.isEmpty()) groups.firstOrNull()?.let { out += Slot.STORY to it.main }
        return out
    }
}
