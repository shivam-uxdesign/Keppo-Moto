package com.ridetrack.app.moments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
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
 * pauses Moments.
 */
class MomentRecorder(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val repo: MomentRepository,
    private val hub: MomentsHub,
    private val settings: MomentSettings,
    private val landscapeMount: Boolean,
    private val scope: CoroutineScope,
) {
    private val buffer = RollingBuffer()
    private val requests = Channel<MomentRequest>(Channel.UNLIMITED)
    private val main = ContextCompat.getMainExecutor(context)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null

    @Volatile private var encoder: VideoEncoder? = null
    @Volatile private var rotationDegrees = 0
    private var audio: AudioEncoder? = null

    private val reasons = linkedSetOf<PauseReason>()
    private var videoBound = false
    private var worker: Job? = null
    private var watcher: Job? = null
    private var idleJob: Job? = null
    private var retryJob: Job? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private val targetRotation get() = if (landscapeMount) Surface.ROTATION_90 else Surface.ROTATION_0

    fun start() {
        hub.setStatus(MomentStatus.STARTING)
        if (!granted(Manifest.permission.CAMERA)) {
            hub.setStatus(MomentStatus.PAUSED, PauseReason.NO_PERMISSION)
            return
        }
        worker = scope.launch(Dispatchers.IO) { for (r in requests) handle(r) }
        watcher = scope.launch {
            while (isActive) {
                setReason(PauseReason.LOW_STORAGE, lowStorage())
                delay(30_000)
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
                Log.e(TAG, "Camera provider unavailable", e)
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
        hub.detach()
        hub.drainQueued().forEach { requests.trySend(it) }
        requests.close()
        withTimeoutOrNull(25_000) { worker?.join() }
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
    }

    // ---- Camera -------------------------------------------------------------------------

    private val videoWanted: Boolean
        get() = reasons.none { it == PauseReason.RIDE_PAUSED || it == PauseReason.HOT || it == PauseReason.LOW_STORAGE }

    /** (Re)binds the use cases for the current state. Main thread. */
    private fun bindCamera() {
        val p = provider ?: return
        val selector = when {
            p.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            p.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> {
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
            cam.cameraInfo.cameraState.removeObservers(owner)
            cam.cameraInfo.cameraState.observe(owner) { st -> onCameraState(st) }
            if (withVideo) startAudio() else stopAudio()
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            setReason(PauseReason.CAMERA_BUSY, true)
            scheduleRetry()
        }
    }

    private fun buildPreview(): Preview {
        val size = if (settings.quality.height >= 1080) Size(1920, 1080) else Size(1280, 720)
        val pv = Preview.Builder()
            .setTargetRotation(targetRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .build()
        pv.setSurfaceProvider(main) { request ->
            val enc = try {
                VideoEncoder(request.resolution, settings.quality.bitrate, buffer)
            } catch (e: Exception) {
                Log.e(TAG, "Video encoder unavailable", e)
                request.willNotProvideSurface()
                setReason(PauseReason.FAILED, true)
                return@setSurfaceProvider
            }
            // A new encoder session: older samples came from a different stream.
            encoder?.let { buffer.clear() }
            encoder = enc
            request.setTransformationInfoListener(main) { rotationDegrees = it.rotationDegrees }
            request.provideSurface(enc.inputSurface, main) {
                enc.release()
                if (encoder === enc) encoder = null
            }
            refreshStatus()
        }
        preview = pv
        return pv
    }

    private fun onCameraState(st: CameraState) {
        val err = st.error
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
        if (audio != null || !granted(Manifest.permission.RECORD_AUDIO)) return
        audio = try {
            AudioEncoder(buffer)
        } catch (e: Exception) {
            Log.w(TAG, "No audio for moments", e)
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
            // Video on/off depends on the reasons; rebind only when that actually changes.
            if (provider != null && videoWanted != videoBound && PauseReason.FAILED !in reasons) bindCamera()
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        val reason = reasons.firstOrNull()
        when {
            reason != null -> hub.setStatus(MomentStatus.PAUSED, reason)
            encoder != null -> hub.setStatus(MomentStatus.ARMED)
            else -> hub.setStatus(MomentStatus.STARTING)
        }
    }

    private fun watchThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService<PowerManager>() ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            when {
                status >= PowerManager.THERMAL_STATUS_SEVERE -> setReason(PauseReason.HOT, true)
                status <= PowerManager.THERMAL_STATUS_MODERATE -> setReason(PauseReason.HOT, false)
            }
        }
        thermalListener = l
        pm.addThermalStatusListener(main, l)
    }

    // ---- Requests -----------------------------------------------------------------------

    private suspend fun handle(r: MomentRequest) {
        try {
            when (r) {
                is MomentRequest.Clip -> writeClip(r)
                is MomentRequest.Photo -> takePhoto(r)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Moment not saved", e)
        } finally {
            hub.setWriting(false)
        }
    }

    private suspend fun writeClip(r: MomentRequest.Clip) {
        val w = r.window
        // Let the encoders catch up with the end of the window.
        val wait = w.endMillis + ENCODER_LATENCY_MILLIS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (lowStorage()) {
            setReason(PauseReason.LOW_STORAGE, true)
            return
        }
        hub.setWriting(true)
        val vFormat = encoder?.format ?: return
        val (video, audioSamples) = buffer.extract(w.startMillis * 1000, w.endMillis * 1000)
        if (video.isEmpty()) return
        val dir = repo.dir(r.rideId)
        val id = UUID.randomUUID().toString()
        val file = File(dir, "clip-${w.anchorMillis}-${id.take(6)}.mp4")
        val length = ClipWriter.writeMp4(file, vFormat, audio?.format, video, audioSamples, rotationDegrees) ?: return
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
            ),
        )
        hub.onSaved()
    }

    private suspend fun takePhoto(r: MomentRequest.Photo) {
        val capture = imageCapture ?: return
        if (lowStorage()) return
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
                            Log.w(TAG, "Photo failed", exception)
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
        hub.onSaved()
    }

    private fun lowStorage(): Boolean =
        runCatching { StatFs(context.filesDir.path).availableBytes < MIN_FREE_BYTES }.getOrDefault(false)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ProcessCameraProvider.hasCameraSafe(s: CameraSelector) = runCatching { hasCamera(s) }.getOrDefault(false)

    companion object {
        private const val TAG = "Moments"
        private const val ENCODER_LATENCY_MILLIS = 800L
        private const val MIN_FREE_BYTES = 1_000_000_000L
    }
}
