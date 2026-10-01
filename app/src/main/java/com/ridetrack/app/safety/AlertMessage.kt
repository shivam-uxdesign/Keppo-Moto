package com.ridetrack.app.safety

import com.ridetrack.app.data.MedicalInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** What we know about a possible crash, for the alert screen and the text. */
data class CrashReport(
    val timeMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Double?,
    val speedBeforeMps: Double?,
    val bikeName: String,
    val riderName: String,
    val batteryPercent: Int?,
    val medical: MedicalInfo,
)

/**
 * The SMS texts. Plain ASCII on purpose: one symbol like ± switches the whole text to
 * Unicode (70 characters per part instead of 160), and some phones show it garbled.
 */
object AlertMessage {
    private val time = DateTimeFormatter.ofPattern("h:mm a, d MMM", Locale.US)

    fun mapsLink(lat: Double, lon: Double): String = String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", lat, lon)

    fun crash(r: CrashReport, zone: ZoneId = ZoneId.systemDefault(), test: Boolean = false): String = buildString {
        append("Keppo Moto: ")
        append(if (test) "(test) this is how a crash alert looks. " else "possible crash. ")
        append(r.riderName.ifBlank { "The rider" })
        if (r.bikeName.isNotBlank()) append(" on ").append(r.bikeName)
        append(" at ").append(time.format(Instant.ofEpochMilli(r.timeMillis).atZone(zone))).append('.')
        r.speedBeforeMps?.let { append(" Last speed ").append((it * 3.6).roundToInt()).append(" km/h.") }
        if (r.latitude != null && r.longitude != null) {
            append(" Location: ").append(mapsLink(r.latitude, r.longitude))
            r.accuracyM?.let { append(" (within ").append(it.roundToInt()).append(" m)") }
            append('.')
        } else {
            append(" Location unknown (no GPS).")
        }
        r.batteryPercent?.let { append(" Battery ").append(it).append("%.") }
        medical(r.medical)?.let { append(' ').append(it) }
    }

    fun update(lat: Double, lon: Double, timeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "Keppo Moto: updated location at ${time.format(Instant.ofEpochMilli(timeMillis).atZone(zone))}: ${mapsLink(lat, lon)}"

    fun allClear(riderName: String): String = "Keppo Moto: ${riderName.ifBlank { "The rider" }} says they're OK. Sorry for the scare."

    private fun medical(m: MedicalInfo): String? {
        val parts = listOfNotNull(
            m.bloodGroup?.let { "Blood group ${it.replace('−', '-')}" },
            m.allergies.trim().takeIf { it.isNotEmpty() }?.let { "allergies: $it" },
            m.notes.trim().takeIf { it.isNotEmpty() },
        )
        return if (parts.isEmpty()) null else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}
