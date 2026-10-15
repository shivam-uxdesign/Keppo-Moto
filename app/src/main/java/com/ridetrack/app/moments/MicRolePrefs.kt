package com.ridetrack.app.moments

import android.content.Context

/** "On the last ride your voice was heard on Mic 2": kept until the rider swaps the mics or says no. */
object MicRolePrefs {
    private const val FILE = "moment_mics"
    private const val VOICE_ON_MIC2 = "voice_on_mic2"

    fun voiceOnMic2(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(VOICE_ON_MIC2, false) }.getOrDefault(false)

    fun setVoiceOnMic2(context: Context, on: Boolean) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(VOICE_ON_MIC2, on).apply() }
    }
}
