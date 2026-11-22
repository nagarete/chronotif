// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The QR scanner is Google Lens and nothing else (ADR 0017). Lens comes inside the Google app, and
 * as its own Google Lens app; either one opens straight into its camera. Google publishes no intent
 * for this, so Chronotif opens the Lens app, or without it the Google app the way the Lens app does.
 * When neither is there, the QR scanner isn't available.
 */
object GoogleLens {
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val LENS_APP = "com.google.ar.lens"

    /** How to open Lens on this phone, or null without it. */
    fun intent(context: Context): Intent? {
        val pm = context.packageManager
        // The Lens app opens the Google app's Lens with google://lens; Chronotif does the same without
        // it. (googleapp://lens resolves too, but on HyperOS it closes at once without opening Lens.)
        val inGoogle = Intent(Intent.ACTION_VIEW, Uri.parse("google://lens")).setPackage(GOOGLE_APP)
        val found = pm.getLaunchIntentForPackage(LENS_APP)
            ?: inGoogle.takeIf { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
        return found?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun installed(context: Context) = intent(context) != null

    /** Opens Lens; false when it isn't there or won't start. */
    fun open(context: Context): Boolean {
        val i = intent(context) ?: return false
        return runCatching { context.startActivity(i) }.isSuccess
    }

    /** Google Lens in the Play Store, or on the web without the Play Store. */
    fun get(context: Context) {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$LENS_APP")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$LENS_APP"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(store) }.onFailure { runCatching { context.startActivity(web) } }
    }
}
