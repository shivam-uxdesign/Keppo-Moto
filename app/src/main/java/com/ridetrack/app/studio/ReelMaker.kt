package com.ridetrack.app.studio

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** One piece to make in the background: the project to save (its id set) and what the renderer needs. */
data class MakeJob(val project: ReelProject, val input: RenderInput)

/** What the maker is doing, for Studio and the notification. */
data class MakerState(
    /** The title of the piece being made; null when idle. */
    val current: String? = null,
    /** The id of the Reel being made. */
    val currentId: String? = null,
    val progress: Int? = null,
    val queued: Int = 0,
    val made: Int = 0,
    val failed: Int = 0,
    /** Reels being made on the Studio screen itself (they keep the app running too). */
    val holding: Int = 0,
) {
    val idle: Boolean get() = current == null && queued == 0 && holding == 0
}

/** A piece finished: saved, or why it couldn't be made. */
sealed interface MakerEvent {
    val id: String
    data class Made(val project: ReelProject) : MakerEvent { override val id get() = project.id }
    data class Failed(override val id: String, val title: String, val message: String) : MakerEvent
}

/**
 * Makes pieces one after another, away from the Studio screen: a foreground service keeps the app
 * running while it works (switching apps or swiping it away doesn't stop it), the queue is kept on
 * disk so pieces left when the app was closed are made the next time it starts, and a
 * notification shows progress. Pieces land in Your Reels as they finish.
 */
