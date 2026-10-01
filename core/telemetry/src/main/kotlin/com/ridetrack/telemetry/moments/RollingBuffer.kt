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
    /** Gets every new sample (true = video) while a long recording streams out of the buffer. */
    private var tap: ((Boolean, EncodedSample) -> Unit)? = null

    @get:Synchronized
    val bytes: Long get() = video.sumOf { it.data.size.toLong() } + audio.sumOf { it.data.size.toLong() }

    @Synchronized
    fun addVideo(s: EncodedSample) {
        video.addLast(s)
        trimVideo(s.wallMicros - capacityMicros)
        tap?.invoke(true, s)
    }

    @Synchronized
    fun addAudio(s: EncodedSample) {
        audio.addLast(s)
        val cutoff = s.wallMicros - capacityMicros
        while (audio.isNotEmpty() && audio.first().wallMicros < cutoff) audio.removeFirst()
        tap?.invoke(false, s)
    }

    /**
     * Starts streaming: [seed] gets what's buffered from [fromMicros] on (as [extract]), then
     * [onSample] every sample after it, with nothing lost or repeated in between. False when
     * the buffer has no usable video yet (nothing is tapped then).
     */
    @Synchronized
    fun startTap(fromMicros: Long, seed: (List<EncodedSample>, List<EncodedSample>) -> Unit, onSample: (Boolean, EncodedSample) -> Unit): Boolean {
        val (v, a) = extract(fromMicros, Long.MAX_VALUE)
        if (v.isEmpty()) return false
        seed(v, a)
        tap = onSample
        return true
    }

    @Synchronized
    fun stopTap() {
        tap = null
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

    /** Sample counts and the video time span, for diagnostics. */
    @Synchronized
    fun describe(): String {
        val span = if (video.size > 1) (video.last().wallMicros - video.first().wallMicros) / 1_000_000.0 else 0.0
        return "video=${video.size} audio=${audio.size} span=${"%.1f".format(java.util.Locale.US, span)}s " +
            "bytes=${(video.sumOf { it.data.size.toLong() } + audio.sumOf { it.data.size.toLong() }) / 1024}KB"
    }

    companion object {
        /**
         * Video and audio merged in time order (video first on ties), which is how a muxer
         * expects to receive them. true = video.
         */
        fun interleave(video: List<EncodedSample>, audio: List<EncodedSample>): List<Pair<Boolean, EncodedSample>> {
            val out = ArrayList<Pair<Boolean, EncodedSample>>(video.size + audio.size)
            var v = 0
            var a = 0
            while (v < video.size || a < audio.size) {
                val takeVideo = a >= audio.size || (v < video.size && video[v].wallMicros <= audio[a].wallMicros)
                out += if (takeVideo) true to video[v++] else false to audio[a++]
            }
            return out
        }
    }
}

/** One line of the Moments diagnostics log: UTC time, then the message. */
fun momentLogLine(wallMillis: Long, message: String): String =
    java.time.Instant.ofEpochMilli(wallMillis).toString().substring(11, 23) + "Z  " + message.replace('\n', ' ')
