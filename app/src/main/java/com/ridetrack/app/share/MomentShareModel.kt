package com.ridetrack.app.share

import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.moments.TelemetryPoint
import kotlin.math.abs

/** Details that can be switched on or off on a moment graphic. */
enum class MomentField(val label: String) {
    EVENT("Event"),
    TIME("Time"),
    MAP("Map"),
    SPEED("Speed"),
    LEAN("Lean"),
    G_FORCE("G-force"),
    BRAND("Ride name"),
    ;

    companion object {
        val DEFAULT: Set<MomentField> = entries.toSet()
    }
}

enum class MomentLayout(val label: String, val hint: String) {
    MINIMAL("Minimal", "A small cluster in the corner"),
    BAR("Strip", "A strip of live numbers"),
    HUD("HUD", "Gauges, like the bike's dash"),
}

/**
 * What a moment graphic shows at one instant. Values are null when unknown; a row is only
 * drawn when it's switched on *and* there is something true to show.
 */
data class MomentOverlay(
    val eventTypes: Set<RideEventType>,
    val eventValue: Double?,
    val timeText: String,
    val dateText: String,
    val rideName: String?,
    val point: TelemetryPoint?,
    /** Route of the whole ride, for the mini-map. */
    val route: List<Pair<Double, Double>>,
    val demo: Boolean,
) {
    val speedMps: Double? get() = point?.speedMps
    val leanDeg: Double? get() = point?.leanDeg

    /** Longitudinal G worth showing: an accel/brake moment, or anything beyond noise. */
    val gForce: Double?
        get() {
            val g = point?.longitudinalG ?: return null
            val eventG = RideEventType.HARD_BRAKE in eventTypes || RideEventType.STRONG_ACCELERATION in eventTypes
            return g.takeIf { eventG || abs(it) >= MIN_G }
        }

    /** Lean worth showing: known and more than a wobble. */
    val lean: Double? get() = leanDeg?.takeIf { abs(it) >= MIN_LEAN_DEG }

    val eventLabel: String?
        get() {
            val parts = eventTypes.sortedBy { it.ordinal }.mapNotNull {
                when (it) {
                    RideEventType.HARD_BRAKE -> "Hard braking"
                    RideEventType.STRONG_ACCELERATION -> "Strong acceleration"
                    RideEventType.SIGNIFICANT_LEAN -> "Deep lean"
                    RideEventType.VOICE -> "Talking"
                    else -> null
                }
            }
            return parts.joinToString(" + ").ifEmpty { null }
        }

    fun shows(field: MomentField, on: Set<MomentField>): Boolean = field in on && when (field) {
        MomentField.EVENT -> eventLabel != null
        MomentField.TIME -> true
        MomentField.MAP -> route.size >= 2 && point?.latitude != null && point.longitude != null
        MomentField.SPEED -> speedMps != null
        MomentField.LEAN -> lean != null
        MomentField.G_FORCE -> gForce != null
        MomentField.BRAND -> true
    }

    companion object {
        const val MIN_G = 0.15
        const val MIN_LEAN_DEG = 3.0
    }
}
