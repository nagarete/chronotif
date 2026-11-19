// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import app.chronotif.Prefs
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchActivityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        Prefs.init(context)
    }

    private fun overlays(root: ViewGroup) = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<SearchOverlay>()

    @Test fun opensStraightIntoSearchAndFinishesWhenItCloses() {
        val controller = Robolectric.buildActivity(SearchActivity::class.java, SearchActivity.intent(context)).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView as ViewGroup
        val search = overlays(root).single()
        search.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue("Closing Search closes its window", activity.isFinishing)
        controller.destroy()
    }
}
