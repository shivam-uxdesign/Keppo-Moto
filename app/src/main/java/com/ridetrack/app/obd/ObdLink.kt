package com.ridetrack.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** The text line to an ELM327 adapter: send a command, get its answer up to the ">" prompt. */
interface ObdLink {
    /** "Bluetooth classic" or "BLE", for the test screen and the log. */
    val kind: String

    suspend fun open()

    /** Sends [command] and returns everything up to the prompt; throws on a lost link. */
    suspend fun send(command: String, timeoutMs: Long = 3_000): String

    fun close()

    companion object {
        /** The right link for [device]: classic for classic and dual-mode adapters, BLE for BLE-only ones. */
        @SuppressLint("MissingPermission")
        fun forDevice(context: Context, device: BluetoothDevice): List<ObdLink> = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> listOf(BleLink(context, device))
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> listOf(ClassicLink(device))
            else -> listOf(ClassicLink(device), BleLink(context, device))
        }
    }
}

/** Bluetooth classic adapters: the serial port profile, paired in Android's Bluetooth settings. */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is asked for before pairing an adapter.
class ClassicLink(private val device: BluetoothDevice) : ObdLink {
    override val kind = "Bluetooth classic"
    private var socket: BluetoothSocket? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val s = runCatching { device.createRfcommSocketToServiceRecord(SPP).also { it.connect() } }
            .recoverCatching { device.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() } }
            .getOrThrow()
        socket = s
    }

    override suspend fun send(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("not connected")
        val input = s.inputStream
        while (input.available() > 0) input.read() // anything left over from a timed-out answer
        s.outputStream.write("$command\r".toByteArray())
        s.outputStream.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("link closed")
                if (c.toChar() == '>') return@withContext sb.toString()
                sb.append(c.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        sb.toString()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * BLE adapters (they show up only inside apps, not in Android's Bluetooth list): a "serial"
 * service with one characteristic to write commands to and one that notifies the answer.
 * Cheap ELM327 clones use FFF0/FFE0/18F0; otherwise the first service that has both.
 */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context, private val device: BluetoothDevice) : ObdLink {
    override val kind = "BLE"
    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var lost = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                lost = true
                ready?.completeExceptionally(IOException("BLE disconnected ($status)"))
                incoming.trySend(LOST)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val (w, n) = pick(g) ?: return run { ready?.completeExceptionally(IOException("no serial service on this adapter")) }
            write = w
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(CCCD)
            if (cccd == null) {
                ready?.complete(Unit)
                return
            }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready?.complete(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { incoming.trySend(String(it)) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(String(value))
        }
    }

    override suspend fun open() {
        val r = CompletableDeferred<Unit>()
        ready = r
        lost = false
        gatt = withContext(Dispatchers.Main) { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }
        withTimeout(15_000) { r.await() }
    }

    override suspend fun send(command: String, timeoutMs: Long): String {
        val g = gatt ?: throw IOException("not connected")
        val w = write ?: throw IOException("not connected")
        if (lost) throw IOException("BLE disconnected")
        while (incoming.tryReceive().isSuccess) Unit
        val bytes = "$command\r".toByteArray()
        val type = if (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(w, bytes, type)
        else @Suppress("DEPRECATION") run { w.writeType = type; w.value = bytes; g.writeCharacteristic(w) }
        val sb = StringBuilder()
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val part = incoming.receive()
                if (part == LOST) throw IOException("BLE disconnected")
                sb.append(part)
                if ('>' in part) break
            }
        }
        return sb.toString().substringBefore('>')
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun pick(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val services = g.services.sortedBy { s -> KNOWN.indexOf(s.uuid).let { if (it < 0) Int.MAX_VALUE else it } }
        for (s in services) {
            val w = s.characteristics.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
            val n = s.characteristics.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
            if (w != null && n != null) return w to n
        }
        return null
    }

    private companion object {
        const val LOST = "\u0000lost"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val KNOWN = listOf("fff0", "ffe0", "18f0", "e7810a71-73ae-499d-8c15-faa9aef0c3f2").map {
            if (it.length == 4) UUID.fromString("0000$it-0000-1000-8000-00805f9b34fb") else UUID.fromString(it)
        }
    }
}
