// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.chronotif.ui.ScanActivity

/**
 * The Chronotif QR scanner tile: opens Google Lens (ADR 0017). Without Lens it still shows, and
 * tapping it says why it can't scan; Android's unavailable state would take no tap to say so.
 * On the lock screen [ScanActivity] shows over the keyguard; whether Lens opens there is up to
 * Lens (ADR 0017).
 */
class QrTile : TileService() {
    override fun onTileAdded() {
        Prefs.qrTileAdded = true
    }

    override fun onTileRemoved() {
        Prefs.qrTileAdded = false
    }

    override fun onStartListening() {
        Prefs.qrTileAdded = true
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.subtitle = if (GoogleLens.installed(this)) null else getString(R.string.qr_tile_needs_lens)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        UsageData.count(app.chronotif.core.Usage.Count.QR_TILE)
        val intent = ScanActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
