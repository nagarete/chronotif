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
 * It's offered on every phone, since some with a sensor under the screen don't say so; where one
 * that doesn't was fitted, a button near the top takes the fit back for a sensor on the side or back.
 */
class FingerprintFitActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var body: TextView
    private var remove: TextView? = null
    private var done = false
    /** The gesture now going on started on [remove], so it goes to the button. */
    private var removing = false

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
            // A phone that says it has a sensor under the screen keeps it there: fit again instead.
            if (Prefs.fingerprintTop > 0f && !FingerprintSensor.present(this@FingerprintFitActivity)) {
                remove = Ui.text(this@FingerprintFitActivity, 15f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    gravity = Gravity.CENTER
                    setText(R.string.fingerprint_fit_remove)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = Ui.pressable(0x33FFFFFF, dp(24).toFloat(), 0x33FFFFFF)
                    setOnClickListener { removeFit() }
                }
                addView(remove, Ui.lp(-2).apply { topMargin = dp(32); gravity = Gravity.CENTER_HORIZONTAL })
            }
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) removing = !done && remove?.let { hits(it, ev) } == true
        if (removing) return super.dispatchTouchEvent(ev)
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

    private fun hits(v: TextView, ev: MotionEvent): Boolean {
        val at = IntArray(2).also { v.getLocationOnScreen(it) }
        return ev.rawX >= at[0] && ev.rawX < at[0] + v.width && ev.rawY >= at[1] && ev.rawY < at[1] + v.height
    }

    /** The phone's sensor isn't under the screen after all: the lock screen goes back to its usual layout. */
    private fun removeFit() {
        Prefs.fingerprintTop = 0f
        done = true
        remove?.visibility = android.view.View.GONE
        title.setText(R.string.fingerprint_fit_removed)
        body.text = ""
        title.postDelayed({ finish() }, 1200)
    }
}
