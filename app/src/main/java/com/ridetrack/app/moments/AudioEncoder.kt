package com.ridetrack.app.moments

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import com.ridetrack.telemetry.moments.EncodedSample
import com.ridetrack.telemetry.moments.RollingBuffer

/**
 * Microphone → AAC into the [RollingBuffer]. Runs its own thread until [release].
 * [device] picks an external mic (USB-C receiver, Bluetooth, wired); null = the phone's.
 *
 * Only a Bluetooth headset needs the phone's call-audio link (and while it's open the
 * headset can't play music). It's opened here, and closed on release or as soon as the
 * headset drops it: [onHeadsetLost] then tells the owner to carry on with another mic.
 * Any other mic never touches the call link or the headset.
 */
class AudioEncoder(
    private val context: Context,
    private val buffer: RollingBuffer,
    private val device: AudioDeviceInfo? = null,
    private val onHeadsetLost: () -> Unit = {},
    /** Each chunk's loudness: (wall time, dBFS after a 150 Hz high-pass, chunk length in ms). */
    private val onLevel: ((Long, Float, Long) -> Unit)? = null,
) {
    private var hpPrevX = 0.0
    private var hpPrevY = 0.0
    private val audioManager = context.getSystemService<AudioManager>()
    private val bluetooth = device != null && Microphones.typeOf(device) == MicType.BLUETOOTH
    @Volatile private var scoStarted = false
    private val startNanos = System.nanoTime()

    @Volatile
    var format: MediaFormat? = null
        private set

    @Volatile
    private var running = true
    private val thread = Thread(::loop, "moments-audio")

    // RECORD_AUDIO is checked by the caller before constructing this.
    @SuppressLint("MissingPermission")
    private val record = AudioRecord(
        // Bluetooth mics only arrive on the voice path; everything else films like a camcorder.
        if (bluetooth) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.CAMCORDER,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192) * 2,
    )
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        if (bluetooth) openBluetoothRoute()
        if (device != null) record.setPreferredDevice(device)
        if (bluetooth) {
            record.addOnRoutingChangedListener({ r ->
                val d = r.routedDevice
                // The headset left call mode: let it go at once. Holding the request keeps it
                // bouncing in and out of call mode, so its music stays silent.
                if (scoStarted && System.nanoTime() - startNanos > SETTLE_NANOS && (d == null || Microphones.typeOf(d) != MicType.BLUETOOTH)) {
                    closeBluetoothRoute()
                    onHeadsetLost()
                }
            }, null)
        }
        record.startRecording()
        thread.start()
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        // Wall clock = monotonic + a fixed offset measured once.
        val offsetMicros = System.currentTimeMillis() * 1000 - System.nanoTime() / 1000
        try {
            while (running) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex) ?: continue
                    inBuf.clear()
                    val read = record.read(inBuf, minOf(inBuf.capacity(), 4096))
                    val nowMicros = System.nanoTime() / 1000
                    if (read > 0) onLevel?.let { report ->
                        val samples = read / 2
                        report((nowMicros + offsetMicros) / 1000, levelDb(inBuf, samples), samples * 1000L / SAMPLE_RATE)
                    }
                    // pts = when the first sample of this chunk was captured.
                    val pts = nowMicros - (read.coerceAtLeast(0) / 2) * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(inIndex, 0, read.coerceAtLeast(0), pts, 0)
                }
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        format = codec.outputFormat
                        continue
                    }
                    if (outIndex < 0) break
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        codec.getOutputBuffer(outIndex)?.let { out ->
                            val bytes = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(bytes, 0, info.size)
                            buffer.addAudio(EncodedSample(info.presentationTimeUs + offsetMicros, true, bytes))
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture stopped", e)
        }
    }

    /**
     * RMS loudness of [samples] 16-bit samples at the start of [buf], in dBFS, after a
     * one-pole ~150 Hz high-pass that takes out most wind and engine rumble.
     */
    private fun levelDb(buf: java.nio.ByteBuffer, samples: Int): Float {
        if (samples <= 0) return SILENCE_DB
        val b = buf.duplicate().order(java.nio.ByteOrder.nativeOrder())
        var sum = 0.0
        for (i in 0 until samples) {
            val x = b.getShort(i * 2) / 32768.0
            val y = HIGH_PASS_A * (hpPrevY + x - hpPrevX)
            hpPrevX = x
            hpPrevY = y
            sum += y * y
        }
        return (10 * kotlin.math.log10(sum / samples + 1e-12)).toFloat().coerceAtLeast(SILENCE_DB)
    }

    /** The input actually in use (may differ from the preferred one if it disconnected). */
    val routedDevice: AudioDeviceInfo? get() = runCatching { record.routedDevice }.getOrNull()

    fun addOnRoutingChanged(listener: (AudioDeviceInfo?) -> Unit) {
        record.addOnRoutingChangedListener({ r -> listener(r.routedDevice) }, null)
    }

    @Suppress("DEPRECATION")
    private fun openBluetoothRoute() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (target != null) scoStarted = am.setCommunicationDevice(target)
            } else {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                scoStarted = true
            }
        }.onFailure { Log.w(TAG, "Bluetooth mic route unavailable", it) }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun closeBluetoothRoute() {
        if (!scoStarted) return
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
        }
        scoStarted = false
    }

    fun release() {
        running = false
        runCatching { thread.join(500) }
        runCatching { record.stop() }
        record.release()
        runCatching { codec.stop() }
        runCatching { codec.release() }
        closeBluetoothRoute()
    }

    companion object {
        private const val TAG = "MomentsAudio"
        private const val SAMPLE_RATE = 44_100
        private const val SILENCE_DB = -90f
        /** One-pole high-pass at ~150 Hz for 44.1 kHz: RC / (RC + dt). */
        private const val HIGH_PASS_A = 0.979
        /** The call link takes a moment to come up; routing flips during it don't count as a drop. */
        private const val SETTLE_NANOS = 4_000_000_000L
    }
}
