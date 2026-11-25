// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import app.chronotif.ui.SearchActivity

/** The "Search" shortcut on a long press of the app icon (ADR 0008). Launchers such as Nova can bind it to a gesture. */
object SearchShortcut {
    /**
     * Called at every app start, so it runs on [Background]: each ShortcutManager call goes to the
     * system. It publishes again only when the label or intent differs (a new language), since Android
     * limits how often an app in the background may update its shortcuts.
     */
    fun publish(context: Context) {
        val app = context.applicationContext
        Background.run {
            val sm = app.getSystemService(ShortcutManager::class.java) ?: return@run
            val label = app.getString(R.string.search_label)
            val intent = SearchActivity.intent(app)
            val published = runCatching { sm.dynamicShortcuts }.getOrNull()?.firstOrNull { it.id == ID }
            if (published?.shortLabel?.toString() == label && published.intent?.filterEquals(intent) == true) return@run
            val shortcut = ShortcutInfo.Builder(app, ID)
                .setShortLabel(label)
                .setIcon(Icon.createWithResource(app, R.drawable.ic_shortcut_search))
                .setIntent(intent)
                .build()
            runCatching { sm.dynamicShortcuts = listOf(shortcut) }
        }
    }

    private const val ID = "search"
}
