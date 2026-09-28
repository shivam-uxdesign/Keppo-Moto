package com.ridetrack.app.moments

import android.content.Context
import com.ridetrack.app.data.db.MomentDao
import com.ridetrack.app.data.db.MomentEntity
import com.ridetrack.telemetry.model.RideEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

enum class MomentKind { CLIP, PHOTO }

/** A saved clip or photo. Files live in app storage under files/moments/<rideId>/. */
data class Moment(
    val id: String,
    val rideId: String,
    val kind: MomentKind,
    val types: Set<RideEventType>,
    val timeMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
    val peakValue: Double?,
    val file: File,
    val thumb: File?,
    val durationMillis: Long?,
    val starred: Boolean,
) {
    /** The event that best describes the moment (for its dot colour). */
    val primaryType: RideEventType?
        get() = listOf(RideEventType.HARD_BRAKE, RideEventType.SIGNIFICANT_LEAN, RideEventType.STRONG_ACCELERATION)
            .firstOrNull { it in types }
}

class MomentRepository(private val context: Context, private val dao: MomentDao) {
    fun dir(rideId: String): File = File(File(context.filesDir, "moments"), rideId).apply { mkdirs() }

    fun observe(rideId: String): Flow<List<Moment>> = dao.observeForRide(rideId).map { list -> list.map { it.toModel() } }

    suspend fun forRide(rideId: String): List<Moment> = dao.forRide(rideId).map { it.toModel() }

    suspend fun add(m: Moment) = dao.insert(
        MomentEntity(
            id = m.id,
            rideId = m.rideId,
            kind = m.kind.name,
            types = m.types.joinToString(",") { it.name },
            timeMillis = m.timeMillis,
            latitude = m.latitude,
            longitude = m.longitude,
            speedMps = m.speedMps,
            peakValue = m.peakValue,
            file = m.file.name,
            thumbFile = m.thumb?.name,
            durationMillis = m.durationMillis,
            starred = m.starred,
        ),
    )

    suspend fun setStarred(id: String, starred: Boolean) = dao.setStarred(id, starred)

    suspend fun delete(m: Moment) {
        dao.delete(m.id)
        withContext(Dispatchers.IO) {
            m.file.delete()
            m.thumb?.delete()
        }
    }

    /** Removes a ride's files; its rows go with the ride (foreign-key cascade). */
    suspend fun deleteFilesForRide(rideId: String) = withContext(Dispatchers.IO) {
        File(File(context.filesDir, "moments"), rideId).deleteRecursively()
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        withContext(Dispatchers.IO) { File(context.filesDir, "moments").deleteRecursively() }
    }

    suspend fun bytesUsed(): Long = withContext(Dispatchers.IO) {
        File(context.filesDir, "moments").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun MomentEntity.toModel(): Moment {
        val d = File(File(context.filesDir, "moments"), rideId)
        return Moment(
            id = id,
            rideId = rideId,
            kind = if (kind == MomentKind.PHOTO.name) MomentKind.PHOTO else MomentKind.CLIP,
            types = types.split(',').mapNotNull { n -> RideEventType.entries.firstOrNull { it.name == n } }.toSet(),
            timeMillis = timeMillis,
            latitude = latitude,
            longitude = longitude,
            speedMps = speedMps,
            peakValue = peakValue,
            file = File(d, file),
            thumb = thumbFile?.let { File(d, it) },
            durationMillis = durationMillis,
            starred = starred,
        )
    }
}
