package com.ridetrack.app.studio

import android.content.Context
import java.io.File

/**
 * Deletes Studio's own files that no edit uses any more: still frames ("Freeze"), reversed clips
 * and voice-over recordings. Edits point to them by name from their JSON (Reels and their versions,
 * drafts, ride plans), so a file whose name is in none of them, and that is a few days old (an edit
 * open right now may not be saved yet), is left over from a discarded edit.
 */
object StudioSweeper {
    const val MIN_AGE_MS = 3L * 24 * 3600 * 1000
    private const val EVERY_MS = 24L * 3600 * 1000
    private const val PREFS = "studio-sweeper"
    private const val KEY_LAST = "last"
    /** JSON larger than this isn't an edit (ride exports and the like); skipped. */
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024

    /** Runs at most once a day. Returns how many files were deleted. */
    fun sweepDaily(context: Context, now: Long = System.currentTimeMillis()): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - prefs.getLong(KEY_LAST, 0) < EVERY_MS) return 0
        val n = sweep(context.filesDir, now)
        prefs.edit().putLong(KEY_LAST, now).apply()
        return n
    }

    fun sweep(filesDir: File, now: Long): Int {
        val candidates = candidates(filesDir).filter { now - it.lastModified() >= MIN_AGE_MS }
        if (candidates.isEmpty()) return 0
        val unused = candidates.associateBy { it.name }.toMutableMap()
        filesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") && it.length() <= MAX_JSON_BYTES }.forEach { f ->
            if (unused.isEmpty()) return@forEach
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            unused.keys.removeAll { it in text }
        }
        return unused.values.count { it.delete() }
    }

    /** The files Studio makes for edits. */
    internal fun candidates(filesDir: File): List<File> =
        File(filesDir, "studio-stills").listFiles().orEmpty().filter { it.isFile } +
            File(filesDir, "studio-reversed").listFiles().orEmpty().filter { it.isFile } +
            File(filesDir, "studio").listFiles().orEmpty().filter { it.isFile && it.name.startsWith("voice-") && it.name.endsWith(".pcm") }
}
