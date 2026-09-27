package com.ridetrack.telemetry.demo

import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.SensorAvailability
import com.ridetrack.telemetry.source.RawReading
import com.ridetrack.telemetry.source.TelemetrySource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Simulated telemetry for DEMO MODE. Always reports [DataSourceKind.DEMO] so demo data
 * can never be mistaken for a real ride.
 */
class DemoTelemetrySource(
    private val clockNanos: () -> Long,
    simulateEngine: Boolean = false,
    private val model: DemoRideModel = DemoRideModel(simulateEngine = simulateEngine),
) : TelemetrySource {
    override val kind = DataSourceKind.DEMO
    override val sensors = SensorAvailability(accelerometer = true, gyroscope = true, magnetometer = false)
    val calibration get() = model.calibration

    override fun readings(): Flow<RawReading> = flow {
        var next = clockNanos()
        while (true) {
            model.step(next).forEach { emit(it) }
            next += model.stepNanos
            val waitMillis = (next - clockNanos()) / 1_000_000L
            if (waitMillis > 0) delay(waitMillis)
        }
    }
}
