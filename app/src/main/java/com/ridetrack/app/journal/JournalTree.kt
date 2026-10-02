package com.ridetrack.app.journal

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The read-only folder Keppo Journal sees on this phone, as document ids. Pure, so it is
 * unit-tested; [RideDocumentsProvider] maps it onto Android's documents API.
 *
 * ```
 * root                       "Keppo Moto"
 * ride:<rideId>              "2026-10-01 Sunday at Nahan"
 * file:<rideId>/<name>       ride.json, route.png, moment files
 * deleted.json               what was deleted, restored or purged (keppo.deleted)
 * ```
 */
object JournalTree {
    const val ROOT = "root"
    const val RIDE_JSON = "ride.json"
    const val ROUTE_PNG = "route.png"
    const val DELETED_JSON = "deleted.json"

    sealed interface Doc {
        data object Root : Doc
        data class Ride(val rideId: String) : Doc
        data class File(val rideId: String, val name: String) : Doc
        /** `deleted.json` at the root. */
        data object Deletions : Doc
    }

    fun id(doc: Doc): String = when (doc) {
        Doc.Root -> ROOT
        is Doc.Ride -> "ride:${doc.rideId}"
        is Doc.File -> "file:${doc.rideId}/${doc.name}"
        Doc.Deletions -> DELETED_JSON
    }

    /** Null for anything we didn't hand out (or that tries to climb out of a ride folder). */
    fun parse(id: String): Doc? = when {
        id == ROOT -> Doc.Root
        id == DELETED_JSON -> Doc.Deletions
        id.startsWith("ride:") -> id.removePrefix("ride:").takeIf(::safe)?.let { Doc.Ride(it) }
        id.startsWith("file:") -> {
            val rest = id.removePrefix("file:")
            val slash = rest.indexOf('/')
            if (slash <= 0) null
            else {
                val ride = rest.substring(0, slash)
                val name = rest.substring(slash + 1)
                if (safe(ride) && safe(name)) Doc.File(ride, name) else null
            }
        }
        else -> null
    }

    /** "2026-10-01 Sunday at Nahan", safe as a folder name. */
    fun rideFolderName(startMillis: Long, name: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DATE.format(Instant.ofEpochMilli(startMillis).atZone(zone))
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), " ").replace(Regex("\\s+"), " ").trim()
        return if (clean.isEmpty()) date else "$date $clean"
    }

    /** What a ride folder holds: its data, its route picture, then the moment files that exist on the phone. */
    fun rideChildren(rideId: String, momentFiles: List<String>): List<Doc.File> =
        listOf(Doc.File(rideId, RIDE_JSON), Doc.File(rideId, ROUTE_PNG)) +
            momentFiles.filter(::safe).filter { it != RIDE_JSON && it != ROUTE_PNG }.distinct().map { Doc.File(rideId, it) }

    fun isChild(parentId: String, childId: String): Boolean {
        val parent = parse(parentId) ?: return false
        val child = parse(childId) ?: return false
        return when (parent) {
            Doc.Root -> child != Doc.Root
            is Doc.Ride -> child is Doc.File && child.rideId == parent.rideId
            is Doc.File, Doc.Deletions -> false
        }
    }

    fun mime(name: String): String = when {
        name.endsWith(".json") -> "application/json"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".webp") -> "image/webp"
        name.endsWith(".mp4") -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun safe(s: String) = s.isNotEmpty() && !s.contains('/') && !s.contains('\\') && s != "." && s != ".."

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}
