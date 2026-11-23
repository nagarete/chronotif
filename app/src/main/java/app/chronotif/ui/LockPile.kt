// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ViewOutlineProvider
import android.widget.ImageView
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
 *
 * Tapping the Pile's card opens that card in place, and the rest stays folded under it. An opened
 * card too tall for the room under the clock rests scrolled by the difference ([restAt]), so it
 * pushes the clock up as the unfold does, and scrolling back down shows its top.
 *
 * With a Media player that has artwork, the artwork rests large between the clock and the player,
 * and the Pile sits under the player (ADR 0012). Scrolling up shrinks it into the player's
 * thumbnail as the list moves, and a blurred copy stands in for the wallpaper behind everything.
 */
class LockPile(
    private val stage: FrameLayout,
    private val scroll: ScrollView,
    private val clockRoom: View,
    private val clockBox: View,
    private val listBox: LinearLayout,
    private val list: NotifList,
    private val backdrop: ImageView,
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

    /** The large artwork, drawn over the list's art room and shrunk into the player's thumbnail. */
    private val big = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
        pivotX = 0f
        pivotY = 0f
        elevation = 0f
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(clip, radius)
        }
        clipToOutline = true
    }
    /** The part of [big] that shows, in its own (unscaled) coordinates, and its corner radius. */
    private val clip = Rect()
    private var radius = 0f
    /** The artwork's size at rest, set while fitting the room. */
    private var artW = 0
    private var artH = 0
    private var backdropOf: Drawable? = null

    init {
        // Over the list, under the clock.
        stage.addView(big, 1, FrameLayout.LayoutParams(0, 0))
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

    /**
     * The scroll position the Pile rests at: 0, or how far an opened Pile card overflows the room
     * under the clock, so its bottom still rests where the Pile does.
     */
    var restAt = 0
        private set
    /** Set when [restAt] moves while resting, until the list has scrolled to it. */
    private var settling = false

    /** How far the Pile has unfolded: 0 resting, 1 open. */
    val progress: Float get() = ((scroll.scrollY - restAt) / unfold).coerceIn(0f, 1f)

    val resting: Boolean get() = scroll.scrollY <= restAt

    /** The scroll position at which the list is open with New at the top of the screen. */
    val openAt: Int
        get() = maxOf(restAt + unfold.toInt(), listBox.top + (list.spacer?.bottom ?: 0) - ctx.dp(8))

    /** Whether [ev] lands on the Pile's card while the Pile isn't open. */
    fun hitsCard(ev: MotionEvent): Boolean = progress < 1f && list.pile.any { on(it, ev) }

    /** Whether [ev] lands on the count under the Pile while it isn't open. */
    fun hitsCount(ev: MotionEvent): Boolean = progress < 1f && count.visibility == View.VISIBLE && on(count, ev)

    private fun on(v: View, ev: MotionEvent): Boolean {
        val r = Rect()
        return v.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    /**
     * The Pile's card, whose last row the rest fold under, and the rows after the clock's room. With
     * nothing New the card is empty, and everything folds where Earlier's first row is.
     */
    private fun folded(): Pair<List<View>, List<View>> {
        val sp = list.spacer ?: return emptyList<View>() to emptyList()
        val after = (listBox.indexOfChild(sp) + 1 until listBox.childCount).map { listBox.getChildAt(it) }
        return list.pile to after
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
        val (card, after) = folded()
        val top = card.lastOrNull()
        val first = top ?: after.firstOrNull()
        var gap = list.gap
        var art = 0
        val featured = list.featured?.takeIf { it.height > 0 }
        val artwork = featured?.artwork
        val sp = list.spacer
        if (featured != null && artwork != null && sp != null) {
            // The room left between the clock and what rests at the bottom: the Pile, or the
            // player itself when there's nothing to unfold. The artwork takes it, up to the width.
            val target = if (first == null) stage.height - ctx.dp(24) else stage.height - decoHeight() - ctx.dp(8)
            val at = listBox.top + when {
                first == null -> sp.top
                top != null -> first.bottom
                else -> first.top
            }
            val free = target - at + list.artRoom + (if (first == null) 0 else list.gap)
            val iw = artwork.intrinsicWidth.takeIf { it > 0 } ?: 1
            val ih = artwork.intrinsicHeight.takeIf { it > 0 } ?: 1
            val aspect = (iw.toFloat() / ih).coerceIn(1f, 2f)
            val maxW = listBox.width - listBox.paddingLeft - listBox.paddingRight - 2 * ctx.dp(ART_INSET_DP)
            val h = minOf((maxW / aspect).toInt(), free - ctx.dp(ART_ABOVE_DP + ART_BELOW_DP + 12))
            if (h >= ctx.dp(ART_MIN_DP)) {
                artH = h
                artW = (h * aspect).toInt()
                art = h + ctx.dp(ART_ABOVE_DP + ART_BELOW_DP)
                gap = if (first == null) 0 else free - art
            }
        }
        if (art == 0) {
            gap = if (first == null) 0 else {
                val target = stage.height - decoHeight() - ctx.dp(8)
                val at = listBox.top + (if (top != null) first.bottom else first.top)
                list.gap + list.artRoom + target - at
            }
        }
        // An opened card taller than the room rests scrolled by what doesn't fit.
        val rest = if (top != null && list.pileOpened) maxOf(0, -gap) else 0
        gap = maxOf(0, gap)
        if (rest != restAt) {
            if (resting) settling = true
            restAt = rest
            changed = true
        }
        if (art != list.artRoom) { list.artRoom = art; changed = true }
        if (gap != list.gap) { list.gap = gap; changed = true }
        // Scroll room for the unfold, even when the list is short.
        val bottom = listBox.top + listBox.height - listBox.paddingBottom
        // With artwork, room to scroll it all the way into the player, too.
        val reach = maxOf(if (first == null) 0 else restAt + unfold.toInt(), art)
        val need = maxOf(ctx.dp(16), stage.height + reach - bottom)
        if (listBox.paddingBottom != need) {
            listBox.setPadding(listBox.paddingLeft, listBox.paddingTop, listBox.paddingRight, need)
            changed = true
        }
        // Once laid out, move to the new resting place, once: the user may scroll from there.
        if (settling && !changed) {
            settling = false
            if (scroll.scrollY != restAt) {
                scroll.scrollTo(0, restAt)
                changed = true
            }
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
        val (card, after) = folded()
        val top = card.lastOrNull()
        val sy = scroll.scrollY
        val first = top ?: after.firstOrNull()
        moved.retainAll { it.parent === listBox }
        for (v in card) if (v in moved) {
            unfoldRow(v)
            moved -= v
        }
        if (p >= 1f) {
            moved.forEach(::unfoldRow)
            moved.clear()
        } else {
            val lift = -unfold * (1f - p)
            val below = top?.let { after.indexOf(it) } ?: -1
            val edge = top?.bottom ?: Int.MIN_VALUE
            for ((i, v) in after.withIndex()) {
                if (v in card) continue
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
            .filter { it !== list.spacer && it !== list.artRow && it !in moved && it.visibility == View.VISIBLE && it.height > 0 }
            .minOfOrNull { it.top + it.translationY.toInt() }.let { it ?: listBox.height }
        val push = minOf(0, edge - clockBox.bottom).toFloat()
        clockBox.translationY = push
        clockBox.alpha = (1f + push / maxOf(1, clockBox.height)).coerceIn(0f, 1f)

        artwork(sy)
    }

    /**
     * Draws the large artwork for scroll position [sy]: at rest in the art room, shrinking into the
     * player's thumbnail as the room scrolls away, gone once it's there. Keeps the backdrop current.
     */
    private fun artwork(sy: Int) {
        val featured = list.featured
        val drawable = featured?.artwork
        setBackdrop(drawable)
        val row = list.artRow
        val room = list.artRoom
        if (featured == null || drawable == null || row == null || room == 0 || artW == 0) {
            big.visibility = View.GONE
            featured?.feature(0f)
            return
        }
        val a = (sy / room.toFloat()).coerceIn(0f, 1f)
        if (a >= 1f) {
            big.visibility = View.GONE
            featured.feature(0f)
            return
        }
        if (big.drawable !== drawable) big.setImageDrawable(drawable)
        val lp = big.layoutParams
        if (lp.width != artW || lp.height != artH) {
            lp.width = artW
            lp.height = artH
            big.layoutParams = lp
        }
        big.visibility = View.VISIBLE
        featured.feature(1f - a)

        // From the room (moving with the list) to the thumbnail, eased.
        val e = a * a * (3f - 2f * a)
        val x0 = (stage.width - artW) / 2f
        val y0 = (listBox.top + row.top + ctx.dp(ART_ABOVE_DP) - sy).toFloat()
        val here = IntArray(2).also(stage::getLocationInWindow)
        val at = IntArray(2).also(featured.thumb::getLocationInWindow)
        val x1 = (at[0] - here[0]).toFloat()
        val y1 = (at[1] - here[1]).toFloat()
        val tw = featured.thumb.width.toFloat().takeIf { it > 0 } ?: ctx.dp(64).toFloat()
        val th = featured.thumb.height.toFloat().takeIf { it > 0 } ?: tw
        val x = x0 + (x1 - x0) * e
        val y = y0 + (y1 - y0) * e
        val w = artW + (tw - artW) * e
        val h = artH + (th - artH) * e
        // Scale evenly and crop to the shape in between, so the picture never stretches.
        val scale = maxOf(w / artW, h / artH)
        val cw = w / scale
        val ch = h / scale
        val cx = (artW - cw) / 2f
        val cy = (artH - ch) / 2f
        // Only touch the outline when it changes: invalidating it asks for another frame.
        val r = (ctx.dp(ART_RADIUS_DP) + (ctx.dp(14) - ctx.dp(ART_RADIUS_DP)) * e) / scale
        val l = cx.toInt()
        val t = cy.toInt()
        val rr = (cx + cw).toInt()
        val b = (cy + ch).toInt()
        if (clip.left != l || clip.top != t || clip.right != rr || clip.bottom != b || r != radius) {
            clip.set(l, t, rr, b)
            radius = r
            big.invalidateOutline()
        }
        big.scaleX = scale
        big.scaleY = scale
        big.translationX = x - cx * scale
        big.translationY = y - cy * scale
    }

    /**
     * A blurred copy of the artwork behind everything, in place of the wallpaper. It's drawn
     * a few pixels across and stretched, which blurs it on any Android; 12+ smooths it further.
     */
    private fun setBackdrop(d: Drawable?) {
        if (d === backdropOf) return
        backdropOf = d
        if (d == null) {
            backdrop.setImageDrawable(null)
            backdrop.visibility = View.GONE
            return
        }
        val small = Bitmap.createBitmap(BACKDROP_PX, BACKDROP_PX, Bitmap.Config.ARGB_8888)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: BACKDROP_PX
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: BACKDROP_PX
        // Cover the square, cropping the longer side.
        val k = maxOf(BACKDROP_PX.toFloat() / iw, BACKDROP_PX.toFloat() / ih)
        val dw = (iw * k).toInt()
        val dh = (ih * k).toInt()
        val old = d.copyBounds()
        d.setBounds((BACKDROP_PX - dw) / 2, (BACKDROP_PX - dh) / 2, (BACKDROP_PX + dw) / 2, (BACKDROP_PX + dh) / 2)
        // A hardware bitmap can't be drawn into a software canvas: no backdrop then.
        val drawn = runCatching { d.draw(Canvas(small)) }.isSuccess
        d.bounds = old
        if (!drawn) {
            backdrop.visibility = View.GONE
            return
        }
        backdrop.setImageBitmap(small)
        backdrop.setColorFilter(BACKDROP_DIM, PorterDuff.Mode.SRC_ATOP)
        if (Build.VERSION.SDK_INT >= 31) {
            val r = ctx.dp(24).toFloat()
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        }
        backdrop.visibility = View.VISIBLE
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

        /** The large artwork: space above and below it, side inset, smallest worth showing, corners. */
        const val ART_ABOVE_DP = 8
        const val ART_BELOW_DP = 16
        const val ART_INSET_DP = 16
        const val ART_MIN_DP = 120
        const val ART_RADIUS_DP = 22

        const val BACKDROP_PX = 12
        /** Darkens the backdrop so white text stays readable on bright artwork. */
        const val BACKDROP_DIM = 0x66000000
    }
}
