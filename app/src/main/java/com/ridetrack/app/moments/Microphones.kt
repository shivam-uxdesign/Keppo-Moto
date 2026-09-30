package com.ridetrack.app.moments

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService

/** Kinds of microphone the rider can pick for moment clips. */
enum class MicType(val label: String) {
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
    val label: String get() = if (type == MicType.PHONE) "Phone mic" else listOfNotNull(name?.takeIf { it.isNotBlank() }, type.label).joinToString(" · ")
    fun encode(): String = "${type.name}|${name.orEmpty()}"

    companion object {
        val PHONE = MicChoice(MicType.PHONE, null)
        fun decode(value: String?): MicChoice {
            if (value.isNullOrBlank()) return PHONE
            val type = MicType.entries.firstOrNull { it.name == value.substringBefore('|') } ?: return PHONE
            return MicChoice(type, value.substringAfter('|', "").ifBlank { null })
        }
    }
}

object Microphones {
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
        if (choice.type == MicType.PHONE) return null
        val am = context.getSystemService<AudioManager>() ?: return null
        val same = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { typeOf(it) == choice.type }
        return same.firstOrNull { it.productName?.toString() == choice.name } ?: same.firstOrNull()
    }
}
