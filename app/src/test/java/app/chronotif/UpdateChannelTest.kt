// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateChannelTest {
    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
    }

    @Test
    fun freshInstallUsesBuildChannelAndExplicitChoicePersists() {
        assertEquals(BuildConfig.NIGHTLY, Prefs.nightlyUpdates)
        Prefs.nightlyUpdates = !BuildConfig.NIGHTLY
        Prefs.init(RuntimeEnvironment.getApplication())
        assertEquals(!BuildConfig.NIGHTLY, Prefs.nightlyUpdates)
    }

    @Test
    fun stableIgnoresNightlyTags() {
        val tags = listOf("nightly-0.2.4-100", "v0.2.3")
        assertEquals("0.2.3", Updater.selectRelease(tags, nightly = false)?.version)
    }

    @Test
    fun nightlySelectsNewestBuildWithoutCrossingToStable() {
        val tags = listOf("v0.3.0", "nightly-0.2.3-9", "nightly-0.2.3-10")
        assertEquals("0.2.3-nightly.10", Updater.selectRelease(tags, nightly = true)?.version)
    }

    @Test
    fun downloadUrlsFollowTheTag() {
        val selected = Updater.selectRelease(listOf("nightly-0.2.5-172"), nightly = true)!!
        val apk = "https://github.com/nagarete/chronotif/releases/download/nightly-0.2.5-172/chronotif-nightly-0.2.5-172.apk"
        assertEquals(apk, selected.apkUrl)
        assertEquals("$apk.sha256", selected.sumUrl)
        assertNull(Updater.selectRelease(listOf("nightly-broken", "v0.2.3-rc1"), nightly = true))
    }

    @Test
    fun feedTagsComeFromEntryLinksNotReleaseNotes() {
        val atom = """
            <feed>
              <link type="text/html" rel="alternate" href="https://github.com/nagarete/chronotif/releases"/>
              <entry>
                <link rel="alternate" type="text/html" href="https://github.com/nagarete/chronotif/releases/tag/nightly-0.2.5-172"/>
                <content type="html">&lt;a href=&quot;https://github.com/nagarete/chronotif/releases/tag/v9.9.9&quot;&gt;</content>
              </entry>
              <entry>
                <link rel="alternate" type="text/html" href="https://github.com/nagarete/chronotif/releases/tag/v0.2.4"/>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals(listOf("nightly-0.2.5-172", "v0.2.4"), Updater.feedTags(atom))
    }
}
