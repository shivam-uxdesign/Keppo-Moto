package com.ridetrack.app.pro

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The rider agreed that their clips' sound, some frames and ride details may go to Google's
 * Gemini (Studio, captions, the petrol price). Nothing is sent to Gemini without it.
 */
class AiConsent(context: Context) {
    private val prefs = context.getSharedPreferences("ai_consent", Context.MODE_PRIVATE)
    private val _given = MutableStateFlow(prefs.getBoolean(GIVEN, false))
    val given: StateFlow<Boolean> = _given.asStateFlow()
    val isGiven: Boolean get() = _given.value

    fun set(on: Boolean) {
        prefs.edit().putBoolean(GIVEN, on).apply()
        _given.value = on
    }

    private companion object {
        const val GIVEN = "given"
    }
}

/**
 * Reports of a Gemini result the rider found offensive, harmful or wrong (Play's rule for apps
 * that make AI content: report it without leaving the app). Kept on the phone, and sent once the
 * app's server is there.
 */
class AiReports(context: Context) {
    private val file = File(context.filesDir, "ai-reports.json")

    enum class Reason(val label: String) { OFFENSIVE("Offensive or harmful"), WRONG("Wrong or made up"), OTHER("Something else") }

    @Synchronized
    fun add(what: String, text: String, reason: Reason, note: String) {
        val list = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        list.put(JSONObject().put("at", System.currentTimeMillis()).put("what", what).put("text", text.take(2_000)).put("reason", reason.name).put("note", note.take(1_000)))
        runCatching { file.writeText(list.toString()) }
    }

    /** How many are waiting to be sent. */
    fun count(): Int = runCatching { JSONArray(file.readText()).length() }.getOrDefault(0)
}
