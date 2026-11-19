// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Search on its own, over whatever is on screen (ADR 0008): opened from the edge handle's long
 * press, the Search tile, the widget and the app icon's shortcut. It shows no notifications, so it
 * never counts as a check. Over the lock screen, opening anything asks to unlock first.
 */
class SearchActivity : Activity(), NotifList.Host {

    companion object {
        /** Exported, so launchers such as Nova can bind it to their own gestures. */
        const val ACTION = "app.chronotif.action.SEARCH"
        /** Text to start with: what was typed on the lock-screen view before unlocking. */
        private const val EXTRA_QUERY = "query"

        fun intent(context: Context, query: String = ""): Intent =
            Intent(context, SearchActivity::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        fun open(context: Context, query: String = "") = context.startActivity(intent(context, query))
    }

    override val context: Context get() = this
    override val keepsSearchAfterUnlock get() = true

    private var unlocking = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        Ui.edgeToEdge(window)
        if (Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = dp(48) }
        }
        getSystemService(ShortcutManager::class.java)?.reportShortcutUsed("search")
        val root = FrameLayout(this)
        setContentView(root)
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        root.post { SearchOverlay.show(root, this, query) { finishQuietly() } }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF),
            if (Build.VERSION.SDK_INT >= 33) RECEIVER_EXPORTED else 0)
    }

    override fun onStop() {
        super.onStop()
        // Like the panel: once something else covers it, it's gone.
        if (!isFinishing && !unlocking) finish()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    // --- Host ---

    override fun launched() = finishQuietly()

    override fun guarded(action: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return action()
        unlocking = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                unlocking = false
                action()
            }

            override fun onDismissCancelled() {
                unlocking = false
            }

            override fun onDismissError() {
                unlocking = false
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }
}
