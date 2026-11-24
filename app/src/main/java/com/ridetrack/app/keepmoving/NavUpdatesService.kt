package com.ridetrack.app.keepmoving

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Receives the Navigation SDK's turn-by-turn feed (the next turn while Keep moving guides), for
 * the top bar shown on the app's other screens. Bound by the Navigation SDK only (not exported).
 */
class NavUpdatesService : Service() {
    /** The next turn: Google's instruction (shown, never read aloud by the app), how far, its arrow. */
    data class NextTurn(val instruction: String, val road: String?, val inMeters: Int?, val arrow: Bitmap?, val toGoSeconds: Int?, val toGoMeters: Int?)

    private val manager = TurnByTurnManager.createInstance()

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != TurnByTurnManager.MSG_NAV_INFO) return
            val info = runCatching { manager.readNavInfoFromBundle(msg.data) }.getOrNull() ?: return
            val step = info.currentStep
            _next.value = step?.let {
                NextTurn(
                    instruction = it.fullInstructionText.orEmpty(),
                    road = it.simpleRoadName?.takeIf { r -> r.isNotBlank() },
                    inMeters = info.distanceToCurrentStepMeters,
                    arrow = it.maneuverBitmap,
                    toGoSeconds = info.timeToFinalDestinationSeconds,
                    toGoMeters = info.distanceToFinalDestinationMeters,
                )
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        _next.value = null
        return super.onUnbind(intent)
    }

    companion object {
        private val _next = MutableStateFlow<NextTurn?>(null)
        val next: StateFlow<NextTurn?> = _next.asStateFlow()
        fun clear() { _next.value = null }
    }
}
