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
 * A Bluetooth mic needs the phone's call-audio link, which is opened here and closed on release.
 */
class AudioEncoder(private val context: Context, private val buffer: RollingBuffer, private val device: AudioDeviceInfo? = null) {
    private val audioManager = context.getSystemService<AudioManager>()
    private val bluetooth = device != null && Microphones.typeOf(device) == MicType.BLUETOOTH
    private var scoStarted = false

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
    }
}
