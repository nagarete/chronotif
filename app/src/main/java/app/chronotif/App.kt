// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import app.chronotif.ui.LockActivity
import app.chronotif.ui.Panel
import app.chronotif.ui.PanelActivity

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        DemoMode.init(this)
        UsageData.init(this)
        Updater.sync(this)
        SearchShortcut.publish(this)
        Apps.watchPackages(this)
        config = Configuration(resources.configuration)
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

    // The panel kept between opens (ADR 0009) goes when memory is short or its look may be stale.
    // So do decoded pictures when memory is short, and app names and icons when their look may be stale.

    private var config: Configuration? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Hidden activities aren't a shortage, just the settings screen closing.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Panel.drop()
            Apps.trim()
            Images.trim()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Panel.drop()
        config?.let { Apps.configurationChanged(it.diff(newConfig)) }
        config = Configuration(newConfig)
    }
}
