package com.ridetrack.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bikes")
data class BikeEntity(
    @PrimaryKey val id: String,
    val make: String,
    val model: String,
    val year: Int?,
    val displacementCc: Int?,
    val weightKg: Int?,
    val fuelType: String,
    val mountOrientation: String,
    val calibUpX: Double?,
    val calibUpY: Double?,
    val calibUpZ: Double?,
    val calibratedAtMillis: Long?,
    val createdAtMillis: Long,
    val redlineRpm: Int? = null,
    val photoFile: String? = null,
)

@Entity(
    tableName = "rides",
    indices = [Index("bikeId"), Index("status"), Index("startTimeMillis")],
)
data class RideEntity(
    @PrimaryKey val id: String,
    val bikeId: String,
    val name: String,
    val status: String,
    val source: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long?,
    /** Last time the in-progress snapshot was written; used to close crashed rides. */
    val lastUpdateMillis: Long,
    val distanceM: Double,
    val movingMillis: Long,
    val stoppedMillis: Long,
    val maxSpeedMps: Double?,
    val maxAccelG: Double?,
    val maxBrakeG: Double?,
    val peakG: Double?,
    val maxLeftLeanDeg: Double?,
    val maxRightLeanDeg: Double?,
    val avgLeanDeg: Double?,
    val stopCount: Int,
    val leftTurns: Int,
    val rightTurns: Int,
    val brakeEvents: Int,
    val accelEvents: Int,
    val leanEvents: Int,
)

@Entity(
    tableName = "samples",
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["rideId", "timeMillis"])],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val rideId: String,
    val timeMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
    val altitudeM: Double?,
    val headingDeg: Double?,
    val longitudinalG: Double?,
    val lateralG: Double?,
    val leanDeg: Double?,
    val gpsAccuracyM: Double?,
    val rpm: Double? = null,
    val gear: Int? = null,
)

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["rideId", "timeMillis"])],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val rideId: String,
    val type: String,
    val timeMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
    val value: Double?,
)
