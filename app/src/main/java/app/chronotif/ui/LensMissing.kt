// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.os.Bundle
import android.service.quicksettings.TileService
import app.chronotif.GoogleLens
import app.chronotif.R

/**
 * Says the QR scanner isn't available without Google Lens (ADR 0017), and offers to get it.
 * [unlockFirst] runs its action after an unlock on the lock screen, since the Play Store won't open over it.
 */
internal fun showLensMissing(activity: Activity, unlockFirst: (() -> Unit) -> Unit, onClosed: () -> Unit = {}) {
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(R.string.qr_scanner)
        .setMessage(R.string.qr_needs_lens)
        .setPositiveButton(R.string.qr_get_lens) { _, _ -> unlockFirst { GoogleLens.get(activity) } }
        .setNegativeButton(R.string.close, null)
        .setOnDismissListener { onClosed() }
        .show()
}

/**
 * What the Chronotif QR scanner tile opens: Google Lens, or without it the message that the QR
 * scanner needs Lens. It shows over the lock screen, so the message needs no unlock.
 */
class ScanActivity : Activity() {
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        if (GoogleLens.open(this)) {
            finish()
            return
        }
        showLensMissing(this, unlockFirst = { then ->
            leaving = true
            val km = getSystemService(KeyguardManager::class.java)
            if (!km.isKeyguardLocked) {
                then()
                finish()
            } else km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    then()
                    finish()
                }

                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        }) { if (!leaving) finish() }
    }

    companion object {
        fun intent(tile: TileService): android.content.Intent =
            android.content.Intent(tile, ScanActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
