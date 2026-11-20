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
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.chronotif.ui.Panel
import kotlin.math.abs

/**
 * Optional status-bar swipe. An invisible accessibility overlay covers the physical left half of
 * the status bar, where HyperOS opens its notification shade; a tap or downward swipe there opens
 * Chronotif. Ordinary overlays can't sit above the status bar, hence the accessibility service.
 * It subscribes to no events and can't retrieve window content (see xml/status_bar_swipe.xml).
 *
 * Shown only while Chronotif and this feature are on, overlays are allowed, the screen is on and
 * unlocked, in portrait, and none of Chronotif's own screens are open. Touches pass through while
 * the status bar is hidden (fullscreen apps), including while it is only revealed temporarily.
 */
class StatusBarSwipe : AccessibilityService() {
    companion object {
        private const val TAG = "StatusBarSwipe"

        /** The connected service, or null when accessibility access is off or not yet bound. */
        var instance: StatusBarSwipe? = null
            private set

        private var ownUiShown = 0

        /** Called while the panel or lock-screen view is shown, so the strip never sits on them. */
        fun ownUiVisible(visible: Boolean) {
            ownUiShown = (ownUiShown + if (visible) 1 else -1).coerceAtLeast(0)
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
    private var probe: View? = null
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

    /**
     * Accessibility overlays sit above the status bar and, at least on MIUI 14 / Android 12, are
     * never told that it's visible. An ordinary app overlay below it is, so a 1 px, untouchable,
     * fully transparent one reports the bar's visibility. Transparent and untouchable, it doesn't
     * count as obscuring other apps' touches. Without it the strip isn't shown at all, rather
     * than risk covering fullscreen apps.
     */
    private val probeCtx by lazy {
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            .let { createDisplayContext(it) }
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }
    private val probeWm by lazy { probeCtx.getSystemService(WindowManager::class.java) }

    @SuppressLint("RtlHardcoded")
    private val probeParams = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "Chronotif status bar probe"
        gravity = Gravity.BOTTOM or Gravity.LEFT
        alpha = 0f
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

    /**
     * Collapses the system shade, so the Quick Settings tile can open the panel window. MIUI 14
     * may refuse the dedicated action; Back closes an open shade everywhere, and the tile only
     * runs while the shade is open, so it can't reach the app underneath.
     */
    fun dismissShade(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)) return true
        Log.i(TAG, "Dismiss-shade action refused; collapsing with Back")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun onInterrupt() {}

    /** Re-evaluates whether the strip should be shown. Called again from setup when settings change. */
    fun refresh() {
        // A pull keeps its strip until the finger lifts, though the panel it brings in is showing.
        if (view?.pulling == true) return
        when {
            !allowed() -> hide()
            // The probe stays, so the strip takes touches again the moment the panel closes:
            // a new probe would leave it untouchable, and a swipe would open the system shade.
            ownUiShown > 0 -> removeStrip()
            else -> show()
        }
    }

    private fun eligible(): Boolean = ownUiShown == 0 && allowed()

    /** Everything but Chronotif's own screens being open. */
    private fun allowed(): Boolean {
        if (!Prefs.enabled || !Prefs.statusSwipeEnabled) return false
        // The probe needs it; see probeParams.
        if (!Settings.canDrawOverlays(this)) return false
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return false
        // The lock screen keeps the system shade.
        return getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    private fun show() {
        val metrics = wm.currentWindowMetrics
        val bar = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        // No status bar to cover (never guess a height over app content), or no way to tell
        // when it's hidden.
        if (bar <= 0 || !showProbe()) {
            hide()
            return
        }
        params.width = metrics.bounds.width() / 2
        params.height = bar
        applyStrip()
    }

    /** Ensures the probe is attached. A new probe starts from "hidden" until it reports. */
    private fun showProbe(): Boolean {
        if (probe != null) return true
        barVisible = false
        val p = View(probeCtx)
        p.setOnApplyWindowInsetsListener { _, insets ->
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            if (visible != barVisible) {
                barVisible = visible
                applyStrip()
            }
            insets
        }
        return try {
            probeWm.addView(p, probeParams)
            probe = p
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't add the status-bar probe", e)
            false
        }
    }

    /** Adds or updates the strip with the current size and touchability, re-adding it if needed. */
    private fun applyStrip() {
        params.flags = if (barVisible) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (params.height <= 0) return // Not sized yet: show() calls again.
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
        val v = SwipeView(this, ::open, ::pull, ::refresh)
        try {
            wm.addView(v, params)
            view = v
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't add the status-bar overlay", e)
        }
    }

    private fun removeStrip() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
    }

    private fun hide() {
        removeStrip()
        probe?.let { runCatching { probeWm.removeViewImmediate(it) } }
        probe = null
    }

    private fun teardown() {
        if (instance === this) instance = null
        hide()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
    }

    /** The haptic comes first, so the swipe is felt before the panel has drawn anything. */
    private fun open(v: View) {
        if (!eligible()) {
            refresh()
            return
        }
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        try {
            Panel.open(this)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't open the panel", e)
        }
    }

    /** Starts a pull the panel follows; null if it can't, and the swipe then just opens it. */
    private fun pull(): Panel.Pull? {
        if (!eligible()) return null
        return try {
            Panel.pull(this)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't start the panel", e)
            null
        }
    }

    /**
     * A tap opens the panel. A mostly downward single-finger swipe pulls it in under the finger,
     * like the system shade; letting go opens it or lets it slide back. Anything else does nothing.
     * The window keeps the whole gesture, even once the panel is over it.
     */
    @SuppressLint("ViewConstructor")
    private class SwipeView(
        context: Context,
        private val onOpen: (View) -> Unit,
        private val onPull: () -> Panel.Pull?,
        private val onPullEnd: () -> Unit,
    ) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private var pull: Panel.Pull? = null
        /** Starting the pull shows the panel, which asks the strip to step aside: not yet. */
        private var starting = false
        private var velocity: VelocityTracker? = null

        val pulling get() = pull != null || starting

        private companion object {
            const val SETTLE_MS = 300L
        }

        @SuppressLint("ClickableViewAccessibility", "Recycle")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            velocity?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    tracking = true
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    letGo(e, cancelled = true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    pull?.move(dy)
                    if (tracking && (abs(dx) > slop || abs(dy) > slop)) {
                        tracking = false
                        if (dy > abs(dx)) {
                            starting = true
                            pull = onPull()?.also { it.move(dy) }
                            starting = false
                            if (pull == null) onOpen(this)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (tracking) {
                        tracking = false
                        onOpen(this)
                    }
                    letGo(e, cancelled = false)
                }
            }
            return true
        }

        /** Ends a pull; the haptic marks the panel committing to open. A cancelled pull slides back. */
        private fun letGo(e: MotionEvent, cancelled: Boolean) {
            val p = pull ?: return
            pull = null
            val vy = velocity?.let {
                it.computeCurrentVelocity(1000)
                it.yVelocity
            } ?: 0f
            if (p.release(if (cancelled) 0f else e.rawY - downY, if (cancelled) 0f else vy)) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            // Once the panel has settled: stepping aside lays windows out, which would stutter it.
            postDelayed(onPullEnd, SETTLE_MS)
        }
    }
}
