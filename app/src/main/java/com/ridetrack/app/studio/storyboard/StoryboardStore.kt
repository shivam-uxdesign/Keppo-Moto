package com.ridetrack.app.studio.storyboard

import android.content.Context
import com.ridetrack.app.studio.ScriptJson
import com.ridetrack.app.studio.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Storyboard's memory on this phone, in one small file (studio-storyboard.json, backed up):
 * every chat with its messages and script versions, the rider's gear, and how many chats were
 * started this month (free riders get [StoryboardEngine.FREE_CHATS_A_MONTH]).
 */
class StoryboardStore(private val context: Context) {
    private val file = File(context.filesDir, FILE)
    private val _chats = MutableStateFlow<List<StoryboardChat>>(emptyList())
    /** Newest first. */
    val chats: StateFlow<List<StoryboardChat>> = _chats.asStateFlow()
    private val _gear = MutableStateFlow(Gear())
    val gear: StateFlow<Gear> = _gear.asStateFlow()
    private var month = ""
    private var started = 0

    init { load() }

    fun get(id: String): StoryboardChat? = _chats.value.firstOrNull { it.id == id }

    fun newChat(): StoryboardChat {
        val now = System.currentTimeMillis()
        val c = StoryboardChat(UUID.randomUUID().toString().take(12), "New storyboard", now, now)
        countStart()
        _chats.value = listOf(c) + _chats.value
        save()
        return c
    }

    fun update(id: String, f: (StoryboardChat) -> StoryboardChat): StoryboardChat? {
        var out: StoryboardChat? = null
        _chats.value = _chats.value.map { if (it.id == id) f(it).copy(updatedAt = System.currentTimeMillis()).also { n -> out = n } else it }.sortedByDescending { it.updatedAt }
        save()
        return out
    }

    fun delete(id: String) {
        _chats.value = _chats.value.filter { it.id != id }
        save()
    }

    fun setGear(g: Gear) {
        _gear.value = g
        save()
    }

    /** Chats started this calendar month. */
    fun startedThisMonth(): Int = if (month == thisMonth()) started else 0

    private fun countStart() {
        val m = thisMonth()
        if (month != m) { month = m; started = 0 }
        started++
    }

    private fun thisMonth() = LocalDate.now().let { "${it.year}-${it.monthValue}" }

    /** Reads the file again (after a restore). */
    fun reload() { load() }

    private fun load() = runCatching {
        if (!file.isFile) return@runCatching
        val o = JSONObject(file.readText())
        _gear.value = readGear(o.optJSONObject("gear"))
        month = o.optString("month")
        started = o.optInt("started")
        _chats.value = o.optJSONArray("chats")?.let { a -> (0 until a.length()).mapNotNull { readChat(a.optJSONObject(it)) } }.orEmpty().sortedByDescending { it.updatedAt }
    }

    private fun save() {
        runCatching { file.writeAtomically(write(_chats.value, _gear.value, month, started)) }
    }

    companion object {
        const val FILE = "studio-storyboard.json"

        fun write(chats: List<StoryboardChat>, gear: Gear, month: String, started: Int): String = JSONObject()
            .put("v", 1).put("month", month).put("started", started)
            .put("gear", JSONObject().put("helper", gear.helper).put("asked", gear.asked).put("items", JSONArray().apply {
                gear.items.forEach { put(JSONObject().put("kind", it.kind).put("name", it.name).put("detail", it.detail).put("source", it.source)) }
            }))
            .put("chats", JSONArray().apply { chats.forEach { put(writeChat(it)) } })
            .toString()

        fun writeChat(c: StoryboardChat): JSONObject = JSONObject()
            .put("id", c.id).put("title", c.title).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
            .put("templates", JSONArray(c.templateIds))
            .put("raw", JSONArray(c.raw.takeLast(20)))
            .put("versions", JSONArray().apply { c.versions.forEach { put(ScriptJson.write(it)) } })
            .put("current", c.current)
            .put("clips", c.clips?.name ?: JSONObject.NULL)
            .put("messages", JSONArray().apply {
                c.messages.forEach { m ->
                    put(JSONObject().put("rider", m.fromRider).put("text", m.text).put("at", m.at)
                        .put("voiceMs", m.voiceMs ?: JSONObject.NULL).put("question", m.question ?: JSONObject.NULL)
                        .put("choices", JSONArray(m.choices)).put("version", m.version ?: JSONObject.NULL).put("template", m.templateId ?: JSONObject.NULL)
                        .put("changes", JSONArray(m.changes))
                        .put("chips", JSONArray().apply {
                            m.chips.forEach { ch ->
                                put(JSONObject().put("text", ch.text).put("removed", ch.removed).put("undone", ch.undone)
                                    .put("item", ch.undo?.let { JSONObject().put("kind", it.kind).put("name", it.name).put("detail", it.detail) } ?: JSONObject.NULL))
                            }
                        }))
                }
            })

        fun readChat(o: JSONObject?): StoryboardChat? = o?.let {
            runCatching {
                StoryboardChat(
                    id = o.getString("id"), title = o.optString("title", "Storyboard"), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
                    templateIds = o.optJSONArray("templates").strings(),
                    raw = o.optJSONArray("raw").strings(),
                    versions = o.optJSONArray("versions")?.let { a -> (0 until a.length()).mapNotNull { ScriptJson.read(a.optJSONObject(it)) } }.orEmpty(),
                    current = o.optInt("current", -1),
                    clips = if (o.isNull("clips")) null else ClipSource.of(o.optString("clips")),
                    messages = o.optJSONArray("messages")?.let { a ->
                        (0 until a.length()).mapNotNull { i ->
                            val m = a.optJSONObject(i) ?: return@mapNotNull null
                            ChatMessage(
                                fromRider = m.optBoolean("rider"), text = m.optString("text"), at = m.optLong("at"),
                                voiceMs = if (m.isNull("voiceMs")) null else m.optLong("voiceMs"),
                                question = if (m.isNull("question")) null else m.optString("question").ifBlank { null },
                                choices = m.optJSONArray("choices").strings(),
                                version = if (m.isNull("version")) null else m.optInt("version"),
                                templateId = if (m.isNull("template")) null else m.optString("template").ifBlank { null },
                                changes = m.optJSONArray("changes").strings(),
                                chips = m.optJSONArray("chips")?.let { ca ->
                                    (0 until ca.length()).mapNotNull { k ->
                                        val ch = ca.optJSONObject(k) ?: return@mapNotNull null
                                        MemoryChip(ch.optString("text"), ch.optJSONObject("item")?.let { x -> GearItem(x.optString("kind"), x.optString("name"), x.optString("detail")) }, ch.optBoolean("removed"), ch.optBoolean("undone"))
                                    }
                                }.orEmpty(),
                            )
                        }
                    }.orEmpty(),
                )
            }.getOrNull()
        }

        fun readGear(o: JSONObject?): Gear = if (o == null) Gear() else Gear(
            items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { x -> GearItem(x.optString("kind"), x.optString("name"), x.optString("detail"), x.optString("source", "chat")) } }
            }.orEmpty(),
            helper = o.optBoolean("helper"), asked = o.optBoolean("asked"),
        )

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
    }
}
