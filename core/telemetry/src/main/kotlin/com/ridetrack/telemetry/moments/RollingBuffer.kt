package com.ridetrack.telemetry.moments

/**
 * One encoded video or audio access unit. [wallMicros] is wall-clock time (µs since the
 * epoch) mapped from the encoder's own timestamp, so clips can be cut by ride time.
 */
class EncodedSample(
    val wallMicros: Long,
    val keyFrame: Boolean,
    val data: ByteArray,
)

/**
 * The last [capacityMicros] of encoded video + audio, kept in memory so a clip can start
 * *before* the event that triggered it. Video is trimmed in whole GOPs (keyframe to
 * keyframe) so what remains always decodes; audio frames are all independent.
 * Thread-safe: encoders append from their own threads while the writer extracts.
 */
class RollingBuffer(private val capacityMicros: Long = 45_000_000L) {
    private val video = ArrayDeque<EncodedSample>()
    private val audio = ArrayDeque<EncodedSample>()

    @get:Synchronized
    val bytes: Long get() = video.sumOf { it.data.size.toLong() } + audio.sumOf { it.data.size.toLong() }

    @Synchronized
    fun addVideo(s: EncodedSample) {
        video.addLast(s)
        trimVideo(s.wallMicros - capacityMicros)
    }

    @Synchronized
    fun addAudio(s: EncodedSample) {
        audio.addLast(s)
        val cutoff = s.wallMicros - capacityMicros
        while (audio.isNotEmpty() && audio.first().wallMicros < cutoff) audio.removeFirst()
    }

    /** Drops GOPs that end before [cutoff]: keep from the last keyframe at or before it. */
    private fun trimVideo(cutoff: Long) {
        var keep = -1
        for (i in video.indices) {
            val s = video[i]
            if (s.wallMicros > cutoff) break
            if (s.keyFrame) keep = i
        }
        repeat(keep.coerceAtLeast(0)) { video.removeFirst() }
        // Never start on a non-keyframe (e.g. right after the encoder started).
        while (video.isNotEmpty() && !video.first().keyFrame) video.removeFirst()
    }

    /**
     * Samples covering [fromMicros, toMicros]: video from the last keyframe at or before
     * the start (or the first keyframe after it, if the buffer starts later), audio from
     * that same moment. Empty video = nothing usable.
     */
    @Synchronized
    fun extract(fromMicros: Long, toMicros: Long): Pair<List<EncodedSample>, List<EncodedSample>> {
        var start = -1
        for (i in video.indices) {
            val s = video[i]
            if (s.keyFrame && s.wallMicros <= fromMicros) start = i
            if (s.wallMicros > fromMicros) break
        }
        if (start < 0) start = video.indexOfFirst { it.keyFrame && it.wallMicros >= fromMicros }
        if (start < 0) return emptyList<EncodedSample>() to emptyList()
        val v = video.drop(start).takeWhile { it.wallMicros <= toMicros }
        if (v.isEmpty()) return v to emptyList()
        val first = v.first().wallMicros
        val a = audio.filter { it.wallMicros in first..toMicros }
        return v to a
    }

    @Synchronized
    fun clear() {
        video.clear()
        audio.clear()
    }
}
