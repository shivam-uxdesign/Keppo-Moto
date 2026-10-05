package com.ridetrack.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BikeDao {
    @Query("SELECT * FROM bikes ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<BikeEntity>>

    @Query("SELECT * FROM bikes WHERE id = :id")
    fun observe(id: String): Flow<BikeEntity?>

    @Query("SELECT * FROM bikes WHERE id = :id")
    suspend fun get(id: String): BikeEntity?

    @Query("SELECT * FROM bikes")
    suspend fun all(): List<BikeEntity>

    @Upsert
    suspend fun upsert(bike: BikeEntity)

    @Query("UPDATE bikes SET calibUpX = :x, calibUpY = :y, calibUpZ = :z, calibratedAtMillis = :at WHERE id = :id")
    suspend fun setCalibration(id: String, x: Double, y: Double, z: Double, at: Long)

    @Query("DELETE FROM bikes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM rides WHERE bikeId = :id")
    suspend fun rideCount(id: String): Int
}

@Dao
interface RideDao {
    @Query("SELECT * FROM rides WHERE status = 'COMPLETED' AND deletedAtMillis IS NULL ORDER BY startTimeMillis DESC")
    fun observeCompleted(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE id = :id")
    fun observe(id: String): Flow<RideEntity?>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun get(id: String): RideEntity?

    @Query("SELECT * FROM rides WHERE status = 'IN_PROGRESS' AND deletedAtMillis IS NULL ORDER BY startTimeMillis DESC")
    fun observeInProgress(): Flow<List<RideEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(ride: RideEntity)

    /** What Drive backup keeps: finished, real rides (not demo, not in progress). */
    @Query("SELECT * FROM rides WHERE status = 'COMPLETED' AND source != 'DEMO' AND deletedAtMillis IS NULL")
    suspend fun backupable(): List<RideEntity>

    @Query("SELECT id FROM rides")
    suspend fun allIds(): List<String>

    /** Every finished ride, demo ones included (Keppo Journal's testing switch). */
    @Query("SELECT * FROM rides WHERE status = 'COMPLETED' AND deletedAtMillis IS NULL")
    suspend fun completed(): List<RideEntity>

    @Query(
        """
        UPDATE rides SET lastUpdateMillis = :now, distanceM = :distanceM, movingMillis = :movingMillis,
            stoppedMillis = :stoppedMillis, maxSpeedMps = :maxSpeedMps, maxAccelG = :maxAccelG,
            maxBrakeG = :maxBrakeG, peakG = :peakG, maxLeftLeanDeg = :maxLeftLeanDeg,
            maxRightLeanDeg = :maxRightLeanDeg, avgLeanDeg = :avgLeanDeg, stopCount = :stopCount,
            leftTurns = :leftTurns, rightTurns = :rightTurns, brakeEvents = :brakeEvents,
            accelEvents = :accelEvents, leanEvents = :leanEvents, breakMillis = :breakMillis
        WHERE id = :id AND status = 'IN_PROGRESS'
        """,
    )
    suspend fun updateSnapshot(
        id: String, now: Long, distanceM: Double, movingMillis: Long, stoppedMillis: Long,
        maxSpeedMps: Double?, maxAccelG: Double?, maxBrakeG: Double?, peakG: Double?,
        maxLeftLeanDeg: Double?, maxRightLeanDeg: Double?, avgLeanDeg: Double?, stopCount: Int,
        leftTurns: Int, rightTurns: Int, brakeEvents: Int, accelEvents: Int, leanEvents: Int, breakMillis: Long,
    ): Int

    /** Idempotent: only an in-progress ride can be completed, so a ride can't be saved twice. */
    @Query("UPDATE rides SET status = 'COMPLETED', endTimeMillis = :endTime, name = :name WHERE id = :id AND status = 'IN_PROGRESS'")
    suspend fun complete(id: String, endTime: Long, name: String): Int

    @Query("UPDATE rides SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun delete(id: String)

    // ---- Recently deleted ----

    @Query("UPDATE rides SET deletedAtMillis = :at WHERE id = :id AND deletedAtMillis IS NULL")
    suspend fun softDelete(id: String, at: Long): Int

    @Query("UPDATE rides SET deletedAtMillis = NULL WHERE id = :id AND deletedAtMillis IS NOT NULL")
    suspend fun restore(id: String): Int

    @Query("SELECT * FROM rides WHERE deletedAtMillis IS NOT NULL ORDER BY deletedAtMillis DESC")
    fun observeDeleted(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE deletedAtMillis IS NOT NULL AND deletedAtMillis <= :before")
    suspend fun deletedBefore(before: Long): List<RideEntity>

    @Insert
    suspend fun insertSamples(samples: List<SampleEntity>)

    @Insert
    suspend fun insertEvents(events: List<EventEntity>)

    @Query("SELECT * FROM samples WHERE rideId = :rideId ORDER BY timeMillis ASC")
    suspend fun samples(rideId: String): List<SampleEntity>

    @Query("SELECT * FROM events WHERE rideId = :rideId ORDER BY timeMillis ASC")
    suspend fun events(rideId: String): List<EventEntity>

    @Query("SELECT MAX(timeMillis) FROM samples WHERE rideId = :rideId")
    suspend fun lastSampleTime(rideId: String): Long?
}

/** Row of [MomentDao.observeCountsByBike]. */
data class BikeMomentCount(val bikeId: String?, val count: Int)

@Dao
interface MomentDao {
    @Query("SELECT * FROM moments WHERE rideId = :rideId AND deletedAtMillis IS NULL ORDER BY timeMillis ASC")
    fun observeForRide(rideId: String): Flow<List<MomentEntity>>

    @Query("SELECT * FROM moments WHERE rideId = :rideId AND deletedAtMillis IS NULL ORDER BY timeMillis ASC")
    suspend fun forRide(rideId: String): List<MomentEntity>

    @Query("SELECT m.* FROM moments m JOIN rides r ON r.id = m.rideId WHERE m.deletedAtMillis IS NULL AND r.deletedAtMillis IS NULL")
    suspend fun all(): List<MomentEntity>

    /** Every live moment of a live ride, for the ride cards. */
    @Query("SELECT m.* FROM moments m JOIN rides r ON r.id = m.rideId WHERE m.deletedAtMillis IS NULL AND r.deletedAtMillis IS NULL ORDER BY m.timeMillis ASC")
    fun observeAllLive(): Flow<List<MomentEntity>>

    @Query("SELECT id FROM moments")
    suspend fun allIds(): List<String>

    /** Moments per bike, through the ride each was captured on. */
    @Query("SELECT r.bikeId AS bikeId, COUNT(m.id) AS count FROM moments m JOIN rides r ON r.id = m.rideId WHERE m.deletedAtMillis IS NULL AND r.deletedAtMillis IS NULL GROUP BY r.bikeId")
    fun observeCountsByBike(): Flow<List<BikeMomentCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MomentEntity)

    @Query("UPDATE moments SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("UPDATE moments SET trimStartMillis = :start, trimEndMillis = :end WHERE id = :id")
    suspend fun setTrim(id: String, start: Long?, end: Long?)

    @Query("DELETE FROM moments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM moments WHERE id = :id")
    suspend fun get(id: String): MomentEntity?

    /** Every moment of a ride, including ones in Recently deleted (for purging and for undo). */
    @Query("SELECT * FROM moments WHERE rideId = :rideId")
    suspend fun allForRide(rideId: String): List<MomentEntity>

    // ---- Recently deleted ----

    @Query("UPDATE moments SET deletedAtMillis = :at WHERE id = :id AND deletedAtMillis IS NULL")
    suspend fun softDelete(id: String, at: Long): Int

    /** "Delete all moments": every live moment of every live ride moves to Recently deleted. */
    @Query("SELECT m.* FROM moments m JOIN rides r ON r.id = m.rideId WHERE m.deletedAtMillis IS NULL AND r.deletedAtMillis IS NULL")
    suspend fun allLive(): List<MomentEntity>

    @Query("UPDATE moments SET deletedAtMillis = NULL WHERE id = :id AND deletedAtMillis IS NOT NULL")
    suspend fun restore(id: String): Int

    /** Moments deleted on their own (their ride is still live). */
    @Query("SELECT m.* FROM moments m JOIN rides r ON r.id = m.rideId WHERE m.deletedAtMillis IS NOT NULL AND r.deletedAtMillis IS NULL ORDER BY m.deletedAtMillis DESC")
    fun observeDeleted(): Flow<List<MomentEntity>>

    @Query("SELECT * FROM moments WHERE deletedAtMillis IS NOT NULL AND deletedAtMillis <= :before")
    suspend fun deletedBefore(before: Long): List<MomentEntity>
}
