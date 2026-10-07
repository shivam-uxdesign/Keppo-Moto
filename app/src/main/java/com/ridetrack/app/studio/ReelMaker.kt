package com.ridetrack.app.studio

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.ridetrack.app.R
import com.ridetrack.app.diag.ErrorLog
import com.ridetrack.app.share.ShareImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One piece to make in the background: the project to save (its id set) and what the renderer needs. */
data class MakeJob(val project: ReelProject, val input: RenderInput)

/** What Make all is doing, for Studio and the notification. */
data class MakerState(
    /** The title of the piece being made; null when idle. */
    val current: String? = null,
    val progress: Int? = null,
    val queued: Int = 0,
    val made: Int = 0,
    val failed: Int = 0,
)

/**
 * Makes pieces one after another away from the Studio screen (Make all), with a notification
 * showing progress. The rider can leave Studio; the pieces land in Your Reels as they finish.
 */
@OptIn(UnstableApi::class)
class ReelMaker(
    private val context: Context,
    private val reels: ReelStore,
    private val errors: ErrorLog,
    private val onSaved: (ReelProject) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val pending = ArrayDeque<MakeJob>()
    private var worker: Job? = null
    private val _state = MutableStateFlow(MakerState())
    val state: StateFlow<MakerState> = _state.asStateFlow()

    fun enqueue(jobs: List<MakeJob>) {
        if (jobs.isEmpty()) return
        synchronized(pending) { pending.addAll(jobs) }
        _state.update { it.copy(queued = synchronized(pending) { pending.size }) }
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    /** Stops after nothing more: the one being made is cancelled, the rest dropped. */
    fun cancelAll() {
        synchronized(pending) { pending.clear() }
        worker?.cancel()
        _state.value = MakerState()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION)
    }

    private suspend fun drain() = lock.withLock {
        _state.update { it.copy(made = 0, failed = 0) }
        while (true) {
            val job = synchronized(pending) { pending.removeFirstOrNull() } ?: break
            _state.update { it.copy(current = job.project.title, progress = 0, queued = synchronized(pending) { pending.size }) }
            notify(job.project.title, 0)
            try {
                make(job)
                _state.update { it.copy(made = it.made + 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.record("Studio Make all", "Couldn't make \"${job.project.title}\"", e)
                _state.update { it.copy(failed = it.failed + 1) }
            }
        }
        val s = _state.value
        _state.value = MakerState(made = s.made, failed = s.failed)
        done(s.made, s.failed)
    }

    private suspend fun make(job: MakeJob) {
        val out = File(ShareImages.sharesDir(context), "keppo-piece-${System.currentTimeMillis()}.mp4")
        val done = StudioRenderer(context).render(job.input, out) { p ->
            _state.update { it.copy(progress = p) }
            notify(job.project.title, p)
        }.getOrThrow()
        val saved = reels.save(job.project, done.file, emptyList())
        val withCover = drawReelCover(context, reels, saved, job.input.files, null)
        onSaved(withCover)
    }

    @SuppressLint("MissingPermission")
    private fun notify(title: String, progress: Int) {
        if (!canNotify()) return
        val left = synchronized(pending) { pending.size }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Making \"$title\"")
            .setContentText(if (left > 0) "$left more after this" else "Last one")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION, n)
    }

    @SuppressLint("MissingPermission")
    private fun done(made: Int, failed: Int) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(if (made == 1) "1 piece ready in Your Reels" else "$made pieces ready in Your Reels")
            .apply { if (failed > 0) setContentText("$failed couldn't be made: see Profile › Error log") }
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION, n)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Studio", NotificationManager.IMPORTANCE_LOW).apply { description = "Making Reels in the background"; setShowBadge(false) })
        }
        return true
    }

    private companion object {
        const val CHANNEL = "studio_making"
        const val NOTIFICATION = 4_201
    }
}

/**
 * Draws [p]'s cover from its settings; with no frame chosen yet, picks the best of the hook clip
 * (sharp, lit, a face if there is one) and remembers it. [files] are the clips' videos by moment id.
 */
suspend fun drawReelCover(context: Context, reels: ReelStore, p: ReelProject, files: Map<String, Uri>, route: Bitmap?): ReelProject = withContext(Dispatchers.Default) {
    fun frame(atMs: Long): Bitmap? {
        val spot = ReelCover.spotAt(p.plan, atMs) ?: return null
        val uri = files[spot.segment.bit.momentId] ?: spot.segment.bit.source?.let(Uri::parse) ?: return null
        return ReelCover.frame(context, uri, spot.sourceMs)
    }
    val at = p.coverAtMs ?: ReelCover.candidates(p.plan).mapNotNull { t ->
        frame(t)?.let { b -> (t to ReelCover.score(b)).also { b.recycle() } }
    }.maxByOrNull { it.second }?.first ?: (p.durationMs / 6)
    val f = frame(at) ?: ReelStore.frame(reels.video(p.id), at)
    val line = if (p.coverText) (p.coverLine ?: p.hookLine.ifBlank { p.title }) else null
    val bmp = ReelCover.render(context, f, route, p.vibe, line)
    withContext(Dispatchers.IO) { reels.writeCover(p.id, bmp) }
    bmp.recycle()
    f?.recycle()
    reels.update(p.id) { it.copy(coverAtMs = at) } ?: p
}
