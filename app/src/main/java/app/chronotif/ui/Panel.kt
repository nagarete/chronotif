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
        val panel = current ?: if (Settings.canDrawOverlays(app) && !locked) {
            OverlayPanel(app).takeIf { it.show() }?.also { current = it }
        } else {
            null
        }
        if (panel == null) {
            openActivity(context, focusKey, replyIndex)
            return
        }
        if (focusKey != null) panel.view.focus(focusKey, replyIndex)
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
    private val ctx: Context = app.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)
        .let { app.createDisplayContext(it) }
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        .let { ContextThemeWrapper(it, R.style.Theme_Chronotif_Panel) }
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val blur = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
    val view = PanelView(ctx, this, blur) { remove() }
    private var attached = false
    private var hadFocus = false

    private val root = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) view.close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            // Like the shade: home, recents, another app or the system shade taking over
            // closes it. The keyboard doesn't take window focus.
            if (hasWindowFocus) hadFocus = true else if (hadFocus) remove()
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
        if (blur) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            blurBehindRadius = ctx.dp(48)
        }
    }

    init {
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
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
        @Suppress("DEPRECATION") // Still delivered for home and recents.
        ctx.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            },
        )
        StatusBarSwipe.ownUiVisible(true)
        view.shown()
        view.animateIn()
        return true
    }

    private fun remove() {
        if (!attached) return
        attached = false
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
