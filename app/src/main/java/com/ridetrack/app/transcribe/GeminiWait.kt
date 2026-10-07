package com.ridetrack.app.transcribe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** When Gemini is free again, worked out live from the saved reset times. */
data class GeminiWait(val captionsAt: Long?, val storyAt: Long?, val now: Long) {
    val captionsBlocked: Boolean get() = (captionsAt ?: 0) > now
    /** The line to show, or null when Gemini is free. */
    val line: String? get() = GeminiQuota.waitLine(captionsAt, storyAt, now)
}

/** Ticks every 20 s, so the countdown moves and the message goes once the time has passed. */
@Composable
fun rememberGeminiWait(): GeminiWait {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }
    return GeminiWait(GeminiQuota.freeAt(GeminiQuota.CAPTIONS, now), GeminiQuota.freeAt(GeminiQuota.STORY, now), now)
}
