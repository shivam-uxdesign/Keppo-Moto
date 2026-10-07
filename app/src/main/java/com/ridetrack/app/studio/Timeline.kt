package com.ridetrack.app.studio

import kotlin.math.abs

/** What's selected on the timeline. */
sealed interface TimelinePick {
    data class Clip(val index: Int) : TimelinePick
    data class Caption(val index: Int, val line: Int) : TimelinePick
    data class Text(val id: String) : TimelinePick
    data class Layer(val id: String) : TimelinePick
    data class Audio(val id: String) : TimelinePick
    /** Several clips selected together (to move, delete or paste settings). */
    data class Clips(val indices: Set<Int>) : TimelinePick
    data class Marker(val id: String) : TimelinePick
}

/**
 * The timeline editor's changes to a plan: trim, split, move, delete, volume, captions and text.
 * Every change returns a new plan, so undo is a list of plans. Pure, unit-tested.
 */
object TimelineEdits {
    const val MIN_MS = 300L

    /** The segment at [ms] into the Reel and how far into it. */
    fun at(plan: StudioPlan, ms: Long): Pair<Int, Long> {
        var start = 0L
        plan.segments.forEachIndexed { i, s ->
            if (ms < start + s.durMs) return i to (ms - start).coerceAtLeast(0)
            start += s.durMs
        }
        val last = plan.segments.lastIndex
        return last to (plan.segments.lastOrNull()?.durMs ?: 0L)
    }

    private fun clip(plan: StudioPlan, i: Int): ClipSegment? = (plan.segments.getOrNull(i) as? ClipSegment)?.takeIf { !it.tail }

    private fun with(plan: StudioPlan, i: Int, seg: Segment) = plan.copy(segments = plan.segments.toMutableList().also { it[i] = seg })

    /** Moves a clip's start by [deltaMs] (positive = later), keeping its words where they were said. */
    fun trimStart(plan: StudioPlan, i: Int, deltaMs: Long): StudioPlan {
        val s = clip(plan, i) ?: return plan
        // A freeze frame only gets shorter or longer.
        if (s.still != null) return normalize(with(plan, i, s.copy(durMs = (s.durMs - deltaMs).coerceIn(MIN_MS, 10_000))))
        // Sped up or slowed down: the edge moves in the Reel's time, the clip moves in its own.
        val f = Speed.outPerSource(s.speed, s.ramp)
        val newIn = (s.inMs + (deltaMs / f).toLong()).coerceIn(0, s.inMs + s.sourceMs - (MIN_MS / f).toLong())
        val shift = ((newIn - s.inMs) * f).toLong()
        val dur = s.durMs - shift
        val lines = s.lines.map { it.copy(startMs = it.startMs - shift, endMs = it.endMs - shift) }.filter { it.endMs > 0 && it.startMs < dur }
        return normalize(with(plan, i, s.copy(inMs = newIn, durMs = dur, lines = lines)))
    }

    /** Moves a clip's end by [deltaMs] (positive = longer), never past the end of the clip. */
    fun trimEnd(plan: StudioPlan, i: Int, deltaMs: Long): StudioPlan {
        val s = clip(plan, i) ?: return plan
        val max = if (s.still != null) 10_000 else ((s.bit.clipDurationMs - s.inMs) * Speed.outPerSource(s.speed, s.ramp)).toLong()
        val dur = (s.durMs + deltaMs).coerceIn(MIN_MS, max.coerceAtLeast(MIN_MS))
        return normalize(with(plan, i, s.copy(durMs = dur, lines = s.lines.filter { it.startMs < dur })))
    }

    /** Cuts the clip under [atMs] in two (each part at least [MIN_MS]). */
    fun split(plan: StudioPlan, atMs: Long): StudioPlan {
        val (i, local) = at(plan, atMs)
        val s = clip(plan, i) ?: return plan
        if (local < MIN_MS || local > s.durMs - MIN_MS) return plan
        val a = s.copy(durMs = local, lines = s.lines.filter { it.startMs < local }.map { it.copy(endMs = minOf(it.endMs, local)) })
        val srcLocal = if (s.still != null) 0L else (local / Speed.outPerSource(s.speed, s.ramp)).toLong()
        val b = s.copy(
            inMs = s.inMs + srcLocal, durMs = s.durMs - local, hook = false, text = null, transition = Transition(TransitionKind.CUT),
            frame = s.frame.filter { it.atMs >= local }.map { it.copy(atMs = it.atMs - local) },
            lines = s.lines.filter { it.endMs > local }.map { it.copy(startMs = (it.startMs - local).coerceAtLeast(0), endMs = it.endMs - local) },
        )
        return normalize(plan.copy(segments = plan.segments.toMutableList().also { it[i] = a; it.add(i + 1, b) }))
    }

