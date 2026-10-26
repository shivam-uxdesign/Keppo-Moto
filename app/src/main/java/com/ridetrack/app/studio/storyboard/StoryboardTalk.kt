package com.ridetrack.app.studio.storyboard

import com.ridetrack.app.AppContainer
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.StudioGemini
import com.ridetrack.app.transcribe.GeminiNet
import kotlinx.coroutines.launch

/**
 * One turn of a Storyboard chat: the rider's message goes to Gemini with the gear, style, script
 * and chat so far; the answer is checked (one retry with the problem named), then applied:
 * a first script or patches, memory chips, one question. Nothing goes without the rider's OK to
 * use Gemini; free riders get a few chats a month.
 */
class StoryboardTalk(private val c: AppContainer) {
    private val gemini = StudioGemini(preferred = { c.transcripts.model }, onWorking = { c.transcripts.model = it })

    /** Why a chat can't be sent now; null when it can. */
    fun blocked(chat: StoryboardChat?): String? = when {
        !c.transcripts.available -> "Storyboard needs Gemini, which isn't set up in this build."
        !c.aiConsent.isGiven -> "Allow Studio to use Gemini first (Studio › Settings)."
        !c.entitlements.isPro && chat == null && c.storyboard.startedThisMonth() >= StoryboardEngine.FREE_CHATS_A_MONTH ->
            "You've used this month's ${StoryboardEngine.FREE_CHATS_A_MONTH} free storyboards. Pro has unlimited ones."
        else -> null
    }

    /** One turn's outcome: the chat after it, the version it started from, and which shots changed. */
    data class Turn(val chat: StoryboardChat, val fromIndex: Int, val changed: Set<String>, val changes: List<String>)

