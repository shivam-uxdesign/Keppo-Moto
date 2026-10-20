package com.ridetrack.app.studio.storyboard

import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.studio.Section
import com.ridetrack.app.studio.SectionKind
import com.ridetrack.app.studio.StyleContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One message in a Storyboard chat. */
data class ChatMessage(
    val fromRider: Boolean,
    val text: String,
    val at: Long,
    /** Sent as a voice note (shown with its length). */
    val voiceMs: Long? = null,
    /** Memory changes Studio made from this reply ("Added: tripod"), each with Undo. */
    val chips: List<MemoryChip> = emptyList(),
    /** Studio's one question, with tap-to-answer choices. */
    val question: String? = null,
    val choices: List<String> = emptyList(),
    /** The script version this reply made (1-based); null = no change. */
    val version: Int? = null,
    /** A template made at this point. */
    val templateId: String? = null,
)

/** A memory change shown as a chip: what it says, and what Undo puts back. */
data class MemoryChip(val text: String, val undo: GearItem?, val removed: Boolean, val undone: Boolean = false)

/** Something the rider films with. */
data class GearItem(
    /** CAMERA, MOUNT, AUDIO or EXTRA. */
    val kind: String,
    val name: String,
    val detail: String = "",
    /** "chat" (said in a chat) or "settings". */
    val source: String = "chat",
)

/** The rider's gear, shared across bikes. */
data class Gear(val items: List<GearItem> = emptyList(), val helper: Boolean = false, val asked: Boolean = false) {
    fun describe(): String = if (items.isEmpty()) "Not told yet: assume a phone (front and back cameras) on a handlebar mount." else
        items.joinToString("; ") { listOf(it.kind.lowercase(), it.name, it.detail).filter(String::isNotBlank).joinToString(": ") } +
            if (helper) "; a helper is available to film" else "; no helper: everything must be filmable solo"
}

/** A Storyboard chat: its messages, every script version, and the templates it made. */
data class StoryboardChat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val versions: List<Script> = emptyList(),
    val templateIds: List<String> = emptyList(),
    /** Gemini's raw answers, for Send details. */
    val raw: List<String> = emptyList(),
) {
    val script: Script? get() = versions.lastOrNull()
}

/** What Gemini answered: the chat reply, a full script or patches, memory changes, one question. */
data class StoryboardReply(
    val reply: String,
    val script: Script? = null,
    val patches: List<Patch> = emptyList(),
    val memory: List<MemoryUpdate> = emptyList(),
    val styleSuggestion: String? = null,
    val question: String? = null,
    val choices: List<String> = emptyList(),
    /** Locked fields the rider asked in so many words to change ("s3.action"). */
    val unlock: Set<String> = emptySet(),
    /** Asked to save the change as a new template (true) or update this one (false); null = not said. */
    val newVersion: Boolean? = null,
)

/** A change to one shot: update some fields, insert a new shot after one, or delete it. */
data class Patch(val op: String, val id: String?, val after: String? = null, val section: SectionKind? = null, val fields: JSONObject = JSONObject())

data class MemoryUpdate(val op: String, val item: GearItem, val confirm: String)

/**
 * The Storyboard chat's talk with Gemini: the prompt (gear, style, the script so far, the chat),
 * reading and checking the JSON answer, and applying it (patches never touch fields the rider
 * edited by hand). Pure, unit-tested with recorded answers.
 */
object StoryboardEngine {
    const val FREE_CHATS_A_MONTH = 3
    private const val HISTORY = 10