    /** Swaps a clip with the clip [by] places away (title, stats and the loop end stay put). */
    fun move(plan: StudioPlan, i: Int, by: Int): StudioPlan {
        val a = clip(plan, i) ?: return plan
        val b = clip(plan, i + by) ?: return plan
        return normalize(plan.copy(segments = plan.segments.toMutableList().also { it[i] = b; it[i + by] = a }))
    }

    fun delete(plan: StudioPlan, i: Int): StudioPlan {
        clip(plan, i) ?: return plan
        if (plan.clips.size <= 1) return plan
        return normalize(plan.copy(segments = plan.segments.filterIndexed { k, _ -> k != i }))
    }

    fun volume(plan: StudioPlan, i: Int, v: Float): StudioPlan {
        val s = clip(plan, i) ?: return plan
        return with(plan, i, s.copy(volume = v.coerceIn(0f, 1.5f)))
    }

    /** Puts [bit] in after the clip under [atMs]. */
    fun insert(plan: StudioPlan, bit: Bit, atMs: Long): StudioPlan {
        val (i, _) = at(plan, atMs)
        val pos = (i + 1).coerceAtMost(plan.segments.indexOfLast { it is ClipSegment && !it.tail } + 1).coerceAtLeast(0)
        val seg = ClipSegment(bit, bit.inMs, StudioPlanner.durationOf(bit, plan.vibe, 0.5), bit.lines, hook = false)
        return normalize(plan.copy(segments = plan.segments.toMutableList().also { it.add(pos, seg) }))
    }

    // ---- captions ---------------------------------------------------------------------------

    /** New words for a caption line; empty removes it. */
    fun caption(plan: StudioPlan, i: Int, line: Int, text: String): StudioPlan {
        val s = clip(plan, i) ?: return plan
        val t = text.trim()
        val lines = s.lines.toMutableList()
        if (line !in lines.indices) return plan
        if (t.isEmpty()) lines.removeAt(line) else lines[line] = lines[line].copy(text = t)
        return with(plan, i, s.copy(lines = lines))
    }

    /** Shows a caption line [deltaMs] earlier or later, inside its clip. */
    fun nudgeCaption(plan: StudioPlan, i: Int, line: Int, deltaMs: Long): StudioPlan {
        val s = clip(plan, i) ?: return plan
        val l = s.lines.getOrNull(line) ?: return plan
        val len = l.endMs - l.startMs
        val start = (l.startMs + deltaMs).coerceIn(0, (s.durMs - len).coerceAtLeast(0))
        return with(plan, i, s.copy(lines = s.lines.toMutableList().also { it[line] = l.copy(startMs = start, endMs = start + len) }.sortedBy { it.startMs }))
    }

    /** A new caption at [atMs] for 2 s (inside the clip there). */
    fun addCaption(plan: StudioPlan, atMs: Long, text: String): StudioPlan {
        val (i, local) = at(plan, atMs)
        val s = clip(plan, i) ?: return plan
        val start = local.coerceIn(0, (s.durMs - 500).coerceAtLeast(0))
        val line = CaptionLine(start, minOf(start + 2_000, s.durMs), text.trim())
        return with(plan, i, s.copy(lines = (s.lines + line).sortedBy { it.startMs }))
    }

    // ---- text -------------------------------------------------------------------------------

    fun addText(plan: StudioPlan, atMs: Long, text: String, id: String): StudioPlan {
        val start = atMs.coerceIn(0, (plan.totalMs - 500).coerceAtLeast(0))
        return plan.copy(texts = plan.texts + TextItem(id, start, minOf(start + 2_500, plan.totalMs), text.trim(), 0.3f))
    }

    private fun text(plan: StudioPlan, id: String, f: (TextItem) -> TextItem?) =
        plan.copy(texts = plan.texts.mapNotNull { if (it.id == id) f(it) else it })

    fun editText(plan: StudioPlan, id: String, text: String) = text(plan, id) { t -> text.trim().takeIf { it.isNotEmpty() }?.let { t.copy(text = it) } }

    fun deleteText(plan: StudioPlan, id: String) = text(plan, id) { null }

    /** Longer or shorter by [deltaMs] (at least half a second, not past the end). */
    fun resizeText(plan: StudioPlan, id: String, deltaMs: Long) = text(plan, id) { t -> t.copy(endMs = (t.endMs + deltaMs).coerceIn(t.startMs + 500, plan.totalMs)) }

    /** Earlier or later by [deltaMs], keeping its length. */
    fun moveText(plan: StudioPlan, id: String, deltaMs: Long) = text(plan, id) { t ->
        val len = t.endMs - t.startMs
        val start = (t.startMs + deltaMs).coerceIn(0, (plan.totalMs - len).coerceAtLeast(0))
        t.copy(startMs = start, endMs = start + len)
    }

