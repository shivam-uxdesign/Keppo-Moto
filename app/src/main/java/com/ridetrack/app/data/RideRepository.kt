package com.ridetrack.app.data

import com.ridetrack.app.data.db.RideDao
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.TelemetrySample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RideTrack(val samples: List<TelemetrySample>, val events: List<RideEvent>)

class RideRepository(private val dao: RideDao) {
    fun observeCompleted(): Flow<List<Ride>> = dao.observeCompleted().map { list -> list.map { it.toModel() } }

    fun observeRide(id: String): Flow<Ride?> = dao.observe(id).map { it?.toModel() }

    fun observeInProgress(): Flow<List<Ride>> = dao.observeInProgress().map { list -> list.map { it.toModel() } }

    suspend fun get(id: String): Ride? = dao.get(id)?.toModel()

    suspend fun create(id: String, bikeId: String, source: DataSourceKind, startMillis: Long) =
        dao.insert(newRideEntity(id, bikeId, source, startMillis))

    suspend fun append(rideId: String, samples: List<TelemetrySample>, events: List<RideEvent>) {
        if (samples.isNotEmpty()) dao.insertSamples(samples.map { it.toEntity(rideId) })
        if (events.isNotEmpty()) dao.insertEvents(events.map { it.toEntity(rideId) })
    }

    suspend fun snapshot(rideId: String, stats: RideStats, nowMillis: Long) {
        dao.updateSnapshot(
            id = rideId, now = nowMillis, distanceM = stats.distanceM, movingMillis = stats.movingMillis,
            stoppedMillis = stats.stoppedMillis, maxSpeedMps = stats.maxSpeedMps, maxAccelG = stats.maxAccelG,
            maxBrakeG = stats.maxBrakeG, peakG = stats.peakG, maxLeftLeanDeg = stats.maxLeftLeanDeg,
            maxRightLeanDeg = stats.maxRightLeanDeg, avgLeanDeg = stats.avgLeanDeg, stopCount = stats.stopCount,
            leftTurns = stats.leftTurns, rightTurns = stats.rightTurns, brakeEvents = stats.brakeEvents,
            accelEvents = stats.accelEvents, leanEvents = stats.leanEvents, breakMillis = stats.breakMillis,
        )
    }

    /** Returns false if the ride was already completed (never saves twice). */
    suspend fun complete(rideId: String, endMillis: Long, name: String): Boolean =
        dao.complete(rideId, endMillis, name) > 0

    /**
     * Closes a ride left in progress by a crash or a killed process, using the last
     * persisted snapshot and sample.
     */
    suspend fun recover(ride: Ride, name: String): Boolean {
        val entity = dao.get(ride.id) ?: return false
        val end = maxOf(dao.lastSampleTime(ride.id) ?: entity.lastUpdateMillis, entity.lastUpdateMillis)
        return dao.complete(ride.id, end, name) > 0
    }

    suspend fun rename(rideId: String, name: String) = dao.rename(rideId, name.trim())

    suspend fun track(rideId: String): RideTrack = RideTrack(
        samples = dao.samples(rideId).map { it.toModel() },
        events = dao.events(rideId).mapNotNull { it.toModelOrNull() },
    )

    /** Where a ride is, for links from outside (Keppo Journal's OPEN_RIDE). */
    suspend fun lookup(id: String): RideLookup {
        val e = dao.get(id) ?: return RideLookup.MISSING
        return if (e.deletedAtMillis != null) RideLookup.DELETED else RideLookup.LIVE
    }
}

enum class RideLookup { LIVE, DELETED, MISSING }
