package com.ridetrack.app.transcribe

import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.tasks.await

/**
 * The last resort when every built-in Gemini name fails: a model name set in the Firebase
 * console (Remote Config parameter `gemini_model`), so a renamed model needs no app update.
 */
object RemoteModel {
    private const val KEY = "gemini_model"

    suspend fun get(): String? = runCatching {
        val rc = Firebase.remoteConfig
        rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 3_600 }).await()
        rc.fetchAndActivate().await()
        rc.getString(KEY).trim().removePrefix("models/").ifBlank { null }
    }.getOrNull()
}
