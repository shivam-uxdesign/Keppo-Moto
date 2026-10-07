package com.ridetrack.app.studio

import kotlin.math.abs

/** Ready-made places for a layer. */
enum class LayerPreset(val label: String) {
    CORNER("Corner"),
    TOP("Top half"),
    BOTTOM("Bottom half"),
    BOTTOM_LEFT("Below, left"),
    BOTTOM_RIGHT("Below, right"),
    FULL("Full"),
}

/** Changes to layers, sound tracks, the mixer and markers. Every change returns a new plan. Pure, unit-tested. */
object TrackEdits {
    const val MIN_MS = 300L

    // ---- layers ------------------------------------------------------------------------------

    /** Puts [bit] over the edit from [atMs], as long as it lasts (to the end of the Reel at most). */
    fun addLayer(plan: StudioPlan, bit: Bit, atMs: Long, id: String, preset: LayerPreset = LayerPreset.CORNER): StudioPlan {
        val start = atMs.coerceIn(0, (plan.totalMs - MIN_MS).coerceAtLeast(0))
        val dur = (bit.outMs - bit.inMs).coerceAtLeast(MIN_MS).coerceAtMost(plan.totalMs - start).coerceAtMost(bit.clipDurationMs - bit.inMs)
        if (dur < MIN_MS) return plan
        val layer = place(LayerItem(id, bit, bit.inMs, start, dur), preset)
        return plan.copy(layers = plan.layers + layer)
    }

    /** The preset's place, size, shape and frame. */
    fun place(l: LayerItem, preset: LayerPreset): LayerItem = when (preset) {
        LayerPreset.CORNER -> l.copy(cx = 0.72f, cy = 0.22f, w = 0.42f, aspect = 9f / 16f, shape = LayerShape.ROUNDED, border = true, keys = emptyList())
        LayerPreset.TOP -> l.copy(cx = 0.5f, cy = 0.25f, w = 1f, aspect = 9f / 8f, shape = LayerShape.RECT, border = false, rotation = 0f, keys = emptyList())
        LayerPreset.BOTTOM -> l.copy(cx = 0.5f, cy = 0.75f, w = 1f, aspect = 9f / 8f, shape = LayerShape.RECT, border = false, rotation = 0f, keys = emptyList())
        LayerPreset.BOTTOM_LEFT -> l.copy(cx = 0.25f, cy = 0.75f, w = 0.5f, aspect = 9f / 16f, shape = LayerShape.RECT, border = false, rotation = 0f, keys = emptyList())
        LayerPreset.BOTTOM_RIGHT -> l.copy(cx = 0.75f, cy = 0.75f, w = 0.5f, aspect = 9f / 16f, shape = LayerShape.RECT, border = false, rotation = 0f, keys = emptyList())
        LayerPreset.FULL -> l.copy(cx = 0.5f, cy = 0.5f, w = 1f, aspect = 9f / 16f, shape = LayerShape.RECT, border = false, rotation = 0f, keys = emptyList())
    }

    private fun layer(plan: StudioPlan, id: String, f: (LayerItem) -> LayerItem?): StudioPlan =
        plan.copy(layers = plan.layers.mapNotNull { if (it.id == id) f(it) else it })

    fun preset(plan: StudioPlan, id: String, p: LayerPreset) = layer(plan, id) { place(it, p) }

    /** Moves the layer by a fraction of the frame (at [atMs] into the Reel when it has moves, else everywhere). */
    fun moveLayer(plan: StudioPlan, id: String, dx: Float, dy: Float, atMs: Long? = null) = layer(plan, id) { l ->
        if (l.keys.isNotEmpty() && atMs != null) {
            val k = keyAt(l, atMs - l.startMs)
            setKey(l, k.copy(cx = (k.cx + dx).coerceIn(0f, 1f), cy = (k.cy + dy).coerceIn(0f, 1f)))
        } else {
            l.copy(cx = (l.cx + dx).coerceIn(0f, 1f), cy = (l.cy + dy).coerceIn(0f, 1f))
        }
    }

    /** Scales the layer's width by [factor] (at [atMs] when it has moves). */
    fun resizeLayer(plan: StudioPlan, id: String, factor: Float, atMs: Long? = null) = layer(plan, id) { l ->
        if (l.keys.isNotEmpty() && atMs != null) {
            val k = keyAt(l, atMs - l.startMs)
            setKey(l, k.copy(w = (k.w * factor).coerceIn(0.1f, 1.5f)))
        } else {
            l.copy(w = (l.w * factor).coerceIn(0.1f, 1.5f))
        }
    }

