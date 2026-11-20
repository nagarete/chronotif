// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import app.chronotif.ui.SearchActivity

/** A Quick Settings tile that opens Search (ADR 0008). On the lock screen, Search itself asks to unlock before opening anything. */
class SearchTile : TileService() {
    // Remembered for the Search checklist, which offers to add the tile. Listening means it's in Quick Settings.
    override fun onTileAdded() {
        Prefs.searchTileAdded = true
    }

    override fun onTileRemoved() {
        Prefs.searchTileAdded = false
    }

    override fun onStartListening() {
        Prefs.searchTileAdded = true
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val intent = SearchActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
