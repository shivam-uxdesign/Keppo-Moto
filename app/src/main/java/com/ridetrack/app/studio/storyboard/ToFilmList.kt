package com.ridetrack.app.studio.storyboard

import com.ridetrack.app.AppContainer

/**
 * Unfilled Storyboard shots on the shot list (Home's card and the ride pop-up's first 30 s),
 * grouped by where the phone goes: "Tripod at the tunnel exit: shots 03, 11 · Tunnel 101".
 * Done when the slot is filled (the line goes away at the next sync).
 */
object ToFilmList {
    /** The shot-list lines for every template's unfilled shots. Pure apart from what it reads. */
    fun lines(templates: List<Pair<String, com.ridetrack.app.studio.Script>>, unfilled: Map<String, Set<String>>, templateIds: List<String>): List<String> =
        templateIds.zip(templates).flatMap { (id, pair) ->
            val (title, s) = pair
            val open = unfilled[id].orEmpty()
            val shots = Placeholders.shots(s).filter { it.second.id in open }.map { it.second.id!! to it.second.board!! }
            ToFilm.groups(shots).map { g -> "${g.where}: shot${if (g.shots.size > 1) "s" else ""} ${g.shots.joinToString(", ") { Placeholders.number(s, it.first) }} · $title" }
        }

    fun sync(c: AppContainer) {
        val tpl = c.reels.reels.value.filter { it.template != null && it.deletedAt == null && it.script != null }
        val ids = tpl.map { it.id }
        c.studio.setStoryboardShots(lines(tpl.map { it.title to it.script!! }, tpl.associate { it.id to TemplateBuilder.placeholders(it.plan).toSet() }, ids))
    }
}
