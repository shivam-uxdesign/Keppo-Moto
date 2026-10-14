package com.ridetrack.app.safety

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridetrack.app.hud.OverlayLifecycleOwner
import com.ridetrack.app.ui.safety.CrashAlertContent
import com.ridetrack.app.ui.theme.RideDark

/**
 * The crash alert as a window over any app (the "Display over other apps" permission the
 * pop-up HUD uses), for phones where the full-screen alert isn't allowed. It shows the same
 * screen as [CrashAlertActivity]; once it's up, Android also lets the app open that screen
 * (Android 15+ only allows it while one of the app's windows is visible), which then takes over
 * and this one goes. It can't show over the lock screen: the notification covers that. Main
 * thread only.
 */
internal class CrashAlertOverlay(private val context: Context, private val alerts: CrashAlerts) {
    private val wm = context.getSystemService<WindowManager>()
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null

    val isShowing: Boolean get() = view != null

    fun show(): Boolean {
        if (view != null) return true
        val windowManager = wm ?: return false
        if (!com.ridetrack.app.hud.OverlayPermission.isGranted(context)) return false
        val lifecycle = OverlayLifecycleOwner()
        // The window takes no key focus (so it never steals typing), so there's no back key here;
        // the screen's back handling just needs a dispatcher to exist.
        val back = object : OnBackPressedDispatcherOwner {
            override val lifecycle get() = lifecycle.lifecycle
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
        }
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides back) {
                    RideDark {
                        val state by alerts.state.collectAsStateWithLifecycle()
                        CrashAlertContent(
                            state = state,
                            onCancel = alerts::cancel,
                            onSendNow = alerts::sendNow,
                            onAllClear = alerts::allClear,
                            onClose = { alerts.dismiss(); hide() },
                        )
                    }
                }
            }
        }
        lifecycle.attachTo(compose)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        return try {
            windowManager.addView(compose, params)
            view = compose
            owner = lifecycle
            true
        } catch (e: Exception) {
            // Permission revoked or window refused: the notification and alarm still run.
            Log.w(TAG, "Could not show the crash alert over other apps", e)
            lifecycle.destroy()
            false
        }
    }

    fun hide() {
        view?.let { v -> runCatching { wm?.removeView(v) } }
        owner?.destroy()
        view = null
        owner = null
    }

    private companion object {
        const val TAG = "CrashAlertOverlay"
    }
}
