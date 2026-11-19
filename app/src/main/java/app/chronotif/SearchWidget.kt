// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.chronotif.ui.SearchActivity

/**
 * A home-screen search bar, 2 to 5 cells wide: tapping it opens Search with the keyboard up. It
 * shows nothing that changes, so it never updates.
 */
open class SearchWidget(private val layout: Int = R.layout.search_widget) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(context, 0, SearchActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, layout).apply {
            setOnClickPendingIntent(R.id.search_bar, open)
        }
        manager.updateAppWidget(ids, views)
    }
}

/** The same, as a round 1x1 button the size of an app icon. */
class SearchIconWidget : SearchWidget(R.layout.search_icon_widget)
