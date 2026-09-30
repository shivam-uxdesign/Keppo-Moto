package com.ridetrack.app.hud

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Hosts the pop-up's Compose content and turns raw touches into drag / long-press /
 * double-tap. Raw screen coordinates are used for dragging because the window itself moves
 * under the finger. While the quick controls are open, touches go to the controls instead.
 */
@SuppressLint("ViewConstructor")
internal class HudTouchContainer(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {

    interface Callbacks {
        /**
         * Whether a touch starting at [y] (in this view) drags / long-presses the pop-up.
         * False passes it to the Compose children (quick controls open, or the ride buttons).
         */
        fun handlesGestures(y: Float): Boolean
        fun onDragStart()
        fun onDrag(dx: Int, dy: Int)
        fun onDragEnd()
        fun onLongPress()
        fun onDoubleTap()
        fun onOutsideTouch()
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    /** Decided on ACTION_DOWN for the whole gesture. */
    private var mine = false

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) {
                if (!dragging) callbacks.onLongPress()
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                callbacks.onDoubleTap()
                return true
            }
        },
    )

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) mine = callbacks.handlesGestures(ev.y)
        return mine
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            callbacks.onOutsideTouch()
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) mine = callbacks.handlesGestures(ev.y)
        if (!mine) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                lastX = ev.rawX
                lastY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                    dragging = true
                    cancelDetector(ev)
                    callbacks.onDragStart()
                }
                if (dragging) {
                    callbacks.onDrag((ev.rawX - lastX).toInt(), (ev.rawY - lastY).toInt())
                    lastX = ev.rawX
                    lastY = ev.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                callbacks.onDragEnd()
                return true
            }
        }
        if (!dragging) detector.onTouchEvent(ev)
        return true
    }

    private fun cancelDetector(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        detector.onTouchEvent(cancel)
        cancel.recycle()
    }
}
