// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.R
import app.chronotif.Tutorial

/**
 * The Tutorial's card: which step of which part, what to try, a mark once it was tried, any extra
 * the step offers (adding a tile or the widget), then Back and Continue. It sits on the tutorial
 * screen, and at the bottom of the Panel while the Panel is open, and follows [Tutorial] as the
 * step changes. It's an ordinary view, not an [Overlay], so the Panel's Previews, Search and Back
 * work as they do without it.
 */
class TutorialGuide(
    context: android.content.Context,
    /** In the Panel the card is compact, has no extras, and finishing closes the Panel. */
    private val inPanel: Boolean,
    private val onFinished: () -> Unit,
) : LinearLayout(context) {

    private val c = M3(context)
    private val counter = text(13f, c.textSecondary, bold = true)
    private val title = text(18f, c.text, bold = true)
    private val body = text(15f, c.text)
    private val tried = text(14f, c.good, bold = true).apply { setText(R.string.tutorial_tried) }
    private val extras = LinearLayout(context).apply { orientation = VERTICAL }
    private val back = button("", filled = false) {
        val r = Tutorial.run
        if (r?.finished == true) onFinished() else Tutorial.back(context)
    }
    private val next = button("", filled = true) {
        val r = Tutorial.run ?: return@button
        val following = Tutorial.nextPart(r.part)
        when {
            !r.finished -> Tutorial.next(context)
            following != null -> Tutorial.start(context, following, fromStart = Tutorial.placeOf(following) == null)
            else -> onFinished()
        }
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
        addView(extras, gap(4))
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
        extras.removeAllViews()
        if (r.finished) {
            val following = Tutorial.nextPart(r.part)
            counter.text = partName
            title.setText(R.string.tutorial_finished_title)
            body.text = context.getString(R.string.tutorial_finished_body, partName)
            tried.visibility = GONE
            back.visibility = if (following != null) VISIBLE else GONE
            back.setText(R.string.tutorial_done)
            next.text = following?.let { context.getString(R.string.tutorial_next_part, context.getString(it.title)) }
                ?: context.getString(R.string.tutorial_done)
            return
        }
        val step = steps[r.step]
        counter.text = context.getString(R.string.tutorial_counter, partName, r.step + 1, steps.size)
        title.setText(step.title)
        body.text = step.body(context)
        tried.visibility = if (r.tried) VISIBLE else GONE
        // Asking Android or the home screen for something needs an activity in front: not the Panel.
        if (!inPanel) step.extras.forEach { extra ->
            if (extra.available(context)) {
                val label = context.getString(extra.label).let { if (extra.picked?.invoke(context) == true) "✓  $it" else it }
                extras.addView(button(label, filled = false) { extra.act(context) }, gap(10).apply { width = -2 })
            } else if (extra.manual != null) {
                extras.addView(text(13f, c.textSecondary).apply { setText(extra.manual) }, gap(10))
            }
        }
        extras.visibility = if (extras.childCount > 0) VISIBLE else GONE
        back.visibility = if (r.step > 0) VISIBLE else INVISIBLE
        back.setText(R.string.tutorial_back)
        next.setText(if (r.step == steps.lastIndex) R.string.tutorial_finish else R.string.tutorial_continue)
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