    fun rotateLayer(plan: StudioPlan, id: String, degrees: Float) = layer(plan, id) { it.copy(rotation = ((it.rotation + degrees) % 360f + 360f) % 360f) }
    fun shape(plan: StudioPlan, id: String, s: LayerShape) = layer(plan, id) { it.copy(shape = s, aspect = if (s == LayerShape.CIRCLE) 1f else if (it.aspect == 1f) 9f / 16f else it.aspect) }
    fun opacity(plan: StudioPlan, id: String, o: Float) = layer(plan, id) { it.copy(opacity = o.coerceIn(0.1f, 1f)) }
    fun border(plan: StudioPlan, id: String, on: Boolean) = layer(plan, id) { it.copy(border = on) }
    fun layerVolume(plan: StudioPlan, id: String, v: Float) = layer(plan, id) { it.copy(volume = v.coerceIn(0f, 1.5f)) }
    fun deleteLayer(plan: StudioPlan, id: String) = layer(plan, id) { null }

    /** Earlier or later by [deltaMs], keeping its length, inside the Reel. */
    fun shiftLayer(plan: StudioPlan, id: String, deltaMs: Long) = layer(plan, id) { l ->
        l.copy(startMs = (l.startMs + deltaMs).coerceIn(0, (plan.totalMs - l.durMs).coerceAtLeast(0)))
    }

    /** Longer or shorter at its end, within its clip and the Reel. */
    fun resizeLayerTime(plan: StudioPlan, id: String, deltaMs: Long) = layer(plan, id) { l ->
        val max = minOf(l.bit.clipDurationMs - l.inMs, plan.totalMs - l.startMs)
        l.copy(durMs = (l.durMs + deltaMs).coerceIn(MIN_MS, max.coerceAtLeast(MIN_MS)))
    }

    /** Where the layer is [localMs] after it starts: between its moves, or its fixed place. */
    fun keyAt(l: LayerItem, localMs: Long): LayerKey {
        val keys = l.keys
        if (keys.isEmpty()) return LayerKey(localMs, l.cx, l.cy, l.w)
        if (localMs <= keys.first().atMs) return keys.first().copy(atMs = localMs)
        if (localMs >= keys.last().atMs) return keys.last().copy(atMs = localMs)
        val b = keys.indexOfFirst { it.atMs >= localMs }
        val k1 = keys[b - 1]
        val k2 = keys[b]
        val f = (localMs - k1.atMs).toFloat() / (k2.atMs - k1.atMs).coerceAtLeast(1)
        // Ease in and out between moves.
        val e = f * f * (3 - 2 * f)
        return LayerKey(localMs, k1.cx + (k2.cx - k1.cx) * e, k1.cy + (k2.cy - k1.cy) * e, k1.w + (k2.w - k1.w) * e)
    }

    /** Sets (or replaces, within 100 ms) a move point. */
    private fun setKey(l: LayerItem, k: LayerKey): LayerItem {
        val others = l.keys.filter { abs(it.atMs - k.atMs) > 100 }
        return l.copy(keys = (others + k).sortedBy { it.atMs })
    }

    /** Marks where the layer is now, at [atMs] (Reel time), as a move point; the first one also marks its start. */
    fun addKey(plan: StudioPlan, id: String, atMs: Long) = layer(plan, id) { l ->
        val local = (atMs - l.startMs).coerceIn(0, l.durMs)
        val here = keyAt(l, local)
        val start = if (l.keys.isEmpty() && local > 0) listOf(LayerKey(0, l.cx, l.cy, l.w)) else emptyList()
        setKey(l.copy(keys = l.keys + start), here)
    }

    fun clearKeys(plan: StudioPlan, id: String) = layer(plan, id) { it.copy(keys = emptyList()) }

    // ---- sound -------------------------------------------------------------------------------

    private fun clip(plan: StudioPlan, i: Int): ClipSegment? = (plan.segments.getOrNull(i) as? ClipSegment)?.takeIf { !it.tail }

    /**
     * Detaches the clip's sound onto its own track: the clip goes quiet and the sound becomes a
     * block that can be moved or run past the cut.
     */
    fun detach(plan: StudioPlan, i: Int, id: String): StudioPlan {
        val s = clip(plan, i) ?: return plan
        if (s.volume == 0f && plan.audio.any { it.kind == TrackKind.DETACHED && it.bit.momentId == s.bit.momentId && it.inMs == s.inMs }) return plan
        val level = (if (s.lines.isNotEmpty()) 1f else 0.55f) * s.volume.coerceAtLeast(0.01f)
        val item = AudioItem(id, TrackKind.DETACHED, s.bit, s.inMs, plan.startOf(i), s.durMs, volume = level)
        return plan.copy(
            segments = plan.segments.toMutableList().also { it[i] = s.copy(volume = 0f) },
            audio = plan.audio + item,
        )
    }

