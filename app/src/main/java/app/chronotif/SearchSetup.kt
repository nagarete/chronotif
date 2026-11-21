// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The checklist at the top of Settings → Search: what Search can do once allowed, then its
 * home-screen widget. A step is done when it's on (or the widget is placed), or handled when the
 * user said Not now; the switches below the checklist still turn it on later. The Search tile is
 * offered too, but it's optional and never counts toward what's left.
 */
object SearchSetup {

    enum class Step(val counted: Boolean = true) {
        CONTACTS,
        CALLS,
        CURRENCY,
        CALENDAR,
        WIDGET,
        TILE(counted = false),
    }

    /** The steps that count toward "N steps left", in the order they're shown. */
    val counted: List<Step> = Step.entries.filter { it.counted }

    fun done(ctx: Context, step: Step): Boolean = when (step) {
        Step.CONTACTS -> Prefs.searchContacts && granted(ctx, Manifest.permission.READ_CONTACTS)
        Step.CALLS -> Prefs.searchCalls && granted(ctx, Manifest.permission.CALL_PHONE)
        Step.CURRENCY -> Prefs.currencyRates
        Step.CALENDAR -> Prefs.searchCalendar && granted(ctx, Manifest.permission.READ_CALENDAR)
        Step.WIDGET -> widgetPlaced(ctx)
        Step.TILE -> Prefs.searchTileAdded
    }

    fun skipped(step: Step) = step.name in Prefs.searchSetupSkipped

    /** Not now: the step stops counting as left, and can still be done from its row or its switch. */
    fun skip(step: Step) {
        Prefs.searchSetupSkipped = Prefs.searchSetupSkipped + step.name
    }

    fun handled(ctx: Context, step: Step) = done(ctx, step) || skipped(step)

    fun left(ctx: Context) = counted.count { !handled(ctx, it) }

    /** The first counted step still left, or null when everything is done or put off. */
    fun next(ctx: Context): Step? = counted.firstOrNull { !handled(ctx, it) }

    /** Whether a Search widget, the bar or the round one, sits on a home screen. */
    fun widgetPlaced(ctx: Context): Boolean {
        val widgets = AppWidgetManager.getInstance(ctx) ?: return false
        return listOf(SearchWidget::class.java, SearchIconWidget::class.java).any {
            widgets.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty()
        }
    }

    private fun granted(ctx: Context, permission: String) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun title(step: Step) = when (step) {
        Step.CONTACTS -> R.string.search_find_contacts
        Step.CALLS -> R.string.search_calls
        Step.CURRENCY -> R.string.search_currency_rates
        Step.CALENDAR -> R.string.search_find_calendar
        Step.WIDGET -> R.string.search_setup_widget
        Step.TILE -> R.string.search_setup_tile
    }

    /** Whether the home screen lets apps place a widget for the user to confirm. */
    fun canPinWidget(ctx: Context) = AppWidgetManager.getInstance(ctx)?.isRequestPinAppWidgetSupported == true

    /** Asks the home screen to place the Search bar; the step shows whether it did once the user is back. */
    fun pinWidget(ctx: Context) {
        if (!canPinWidget(ctx)) return
        AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, SearchWidget::class.java), null, null)
    }

    /** Asks Android (13 and newer) to add the Search tile to Quick Settings, then calls [answered]. */
    fun addTile(ctx: Context, answered: () -> Unit = {}) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
            ComponentName(ctx, SearchTile::class.java),
            ctx.getString(R.string.search_tile_label),
            android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_search),
            ctx.mainExecutor,
        ) { result ->
            if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Prefs.searchTileAdded = true
            }
            answered()
        }
    }
}