    fun prompt(chat: StoryboardChat, gear: Gear, style: StyleContext?, text: String, firstChat: Boolean): String = buildString {
        appendLine("You are Keppo Studio's storyboard writer for a motorcycle rider's Instagram Reels and Stories.")
        appendLine("The rider describes a Reel idea; you turn it into a storyboard script: sections, and for each shot exactly what to film.")
        appendLine("RULES:")
        appendLine("- Only plan shots the rider's gear can capture. If the ideal gear isn't owned, plan the closest alternative and fill \"fallback\" (ideal, reason, using). Never silently drop a shot.")
        appendLine("- Every shot must be filmable solo unless the gear says a helper is available.")
        appendLine("- Shots filmed while riding (\"riding\": true) use fixed mounts, never handheld.")
        appendLine("- Be specific enough that two people would film nearly the same shot: who, what moves, from where, what must be in frame.")
        appendLine("- Fill gaps sensibly (\"Step one: the ___\" becomes a fitting name) and say so in the reply.")
        appendLine("- The rider's style below is the default; what they ask for in this chat wins (without changing the style).")
        appendLine("- Keep the reply short and friendly. Ask at most ONE question, only if it really matters, with up to 4 choices.")
        appendLine("- On-screen text can be anything (titles, step names, jokes): list it in \"texts\", each with the shot it starts on.")
        appendLine("- Story pieces may have a voice-over line per shot in \"say\", in the rider's own language (Hinglish in English letters, the way people text it).")
        appendLine("- Never change a field marked LOCKED unless the rider asks for that exact change; then list it in \"unlock\" (\"s3.action\").")
        appendLine("- Mentioned gear changes (\"I bought a GoPro\", \"no tripod any more\") go in \"memory\", each with a short confirm text.")
        appendLine()
        appendLine("RIDER'S GEAR: ${gear.describe()}")
        if (firstChat && !gear.asked) appendLine("This is the rider's first chat: if they haven't said their gear, ask about cameras, mounts and audio in one friendly question; meanwhile assume phone + handlebar mount.")
        style?.let { s ->
            if (s.rules.isNotEmpty()) appendLine("RIDER'S STYLE RULES: " + s.rules.joinToString("; "))
            s.examples.takeIf { it.isNotEmpty() }?.let { ex -> appendLine("RECENT EDITS THE RIDER MADE: " + ex.joinToString(" | ") { "\"${it.before}\" → \"${it.after}\"" + (it.note?.let { n -> " ($n)" } ?: "") }) }
        }
        chat.script?.let { s ->
            appendLine()
            appendLine("THE SCRIPT SO FAR (change it with \"patches\" only; keep approved parts as they are):")
            appendLine(describe(s))
        }
        val recent = chat.messages.takeLast(HISTORY)
        if (recent.isNotEmpty()) {
            appendLine()
            appendLine("THE CHAT SO FAR:")
            recent.forEach { appendLine((if (it.fromRider) "Rider: " else "Studio: ") + it.text.replace('\n', ' ')) }
        }
        appendLine()
        appendLine("RIDER NOW: $text")
        appendLine()
        appendLine(
            "Reply with JSON only: {\"reply\":\"...\",\"script\":SCRIPT or null,\"patches\":[...],\"memory\":[...],\"styleSuggestion\":null,\"needsClarification\":null,\"unlock\":[],\"newVersion\":null}. " +
                "SCRIPT (only when there's no script yet, or the rider asks for a new one): {\"title\":\"...\",\"format\":\"reel|story|short\",\"musicNote\":\"...\"," +
                "\"sections\":[{\"kind\":\"hook|setup|build|peak|turn|payoff|ending\",\"text\":\"on-screen text at its start or null\",\"shots\":[SHOT]}],\"texts\":[{\"text\":\"...\",\"shot\":\"s1\",\"sec\":2}]}. " +
                "SHOT: {\"id\":\"s1\",\"action\":\"...\",\"framing\":\"extreme close-up|close-up|medium|wide\",\"angle\":\"...\",\"movement\":\"...\",\"mustShow\":\"...\"," +
                "\"camera\":\"phone back|phone front|...\",\"mount\":\"handlebar|tripod at ...|handheld|...\",\"riding\":true,\"fallback\":{\"ideal\":\"...\",\"reason\":\"...\",\"using\":\"...\"} or null," +
                "\"sec\":1.5,\"say\":\"voice-over line or null\",\"audio\":\"engine|music|voice\",\"transition\":\"cut|whip|fade|null\"," +
                "\"match\":{\"events\":[\"strong acceleration\"],\"words\":[\"tunnel\"],\"minSec\":1,\"engine\":true,\"mustRecord\":false}}. " +
                "PATCH: {\"op\":\"update\",\"id\":\"s3\",\"fields\":{SHOT fields to change}} | {\"op\":\"insert\",\"after\":\"s3\",\"section\":\"build\",\"shot\":SHOT} | {\"op\":\"delete\",\"id\":\"s5\"}. " +
                "MEMORY: {\"op\":\"add|update|remove\",\"kind\":\"camera|mount|audio|extra|helper\",\"name\":\"tripod\",\"detail\":\"...\",\"confirm\":\"Added: tripod\"}. " +
                "needsClarification: {\"question\":\"...\",\"choices\":[\"...\"]}. newVersion: true when the rider asks for a new version or a different take, false when they ask to change this one.",
        )
    }

