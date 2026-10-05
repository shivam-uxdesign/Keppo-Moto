package com.ridetrack.app.ui.home

import com.ridetrack.app.moments.MicChoice
import com.ridetrack.app.moments.MicType
import com.ridetrack.app.moments.Microphones

/** What a readiness problem's button does. */
enum class ReadyFix { LOCATION_SETTINGS, APP_SETTINGS, OVERLAY }

enum class CheckState { OK, WAIT, PROBLEM }

/** One pre-ride check. A [PROBLEM][CheckState.PROBLEM] carries what it means and how to fix it. */
data class ReadyCheck(
    val label: String,
    val state: CheckState,
    val problem: String? = null,
    val detail: String? = null,
    val fixLabel: String? = null,
    val fix: ReadyFix? = null,
)

/** What the phone knows before a ride. Read on the main thread, judged by [Readiness.checks]. */
data class ReadyInputs(
    val demo: Boolean = false,
    val gps: GpsReadiness = GpsReadiness.READY,
    val calibrated: Boolean = true,
    val momentsOn: Boolean = false,
    val micChoice: MicChoice = MicChoice.AUTO,
    /** External mics connected now (no phone mic). */
    val mics: List<MicChoice> = emptyList(),
    val cameraAllowed: Boolean = true,
    val hudOn: Boolean = false,
    val overlayAllowed: Boolean = true,
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    val freeBytes: Long? = null,
)

object Readiness {
    const val LOW_BATTERY = 20
    private const val GB = 1_000_000_000L

    /** Problems first, then the rest, in the order riders look for them. */
    fun checks(i: ReadyInputs): List<ReadyCheck> {
        val list = ArrayList<ReadyCheck>()
        if (i.momentsOn) list += mic(i)
        list += when {
            i.demo -> ReadyCheck("GPS simulated", CheckState.OK)
            i.gps == GpsReadiness.READY -> ReadyCheck("GPS", CheckState.OK)
            i.gps == GpsReadiness.PERMISSION_NEEDED -> ReadyCheck("GPS asks at start", CheckState.WAIT)
            i.gps == GpsReadiness.DISABLED -> ReadyCheck(
                "GPS", CheckState.PROBLEM, "Location is off", "Speed, distance and the route need it.", "Turn on location", ReadyFix.LOCATION_SETTINGS,
            )
            else -> ReadyCheck("GPS", CheckState.PROBLEM, "This phone has no GPS", "Rides can't be recorded here. Try Demo mode in Profile.")
        }
        list += if (i.calibrated) ReadyCheck("Mount", CheckState.OK) else ReadyCheck("Mount calibrates on the ride", CheckState.WAIT)
        if (i.momentsOn) {
            list += if (i.cameraAllowed) ReadyCheck("Camera", CheckState.OK)
            else ReadyCheck("Camera", CheckState.PROBLEM, "Camera not allowed", "Moments can't film without it.", "Allow camera", ReadyFix.APP_SETTINGS)
        }
        if (i.hudOn) {
            list += if (i.overlayAllowed) ReadyCheck("Pop-up", CheckState.OK)
            else ReadyCheck("Pop-up", CheckState.PROBLEM, "Pop-up not allowed", "The ride pop-up can't show over your maps app.", "Allow pop-up", ReadyFix.OVERLAY)
        }
        i.batteryPct?.let { pct ->
            list += when {
                i.charging -> ReadyCheck("Battery $pct% · charging", CheckState.OK)
                pct < LOW_BATTERY -> ReadyCheck(
                    "Battery $pct%", CheckState.PROBLEM, "Battery $pct%",
                    "GPS${if (i.momentsOn) " and filming" else ""} use a lot. Charge, or plug into the bike.",
                )
                else -> ReadyCheck("Battery $pct%", CheckState.OK)
            }
        }
        i.freeBytes?.let { free ->
            val need = if (i.momentsOn) 2 * GB else GB / 2
            val label = if (free >= 10 * GB) "${free / GB} GB free" else String.format(java.util.Locale.US, "%.1f GB free", free.toDouble() / GB)
            list += if (free >= need) ReadyCheck(label, CheckState.OK)
            else ReadyCheck(label, CheckState.PROBLEM, "Phone storage almost full", "$label. Clips stop recording when it runs out.")
        }
        return list.sortedBy { if (it.state == CheckState.PROBLEM) 0 else 1 }
    }

    private fun mic(i: ReadyInputs): ReadyCheck {
        val c = i.micChoice
        return when (c.type) {
            MicType.PHONE -> ReadyCheck("Phone mic", CheckState.OK)
            MicType.AUTO -> {
                val best = autoMic(i.mics)
                if (best == null) ReadyCheck("Phone mic", CheckState.WAIT) else ReadyCheck(best.name ?: best.type.label, CheckState.OK)
            }
            else -> {
                val name = c.name?.takeIf { it.isNotBlank() } ?: "${c.type.label} mic"
                if (i.mics.any { it.type == c.type }) ReadyCheck(name, CheckState.OK)
                else ReadyCheck(
                    name, CheckState.PROBLEM, "$name not connected",
                    "Clips will record with the phone mic. ${if (c.type == MicType.BLUETOOTH) "Turn it on and pair it." else "Plug in the receiver."}",
                )
            }
        }
    }

    /** The external mic Automatic would pick, null = the phone. */
    private fun autoMic(mics: List<MicChoice>): MicChoice? =
        Microphones.pick(mics, MicChoice.AUTO).takeIf { it.type != MicType.PHONE }

    /** "Ready to ride · DJI MIC · GPS · Mount" — the quiet one-liner when nothing's wrong. */
    fun summary(checks: List<ReadyCheck>): String =
        (listOf("Ready to ride") + checks.take(3).map { it.label }).joinToString(" · ")
}
