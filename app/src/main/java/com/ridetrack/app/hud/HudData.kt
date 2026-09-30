package com.ridetrack.app.hud

import com.ridetrack.app.moments.MomentState
import com.ridetrack.app.moments.MomentStatus
import com.ridetrack.app.ride.ActiveRide
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.GpsQuality
import com.ridetrack.telemetry.model.TelemetryFrame

/**
 * What the ride is doing: recording, auto-paused at a stop, paused by the rider, GPS lost,
 * or no ride at all (the pop-up waiting with its record button).
 */
enum class HudStatus { RECORDING, STOPPED, PAUSED, GPS_LOST, IDLE }

/** The Moments camera, as the rider should see it: quiet while buffering, REC while saving. */
enum class CameraIndicator {
    OFF, ON, REC;

    companion object {
        fun from(s: MomentState): CameraIndicator = when {
            s.status == MomentStatus.OFF || s.status == MomentStatus.PAUSED -> OFF
            s.saving -> REC
            else -> ON
        }
    }
}

/** Everything the pop-up shows, already reduced to what's known. Null = unknown. */
data class HudData(
    val status: HudStatus,
    val stoppedForMillis: Long?,
    val speedMps: Double?,
    val leanDeg: Double?,
    /** Why lean is missing, when it is. */
    val leanNote: String?,
    val distanceM: Double?,
    val elapsedMillis: Long?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val longitudinalG: Double?,
    val combinedG: Double?,
    /** Signed max lean (+ right), the larger side. */
    val maxLeanDeg: Double?,
    val headingDeg: Double?,
    val demo: Boolean,
    /** From OBD; the rev bar only appears when this is known. */
    val rpm: Double? = null,
    val gear: Int? = null,
    val redlineRpm: Int? = null,
    val camera: CameraIndicator = CameraIndicator.OFF,
) {
    companion object {
        fun from(
            frame: TelemetryFrame?,
            active: ActiveRide?,
            paused: Boolean,
            stoppedForMillis: Long?,
            camera: CameraIndicator = CameraIndicator.OFF,
            manuallyPaused: Boolean = false,
        ): HudData {
            val stats = frame?.stats
            val left = stats?.maxLeftLeanDeg
            val right = stats?.maxRightLeanDeg
            val maxLean = when {
                left == null && right == null -> null
                (right ?: 0.0) >= (left ?: 0.0) -> right
                else -> left?.let { -it }
            }
            val gpsLost = active?.source == DataSourceKind.PHONE &&
                (frame?.gpsQuality == GpsQuality.LOST || frame?.gpsQuality == GpsQuality.UNAVAILABLE)
            return HudData(
                status = when {
                    manuallyPaused -> HudStatus.PAUSED
                    paused -> HudStatus.STOPPED
                    gpsLost -> HudStatus.GPS_LOST
                    else -> HudStatus.RECORDING
                },
                stoppedForMillis = stoppedForMillis.takeIf { paused },
                speedMps = frame?.speedMps,
                leanDeg = frame?.leanDeg,
                leanNote = when {
                    active?.sensors?.canEstimateLean == false -> "No gyroscope"
                    frame?.leanDeg == null && active?.calibrated == false -> "Learning mount…"
                    else -> null
                },
                distanceM = stats?.distanceM,
                elapsedMillis = frame?.elapsedMillis,
                avgSpeedMps = stats?.avgSpeedMps,
                maxSpeedMps = stats?.maxSpeedMps,
                longitudinalG = frame?.longitudinalG,
                combinedG = frame?.combinedG,
                maxLeanDeg = maxLean,
                headingDeg = frame?.headingDeg,
                demo = active?.source == DataSourceKind.DEMO,
                rpm = frame?.rpm,
                gear = frame?.gear,
                redlineRpm = active?.redlineRpm,
                camera = camera,
            )
        }

        /** No ride: the pop-up stays up with its record button. */
        fun idle(): HudData = HudData(
            status = HudStatus.IDLE, stoppedForMillis = null, speedMps = null, leanDeg = null, leanNote = null,
            distanceM = null, elapsedMillis = null, avgSpeedMps = null, maxSpeedMps = null, longitudinalG = null,
            combinedG = null, maxLeanDeg = null, headingDeg = null, demo = false,
        )
    }
}
