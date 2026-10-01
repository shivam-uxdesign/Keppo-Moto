package com.ridetrack.app.backup

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.room.withTransaction
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.data.SettingsRepository
import com.ridetrack.app.data.db.RideTrackDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** What the Backup section shows. */
data class BackupStatus(
    /** Short line while working, e.g. "Backing up 3 of 12…". */
    val working: String? = null,
    /** Files still to upload (e.g. videos waiting for Wi-Fi or charging). */
    val pending: Int = 0,
    /** A backup from another phone was found and this phone hasn't restored or merged yet. */
    val foundRides: Int? = null,
    val error: String? = null,
    val needsReconnect: Boolean = false,
)

/**
 * Backs Keppo Moto up to the rider's own Google Drive (`Keppo/Moto/`) and brings it back.
 * Everything goes: rides with full telemetry, moments, bikes and settings. Uploads are
 * incremental (see [BackupPlanner]); restore never duplicates what's already on the phone.
 */
class BackupRepository(
    private val context: Context,
    private val db: RideTrackDatabase,
    private val settings: SettingsRepository,
) {
    private val auth = DriveAuth(context)
    private val drive = DriveApi { auth.token() }
    private val lock = Mutex()
    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    fun driveAuth() = auth

    /** This install's id. Not in the backed-up settings, so a new phone always gets a new one. */
    private val device: String by lazy {
        val f = File(context.noBackupFilesDir, "keppo-install-id")
        f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { f.writeText(it) }
    }

    // ---- connect ---------------------------------------------------------------------------

    /**
     * Drive access was granted. If Drive already holds rides this phone doesn't have, hold off
     * writing until the rider restores or chooses to merge, so nothing saved gets overwritten.
     */
    suspend fun onConnected(email: String?) {
        val name = email ?: "your Google account"
        val remote = runCatching { Folders.find(drive)?.let { readManifest(it) } }.getOrNull()
        val missing = remote?.let { BackupPlanner.toRestore(it, db.rideDao().allIds().toSet()) }.orEmpty()
        settings.setBackupConnected(name, adopted = missing.isEmpty())
        _status.update { BackupStatus(foundRides = missing.size.takeIf { it > 0 }) }
    }

    suspend fun disconnect() {
        settings.setBackupConnected(null, adopted = false)
        _status.value = BackupStatus()
    }

    /** "Keep both": this phone's rides join the backup, and its settings replace the saved ones. */
    suspend fun merge() {
        settings.setBackupAdopted(true)
        _status.update { it.copy(foundRides = null) }
    }

    // ---- back up ---------------------------------------------------------------------------

    /** One backup run. Returns false if it should be retried later (network). */
    suspend fun backUp(): Boolean = lock.withLock {
        val s = settings.settings.first().backup
        if (!s.connected || !s.adopted) return true
        run("Backing up…") {
            val f = Folders.findOrCreate(drive)
            var manifest = readManifest(f)
            val now = System.currentTimeMillis()

            // Settings and garage are small: always refresh.
            putText(f.moto, SETTINGS, BackupFormat.settingsJson(settings.exportPrefs()), f.motoFiles[SETTINGS])
            val bikes = db.bikeDao().all()
            putText(f.garage, BIKES, BackupFormat.bikesJson(bikes), f.garageFiles[BIKES])
            val garage = manifest.garage.toMutableMap()
            bikes.mapNotNull { it.photoFile }.forEach { name ->
                val file = File(File(context.filesDir, "bikes"), name)
                if (file.exists() && garage[name]?.size != file.length()) {
                    drive.putLarge(f.garage, name, file, mime(name), f.garageFiles[name])
                    garage[name] = ManifestFile(file.length())
                }
            }
            manifest = manifest.copy(garage = garage)

            // Rides.
            val local = db.rideDao().backupable().map { ride ->
                val events = db.rideDao().events(ride.id)
                val moments = db.momentDao().forRide(ride.id)
                val json = BackupFormat.rideJson(RideBundle(ride, events, moments), bikes.firstOrNull { it.id == ride.bikeId }?.let { "${it.make} ${it.model}".trim() })
                val dir = momentsDir(ride.id)
                val files = moments.flatMap { listOfNotNull(it.file, it.thumbFile) }
                    .map { File(dir, it) }.filter { it.exists() }.associate { it.name to it.length() }
                LocalSnapshot(LocalRide(ride.id, BackupFormat.fingerprint(json), files), json)
            }
            val plan = BackupPlanner.plan(local.map { it.ride }, manifest, device, now)
            val rides = manifest.rides.toMutableMap()
            val rideFolders = drive.children(f.rides).filter { it.folder }.associateBy { it.name }.toMutableMap()
            suspend fun rideFolder(id: String) = rideFolders[id]?.id
                ?: drive.createFolder(id, "moto-ride", f.rides).also { rideFolders[id] = it }.id

            plan.rideData.forEachIndexed { i, id ->
                _status.update { it.copy(working = "Backing up rides ${i + 1} of ${plan.rideData.size}…") }
                val snap = local.first { it.ride.id == id }
                val folder = rideFolder(id)
                val existing = drive.children(folder).associateBy { it.name }
                drive.putSmall(folder, RIDE, snap.json.toByteArray(), JSON, existing[RIDE]?.id)
                val samples = File.createTempFile("samples", ".gz", context.cacheDir)
                try {
                    samples.outputStream().use { BackupFormat.writeSamples(db.rideDao().samples(id), it) }
                    drive.putLarge(folder, SAMPLES, samples, "application/gzip", existing[SAMPLES]?.id)
                } finally {
                    samples.delete()
                }
                rides[id] = (rides[id] ?: ManifestRide(snap.ride.fingerprint, device)).copy(fingerprint = snap.ride.fingerprint, device = device, deletedAt = null)
            }
            plan.claim.forEach { id -> rides[id]?.let { rides[id] = it.copy(device = device) } }
            plan.tombstoneRides.forEach { id -> rides[id]?.let { rides[id] = it.copy(deletedAt = now) } }
            plan.tombstoneFiles.forEach { (id, name) ->
                rides[id]?.let { r -> rides[id] = r.copy(files = r.files + (name to r.files.getValue(name).copy(deletedAt = now))) }
            }
            manifest = manifest.copy(rides = rides)
            writeManifest(f, manifest)

            // Moments: clips can be big, so the manifest is saved as they go.
            val charging = isCharging()
            val media = plan.media.filter { (_, name) -> !(isVideo(name) && s.videosOnlyWhileCharging && !charging) }
            val momentFolders = mutableMapOf<String, String>()
            media.forEachIndexed { i, (id, name) ->
                _status.update { it.copy(working = "Backing up moments ${i + 1} of ${media.size}…") }
                val folder = momentFolders.getOrPut(id) {
                    val rf = rideFolder(id)
                    drive.children(rf).firstOrNull { it.folder && it.name == MOMENTS }?.id ?: drive.createFolder(MOMENTS, "moto-moments", rf).id
                }
                val file = File(momentsDir(id), name)
                val existing = drive.children(folder).firstOrNull { it.name == name }
                drive.putLarge(folder, name, file, mime(name), existing?.id)
                rides[id]?.let { r -> rides[id] = r.copy(files = r.files + (name to ManifestFile(file.length()))) }
                if (i % 5 == 4) writeManifest(f, manifest.copy(rides = rides))
            }

            // Past the 30-day grace period: remove for good.
            plan.purgeRides.forEach { id ->
                rideFolders[id]?.let { drive.delete(it.id) }
                rides.remove(id)
            }
            plan.purgeFiles.forEach { (id, name) ->
                rideFolders[id]?.let { rf ->
                    drive.children(rf.id).firstOrNull { it.folder && it.name == MOMENTS }?.let { mf ->
                        drive.children(mf.id).firstOrNull { it.name == name }?.let { drive.delete(it.id) }
                    }
                }
                rides[id]?.let { r -> rides[id] = r.copy(files = r.files - name) }
            }
            manifest = manifest.copy(rides = rides)
            writeManifest(f, manifest)

            val bytes = manifest.liveRides.values.sumOf { r -> r.files.values.filter { it.deletedAt == null }.sumOf { it.size } } +
                manifest.garage.values.sumOf { it.size }
            settings.setBackupResult(System.currentTimeMillis(), bytes)
            _status.update { it.copy(pending = plan.media.size - media.size) }
        }
    }

    // ---- restore ---------------------------------------------------------------------------

    /** Brings back settings, bikes and rides; moments follow in the background ([restoreMedia]). */
    suspend fun restore(): Boolean = lock.withLock {
        run("Restoring…") {
            val f = Folders.find(drive)
            if (f == null) {
                _status.update { it.copy(error = "No Keppo Moto backup in this Drive yet.") }
                return@run
            }
            val manifest = readManifest(f)
            f.motoFiles[SETTINGS]?.let { settings.importPrefs(BackupFormat.parseSettings(drive.downloadText(it))) }

            f.garageFiles[BIKES]?.let { id ->
                val have = db.bikeDao().all().map { it.id }.toSet()
                BackupFormat.parseBikes(drive.downloadText(id)).filter { it.id !in have }.forEach { bike ->
                    db.bikeDao().upsert(bike)
                    bike.photoFile?.let { name -> f.garageFiles[name]?.let { download(it, File(File(context.filesDir, "bikes").apply { mkdirs() }, name)) } }
                }
            }

            val todo = BackupPlanner.toRestore(manifest, db.rideDao().allIds().toSet())
            val rideFolders = drive.children(f.rides).filter { it.folder }.associateBy { it.name }
            val momentIds = db.momentDao().allIds().toMutableSet()
            todo.forEachIndexed { i, id ->
                _status.update { it.copy(working = "Restoring rides ${i + 1} of ${todo.size}…") }
                val folder = rideFolders[id] ?: return@forEachIndexed
                val files = drive.children(folder.id).associateBy { it.name }
                val bundle = BackupFormat.parseRide(drive.downloadText(files[RIDE]?.id ?: return@forEachIndexed))
                val samples = files[SAMPLES]?.let { sf ->
                    val tmp = File.createTempFile("samples", ".gz", context.cacheDir)
                    try {
                        download(sf.id, tmp)
                        tmp.inputStream().use { BackupFormat.readSamples(id, it) }
                    } finally {
                        tmp.delete()
                    }
                }.orEmpty()
                db.withTransaction {
                    db.rideDao().insert(bundle.ride)
                    if (samples.isNotEmpty()) db.rideDao().insertSamples(samples)
                    if (bundle.events.isNotEmpty()) db.rideDao().insertEvents(bundle.events)
                    bundle.moments.filter { momentIds.add(it.id) }.forEach { db.momentDao().insert(it) }
                }
            }
            settings.setBackupAdopted(true)
            _status.update { it.copy(foundRides = null) }
        }
    }

    /** Downloads moment files the phone is missing. Safe to run again; it skips what's there. */
    suspend fun restoreMedia(): Boolean = lock.withLock {
        run("Downloading moments…") {
            val f = Folders.find(drive) ?: return@run
            val manifest = readManifest(f)
            val rideFolders = drive.children(f.rides).filter { it.folder }.associateBy { it.name }
            val wanted = db.momentDao().all().groupBy { it.rideId }.mapNotNull { (rideId, ms) ->
                val dir = momentsDir(rideId)
                val names = ms.flatMap { listOfNotNull(it.file, it.thumbFile) }.filter { !File(dir, it).exists() }
                    .filter { manifest.rides[rideId]?.files?.get(it)?.deletedAt == null }
                if (names.isEmpty()) null else rideId to names
            }
            val total = wanted.sumOf { it.second.size }
            var done = 0
            wanted.forEach { (rideId, names) ->
                val rf = rideFolders[rideId] ?: return@forEach
                val mf = drive.children(rf.id).firstOrNull { it.folder && it.name == MOMENTS } ?: return@forEach
                val remote = drive.children(mf.id).associateBy { it.name }
                names.forEach { name ->
                    done++
                    _status.update { it.copy(working = "Downloading moments $done of $total…") }
                    remote[name]?.let { download(it.id, File(momentsDir(rideId), name)) }
                }
            }
        }
    }

    /** Rides in the backup that aren't on this phone, for the Home card. */
    suspend fun countRestorable(): Int = runCatching {
        Folders.find(drive)?.let { BackupPlanner.toRestore(readManifest(it), db.rideDao().allIds().toSet()).size } ?: 0
    }.getOrDefault(0)

    // ---- plumbing --------------------------------------------------------------------------

    private data class LocalSnapshot(val ride: LocalRide, val json: String)

    /** Runs [block] with status + error handling. False = try again later. */
    private suspend fun run(label: String, block: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        _status.update { it.copy(working = label, error = null, needsReconnect = false) }
        try {
            block()
            _status.update { it.copy(working = null) }
            true
        } catch (e: ReconnectNeeded) {
            _status.update { it.copy(working = null, needsReconnect = true, error = "Reconnect Google Drive to keep backing up.") }
            true
        } catch (e: DriveException) {
            Log.w(TAG, "Drive error", e)
            _status.update { it.copy(working = null, error = if (e.retryable) "Drive is busy. Trying again soon." else "Drive said no (${e.code}). Trying again later.") }
            false
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Network error", e)
            _status.update { it.copy(working = null, error = "Couldn't reach Drive. Trying again when you're online.") }
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            _status.update { it.copy(working = null) }
            throw e
        } catch (e: Exception) {
            // Never crash the app over a backup; say what happened and wait for the next run.
            Log.e(TAG, "Backup failed", e)
            _status.update { it.copy(working = null, error = "Backup hit a problem (${e.javaClass.simpleName}). It'll try again later.") }
            true
        }
    }

    private suspend fun readManifest(f: Folders.Ids): Manifest =
        f.motoFiles[MANIFEST]?.let { Manifest.parse(drive.downloadText(it)) } ?: Manifest()

    private suspend fun writeManifest(f: Folders.Ids, m: Manifest) {
        val text = m.copy(updatedAt = System.currentTimeMillis(), appVersion = BuildConfig.VERSION_NAME).toJson()
        val file = drive.putSmall(f.moto, MANIFEST, text.toByteArray(), JSON, f.motoFiles[MANIFEST])
        f.motoFiles[MANIFEST] = file.id
    }

    private suspend fun putText(parent: String, name: String, text: String, existingId: String?) =
        drive.putSmall(parent, name, text.toByteArray(), JSON, existingId)

    /** Downloads to a temp file first, so an interrupted download never leaves half a clip. */
    private suspend fun download(fileId: String, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, ".${to.name}.part")
        tmp.outputStream().use { drive.download(fileId, it) }
        tmp.renameTo(to)
    }

    private fun momentsDir(rideId: String) = File(File(context.filesDir, "moments"), rideId)
    private fun isCharging() = context.getSystemService<BatteryManager>()?.isCharging == true
    private fun isVideo(name: String) = name.endsWith(".mp4")
    private fun mime(name: String) = when {
        name.endsWith(".mp4") -> "video/mp4"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }

    /** `Keppo/Moto/{garage,rides}`, found by tag and created if missing. */
    private object Folders {
        class Ids(val moto: String, val garage: String, val rides: String, val motoFiles: MutableMap<String, String>, val garageFiles: Map<String, String>)

        suspend fun find(drive: DriveApi): Ids? {
            val moto = drive.findFolderByTag(TAG_MOTO) ?: return null
            return ids(drive, moto.id)
        }

        suspend fun findOrCreate(drive: DriveApi): Ids {
            drive.findFolderByTag(TAG_MOTO)?.let { return ids(drive, it.id) }
            val root = drive.findFolderByTag(TAG_ROOT) ?: drive.createFolder("Keppo", TAG_ROOT, null)
            return ids(drive, drive.createFolder("Moto", TAG_MOTO, root.id).id)
        }

        private suspend fun ids(drive: DriveApi, moto: String): Ids {
            val kids = drive.children(moto)
            val garage = kids.firstOrNull { it.folder && it.name == GARAGE } ?: drive.createFolder(GARAGE, "moto-garage", moto)
            val rides = kids.firstOrNull { it.folder && it.name == RIDES } ?: drive.createFolder(RIDES, "moto-rides", moto)
            return Ids(
                moto = moto, garage = garage.id, rides = rides.id,
                motoFiles = kids.filter { !it.folder }.associate { it.name to it.id }.toMutableMap(),
                garageFiles = drive.children(garage.id).filter { !it.folder }.associate { it.name to it.id },
            )
        }
    }

    companion object {
        private const val TAG = "Backup"
        const val TAG_ROOT = "root"
        const val TAG_MOTO = "moto"
        private const val MANIFEST = "manifest.json"
        private const val SETTINGS = "settings.json"
        private const val BIKES = "bikes.json"
        private const val RIDE = "ride.json"
        private const val SAMPLES = "samples.jsonl.gz"
        private const val GARAGE = "garage"
        private const val RIDES = "rides"
        private const val MOMENTS = "moments"
        private const val JSON = "application/json"
    }
}
