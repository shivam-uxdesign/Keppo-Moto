package com.ridetrack.telemetry.model

/** Where the mount calibration used by the current ride came from. */
enum class CalibrationStatus {
    /** No calibration yet; lean angle is unavailable until one is learned or captured. */
    NONE,
    /** Carried over from a previous ride or the Bike screen; may be re-learned. */
    SAVED,
    /** Learned during this ride while riding straight at a steady speed. */
    AUTO,
    /** Captured during this ride with "Calibrate now". */
    MANUAL,
}

enum class CaptureOutcome { SUCCESS, TOO_MUCH_MOTION, IMPLAUSIBLE }

data class CalibrationInfo(
    val status: CalibrationStatus = CalibrationStatus.NONE,
    /** 0..1 while a "Calibrate now" capture is running, else null. */
    val captureProgress: Double? = null,
    /** Result of the most recent capture, until the next one starts. */
    val lastCapture: CaptureOutcome? = null,
)
