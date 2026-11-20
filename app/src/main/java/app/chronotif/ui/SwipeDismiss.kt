// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sign

/**
 * Horizontal swipe-to-dismiss that coexists with vertical scrolling and the view's own
 * click/long-press handling (it only takes over once a horizontal drag is recognised).
 * While [resist] says so (Swipe to clear is off), a sideways drag still takes over, so it
 * doesn't end as a tap, but the view only gives a little and springs back.
 */
class SwipeDismiss(
    private val canDismiss: () -> Boolean,
    private val onDismiss: () -> Unit,
    private val resist: () -> Boolean = { false },
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var resisting = false
    private var tracker: VelocityTracker? = null
    var dismissing = false
        private set

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        if (dismissing || !canDismiss()) return false
        val cfg = ViewConfiguration.get(v.context)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                track(e)
            }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && abs(dx) > cfg.scaledTouchSlop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    resisting = resist()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.cancelLongPress()
                    v.isPressed = false
                }
                if (dragging && resisting) {
                    v.translationX = dx * RESISTANCE
                } else if (dragging) {
                    v.translationX = dx
                    v.alpha = 1f - (abs(dx) / v.width).coerceAtMost(1f) * 0.7f
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                val dx = e.rawX - downX
                val t = tracker
                t?.computeCurrentVelocity(1000)
                val vx = t?.xVelocity ?: 0f
                t?.recycle()
                tracker = null
                val fling = abs(vx) > cfg.scaledMinimumFlingVelocity * 6 && sign(vx) == sign(dx)
                if (!resisting && e.actionMasked == MotionEvent.ACTION_UP && (abs(dx) > v.width * 0.4f || fling)) {
                    dismissing = true
                    v.animate().translationX(sign(dx) * v.width).alpha(0f).setDuration(160)
                        .withEndAction(onDismiss).start()
                } else {
                    v.animate().translationX(0f).alpha(1f).setDuration(160).start()
                }
                return true
            }
        }
        return dragging
    }

    private companion object {
        /** How far a card follows the finger when it can't be swiped away. */
        const val RESISTANCE = 0.12f
    }

    /** The view is being reused for fresh content: forget any finished dismissal. */
    fun reset(v: View) {
        if (dismissing) {
            dismissing = false
            v.animate().cancel()
            v.translationX = 0f
            v.alpha = 1f
        }
    }

    private fun track(e: MotionEvent) {
        val copy = MotionEvent.obtain(e)
        copy.setLocation(e.rawX, e.rawY)
        tracker?.addMovement(copy)
        copy.recycle()
    }
}