    /**
     * The engine mic's sound under every clip that has it ([engine]: moment id → its engine
     * file), lined up with the picture. Replaces engine sound added before.
     */
    fun addEngine(plan: StudioPlan, engine: Map<String, String>, newId: () -> String, volume: Float = 0.6f): StudioPlan {
        val starts = TimelineEdits.starts(plan)
        val items = plan.segments.mapIndexedNotNull { i, seg ->
            val s = seg as? ClipSegment ?: return@mapIndexedNotNull null
            if (s.tail) return@mapIndexedNotNull null
            val f = engine[s.bit.momentId] ?: return@mapIndexedNotNull null
            AudioItem(newId(), TrackKind.ENGINE, s.bit, s.inMs, starts[i], s.durMs, volume, fadeInMs = 60, fadeOutMs = 60, file = f)
        }
        return plan.copy(audio = plan.audio.filter { it.kind != TrackKind.ENGINE } + items)
    }

    private fun audio(plan: StudioPlan, id: String, f: (AudioItem) -> AudioItem?): StudioPlan =
        plan.copy(audio = plan.audio.mapNotNull { if (it.id == id) f(it) else it })

    fun audioVolume(plan: StudioPlan, id: String, v: Float) = audio(plan, id) { it.copy(volume = v.coerceIn(0f, 1.5f)) }
    fun fade(plan: StudioPlan, id: String, inMs: Long, outMs: Long) = audio(plan, id) { it.copy(fadeInMs = inMs.coerceIn(0, it.durMs / 2), fadeOutMs = outMs.coerceIn(0, it.durMs / 2)) }
    fun deleteAudio(plan: StudioPlan, id: String) = audio(plan, id) { null }

    /** Earlier or later, keeping its length (a J or L cut). */
    fun shiftAudio(plan: StudioPlan, id: String, deltaMs: Long) = audio(plan, id) { a ->
        a.copy(startMs = (a.startMs + deltaMs).coerceIn(0, (plan.totalMs - MIN_MS).coerceAtLeast(0)))
    }

    /** Runs longer or shorter at its end: within its clip (the sound can run past the picture's cut). */
    fun resizeAudio(plan: StudioPlan, id: String, deltaMs: Long) = audio(plan, id) { a ->
        val max = minOf(a.bit.clipDurationMs - a.inMs, plan.totalMs - a.startMs)
        a.copy(durMs = (a.durMs + deltaMs).coerceIn(MIN_MS, max.coerceAtLeast(MIN_MS)))
    }

    /** Starts earlier or later in its clip and on the timeline together (its end stays). */
    fun trimAudioStart(plan: StudioPlan, id: String, deltaMs: Long) = audio(plan, id) { a ->
        val d = deltaMs.coerceIn(-minOf(a.inMs, a.startMs), a.durMs - MIN_MS)
        a.copy(inMs = a.inMs + d, startMs = a.startMs + d, durMs = a.durMs - d)
    }

    /** A volume point [atMs] after the sound starts (replaces one within 100 ms). */
    fun addVolumePoint(plan: StudioPlan, id: String, atMs: Long, level: Float) = audio(plan, id) { a ->
        val t = atMs.coerceIn(0, a.durMs)
        a.copy(curve = (a.curve.filter { abs(it.atMs - t) > 100 } + VolumePoint(t, level.coerceIn(0f, 1.5f))).sortedBy { it.atMs })
    }

    fun clearCurve(plan: StudioPlan, id: String) = audio(plan, id) { it.copy(curve = emptyList()) }

    /** The level of [a] at [localMs]: its volume, its curve, and its fades. */
    fun levelAt(a: AudioItem, localMs: Long): Float {
        val curve = a.curve
        val c = when {
            curve.isEmpty() -> 1f
            localMs <= curve.first().atMs -> curve.first().level
            localMs >= curve.last().atMs -> curve.last().level
            else -> {
                val b = curve.indexOfFirst { it.atMs >= localMs }
                val p1 = curve[b - 1]
                val p2 = curve[b]
                p1.level + (p2.level - p1.level) * (localMs - p1.atMs).toFloat() / (p2.atMs - p1.atMs).coerceAtLeast(1)
            }
        }
        val fadeIn = if (a.fadeInMs > 0) (localMs.toFloat() / a.fadeInMs).coerceIn(0f, 1f) else 1f
        val fadeOut = if (a.fadeOutMs > 0) ((a.durMs - localMs).toFloat() / a.fadeOutMs).coerceIn(0f, 1f) else 1f
        return a.volume * c * fadeIn * fadeOut
    }

    // ---- mixer -------------------------------------------------------------------------------

