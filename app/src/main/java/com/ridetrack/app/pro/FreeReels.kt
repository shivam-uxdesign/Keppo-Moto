package com.ridetrack.app.pro

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The free rider's one Reel a week: it's used when a Reel is made, and comes back 7 days after.
 * Kept on the phone for now; the server will count it too (so reinstalling doesn't reset it).
 */
class FreeReels(context: Context) {
    private val prefs = context.getSharedPreferences("free_reels", Context.MODE_PRIVATE)
    private val _usedAt = MutableStateFlow(prefs.getLong(USED_AT, 0L).takeIf { it > 0 })

    /** When the last free Reel was made; null if never. */
    val usedAt: StateFlow<Long?> = _usedAt.asStateFlow()

    fun available(now: Long = System.currentTimeMillis()): Boolean = FreeReelRule.available(_usedAt.value, now)

    /** When the next free Reel comes; null when one is free now. */
    fun nextAt(now: Long = System.currentTimeMillis()): Long? = FreeReelRule.nextAt(_usedAt.value, now)

    fun markUsed(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(USED_AT, now).apply()
        _usedAt.value = now
    }

    /** Test builds: a free Reel again now. */
    fun reset() {
        prefs.edit().remove(USED_AT).apply()
        _usedAt.value = null
    }

    private companion object {
        const val USED_AT = "usedAt"
    }
}

/** The weekly free Reel's rule. Pure, unit-tested. */
object FreeReelRule {
    const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    fun available(usedAt: Long?, now: Long): Boolean = usedAt == null || now - usedAt >= WEEK_MS || now < usedAt

    fun nextAt(usedAt: Long?, now: Long): Long? = if (available(usedAt, now)) null else usedAt!! + WEEK_MS

    /** "in 3 days", "tomorrow", "in 5 hours". */
    fun wait(nextAt: Long, now: Long): String {
        val ms = (nextAt - now).coerceAtLeast(0)
        val hours = (ms + 3_599_999) / 3_600_000
        val days = hours / 24
        return when {
            hours <= 1 -> "within the hour"
            hours < 24 -> "in $hours hours"
            days == 1L -> "tomorrow"
            else -> "in $days days"
        }
    }
}
