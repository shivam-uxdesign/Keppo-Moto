package com.ridetrack.app.transcribe

import android.content.Context
import android.content.SharedPreferences
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Remembers which Gemini models are over their free allowance and until when ("Please retry in
 * 18h46m11s"), so the app doesn't spend requests asking again before the limit resets.
 */
object GeminiQuota {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("gemini_quota", Context.MODE_PRIVATE)
    }

    /** This model is over its limit until a time still to come. */
    fun blocked(model: String, now: Long = System.currentTimeMillis()): Boolean = (prefs?.getLong(model, 0L) ?: 0L) > now

    /** The earliest time any of [models] is free again; null if one is free now. */
    fun freeAt(models: List<String>, now: Long = System.currentTimeMillis()): Long? =
        models.map { prefs?.getLong(it, 0L) ?: 0L }.takeIf { all -> all.isNotEmpty() && all.all { it > now } }?.min()

    /** Marks [model] as over its limit, until the time Google's error gives (or the daily reset). */
    fun block(model: String, message: String?, now: Long = System.currentTimeMillis()) {
        val until = now + (retryAfterMs(message.orEmpty()) ?: if (FirebaseTranscriber.isDailyText(message.orEmpty())) untilPacificMidnight(now) else 60_000L)
        prefs?.edit()?.putLong(model, until)?.apply()
    }

    /** "Please retry in 18h46m11.73s" or "retryDelay": "67s" → ms. */
    fun retryAfterMs(message: String): Long? {
        val m = Regex("retry in ((?:\\d+h)?(?:\\d+m)?(?:[\\d.]+s)?)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)
            ?: Regex("retryDelay\\W+([\\d.]+s)").find(message)?.groupValues?.get(1)
            ?: return null
        var ms = 0.0
        Regex("([\\d.]+)([hms])").findAll(m).forEach { p ->
            val v = p.groupValues[1].toDoubleOrNull() ?: return@forEach
            ms += when (p.groupValues[2]) { "h" -> v * 3_600_000; "m" -> v * 60_000; else -> v * 1_000 }
        }
        return ms.toLong().takeIf { it > 0 }
    }

    /** Models captions (and background transcripts) can use; story and tips use the full ones. */
    val CAPTIONS: List<String> get() = FirebaseTranscriber.LITE + FirebaseTranscriber.MODELS
    val STORY: List<String> get() = FirebaseTranscriber.MODELS

    /** "5:29 AM" in the phone's time zone. Pure. */
    fun clock(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(java.time.Instant.ofEpochMilli(at).atZone(zone))

    /** "3 h 12 min", "12 min", "1 min". Pure. */
    fun left(at: Long, now: Long): String {
        val min = ((at - now + 59_999) / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    /**
     * The live line for "Gemini is busy": when captions and the story/tips are free again, null
     * when both are free now. Models run out separately, so they can differ. Pure.
     */
    fun waitLine(captionsAt: Long?, storyAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val c = captionsAt?.takeIf { it > now }
        val s = storyAt?.takeIf { it > now }
        fun at(t: Long) = "${clock(t, zone)} · in ${left(t, now)}"
        return when {
            c == null && s == null -> null
            c != null && s != null && kotlin.math.abs(c - s) < 5 * 60_000 -> "Gemini is free again at ${at(maxOf(c, s))}"
            c != null && s != null -> "Captions free again at ${at(c)}; story and tips at ${clock(s, zone)}"
            c != null -> "Captions free again at ${at(c)}"
            else -> "Story and tips free again at ${at(s!!)}"
        }
    }

    /** "about 19 h", "about 40 min" — how long until [at]. */
    fun waitText(at: Long, now: Long = System.currentTimeMillis()): String {
        val min = ((at - now) / 60_000).coerceAtLeast(1)
        return if (min >= 90) "about ${(min + 30) / 60} h" else "about $min min"
    }

    private fun untilPacificMidnight(now: Long): Long {
        val pt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.of("America/Los_Angeles"))
        return pt.toLocalDate().plusDays(1).atStartOfDay(pt.zone).toInstant().toEpochMilli() - now
    }
}
