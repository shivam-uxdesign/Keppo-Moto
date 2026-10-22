package com.ridetrack.app.moments

import java.io.File
import java.util.Locale

/** One audio chunk's raw readings that only the audio thread has, for [VoiceTrace]. */
class TraceAudio(
    val timeMillis: Long,
    /** The voice mic's 1–4 kHz level (dBFS). */
    val highDb: Float,
    /** The engine mic's 1–4 kHz level (dBFS); null with one mic. */
    val engineHighDb: Float?,
    /** The voice detector on the voice mic with the wind rumble (below 250 Hz) taken out; null when not run. */
    val voiceDewind: Float?,
    /** How independent the two sides are (0 = one mic copied, 1 = two mics); null with one mic. */
    val independence: Double?,
    /** Share of the chunk's samples at full scale (wind overload). */
    val clipped: Float,
    /** Each side's whole-band level (dBFS); null with one mic. */
    val leftDb: Float?,
    val rightDb: Float?,
)

/**
 * Every chunk of "Film when I speak" (about 20 a second) as a row of a CSV in the ride's
 * folder (voice-trace.csv), tester builds only, exported with the ride. The raw readings let
 * any version of the speech rules be replayed on a real ride and scored. Buffered; written
 * every few seconds.
 */
class VoiceTrace(private val file: File) {
    private val pending = StringBuilder()
    private var flushedAt = 0L

    init {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            runCatching { file.writeText(HEADER + "\n") }
        }
    }

    @Synchronized
    fun row(
        a: TraceAudio,
        chunk: VoiceChunk?,
        speedKmh: Float?,
        wind: Float,
        engineVoice: Float?,
        twoKind: TwoMicKind?,
        speaking: Boolean,
    ) {
        fun f(v: Float?, d: Int = 1) = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.${d}f", it) } ?: ""
        pending.append(a.timeMillis).append(',')
            .append(chunk?.chunkMillis ?: "").append(',')
            .append(f(speedKmh)).append(',')
            .append(f(chunk?.levelDb)).append(',')
            .append(f(chunk?.backgroundDb)).append(',')
            .append(f(chunk?.voice, 2)).append(',')
            .append(f(a.voiceDewind, 2)).append(',')
            .append(f(a.highDb)).append(',')
            .append(f(a.engineHighDb)).append(',')
            .append(f(a.leftDb)).append(',')
            .append(f(a.rightDb)).append(',')
            .append(f(engineVoice, 2)).append(',')
            .append(twoKind?.name ?: "").append(',')
            .append(f(a.independence?.toFloat(), 2)).append(',')
            .append(f(chunk?.liftDb)).append(',')
            .append(f(chunk?.highLiftDb)).append(',')
            .append(f(wind, 2)).append(',')
            .append(f(a.clipped * 100f, 2)).append(',')
            .append(if (speaking) 1 else 0).append('\n')
        val now = a.timeMillis
        if (now - flushedAt >= FLUSH_EVERY_MILLIS) flush(now)
    }

    @Synchronized
    fun flush(now: Long = System.currentTimeMillis()) {
        if (pending.isEmpty()) return
        runCatching {
            file.appendText(pending.toString())
            pending.setLength(0)
            flushedAt = now
        }
    }

    companion object {
        const val FILE_NAME = "voice-trace.csv"
        private const val FLUSH_EVERY_MILLIS = 5_000L
        const val HEADER = "t_ms,chunk_ms,speed_kmh,level_db,background_db,voice,voice_dewind,high_db,engine_high_db," +
            "left_db,right_db,engine_voice,two_mics,independence,lift_db,high_lift_db,wind,clipped_pct,speaking"
    }
}
