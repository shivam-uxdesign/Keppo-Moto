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

    suspend fun rides(): List<RideEntity> = db.rideDao().backupable().sortedByDescending { it.startTimeMillis }

    suspend fun ride(id: String): RideEntity? = db.rideDao().get(id)?.takeIf { it.status == "COMPLETED" && it.source != "DEMO" }

    /** Moment files present on the phone for [rideId] (clips, photos, thumbnails). */
    suspend fun momentFiles(rideId: String): List<File> {
        val dir = momentsDir(rideId)
        return db.momentDao().forRide(rideId).flatMap { listOfNotNull(it.file, it.thumbFile) }
            .map { File(dir, it) }.filter { it.isFile }
    }

    fun momentFile(rideId: String, name: String): File = File(momentsDir(rideId), name)

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

    /** A ride was saved: tell the file picker, and tap Keppo Journal on the shoulder. */
    suspend fun onRideSaved(rideId: String) {
        if (!enabled()) return
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context), JournalTree.ROOT), null)
        context.sendBroadcast(
            Intent(ACTION_RIDE_SAVED).setPackage(JOURNAL_PACKAGE).putExtra(EXTRA_RIDE_ID, rideId).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
        )
    }

    /** Sharing turned on or off: the "Keppo Moto" root appears in, or leaves, the file picker. */
    fun onSharingChanged() {
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null)
    }

    private fun momentsDir(rideId: String) = File(File(context.filesDir, "moments"), rideId)
    private fun cacheDir(rideId: String) = File(File(context.cacheDir, "journal"), rideId).apply { mkdirs() }

    companion object {
        const val JOURNAL_PACKAGE = "com.keppo.journal"
        const val ACTION_RIDE_SAVED = "com.keppo.action.RIDE_SAVED"
        /** Keppo Journal's one-tap connect; handled by [ShareRidesActivity]. */
        const val ACTION_SHARE_RIDES = "com.keppo.action.SHARE_RIDES"
        const val EXTRA_RIDE_ID = "rideId"

        fun authority(context: Context) = "${context.packageName}.rides"
    }
}
