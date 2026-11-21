// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.chronotif.CardSource
import app.chronotif.R
import app.chronotif.StatusBarSwipe
import app.chronotif.Tutorial

/**
 * Opens the panel. It's an overlay window, like the system shade, so the app underneath keeps
 * running (an activity would pause it). Falls back to [PanelActivity] when overlays aren't
 * allowed or the device is locked.
 *
 * The last panel is kept once closed, cards and all, so the next open only updates what changed
 * (ADR 0009). Nothing runs while it's closed. It's let go when memory runs short or its look may
 * be out of date: see [drop].
 */
object Panel {
    // Both are built on the application context, never an activity's.
    @SuppressLint("StaticFieldLeak")
    private var current: OverlayPanel? = null
    @SuppressLint("StaticFieldLeak")
    private var kept: OverlayPanel? = null

    fun open(context: Context, focusKey: String? = null, replyIndex: Int = -1) {
        val panel = current?.takeIf { !it.closing } ?: overlay(context, pulling = false)
        if (panel == null) {
            openActivity(context, focusKey, replyIndex)
            return
        }
        if (focusKey != null) panel.focusWhenReady(focusKey, replyIndex)
    }

    /** A status-bar pull the panel follows, or null when it can't (the caller then just opens it). */
    interface Pull {
        /** The finger is [dy] px below where it started. */
        fun move(dy: Float)
        /** Let go: opens past a third of the screen or on a flick down, else slides away. True if it opens. */
        fun release(dy: Float, velocityY: Float): Boolean
    }

    fun pull(context: Context): Pull? {
        if (current?.closing == false) return null
        return overlay(context, pulling = true)
    }

    private fun overlay(context: Context, pulling: Boolean): OverlayPanel? {
        val requestedAt = SystemClock.uptimeMillis()
        val app = context.applicationContext
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Opening while it animates out replaces it, rather than losing the request.
        current?.takeIf { it.closing }?.remove()
        if (!Settings.canDrawOverlays(app) || locked) return null
        // A panel kept from before the Tutorial started, or ended, shows the wrong notifications.
        val source = Tutorial.source ?: CardSource.Real
        if (kept?.source !== source) drop()
        val wasKept = kept != null
        val panel = kept ?: OverlayPanel(app, source)
        kept = null
        if (!panel.show(pulling, PanelView.Timing(requestedAt, wasKept))) return null
        current = panel
        return panel
    }

    /**
     * Lets go of the kept panel, so the next open builds a new one: memory is short, or the
     * theme, font size, language or Chronotif's settings may have changed how it should look.
     */
    fun drop() {
        kept?.destroy()
        kept = null
    }

    fun openActivity(context: Context, focusKey: String? = null, replyIndex: Int = -1) {
        context.startActivity(
            Intent(context, PanelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .apply {
                    if (focusKey != null) {
                        putExtra(PanelActivity.EXTRA_FOCUS_KEY, focusKey)
                        putExtra(PanelActivity.EXTRA_REPLY_INDEX, replyIndex)
                    }
                },
        )
    }

    internal fun removed(panel: OverlayPanel) {
        if (current === panel) current = null
        if (kept !== panel) kept?.destroy()
        kept = panel
    }
}

/** The panel as a focusable, full-screen overlay window. Gone as soon as it loses focus. */
internal class OverlayPanel(app: Context, val source: CardSource = CardSource.Real) : NotifList.Host, Panel.Pull {
    private companion object {
        const val FOCUS_TIMEOUT_MS = 3000L
        /** The pull that brings the panel fully in, as a share of the screen's height. */
        const val PULL_FULL = 0.5f
        /** Letting go past this share of the screen opens it. */
        const val PULL_OPENS = 1 / 3f
        /** The blur fading in after a pull, updated at 30 per second. */
        const val BLUR_IN_MS = 160L
        const val WINDOW_UPDATE_MS = 32L
        /** While hidden between opens. */
        const val HIDDEN_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private val ctx: Context = app.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)
        .let { app.createDisplayContext(it) }
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        .let { ContextThemeWrapper(it, R.style.Theme_Chronotif_Panel) }
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val blur = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
    // The window dims instead of the view: Android then gives the status and navigation bars
    // light icons over it, as for the shade. A view scrim leaves them to the app underneath, so
    // the view only dims while a pull is moving (see dimWindow).
    private val view = PanelView(ctx, this, blur, windowDims = true, source = source) { remove() }
    /** Open: shown, or brought in by a pull. */
    private var attached = false
    /** The window exists, open or hidden between opens. */
    private var added = false
    private var hadFocus = false
    private var pendingFocus: Pair<String, Int>? = null

    val closing get() = view.isClosing

    /** A window that never gets focus (e.g. overlays suppressed) would otherwise never close. */
    private val focusTimeout = Runnable { if (!hadFocus) remove() }

    private val back = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { view.close() } else null

    private val root = object : FrameLayout(ctx) {
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // Android 16 (and 13+ with predictive back) routes Back to callbacks, not key events.
            if (Build.VERSION.SDK_INT >= 33) {
                findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, back!!,
                )
            }
        }