    /** The script as the prompt shows it, with LOCKED fields marked. */
    fun describe(s: Script): String = buildString {
        appendLine("\"${s.title}\" (${s.format.name.lowercase()}, ${s.totalMs / 1000} s)" + (s.musicNote?.let { " · music: $it" } ?: ""))
        s.sections.forEach { sec ->
            appendLine("[${sec.kind.name.lowercase()}]" + (sec.text?.let { " text: \"$it\"" } ?: ""))
            sec.shots.forEach { sh ->
                val b = sh.board
                if (b == null) appendLine("  ${sh.id ?: "-"}: filmed clip, ${sh.durMs / 1000.0} s (keep)")
                else appendLine("  ${sh.id}: ${b.action} | ${b.framing} | ${b.camera} on ${b.mount} | ${b.durationMs / 1000.0} s" +
                    (b.say?.let { " | say \"$it\"" } ?: "") + (if (sh.clip.isNotEmpty()) " | FILMED (keep)" else "") +
                    sh.locked.takeIf { it.isNotEmpty() }?.let { " | LOCKED: ${it.joinToString()}" }.orEmpty())
            }
        }
        if (s.texts.isNotEmpty()) appendLine("texts: " + s.texts.joinToString { "\"${it.text}\" on ${it.shotId}" })
    }

    /** Gemini's answer, checked; null when it isn't the expected JSON (the caller retries once). */
    fun parse(reply: String, current: Script?): StoryboardReply? = runCatching {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        val o = JSONObject(reply.substring(a, b + 1))
        val text = o.optString("reply").trim().takeIf { it.isNotEmpty() } ?: return null
        val script = o.optJSONObject("script")?.let { scriptOf(it) }
        if (o.has("script") && !o.isNull("script") && script == null) return null
        val patches = o.optJSONArray("patches")?.let { p -> (0 until p.length()).mapNotNull { patchOf(p.optJSONObject(it)) } }.orEmpty()
        // Patches to shots that don't exist are refused, not guessed.
        val ids = current?.sections?.flatMap { it.shots }?.mapNotNull { it.id }?.toSet().orEmpty()
        val okPatches = patches.filter { p -> when (p.op) { "insert" -> p.after == null || p.after in ids; else -> p.id in ids } }
        val q = o.optJSONObject("needsClarification")
        StoryboardReply(
            reply = text,
            script = script,
            patches = okPatches,
            memory = o.optJSONArray("memory")?.let { m -> (0 until m.length()).mapNotNull { memoryOf(m.optJSONObject(it)) } }.orEmpty(),
            styleSuggestion = o.optString("styleSuggestion").takeIf { !o.isNull("styleSuggestion") && it.isNotBlank() },
            question = q?.optString("question")?.takeIf { it.isNotBlank() },
            choices = q?.optJSONArray("choices")?.let { c -> (0 until c.length()).map { c.optString(it) }.filter { it.isNotBlank() }.take(4) }.orEmpty(),
            unlock = o.optJSONArray("unlock")?.let { u -> (0 until u.length()).map { u.optString(it) }.toSet() }.orEmpty(),
            newVersion = if (o.has("newVersion") && !o.isNull("newVersion")) o.optBoolean("newVersion") else null,
        )
    }.getOrNull()

