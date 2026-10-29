// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.chronotif.ui.PanelActivity
import app.chronotif.ui.dp
import kotlin.math.abs

/**
 * PROTOTYPE. An invisible accessibility overlay on top of the left half of the status bar, where
 * HyperOS opens its notification shade. A downward swipe there opens Chronotif instead; the right
 * half (Control Center) is left alone. Ordinary overlays can't sit above the status bar, so this
 * needs an accessibility service. It reads no window content.
 */
class StatusBarSwipe : AccessibilityService() {
    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, StatusBarSwipe::class.java).flattenToString()
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return on?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
        }
    }

    private var view: View? = null
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onServiceConnected() {
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        refresh()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hide()
        runCatching { unregisterReceiver(screenReceiver) }
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Shown only while unlocked in portrait: the lock screen keeps its own shade. */
    private fun refresh() {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (locked || landscape) hide() else show()
    }

    private fun show() {
        val metrics = wm.currentWindowMetrics
        val bar = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val params = WindowManager.LayoutParams(
            metrics.bounds.width() / 2,
            if (bar > 0) bar else dp(24),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Chronotif status bar swipe"
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val v = view
        if (v != null) {
            runCatching { wm.updateViewLayout(v, params) }
            return
        }
        val nv = SwipeView(this)
        try {
            wm.addView(nv, params)
            view = nv
        } catch (_: RuntimeException) {
        }
    }

    private fun hide() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
    }

    @SuppressLint("ViewConstructor")
    private class SwipeView(context: Context) : View(context) {
        private val trigger = context.dp(16).toFloat()
        private var downX = 0f
        private var downY = 0f
        private var fired = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    fired = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!fired && dy > trigger && dy > abs(e.rawX - downX)) open()
                }
            }
            return true
        }

        private fun open() {
            fired = true
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            runCatching {
                context.startActivity(
                    Intent(context, PanelActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
        }
    }
}
