package com.ridetrack.app.studio

import android.graphics.Canvas

/**
 * One frame's graphics over segment [i] of a Reel, [localMs] into it: the made video and the
 * editor's preview both draw through here, so what the rider sees is what they get.
 */
object ReelOverlay {
    /** What's live at the frame: speed (−1 = none), the clock, lean (null = unknown). */
    data class Live(val kmh: Int, val clock: String, val lean: Int?)

    /**
     * The speed, clock and lean at [wall] in clip [seg]. Clips from other rides have no samples
     * here: their own speed; no speed for that moment (a GPS gap, a phone video): none rather
     * than a wrong "0".
     */
    fun liveAt(input: RenderInput, seg: ClipSegment, wall: Long): Live {
        val kmh = input.speedAt(wall).takeIf { it >= 0 && seg.bit.fromRide == null } ?: seg.bit.speedKmh.toInt().takeIf { it > 0 } ?: -1
        return Live(kmh, input.clockAt(wall), input.leanAt(wall))
    }

    /** [liveAt] for the editor's preview, [localMs] into the segment (its time runs at the clip's speed). */
    fun liveAt(input: RenderInput, seg: ClipSegment, localMs: Long, durMs: Long): Live {
        val start = seg.bit.atMillis - seg.bit.inMs + seg.inMs
        return liveAt(input, seg, start + if (seg.still != null) 0 else localMs * seg.sourceMs / durMs.coerceAtLeast(1))
    }

    /**
     * The redline light over clip [seg] (segment [i], shown for [durMs]) at [localMs]: from the
     * rider's tags where there are some, else from the engine sound. 0 when off.
     */
    fun redline(input: RenderInput, plan: StudioPlan, i: Int, seg: ClipSegment, localMs: Long, durMs: Long): Float {
        val start = plan.startOf(i)
        val tags = plan.redlines.filter { it.endMs > start && it.startMs < start + durMs }
        val level = { t: Long ->
            tags.firstNotNullOfOrNull { Revs.tagLevel(it, start + t) }
                ?: if (seg.still != null) null else input.revAt(seg.bit.momentId, seg.inMs + t * seg.sourceMs / durMs.coerceAtLeast(1))
        }
        return Revs.pulse(level, localMs)
    }

    /** The voice-over's captions over segment [i] (shown for [durMs]), timed from its start. */
    fun voiceLines(input: RenderInput, plan: StudioPlan, i: Int, durMs: Long): List<CaptionLine> {
        val start = plan.startOf(i)
        return input.voiceLines.filter { it.endMs > start && it.startMs < start + durMs }.map { it.copy(startMs = it.startMs - start, endMs = it.endMs - start) }
    }

    /**
     * Draws the frame on [c] (sized as [art]). [voLines] are the voice-over's captions timed from
     * the segment's start; [layers] draws layers (made frame by frame) under the text, by Reel time.
     */
    fun draw(
        c: Canvas,
        art: StudioArt,
        plan: StudioPlan,
        i: Int,
        localMs: Long,
        durMs: Long,
        input: RenderInput,
        live: Live,
        voLines: List<CaptionLine> = emptyList(),
        layers: ((Canvas, Long) -> Unit)? = null,
    ) {
        val seg = plan.segments.getOrNull(i) ?: return
        val o = input.options
        val beatMs = o.bpm?.takeIf { it in 50..200 }?.let { 60_000L / it }
        val f = Cuts.frameAt(plan, i, localMs, durMs, beatMs)
        val global = plan.startOf(i) + localMs
        when (seg) {
            is ClipSegment -> {
                val lines = if (seg.tail) emptyList() else (seg.lines + voLines).sortedBy { it.startMs }
                // The speed shows once: not in the corner while a speed sticker shows it.
                val sticker = plan.stickers.any { it.kind == StickerKind.SPEED && global in it.startMs until it.endMs }
                val opener = input.opener.takeIf { seg.hook && !seg.tail }
                art.drawClip(c, f, lines, if (o.speedBadge && !sticker) live.kmh else -1, live.clock, o.captions, opener, plan.captionLook, redline(input, plan, i, seg, localMs, durMs))
                seg.text?.let { t -> art.drawSectionText(c, plan.vibe, t, localMs) }
            }
            is TitleSegment -> art.drawTitle(c, f, input.card, o.map)
            is StatsSegment -> art.drawStats(c, f, input.card, o.map, o.watermark)
        }
        layers?.invoke(c, global)
        // Text and stickers the rider placed on the timeline, in Reel time.
        plan.texts.forEach { t -> if (global in t.startMs until t.endMs) art.drawTextItem(c, plan.vibe, t, global - t.startMs, t.endMs - t.startMs) }
        plan.stickers.forEach { st -> if (global in st.startMs until st.endMs) art.drawSticker(c, st, global - st.startMs, live.kmh, live.lean, plan.vibe) }
    }
}
