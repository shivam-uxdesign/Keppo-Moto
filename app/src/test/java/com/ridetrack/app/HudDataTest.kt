package com.ridetrack.app

import com.ridetrack.app.hud.HudData
import com.ridetrack.app.hud.HudStatus
import com.ridetrack.app.hud.HudVideo
import com.ridetrack.app.moments.MomentSource
import com.ridetrack.app.ride.ActiveRide
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.GpsQuality
import com.ridetrack.telemetry.model.LeanConfidence
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.model.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudDataTest {
    private val sensors = SensorAvailability(accelerometer = true, gyroscope = true, magnetometer = true)

    private fun active(source: DataSourceKind = DataSourceKind.PHONE, calibrated: Boolean = true, s: SensorAvailability = sensors) =
        ActiveRide("r", "b", "Bike", source, 0, calibrated, s)

    private fun frame(gps: GpsQuality = GpsQuality.GOOD, speed: Double? = 20.0, lean: Double? = 12.0, stats: RideStats = RideStats()) =
        TelemetryFrame(
            timeMillis = 0, elapsedMillis = 60_000, stats = stats, speedMps = speed, leanDeg = lean,
            leanConfidence = LeanConfidence.GOOD, longitudinalG = 0.1, lateralG = 0.2, headingDeg = 90.0,
            altitudeM = null, gpsQuality = gps, gpsAccuracyM = 5.0, isStopped = false, latitude = 1.0,
            longitude = 1.0, source = DataSourceKind.PHONE,
        )

    @Test
    fun `gps loss is shown and speed stays unknown`() {
        val d = HudData.from(frame(gps = GpsQuality.LOST, speed = null), active(), paused = false, stoppedForMillis = null)
        assertEquals(HudStatus.GPS_LOST, d.status)
        assertNull(d.speedMps)
    }

    @Test
    fun `paused wins over gps state and carries the stop time`() {
        val d = HudData.from(frame(), active(), paused = true, stoppedForMillis = 42_000)
        assertEquals(HudStatus.STOPPED, d.status)
        assertEquals(42_000, d.stoppedForMillis)
    }

    @Test
    fun `lean unavailable explains why`() {
        val d = HudData.from(frame(lean = null), active(calibrated = false), paused = false, stoppedForMillis = null)
        assertNull(d.leanDeg)
        // The mount is learned during the ride, so "not calibrated" is a transient state.
        assertEquals("Learning mount…", d.leanNote)
        val noGyro = HudData.from(frame(lean = null), active(s = sensors.copy(gyroscope = false)), paused = false, stoppedForMillis = null)
        assertEquals("No gyroscope", noGyro.leanNote)
    }

    @Test
    fun `engine data passes through only when present`() {
        val none = HudData.from(frame(), active(), false, null)
        assertNull(none.rpm)
        assertNull(none.gear)
        val obd = HudData.from(frame().copy(rpm = 6_200.0, gear = 3), active().copy(redlineRpm = 9_500), false, null)
        assertEquals(6_200.0, obd.rpm)
        assertEquals(3, obd.gear)
        assertEquals(9_500, obd.redlineRpm)
    }

    @Test
    fun `max lean keeps the larger side with its sign`() {
        val left = HudData.from(frame(stats = RideStats(maxLeftLeanDeg = 31.0, maxRightLeanDeg = 24.0)), active(), false, null)
        assertEquals(-31.0, left.maxLeanDeg)
        val none = HudData.from(frame(), active(), false, null)
        assertNull(none.maxLeanDeg)
    }

    @Test
    fun `demo rides are flagged and never report gps loss`() {
        val d = HudData.from(frame(gps = GpsQuality.UNAVAILABLE), active(source = DataSourceKind.DEMO), false, null)
        assertTrue(d.demo)
        assertEquals(HudStatus.RECORDING, d.status)
        assertFalse(HudData.from(frame(), active(), false, null).demo)
    }

    @Test
    fun `a rider pause shows as paused, not an auto-stop`() {
        assertEquals(HudStatus.PAUSED, HudData.from(frame(), active(), paused = true, stoppedForMillis = 5_000, manuallyPaused = true).status)
        assertEquals(HudStatus.STOPPED, HudData.from(frame(), active(), paused = true, stoppedForMillis = 5_000).status)
    }

    @Test
    fun `a video being filmed is carried to the pop-up`() {
        val v = HudVideo(MomentSource.GPS_LOST, starting = false, paused = false, elapsedMillis = 12_000)
        assertEquals(v, HudData.from(frame(), active(), false, null, video = v).video)
        assertNull(HudData.from(frame(), active(), false, null).video)
    }
}
