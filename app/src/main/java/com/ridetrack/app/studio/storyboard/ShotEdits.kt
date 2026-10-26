package com.ridetrack.app.studio.storyboard

import com.ridetrack.app.studio.Script
import com.ridetrack.app.studio.ScriptShot
import java.util.Locale

/** The rider's direct changes to shots: each changed field gets locked so Gemini leaves it. Pure, unit-tested. */
object ShotEdits {
    fun edit(s: Script, id: String, f: (Storyboard) -> Storyboard): Script {
        var out = s
        out = out.copy(sections = out.sections.map { sec ->
            sec.copy(shots = sec.shots.map { sh ->
                val b = sh.board
                if (sh.id != id || b == null) sh else {
                    val n = f(b)
                    sh.copy(board = n, locked = sh.locked + changed(b, n), outMs = if (sh.clip.isEmpty()) sh.inMs + n.durationMs else sh.outMs)
                }
            })
        })
        return out.copy(lengthSec = (out.totalMs / 1000).toInt().coerceAtLeast(1))
    }

    /** Which fields differ (the ones to lock). */
    fun changed(a: Storyboard, b: Storyboard): Set<String> = buildSet {
        if (a.action != b.action) add(ShotFields.ACTION)
        if (a.framing != b.framing) add(ShotFields.FRAMING)
        if (a.mount != b.mount) add(ShotFields.MOUNT)
        if (a.camera != b.camera) add(ShotFields.CAMERA)
        if (a.durationMs != b.durationMs) add(ShotFields.DURATION)
        if (a.say != b.say) add(ShotFields.SAY)
        if (a.caption != b.caption || a.captionText != b.captionText) add(ShotFields.CAPTION)
    }

    /**
     * Moves shot [id] earlier or later by one place. Each part keeps its number of shots, so at a
     * part's edge the shot crosses into the next part (and its neighbour crosses back).
     */
    fun move(s: Script, id: String, by: Int): Script {
        val order = s.sections.flatMap { it.shots }.toMutableList()
        val a = order.indexOfFirst { it.id == id }
        if (a < 0) return s
        val b = (a + by).coerceIn(0, order.size - 1)
        if (a == b) return s
        order.add(b, order.removeAt(a))
        var k = 0
        return s.copy(sections = s.sections.map { sec -> sec.copy(shots = order.subList(k, k + sec.shots.size).toList().also { k += sec.shots.size }) })
    }

    /** Shot [id]'s on-screen text set to [text] (blank = none); typed by the rider, so it's theirs. */
    fun setText(s: Script, id: String, text: String): Script {
        val sh = Placeholders.find(s, id) ?: return s
        val others = s.texts.filter { it.shotId != id }
        val texts = if (text.isBlank()) others else others + OnScreenText(text, id, minOf(sh.durMs, 3_000L).coerceAtLeast(StoryboardJson.MIN_MS))
        val order = Placeholders.shots(s).map { it.second.id }
        return s.copy(
            texts = texts.sortedBy { order.indexOf(it.shotId) },
            sections = s.sections.map { sec -> sec.copy(shots = sec.shots.map { if (it.id == id) it.copy(locked = it.locked + ShotFields.TEXT) else it }) },
        )
    }

    /** Locks (or unlocks) [field] of shot [id]: Keppo leaves a locked field as it is. */
    fun lock(s: Script, id: String, field: String, on: Boolean): Script =
        s.copy(sections = s.sections.map { sec -> sec.copy(shots = sec.shots.map { if (it.id == id) it.copy(locked = if (on) it.locked + field else it.locked - field) else it }) })

    fun delete(s: Script, id: String): Script =
        if (s.sections.sumOf { it.shots.size } <= 1) s
        else s.copy(sections = s.sections.map { sec -> sec.copy(shots = sec.shots.filter { it.id != id }) }.filter { it.shots.isNotEmpty() })

    /** A new shot after [after], a copy of it to change. */
    fun add(s: Script, after: String): Script {
        val id = Placeholders.newId(s)
        return s.copy(sections = s.sections.map { sec ->
            sec.copy(shots = sec.shots.flatMap { sh -> if (sh.id == after && sh.board != null) listOf(sh, ScriptShot("", 0, sh.board.durationMs, id = id, board = sh.board.copy(action = "New shot: say what to film"))) else listOf(sh) })
        })
    }

    /** One line about a shot for the style memory's edit log. */
    fun describe(b: Storyboard): String = "${b.action} (${b.framing}, ${b.mount}, ${String.format(Locale.US, "%.1f", b.durationMs / 1000.0)} s)"
}
