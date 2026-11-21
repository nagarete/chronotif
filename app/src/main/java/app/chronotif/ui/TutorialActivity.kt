// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import app.chronotif.R
import app.chronotif.Tutorial

/**
 * The tutorial screen: the current step's card, for the steps that happen outside the Panel
 * (opening it), and where a part ends. While it's shown, a [Tutorial] run is on and the Panel
 * opens with the samples; leaving it with the Panel closed ends the run.
 */
class TutorialActivity : Activity() {

    companion object {
        private const val EXTRA_PART = "part"
        private const val EXTRA_FROM_START = "from_start"

        fun intent(context: Context, part: Tutorial.Part, fromStart: Boolean = false): Intent =
            Intent(context, TutorialActivity::class.java)
                .putExtra(EXTRA_PART, part.name)
                .putExtra(EXTRA_FROM_START, fromStart)
    }

    /** The part on screen: the one asked for, then whichever the run moves on to. */
    private var part = Tutorial.Part.NOTIFICATIONS
    private val follow: () -> Unit = { Tutorial.run?.part?.let { part = it } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        val c = M3(this)
        window.decorView.setBackgroundColor(c.surface)
        part = intent.getStringExtra(EXTRA_PART)?.let { runCatching { Tutorial.Part.valueOf(it) }.getOrNull() } ?: part
        Tutorial.start(this, part, fromStart = savedInstanceState == null && intent.getBooleanExtra(EXTRA_FROM_START, false))
        Tutorial.addListener(follow)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            addView(Ui.text(context, 28f, c.text, bold = true).apply { setText(R.string.setup_tutorial) }, Ui.lp())
            addView(Ui.text(context, 15f, c.textSecondary).apply { setText(R.string.tutorial_screen_intro) },
                Ui.lp().apply { topMargin = dp(8) })
            addView(TutorialGuide(context, inPanel = false) { finish() }, Ui.lp().apply { topMargin = dp(28) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        // Back after the run ended (the Panel closed while this screen was away): carry on where it was.
        if (Tutorial.run == null) Tutorial.start(this, part)
        Tutorial.shown(Tutorial.Surface.SCREEN, true)
    }

    override fun onResume() {
        super.onResume()
        Tutorial.recheck(this)
    }

    override fun onDestroy() {
        Tutorial.removeListener(follow)
        super.onDestroy()
    }

    override fun onStop() {
        Tutorial.shown(Tutorial.Surface.SCREEN, false)
        super.onStop()
    }
}
