package com.ridetrack.app.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ridetrack.app.AppContainer
import com.ridetrack.app.BuildConfig
import com.ridetrack.app.moments.Moment
import com.ridetrack.app.moments.MomentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * BETA TOOL — writes a ZIP of ride exports to the cache and opens the share sheet.
 * Each ride gets `<stem>.json` (everything), `<stem>.csv` (1 Hz samples) and `<stem>.gpx`.
 */
object ExportShare {
    private const val AUTHORITY_SUFFIX = ".exports"

    /** Exports [rideIds] (or every saved ride when null). Returns the number of rides exported. */
    suspend fun exportAndShare(context: Context, c: AppContainer, rideIds: List<String>? = null): Int {
        val zip = withContext(Dispatchers.IO) {
            val all = c.rides.observeCompleted().first()
            val rides = if (rideIds == null) all else all.filter { it.id in rideIds }
            if (rides.isEmpty()) return@withContext null
            val bikes = c.bikes.observeBikes().first().associateBy { it.id }
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val name = if (rides.size == 1) RideExporter.fileStem(rides.first()) else "ridetrack-export-${rides.size}-rides"
            val file = File(dir, "$name.zip")
            ZipOutputStream(file.outputStream().buffered()).use { out ->
                fun put(path: String, text: String) {
                    out.putNextEntry(ZipEntry(path))
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
                rides.forEach { ride ->
                    val track = c.rides.track(ride.id)
                    val bike = bikes[ride.bikeId]
                    val stem = RideExporter.fileStem(ride)
                    val folder = if (rides.size == 1) "" else "$stem/"
                    put("$folder$stem.json", RideExporter.json(ride, bike?.displayName, bike?.let { it.calibration != null }, track, BuildConfig.VERSION_NAME))
                    put("$folder$stem.csv", RideExporter.csv(ride, track))
                    put("$folder$stem.gpx", RideExporter.gpx(ride, track))
                    // Moments diagnostics: the capture log and what was saved (files themselves are not included).
                    val log = File(c.moments.dir(ride.id), MomentLog.FILE_NAME)
                    if (log.exists()) put("${folder}moments-log.txt", log.readText())
                    val moments = c.moments.forRide(ride.id)
                    if (moments.isNotEmpty() || log.exists()) put("${folder}moments.json", momentsJson(moments))
                }
                put("rides_summary.csv", RideExporter.summaryCsv(rides.map { it to bikes[it.bikeId]?.displayName }))
            }
            file to rides.size
        } ?: return 0

        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, zip.first)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Keppo Moto export")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ride data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return zip.second
    }

    private fun momentsJson(list: List<Moment>): String = list.joinToString(",\n", "[\n", "\n]\n") { m ->
        "  {\"kind\": \"${m.kind}\", \"types\": \"${m.types.joinToString("+")}\", \"time\": \"${java.time.Instant.ofEpochMilli(m.timeMillis)}\", " +
            "\"file\": \"${m.file.name}\", \"bytes\": ${m.file.length()}, \"durationMs\": ${m.durationMillis ?: "null"}, " +
            "\"thumb\": ${m.thumb?.exists() == true}}"
    }
}
