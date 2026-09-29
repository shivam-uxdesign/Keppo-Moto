package com.ridetrack.app.ui.common

import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideStatus

/**
 * A bike's odometer: the reading the rider entered, plus every real (non-demo) completed
 * ride recorded on that bike since it was entered.
 */
object Odometer {
    /** Current reading in km, or null when the rider has never set one. */
    fun readingKm(bike: Bike, rides: List<Ride>): Double? {
        val base = bike.odometerKm ?: return null
        val since = bike.odometerSetAtMillis ?: Long.MIN_VALUE
        val recordedM = rides
            .filter { it.bikeId == bike.id && it.status == RideStatus.COMPLETED && it.source != DataSourceKind.DEMO && it.startTimeMillis >= since }
            .sumOf { it.stats.distanceM }
        return base + recordedM / 1000.0
    }
}
