package com.ridetrack.app.hud

import com.ridetrack.app.moments.MomentSource
import com.ridetrack.app.moments.MomentState
import com.ridetrack.app.moments.MomentStatus
import com.ridetrack.app.ride.ActiveRide
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.GpsQuality
import com.ridetrack.telemetry.model.TelemetryFrame

/**
 * What the ride is doing: recording, auto-paused at a stop, paused by the rider, GPS lost,
 * or on a break (a long stop off the bike).
 */
enum class HudStatus { RECORDING, STOPPED, PAUSED, GPS_LOST, BREAK }

/** A video being filmed (your record button, or GPS lost), as the pop-up shows it. */
data class HudVideo(val source: MomentSource, val starting: Boolean, val paused: Boolean, val elapsedMillis: Long)

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
    val video: HudVideo? = null,
    /** "Saved to moments · 0:42" for a moment after a video stops. */
    val savedNote: String? = null,
    /** Start (with look-back) of the event clip being filmed. */
    val clipStartMillis: Long? = null,
    /** A photo is coming at this time. */
    val photoAtMillis: Long? = null,
    val photoTakenAtMillis: Long? = null,
    /** The clock the three above are on (the ride's). */
    val nowMillis: Long = 0,
    /** The chosen mic (its name) dropped out; the phone mic is recording instead. */
    val micFallback: String? = null,
) {
    companion object {
        fun from(
            frame: TelemetryFrame?,
            active: ActiveRide?,
            paused: Boolean,
            stoppedForMillis: Long?,
            camera: CameraIndicator = CameraIndicator.OFF,
            manuallyPaused: Boolean = false,
            video: HudVideo? = null,
            savedNote: String? = null,
            moments: MomentState? = null,
            nowMillis: Long = frame?.timeMillis ?: 0,
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
                    frame?.onBreak == true -> HudStatus.BREAK
                    paused -> HudStatus.STOPPED
                    gpsLost -> HudStatus.GPS_LOST
                    else -> HudStatus.RECORDING
                },
                stoppedForMillis = if (frame?.onBreak == true) {
                    frame.breakStartMillis?.let { frame.timeMillis - it }
                } else {
                    stoppedForMillis.takeIf { paused }
                },
                speedMps = frame?.speedMps,
                leanDeg = frame?.leanDeg,
                leanNote = when {
                    active?.sensors?.canEstimateLean == false -> "No gyroscope"
                    frame?.leanDeg == null && active?.calibrated == false -> "Learning mount…"
                    else -> null
                },
                distanceM = stats?.distanceM,
                // Riding time: breaks don't count.
                elapsedMillis = frame?.let { (it.elapsedMillis - it.stats.breakMillis).coerceAtLeast(0) },
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
                video = video,
                savedNote = savedNote,
                clipStartMillis = moments?.clipStartMillis,
                photoAtMillis = moments?.photoAtMillis,
                photoTakenAtMillis = moments?.photoTakenAtMillis,
                micFallback = moments?.micFallback,
                nowMillis = nowMillis,
            )
        }
    }
}
