package com.ridetrack.app.trash

import org.json.JSONObject
import java.io.File

/** One deleted ride or moment, as Keppo Journal sees it in `deleted.json`. */
data class Deletion(
    val deletedAt: Long,
    val restoredAt: Long? = null,
    val purgedAt: Long? = null,
    /** Moments only: the ride it belongs to. */
    val rideId: String? = null,
) {
    /** Deleted unless restored after the last delete. */
    val isDeleted: Boolean get() = restoredAt == null || deletedAt > restoredAt
}

/**
 * `deleted.json` (keppo.deleted v1): every ride and moment ever deleted on this phone, so Keppo
 * Journal can catch up on deletions it missed. Entries are never removed, only updated.
 * Pure apart from [load]/[save]; see docs/keppo-ride-format.md, "Deletions".
 */
data class DeletionLog(
    val rides: Map<String, Deletion> = emptyMap(),
    val moments: Map<String, Deletion> = emptyMap(),
) {
    fun rideDeleted(id: String, at: Long) = copy(rides = rides + (id to deleted(rides[id], at, null)))
    fun momentDeleted(id: String, rideId: String, at: Long) = copy(moments = moments + (id to deleted(moments[id], at, rideId)))
    fun rideRestored(id: String, at: Long) = copy(rides = rides.update(id) { it.copy(restoredAt = at) })
    fun momentRestored(id: String, at: Long) = copy(moments = moments.update(id) { it.copy(restoredAt = at) })
    fun ridePurged(id: String, at: Long) = copy(rides = rides.update(id) { it.copy(purgedAt = at) })
    fun momentPurged(id: String, at: Long) = copy(moments = moments.update(id) { it.copy(purgedAt = at) })

    fun toJson(): String = JSONObject()
        .put("format", FORMAT).put("v", 1)
        .put("rides", JSONObject().apply { rides.toSortedMap().forEach { (id, d) -> put(id, entry(d, withRide = false)) } })
        .put("moments", JSONObject().apply { moments.toSortedMap().forEach { (id, d) -> put(id, entry(d, withRide = true)) } })
        .toString(1)

    companion object {
        const val FORMAT = "keppo.deleted"
        const val FILE_NAME = "deleted.json"

        fun parse(json: String): DeletionLog {
            val o = JSONObject(json)
            fun map(key: String) = o.optJSONObject(key)?.let { m ->
                m.keys().asSequence().associateWith { id ->
                    val e = m.getJSONObject(id)
                    Deletion(
                        deletedAt = e.getLong("deletedAt"),
                        restoredAt = if (e.isNull("restoredAt")) null else e.optLong("restoredAt"),
                        purgedAt = if (e.isNull("purgedAt")) null else e.optLong("purgedAt"),
                        rideId = if (e.isNull("rideId")) null else e.optString("rideId"),
                    )
                }
            }.orEmpty()
            return DeletionLog(map("rides"), map("moments"))
        }

        /** Deleting again moves deletedAt forward; a purge, once set, stays. */
        private fun deleted(old: Deletion?, at: Long, rideId: String?) =
            old?.copy(deletedAt = at, rideId = rideId ?: old.rideId) ?: Deletion(at, rideId = rideId)

        private fun Map<String, Deletion>.update(id: String, f: (Deletion) -> Deletion) =
            this[id]?.let { this + (id to f(it)) } ?: this

        private fun entry(d: Deletion, withRide: Boolean) = JSONObject().apply {
            if (withRide) put("rideId", d.rideId ?: JSONObject.NULL)
            put("deletedAt", d.deletedAt)
            put("restoredAt", d.restoredAt ?: JSONObject.NULL)
            put("purgedAt", d.purgedAt ?: JSONObject.NULL)
        }
    }
}

/** Keeps [DeletionLog] in `files/journal/deleted.json`, written atomically. */
class DeletionLogStore(dir: File) {
    val file = File(dir, DeletionLog.FILE_NAME)

    @Synchronized
    fun load(): DeletionLog = runCatching { DeletionLog.parse(file.readText()) }.getOrDefault(DeletionLog())

    @Synchronized
    fun update(f: (DeletionLog) -> DeletionLog) {
        val next = f(load())
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.part")
        tmp.writeText(next.toJson())
        tmp.renameTo(file)
    }

    /** The file the provider serves; created empty on first read so the journal always finds it. */
    @Synchronized
    fun fileForReading(): File {
        if (!file.exists()) update { it }
        return file
    }
}
