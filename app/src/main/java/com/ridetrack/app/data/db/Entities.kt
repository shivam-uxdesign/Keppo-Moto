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
    val odometerKm: Double? = null,
    val odometerSetAtMillis: Long? = null,
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
    /** Set while the ride is in Recently deleted (purged for good 30 days later). */
    val deletedAtMillis: Long? = null,
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

@Entity(
    tableName = "moments",
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["rideId", "timeMillis"])],
)
data class MomentEntity(
    @PrimaryKey val id: String,
    val rideId: String,
    /** CLIP or PHOTO. */
    val kind: String,
    /** Comma-separated RideEventType names that triggered the clip; empty for photos. */
    val types: String,
    val timeMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
    val peakValue: Double?,
    /** File names relative to files/moments/<rideId>/. */
    val file: String,
    val thumbFile: String?,
    val durationMillis: Long?,
    val starred: Boolean,
    /** Wall time of the clip's first frame (lines telemetry up with the video). */
    val clipStartMillis: Long? = null,
    /** MANUAL or GPS_LOST for videos filmed on purpose; null = cut around an event. */
    val source: String? = null,
    /** Set while this moment alone is in Recently deleted (a deleted ride hides its moments with it). */
    val deletedAtMillis: Long? = null,
)
