// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.PanelTile
import app.chronotif.R
import app.chronotif.Tutorial

/**
 * The Tutorial's card: which step of which part, what to try, a mark once it was tried, then
 * Back and Continue. It sits on the tutorial screen, and at the bottom of the Panel while the
 * Panel is open, and follows [Tutorial] as the step changes. It's an ordinary view, not an
 * [Overlay], so the Panel's Previews, Search and Back work as they do without it.
 */
class TutorialGuide(
    context: android.content.Context,
    /** In the Panel the card is compact, and finishing closes the Panel. */
    private val inPanel: Boolean,
    private val onFinished: () -> Unit,
) : LinearLayout(context) {

    private val c = M3(context)
    private val counter = text(13f, c.textSecondary, bold = true)
    private val title = text(18f, c.text, bold = true)
    private val body = text(15f, c.text)
    private val tried = text(14f, c.good, bold = true).apply { setText(R.string.tutorial_tried) }
    private val tile = button(context.getString(R.string.tutorial_add_tile), filled = false) { addPanelTile() }
    private val tileHint = text(13f, c.textSecondary).apply { setText(R.string.tutorial_add_tile_manual) }
    private val back = button(context.getString(R.string.tutorial_back), filled = false) { Tutorial.back(context) }
    private val next = button("", filled = true) {
        val r = Tutorial.run
        if (r?.finished == true) onFinished() else Tutorial.next(context)
    }
    private val refresh: () -> Unit = { bind() }

    init {
        orientation = VERTICAL
        val pad = dp(if (inPanel) 18 else 22)
        setPadding(pad, pad, pad, dp(if (inPanel) 14 else 18))
        background = Ui.rounded(c.high, dp(28).toFloat())
        elevation = dp(6).toFloat()
        isClickable = true // Taps here never close the Panel behind it.
        addView(counter)
        addView(title, gap(6))
        addView(body, gap(6))
        addView(tried, gap(10))
        addView(tile, gap(14).apply { width = -2 })
        addView(tileHint, gap(10))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(back, LayoutParams(-2, -2))
            addView(next, LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }, gap(16))
        bind()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Tutorial.addListener(refresh)
        bind()
    }

    override fun onDetachedFromWindow() {
        Tutorial.removeListener(refresh)
        super.onDetachedFromWindow()
    }

    private fun bind() {
        val r = Tutorial.run ?: return
        val steps = r.steps
        val partName = context.getString(r.part.title)
        if (r.finished) {
            counter.text = partName
            title.setText(R.string.tutorial_finished_title)
            body.text = context.getString(R.string.tutorial_finished_body, partName)
            tried.visibility = GONE
            tile.visibility = GONE
            tileHint.visibility = GONE
            back.visibility = GONE
            next.setText(R.string.tutorial_done)
            return
        }
        val step = steps[r.step]
        counter.text = context.getString(R.string.tutorial_counter, partName, r.step + 1, steps.size)
        title.setText(step.title)
        body.text = step.body(context)
        tried.visibility = if (r.tried) VISIBLE else GONE
        // Asking Android to add a tile needs Android 13 and an activity in front: not the Panel.
        val offerTile = step.offersTile && !inPanel
        tile.visibility = if (offerTile && Build.VERSION.SDK_INT >= 33) VISIBLE else GONE
        tileHint.visibility = if (offerTile && Build.VERSION.SDK_INT < 33) VISIBLE else GONE
        back.visibility = if (r.step > 0) VISIBLE else INVISIBLE
        next.setText(if (r.step == steps.lastIndex) R.string.tutorial_finish else R.string.tutorial_continue)
    }

    private fun addPanelTile() {
        if (Build.VERSION.SDK_INT < 33) return
        context.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, PanelTile::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_tile),
            context.mainExecutor,
        ) {}
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = Ui.text(context, sp, color, bold)

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(20), 0, dp(20), 0)
        setTextColor(if (filled) c.onPrimary else c.primary)
        background = Ui.pressable(if (filled) c.primary else 0, dp(22).toFloat(), if (filled) 0x33FFFFFF else 0x22000000)
        setOnClickListener { onClick() }
    }

    private fun gap(top: Int) = LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