    fun trackVolume(plan: StudioPlan, k: TrackKind, v: Float) = plan.copy(mix = plan.mix.copy(volumes = plan.mix.volumes + (k to v.coerceIn(0f, 1.5f))))
    fun mute(plan: StudioPlan, k: TrackKind) = plan.copy(mix = plan.mix.copy(muted = if (k in plan.mix.muted) plan.mix.muted - k else plan.mix.muted + k))
    fun solo(plan: StudioPlan, k: TrackKind) = plan.copy(mix = plan.mix.copy(solo = if (plan.mix.solo == k) null else k))
    fun duck(plan: StudioPlan, on: Boolean) = plan.copy(mix = plan.mix.copy(duck = on))
    fun cleanVoice(plan: StudioPlan, on: Boolean) = plan.copy(mix = plan.mix.copy(cleanVoice = on))

    // ---- several clips at once ---------------------------------------------------------------

    /** Deletes the clips at [indices] (at least one clip stays). */
    fun deleteClips(plan: StudioPlan, indices: Set<Int>): StudioPlan {
        val drop = indices.filter { clip(plan, it) != null }.toSet()
        if (drop.isEmpty() || plan.clips.size - drop.size < 1) return plan
        return TimelineEdits.normalize(plan.copy(segments = plan.segments.filterIndexed { k, _ -> k !in drop }))
    }

    /**
     * Moves the clips at [indices] together, one place earlier ([by] = -1) or later (+1), past the
     * neighbouring clip. Returns the plan and where the moved clips are now.
     */
    fun moveClips(plan: StudioPlan, indices: Set<Int>, by: Int): Pair<StudioPlan, Set<Int>> {
        val clipIdx = plan.segments.indices.filter { clip(plan, it) != null }
        val chosen = indices.filter { it in clipIdx }.sorted()
        if (chosen.isEmpty()) return plan to indices
        val order = clipIdx.toMutableList()
        val pos = chosen.map { order.indexOf(it) }
        if (by < 0 && pos.first() == 0 || by > 0 && pos.last() == order.lastIndex) return plan to indices
        val block = chosen.toSet()
        val rest = order.filter { it !in block }
        val insertAt = (pos.first() + by).coerceIn(0, rest.size)
        val newOrder = rest.toMutableList().apply { addAll(insertAt, chosen) }
        val segs = plan.segments.toMutableList()
        clipIdx.forEachIndexed { k, slot -> segs[slot] = plan.segments[newOrder[k]] }
        val moved = chosen.indices.map { clipIdx[insertAt + it] }.toSet()
        return TimelineEdits.normalize(plan.copy(segments = segs)) to moved
    }

    /** A clip's settings to paste onto others: sound level, speed, turn, mirror and colour. */
    data class ClipSettings(val volume: Float, val speed: Float = 1f, val ramp: SpeedRamp = SpeedRamp.NONE, val rotation: Int = 0, val flip: Boolean = false, val color: ClipColor = ClipColor())

    fun copySettings(plan: StudioPlan, i: Int): ClipSettings? = clip(plan, i)?.let { ClipSettings(it.volume, it.speed, it.ramp, it.rotation, it.flip, it.color) }

    fun pasteSettings(plan: StudioPlan, indices: Set<Int>, s: ClipSettings): StudioPlan {
        var p = plan.copy(
            segments = plan.segments.mapIndexed { k, seg -> if (k in indices && seg is ClipSegment && !seg.tail) seg.copy(volume = s.volume, rotation = s.rotation, flip = s.flip, color = s.color) else seg },
        )
        // Speed changes their length, so it goes through the speed tool.
        indices.sorted().forEach { k ->
            val seg = clip(p, k) ?: return@forEach
            if (seg.still == null && (seg.speed != s.speed || seg.ramp != s.ramp)) p = ClipTools.ramp(ClipTools.speed(p, k, s.speed), k, s.ramp)
        }
        return p
    }

    // ---- markers -----------------------------------------------------------------------------

    fun addMarker(plan: StudioPlan, atMs: Long, id: String) =
        if (plan.markers.any { abs(it.atMs - atMs) < 150 }) plan else plan.copy(markers = (plan.markers + Marker(id, atMs.coerceIn(0, plan.totalMs))).sortedBy { it.atMs })

    fun deleteMarker(plan: StudioPlan, id: String) = plan.copy(markers = plan.markers.filter { it.id != id })

    /** After the clips change: layers, sounds and markers stay inside the Reel. */
    fun clamp(plan: StudioPlan): StudioPlan {
        val total = plan.totalMs
        val layers = plan.layers.mapNotNull { l ->
            if (l.startMs >= total - MIN_MS / 2) null else l.copy(durMs = minOf(l.durMs, total - l.startMs))
        }
        val audio = plan.audio.mapNotNull { a ->
            if (a.startMs >= total - MIN_MS / 2) null else a.copy(durMs = minOf(a.durMs, total - a.startMs))
        }
        val stickers = plan.stickers.mapNotNull { s -> if (s.startMs >= total - 250) null else s.copy(endMs = minOf(s.endMs, total)) }
        return plan.copy(layers = layers, audio = audio, markers = plan.markers.filter { it.atMs <= total }, stickers = stickers)
    }
}