    fun placeText(plan: StudioPlan, id: String, y: Float) = text(plan, id) { it.copy(y = y.coerceIn(0.08f, 0.92f)) }

    /** The section texts the script put on clips become timeline text, so they can be moved and edited. */
    fun liftTexts(plan: StudioPlan, newId: () -> String): StudioPlan {
        val extra = ArrayList<TextItem>()
        var start = 0L
        val segs = plan.segments.map { s ->
            val out = if (s is ClipSegment && s.text != null) {
                extra += TextItem(newId(), start, start + minOf(2_600L, s.durMs), s.text, 0.3f)
                s.copy(text = null)
            } else s
            start += s.durMs
            out
        }
        return plan.copy(segments = segs, texts = plan.texts + extra)
    }

    /**
     * After a change: the first clip opens the Reel (the hook, with the title and hook line), the
     * loop end shows its first split second again, and texts stay inside the Reel.
     */
    fun normalize(plan: StudioPlan): StudioPlan {
        val firstClip = plan.segments.indexOfFirst { it is ClipSegment && !it.tail && !it.teaser }
        var segs = plan.segments.mapIndexed { k, s -> if (s is ClipSegment && !s.tail) s.copy(hook = k == firstClip) else s }
        val tail = segs.indexOfFirst { it is ClipSegment && it.tail }
        if (tail >= 0 && firstClip >= 0) {
            val f = segs[firstClip] as ClipSegment
            segs = segs.toMutableList().also { it[tail] = ClipSegment(f.bit, f.inMs, StudioPlanner.TAIL_MS.coerceAtMost(f.durMs), emptyList(), hook = false, tail = true) }
        }
        val total = segs.sumOf { it.durMs }
        val texts = plan.texts.mapNotNull { t ->
            if (t.startMs >= total) null else t.copy(endMs = minOf(t.endMs, total))
        }
        var out = plan.copy(segments = segs, texts = texts)
        // The engine sound follows its clips wherever they moved.
        val engine = plan.audio.filter { it.kind == TrackKind.ENGINE && it.file != null }
        if (engine.isNotEmpty()) {
            var n = 0
            out = TrackEdits.addEngine(out, engine.associate { it.bit.momentId to it.file!! }, { "eng-${n++}" }, engine.first().volume)
        }
        return TrackEdits.clamp(out)
    }

    /** Where each segment starts, in Reel ms. */
    fun starts(plan: StudioPlan): List<Long> {
        var t = 0L
        return plan.segments.map { s -> t.also { t += s.durMs } }
    }

    /** The nearest cut to [ms] within [snapMs], for snapping the playhead; null if none. */
    fun snap(plan: StudioPlan, ms: Long, snapMs: Long = 150): Long? =
        (starts(plan) + plan.totalMs).minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) <= snapMs }
}

/** Undo and redo for the timeline: every change pushes the plan before it. */
class EditHistory(start: StudioPlan) {
    private val undo = ArrayDeque<StudioPlan>()
    private val redo = ArrayDeque<StudioPlan>()
    private val undoLabels = ArrayDeque<String>()
    private val redoLabels = ArrayDeque<String>()

    /** What each undo step did, oldest first. */
    val steps: List<String> get() = undoLabels.toList()
    var current: StudioPlan = start
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun apply(next: StudioPlan, label: String = "Change") {
        if (next == current) return
        undo.addLast(current)
        undoLabels.addLast(label)
        if (undo.size > 100) { undo.removeFirst(); undoLabels.removeFirst() }
        redo.clear()
        redoLabels.clear()
        current = next
    }

    /** While a finger drags (a trim): show [next] without adding a step yet. */
    fun preview(next: StudioPlan) { current = next }

    /** The drag ended: one undo step back to [before]. */
    fun settle(before: StudioPlan, label: String = "Trim") {
        if (before == current) return
        undo.addLast(before)
        undoLabels.addLast(label)
        if (undo.size > 100) { undo.removeFirst(); undoLabels.removeFirst() }
        redo.clear()
        redoLabels.clear()
    }

    fun undo() {
        undo.removeLastOrNull()?.let { redo.addLast(current); redoLabels.addLast(undoLabels.removeLastOrNull() ?: "Change"); current = it }
    }

    fun redo() {
        redo.removeLastOrNull()?.let { undo.addLast(current); undoLabels.addLast(redoLabels.removeLastOrNull() ?: "Change"); current = it }
    }

    /** Goes back to just before step [index] of [steps] (undoing it and everything after). */
    fun undoTo(index: Int) {
        while (undo.size > index && undo.isNotEmpty()) undo()
    }
}
