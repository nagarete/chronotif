// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Updater.sync(this)
        ListenerWatchdog.schedule(this)
        ListenerService.ensureBound(this)
    }
}
