package com.ridetrack.app.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ridetrack.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One failure, with everything needed to find its cause. */
data class ErrorEntry(
    val timeMillis: Long,
    val area: String,
    val summary: String,
    val details: String,
    /** It worked another way (a fallback): worth knowing, not a failure. */
    val warning: Boolean = false,
) {
    /** The full text, as sent to the developer. */
    fun text(): String = "[${ErrorLog.stamp(timeMillis)}] ${if (warning) "Warning · " else ""}$area: $summary\n$details"
}

/**
 * Every failure the app reports (Studio, Gemini, transcripts), kept on the phone with its full
 * technical detail (all causes, stack traces, app build, phone) so it can be copied or shared.
 * The newest 50 are kept, in files/errors.log.
 */
class ErrorLog(private val context: Context) {
    private val file = File(context.filesDir, "errors.log")
    private val _entries = MutableStateFlow(read())
    val entries: StateFlow<List<ErrorEntry>> = _entries.asStateFlow()
    private val prefs = context.getSharedPreferences("error_log", Context.MODE_PRIVATE)
    private val _seenAt = MutableStateFlow(prefs.getLong("seen_at", 0L))
    /** When the rider last opened the log: entries after it are new. */
    val seenAt: StateFlow<Long> = _seenAt.asStateFlow()

    fun markSeen() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("seen_at", now).apply()
        _seenAt.value = now
    }

    /** Something worked another way (a fallback): kept like an error, marked as a warning. */
    fun warn(area: String, summary: String, e: Throwable? = null, extra: String? = null): ErrorEntry = record(area, summary, e, extra, warning = true)

    /** Records [e] (with every cause and stack trace) under [area]; returns the entry. */
    fun record(area: String, summary: String, e: Throwable? = null, extra: String? = null, warning: Boolean = false): ErrorEntry {
        val details = buildString {
            extra?.let { appendLine(it) }
            e?.let { appendLine(stackOf(it)) }
        }.trim()
        val entry = ErrorEntry(System.currentTimeMillis(), area, summary.take(500), details, warning)
        Log.w("ErrorLog", "$area: $summary", e)
        synchronized(this) {
            val list = (listOf(entry) + _entries.value).take(MAX)
            _entries.value = list
            runCatching { write(list) }
        }
        return entry
    }

    fun clear() = synchronized(this) {
        _entries.value = emptyList()
        file.delete()
    }

    /** Everything, newest first, with the app build and phone at the top. */
    fun report(entries: List<ErrorEntry> = _entries.value): String = buildString {
        appendLine("Keppo Moto ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TAG}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        entries.forEach { appendLine(it.text()); appendLine("----") }
    }

    /** Opens the share sheet with the report as text (WhatsApp, email, notes…). */
    fun share(entries: List<ErrorEntry> = _entries.value) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Keppo Moto error report")
            .putExtra(Intent.EXTRA_TEXT, report(entries).take(MAX_SHARE_CHARS))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(send, "Send error report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Opens the share sheet with [body] under the app build and phone line. */
    fun shareText(subject: String, body: String) {
        val text = buildString {
            appendLine("Keppo Moto ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TAG}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(body)
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text.take(MAX_SHARE_CHARS))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun copy(entries: List<ErrorEntry> = _entries.value): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("Keppo Moto error report", report(entries).take(MAX_SHARE_CHARS)))
        return true
    }

    private fun write(list: List<ErrorEntry>) {
        file.writeText(list.joinToString(SEP) { "${it.timeMillis}\n${if (it.warning) WARN else ""}${it.area}\n${it.summary.replace('\n', ' ')}\n${it.details}" })
    }

    private fun read(): List<ErrorEntry> = runCatching {
        if (!file.isFile) return emptyList()
        file.readText().split(SEP).mapNotNull { block ->
            val parts = block.split('\n', limit = 4)
            if (parts.size < 3) return@mapNotNull null
            val warning = parts[1].startsWith(WARN)
            ErrorEntry(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[1].removePrefix(WARN), parts[2], parts.getOrElse(3) { "" }, warning)
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX = 50
        private const val MAX_SHARE_CHARS = 90_000
        private const val SEP = "\n\u001E\n"
        /** Marks a warning's area in the file. */
        private const val WARN = "\u0007"
        private val FMT = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", Locale.US)

        fun stamp(millis: Long): String = FMT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        /** The throwable and all its causes, with stack traces. */
        fun stackOf(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
    }
}
