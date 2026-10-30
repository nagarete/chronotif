// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.chronotif.ui.LockActivity
import app.chronotif.ui.PanelActivity

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Updater.sync(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (a is PanelActivity || a is LockActivity) StatusBarSwipe.ownUiVisible(true)
            }
            override fun onActivityStopped(a: Activity) {
                if (a is PanelActivity || a is LockActivity) StatusBarSwipe.ownUiVisible(false)
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
