package com.ridetrack.app.moments

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService

/** Kinds of microphone the rider can pick for moment clips. */
enum class MicType(val label: String) {
    /** Not a device: the best connected mic, in [Microphones.AUTO_ORDER]. */
    AUTO("Automatic"),
    PHONE("Phone"),
    USB("USB-C"),
    BLUETOOTH("Bluetooth"),
    WIRED("Wired"),
}

/**
 * A chosen microphone, stored as "TYPE|product name". Device ids change on every reconnect,
 * so the product name ("DJI MIC MINI") is what identifies it next time.
 */
data class MicChoice(val type: MicType, val name: String?) {
    val label: String get() = when (type) {
        MicType.PHONE -> "Phone mic"
        MicType.AUTO -> "Automatic"
        else -> listOfNotNull(name?.takeIf { it.isNotBlank() }, type.label).joinToString(" · ")
    }
    fun encode(): String = "${type.name}|${name.orEmpty()}"

    companion object {
        val PHONE = MicChoice(MicType.PHONE, null)
        val AUTO = MicChoice(MicType.AUTO, null)

        /** Nothing saved (or unreadable) = Automatic. */
        fun decode(value: String?): MicChoice {
            if (value.isNullOrBlank()) return AUTO
            val type = MicType.entries.firstOrNull { it.name == value.substringBefore('|') } ?: return AUTO
            return MicChoice(type, value.substringAfter('|', "").ifBlank { null })
        }
    }
}

object Microphones {
    /** Automatic picks the first of these that's connected: a USB-C receiver, a wired mic, the headset (if allowed), then the phone. */
    val AUTO_ORDER = listOf(MicType.USB, MicType.WIRED, MicType.BLUETOOTH)

    /**
     * Which mic to record from, given the inputs connected now. A chosen mic that isn't
     * connected falls back to the phone (never to a Bluetooth headset, which would cut its
     * music); Automatic goes down [AUTO_ORDER].
     */
    fun pick(connected: List<MicChoice>, choice: MicChoice, allowHeadset: Boolean = true): MicChoice = when (choice.type) {
        MicType.PHONE -> MicChoice.PHONE
        // A Bluetooth headset is skipped unless the rider allows it (its music stops while it records).
        MicType.AUTO -> AUTO_ORDER.filter { allowHeadset || it != MicType.BLUETOOTH }
            .firstNotNullOfOrNull { t -> connected.firstOrNull { it.type == t } } ?: MicChoice.PHONE
        else -> connected.firstOrNull { it.type == choice.type && it.name == choice.name }
            ?: connected.firstOrNull { it.type == choice.type }
            ?: MicChoice.PHONE
    }

    /** The device to record from for [choice] (resolved with [pick]); null = the phone mic. */
    fun resolve(context: Context, choice: MicChoice, allowHeadset: Boolean = true): AudioDeviceInfo? {
        val picked = pick(available(context).filter { it.type != MicType.PHONE }, choice, allowHeadset)
        return if (picked.type == MicType.PHONE) null else find(context, picked)
    }

    fun typeOf(d: AudioDeviceInfo): MicType? = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicType.PHONE
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicType.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicType.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicType.WIRED
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) MicType.BLUETOOTH else null
    }

    /** Inputs connected right now, phone mic first, one entry per mic. */
    fun available(context: Context): List<MicChoice> {
        val am = context.getSystemService<AudioManager>() ?: return listOf(MicChoice.PHONE)
        val external = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { d -> typeOf(d)?.takeIf { it != MicType.PHONE }?.let { MicChoice(it, d.productName?.toString()) } }
            .distinct()
        return listOf(MicChoice.PHONE) + external
    }

    /** The connected device for [choice]: same kind and name, else same kind; null = phone mic. */
    fun find(context: Context, choice: MicChoice): AudioDeviceInfo? {
        if (choice.type == MicType.PHONE || choice.type == MicType.AUTO) return null
        val am = context.getSystemService<AudioManager>() ?: return null
        val same = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { typeOf(it) == choice.type }
        return same.firstOrNull { it.productName?.toString() == choice.name } ?: same.firstOrNull()
    }
}