        override fun onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= 33) findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back!!)
            super.onDetachedFromWindow()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !Overlay.dismissTop(this)) view.close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            // Like the shade: home, recents, another app or the system shade taking over
            // closes it. The keyboard doesn't take window focus.
            if (hasWindowFocus) {
                if (!hadFocus) {
                    hadFocus = true
                    // The keyboard for a reply handoff only shows once the window has focus.
                    pendingFocus?.let { (key, index) -> view.focus(key, index) }
                    pendingFocus = null
                }
            } else if (hadFocus) {
                remove()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = remove()
    }

    @SuppressLint("RtlHardcoded")
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "Chronotif panel"
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        if (blur) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        // Dim and blur amounts are set per open by window().
    }

    init {
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.accessibilityPaneTitle = ctx.getString(R.string.tile_label)
    }

    private val blurRadius = ctx.dp(48)
    /** Faster than this, a pull let go short of a third of the screen still opens: a flick. */
    private val flingVelocity = ctx.dp(1000).toFloat()
    private var settling: ValueAnimator? = null
    private var windowUpdatedAt = 0L

    fun focusWhenReady(key: String, replyIndex: Int) {
        if (hadFocus) view.focus(key, replyIndex) else pendingFocus = key to replyIndex
    }

    /**
     * Returns false if the window couldn't be added; the caller falls back to the activity. A
     * [pulling] panel starts out of sight and follows [move] until [release].
     */
    fun show(pulling: Boolean, timing: PanelView.Timing? = null): Boolean {
        view.reset()
        hadFocus = false
        pendingFocus = null
        // A pull starts out of sight, dimmed by the panel itself until it settles; see release().
        view.pull(if (pulling) 0f else 1f)
        window(if (pulling) 0f else 1f, apply = false)
        params.flags = params.flags and HIDDEN_FLAGS.inv()
        root.visibility = View.VISIBLE
        try {
            if (added) wm.updateViewLayout(root, params) else wm.addView(root, params)
        } catch (e: RuntimeException) {
            Log.w("Panel", "Couldn't show the panel window", e)
            destroy()
            return false
        }
        added = true
        attached = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            @Suppress("DEPRECATION") // Still sent by the system for home and recents.
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        // Both come from the system, which reaches non-exported receivers.
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag") // The flags only exist from API 33.
            ctx.registerReceiver(receiver, filter)
        }
        StatusBarSwipe.ownUiVisible(true)
        view.shown(pulling, timing)
        if (!pulling) view.animateIn()
        root.postDelayed(focusTimeout, FOCUS_TIMEOUT_MS)
        return true
    }

    // --- Following a status-bar pull ---

    private val screenHeight get() = root.height.takeIf { it > 0 } ?: wm.currentWindowMetrics.bounds.height()

    override fun move(dy: Float) {
        if (attached && settling == null) progress(dy / (screenHeight * PULL_FULL))
    }

    private fun progress(p: Float) {
        val v = p.coerceIn(0f, 1f)
        view.pull(v)
        view.dim(v)
    }

    override fun release(dy: Float, velocityY: Float): Boolean {
        if (!attached) return false
        val opens = velocityY > flingVelocity || (dy >= screenHeight * PULL_OPENS && velocityY > -flingVelocity)
        val from = view.pullProgress
        settling = ValueAnimator.ofFloat(from, if (opens) 1f else 0f).apply {
            duration = if (opens) 220L else 180L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { progress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (settling !== animation) return
                    settling = null
                    if (!opens) {
                        remove()
                        return
                    }
                    view.settle()
                    dimWindow()
                }
            })
            start()
        }
        return opens
    }

    /**
     * A settled pull hands its dim to the window. Changing the window lays it out again, which
     * costs a slow phone most of a frame, so it waits until nothing else moves. With a blur, the
     * panel's own dim stays (it's the same light tint) and the blur fades in; without one, the
     * window's dim takes over at once so the status bar gets light icons over it.
     */
    private fun dimWindow() {
        if (!blur) {
            window(1f)
            view.dim(0f)
            return
        }
        settling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BLUR_IN_MS
            addUpdateListener { window(it.animatedValue as Float, dim = false) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (settling === animation) settling = null
                }
            })
            start()
        }
    }

    /** The window's dim (unless [dim] is false) and blur, [amount] of the way in. */
    private fun window(amount: Float, dim: Boolean = true, apply: Boolean = true) {
        params.dimAmount = if (dim) PanelView.scrimAlpha(blur) * amount else 0f
        if (blur) params.blurBehindRadius = (blurRadius * amount).toInt()
        if (!apply || !attached) return
        val now = SystemClock.uptimeMillis()
        if (amount == 0f || amount == 1f || now - windowUpdatedAt >= WINDOW_UPDATE_MS) {
            windowUpdatedAt = now
            runCatching { wm.updateViewLayout(root, params) }
        }
    }

    fun remove() {
        if (!attached) return
        attached = false
        // Cleared first: a cancelled animation still ends, and its end mustn't act on a closed panel.
        val s = settling
        settling = null
        s?.cancel()
        root.removeCallbacks(focusTimeout)
        view.dismissed()
        runCatching { ctx.unregisterReceiver(receiver) }
        // Hidden rather than removed: removing it would drop every card's drawing, and a list
        // of a hundred cards takes a noticeable moment to attach and draw again. Hidden, it isn't
        // shown, takes no touches and can't be focused.
        root.visibility = View.INVISIBLE
        params.flags = params.flags or HIDDEN_FLAGS
        window(0f, apply = false)
        try {
            wm.updateViewLayout(root, params)
        } catch (e: RuntimeException) {
            destroy()
        }
        // A Preview, image viewer or Search left open doesn't come back with the next open.
        for (i in root.childCount - 1 downTo 0) if (root.getChildAt(i) !== view) root.removeViewAt(i)
        StatusBarSwipe.ownUiVisible(false)
        Panel.removed(this)
    }

    /** Removes the window for good, open or hidden. */
    fun destroy() {
        if (attached) remove()
        if (!added) return
        added = false
        runCatching { wm.removeViewImmediate(root) }
    }

    // --- Host ---

    override val context: Context get() = ctx

    override fun launched() = remove()

    override fun guarded(action: () -> Unit) = action()
}
