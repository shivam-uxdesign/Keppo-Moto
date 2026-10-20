package com.ridetrack.app.studio.storyboard

import com.ridetrack.app.AppContainer
import com.ridetrack.app.studio.StudioGemini
import com.ridetrack.app.transcribe.GeminiNet

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

    /** Sends [text] in [chatId]; returns the updated chat, or throws with a message for the rider. */
    suspend fun send(chatId: String, text: String, voiceMs: Long? = null): StoryboardChat {
        val store = c.storyboard
        val before = store.get(chatId) ?: error("That chat is gone")
        val first = before.messages.none { it.fromRider }
        var chat = store.update(chatId) { ch ->
            ch.copy(
                title = if (first) StoryboardEngine.titleOf(text) else ch.title,
                messages = ch.messages + ChatMessage(true, text, System.currentTimeMillis(), voiceMs),
            )
        }!!
        val prompt = StoryboardEngine.prompt(before, store.gear.value, c.style.context(), text, firstChat = store.chats.value.size <= 1)
        var raw = ""
        var reply: StoryboardReply? = null
        for (attempt in 0..1) {
            raw = try {
                gemini.storyboard(if (attempt == 0) prompt else "$prompt\n\nYour last answer couldn't be read as the JSON asked for. Answer again with JSON only, exactly in the shape given.")
            } catch (e: Exception) {
                c.errors.record("Storyboard", "Couldn't reach Gemini", e)
                store.update(chatId) { it.copy(messages = it.messages + ChatMessage(false, if (GeminiNet.isUnreachable(e)) GeminiNet.message(e) else "Couldn't reach Gemini just now. Try again in a moment.", System.currentTimeMillis())) }
                throw e
            }
            reply = StoryboardEngine.parse(raw, chat.script)
            if (reply != null) break
        }
        val r = reply ?: run {
            c.errors.record("Storyboard", "Couldn't read Gemini's answer", extra = raw.take(8_000))
            return store.update(chatId) { it.copy(raw = it.raw + raw, messages = it.messages + ChatMessage(false, "Couldn't read Gemini's answer. Try again.", System.currentTimeMillis())) }!!
        }
        // A new script, or patches to the current one (locked fields stay).
        val current = chat.script
        val next = when {
            r.script != null && current == null -> r.script
            r.script != null && current != null && r.newVersion == true -> r.script
            current != null && r.patches.isNotEmpty() -> StoryboardEngine.apply(current, r.patches, r.unlock)
            r.script != null -> r.script
            else -> null
        }
        val (gear, chips) = StoryboardEngine.remember(store.gear.value, r.memory)
        if (r.memory.isNotEmpty() || !store.gear.value.asked) store.setGear(gear)
        chat = store.update(chatId) { ch ->
            val versions = if (next != null && next != current) ch.versions + next else ch.versions
            ch.copy(
                raw = ch.raw + raw,
                versions = versions,
                messages = ch.messages + ChatMessage(
                    false, r.reply + (r.styleSuggestion?.let { "\n\n$it" } ?: ""), System.currentTimeMillis(),
                    chips = chips, question = r.question, choices = r.choices,
                    version = if (next != null && next != current) versions.size else null,
                ),
            )
        }!!
        return chat
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