@OptIn(UnstableApi::class)
class ReelMaker(
    private val context: Context,
    private val reels: ReelStore,
    private val errors: ErrorLog,
    /** What the renderer needs for a saved queue entry (after the app was closed); null when its clips are gone. */
    private val rebuild: suspend (ReelProject) -> RenderInput?,
    /** New Reels also go to the phone's gallery. */
    private val alsoToGallery: () -> Boolean,
    private val onSaved: (ReelProject) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val pending = ArrayDeque<MakeJob>()
    private var worker: Job? = null
    private val _state = MutableStateFlow(MakerState())
    val state: StateFlow<MakerState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<MakerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<MakerEvent> = _events.asSharedFlow()
    private val queueFile = File(context.filesDir, "studio-queue.json")
    private var current: MakeJob? = null

    /**
     * Adds [jobs] to the queue. [service]: keep the app running with the foreground service (only
     * allowed while the app is on screen; background work keeps itself running instead).
     */
    fun enqueue(jobs: List<MakeJob>, service: Boolean = true) {
        if (jobs.isEmpty()) return
        synchronized(pending) { pending.addAll(jobs) }
        save()
        _state.update { it.copy(queued = synchronized(pending) { pending.size }) }
        start(service)
    }

    /** Keeps the app running while [block] makes a Reel on the Studio screen. */
    suspend fun <T> keepRunning(block: suspend () -> T): T {
        _state.update { it.copy(holding = it.holding + 1) }
        StudioService.start(context)
        try {
            return block()
        } finally {
            _state.update { it.copy(holding = (it.holding - 1).coerceAtLeast(0)) }
        }
    }

    /** Stops making [id] (or takes it out of the queue); the others carry on. */
    fun cancel(id: String) {
        synchronized(pending) { pending.removeAll { it.project.id == id } }
        if (current?.project?.id == id) {
            worker?.cancel()
            worker = null
            current = null
            _state.update { it.copy(current = null, currentId = null, progress = null) }
            if (synchronized(pending) { pending.isNotEmpty() }) start()
        }
        _state.update { it.copy(queued = synchronized(pending) { pending.size }) }
        save()
    }

    /** How many are waiting before [id]; null when it isn't waiting. */
    fun waitingBefore(id: String): Int? = synchronized(pending) { pending.indexOfFirst { it.project.id == id }.takeIf { it >= 0 } }

    /** Pieces left in the queue when the app was closed are made now. */
    fun resume() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { JSONArray(queueFile.readText()) }.getOrNull() } ?: return@launch
            val projects = (0 until saved.length()).mapNotNull { ReelJson.read(saved.optString(it)) }
                .filter { p -> synchronized(pending) { pending.none { it.project.id == p.id } } && current?.project?.id != p.id && reels.get(p.id) == null }
            val jobs = projects.mapNotNull { p ->
                runCatching { rebuild(p) }.onFailure { errors.record("Studio Make all", "Couldn't pick up \"${p.title}\" again", it) }.getOrNull()?.let { MakeJob(p, it) }
            }
            if (jobs.size < projects.size) errors.record("Studio Make all", "${projects.size - jobs.size} waiting pieces couldn't be made: their clips are gone")
            if (jobs.isEmpty()) save() else enqueue(jobs)
        }
    }

    private fun start(service: Boolean = true) {
        if (worker?.isActive != true) worker = scope.launch { drain() }
        if (service) StudioService.start(context)
    }

    /** Stops after nothing more: the one being made is cancelled, the rest dropped. */
    fun cancelAll() {
        synchronized(pending) { pending.clear() }
        worker?.cancel()
        current = null
        save()
        _state.update { MakerState(holding = it.holding) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION)
    }

    /** The queue (the one being made first) on disk, so it survives the app being closed. */
    private fun save() {
        val all = listOfNotNull(current) + synchronized(pending) { pending.toList() }
        runCatching {
            if (all.isEmpty()) queueFile.delete()
            else queueFile.writeText(JSONArray().apply { all.forEach { put(ReelJson.write(it.project)) } }.toString())
        }
    }

    private suspend fun drain() = lock.withLock {
        _state.update { it.copy(made = 0, failed = 0) }
        while (true) {
            val job = synchronized(pending) { pending.removeFirstOrNull() } ?: break
            current = job
            _state.update { it.copy(current = job.project.title, currentId = job.project.id, progress = 0, queued = synchronized(pending) { pending.size }) }
            notify(job.project.title, 0)
            try {
                val saved = make(job)
                _state.update { it.copy(made = it.made + 1) }
                _events.tryEmit(MakerEvent.Made(saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.record("Studio making", "Couldn't make \"${job.project.title}\"", e)
                _state.update { it.copy(failed = it.failed + 1) }
                _events.tryEmit(MakerEvent.Failed(job.project.id, job.project.title, e.message ?: e.javaClass.simpleName))
            }
            current = null
            save()
        }
        val s = _state.value
        _state.update { MakerState(made = s.made, failed = s.failed, holding = it.holding) }
        done(s.made, s.failed)
    }

    private suspend fun make(job: MakeJob): ReelProject {
        val out = File(ShareImages.sharesDir(context), "keppo-piece-${System.currentTimeMillis()}.mp4")
        val done = StudioRenderer(context).render(job.input, out) { p ->
            _state.update { it.copy(progress = p) }
            notify(job.project.title, p)
        }.getOrThrow()
        // A simpler way had to be used: worth knowing, and said on the Reel.
        val note = done.note ?: if (done.failures.isNotEmpty()) "Made with a simpler method on this phone" else null
        if (done.failures.isNotEmpty()) {
            val e = StudioRenderer.ExportFailed("Made on attempt ${done.failures.size + 1}").also { x -> done.failures.forEach { x.addSuppressed(it) } }
            errors.warn("Studio export", "Needed a fallback for \"${job.project.title}\": ${done.note ?: "a simpler method"}", e)
        }
        val saved = reels.save(job.project.copy(note = note), done.file, emptyList())
        if (alsoToGallery()) ShareImages.saveVideo(context, reels.video(saved.id), "Keppo Reel ${saved.id}")
        val withCover = drawReelCover(context, reels, saved, job.input.files, null)
        onSaved(withCover)
        return withCover
    }

    /** The notification the service shows while making. */
    fun ongoing(): android.app.Notification {
        canNotify()
        val s = _state.value
        return builder(s.current ?: "your Reel", s.progress ?: 0).build()
    }

    private fun builder(title: String, progress: Int): NotificationCompat.Builder {
        val left = synchronized(pending) { pending.size }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Making \"$title\"")
            .setContentText(if (left > 0) "$left more after this" else "Last one")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
    }

    private fun openApp(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission")
    private fun notify(title: String, progress: Int) {
        if (!canNotify()) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION, builder(title, progress).build())
    }

    @SuppressLint("MissingPermission")
    private fun done(made: Int, failed: Int) {
        if (!canNotify()) return
        // A separate id: the service's ongoing one goes away with it.
        NotificationManagerCompat.from(context).cancel(NOTIFICATION)
        if (made == 0 && failed == 0) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(if (made == 1) "1 piece ready in Your Reels" else "$made pieces ready in Your Reels")
            .apply { if (failed > 0) setContentText("$failed couldn't be made: see Profile › Error log") }
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(DONE, n)
    }

    private fun canNotify(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Studio", NotificationManager.IMPORTANCE_LOW).apply { description = "Making Reels in the background"; setShowBadge(false) })
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL = "studio_making"
        const val NOTIFICATION = 4_201
        const val DONE = 4_202
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
