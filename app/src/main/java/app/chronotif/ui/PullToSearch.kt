// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import app.chronotif.Prefs
import kotlin.math.abs

/**
 * Pulling down with the list at its top opens App search (ADR 0007). Fed every touch from a
 * window's dispatchTouchEvent; [list] follows the finger, damped, and springs back on release.
 * [ready] lets the panel ignore touches until it has finished opening.
 */
class PullToSearch(
    private val list: View,
    private val atTop: () -> Boolean,
    private val ready: () -> Boolean = { true },
    private val open: () -> Unit,
) {
    companion object {
        /** How far the finger must travel for the pull to open search; a shorter one springs back. */
        const val OPEN_DP = 80
        const val DAMPING = 0.5f
    }

    private val slop = ViewConfiguration.get(list.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var pulling = false

    /**
     * Returns true while this gesture is a pull, so the caller stops dispatching it. On the move
     * that starts a pull, [cancelChildren] is called so a card or scroll stops tracking it.
     */
    fun onTouch(ev: MotionEvent, cancelChildren: () -> Unit): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                pulling = false
                armed = Prefs.appSearch && ready() && atTop()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!pulling) armed = false
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (armed && !pulling) {
                    if (dy > slop * 2 && dy > abs(dx) * 2) {
                        pulling = true
                        cancelChildren()
                    } else if (dy < -slop || abs(dx) > slop * 2) {
                        armed = false
                    }
                }
                if (pulling) {
                    list.translationY = maxOf(0f, dy) * DAMPING
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling) {
                pulling = false
                armed = false
                list.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
                val reached = ev.rawY - downY >= OPEN_DP * list.resources.displayMetrics.density
                if (ev.actionMasked == MotionEvent.ACTION_UP && reached) open()
                return true
            }
        }
        return false
    }
}
