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
 * "Write down what I say": talking clips are transcribed with Gemini after the ride, in the
 * background (on Wi-Fi unless the rider allows mobile data). The key stays on this phone and
 * is never backed up.
 */
class Transcripts(private val context: Context) {
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val state = context.getSharedPreferences("transcripts", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(TranscriptStatus(lastError = state.getString(ERROR, null)))
    val status: StateFlow<TranscriptStatus> = _status.asStateFlow()

    var apiKey: String?
        get() = secrets.getString(KEY, null)?.takeIf { it.isNotBlank() }
        set(v) {
            secrets.edit().putString(KEY, v?.trim()).apply()
            setError(null)
            state.edit().remove(MODEL).apply()
        }

    fun setError(e: String?) {
        state.edit().putString(ERROR, e).apply()
        _status.value = _status.value.copy(lastError = e)
    }

    fun setWaiting(n: Int) {
        _status.value = _status.value.copy(waiting = n)
    }

    /** Transcribe soon, if it's on and there's a key. */
    fun schedule(wifiOnly: Boolean) {
        if (apiKey == null) return
        val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    internal var model: String?
        get() = state.getString(MODEL, null)
        set(v) = state.edit().putString(MODEL, v).apply()

    private companion object {
        const val KEY = "gemini_api_key"
        const val ERROR = "last_error"
        const val MODEL = "model"
        const val WORK = "keppo-transcribe"
    }
}

/** Transcribes waiting talking clips, a few seconds apart (the free tier limits requests per minute). */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = (applicationContext as RideTrackApp).container
        val t = c.transcripts
        val key = t.apiKey ?: return@withContext Result.success()
        if (!c.settings.settings.first().moments.transcribe) return@withContext Result.success()
        val gemini = GeminiTranscriber(key)
        val waiting = c.moments.untranscribed()
        t.setWaiting(waiting.size)
        if (waiting.isEmpty()) return@withContext Result.success()
        val rides = HashSet<String>()
        try {
            val model = t.model ?: gemini.pickModel().also { t.model = it }
            for ((i, m) in waiting.withIndex()) {
                if (isStopped) break
                if (!m.file.exists()) continue
                val audio = File(applicationContext.cacheDir, "transcribe-${m.id}.m4a")
                try {
                    val text = if (ClipAudio.extract(m.file, audio)) {
                        if (audio.length() > MAX_BYTES) "" else gemini.transcribe(model, audio.readBytes(), "audio/mp4")
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
        } catch (e: GeminiTranscriber.BadKey) {
            t.setError("Gemini didn't accept the key: ${e.message}")
            Result.failure()
        } catch (e: GeminiTranscriber.Busy) {
            t.setError("Gemini is busy (free-tier limit); trying again later")
            Result.retry()
        } catch (e: Exception) {
            t.setError("Couldn't transcribe: ${e.message}")
            t.model = null
            Result.retry()
        } finally {
            // Keppo Journal and the backup pick up the new transcripts.
            rides.forEach { c.journal.onRideSaved(it) }
            if (rides.isNotEmpty()) c.backUpSoon()
        }
    }

    private companion object {
        const val BETWEEN_MILLIS = 5_000L
        /** Gemini takes up to 20 MB inline; base64 adds a third. */
        const val MAX_BYTES = 14L * 1024 * 1024
    }
}
