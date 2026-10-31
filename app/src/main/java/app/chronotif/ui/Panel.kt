// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.chronotif.R
import app.chronotif.StatusBarSwipe

/**
 * Opens the panel. It's an overlay window, like the system shade, so the app underneath keeps
 * running (an activity would pause it). Falls back to [PanelActivity] when overlays aren't
 * allowed or the device is locked.
 */
object Panel {
    private var current: OverlayPanel? = null

    fun open(context: Context, focusKey: String? = null, replyIndex: Int = -1) {
        val app = context.applicationContext
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Opening while it animates out replaces it, rather than losing the request.
        current?.takeIf { it.closing }?.remove()
        val panel = current ?: if (Settings.canDrawOverlays(app) && !locked) {
            OverlayPanel(app).takeIf { it.show() }?.also { current = it }
        } else {
            null
        }
        if (panel == null) {
            openActivity(context, focusKey, replyIndex)
            return
        }
        if (focusKey != null) panel.focusWhenReady(focusKey, replyIndex)
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
    }
}

/** The panel as a focusable, full-screen overlay window. Gone as soon as it loses focus. */
internal class OverlayPanel(app: Context) : NotifList.Host {
    private companion object {
        const val FOCUS_TIMEOUT_MS = 3000L
    }

    private val ctx: Context = app.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)
        .let { app.createDisplayContext(it) }
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        .let { ContextThemeWrapper(it, R.style.Theme_Chronotif_Panel) }
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val blur = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
    // The window dims instead of the view: Android then gives the status and navigation bars
    // light icons over it, as for the shade. A view scrim leaves them to the app underneath.
    private val view = PanelView(ctx, this, blur, windowDims = true) { remove() }
    private var attached = false
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
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !ImageViewer.dismissIn(this)) view.close()
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
        dimAmount = PanelView.scrimAlpha(blur)
        if (blur) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            blurBehindRadius = ctx.dp(48)
        }
    }

    init {
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.accessibilityPaneTitle = ctx.getString(R.string.tile_label)
    }

    fun focusWhenReady(key: String, replyIndex: Int) {
        if (hadFocus) view.focus(key, replyIndex) else pendingFocus = key to replyIndex
    }

    /** Returns false if the window couldn't be added; the caller falls back to the activity. */
    fun show(): Boolean {
        try {
            wm.addView(root, params)
        } catch (e: RuntimeException) {
            Log.w("Panel", "Couldn't show the panel window", e)
            return false
        }
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
        view.shown()
        view.animateIn()
        root.postDelayed(focusTimeout, FOCUS_TIMEOUT_MS)
        return true
    }

    fun remove() {
        if (!attached) return
        attached = false
        root.removeCallbacks(focusTimeout)
        view.dismissed()
        runCatching { ctx.unregisterReceiver(receiver) }
        runCatching { wm.removeViewImmediate(root) }
        StatusBarSwipe.ownUiVisible(false)
        Panel.removed(this)
    }

    // --- Host ---

    override val context: Context get() = ctx

    override fun launched() = remove()

    override fun guarded(action: () -> Unit) = action()
}
