package com.ridetrack.app.trash

import android.content.Context
import com.ridetrack.app.data.db.MomentEntity
import com.ridetrack.app.data.db.RideEntity
import com.ridetrack.app.data.db.RideTrackDatabase
import com.ridetrack.app.journal.JournalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recently deleted: every delete in Keppo Moto (a ride, a moment, "Delete all moments", a
 * discarded unfinished ride, or Keppo Journal's DELETE_RIDE) lands here first. Files stay on
 * the phone for [KEEP_MILLIS], then [purgeExpired] removes them for good. Each step is written
 * to `deleted.json` and signalled to Keppo Journal (docs/keppo-ride-format.md, "Deletions").
 */
class RecentlyDeleted(
    private val context: Context,
    private val db: RideTrackDatabase,
    private val journal: JournalSource,
    /** Queue a Drive backup so the backup follows (it keeps its own 30-day grace). */
    private val onChanged: () -> Unit,
) {
    val log = DeletionLogStore(File(context.filesDir, "journal"))

    fun observeRides(): Flow<List<RideEntity>> = db.rideDao().observeDeleted()
    fun observeMoments(): Flow<List<MomentEntity>> = db.momentDao().observeDeleted()

    /** Videos and photos a ride would take with it, for the confirmation text. */
    suspend fun counts(rideId: String): Pair<Int, Int> {
        val ms = db.momentDao().forRide(rideId)
        return ms.count { it.kind == "CLIP" } to ms.count { it.kind == "PHOTO" }
    }

    /** True if the ride was live and is now in Recently deleted. */
    suspend fun deleteRide(rideId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (db.rideDao().softDelete(rideId, now) == 0) return false
        log.update { it.rideDeleted(rideId, now) }
        journal.onRideDeleted(rideId)
        onChanged()
        return true
    }

    suspend fun deleteMoment(momentId: String, now: Long = System.currentTimeMillis()): Boolean {
        val m = db.momentDao().get(momentId) ?: return false
        if (db.momentDao().softDelete(momentId, now) == 0) return false
        log.update { it.momentDeleted(momentId, m.rideId, now) }
        journal.onContentsChanged()
        onChanged()
        return true
    }

    /** Profile › Moments › "Delete all moments": every live moment moves here. */
    suspend fun deleteAllMoments(now: Long = System.currentTimeMillis()) {
        val all = db.momentDao().allLive()
        all.forEach { db.momentDao().softDelete(it.id, now) }
        log.update { l -> all.fold(l) { acc, m -> acc.momentDeleted(m.id, m.rideId, now) } }
        journal.onContentsChanged()
        onChanged()
    }

    suspend fun restoreRide(rideId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (db.rideDao().restore(rideId) == 0) return false
        log.update { it.rideRestored(rideId, now) }
        journal.onRideSaved(rideId)
        onChanged()
        return true
    }

    suspend fun restoreMoment(momentId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (db.momentDao().restore(momentId) == 0) return false
        log.update { it.momentRestored(momentId, now) }
        journal.onContentsChanged()
        onChanged()
        return true
    }

    /** "Delete now": gone for good, files and all. */
    suspend fun purgeRide(rideId: String, now: Long = System.currentTimeMillis()) {
        val moments = db.momentDao().allForRide(rideId)
        db.rideDao().delete(rideId) // moments, samples and events go with it (cascade)
        withContext(Dispatchers.IO) { momentsDir(rideId).deleteRecursively() }
        log.update { l ->
            moments.fold(l.ridePurged(rideId, now)) { acc, m -> if (acc.moments.containsKey(m.id)) acc.momentPurged(m.id, now) else acc }
        }
        journal.onContentsChanged()
        onChanged()
    }

    suspend fun purgeMoment(momentId: String, now: Long = System.currentTimeMillis()) {
        val m = db.momentDao().get(momentId) ?: return
        db.momentDao().delete(momentId)
        withContext(Dispatchers.IO) {
            val dir = momentsDir(m.rideId)
            File(dir, m.file).delete()
            m.thumbFile?.let { File(dir, it).delete() }
        }
        log.update { it.momentPurged(momentId, now) }
        journal.onContentsChanged()
        onChanged()
    }

    /** "Empty Recently deleted". */
    suspend fun purgeAll(now: Long = System.currentTimeMillis()) = purgeOlderThan(Long.MAX_VALUE, now)

    /** Removes for good whatever has been here longer than [KEEP_MILLIS]. Run daily and on app start. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) = purgeOlderThan(now - KEEP_MILLIS, now)

    private suspend fun purgeOlderThan(before: Long, now: Long) {
        db.rideDao().deletedBefore(before).forEach { purgeRide(it.id, now) }
        db.momentDao().deletedBefore(before).forEach { purgeMoment(it.id, now) }
    }

    private fun momentsDir(rideId: String) = File(File(context.filesDir, "moments"), rideId)

    companion object {
        const val KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Days left (rounded up) before something deleted at [deletedAt] is purged; 0 once due. */
        fun daysLeft(deletedAt: Long, now: Long): Int =
            ((deletedAt + KEEP_MILLIS - now).coerceAtLeast(0) + DAY - 1).div(DAY).toInt()

        private const val DAY = 24L * 60 * 60 * 1000
    }
}
