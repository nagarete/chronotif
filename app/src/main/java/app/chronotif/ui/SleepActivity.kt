package app.chronotif.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import app.chronotif.BuildConfig

/**
 * Sleep from the edge handle: a black page over whatever is on screen, as dim as the display goes
 * and without the system bars, that asks for the shortest screen timeout. Android has no call for
 * an app to put the display to sleep, so it sleeps on its own once its minimum timeout runs out, as
 * Double-tap to sleep does (ADR 0014). A tap before then brings back the app underneath.
 */
class SleepActivity : Activity() {
    companion object {
        // NO_USER_ACTION keeps the app underneath from hearing onUserLeaveHint: a playing video
        // would otherwise go picture-in-picture, and its window would keep the screen on.
        fun open(context: Context) = context.startActivity(
            Intent(context, SleepActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        )
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    /** The finger that held the handle may still be down when this opens; its lift isn't a tap. */
    private var armed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        }
        userActivityTimeout(1L)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> armed = true
            MotionEvent.ACTION_UP -> if (armed) finish()
        }
        return true
    }

    /** How long after the last touch the display sleeps while this window shows. Android won't go below its own minimum. */
    private fun userActivityTimeout(ms: Long) {
        try {
            val attrs = window.attributes
            WindowManager.LayoutParams::class.java.getField("userActivityTimeout").setLong(attrs, ms)
            window.attributes = attrs
        } catch (e: ReflectiveOperationException) {
            if (BuildConfig.DEBUG) android.util.Log.w("Chronotif", "screen timeout unavailable", e)
        }
    }
}
