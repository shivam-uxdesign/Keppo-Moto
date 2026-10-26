package com.ridetrack.app.studio.storyboard

/**
 * Where a template's shots get footage from. Asked every time shots would be filled (Make
 * template), unless the rider already said it in the chat.
 */
enum class ClipSource(val label: String, val detail: String) {
    /** Only footage filmed after the template was made (for this Reel); older clips never show. */
    FRESH("Fresh footage only", "Film every shot new. Your older clips won't be used or shown."),
    /** Nothing goes in by itself; the closest clips from your rides are offered on each shot. */
    SUGGEST("Suggest from my rides", "Shots stay empty. Each one offers its closest clips to pick from."),
    /** The strongest matches from your rides go in at once, badged until you keep them. */
    AUTO("Fill with my best clips", "Shots that match a clip well are filled now. Keep or change each one."),
    ;

    /** Whether a clip filmed at [clipMillis] may be used in a template made at [madeMillis]. */
    fun allows(clipMillis: Long, madeMillis: Long): Boolean = this != FRESH || clipMillis >= madeMillis

    /** Whether shots are filled without the rider picking (AUTO; FRESH only with footage filmed for it). */
    val fillsByItself: Boolean get() = this != SUGGEST

    companion object {
        fun of(name: String?): ClipSource? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}
