package com.ridetrack.app.journal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.DocumentsContract
import com.ridetrack.app.backup.BackupFormat
import com.ridetrack.app.backup.RideBundle
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.app.data.db.RideEntity
import com.ridetrack.app.data.db.RideTrackDatabase
import com.ridetrack.app.data.toModel
import com.ridetrack.app.share.ShareCardData
import com.ridetrack.app.share.ShareCardRenderer
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * What Keppo Journal can read from this phone: finished, real rides, each as `ride.json`
 * (the shared keppo.ride format), `route.png` and its moment files. Read-only.
 */
class JournalSource(
    private val context: Context,
    private val db: RideTrackDatabase,
    private val settings: SettingsRepository,
) {
    @Volatile private var lastReadRecorded = 0L

    suspend fun enabled(): Boolean = settings.settings.first().journalSharing

    /** Finished, real rides; demo ones too while the Developer testing switch is on. */
    suspend fun rides(): List<RideEntity> =
        (if (shareDemo()) db.rideDao().completed() else db.rideDao().backupable()).sortedByDescending { it.startTimeMillis }

    suspend fun ride(id: String): RideEntity? =
        db.rideDao().get(id)?.takeIf { it.status == "COMPLETED" && (it.source != "DEMO" || shareDemo()) }

    private suspend fun shareDemo() = settings.settings.first().journalShareDemo

    /** Moment files present on the phone for [rideId] (clips, photos, thumbnails). */
    suspend fun momentFiles(rideId: String): List<File> {
        val dir = momentsDir(rideId)
        return db.momentDao().forRide(rideId).flatMap { listOfNotNull(it.file, it.thumbFile) }
            .map { File(dir, it) }.filter { it.isFile }
    }

    fun momentFile(rideId: String, name: String): File = File(momentsDir(rideId), name)

    /**
     * The picture to show for a file in lists: a clip's saved thumbnail frame, a photo or
     * thumbnail itself, or the route picture. Null for files without one (ride.json).
     */
    suspend fun thumbnail(rideId: String, name: String): File? {
        if (name == JournalTree.ROUTE_PNG) return ride(rideId)?.let { routePng(it) }
        val dir = momentsDir(rideId)
        val clip = db.momentDao().forRide(rideId).firstOrNull { it.file == name && it.kind == "CLIP" }
        val f = when {
            clip != null -> clip.thumbFile?.let { File(dir, it) }
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> File(dir, name)
            else -> null
        }
        return f?.takeIf { it.isFile }
    }

    /** `ride.json`, regenerated when the ride or its moments changed. */
    suspend fun rideJson(ride: RideEntity): File {
        val moments = db.momentDao().forRide(ride.id)
        val bike = db.bikeDao().get(ride.bikeId)
        val text = BackupFormat.rideJson(RideBundle(ride, db.rideDao().events(ride.id), moments), bike?.let { "${it.make} ${it.model}".trim() })
        val f = File(cacheDir(ride.id), JournalTree.RIDE_JSON)
        if (!f.exists() || f.readText() != text) f.writeText(text)
        return f
    }

    /** `route.png`, drawn once per ride (the route never changes after the ride ends). */
    suspend fun routePng(ride: RideEntity): File {
        val f = File(cacheDir(ride.id), JournalTree.ROUTE_PNG)
        if (f.exists() && f.lastModified() >= ride.lastUpdateMillis) return f
        val data = ShareCardData.from(ride.toModel(), null, db.rideDao().samples(ride.id).map { it.toModel() })
        val bmp = ShareCardRenderer(context).renderRoute(data)
        val tmp = File(f.parentFile, "${f.name}.part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        tmp.renameTo(f)
        return f
    }

    /** Remember (at most once a minute) that the journal read our rides, for Profile's status line. */
    suspend fun markRead() {
        val now = System.currentTimeMillis()
        if (now - lastReadRecorded < 60_000) return
        lastReadRecorded = now
        settings.setJournalLastRead(now)
    }

    /**
     * A ride was saved or restored: tell the file picker, and tap Keppo Journal on the shoulder.
     * No [rideId] means "several rides changed, check everything" (after a Drive restore).
     */
    suspend fun onRideSaved(rideId: String?) = signal(ACTION_RIDE_SAVED, rideId)

    /** A ride moved to Recently deleted. `deleted.json` records it too, in case this is missed. */
    suspend fun onRideDeleted(rideId: String) = signal(ACTION_RIDE_DELETED, rideId)

    /** Something inside the folder changed (a moment deleted or restored): refresh listings only. */
    fun onContentsChanged() {
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context), JournalTree.ROOT), null)
    }

    private suspend fun signal(action: String, rideId: String?) {
        if (!enabled()) return
        onContentsChanged()
        val intent = Intent(action).setPackage(JOURNAL_PACKAGE).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        if (rideId != null) intent.putExtra(EXTRA_RIDE_ID, rideId)
        context.sendBroadcast(intent)
    }

    /** Sharing turned on or off: the "Keppo Moto" root appears in, or leaves, the file picker. */
    fun onSharingChanged() {
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null)
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context), JournalTree.ROOT), null)
    }

    private fun momentsDir(rideId: String) = File(File(context.filesDir, "moments"), rideId)
    private fun cacheDir(rideId: String) = File(File(context.cacheDir, "journal"), rideId).apply { mkdirs() }

    companion object {
        const val JOURNAL_PACKAGE = "com.keppo.journal"
        const val ACTION_RIDE_SAVED = "com.keppo.action.RIDE_SAVED"
        const val ACTION_RIDE_DELETED = "com.keppo.action.RIDE_DELETED"
        /** Keppo Journal's one-tap connect; handled by [ShareRidesActivity]. */
        const val ACTION_SHARE_RIDES = "com.keppo.action.SHARE_RIDES"
        const val EXTRA_RIDE_ID = "rideId"

        fun authority(context: Context) = "${context.packageName}.rides"
    }
}
