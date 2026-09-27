package com.ridetrack.app

import com.ridetrack.app.data.RideTrack
import com.ridetrack.app.data.export.RideExporter
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.RideStatus
import com.ridetrack.telemetry.model.TelemetrySample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RideExporterTest {
    private val start = 1_790_000_000_000L
    private val ride = Ride(
        "r1", "b1", "Evening \"Test\" Ride", RideStatus.COMPLETED, DataSourceKind.PHONE, start, start + 120_000,
        RideStats(distanceM = 1234.5, movingMillis = 100_000, maxSpeedMps = 20.0, maxLeftLeanDeg = 21.0),
    )
    private val track = RideTrack(
        samples = listOf(
            TelemetrySample(start, 12.97, 77.59, 10.0, 900.0, 45.0, 0.1, -0.2, 12.5, 4.0),
            TelemetrySample(start + 1000, null, null, null, null, null, null, null, null, null),
            TelemetrySample(start + 2000, 12.9701, 77.5901, 0.0, 901.0, 46.0, 0.0, 0.0, 0.0, 5.0),
        ),
        events = listOf(RideEvent(RideEventType.HARD_BRAKE, start + 500, 12.97, 77.59, 15.0, -0.42)),
    )

    @Test
    fun `csv keeps unknown values empty and real zeros as zero`() {
        val lines = RideExporter.csv(ride, track).trim().lines()
        assertEquals(RideExporter.CSV_HEADER, lines[0])
        assertEquals(4, lines.size)
        val unknown = lines[2].split(',')
        assertTrue(unknown.drop(2).all { it.isEmpty() }, lines[2])
        val zero = lines[3].split(',')
        assertEquals("0.0", zero[4]) // speed 0 km/h is a real zero
    }

    @Test
    fun `json uses null for unknown and escapes text`() {
        val json = RideExporter.json(ride, "Pulsar NS200", true, track, "0.1.0")
        assertTrue(json.contains("\"name\": \"Evening \\\"Test\\\" Ride\""))
        assertTrue(json.contains("\"maxRightLeanDeg\": null"))
        assertTrue(json.contains("\"lat\": null, \"lon\": null, \"speedMps\": null"))
        assertTrue(json.contains("\"type\": \"HARD_BRAKE\""))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    @Test
    fun `gpx only contains points with a fix`() {
        val gpx = RideExporter.gpx(ride, track)
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("<rt:lean>12.5</rt:lean>"))
        assertFalse(gpx.contains("\"Test\""))
    }
}
