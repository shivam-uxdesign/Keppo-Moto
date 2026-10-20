package com.ridetrack.app.studio.storyboard

import com.ridetrack.app.studio.PieceFormat
import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptShot
import com.ridetrack.app.studio.Section
import com.ridetrack.app.studio.SectionKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** When the ideal gear for a shot isn't owned: what it would be, why, and what's used instead. */
data class GearFallback(val ideal: String, val reason: String, val using: String)

/** Hints for finding footage that fits a shot (auto-suggest). */
data class MatchHints(
    /** Event words: "strong acceleration", "lean", "hard brake", "rev", "voice"… */
    val events: List<String> = emptyList(),
    /** Words that may have been said or seen: "tunnel", "rain"… */
    val words: List<String> = emptyList(),
    val minMs: Long = 0,
    /** Needs the engine mic's sound. */
    val engine: Boolean = false,
    /** Only a fresh recording will do (never filled by a suggestion). */
    val mustRecord: Boolean = false,
)

/**
 * A shot's storyboard: exactly what to film. Specific enough that two people would film nearly the
 * same shot.
 */
data class Storyboard(
    val action: String,
    /** extreme close-up, close-up, medium or wide. */
    val framing: String = "medium",
    val angle: String = "",
    val movement: String = "",
    val mustShow: String = "",
    /** "phone back", "phone front", an action cam… */
    val camera: String = "phone back",
    /** "handlebar", "tripod at the roadside", "handheld"… */
    val mount: String = "handlebar",
    /** Filmed while riding (needs a fixed mount and the ride camera). */
    val riding: Boolean = true,
    val fallback: GearFallback? = null,
    val durationMs: Long = 2_000,
    /** A voice-over line to say over it; null = none. */
    val say: String? = null,
    /** What leads the sound: "engine", "music" or "voice". */
    val audio: String = "music",
    val transition: String? = null,
    val match: MatchHints = MatchHints(),
) {
    /** Off-bike shots (tripod, handheld, a helper) can be recorded with the phone's camera page. */
    val recordable: Boolean get() = !riding

    /** The framing icon's word: eye, head, rider or road. */
    val framingKind: Framing get() = Framing.of(framing)
}

enum class Framing(val label: String) {
    EXTREME("Extreme close-up"), CLOSE("Close-up"), MEDIUM("Medium"), WIDE("Wide");

    companion object {
        fun of(text: String): Framing {
            val t = text.lowercase(Locale.US)
            return when {
                "extreme" in t || "ecu" in t || "macro" in t -> EXTREME
                "close" in t || t == "cu" -> CLOSE
                "wide" in t || "long" in t || "establishing" in t -> WIDE
                else -> MEDIUM
            }
        }
    }
}

/** Text on the Reel (a title, a step name, a joke): which shot it starts on and how long it stays. */
data class OnScreenText(val text: String, val shotId: String, val durMs: Long)

/** Fields of a shot the rider can lock by editing them by hand (Gemini never changes them then). */
object ShotFields {
    const val ACTION = "action"
    const val FRAMING = "framing"
    const val MOUNT = "mount"
    const val CAMERA = "camera"
    const val DURATION = "duration"
    const val SAY = "say"
    val ALL = listOf(ACTION, FRAMING, MOUNT, CAMERA, DURATION, SAY, "angle", "movement", "mustShow", "audio", "transition", "riding", "fallback", "match")
}

/** The storyboard parts of a script: shots with no clip yet, read and written alongside [com.ridetrack.app.studio.ScriptJson]. Pure, unit-tested. */
object StoryboardJson {
    fun write(b: Storyboard): JSONObject = JSONObject()
        .put("action", b.action).put("framing", b.framing).put("angle", b.angle).put("movement", b.movement).put("mustShow", b.mustShow)
        .put("camera", b.camera).put("mount", b.mount).put("riding", b.riding).put("durationMs", b.durationMs)
        .put("say", b.say ?: JSONObject.NULL).put("audio", b.audio).put("transition", b.transition ?: JSONObject.NULL)
        .put("fallback", b.fallback?.let { JSONObject().put("ideal", it.ideal).put("reason", it.reason).put("using", it.using) } ?: JSONObject.NULL)
        .put("match", JSONObject().put("events", JSONArray(b.match.events)).put("words", JSONArray(b.match.words)).put("minMs", b.match.minMs)
            .put("engine", b.match.engine).put("mustRecord", b.match.mustRecord))

