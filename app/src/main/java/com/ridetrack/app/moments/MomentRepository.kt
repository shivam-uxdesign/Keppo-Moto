package com.ridetrack.app.moments

import android.content.Context
import com.ridetrack.app.data.db.MomentDao
import com.ridetrack.app.data.db.MomentEntity
import com.ridetrack.telemetry.model.RideEventType
import com.ridetrack.telemetry.model.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

enum class MomentKind { CLIP, PHOTO }

/** A saved clip or photo. Files live in app storage under files/moments/<rideId>/. */
/** Why a clip exists: cut around an event, filmed on purpose, or filmed while GPS was lost. */
enum class MomentSource { EVENT, MANUAL, GPS_LOST }

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
    val clipStartMillis: Long? = null,
    val source: MomentSource = MomentSource.EVENT,
    /** The part to share, in ms from the clip's start; null = the whole clip. */
    val trimStartMillis: Long? = null,
    val trimEndMillis: Long? = null,
    /** Clips: the fastest the bike went while it was filmed (m/s). */
    val topSpeedMps: Double? = null,
    /** What the rider said (Hinglish in English letters); "" = no clear speech; null = not yet. */
    val transcript: String? = null,
    /** "back" for the back camera (road view); null = the selfie camera. */
    val camera: String? = null,
) {
    /** When the clip's first frame was filmed; estimated for clips saved before this was stored. */
    val videoStartMillis: Long get() = clipStartMillis ?: (timeMillis - 10_000)

    /** The event that best describes the moment (for its dot colour). */
    val primaryType: RideEventType?
        get() = listOf(RideEventType.HARD_BRAKE, RideEventType.SIGNIFICANT_LEAN, RideEventType.STRONG_ACCELERATION)
            .firstOrNull { it in types }
}

/** What a ride card shows of its moments. */
data class RideMoments(val count: Int, val thumbs: List<File>)

class MomentRepository(private val context: Context, private val dao: MomentDao) {
    fun dir(rideId: String): File = File(File(context.filesDir, "moments"), rideId).apply { mkdirs() }

    fun observe(rideId: String): Flow<List<Moment>> = dao.observeForRide(rideId).map { list -> list.map { it.toModel() } }

    /** Number of moments per bike id. */
    fun observeCountsByBike(): Flow<Map<String, Int>> = dao.observeCountsByBike().map { rows -> rows.mapNotNull { r -> r.bikeId?.let { it to r.count } }.toMap() }

    suspend fun forRide(rideId: String): List<Moment> = dao.forRide(rideId).map { it.toModel() }

    /** Every moment of every ride that isn't deleted. */
    suspend fun all(): List<Moment> = dao.all().map { it.toModel() }

    /** Per ride: how many moments it has and up to [max] pictures for its card (starred first). */
    fun observeCards(max: Int = 2): Flow<Map<String, RideMoments>> = dao.observeAllLive().map { all ->
        all.groupBy { it.rideId }.mapValues { (rideId, ms) ->
            val base = File(File(context.filesDir, "moments"), rideId)
            RideMoments(
                count = ms.size,
                thumbs = ms.sortedByDescending { it.starred }
                    .mapNotNull { m -> (m.thumbFile ?: m.file.takeIf { m.kind == "PHOTO" })?.let { File(base, it) } }
                    .take(max),
            )
        }
    }

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
            clipStartMillis = m.clipStartMillis,
            source = m.source.takeIf { it != MomentSource.EVENT }?.name,
            camera = m.camera,
        ),
    )

    suspend fun setStarred(id: String, starred: Boolean) = dao.setStarred(id, starred)

    /**
     * Works out and stores the top speed of each clip of [rideId] that doesn't have one yet,
     * from the ride's [samples]. Returns how many it filled. Cheap when they're all done.
     */
    /** Talking clips waiting for a transcript. */
    suspend fun untranscribed(): List<Moment> = dao.untranscribed().map { it.toModel() }

    suspend fun setTranscript(id: String, text: String) = dao.setTranscript(id, text)

    suspend fun fillTopSpeeds(rideId: String, samples: List<TelemetrySample>): Int {
        if (samples.isEmpty()) return 0
        var filled = 0
        dao.forRide(rideId).filter { it.kind == MomentKind.CLIP.name && it.topSpeedMps == null }.forEach { e ->
            val start = e.clipStartMillis ?: (e.timeMillis - 10_000)
            val end = start + (e.durationMillis ?: 20_000)
            // Only once the ride has been recorded past the clip's end.
            if (samples.last().timeMillis < end) return@forEach
            MomentTopSpeed.of(samples, start, end)?.let {
                dao.setTopSpeed(e.id, it)
                filled++
            }
        }
        return filled
    }

    /** Remembers the part of a clip to share; null, null = the whole clip. */
    suspend fun setTrim(id: String, startMillis: Long?, endMillis: Long?) = dao.setTrim(id, startMillis, endMillis)

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
            clipStartMillis = clipStartMillis,
            source = MomentSource.entries.firstOrNull { it.name == source } ?: MomentSource.EVENT,
            trimStartMillis = trimStartMillis,
            trimEndMillis = trimEndMillis,
            topSpeedMps = topSpeedMps,
            transcript = transcript,
            camera = camera,
        )
    }
}
