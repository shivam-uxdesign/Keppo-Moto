package com.ridetrack.app.data

import com.ridetrack.app.data.db.BikeEntity
import com.ridetrack.app.data.db.EventEntity
import com.ridetrack.app.data.db.RideEntity
import com.ridetrack.app.data.db.SampleEntity
import com.ridetrack.telemetry.math.Vec3
import com.ridetrack.telemetry.model.Bike
import com.ridetrack.telemetry.model.DataSourceKind
import com.ridetrack.telemetry.model.FuelType
import com.ridetrack.telemetry.model.MountCalibration
import com.ridetrack.telemetry.model.MountOrientation
import com.ridetrack.telemetry.model.Ride
import com.ridetrack.telemetry.model.RideEvent
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.RideStats
import com.ridetrack.telemetry.model.RideStatus
import com.ridetrack.telemetry.model.TelemetrySample

private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

fun BikeEntity.toModel(): Bike {
    val x = calibUpX
    val y = calibUpY
    val z = calibUpZ
    val at = calibratedAtMillis
    val calibration = if (x != null && y != null && z != null && at != null) {
        runCatching { MountCalibration(Vec3(x, y, z), at) }.getOrNull()
    } else {
        null
    }
    return Bike(
        id = id,
        make = make,
        model = model,
        year = year,
        displacementCc = displacementCc,
        weightKg = weightKg,
        fuelType = enumOr(fuelType, FuelType.PETROL),
        mountOrientation = enumOr(mountOrientation, MountOrientation.PORTRAIT),
        calibration = calibration,
        createdAtMillis = createdAtMillis,
        redlineRpm = redlineRpm,
        photoFile = photoFile,
    )
}

fun Bike.toEntity() = BikeEntity(
    id = id,
    make = make,
    model = model,
    year = year,
    displacementCc = displacementCc,
    weightKg = weightKg,
    fuelType = fuelType.name,
    mountOrientation = mountOrientation.name,
    calibUpX = calibration?.up?.x,
    calibUpY = calibration?.up?.y,
    calibUpZ = calibration?.up?.z,
    calibratedAtMillis = calibration?.createdAtMillis,
    createdAtMillis = createdAtMillis,
    redlineRpm = redlineRpm,
    photoFile = photoFile,
)

fun RideEntity.toModel() = Ride(
    id = id,
    bikeId = bikeId,
    name = name,
    status = enumOr(status, RideStatus.COMPLETED),
    source = enumOr(source, DataSourceKind.PHONE),
    startTimeMillis = startTimeMillis,
    endTimeMillis = endTimeMillis,
    stats = RideStats(
        distanceM = distanceM,
        movingMillis = movingMillis,
        stoppedMillis = stoppedMillis,
        maxSpeedMps = maxSpeedMps,
        maxAccelG = maxAccelG,
        maxBrakeG = maxBrakeG,
        peakG = peakG,
        maxLeftLeanDeg = maxLeftLeanDeg,
        maxRightLeanDeg = maxRightLeanDeg,
        avgLeanDeg = avgLeanDeg,
        stopCount = stopCount,
        leftTurns = leftTurns,
        rightTurns = rightTurns,
        brakeEvents = brakeEvents,
        accelEvents = accelEvents,
        leanEvents = leanEvents,
    ),
)

fun newRideEntity(id: String, bikeId: String, source: DataSourceKind, startMillis: Long) = RideEntity(
    id = id,
    bikeId = bikeId,
    name = "",
    status = RideStatus.IN_PROGRESS.name,
    source = source.name,
    startTimeMillis = startMillis,
    endTimeMillis = null,
    lastUpdateMillis = startMillis,
    distanceM = 0.0,
    movingMillis = 0,
    stoppedMillis = 0,
    maxSpeedMps = null,
    maxAccelG = null,
    maxBrakeG = null,
    peakG = null,
    maxLeftLeanDeg = null,
    maxRightLeanDeg = null,
    avgLeanDeg = null,
    stopCount = 0,
    leftTurns = 0,
    rightTurns = 0,
    brakeEvents = 0,
    accelEvents = 0,
    leanEvents = 0,
)

fun TelemetrySample.toEntity(rideId: String) = SampleEntity(
    rideId = rideId,
    timeMillis = timeMillis,
    latitude = latitude,
    longitude = longitude,
    speedMps = speedMps,
    altitudeM = altitudeM,
    headingDeg = headingDeg,
    longitudinalG = longitudinalG,
    lateralG = lateralG,
    leanDeg = leanDeg,
    gpsAccuracyM = gpsAccuracyM,
    rpm = rpm,
    gear = gear,
)

fun SampleEntity.toModel() = TelemetrySample(
    timeMillis = timeMillis,
    latitude = latitude,
    longitude = longitude,
    speedMps = speedMps,
    altitudeM = altitudeM,
    headingDeg = headingDeg,
    longitudinalG = longitudinalG,
    lateralG = lateralG,
    leanDeg = leanDeg,
    gpsAccuracyM = gpsAccuracyM,
    rpm = rpm,
    gear = gear,
)

fun RideEvent.toEntity(rideId: String) = EventEntity(
    rideId = rideId,
    type = type.name,
    timeMillis = timeMillis,
    latitude = latitude,
    longitude = longitude,
    speedMps = speedMps,
    value = value,
)

fun EventEntity.toModelOrNull(): RideEvent? {
    val t = enumValues<RideEventType>().firstOrNull { it.name == type } ?: return null
    return RideEvent(t, timeMillis, latitude, longitude, speedMps, value)
}
