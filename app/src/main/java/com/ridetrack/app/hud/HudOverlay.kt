package com.ridetrack.app.hud

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.getSystemService
import com.ridetrack.app.data.HudSettings
import com.ridetrack.app.ui.hud.HudControlActions
import com.ridetrack.app.ui.hud.HudDismissTarget
import com.ridetrack.app.ui.hud.HudOverlayContent
import com.ridetrack.app.ui.theme.RideDark
import kotlin.math.hypot

/**
 * The pop-up window drawn over other apps (TYPE_APPLICATION_OVERLAY), plus a temporary
 * "drag here to hide" target window while it is being dragged. Main thread only.
 */
internal class HudOverlay(
    private val context: Context,
    private val actions: HudControlActions,
    private val onHideByDrag: () -> Unit,
    private val onPositionChanged: (x: Int, y: Int) -> Unit,
) {
    private val wm = context.getSystemService<WindowManager>()
    private val density = context.resources.displayMetrics.density

    private var owner: OverlayLifecycleOwner? = null
    private var hudView: HudTouchContainer? = null
    private var targetView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    var data by mutableStateOf<HudData?>(null)
    var settings by mutableStateOf(HudSettings())
    /** The camera while you film a video; null otherwise. */
    var viewfinder by mutableStateOf<ImageBitmap?>(null)
    private var collapsed by mutableStateOf(false)
    private var controlsOpen by mutableStateOf(false)
    private var nearTarget by mutableStateOf(false)
    /** Top of the Pause / Stop / Record buttons in the window; touches below it are taps, not drags. */
    private var buttonsTop = Float.MAX_VALUE

    val isShowing: Boolean get() = hudView != null

    fun show(): Boolean {
        if (hudView != null) return true
        val windowManager = wm ?: return false
        val lifecycleOwner = OverlayLifecycleOwner()
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val d = data
                if (d != null) HudOverlayContent(d, settings, collapsed, controlsOpen, actions, viewfinder = viewfinder, onButtonsTop = { buttonsTop = it })
            }
        }
        val container = HudTouchContainer(context, gestureCallbacks).apply {
            addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampIntoScreen() }
        }
        lifecycleOwner.attachTo(container)
        val metrics = context.resources.displayMetrics
        val p = baseParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.x ?: (metrics.widthPixels - dp(196f * settings.size.scale + 12f)).coerceAtLeast(0)
            y = settings.y ?: dp(118f)
        }
        return try {
            windowManager.addView(container, p)
            owner = lifecycleOwner
            hudView = container
            params = p
            true
        } catch (e: Exception) {
            // Permission revoked or window rejected: never crash the ride for the pop-up.
            Log.w(TAG, "Could not show pop-up", e)
            lifecycleOwner.destroy()
            false
        }
    }

    fun hide() {
        removeTarget()
        hudView?.let { v -> runCatching { wm?.removeView(v) } }
        owner?.destroy()
        owner = null
        hudView = null
        params = null
        controlsOpen = false
        collapsed = false
    }

    fun closeControls() {
        controlsOpen = false
        setFocusable(false)
    }

    private val gestureCallbacks = object : HudTouchContainer.Callbacks {
        override fun handlesGestures(y: Float) = !controlsOpen && y < buttonsTop
        override fun onDragStart() = showTarget()
        override fun onDrag(dx: Int, dy: Int) {
            val p = params ?: return
            p.x += dx
            p.y += dy
            clampIntoScreen()
            nearTarget = isNearTarget()
        }
        override fun onDragEnd() {
            val hideIt = nearTarget
            removeTarget()
            if (hideIt) {
                onHideByDrag()
            } else {
                params?.let { onPositionChanged(it.x, it.y) }
            }
        }
        override fun onLongPress() {
            controlsOpen = true
            collapsed = false
        }
        override fun onDoubleTap() {
            collapsed = !collapsed
        }
        override fun onOutsideTouch() {
            if (controlsOpen) closeControls()
        }
    }

    private fun setFocusable(focusable: Boolean) {
        val p = params ?: return
        p.flags = if (focusable) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        update()
    }

    private fun clampIntoScreen() {
        val v = hudView ?: return
        val p = params ?: return
        val m = context.resources.displayMetrics
        p.x = p.x.coerceIn(0, (m.widthPixels - v.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (m.heightPixels - v.height).coerceAtLeast(0))
        update()
    }

    private fun update() {
        val v = hudView ?: return
        val p = params ?: return
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun showTarget() {
        if (targetView != null || owner == null) return
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { RideDark { HudDismissTarget(nearTarget) } }
        }
        owner?.attachTo(view)
        val p = baseParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(40f)
        }
        runCatching { wm?.addView(view, p) }.onSuccess { targetView = view }
    }

    private fun removeTarget() {
        targetView?.let { v -> runCatching { wm?.removeView(v) } }
        targetView = null
        nearTarget = false
    }

    private fun isNearTarget(): Boolean {
        val v = hudView ?: return false
        val p = params ?: return false
        val m = context.resources.displayMetrics
        val hudCx = p.x + v.width / 2f
        val hudCy = p.y + v.height / 2f
        val targetCx = m.widthPixels / 2f
        val targetCy = m.heightPixels - dp(40f + 60f)
        return hypot(hudCx - targetCx, hudCy - targetCy) < dp(110f)
    }

    private fun baseParams(flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    )

    private fun dp(v: Float): Int = (v * density).toInt()

    companion object {
        private const val TAG = "HudOverlay"
    }
}
