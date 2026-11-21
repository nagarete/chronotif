// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.chronotif.Prefs
import app.chronotif.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings → Lock screen → Fit to your fingerprint sensor, on phones with one under the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FingerprintFitTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
    }

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun openLockPage(): SetupActivity {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val name = activity.getString(R.string.setup_lock)
        all(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == name && (it.parent as View).isClickable }
            .let { (it.parent as View).performClick() }
        return activity
    }

    @Test fun onlyPhonesWithASensorUnderTheScreenOfferIt() {
        assertFalse(texts(openLockPage().window.decorView).contains(context.getString(R.string.fingerprint_fit)))
        Settings.System.putString(context.contentResolver, "fod_animation_type", "14")
        assertTrue(texts(openLockPage().window.decorView).contains(context.getString(R.string.fingerprint_fit)))
    }

    @Test fun aTouchOnTheSensorFitsItAndOneHighUpDoesnt() {
        val activity = Robolectric.buildActivity(FingerprintFitActivity::class.java).setup().get()
        val height = app.chronotif.FingerprintSensor.screenHeight(activity)
        fun tap(y: Float) {
            activity.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 10f, y, 0).apply { setLocation(10f, y) })
        }
        tap(height * 0.2f)
        assertEquals("Too high: nothing saved", 0f, Prefs.fingerprintTop)
        assertTrue(texts(activity.window.decorView).contains(context.getString(R.string.fingerprint_fit_too_high)))
        tap(height * 0.92f)
        assertTrue("Fitted below the middle", Prefs.fingerprintTop in 0.5f..0.92f)
        assertTrue(texts(activity.window.decorView).contains(context.getString(R.string.fingerprint_fit_done)))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue("and closes", activity.isFinishing)
    }
}
