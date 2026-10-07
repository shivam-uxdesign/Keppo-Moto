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
        val gemini = FirebaseTranscriber()
        val waiting = c.moments.untranscribed().filter { it.timeMillis >= t.since }
        t.setWaiting(waiting.size)
        if (waiting.isEmpty()) return@withContext Result.success()
        val rides = HashSet<String>()
        try {
            var models = listOfNotNull(t.model) + FirebaseTranscriber.MODELS.filter { it != t.model }
            for ((i, m) in waiting.withIndex()) {
                if (isStopped) break
                if (!m.file.exists()) continue
                val audio = File(applicationContext.cacheDir, "transcribe-${m.id}.m4a")
                try {
                    val text = if (ClipAudio.extract(m.file, audio)) {
                        if (audio.length() > MAX_BYTES) "" else transcribe(gemini, models, audio.readBytes()) { working ->
                            t.model = working
                            models = listOf(working) + models.filter { it != working }
                        }
                    } else {
                        ""
                    }
                    c.moments.setTranscript(m.id, text)
                    rides += m.rideId
                } finally {
                    audio.delete()
                }
                t.setWaiting(waiting.size - i - 1)
                delay(BETWEEN_MILLIS)
            }
            t.setError(null)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by the system (or a newer job): not an error, the clips wait for the next run.
            throw e
        } catch (e: FirebaseTranscriber.Busy) {
            t.setError(FirebaseTranscriber.busyMessage(e))
            Result.retry()
        } catch (e: Exception) {
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

    /** Tries [models] in order until one exists; tells [onWorking] which one did. */
    /**
     * Tries [models] in order until one works, jumping to any replacement Google's error names;
     * only when all of them fail, the name set in Firebase Remote Config (`gemini_model`).
     * Tells [onWorking] which one did.
     */
    private suspend fun transcribe(t: FirebaseTranscriber, models: List<String>, audio: ByteArray, onWorking: (String) -> Unit): String {
        val queue = ArrayDeque(models)
        val tried = HashSet<String>()
        var remoteTried = false
        var last: Exception? = null
        var busy: FirebaseTranscriber.Busy? = null
        while (true) {
            val name = queue.removeFirstOrNull() ?: if (!remoteTried) {
                remoteTried = true
                RemoteModel.get()?.takeIf { it !in tried } ?: continue
            } else {
                break
            }
            if (!tried.add(name)) continue
            try {
                // A request that hangs would hold up every clip after it.
                val text = kotlinx.coroutines.withTimeoutOrNull(REQUEST_TIMEOUT_MS) { t.transcribe(name, audio, "audio/mp4") }
                    ?: throw java.io.IOException("Gemini didn't answer within a minute")
                return text.also { onWorking(name) }
            } catch (e: FirebaseTranscriber.Busy) {
                // Each model has its own free allowance: try the next one.
                busy = e
            } catch (e: Exception) {
                if (!FirebaseTranscriber.isMissingModel(e)) throw e
                last = e
                FirebaseTranscriber.suggestedModel(e, name)?.takeIf { it !in tried }?.let { queue.addFirst(it) }
            }
        }
        throw busy ?: last ?: IllegalStateException("no Gemini model")
    }

    private companion object {
        const val BETWEEN_MILLIS = 5_000L
        const val REQUEST_TIMEOUT_MS = 60_000L
        /** Gemini takes up to 20 MB inline; base64 adds a third. */
        const val MAX_BYTES = 14L * 1024 * 1024
    }
}
