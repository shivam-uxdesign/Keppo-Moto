package com.ridetrack.app.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService

/**
 * Freezes ("Keppo Moto isn't responding") aren't crashes, so nothing caught them. Two ways in:
 *  - a watchdog: the screen's thread is poked every second; when it hasn't answered for 4 s
 *    (Android asks "Close app / Wait" at 5 s), what it was doing is written to the error log;
 *  - on the next start, Android's own record of a freeze or a native crash (with its trace) is
 *    added to the error log once.
 */
class FreezeWatch(private val context: Context, private val errors: ErrorLog) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var answeredAt = System.currentTimeMillis()

    fun start() {
        reportPastExits()
        Thread({
            var reported = false
            while (true) {
                val asked = System.currentTimeMillis()
                main.post { answeredAt = System.currentTimeMillis() }
                runCatching { Thread.sleep(POKE_MS) }.onFailure { return@Thread }
                val stuck = System.currentTimeMillis() - answeredAt
                if (stuck >= FROZEN_MS && answeredAt < asked) {
                    if (!reported) {
                        reported = true
                        val trace = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "  at $it" }
                        runCatching { errors.record("Freeze", "The app froze for ${stuck / 1000} s (the screen wasn't answering)", extra = "Main thread:\n$trace") }
                    }
                } else {
                    reported = false
                }
            }
        }, "freeze-watch").apply { isDaemon = true; start() }
    }

    /** Android's record of how the app last ended, when it was a freeze or a native crash. */
    private fun reportPastExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService<ActivityManager>() ?: return
        val prefs = context.getSharedPreferences("freeze_watch", Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 10) }.getOrNull().orEmpty()
        exits.filter { it.timestamp > seen && (it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) }
            .sortedBy { it.timestamp }
            .forEach { e ->
                val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                val kind = if (e.reason == ApplicationExitInfo.REASON_ANR) "stopped responding" else "crashed (native)"
                runCatching {
                    errors.record(
                        if (e.reason == ApplicationExitInfo.REASON_ANR) "Freeze" else "Crash",
                        "The app $kind at ${java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))}: ${e.description ?: ""}".trim(),
                        extra = trace?.let(::mainThreadPart),
                    )
                }
            }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(SEEN, maxOf(it, seen)).apply() }
    }

    private companion object {
        const val POKE_MS = 1_000L
        const val FROZEN_MS = 4_000L
        const val SEEN = "seen"

        /** The main thread's part of an ANR trace (the whole trace is long), or its start. */
        fun mainThreadPart(trace: String): String {
            val at = trace.indexOf("\"main\"")
            if (at < 0) return trace.take(8_000)
            val end = trace.indexOf("\n\n", at).takeIf { it > at } ?: (at + 8_000)
            return trace.substring(at, minOf(end, trace.length, at + 8_000))
        }
    }
}
