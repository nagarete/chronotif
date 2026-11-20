// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.chronotif.R

/**
 * The lock-screen view's Pile (ADR 0011), drawn from the list itself rather than in place of it.
 * The whole list is always laid out, and the Pile is how it looks scrolled to the top: New's first
 * card sits at the bottom, everything after it is folded under it, and slivers and a count stand
 * in for what's folded. Scrolling up unfolds the rest under the finger over [UNFOLD_DP], so
 * nothing is rebuilt mid-gesture. The clock stays put until the list reaches it, then is pushed
 * up and fades.
 */
class LockPile(
    private val stage: FrameLayout,
    private val scroll: ScrollView,
    private val clockRoom: View,
    private val clockBox: View,
    private val listBox: LinearLayout,
    private val list: NotifList,
    private val open: () -> Unit,
) {
    private val ctx = stage.context
    private val pal = Palette(ctx)
    private val unfold = ctx.dp(UNFOLD_DP).toFloat()
    private val sliver1 = sliver(pal.sliver1, 14)
    private val sliver2 = sliver(pal.sliver2, 12)
    private val count = Ui.text(ctx, 13f, pal.onScrim, bold = true, lines = 1).apply {
        gravity = Gravity.CENTER
        background = Ui.pressable(pal.pill, ctx.dp(15).toFloat(), 0x33FFFFFF)
        setPadding(ctx.dp(12), ctx.dp(7), ctx.dp(12), ctx.dp(7))
        setOnClickListener { open() }
    }
    /** Holds the slivers and the count above the list, following the Pile's card as it scrolls. */
    private val deco = FrameLayout(ctx).apply {
        addView(sliver2, FrameLayout.LayoutParams(-1, ctx.dp(7)))
        addView(sliver1, FrameLayout.LayoutParams(-1, ctx.dp(7)))
        addView(count, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
    }

    init {
        stage.addView(deco, FrameLayout.LayoutParams(-1, -1))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> apply() }
        // Size the clock's room and the gap before each frame that follows a change in the list.
        stage.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            var tries = 0

            override fun onPreDraw(): Boolean {
                if (fit() && tries++ < 3) return false
                tries = 0
                apply()
                return true
            }
        })
    }

    /** How far the Pile has unfolded: 0 resting, 1 open. */
    val progress: Float get() = (scroll.scrollY / unfold).coerceIn(0f, 1f)

    val resting: Boolean get() = scroll.scrollY == 0

    /** The scroll position at which the list is open with New at the top of the screen. */
    val openAt: Int
        get() = maxOf(unfold.toInt(), listBox.top + (list.spacer?.bottom ?: 0) - ctx.dp(8))

    /** Whether [ev] lands on the Pile's card or its count while the Pile isn't open. */
    fun hits(ev: MotionEvent): Boolean {
        if (progress >= 1f) return false
        return listOfNotNull(list.pileTop, count.takeIf { it.visibility == View.VISIBLE }).any {
            val r = Rect()
            it.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())
        }
    }

    /** The first card the rest fold under: New's newest, or with nothing New, Earlier's first row. */
    private fun folded(): Pair<View?, List<View>> {
        val sp = list.spacer ?: return null to emptyList()
        val after = (listBox.indexOfChild(sp) + 1 until listBox.childCount).map { listBox.getChildAt(it) }
        return list.pileTop to after
    }

    /**
     * Makes room for the clock above the list, puts the Pile's card at the bottom of the screen
     * when scrolled to the top, and keeps enough below for the unfold. Returns true if anything
     * changed, so the frame is laid out again before it's drawn.
     */
    private fun fit(): Boolean {
        if (stage.height == 0) return false
        var changed = false
        val room = clockBox.height
        if (clockRoom.layoutParams.height != room) {
            clockRoom.layoutParams = clockRoom.layoutParams.apply { height = room }
            changed = true
        }
        val (top, after) = folded()
        val first = top ?: after.firstOrNull()
        if (first == null) {
            if (list.gap != 0) { list.gap = 0; changed = true }
        } else {
            val below = decoHeight() + ctx.dp(8)
            val target = stage.height - below
            val at = listBox.top + (if (top != null) first.bottom else first.top)
            val gap = maxOf(0, list.gap + target - at)
            if (gap != list.gap) { list.gap = gap; changed = true }
        }
        // Scroll room for the unfold, even when the list is short.
        val bottom = listBox.top + listBox.height - listBox.paddingBottom
        val need = if (first == null) ctx.dp(16) else maxOf(ctx.dp(16), stage.height + unfold.toInt() - bottom)
        if (listBox.paddingBottom != need) {
            listBox.setPadding(listBox.paddingLeft, listBox.paddingTop, listBox.paddingRight, need)
            changed = true
        }
        return changed
    }

    private fun decoHeight(): Int {
        val n = list.newPieces
        var h = when {
            n > 2 -> ctx.dp(14)
            n > 1 -> ctx.dp(7)
            else -> 0
        }
        if (countLabel() != null) {
            if (count.height == 0) count.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            h += ctx.dp(10) + maxOf(count.height, count.measuredHeight)
        }
        return h
    }

    private fun countLabel(): String? = when {
        list.newPieces > 1 -> ctx.getString(R.string.more_in_stack, list.newPieces - 1)
        list.newPieces == 0 && list.earlierPieces > 0 -> ctx.getString(R.string.earlier_count, list.earlierPieces)
        else -> null
    }

    /** Rows moved or faded by the fold, to put back once the Pile is open. */
    private val moved = HashSet<View>()

    private fun unfoldRow(v: View) {
        v.translationY = 0f
        v.alpha = 1f
        v.clipBounds = null
        v.visibility = View.VISIBLE
    }

    /**
     * Folds and unfolds the list for the current scroll, and moves the clock and the slivers with
     * it. Run before every frame: rows can arrive over several frames, and each must start folded.
     *
     * Folded rows sit [unfold] higher than their place, so while the list scrolls through the
     * unfold they hold still on screen and the Pile's card slides up off them, uncovering them.
     * Whatever is still under the card is clipped, since the cards are translucent.
     */
    fun apply() {
        val p = progress
        val (top, after) = folded()
        val sy = scroll.scrollY
        val first = top ?: after.firstOrNull()
        moved.retainAll { it.parent === listBox }
        if (top != null && top in moved) {
            unfoldRow(top)
            moved -= top
        }
        if (p >= 1f) {
            moved.forEach(::unfoldRow)
            moved.clear()
        } else {
            val lift = -unfold * (1f - p)
            val below = top?.let { after.indexOf(it) } ?: -1
            val edge = top?.bottom ?: Int.MIN_VALUE
            for ((i, v) in after.withIndex()) {
                if (v === top) continue
                moved += v
                v.translationY = lift
                v.alpha = p
                v.visibility = if (p == 0f) View.INVISIBLE else View.VISIBLE
                val hidden = edge - (v.top + lift.toInt())
                v.clipBounds = if (i > below && hidden > 0) Rect(0, minOf(hidden, v.height), v.width, v.height) else null
            }
        }

        // Slivers and the count, under the Pile's card (or where Earlier folds), fading as it unfolds.
        val label = countLabel()
        if (first == null || p >= 1f || (label == null && list.newPieces < 2)) {
            deco.visibility = View.GONE
        } else {
            deco.visibility = View.VISIBLE
            deco.alpha = 1f - p
            val base = listBox.top + (if (top != null) first.bottom else first.top) - sy
            val left = listBox.left + listBox.paddingLeft
            val width = listBox.width - listBox.paddingLeft - listBox.paddingRight
            fun place(v: View, inset: Int, y: Int, show: Boolean) {
                v.visibility = if (show) View.VISIBLE else View.GONE
                val lp = v.layoutParams as FrameLayout.LayoutParams
                if (lp.leftMargin != left + inset || lp.width != width - 2 * inset) {
                    lp.leftMargin = left + inset
                    lp.width = width - 2 * inset
                    v.layoutParams = lp
                }
                v.translationY = y.toFloat()
            }
            val n = if (top != null) list.newPieces else 0
            place(sliver1, ctx.dp(14), base, n > 1)
            place(sliver2, ctx.dp(30), base + ctx.dp(7), n > 2)
            val slivers = if (n > 2) ctx.dp(14) else if (n > 1) ctx.dp(7) else 0
            count.visibility = if (label != null) View.VISIBLE else View.GONE
            if (label != null && count.text != label) count.text = label
            count.translationY = (base + slivers + ctx.dp(10)).toFloat()
        }

        // The clock stays until the list's top edge reaches it, then is pushed up and fades.
        // Rows still folded don't count: they only fade in where they'll be.
        val edge = listBox.top - sy + (0 until listBox.childCount).map { listBox.getChildAt(it) }
            .filter { it !== list.spacer && it !in moved && it.visibility == View.VISIBLE && it.height > 0 }
            .minOfOrNull { it.top + it.translationY.toInt() }.let { it ?: listBox.height }
        val push = minOf(0, edge - clockBox.bottom).toFloat()
        clockBox.translationY = push
        clockBox.alpha = (1f + push / maxOf(1, clockBox.height)).coerceIn(0f, 1f)
    }

    private fun sliver(color: Int, r: Int) = View(ctx).apply {
        background = GradientDrawable().apply {
            setColor(color)
            val rr = ctx.dp(r).toFloat()
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, rr, rr, rr, rr)
        }
    }

    private companion object {
        /** How far the list scrolls while the Pile unfolds. */
        const val UNFOLD_DP = 140
    }
}
