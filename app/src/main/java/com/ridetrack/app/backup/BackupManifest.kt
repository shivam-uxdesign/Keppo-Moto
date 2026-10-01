package com.ridetrack.app.backup

import org.json.JSONObject

/** A file in the backup. [deletedAt] set = removed on the phone; purged from Drive after the grace period. */
data class ManifestFile(val size: Long, val deletedAt: Long? = null)

/**
 * A ride in the backup. [device] is the install that last held this ride in full: only that
 * install may mark it (or its files) deleted, so a fresh phone that hasn't restored yet can
 * never wipe the backup by simply not having the rides.
 */
data class ManifestRide(
    val fingerprint: String,
    val device: String,
    val files: Map<String, ManifestFile> = emptyMap(),
    val deletedAt: Long? = null,
)

/** `manifest.json`: what's in the backup, so each run uploads only what changed. */
data class Manifest(
    val updatedAt: Long = 0,
    val appVersion: String = "",
    val rides: Map<String, ManifestRide> = emptyMap(),
    val garage: Map<String, ManifestFile> = emptyMap(),
) {
    val liveRides: Map<String, ManifestRide> get() = rides.filterValues { it.deletedAt == null }

    fun toJson(): String = JSONObject().put("v", 1).put("updatedAt", updatedAt).put("appVersion", appVersion)
        .put("rides", JSONObject().apply {
            rides.toSortedMap().forEach { (id, r) ->
                put(id, JSONObject().put("fingerprint", r.fingerprint).put("device", r.device)
                    .put("deletedAt", r.deletedAt ?: JSONObject.NULL).put("files", files(r.files)))
            }
        })
        .put("garage", files(garage))
        .toString(1)

    companion object {
        fun parse(json: String): Manifest {
            val o = JSONObject(json)
            val rides = o.optJSONObject("rides")?.let { r ->
                r.keys().asSequence().associateWith { id ->
                    val e = r.getJSONObject(id)
                    ManifestRide(
                        fingerprint = e.getString("fingerprint"),
                        device = e.optString("device"),
                        files = parseFiles(e.optJSONObject("files")),
                        deletedAt = if (e.isNull("deletedAt")) null else e.optLong("deletedAt"),
                    )
                }
            }.orEmpty()
            return Manifest(o.optLong("updatedAt"), o.optString("appVersion"), rides, parseFiles(o.optJSONObject("garage")))
        }

        private fun files(m: Map<String, ManifestFile>) = JSONObject().apply {
            m.toSortedMap().forEach { (name, f) -> put(name, JSONObject().put("size", f.size).put("deletedAt", f.deletedAt ?: JSONObject.NULL)) }
        }

        private fun parseFiles(o: JSONObject?): Map<String, ManifestFile> = o?.let { f ->
            f.keys().asSequence().associateWith { name ->
                val e = f.getJSONObject(name)
                ManifestFile(e.getLong("size"), if (e.isNull("deletedAt")) null else e.optLong("deletedAt"))
            }
        }.orEmpty()
    }
}

/** A ride on the phone, as backup sees it: its ride.json fingerprint and its moment files (name → bytes). */
data class LocalRide(val id: String, val fingerprint: String, val files: Map<String, Long>)

/** What one backup run has to do, worked out from the phone and the manifest. */
data class BackupPlan(
    /** Rides whose ride.json + samples must be (re)uploaded. */
    val rideData: List<String> = emptyList(),
    /** (rideId, file) moment files to upload. */
    val media: List<Pair<String, String>> = emptyList(),
    /** Rides fully present on this phone that another install held last: take them over. */
    val claim: List<String> = emptyList(),
    /** Rides / files deleted on this phone: mark deleted now, purge later. */
    val tombstoneRides: List<String> = emptyList(),
    val tombstoneFiles: List<Pair<String, String>> = emptyList(),
    /** Grace period over: remove from Drive and the manifest. */
    val purgeRides: List<String> = emptyList(),
    val purgeFiles: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty get() = rideData.isEmpty() && media.isEmpty() && claim.isEmpty() && tombstoneRides.isEmpty() &&
        tombstoneFiles.isEmpty() && purgeRides.isEmpty() && purgeFiles.isEmpty()
}

object BackupPlanner {
    const val GRACE_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun plan(local: List<LocalRide>, manifest: Manifest, device: String, now: Long, graceMillis: Long = GRACE_MILLIS): BackupPlan {
        val localIds = local.map { it.id }.toSet()
        val rideData = mutableListOf<String>()
        val media = mutableListOf<Pair<String, String>>()
        val claim = mutableListOf<String>()
        val tombFiles = mutableListOf<Pair<String, String>>()
        local.forEach { l ->
            val e = manifest.rides[l.id]
            if (e == null || e.deletedAt != null || e.fingerprint != l.fingerprint) rideData += l.id
            l.files.forEach { (name, size) ->
                val f = e?.files?.get(name)
                if (f == null || f.deletedAt != null || f.size != size) media += l.id to name
            }
            if (e != null) {
                val missing = e.files.filter { (name, f) -> f.deletedAt == null && name !in l.files }.keys
                if (e.device == device) missing.forEach { tombFiles += l.id to it }
                else if (missing.isEmpty() && l.id !in rideData) claim += l.id
            }
        }
        val tombRides = manifest.rides.filter { (id, e) -> id !in localIds && e.deletedAt == null && e.device == device }.keys.toList()
        val purgeRides = manifest.rides.filter { (_, e) -> e.deletedAt != null && now - e.deletedAt >= graceMillis }.keys.toList()
        val purgeFiles = manifest.rides.filterKeys { it !in purgeRides }.flatMap { (id, e) ->
            e.files.filter { (_, f) -> f.deletedAt != null && now - f.deletedAt >= graceMillis }.keys.map { id to it }
        }
        return BackupPlan(rideData, media, claim, tombRides, tombFiles, purgeRides, purgeFiles)
    }

    /** Rides in the backup (not deleted) that this phone doesn't have yet: what a restore brings back. */
    fun toRestore(manifest: Manifest, localIds: Set<String>): List<String> =
        manifest.liveRides.keys.filter { it !in localIds }.sorted()
}
