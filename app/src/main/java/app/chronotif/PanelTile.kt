// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.chronotif.ui.Panel
import app.chronotif.ui.PanelActivity

/** Quick Settings tile: a second way into the panel, showing how many are new. */
class PanelTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val fresh = if (Prefs.enabled) NotifStore.newCount() else 0
        tile.state = if (fresh > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            !Prefs.enabled -> getString(R.string.tile_off)
            fresh > 0 -> getString(R.string.tile_new, fresh)
            else -> getString(R.string.tile_nothing_new)
        }
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::launch) else launch()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launch() {
        // The panel window can only show once the shade is out of the way. Collapsing it needs
        // the status-bar swipe's accessibility service; otherwise open the activity, which
        // collapses the shade itself but pauses the app underneath.
        val swipe = StatusBarSwipe.instance?.takeIf { Prefs.enabled && Prefs.statusSwipeEnabled }
        if (swipe != null && Settings.canDrawOverlays(this) && swipe.dismissShade()) {
            Panel.open(this)
            return
        }
        Log.i("PanelTile", "Opening the panel activity (swipe service ${if (swipe == null) "unavailable" else "couldn't close the shade"})")
        val intent = Intent(this, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