    /**
     * Sends [text] in [chatId]; returns the turn, or throws with a message for the rider. [scope]:
     * the shots the rider selected (the app keeps every other shot as it was); [ownScript]: [text]
     * is the rider's script, checked to come back word for word; [ride]: the ride to build from.
     */
    suspend fun send(chatId: String, text: String, voiceMs: Long? = null, scope: List<String> = emptyList(), ownScript: Boolean = false, ride: String? = null): Turn {
        val store = c.storyboard
        val before = store.get(chatId) ?: error("That chat is gone")
        val first = before.messages.none { it.fromRider }
        var chat = store.update(chatId) { ch ->
            ch.copy(
                title = if (first) (if (ownScript) StoryboardEngine.scriptLines(text).firstOrNull()?.let { StoryboardEngine.titleOf(it) } ?: ch.title else StoryboardEngine.titleOf(text)) else ch.title,
                messages = ch.messages + ChatMessage(true, if (ownScript) "My script:\n$text" else text, System.currentTimeMillis(), voiceMs),
            )
        }!!
        val prompt = StoryboardEngine.prompt(before, store.gear.value, null, text, firstChat = store.chats.value.size <= 1, scope = scope, ownScript = ownScript, ride = ride)
        val base = before.script
        var raw = ""
        var reply: StoryboardReply? = null
        var next: Script? = null
        var changes = emptyList<String>()
        var problem: String? = null
        for (attempt in 0..2) {
            raw = try {
                gemini.storyboard(if (problem == null) prompt else "$prompt\n\n$problem")
            } catch (e: Exception) {
                c.errors.record("Storyboard", "Couldn't reach Gemini", e)
                store.update(chatId) { it.copy(messages = it.messages + ChatMessage(false, if (GeminiNet.isUnreachable(e)) GeminiNet.message(e) else "Couldn't reach Gemini just now. Try again in a moment.", System.currentTimeMillis())) }
                throw e
            }
            val r = StoryboardEngine.parse(raw, base)
            if (r == null) {
                problem = "Your last answer couldn't be read as the JSON asked for. Answer again with JSON only, exactly in the shape given."
                if (attempt >= 1) break else continue
            }
            reply = r
            // A rider's own script replaces the board; their selection keeps every other shot as it was.
            val proposed = StoryboardEngine.next(base, if (ownScript && r.script != null) r.copy(fresh = true) else r)
            next = if (proposed != null && base != null && scope.isNotEmpty() && !ownScript) StoryboardEngine.scoped(base, proposed, scope) else proposed
            changes = if (base != null && next != null) StoryboardEngine.diff(base, next) else emptyList()
            // Their own script must come back word for word: name the lines that didn't, once.
            val missing = if (ownScript && next != null) StoryboardEngine.missingLines(text, next) else emptyList()
            if (missing.isNotEmpty() && attempt < 2) {
                problem = "These lines of the rider's script are missing or changed: " + missing.joinToString(" | ") { "\"$it\"" } +
                    ". Put every line back exactly as written (in a shot's say, or on-screen text for TEXT: lines) and answer again."
                c.errors.warn("Storyboard", "Gemini changed the rider's own script; asking again", null)
                continue
            }
            // Said it changed the storyboard, but nothing did: ask once more, naming the problem.
            if (base != null && r.claimed.isNotEmpty() && changes.isEmpty() && attempt < 2) {
                problem = "You said you changed: ${r.claimed.joinToString("; ")}. But the script you returned " +
                    (if (r.script == null) "was missing" else "is exactly the same as the current one") +
                    ". Return the WHOLE updated script with those changes really made (keep the ids of the shots you keep)."
                c.errors.warn("Storyboard", "Gemini claimed changes that weren't in its script; asking again", null)
                continue
            }
            break
        }
        val r = reply ?: run {
            c.errors.record("Storyboard", "Couldn't read Gemini's answer", extra = raw.take(8_000))
            val ch = store.update(chatId) { it.copy(raw = it.raw + raw, messages = it.messages + ChatMessage(false, "Couldn't read Gemini's answer. Try again.", System.currentTimeMillis())) }!!
            return Turn(ch, before.currentIndex, emptySet(), emptyList())
        }
        val changed = next != null && (base == null || changes.isNotEmpty())
        val honest = if (base != null && r.claimed.isNotEmpty() && !changed)
            "I couldn't make that change to the storyboard, so it's the same as before. Try saying it another way, like \"remove shot 3\"."
        else r.reply
        val (gear, gearChips) = StoryboardEngine.remember(store.gear.value, r.memory)
        val newPins = r.pins.filter { p -> chat.pins.none { it.equals(p, ignoreCase = true) } }
        // Which footage to use and rules for this Reel, said in so many words: kept, shown as chips.
        val chips = gearChips + listOfNotNull(r.clips?.let { MemoryChip("Clips: ${it.label}", null, false) }) + newPins.map { MemoryChip("Pinned: $it", null, false) }
        if (r.memory.isNotEmpty() || !store.gear.value.asked) store.setGear(gear)
        chat = store.update(chatId) { ch ->
            val n = (if (changed) ch.withVersion(next!!) else ch).let { if (r.clips != null) it.copy(clips = r.clips) else it }
            n.copy(
                raw = ch.raw + raw,
                pins = ch.pins + newPins,
                messages = ch.messages + ChatMessage(
                    false, honest + (r.styleSuggestion?.let { "\n\n$it" } ?: ""), System.currentTimeMillis(),
                    chips = chips, question = r.question, choices = r.choices,
                    version = if (changed) n.versions.size else null,
                    changes = if (changed) changes else emptyList(),
                ),
            )
        }!!
        return Turn(chat, before.currentIndex, if (changed) StoryboardEngine.changedIds(base, next!!) else emptySet(), if (changed) changes else emptyList())
    }

    companion object {
        /**
         * A turn that keeps going while screens change (started from the projects list): the
         * project shows "Keppo is writing…" until it's done. [rideId]: build from that ride.
         */
        fun start(c: AppContainer, chatId: String, text: String, ownScript: Boolean = false, rideId: String? = null) {
            c.storyboard.setWorking(chatId, true)
            c.appScope.launch {
                try {
                    val ride = rideId?.let { RideBrief.of(c, it) }
                    runCatching { StoryboardTalk(c).send(chatId, text, null, emptyList(), ownScript, ride) }
                } finally {
                    c.storyboard.setWorking(chatId, false)
                }
            }
        }
    }

    /** Undoes a memory chip in [chatId]'s message [at]. */
    fun undo(chatId: String, at: Long, chip: MemoryChip) {
        c.storyboard.setGear(StoryboardEngine.undo(c.storyboard.gear.value, chip))
        c.storyboard.update(chatId) { ch ->
            ch.copy(messages = ch.messages.map { m -> if (m.at == at) m.copy(chips = m.chips.map { if (it == chip) it.copy(undone = true) else it }) else m })
        }
    }

    /** A voice note as text, for the rider to check before sending. */
    suspend fun voiceNote(wav: ByteArray): String = gemini.voiceNote(wav)
}
