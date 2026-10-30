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
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.chronotif.ui.PanelActivity
import kotlin.math.abs

/**
 * Optional status-bar swipe. An invisible accessibility overlay covers the physical left half of
 * the status bar, where HyperOS opens its notification shade; a tap or downward swipe there opens
 * Chronotif. Ordinary overlays can't sit above the status bar, hence the accessibility service.
 * It subscribes to no events and can't retrieve window content (see xml/status_bar_swipe.xml).
 *
 * Shown only while Chronotif and this feature are on, the screen is on and unlocked, in portrait,
 * and none of Chronotif's own screens are open. Touches pass through while the status bar is
 * hidden (fullscreen apps).
 */
class StatusBarSwipe : AccessibilityService() {
    companion object {
        private const val TAG = "StatusBarSwipe"

        /** The connected service, or null when accessibility access is off or not yet bound. */
        var instance: StatusBarSwipe? = null
            private set

        private var ownUiResumed = 0

        /** Called for PanelActivity / LockActivity, so the strip never sits on top of them. */
        fun ownUiVisible(visible: Boolean) {
            ownUiResumed = (ownUiResumed + if (visible) 1 else -1).coerceAtLeast(0)
            instance?.refresh()
        }

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, StatusBarSwipe::class.java).flattenToString()
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return on?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
        }
    }

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var view: SwipeView? = null
    private var barVisible = true
    private var receiverRegistered = false

    @SuppressLint("RtlHardcoded")
    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "Chronotif status bar swipe"
        // Physical left, also in RTL: the system's notification side doesn't follow our locale.
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) hide() else refresh()
        }
    }

    override fun onServiceConnected() {
        instance = this
        if (!receiverRegistered) {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
            )
            receiverRegistered = true
        }
        refresh()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Re-evaluates whether the strip should be shown. Called again from setup when settings change. */
    fun refresh() {
        if (eligible()) show() else hide()
    }

    private fun eligible(): Boolean {
        if (!Prefs.enabled || !Prefs.statusSwipeEnabled || ownUiResumed > 0) return false
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return false
        // The lock screen keeps the system shade.
        return getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    private fun show() {
        val metrics = wm.currentWindowMetrics
        val bar = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        if (bar <= 0) {
            // No status bar to cover; never guess a height over app content.
            hide()
            return
        }
        params.width = metrics.bounds.width() / 2
        params.height = bar
        applyTouchable()
        view?.let { v ->
            try {
                wm.updateViewLayout(v, params)
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "Overlay update failed; re-adding", e)
                runCatching { wm.removeViewImmediate(v) }
                view = null
            }
        }
        barVisible = true
        applyTouchable()
        val v = SwipeView(this, ::open)
        // The bar's live visibility: hidden in fullscreen apps, where the strip must let touches through.
        v.setOnApplyWindowInsetsListener { _, insets ->
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            if (visible != barVisible) {
                barVisible = visible
                applyTouchable()
                view?.let { runCatching { wm.updateViewLayout(it, params) } }
            }
            insets
        }
        try {
            wm.addView(v, params)
            view = v
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't add the status-bar overlay", e)
        }
    }

    private fun applyTouchable() {
        params.flags = if (barVisible) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    private fun hide() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
    }

    private fun teardown() {
        if (instance === this) instance = null
        hide()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
    }

    /** Returns whether the panel was launched. */
    private fun open(): Boolean {
        if (!eligible()) {
            hide()
            return false
        }
        return try {
            startActivity(
                Intent(this, PanelActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't open the panel", e)
            false
        }
    }

    /** A tap, or a mostly downward single-finger swipe, opens the panel. Anything else does nothing. */
    @SuppressLint("ViewConstructor")
    private class SwipeView(context: Context, private val onOpen: () -> Boolean) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var tracking = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    tracking = true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
                MotionEvent.ACTION_MOVE -> if (tracking) {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        tracking = false
                        if (dy > abs(dx)) fire()
                    }
                }
                MotionEvent.ACTION_UP -> if (tracking) {
                    tracking = false
                    fire()
                }
            }
            return true
        }

        private fun fire() {
            if (onOpen()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
