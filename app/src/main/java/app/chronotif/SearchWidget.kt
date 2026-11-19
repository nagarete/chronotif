// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.chronotif.ui.SearchActivity

/** A home-screen search bar: tapping it opens Search with the keyboard up. It shows nothing that changes, so it never updates. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(context, 0, SearchActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(context.packageName, R.layout.search_widget).apply {
            setOnClickPendingIntent(R.id.search_bar, open)
        }
        manager.updateAppWidget(ids, views)
    }
}
