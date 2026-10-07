package com.ridetrack.app.moments

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
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
    /** Silero, loaded once per ride when "Film when I speak" is on. */
    private val vad: SileroVad? by lazy {
        if (!settings.voice) null
        else SileroVad.load(context).also { log.log(if (it != null) "voice detector ready" else "voice detector unavailable: loudness only") }
    }
    /** The mic [audio] records from. */
    private var audioMic: MicChoice? = null
    /** The headset dropped call mode: skip it until the camera next restarts (no retry loop). */
    private var headsetSkipped = false
    private var micCheck: Job? = null
    private var inCall = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            logMics("connected", added)
            micsChanged()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            logMics("disconnected", removed)
            micsChanged()
        }
    }
    /** A mic that dropped out is only switched back to once it stays connected. */
    private val micStability = MicStability()
    /** The external mic used last this ride, so Automatic can say which one went missing. */
    private var lastExternal: MicChoice? = null
    private var powerReceiver: BroadcastReceiver? = null

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

    /** A long video being filmed (yours, or GPS lost); event clips wait until it stops. */
    private class Live(val rec: LiveRecording, val request: MomentRequest.StartLive) {
        /** Events filmed in a chain video; more join while it runs. */
        val types = request.types.toMutableSet()
        var peakValue = request.peakValue
    }
    @Volatile private var live: Live? = null
    private val liveLock = Mutex()
    /** Show the camera on the HUD: only while you film on purpose. */
    @Volatile private var viewfinderWanted = false
    @Volatile private var liveStarting = false
    private var viewfinderBound = false
    private var usingFront = false
    /** Your video wants the back camera (road view, or filming off the bike); back to selfie when it stops. */
    @Volatile private var wantBack = false
    /** The back camera is the one bound now. */
    private var boundBack = false
    /** Bumped for every new encoder session, so a start can wait for a fresh stream. */
    @Volatile private var encoderSession = 0
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastViewfinderMillis = 0L

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
                noteCalls()
                // A missed "connected" signal, or a mic waiting out its stability hold.
                withContext(Dispatchers.Main) { if (audio != null) micsChanged(settleMillis = 0) }
                if (live != null && lowStorage()) liveLock.withLock { stopLive("storage almost full") }
                withContext(Dispatchers.Main) { watchdog() }
                if (tick % 6 == 0) log.log("status: ${hub.state.value.status} reasons=$reasons encoder=${encoderInfo()} ${buffer.describe()} thermal=${thermal()}")
                log.flush()
            }
        }
        watchThermal()
        context.getSystemService<AudioManager>()?.registerAudioDeviceCallback(deviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        watchPower()
        hub.onLog = { log.log(it) }
        hub.onReconnectMic = {
            scope.launch(Dispatchers.Main) {
                log.log("reconnect mic pressed")
                micStability.force()
                micsChanged(settleMillis = 0)
            }
        }
        hub.attach { r ->
            if (r is MomentRequest.StartLive || r is MomentRequest.LiveControl || r is MomentRequest.LiveEvents) {
                scope.launch(Dispatchers.IO) { liveLock.withLock { handleLive(r) } }
            } else {
                requests.trySend(r)
            }
        }
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
    /** [cause]: "manual", "stop" or "break", for the log. */
    fun setRidePaused(paused: Boolean, cause: String = "stop") {
        idleJob?.cancel()
        if (paused) {
            log.log("ride paused ($cause)")
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
        withContext(Dispatchers.IO) { liveLock.withLock { stopLive("ride ended") } }
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
            context.getSystemService<AudioManager>()?.unregisterAudioDeviceCallback(deviceCallback)
            powerReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            powerReceiver = null
            hub.onReconnectMic = null
            hub.onLog = null
            micCheck?.cancel()
        }
        stopAudio()
        if (settings.voice) vad?.close()
        analysisExecutor.shutdown()
        encoder?.release()
        encoder = null
        buffer.clear()
        hub.reset()
        log.log("finished")
        log.flush()
    }

    // ---- Camera -------------------------------------------------------------------------

    private val videoWanted: Boolean
        get() = reasons.none { it == PauseReason.HOT || it == PauseReason.LOW_STORAGE || (it == PauseReason.RIDE_PAUSED && live == null && !liveStarting) }

    /** (Re)binds the use cases for the current state. Main thread. */
    private fun bindCamera() {
        val p = provider ?: return
        val hasBack = p.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA)
        if (wantBack && !hasBack) wantBack = false
        val front = p.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA) && !(wantBack && hasBack)
        val selector = when {
            front -> CameraSelector.DEFAULT_FRONT_CAMERA
            hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
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
        usingFront = front
        boundBack = !front
        val withViewfinder = withVideo && viewfinderWanted
        val useCases = buildList {
            add(capture)
            if (withVideo) add(buildPreview())
            if (withViewfinder) add(buildViewfinder())
        }
        try {
            p.unbindAll()
            var withFinder = withViewfinder
            val cam = try {
                p.bindToLifecycle(owner, selector, *useCases.toTypedArray())
            } catch (e: Exception) {
                if (!withViewfinder) throw e
                // Some cameras can't stream three ways at once: film without the viewfinder.
                log.error("viewfinder not supported with filming; recording without it", e)
                withFinder = false
                p.unbindAll()
                p.bindToLifecycle(owner, selector, *useCases.dropLast(1).toTypedArray())
            }
            viewfinderBound = withFinder
            camera = cam
            videoBound = withVideo
            boundLowPower = lowPower
            bindAtMillis = System.currentTimeMillis()
            log.log("bound ${if (front) "front" else "back"} camera: photo${if (withVideo) " + video" + (if (lowPower) " (low power)" else "") else " only"}")
            cam.cameraInfo.cameraState.removeObservers(owner)
            cam.cameraInfo.cameraState.observe(owner) { st -> onCameraState(st) }
            if (withVideo) {
                startAudio()
            } else if (settings.voice) {
                // "Film when I speak" keeps listening while the camera rests (stopped, paused, on a break).
                startAudio()
            } else {
                stopAudio()
                // Camera off for now: next time it starts, the headset may be tried again.
                headsetSkipped = false
            }
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
            // A new encoder session: older samples came from a different stream. A video
            // being filmed can't change streams mid-file, so it's finished here.
            if (live != null) {
                buffer.stopTap()
                scope.launch(Dispatchers.IO) { liveLock.withLock { stopLive("camera restarted") } }
            }
            if (encoder != null || videoFormat != null) buffer.clear()
            encoder = enc
            encoderSession++
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

    /** Small frames for the HUD's viewfinder, upright (and mirrored for the selfie camera). */
    private fun buildViewfinder(): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(targetRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analysisExecutor) { img ->
            try {
                val now = System.currentTimeMillis()
                if (viewfinderWanted && now - lastViewfinderMillis >= VIEWFINDER_FRAME_MILLIS) {
                    lastViewfinderMillis = now
                    val m = Matrix().apply {
                        postRotate(img.imageInfo.rotationDegrees.toFloat())
                        if (usingFront) postScale(-1f, 1f)
                    }
                    val raw = img.toBitmap()
                    hub.setViewfinder(Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true))
                }
            } catch (e: Exception) {
                // A dropped viewfinder frame doesn't matter.
            } finally {
                img.close()
            }
        }
        return analysis
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

    /** The mic to use now: the saved choice resolved against what's connected (see [Microphones.pick]). */
    private fun pickMic(): MicChoice {
        val connected = Microphones.available(context).filter { it.type != MicType.PHONE && !(headsetSkipped && it.type == MicType.BLUETOOTH) }
        return Microphones.pick(connected, MicChoice.decode(settings.mic), allowHeadset = settings.headsetMic)
    }

    private fun startAudio() {
        if (audio != null) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            log.log("no microphone permission: clips without sound")
            return
        }
        val choice = MicChoice.decode(settings.mic)
        val mic = pickMic()
        micStability.onSeen(android.os.SystemClock.elapsedRealtime(), mic.type != MicType.PHONE)
        val device = if (mic.type == MicType.PHONE) null else Microphones.find(context, mic)
        if (choice.type != MicType.AUTO && choice.type != MicType.PHONE && mic.type == MicType.PHONE) {
            log.log("mic ${choice.label} not connected: using the phone mic")
        }
        audio = try {
            // Both channels of a USB-C receiver: its second transmitter can be the engine mic.
            AudioEncoder(context, buffer, device, onLevel = hub::reportLevel, vad = vad, twoMics = settings.twoMics && mic.type == MicType.USB, swap = settings.swapMics,
                // The engine mic's level, for revs and exhaust pops (only when the two mics really differ).
                onChannels = { l, r -> if (audio?.twoDifferent == true) hub.reportEngine(System.currentTimeMillis(), if (settings.swapMics) l else r, ENGINE_CHUNK_MS) },
                onHeadsetLost = {
                scope.launch(Dispatchers.Main) {
                    log.log("headset left call mode: released it (its music can play again); switching mic")
                    headsetSkipped = true
                    restartAudio()
                }
            }).also { enc ->
                audioMic = mic
                hub.setMicType(mic.type)
                if (mic.type != MicType.PHONE) lastExternal = mic
                hub.setMicFallback(fallbackName(choice, mic))
                log.log("audio from ${if (device == null) "phone mic" else "${mic.type.label} ${device.productName}"} (setting: ${choice.label})${if (enc.recordingTwo) ", both channels (two mics)" else ""}")
                // A mic that drops out (battery, range) falls back to the phone; note it in the log.
                enc.addOnRoutingChanged { d -> log.log("audio now from ${d?.let { "${Microphones.typeOf(it)?.label ?: it.type} ${it.productName}" } ?: "default mic"}") }
            }
        } catch (e: Exception) {
            log.error("audio unavailable, clips without sound", e)
            null
        }
    }

    /** With two different mics, the engine mic's sound goes next to the clip as its own file. */
    private fun writeEngine(clip: File, fromMicros: Long, toMicros: Long) {
        val enc = audio ?: return
        if (!enc.twoDifferent) return
        val format = enc.engineFormat ?: return
        runCatching {
            val out = File(clip.parentFile, clip.nameWithoutExtension + ".engine.m4a")
            if (ClipWriter.writeAudio(out, format, buffer.extraBetween(fromMicros, toMicros), fromMicros)) log.log("engine mic kept: ${out.name}")
        }.onFailure { log.error("engine mic sound couldn't be written", it) }
    }

    private fun stopAudio() {
        audio?.release()
        audio = null
        audioMic = null
        hub.setMicType(null)
        hub.clearLevel()
    }

    /** The mic the rider wanted, when the phone mic is standing in for it; null when all's well. */
    private fun fallbackName(choice: MicChoice, using: MicChoice): String? {
        if (using.type != MicType.PHONE) return null
        val wanted = when (choice.type) {
            MicType.PHONE -> return null
            MicType.AUTO -> lastExternal ?: return null
            else -> choice
        }
        // The headset let go on purpose (so its music plays): not a problem to flag.
        if (wanted.type == MicType.BLUETOOTH && headsetSkipped) return null
        return wanted.name?.takeIf { it.isNotBlank() } ?: wanted.type.label
    }

    private fun logMics(what: String, devices: Array<out AudioDeviceInfo>?) {
        devices.orEmpty().filter { it.isSource && Microphones.typeOf(it) != null && Microphones.typeOf(it) != MicType.PHONE }.forEach {
            log.log("mic $what: ${Microphones.typeOf(it)?.label} ${it.productName}")
        }
    }

    /** Charging coming and going, to match against mic drops in the log. */
    private fun watchPower() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                log.log(if (intent.action == Intent.ACTION_POWER_CONNECTED) "power: charging" else "power: not charging")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { powerReceiver = r }
        val charging = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 }
        log.log("power: ${if (charging == true) "charging" else "not charging"} at start")
    }

    private fun restartAudio() {
        if (audio == null) return
        stopAudio()
        if (videoBound) startAudio()
    }

    /**
     * A mic was plugged in or out (or the periodic check): switch if the best one changed.
     * Dropping to the phone mic is immediate; going back to a mic that dropped waits until
     * it has stayed connected ([MicStability]), so a flapping receiver doesn't chop clips.
     */
    private fun micsChanged(settleMillis: Long = MIC_SETTLE_MILLIS) {
        micCheck?.cancel()
        micCheck = scope.launch(Dispatchers.Main) {
            if (settleMillis > 0) delay(settleMillis)
            val current = audioMic ?: return@launch
            val now = android.os.SystemClock.elapsedRealtime()
            val best = pickMic()
            micStability.onSeen(now, best.type != MicType.PHONE)
            if (best == current) return@launch
            if (best.type != MicType.PHONE && current.type == MicType.PHONE && !micStability.stable(now)) return@launch
            log.log("mic change: ${current.label} → ${best.label}")
            restartAudio()
        }
    }

    /** Calls take the microphone; note it, so silent stretches in clips make sense. */
    private fun noteCalls() {
        val mode = context.getSystemService<AudioManager>()?.mode ?: return
        val call = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_CALL_SCREENING
        if (call != inCall) {
            inCall = call
            log.log(if (call) "mic busy (call): clips are silent until it ends" else "call ended: mic back")
        }
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
        if (videoWanted != videoBound || (videoBound && lowPower != boundLowPower) || (videoBound && viewfinderWanted != viewfinderBound) || boundBack != wantBack) bindCamera()
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
                is MomentRequest.StartLive, is MomentRequest.LiveControl, is MomentRequest.LiveEvents -> Unit
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
        if (live != null) {
            log.log("clip skipped: a video is being filmed")
            return
        }
        // Let the encoders catch up with the end of the window.
        val wait = w.endMillis + ENCODER_LATENCY_MILLIS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (live != null) {
            log.log("clip skipped: a video started filming")
            return
        }
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
        writeEngine(file, video.first().wallMicros, w.endMillis * 1000)
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

    // ---- Long videos (yours, GPS lost) ------------------------------------------------------

    private suspend fun handleLive(r: MomentRequest) {
        try {
            when (r) {
                is MomentRequest.StartLive -> startLive(r)
                is MomentRequest.LiveEvents -> live?.let { l ->
                    l.types += r.types
                    val p = l.peakValue
                    if (p == null || (r.peakValue != null && kotlin.math.abs(r.peakValue) > kotlin.math.abs(p))) l.peakValue = r.peakValue
                    log.log("video chain: ${l.types.joinToString("+")}")
                }
                is MomentRequest.LiveControl -> when (r.action) {
                    LiveAction.PAUSE -> live?.let { l ->
                        l.rec.pause()
                        hub.live.value?.let { st ->
                            if (!st.paused) hub.setLive(st.copy(paused = true, recordedMillis = st.elapsedMillis(System.currentTimeMillis()), segmentStartMillis = null))
                        }
                        log.log("video paused")
                    }
                    LiveAction.RESUME -> live?.let { l ->
                        l.rec.resume()
                        hub.live.value?.let { st -> if (st.paused) hub.setLive(st.copy(paused = false, segmentStartMillis = System.currentTimeMillis())) }
                        log.log("video resumed")
                    }
                    LiveAction.STOP -> stopLive("stopped")
                    LiveAction.FLIP -> flip()
                }
                else -> Unit
            }
        } catch (e: Exception) {
            log.error("video control failed", e)
        } finally {
            log.flush()
        }
    }

    private suspend fun startLive(r: MomentRequest.StartLive) {
        if (live != null) return
        val manual = r.source == MomentSource.MANUAL
        log.log("video requested: ${r.source}, lead-in ${r.preRollMillis / 1000}s")
        if (lowStorage()) {
            log.log("video skipped: storage almost full")
            return
        }
        hub.setLive(LiveState(r.source, starting = true))
        liveStarting = true
        try {
            beginLive(r, manual)
        } finally {
            liveStarting = false
        }
    }

    private suspend fun beginLive(r: MomentRequest.StartLive, manual: Boolean) {
        val session = encoderSession
        // Your video shows the camera on the HUD, which needs the camera re-set up (a new
        // stream, so no lead-in). GPS-lost videos keep the running stream and its lead-in.
        val restart = manual && (!viewfinderBound || boundBack != wantBack)
        viewfinderWanted = manual
        withContext(Dispatchers.Main) { if (restart) bindCamera() else rebindIfNeeded() }
        val askedAt = System.currentTimeMillis()
        val fromMillis = askedAt - r.preRollMillis
        var rec: LiveRecording? = null
        val started = withTimeoutOrNull<Boolean>(LIVE_START_TIMEOUT_MILLIS) {
            while (true) {
                val fmt = videoFormat
                val fresh = !restart || encoderSession != session
                // The mic restarts with the camera: give it a moment so the video has sound.
                val waited = System.currentTimeMillis() - askedAt
                val audioReady = audio == null || audio?.format != null || waited > AUDIO_WAIT_MILLIS
                if (fmt != null && encoder != null && fresh && audioReady) {
                    if (audio?.format == null && audio != null) log.log("video without sound: the mic wasn't ready")
                    val candidate = rec ?: LiveRecording(
                        File(repo.dir(r.rideId), "video-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.mp4"),
                        fmt, audio?.format, rotationDegrees,
                    ).also { rec = it }
                    val from = if (restart) 0L else fromMillis * 1000
                    if (buffer.startTap(from, seed = candidate::seed, onSample = candidate::write)) return@withTimeoutOrNull true
                }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } == true
        val recording = rec
        if (!started || recording == null) {
            log.error("video not started: camera not ready (encoder=${encoderInfo()}, reasons=$reasons)")
            recording?.finish()
            viewfinderWanted = false
            wantBack = false
            hub.setLive(null)
            withContext(Dispatchers.Main) { rebindIfNeeded() }
            return
        }
        live = Live(recording, r)
        // A chain of events counts from the clip's start, look-back included (the HUD's "cam 00:10").
        val countFrom = if (r.source == MomentSource.EVENT) fromMillis else System.currentTimeMillis()
        hub.setLive(LiveState(r.source, segmentStartMillis = countFrom, back = boundBack))
        log.log("video filming: ${recording.file.name}")
    }

    /**
     * Your video: switches between the selfie and the back camera. The encoder can't change
     * streams mid-file, so this clip is saved and a new one starts on the other camera at once.
     */
    private suspend fun flip() {
        val l = live ?: return
        if (l.request.source != MomentSource.MANUAL) return
        val back = !boundBack
        log.log("camera flip: to ${if (back) "back" else "front"}")
        stopLive("camera flipped", rebind = false)
        wantBack = back
        startLive(l.request.copy(preRollMillis = 0))
    }

    /** [rebind] false while flipping: the next clip binds the other camera itself. */
    private suspend fun stopLive(reason: String, rebind: Boolean = true) {
        val l = live ?: return
        buffer.stopTap()
        live = null
        val length = l.rec.finish()
        val camera = if (boundBack) "back" else null
        val hadViewfinder = viewfinderWanted
        if (rebind) {
            viewfinderWanted = false
            // Event and voice clips always use the selfie camera.
            wantBack = false
            hub.setViewfinder(null)
            hub.setLive(null)
        }
        l.rec.failed?.let { log.error("video write failed", it) }
        if (rebind && (hadViewfinder || boundBack)) withContext(Dispatchers.Main) { rebindIfNeeded() }
        if (length == null) {
            log.log("video not saved ($reason): no frames")
            return
        }
        val file = l.rec.file
        val start = l.rec.firstFrameMillis ?: System.currentTimeMillis()
        val req = l.request
        // A chain's picture is its first event; other videos use their first second.
        val anchor = req.anchorMillis?.takeIf { it in start..start + length }
        val thumb = File(file.parentFile, file.nameWithoutExtension + ".jpg")
        val hasThumb = ClipWriter.videoThumbnail(file, anchor?.let { it - start } ?: minOf(1_000L, length / 2), thumb)
        repo.add(
            Moment(
                id = UUID.randomUUID().toString(),
                rideId = req.rideId,
                kind = MomentKind.CLIP,
                types = l.types,
                timeMillis = anchor ?: start,
                latitude = req.latitude,
                longitude = req.longitude,
                speedMps = req.speedMps,
                peakValue = l.peakValue,
                file = file,
                thumb = thumb.takeIf { hasThumb },
                durationMillis = length,
                starred = false,
                clipStartMillis = start,
                source = req.source,
                camera = camera,
            ),
        )
        log.log("video saved ($reason): ${file.name} ${length / 1000}s ${file.length() / 1024}KB")
        hub.onSaved()
        hub.onLiveSaved(LiveSaved(req.source, length, System.currentTimeMillis()))
    }

    private fun freeBytes(): Long = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(-1)

    private fun lowStorage(): Boolean = freeBytes().let { it in 0 until MIN_FREE_BYTES }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ProcessCameraProvider.hasCameraSafe(s: CameraSelector) = runCatching { hasCamera(s) }.getOrDefault(false)

    companion object {
        private const val ENCODER_LATENCY_MILLIS = 800L
        /** One stereo chunk: 2048 frames at 44.1 kHz. */
        private const val ENGINE_CHUNK_MS = 46L
        private const val MIN_FREE_BYTES = 1_000_000_000L
        private const val STALL_MILLIS = 10_000L
        private const val LOW_POWER_BITRATE = 1_500_000
        private const val LIVE_START_TIMEOUT_MILLIS = 6_000L
        private const val VIEWFINDER_FRAME_MILLIS = 100L
        private const val AUDIO_WAIT_MILLIS = 2_000L
        private const val MIC_SETTLE_MILLIS = 800L
    }
}
