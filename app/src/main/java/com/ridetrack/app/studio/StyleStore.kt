package com.ridetrack.app.studio

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One change the rider made to a script, kept to learn their style. */
data class StyleEntry(val atMillis: Long, val before: String, val after: String, val note: String?)

/**
 * How the rider wants stories written: their rules (from their notes, editable) and every change
 * they made to a script (before, after, note). The latest few go with each script request.
 */
class StyleStore(context: Context) {
    private val file = File(context.filesDir, "studio-style.json")
    private val _rules = MutableStateFlow<List<String>>(emptyList())
    private val _log = MutableStateFlow<List<StyleEntry>>(emptyList())
    val rules: StateFlow<List<String>> = _rules.asStateFlow()
    val log: StateFlow<List<StyleEntry>> = _log.asStateFlow()

    init { load() }

    /** What goes with a script request: all rules, and the last [n] changes. */
    fun context(n: Int = 4): StyleContext? {
        val r = _rules.value
        val ex = _log.value.takeLast(n).map { StyleExample(it.before, it.after, it.note) }
        return if (r.isEmpty() && ex.isEmpty()) null else StyleContext(r, ex)
    }

    /** A change to a script; a note also becomes a rule (unless it's already one). */
    fun record(before: String, after: String, note: String?) {
        if (before == after && note.isNullOrBlank()) return
        _log.value = (_log.value + StyleEntry(System.currentTimeMillis(), before, after, note?.trim()?.takeIf { it.isNotEmpty() })).takeLast(MAX_LOG)
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> if (_rules.value.none { it.equals(n, ignoreCase = true) }) _rules.value = (_rules.value + n).takeLast(MAX_RULES) }
        save()
    }

    fun setRules(list: List<String>) {
        _rules.value = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_RULES)
        save()
    }

    /** The whole log as text, to send to the developer. */
    fun export(): String = buildString {
        appendLine("Keppo Studio · your style")
        appendLine()
        appendLine("Rules:")
        _rules.value.forEach { appendLine("- $it") }
        appendLine()
        _log.value.forEach { e ->
            appendLine(java.time.Instant.ofEpochMilli(e.atMillis).toString())
            appendLine("Before: ${e.before}")
            appendLine("After:  ${e.after}")
            e.note?.let { appendLine("Note:   $it") }
            appendLine()
        }
    }

    fun share(context: Context) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Keppo Studio style log").putExtra(Intent.EXTRA_TEXT, export())
        context.startActivity(Intent.createChooser(send, "Send your style log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun load() = runCatching {
        val o = JSONObject(file.readText())
        _rules.value = o.optJSONArray("rules")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        _log.value = o.optJSONArray("log")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { e -> StyleEntry(e.getLong("at"), e.getString("before"), e.getString("after"), e.optString("note").takeIf { it.isNotEmpty() }) } }
        }.orEmpty()
    }

    private fun save() {
        val o = JSONObject()
            .put("rules", JSONArray(_rules.value))
            .put("log", JSONArray().apply { _log.value.forEach { put(JSONObject().put("at", it.atMillis).put("before", it.before).put("after", it.after).put("note", it.note ?: "")) } })
        file.writeText(o.toString())
    }

    private companion object {
        const val MAX_LOG = 200
        const val MAX_RULES = 20
    }
}

/** A script in one line, as a person reads it: "Hook (sound, 0.8 s) “Tooooo” → Peak (take, 10 s) “Paani nahi…” → Ending (loop)". Pure. */
fun Script.describe(footage: List<Footage>): String {
    val byId = footage.associateBy { it.momentId }
    return "${format.label}, ${shape.replace('_', ' ')}: " + sections.joinToString(" → ") { s ->
        val secs = s.shots.sumOf { it.durMs } / 1000.0
        val said = s.shots.flatMap { sh -> byId[sh.clip]?.lines.orEmpty().filter { it.endMs > sh.inMs && it.startMs < sh.outMs } }.joinToString(" ") { it.text }
        buildString {
            append("${s.kind.label} (${s.form}")
            if (secs > 0) append(", ${"%.1f".format(java.util.Locale.US, secs)} s")
            append(")")
            if (said.isNotBlank()) append(" “${said.take(60)}”")
            s.text?.let { append(" [text: $it]") }
        }
    }
}
