package com.ridetrack.app.moments

import android.util.Log
import com.ridetrack.telemetry.moments.momentLogLine
import java.io.File

/**
 * Per-ride diagnostics for Moments (files/moments/<rideId>/moments-log.txt). Lines are
 * buffered and appended on [flush]; also mirrored to logcat. Thread-safe.
 */
class MomentLog(private val file: File) {
    private val pending = StringBuilder()
    private var flushedAt = 0L

    @Synchronized
    fun log(message: String) {
        Log.i(TAG, message)
        val now = System.currentTimeMillis()
        pending.append(momentLogLine(now, message)).append('\n')
        // Written out every few seconds, so a process that dies loses at most that much.
        if (now - flushedAt >= FLUSH_EVERY_MILLIS) flush()
    }

    fun error(message: String, e: Throwable? = null) =
        log("ERROR $message" + (e?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    @Synchronized
    fun flush() {
        if (pending.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(pending.toString())
            pending.setLength(0)
            flushedAt = System.currentTimeMillis()
        }
    }

    companion object {
        private const val TAG = "Moments"
        const val FILE_NAME = "moments-log.txt"
        private const val FLUSH_EVERY_MILLIS = 3_000L

        /** Adds a line to a ride's log from outside a ride (e.g. why the app last stopped). */
        fun append(file: File, message: String) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(momentLogLine(System.currentTimeMillis(), message) + "\n")
            }
        }

        /** "Moments: 0 clips · 4 photos · last issue: …", for the ride page in debug builds. */
        fun summary(file: File): String? {
            if (!file.exists()) return null
            val lines = runCatching { file.readLines() }.getOrNull() ?: return null
            val clips = lines.count { "clip saved" in it }
            val photos = lines.count { "photo saved" in it }
            val issue = lines.lastOrNull { "ERROR" in it || "skipped" in it || "paused:" in it || "watchdog" in it }
                ?.substringAfter("Z  ")
            return "Moments: $clips clips · $photos photos" + (issue?.let { " · last issue: $it" } ?: "")
        }
    }
}