    /** A script from Gemini's SCRIPT JSON: shots get ids, lengths are kept to 0.5–10 s and the format's length. */
    fun scriptOf(o: JSONObject): Script? {
        val format = when (o.optString("format").lowercase(Locale.US)) { "story" -> PieceFormat.STORY; "short" -> PieceFormat.SHORT; else -> PieceFormat.REEL }
        var n = 0
        val used = HashSet<String>()
        val sections = o.optJSONArray("sections")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val so = a.optJSONObject(i) ?: return@mapNotNull null
                val kind = SectionKind.of(so.optString("kind")) ?: SectionKind.PEAK
                val shots = so.optJSONArray("shots")?.let { sh ->
                    (0 until sh.length()).mapNotNull { j ->
                        val x = sh.optJSONObject(j) ?: return@mapNotNull null
                        val b = boardOf(x) ?: return@mapNotNull null
                        n++
                        val id = x.optString("id").takeIf { it.isNotBlank() && used.add(it) } ?: "s$n".also { used += it }
                        ScriptShot("", 0, b.durationMs, id = id, board = b)
                    }
                }.orEmpty()
                if (shots.isEmpty()) null else Section(kind, "storyboard", shots, so.optString("text").takeIf { !so.isNull("text") && it.isNotBlank() })
            }
        }.orEmpty()
        if (sections.isEmpty()) return null
        val texts = o.optJSONArray("texts")?.let { t ->
            (0 until t.length()).mapNotNull { i ->
                val x = t.optJSONObject(i) ?: return@mapNotNull null
                OnScreenText(x.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null, x.optString("shot"), (x.optDouble("sec", 2.0) * 1000).toLong().coerceIn(500, 10_000))
            }
        }.orEmpty()
        val s = Script(
            format = format, title = o.optString("title").ifBlank { "Storyboard" }, why = null, lengthSec = 0, shape = "storyboard",
            hookLine = null, postCaption = null, sections = sections, texts = texts,
            musicNote = o.optString("musicNote").takeIf { !o.isNull("musicNote") && it.isNotBlank() },
        )
        return fit(s)
    }

    /** Lengths kept within the format: when too long, shots not locked are shortened evenly. */
    fun fit(s: Script): Script {
        val max = s.format.maxSec * 1000L
        val total = s.totalMs
        val out = if (total <= max) s else {
            val free = s.sections.flatMap { it.shots }.filter { it.board != null && ShotFields.DURATION !in it.locked && it.clip.isEmpty() }.sumOf { it.durMs }
            val lockedMs = total - free
            val k = ((max - lockedMs).toDouble() / free.coerceAtLeast(1)).coerceIn(0.1, 1.0)
            s.copy(sections = s.sections.map { sec ->
                sec.copy(shots = sec.shots.map { sh ->
                    if (sh.board == null || ShotFields.DURATION in sh.locked || sh.clip.isNotEmpty()) sh
                    else {
                        val d = (sh.durMs * k).toLong().coerceAtLeast(StoryboardJson.MIN_MS)
                        sh.copy(outMs = sh.inMs + d, board = sh.board.copy(durationMs = d))
                    }
                })
            })
        }
        return out.copy(lengthSec = (out.totalMs / 1000).toInt().coerceAtLeast(1))
    }

    fun boardOf(x: JSONObject): Storyboard? {
        val action = x.optString("action").trim().takeIf { it.isNotEmpty() } ?: return null
        val m = x.optJSONObject("match")
        return Storyboard(
            action = action,
            framing = x.optString("framing", "medium").ifBlank { "medium" },
            angle = x.optString("angle"),
            movement = x.optString("movement"),
            mustShow = x.optString("mustShow"),
            camera = x.optString("camera", "phone back").ifBlank { "phone back" },
            mount = x.optString("mount", "handlebar").ifBlank { "handlebar" },
            riding = x.optBoolean("riding", true),
            fallback = x.optJSONObject("fallback")?.let { f -> GearFallback(f.optString("ideal"), f.optString("reason"), f.optString("using")).takeIf { it.ideal.isNotBlank() && it.using.isNotBlank() } },
            durationMs = (x.optDouble("sec", 2.0) * 1000).toLong().coerceIn(StoryboardJson.MIN_MS, StoryboardJson.MAX_MS),
            say = x.optString("say").takeIf { !x.isNull("say") && it.isNotBlank() && it != "null" },
            audio = x.optString("audio", "music").let { if (it in listOf("engine", "music", "voice")) it else "music" },
            transition = x.optString("transition").takeIf { !x.isNull("transition") && it.isNotBlank() && it != "null" },
            match = MatchHints(
                events = m?.optJSONArray("events")?.let { e -> (0 until e.length()).map { e.optString(it).lowercase(Locale.US) } }.orEmpty(),
                words = m?.optJSONArray("words")?.let { e -> (0 until e.length()).map { e.optString(it).lowercase(Locale.US) } }.orEmpty(),
                minMs = ((m?.optDouble("minSec", 0.0) ?: 0.0) * 1000).toLong(),
                engine = m?.optBoolean("engine", false) ?: false,
                mustRecord = m?.optBoolean("mustRecord", false) ?: false,
            ),
        )
    }

    private fun patchOf(o: JSONObject?): Patch? {
        o ?: return null
        val op = o.optString("op").lowercase(Locale.US).takeIf { it in listOf("update", "insert", "delete") } ?: return null
        return Patch(
            op, o.optString("id").takeIf { it.isNotBlank() }, o.optString("after").takeIf { it.isNotBlank() },
            SectionKind.of(o.optString("section")), o.optJSONObject("fields") ?: o.optJSONObject("shot") ?: JSONObject(),
        )
    }

    private fun memoryOf(o: JSONObject?): MemoryUpdate? {
        o ?: return null
        val op = o.optString("op").lowercase(Locale.US).takeIf { it in listOf("add", "update", "remove") } ?: return null
        val kind = o.optString("kind").uppercase(Locale.US).takeIf { it in listOf("CAMERA", "MOUNT", "AUDIO", "EXTRA", "HELPER") } ?: "EXTRA"
        val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        return MemoryUpdate(op, GearItem(kind, name, o.optString("detail")), o.optString("confirm").ifBlank { (if (op == "remove") "Removed: " else "Added: ") + name })
    }

    /**
     * The script after Gemini's patches. Locked fields stay unless [unlock] names them; filmed shots
     * keep their footage; a delete or insert that would leave nothing is ignored.
     */
    fun apply(s: Script, patches: List<Patch>, unlock: Set<String> = emptySet()): Script {
        var out = s
        patches.forEach { p ->
            out = when (p.op) {
                "update" -> out.copy(sections = out.sections.map { sec ->
                    sec.copy(shots = sec.shots.map { sh -> if (sh.id == p.id) update(sh, p.fields, unlock) else sh })
                })
                "delete" -> if (out.sections.sumOf { it.shots.size } <= 1) out else out.copy(
                    sections = out.sections.map { sec -> sec.copy(shots = sec.shots.filter { it.id != p.id }) }.filter { it.shots.isNotEmpty() },
                )
                "insert" -> {
                    val b = boardOf(p.fields) ?: return@forEach
                    val id = p.fields.optString("id").takeIf { it.isNotBlank() && Placeholders.find(out, it) == null } ?: Placeholders.newId(out)
                    val shot = ScriptShot("", 0, b.durationMs, id = id, board = b)
                    val at = out.sections.indexOfFirst { sec -> sec.shots.any { it.id == p.after } }
                    if (at >= 0 && (p.section == null || out.sections[at].kind == p.section)) {
                        out.copy(sections = out.sections.mapIndexed { k, sec ->
                            if (k != at) sec else sec.copy(shots = sec.shots.flatMap { if (it.id == p.after) listOf(it, shot) else listOf(it) })
                        })
                    } else {
                        val target = p.section ?: SectionKind.PEAK
                        val idx = out.sections.indexOfLast { it.kind == target }
                        if (idx >= 0) out.copy(sections = out.sections.mapIndexed { k, sec -> if (k == idx) sec.copy(shots = sec.shots + shot) else sec })
                        else out.copy(sections = out.sections + Section(target, "storyboard", listOf(shot)))
                    }
                }
                else -> out
            }
        }
        return fit(out)
    }

    private fun update(sh: ScriptShot, f: JSONObject, unlock: Set<String>): ScriptShot {
        val b = sh.board ?: return sh
        fun can(field: String) = field !in sh.locked || "${sh.id}.$field" in unlock
        var n = b
        if (f.has("action") && can(ShotFields.ACTION)) n = n.copy(action = f.optString("action").ifBlank { n.action })
        if (f.has("framing") && can(ShotFields.FRAMING)) n = n.copy(framing = f.optString("framing").ifBlank { n.framing })
        if (f.has("mount") && can(ShotFields.MOUNT)) n = n.copy(mount = f.optString("mount").ifBlank { n.mount })
        if (f.has("camera") && can(ShotFields.CAMERA)) n = n.copy(camera = f.optString("camera").ifBlank { n.camera })
        if (f.has("sec") && can(ShotFields.DURATION)) n = n.copy(durationMs = (f.optDouble("sec", n.durationMs / 1000.0) * 1000).toLong().coerceIn(StoryboardJson.MIN_MS, StoryboardJson.MAX_MS))
        if (f.has("say") && can(ShotFields.SAY)) n = n.copy(say = f.optString("say").takeIf { !f.isNull("say") && it.isNotBlank() })
        if (f.has("angle") && can("angle")) n = n.copy(angle = f.optString("angle"))
        if (f.has("movement") && can("movement")) n = n.copy(movement = f.optString("movement"))
        if (f.has("mustShow") && can("mustShow")) n = n.copy(mustShow = f.optString("mustShow"))
        if (f.has("audio") && can("audio")) n = n.copy(audio = f.optString("audio").let { if (it in listOf("engine", "music", "voice")) it else n.audio })
        if (f.has("riding") && can("riding")) n = n.copy(riding = f.optBoolean("riding", n.riding))
        if (f.has("transition") && can("transition")) n = n.copy(transition = f.optString("transition").takeIf { !f.isNull("transition") && it.isNotBlank() })
        if (f.has("fallback") && can("fallback")) n = n.copy(fallback = f.optJSONObject("fallback")?.let { x -> GearFallback(x.optString("ideal"), x.optString("reason"), x.optString("using")) })
        // A filmed shot keeps its footage and length.
        if (sh.clip.isNotEmpty()) n = n.copy(durationMs = b.durationMs)
        return sh.copy(board = n, outMs = if (sh.clip.isEmpty()) sh.inMs + n.durationMs else sh.outMs)
    }

    /** The gear after Gemini's memory updates, and the chips to show (each with what Undo restores). */
    fun remember(gear: Gear, updates: List<MemoryUpdate>): Pair<Gear, List<MemoryChip>> {
        var g = gear
        val chips = ArrayList<MemoryChip>()
        updates.forEach { u ->
            if (u.item.kind == "HELPER") {
                g = g.copy(helper = u.op != "remove")
                chips += MemoryChip(u.confirm, null, u.op == "remove")
                return@forEach
            }
            val same = g.items.firstOrNull { it.name.equals(u.item.name, ignoreCase = true) }
            g = when (u.op) {
                "remove" -> g.copy(items = g.items.filter { it != same })
                else -> g.copy(items = g.items.filter { it != same } + u.item)
            }
            chips += MemoryChip(u.confirm, if (u.op == "remove") same else u.item, u.op == "remove")
        }
        return g.copy(asked = true) to chips
    }

    /** Undo a chip: put back what was removed, or take away what was added. */
    fun undo(gear: Gear, chip: MemoryChip): Gear {
        val item = chip.undo ?: return gear
        return if (chip.removed) gear.copy(items = gear.items + item) else gear.copy(items = gear.items.filter { !it.name.equals(item.name, ignoreCase = true) })
    }

    /** The chat's title from its first message ("How to enter a tunnel 101"). */
    fun titleOf(first: String): String {
        val t = Regex("(?i)title is ([^.!?\\n]{3,60})").find(first)?.groupValues?.get(1)
            ?: first.split(Regex("[.!?\\n]")).firstOrNull { it.isNotBlank() }.orEmpty()
        return t.trim().take(48).replaceFirstChar { it.uppercase() }.ifBlank { "New storyboard" }
    }
}
