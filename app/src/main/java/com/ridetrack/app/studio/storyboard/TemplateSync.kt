package com.ridetrack.app.studio.storyboard

import com.ridetrack.app.studio.ReelProject

/** Whether a project's template still matches its board. Pure. */
object TemplateSync {
    /**
     * What the board changed since [template] was made from it, in a line ("The board changed
     * since this template: 2 changes, 1 new line to record"); null when they match.
     */
    fun behind(chat: StoryboardChat, template: ReelProject): String? {
        val board = chat.script ?: return null
        val made = template.script ?: return null
        val changes = StoryboardEngine.diff(made, board)
        if (changes.isEmpty()) return null
        val before = VoiceLines.lines(made).toMap()
        val lines = VoiceLines.lines(board).count { (id, line) -> before[id] != line }
        return "The board changed since this template: ${changes.size} change${if (changes.size > 1) "s" else ""}" +
            (if (lines > 0) ", $lines line${if (lines > 1) "s" else ""} to record again" else "") + ". Footage already in kept shots stays."
    }
}
