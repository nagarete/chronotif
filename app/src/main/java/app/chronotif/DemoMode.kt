// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.annotation.SuppressLint
import android.content.Context

/**
 * Debug builds only: while on, Chronotif shows nothing but notifications from the made-up apps
 * used to record the promo video (packages under [PREFIX]), so real notifications can't end up
 * in a recording. Kept on across restarts, so a killed process can't let them back in.
 * Switched over adb by the debug build's DemoModeReceiver (see website/video/README.md).
 */
object DemoMode {
    const val PREFIX = "app.chronotif.demo."
    private const val FILE = "demo_mode"
    private const val KEY = "on"

    var on = false
        private set

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        on = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    // Written at once, so demo mode is already saved if the process dies right after the broadcast.
    @SuppressLint("ApplySharedPref")
    fun set(context: Context, value: Boolean) {
        if (!BuildConfig.DEBUG) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).commit()
        on = value
        NotifStore.changed()
    }

    fun hides(pkg: String): Boolean = on && !pkg.startsWith(PREFIX)
}
