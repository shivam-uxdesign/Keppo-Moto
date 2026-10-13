package com.ridetrack.app.studio

/**
 * "Sound on 🔊" pieces: a fast stretch with only the engine's sound (the DJI's engine channel when
 * there is one, else the clip's own) and the speed on screen, no talking. Pure, unit-tested.
 */
object SoundOn {
    const val SHAPE = "sound_on"

    /** Fast enough to be worth hearing. */
    const val MIN_KMH = 35

    private const val MIN_MS = 8_000L
    private const val MAX_MS = 15_000L

    /** Up to [n] pieces from the fastest clips, each a quiet stretch (no words) of 8–15 s. */
    fun scripts(footage: List<Footage>, n: Int = 2): List<Script> = footage
        .filter { it.kmhMax >= MIN_KMH && it.look != "phone" }
        .sortedByDescending { it.kmhMax }
        .mapNotNull { f -> quiet(f)?.let { (a, b) -> f to ScriptShot(f.momentId, a, b) } }
        .take(n)
        .map { (f, shot) ->
            Script(
                format = PieceFormat.SHORT,
                title = "Sound on · ${f.kmhMax} km/h",
                why = "Just the engine: a fast stretch, no talking",
                lengthSec = ((shot.durMs + 999) / 1000).toInt().coerceIn(PieceFormat.SHORT.minSec, PieceFormat.SHORT.maxSec),
                shape = SHAPE,
                hookLine = null,
                postCaption = "Sound on 🔊\n#motovlog #bikelife #exhaustsound #ridersofindia",
                sections = listOf(Section(SectionKind.PEAK, "ride", listOf(shot))),
                vibe = Vibe.HYPE,
            )
        }

    /** The longest stretch of [f] without words, up to 15 s (from its middle); null under 8 s. */
    fun quiet(f: Footage): Pair<Long, Long>? {
        val edges = listOf(0L) + f.lines.sortedBy { it.startMs }.flatMap { listOf(it.startMs - 300, it.endMs + 300) } + f.durationMs
        val gaps = edges.chunked(2).mapNotNull { (a, b) -> (a.coerceAtLeast(0) to b.coerceAtMost(f.durationMs)).takeIf { it.second - it.first >= MIN_MS } }
        val (a, b) = gaps.maxByOrNull { it.second - it.first } ?: return null
        val len = minOf(b - a, MAX_MS)
        val start = a + (b - a - len) / 2
        return start to start + len
    }

    /**
     * [plan] as a Sound on piece: each clip's voice muted where its engine sound ([engine]: moment
     * id → file) plays instead, nothing ducked, no captions, and the speed on screen.
     */
    fun apply(plan: StudioPlan, engine: Map<String, String>, newId: () -> String): StudioPlan {
        var p = plan.copy(
            segments = plan.segments.map { s -> if (s is ClipSegment && s.bit.momentId in engine) s.copy(volume = 0f, lines = emptyList()) else if (s is ClipSegment) s.copy(lines = emptyList()) else s },
            mix = plan.mix.copy(duck = false),
        )
        if (engine.isNotEmpty()) p = TrackEdits.addEngine(p, engine, newId, volume = 1f)
        if (p.stickers.none { it.kind == StickerKind.SPEED }) p = TextTools.addSticker(p, StickerKind.SPEED, 0, newId())
        return p
    }
}
