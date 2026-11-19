// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import app.chronotif.ui.SearchActivity

/** The "Search" shortcut on a long press of the app icon (ADR 0008). Launchers such as Nova can bind it to a gesture. */
object SearchShortcut {
    fun publish(context: Context) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcut = ShortcutInfo.Builder(context, "search")
            .setShortLabel(context.getString(R.string.search_label))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_search))
            .setIntent(SearchActivity.intent(context))
            .build()
        runCatching { sm.dynamicShortcuts = listOf(shortcut) }
    }
}
