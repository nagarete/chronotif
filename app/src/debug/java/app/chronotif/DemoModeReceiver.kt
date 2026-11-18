// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * `adb shell am broadcast -n app.chronotif.debug/app.chronotif.DemoModeReceiver --ez on true`
 * Guarded by DUMP, which the adb shell holds and ordinary apps can't get.
 */
class DemoModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DemoMode.set(context, intent.getBooleanExtra("on", false))
        resultData = "demo mode ${if (DemoMode.on) "on" else "off"}"
    }
}
