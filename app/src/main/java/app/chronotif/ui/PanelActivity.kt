// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

/**
 * The panel as an activity: the fallback when it can't be an overlay window (no overlay
 * permission, or opened from the lock screen or the Quick Settings tile). Unlike the window, it
 * pauses the app underneath. See [Panel].
 */
class PanelActivity : Activity(), NotifList.Host {

    companion object {
        const val EXTRA_FOCUS_KEY = "focus_key"
        const val EXTRA_REPLY_INDEX = "reply_index"
    }

    override val context: Context get() = this

    private lateinit var panel: PanelView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        val blur = Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled
        if (blur) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = dp(48) }
        }
        panel = PanelView(this, this, blur) {
            finish()
            noTransition()
        }
        setContentView(panel)
        handleFocus(intent)

        if (Build.VERSION.SDK_INT >= 33) {
            // Older versions fall back to the default back behaviour (finish).
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { panel.close() }
        }
        panel.animateIn()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleFocus(intent)
    }

    private fun handleFocus(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_FOCUS_KEY) ?: return
        panel.focus(key, intent.getIntExtra(EXTRA_REPLY_INDEX, -1))
    }

    override fun onResume() {
        super.onResume()
        panel.shown()
    }

    override fun onPause() {
        super.onPause()
        panel.dismissed()
    }

    override fun onStop() {
        super.onStop()
        // The panel is transient: once it's not on screen, it's gone.
        if (!isFinishing) finish()
    }

    // --- Host ---

    override fun launched() {
        finish()
        noTransition()
    }

    override fun guarded(action: () -> Unit) = action()

    @Suppress("DEPRECATION")
    private fun noTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }
}
