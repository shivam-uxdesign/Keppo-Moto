package com.ridetrack.app.transcribe

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.ridetrack.app.RideTrackApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Where transcription stands, for Profile. */
data class TranscriptStatus(val waiting: Int = 0, val lastError: String? = null)

/**
 * "Write down what I say": talking clips are transcribed with Gemini through the app's Firebase
 * project after the ride, in the background (on Wi-Fi unless the rider allows mobile data).
 */
class Transcripts(private val context: Context) {
    private val state = context.getSharedPreferences("transcripts", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(TranscriptStatus(lastError = state.getString(ERROR, null)))
    val status: StateFlow<TranscriptStatus> = _status.asStateFlow()

    /** Built with the Firebase config (app/google-services.json); without it there's nothing to send to. */
    val available: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    fun setError(e: String?) {
        state.edit().putString(ERROR, e).apply()
        _status.value = _status.value.copy(lastError = e)
    }

    fun setWaiting(n: Int) {
        _status.value = _status.value.copy(waiting = n)
    }

    /** Transcribe soon. */
    fun schedule(wifiOnly: Boolean) {
        if (!available) return
        val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        // KEEP: a job already waiting or running carries on (it picks up every waiting clip);
        // replacing it would cancel the one in progress.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Only clips filmed after this are written out in the background: older ones are skipped
     * (Studio still reads them when you make a Reel of that ride). Set the first time it's asked.
     */
    val since: Long
        get() = state.getLong(SINCE, 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { state.edit().putLong(SINCE, it).apply() }

    /** The Gemini model that last worked. */
    internal var model: String?
        get() = state.getString(MODEL, null)
        set(v) = state.edit().putString(MODEL, v).apply()

    private companion object {
        const val ERROR = "last_error"
        const val MODEL = "model"
        const val SINCE = "since"
        const val WORK = "keppo-transcribe"
    }
}

/** Transcribes waiting talking clips, a few seconds apart (the free tier limits requests per minute). */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = (applicationContext as RideTrackApp).container
        val t = c.transcripts
        if (!t.available || !c.settings.settings.first().moments.transcribe) return@withContext Result.success()
        val waiting = c.moments.untranscribed().filter { it.timeMillis >= t.since }
        t.setWaiting(waiting.size)
        if (waiting.isEmpty()) return@withContext Result.success()
        val rides = HashSet<String>()
        try {
            val g = com.ridetrack.app.studio.StudioGemini(preferred = { t.model }, onWorking = { t.model = it })
            // Clips Studio already captioned need no request: their words become the transcript.
            val todo = ArrayList<com.ridetrack.app.moments.Moment>()
            for (m in waiting) {
                val saved = com.ridetrack.app.studio.StudioText.load(m.file)
                if (saved != null) { c.moments.setTranscript(m.id, saved.joinToString(" ") { it.text }); rides += m.rideId } else if (m.file.exists()) todo += m
            }
            // Several clips per request: the free tier allows only a few requests a day.
            var left = todo.size
            for (batch in batches(todo)) {
                if (isStopped) break
                val audios = batch.map { m -> m to File(applicationContext.cacheDir, "transcribe-${m.id}.m4a") }
                try {
                    val usable = audios.filter { (m, f) -> ClipAudio.extract(m.file, f) && f.length() <= MAX_BYTES }
                    audios.filter { it !in usable }.forEach { (m, _) -> c.moments.setTranscript(m.id, ""); rides += m.rideId }
                    val results = if (usable.isEmpty()) emptyList() else g.captionsBatch(usable.map { it.second })
                    usable.forEachIndexed { i, (m, _) ->
                        val lines = results.getOrNull(i) ?: return@forEachIndexed // skipped by Gemini: try next time
                        com.ridetrack.app.studio.StudioText.save(m.file, lines)
                        c.moments.setTranscript(m.id, lines.joinToString(" ") { it.text })
                        rides += m.rideId
                    }
                } finally {
                    audios.forEach { it.second.delete() }
                }
                left -= batch.size
                t.setWaiting(left)
                delay(BETWEEN_MILLIS)
            }
            t.setError(null)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by the system (or a newer job): not an error, the clips wait for the next run.
            throw e
        } catch (e: FirebaseTranscriber.Busy) {
            c.errors.record("Transcripts", FirebaseTranscriber.busyMessage(e), e)
            t.setError(FirebaseTranscriber.busyMessage(e))
            Result.retry()
        } catch (e: Exception) {
            c.errors.record("Transcripts", "Couldn't transcribe", e)
            t.setError(
                if (AppCheckSetup.isRejected(e)) "Firebase didn't accept this phone (App Check). Add the debug token shown below in Firebase › App Check › Manage debug tokens."
                else "Couldn't transcribe: ${e.message}",
            )
            t.model = null
            Result.retry()
        } finally {
            // Keppo Journal and the backup pick up the new transcripts.
            rides.forEach { c.journal.onRideSaved(it) }
            if (rides.isNotEmpty()) c.backUpSoon()
        }
    }

    /** Up to 8 clips per request, and not more sound than Gemini takes inline. */
    private fun batches(list: List<com.ridetrack.app.moments.Moment>): List<List<com.ridetrack.app.moments.Moment>> {
        val out = ArrayList<List<com.ridetrack.app.moments.Moment>>()
        var cur = ArrayList<com.ridetrack.app.moments.Moment>()
        var bytes = 0L
        for (m in list) {
            // About 12 kB of sound per second of video.
            val est = (m.durationMillis ?: 20_000) * 12
            if (cur.isNotEmpty() && (cur.size == 8 || bytes + est > MAX_BATCH_BYTES)) { out += cur; cur = ArrayList(); bytes = 0 }
            cur += m
            bytes += est
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private companion object {
        const val MAX_BATCH_BYTES = 9L * 1024 * 1024
        const val BETWEEN_MILLIS = 5_000L
        /** Gemini takes up to 20 MB inline; base64 adds a third. */
        const val MAX_BYTES = 14L * 1024 * 1024
    }
}
