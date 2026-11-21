// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.FingerprintSensor
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.core.FingerprintSpot

/**
 * Settings → Lock screen → Fit to your fingerprint sensor: one touch on the sensor tells the
 * lock-screen view where it is, since HyperOS keeps that from apps (see [FingerprintSpot]).
 * The screen is black and full-screen, so a touch's position is its place on the display.
 */
class FingerprintFitActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var body: TextView
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = Ui.text(this, 22f, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setText(R.string.fingerprint_fit_title)
        }
        body = Ui.text(this, 15f, 0xB3FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setText(R.string.fingerprint_fit_body)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), 0, dp(32), 0)
            addView(title, Ui.lp())
            addView(body, Ui.lp().apply { topMargin = dp(12) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(160) })
        })
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (done || ev.actionMasked != MotionEvent.ACTION_UP) return true
        val top = FingerprintSpot.fitted(ev.rawY, FingerprintSensor.screenHeight(this), dp(FingerprintSpot.HALF_DP).toFloat())
        if (top == null) {
            body.setText(R.string.fingerprint_fit_too_high)
            return true
        }
        Prefs.fingerprintTop = top
        done = true
        title.setText(R.string.fingerprint_fit_done)
        body.setText(R.string.fingerprint_fit_done_body)
        title.postDelayed({ finish() }, 1200)
        return true
    }
}