    fun read(o: JSONObject?): Storyboard? {
        if (o == null) return null
        val action = o.optString("action").trim().takeIf { it.isNotEmpty() } ?: return null
        val m = o.optJSONObject("match")
        return Storyboard(
            action = action,
            framing = o.optString("framing", "medium"),
            angle = o.optString("angle"),
            movement = o.optString("movement"),
            mustShow = o.optString("mustShow"),
            camera = o.optString("camera", "phone back"),
            mount = o.optString("mount", "handlebar"),
            riding = o.optBoolean("riding", true),
            fallback = o.optJSONObject("fallback")?.let { f -> GearFallback(f.optString("ideal"), f.optString("reason"), f.optString("using")).takeIf { it.ideal.isNotBlank() } },
            durationMs = o.optLong("durationMs", 2_000).coerceIn(MIN_MS, MAX_MS),
            say = o.optString("say").takeIf { !o.isNull("say") && it.isNotBlank() },
            audio = o.optString("audio", "music"),
            transition = o.optString("transition").takeIf { !o.isNull("transition") && it.isNotBlank() },
            match = MatchHints(
                events = m?.optJSONArray("events").strings(),
                words = m?.optJSONArray("words").strings(),
                minMs = m?.optLong("minMs", 0) ?: 0,
                engine = m?.optBoolean("engine", false) ?: false,
                mustRecord = m?.optBoolean("mustRecord", false) ?: false,
            ),
        )
    }

    fun writeTexts(list: List<OnScreenText>): JSONArray = JSONArray().apply { list.forEach { put(JSONObject().put("text", it.text).put("shot", it.shotId).put("durMs", it.durMs)) } }

    fun readTexts(a: JSONArray?): List<OnScreenText> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        OnScreenText(o.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null, o.optString("shot"), o.optLong("durMs", 2_000))
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf { s -> s.isNotEmpty() } }

    const val MIN_MS = 500L
    const val MAX_MS = 10_000L
}

/** Placeholder shots in a script: the shots still to film or fill, and helpers. Pure. */
object Placeholders {
    /** Every storyboard shot with its section, in order. */
    fun shots(s: Script): List<Pair<Section, ScriptShot>> = s.sections.flatMap { sec -> sec.shots.filter { it.board != null }.map { sec to it } }

    fun find(s: Script, id: String): ScriptShot? = s.sections.flatMap { it.shots }.firstOrNull { it.id == id }

    /** The shot's number in the script ("02"). */
    fun number(s: Script, id: String): String = String.format(Locale.US, "%02d", shots(s).indexOfFirst { it.second.id == id } + 1)

    /** A fresh shot id not used in [s]. */
    fun newId(s: Script): String {
        val used = s.sections.flatMap { it.shots }.mapNotNull { it.id }.toSet()
        var n = used.size + 1
        while ("s$n" in used) n++
        return "s$n"
    }

    /** A storyboard script with nothing filmed: each shot a placeholder of its planned length. */
    fun empty(title: String, format: PieceFormat, sections: List<Pair<SectionKind, List<Pair<String, Storyboard>>>>, texts: List<OnScreenText> = emptyList(), musicNote: String? = null): Script =
        Script(
            format = format, title = title, why = null,
            lengthSec = (sections.sumOf { s -> s.second.sumOf { it.second.durationMs } } / 1000).toInt().coerceAtLeast(1),
            shape = "storyboard", hookLine = null, postCaption = null,
            sections = sections.map { (kind, shots) -> Section(kind, "storyboard", shots.map { (id, b) -> ScriptShot("", 0, b.durationMs, id = id, board = b) }) },
            texts = texts, musicNote = musicNote,
        )
}
