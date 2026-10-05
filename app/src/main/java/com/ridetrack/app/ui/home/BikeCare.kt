package com.ridetrack.app.ui.home

import java.util.UUID
import kotlin.math.roundToInt

/**
 * A rider-set reminder for one bike: every [everyKm] and/or every [everyDays], counted from
 * when it was last done. Stored in settings (so it's backed up) with [BikeCare.encode].
 */
data class CareItem(
    val id: String,
    val bikeId: String,
    val name: String,
    val everyKm: Int? = null,
    val everyDays: Int? = null,
    /** Odometer (km) and time when it was last done; null = counted from when it was added. */
    val doneAtKm: Double? = null,
    val doneAtMillis: Long,
) {
    val valid: Boolean get() = name.isNotBlank() && ((everyKm ?: 0) > 0 || (everyDays ?: 0) > 0)
}

/** Where a reminder stands now. [used] runs 0..1+ (1 = due); [text] is e.g. "Due in 80 km". */
data class CareStatus(val item: CareItem, val used: Float, val due: Boolean, val text: String)

object BikeCare {
    /** Starting points when adding a reminder; the rider can change the numbers. */
    val PRESETS = listOf(
        CareItem("", "", "Chain lube", everyKm = 500, doneAtMillis = 0),
        CareItem("", "", "Oil change", everyKm = 3_000, everyDays = 180, doneAtMillis = 0),
        CareItem("", "", "Tyre pressure", everyDays = 14, doneAtMillis = 0),
        CareItem("", "", "Service", everyKm = 5_000, everyDays = 365, doneAtMillis = 0),
    )

    fun new(bikeId: String, name: String, everyKm: Int?, everyDays: Int?, odometerKm: Double?, now: Long) =
        CareItem(UUID.randomUUID().toString(), bikeId, name.trim(), everyKm, everyDays, odometerKm, now)

    private const val DAY = 86_400_000L

    /**
     * Status from the bike's odometer now ([odometerKm], null = never set: km reminders can't
     * count) and the time. The closer of km and date wins.
     */
    fun status(item: CareItem, odometerKm: Double?, now: Long): CareStatus {
        val kmLeft = item.everyKm?.takeIf { it > 0 && odometerKm != null }?.let { every ->
            val since = item.doneAtKm ?: odometerKm!!
            every - (odometerKm!! - since)
        }
        val daysLeft = item.everyDays?.takeIf { it > 0 }?.let { every -> every - (now - item.doneAtMillis).toDouble() / DAY }
        val kmUsed = kmLeft?.let { 1 - it / item.everyKm!! }
        val dayUsed = daysLeft?.let { 1 - it / item.everyDays!! }
        val used = listOfNotNull(kmUsed, dayUsed).maxOrNull()?.toFloat() ?: 0f
        val due = (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0)
        val text = when {
            due && kmLeft != null && kmLeft <= 0 -> "Due now · ${km(-kmLeft)} over"
            due -> "Due now"
            kmLeft != null && daysLeft != null -> "In ${km(kmLeft)} · or by ${dayText(now + (daysLeft * DAY).toLong())}"
            kmLeft != null -> "Due in ${km(kmLeft)}"
            daysLeft != null -> if (daysLeft < 1) "Due today" else "Due in ${daysLeft.toInt()} ${if (daysLeft.toInt() == 1) "day" else "days"}"
            else -> "Set the odometer to count km"
        }
        return CareStatus(item, used, due, text)
    }

    /**
     * Reminders added before the bike had an odometer start counting km from the first reading;
     * null when nothing changes.
     */
    fun anchor(items: List<CareItem>, odometers: Map<String, Double?>): List<CareItem>? {
        var changed = false
        val out = items.map { i ->
            val odo = odometers[i.bikeId]
            if (i.doneAtKm == null && i.everyKm != null && odo != null) {
                changed = true
                i.copy(doneAtKm = odo)
            } else {
                i
            }
        }
        return out.takeIf { changed }
    }

    /** Due first, then the closest. */
    fun sorted(items: List<CareItem>, odometerKm: Double?, now: Long): List<CareStatus> =
        items.map { status(it, odometerKm, now) }.sortedByDescending { it.used }

    fun km(v: Double): String = String.format(java.util.Locale.US, "%,d km", v.roundToInt().coerceAtLeast(0))

    private fun dayText(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))

    // Stored as one line per item: id|bikeId|name|everyKm|everyDays|doneAtKm|doneAtMillis.
    fun encode(items: List<CareItem>): String = items.joinToString("\n") { i ->
        listOf(i.id, i.bikeId, i.name.replace('|', '/').replace('\n', ' '), i.everyKm ?: "", i.everyDays ?: "", i.doneAtKm ?: "", i.doneAtMillis).joinToString("|")
    }

    fun decode(value: String?): List<CareItem> = value.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 7) return@mapNotNull null
        CareItem(
            id = p[0],
            bikeId = p[1],
            name = p[2],
            everyKm = p[3].toIntOrNull(),
            everyDays = p[4].toIntOrNull(),
            doneAtKm = p[5].toDoubleOrNull(),
            doneAtMillis = p[6].toLongOrNull() ?: return@mapNotNull null,
        ).takeIf { it.valid }
    }
}
