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

/** Settings → Lock screen → Fit to your fingerprint sensor, offered on every phone. */
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

    @Test fun everyPhoneOffersItAndSaysWhetherItsSensorIsKnownToBeUnderTheScreen() {
        // A Xiaomi 17T has its sensor under the screen but doesn't say so.
        texts(openLockPage().window.decorView).let {
            assertTrue(it.contains(context.getString(R.string.fingerprint_fit)))
            assertTrue(it.contains(context.getString(R.string.fingerprint_fit_desc_unknown)))
        }
        Settings.System.putString(context.contentResolver, "fod_animation_type", "14")
        assertTrue(texts(openLockPage().window.decorView).contains(context.getString(R.string.fingerprint_fit_desc)))
        Prefs.fingerprintTop = 0.86f
        assertTrue(texts(openLockPage().window.decorView).contains(context.getString(R.string.fingerprint_fit_desc_fitted)))
    }

    @Test fun aFitOnAPhoneThatDoesntSayItHasASensorThereCanBeTakenBack() {
        val remove = context.getString(R.string.fingerprint_fit_remove)
        assertFalse("Nothing to take back before a fit",
            texts(Robolectric.buildActivity(FingerprintFitActivity::class.java).setup().get().window.decorView).contains(remove))

        Prefs.fingerprintTop = 0.86f
        val activity = Robolectric.buildActivity(FingerprintFitActivity::class.java).setup().get()
        val button = all(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == remove }
        val at = IntArray(2).also { button.getLocationOnScreen(it) }
        val (x, y) = at[0] + button.width / 2f to at[1] + button.height / 2f
        activity.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        activity.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("The fit is gone, not moved to the button", 0f, Prefs.fingerprintTop)
        assertTrue(texts(activity.window.decorView).contains(context.getString(R.string.fingerprint_fit_removed)))

        // A phone that says its sensor is under the screen only fits again.
        Settings.System.putString(context.contentResolver, "fod_animation_type", "14")
        Prefs.fingerprintTop = 0.86f
        assertFalse(texts(Robolectric.buildActivity(FingerprintFitActivity::class.java).setup().get().window.decorView).contains(remove))
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
