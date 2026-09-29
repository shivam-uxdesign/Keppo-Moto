package com.ridetrack.app.moments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleOwner
import com.ridetrack.app.data.MomentSettings
import com.ridetrack.telemetry.moments.RollingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume

/**
 * Background capture for Moments, owned by the ride's foreground service.
 *
 * The selfie camera streams into a hardware H.264 encoder and the mic into AAC, both
 * into a [RollingBuffer] of the last ~45 s. Nothing touches storage until a request comes
 * in: a clip is cut from the buffer once its after-window has passed; a photo uses the
 * same camera session. The ride never depends on any of this. Every failure here only
 * pauses Moments, and every step is written to the ride's [MomentLog].
 */
class MomentRecorder(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val repo: MomentRepository,
    private val hub: MomentsHub,
    private val settings: MomentSettings,
    private val landscapeMount: Boolean,
    private val scope: CoroutineScope,
    rideId: String,
) {
    private val buffer = RollingBuffer()
    private val requests = Channel<MomentRequest>(Channel.UNLIMITED)
    private val main = ContextCompat.getMainExecutor(context)
    private val log = MomentLog(File(repo.dir(rideId), MomentLog.FILE_NAME))

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    @Volatile private var encoder: VideoEncoder? = null
    @Volatile private var rotationDegrees = 0
    /** Formats of the stream that's in the buffer; outlive the encoder that made them. */
    @Volatile private var videoFormat: MediaFormat? = null
    private var audio: AudioEncoder? = null

    private val reasons = linkedSetOf<PauseReason>()
    private var videoBound = false
    /** Thermal SEVERE: keep filming, but smaller and lighter. */
    private var lowPower = false
    private var boundLowPower = false
    private var bindAtMillis = 0L
    private var watchdogRebinds = 0
    private var worker: Job? = null
    private var watcher: Job? = null
    private var idleJob: Job? = null
    private var retryJob: Job? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private val targetRotation get() = if (landscapeMount) Surface.ROTATION_90 else Surface.ROTATION_0

    fun start() {
        hub.setStatus(MomentStatus.STARTING)
        log.log(
            "start: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
                "quality=${settings.quality} photos=${settings.photos} braking=${settings.braking} accel=${settings.acceleration} " +
                "lean=${settings.lean}; camera=${granted(Manifest.permission.CAMERA)} mic=${granted(Manifest.permission.RECORD_AUDIO)}; " +
                "free=${freeBytes() / 1_000_000}MB",
        )
        if (!granted(Manifest.permission.CAMERA)) {
            log.error("no camera permission; Moments off for this ride")
            log.flush()
            hub.setStatus(MomentStatus.PAUSED, PauseReason.NO_PERMISSION)
            return
        }
        worker = scope.launch(Dispatchers.IO) { for (r in requests) handle(r) }
        watcher = scope.launch {
            var tick = 0
            while (isActive) {
                delay(10_000)
                tick++
                setReason(PauseReason.LOW_STORAGE, lowStorage())
                withContext(Dispatchers.Main) { watchdog() }
                if (tick % 6 == 0) log.log("status: ${hub.state.value.status} reasons=$reasons encoder=${encoderInfo()} ${buffer.describe()} thermal=${thermal()}")
                log.flush()
            }
        }
        watchThermal()
        hub.attach { requests.trySend(it) }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                bindCamera()
            } catch (e: ExecutionException) {
                log.error("camera provider unavailable", e)
                setReason(PauseReason.FAILED, true)
            }
        }, main)
    }

    /** Auto-pause: keep filming for 20 s (an event's after-window may still be running), then idle. */
    fun setRidePaused(paused: Boolean) {
        idleJob?.cancel()
        if (paused) {
            idleJob = scope.launch {
                delay(20_000)
                setReason(PauseReason.RIDE_PAUSED, true)
            }
        } else {
            setReason(PauseReason.RIDE_PAUSED, false)
        }
    }

    /** Ride over: write what's queued (bounded), then release everything. */
    suspend fun finish() {
        log.log("finish: ${buffer.describe()} saved=${hub.state.value.saved}")
        hub.detach()
        hub.drainQueued().forEach { requests.trySend(it) }
        requests.close()
        if (withTimeoutOrNull(25_000) { worker?.join() } == null) log.error("finish: timed out writing queued moments")
        worker?.cancel()
        watcher?.cancel()
        idleJob?.cancel()
        retryJob?.cancel()
        withContext(Dispatchers.Main) {
            runCatching { provider?.unbindAll() }
            val pm = context.getSystemService<PowerManager>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalListener?.let { pm?.removeThermalStatusListener(it) }
        }
        stopAudio()
        encoder?.release()
        encoder = null
        buffer.clear()
        hub.reset()
        log.log("finished")
        log.flush()
    }

    // ---- Camera -------------------------------------------------------------------------

    private val videoWanted: Boolean
        get() = reasons.none { it == PauseReason.RIDE_PAUSED || it == PauseReason.HOT || it == PauseReason.LOW_STORAGE }

    /** (Re)binds the use cases for the current state. Main thread. */
    private fun bindCamera() {
        val p = provider ?: return
        val front = p.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA)
        val selector = when {
            front -> CameraSelector.DEFAULT_FRONT_CAMERA
            p.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> {
                log.error("no camera found")
                setReason(PauseReason.FAILED, true)
                return
            }
        }
        val withVideo = videoWanted
        val capture = imageCapture ?: ImageCapture.Builder()
            .setTargetRotation(targetRotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
            .also { imageCapture = it }
        val useCases = buildList {
            add(capture)
            if (withVideo) add(buildPreview())
        }
        try {
            p.unbindAll()
            val cam = p.bindToLifecycle(owner, selector, *useCases.toTypedArray())
            camera = cam
            videoBound = withVideo
            boundLowPower = lowPower
            bindAtMillis = System.currentTimeMillis()
            log.log("bound ${if (front) "front" else "back"} camera: photo${if (withVideo) " + video" + (if (lowPower) " (low power)" else "") else " only"}")
            cam.cameraInfo.cameraState.removeObservers(owner)
            cam.cameraInfo.cameraState.observe(owner) { st -> onCameraState(st) }
            if (withVideo) startAudio() else stopAudio()
        } catch (e: Exception) {
            log.error("camera bind failed", e)
            setReason(PauseReason.CAMERA_BUSY, true)
            scheduleRetry()
        }
    }

    private fun buildPreview(): Preview {
        val size = when {
            lowPower -> Size(640, 480)
            settings.quality.height >= 1080 -> Size(1920, 1080)
            else -> Size(1280, 720)
        }
        val bitrate = if (lowPower) LOW_POWER_BITRATE else settings.quality.bitrate
        val pv = Preview.Builder()
            .setTargetRotation(targetRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .build()
        pv.setSurfaceProvider(main) { request ->
            log.log("surface requested: ${request.resolution.width}x${request.resolution.height}")
            val enc = try {
                VideoEncoder(
                    request.resolution, bitrate, buffer,
                    onFormat = { f ->
                        videoFormat = f
                        log.log("video format: ${f.getString(MediaFormat.KEY_MIME)} ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}")
                    },
                    onFirstFrame = { log.log("first video frame encoded") },
                )
            } catch (e: Exception) {
                log.error("video encoder unavailable", e)
                request.willNotProvideSurface()
                setReason(PauseReason.FAILED, true)
                return@setSurfaceProvider
            }
            // A new encoder session: older samples came from a different stream.
            if (encoder != null || videoFormat != null) buffer.clear()
            encoder = enc
            request.setTransformationInfoListener(main) { rotationDegrees = it.rotationDegrees }
            request.provideSurface(enc.inputSurface, main) { result ->
                log.log("surface released (result ${result.resultCode}) after ${enc.frames} frames")
                enc.release()
                if (encoder === enc) encoder = null
            }
            refreshStatus()
        }
        return pv
    }

    /**
     * Video is wanted but frames aren't arriving: rebind (up to 3 times), then give up
     * with a clear reason instead of silently producing no clips.
     */
    private fun watchdog() {
        if (!videoBound || provider == null || PauseReason.FAILED in reasons || PauseReason.CAMERA_BUSY in reasons) return
        val now = System.currentTimeMillis()
        val enc = encoder
        val stalled = when {
            enc == null -> now - bindAtMillis > STALL_MILLIS
            enc.frames == 0L -> now - bindAtMillis > STALL_MILLIS
            else -> now - enc.lastFrameMillis > STALL_MILLIS
        }
        if (!stalled) {
            if (enc != null && enc.frames > 0) watchdogRebinds = 0
            return
        }
        watchdogRebinds++
        log.error("watchdog: no video frames (encoder=${encoderInfo()}), rebind $watchdogRebinds/3")
        if (watchdogRebinds > 3) {
            setReason(PauseReason.FAILED, true)
        } else {
            bindCamera()
        }
    }

    private fun encoderInfo(): String = encoder?.let { "${it.frames} frames" } ?: "none"

    private fun onCameraState(st: CameraState) {
        val err = st.error
        log.log("camera ${st.type}" + (err?.let { " error ${it.code}" } ?: ""))
        if (err != null && (err.code == CameraState.ERROR_CAMERA_IN_USE || err.code == CameraState.ERROR_MAX_CAMERAS_IN_USE ||
                err.code == CameraState.ERROR_CAMERA_DISABLED)
        ) {
            setReason(PauseReason.CAMERA_BUSY, true)
            scheduleRetry()
        } else if (st.type == CameraState.Type.OPEN) {
            setReason(PauseReason.CAMERA_BUSY, false)
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch(Dispatchers.Main) {
            delay(30_000)
            bindCamera()
        }
    }

    private fun startAudio() {
        if (audio != null) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            log.log("no microphone permission: clips without sound")
            return
        }
        audio = try {
            AudioEncoder(buffer)
        } catch (e: Exception) {
            log.error("audio unavailable, clips without sound", e)
            null
        }
    }

    private fun stopAudio() {
        audio?.release()
        audio = null
    }

    /** Reasons are only touched on the main thread; callers may be on any thread. */
    private fun setReason(reason: PauseReason, on: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            val changed = if (on) reasons.add(reason) else reasons.remove(reason)
            if (!changed) return@launch
            log.log(if (on) "paused: $reason" else "resumed: $reason cleared")
            rebindIfNeeded()
            refreshStatus()
        }
    }

    private fun rebindIfNeeded() {
        if (provider == null || PauseReason.FAILED in reasons) return
        if (videoWanted != videoBound || (videoBound && lowPower != boundLowPower)) bindCamera()
    }

    private fun refreshStatus() {
        val reason = reasons.firstOrNull()
        when {
            reason != null -> hub.setStatus(MomentStatus.PAUSED, reason)
            encoder != null -> hub.setStatus(MomentStatus.ARMED)
            else -> hub.setStatus(MomentStatus.STARTING)
        }
    }

    /** SEVERE: film smaller (640×480, low bitrate). CRITICAL: stop filming. Photos continue. */
    private fun watchThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService<PowerManager>() ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            log.log("thermal status $status")
            val wasLow = lowPower
            lowPower = status >= PowerManager.THERMAL_STATUS_SEVERE
            setReason(PauseReason.HOT, status >= PowerManager.THERMAL_STATUS_CRITICAL)
            if (wasLow != lowPower) {
                log.log(if (lowPower) "phone hot: filming at low power" else "phone cooled: filming at full quality")
                rebindIfNeeded()
            }
        }
        thermalListener = l
        pm.addThermalStatusListener(main, l)
    }

    private fun thermal(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService<PowerManager>()?.currentThermalStatus?.toString() ?: "?" else "?"

    // ---- Requests -----------------------------------------------------------------------

    private suspend fun handle(r: MomentRequest) {
        try {
            when (r) {
                is MomentRequest.Clip -> writeClip(r)
                is MomentRequest.Photo -> takePhoto(r)
            }
        } catch (e: Exception) {
            log.error("moment not saved", e)
        } finally {
            hub.setWriting(false)
            log.flush()
        }
    }

    private suspend fun writeClip(r: MomentRequest.Clip) {
        val w = r.window
        val label = w.types.joinToString("+").ifEmpty { "test" }
        log.log("clip requested: $label, window ${(w.endMillis - w.startMillis) / 1000}s")
        // Let the encoders catch up with the end of the window.
        val wait = w.endMillis + ENCODER_LATENCY_MILLIS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (lowStorage()) {
            log.log("clip skipped: storage almost full (${freeBytes() / 1_000_000}MB free)")
            setReason(PauseReason.LOW_STORAGE, true)
            return
        }
        hub.setWriting(true)
        val vFormat = videoFormat
        if (vFormat == null) {
            log.log("clip skipped: no video yet (encoder=${encoderInfo()}, reasons=$reasons)")
            return
        }
        val (video, audioSamples) = buffer.extract(w.startMillis * 1000, w.endMillis * 1000)
        if (video.isEmpty()) {
            log.log("clip skipped: buffer has no video for the window (${buffer.describe()}, reasons=$reasons)")
            return
        }
        val dir = repo.dir(r.rideId)
        val id = UUID.randomUUID().toString()
        val file = File(dir, "clip-${w.anchorMillis}-${id.take(6)}.mp4")
        val aFormat = audio?.format
        val length = try {
            ClipWriter.writeMp4(file, vFormat, aFormat, video, audioSamples, rotationDegrees)
        } catch (e: Exception) {
            if (aFormat == null) throw e
            // A bad audio track shouldn't cost the clip: retry with video only.
            log.error("mp4 with audio failed, retrying video-only", e)
            ClipWriter.writeMp4(file, vFormat, null, video, emptyList(), rotationDegrees)
        } ?: return
        val clipStartMillis = video.first().wallMicros / 1000
        val thumb = File(dir, file.nameWithoutExtension + ".jpg")
        val hasThumb = ClipWriter.videoThumbnail(file, (w.anchorMillis - clipStartMillis).coerceAtLeast(0), thumb)
        repo.add(
            Moment(
                id = id,
                rideId = r.rideId,
                kind = MomentKind.CLIP,
                types = w.types,
                timeMillis = w.anchorMillis,
                latitude = w.latitude,
                longitude = w.longitude,
                speedMps = w.speedMps,
                peakValue = w.peakValue,
                file = file,
                thumb = thumb.takeIf { hasThumb },
                durationMillis = length,
                starred = false,
                clipStartMillis = clipStartMillis,
            ),
        )
        log.log("clip saved: ${file.name} ${length / 1000}s ${file.length() / 1024}KB, ${video.size} frames, ${audioSamples.size} audio")
        hub.onSaved()
    }

    private suspend fun takePhoto(r: MomentRequest.Photo) {
        val capture = imageCapture
        if (capture == null) {
            log.log("photo skipped: camera not bound")
            return
        }
        if (lowStorage()) {
            log.log("photo skipped: storage almost full")
            return
        }
        val dir = repo.dir(r.rideId)
        val id = UUID.randomUUID().toString()
        val file = File(dir, "photo-${r.timeMillis}-${id.take(6)}.jpg")
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    main,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(true)
                        override fun onError(exception: ImageCaptureException) {
                            log.error("photo failed", exception)
                            cont.resume(false)
                        }
                    },
                )
            }
        }
        if (!ok) return
        val thumb = File(dir, "photo-${r.timeMillis}-${id.take(6)}-t.jpg")
        val hasThumb = ClipWriter.finishPhoto(file, thumb)
        repo.add(
            Moment(
                id = id,
                rideId = r.rideId,
                kind = MomentKind.PHOTO,
                types = emptySet(),
                timeMillis = r.timeMillis,
                latitude = r.latitude,
                longitude = r.longitude,
                speedMps = r.speedMps,
                peakValue = null,
                file = file,
                thumb = thumb.takeIf { hasThumb },
                durationMillis = null,
                starred = false,
            ),
        )
        log.log("photo saved: ${file.name}")
        hub.onSaved()
    }

    private fun freeBytes(): Long = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(-1)

    private fun lowStorage(): Boolean = freeBytes().let { it in 0 until MIN_FREE_BYTES }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ProcessCameraProvider.hasCameraSafe(s: CameraSelector) = runCatching { hasCamera(s) }.getOrDefault(false)

    companion object {
        private const val ENCODER_LATENCY_MILLIS = 800L
        private const val MIN_FREE_BYTES = 1_000_000_000L
        private const val STALL_MILLIS = 10_000L
        private const val LOW_POWER_BITRATE = 1_500_000
    }
}
