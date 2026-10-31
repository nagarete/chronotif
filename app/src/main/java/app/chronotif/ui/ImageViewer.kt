// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.chronotif.ImageExport
import app.chronotif.Images
import app.chronotif.R
import app.chronotif.Viewable
import kotlin.math.abs

/**
 * Full-screen images on top of whatever window holds the card (panel or lock screen), so it needs
 * no unlock: pinch or double-tap to zoom, swipe sideways between a conversation's images, swipe
 * down or Back to close. Viewing isn't a check; nothing is opened in the app.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ImageViewer private constructor(
    context: Context,
    private val items: List<Viewable>,
    private var index: Int,
    private val actions: Actions,
) : FrameLayout(context) {

    interface Actions {
        /** Share needs another app on screen, so it may ask to unlock first. */
        fun share(start: () -> Unit)
        fun openInApp()
        /** Another app is on screen now; get out of the way. */
        fun launched()
    }

    companion object {
        fun show(anchor: View, items: List<Viewable>, start: Int, actions: Actions) {
            val root = anchor.rootView as? ViewGroup ?: return
            if (items.isEmpty() || find(root) != null) return
            val viewer = ImageViewer(anchor.context, items, start.coerceIn(items.indices), actions)
            root.addView(viewer, ViewGroup.LayoutParams(-1, -1))
            viewer.requestFocus()
        }

        /** Closes a viewer showing in [root]'s window; false if there was none. */
        fun dismissIn(root: View): Boolean {
            val v = find(root.rootView as? ViewGroup ?: return false) ?: return false
            v.close()
            return true
        }

        private fun find(root: ViewGroup): ImageViewer? =
            (0 until root.childCount).map(root::getChildAt).filterIsInstance<ImageViewer>().firstOrNull()
    }

    private val image = ZoomImageView(context)
    private val play = ImageView(context)
    private val counter = Ui.text(context, 15f, 0xFFFFFFFF.toInt(), bold = true, lines = 1)
    private val top = LinearLayout(context)
    private val bottom = LinearLayout(context)
    private val save = button(R.string.save)
    private val share = button(R.string.share)
    private val open = button(R.string.open_in_app)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var closing = false

    private val back = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { close() } else null

    init {
        setBackgroundColor(0xF5000000.toInt())
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        addView(image, LayoutParams(-1, -1))

        play.setImageResource(R.drawable.ic_media_play)
        play.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        play.background = Ui.rounded(0x80000000.toInt(), dp(36).toFloat())
        play.setPadding(dp(20), dp(20), dp(20), dp(20))
        play.contentDescription = context.getString(R.string.video)
        play.setOnClickListener { actions.openInApp() }
        addView(play, LayoutParams(dp(72), dp(72), Gravity.CENTER))

        top.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = Ui.pressable(0x33FFFFFF, dp(22).toFloat(), 0x33FFFFFF)
                contentDescription = context.getString(R.string.close)
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(counter, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        }
        addView(top, LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(8), dp(12), 0) })

        bottom.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(save, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(10) })
            addView(open, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(10) })
            addView(share, LinearLayout.LayoutParams(-2, dp(40)))
        }
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(20) })

        save.setOnClickListener {
            val item = items[index]
            save.isEnabled = false
            ImageExport.save(context, item) { ok ->
                save.isEnabled = true
                save.setText(if (ok) R.string.saved else R.string.save_failed)
                save.postDelayed({ save.setText(R.string.save) }, 1500)
            }
        }
        share.setOnClickListener {
            val item = items[index]
            actions.share {
                ImageExport.share(context, item) { intent ->
                    if (intent != null) {
                        context.startActivity(intent)
                        actions.launched()
                    }
                }
            }
        }
        open.setOnClickListener { actions.openInApp() }

        image.onTap = { toggleChrome() }
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        showItem()
    }

    private fun button(text: Int) = Ui.text(context, 15f, 0xFFFFFFFF.toInt(), bold = true, lines = 1).apply {
        setText(text)
        gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        background = Ui.pressable(0x33FFFFFF, dp(20).toFloat(), 0x33FFFFFF)
    }

    private fun showItem() {
        val item = items[index]
        image.reset()
        val a = item.attachment
        if (a != null) {
            Images.into(image, a, Images.VIEWER_PX) { image.reset() }
        } else {
            image.setImageDrawable(item.picture)
            image.reset()
        }
        counter.text = if (items.size > 1) context.getString(R.string.image_counter, index + 1, items.size) else ""
        // A video's still isn't the video: open it in the app rather than save or share a frame.
        play.visibility = if (item.isVideo) VISIBLE else GONE
        save.visibility = if (item.isVideo) GONE else VISIBLE
        share.visibility = if (item.isVideo) GONE else VISIBLE
        open.visibility = if (item.isVideo) VISIBLE else GONE
    }

    private fun toggleChrome() {
        val show = top.alpha < 1f
        for (v in listOf(top, bottom)) v.animate().alpha(if (show) 1f else 0f).setDuration(150).start()
    }

    fun close() {
        if (closing) return
        closing = true
        animate().alpha(0f).setDuration(150).withEndAction { (parent as? ViewGroup)?.removeView(this) }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alpha = 0f
        animate().alpha(1f).setDuration(150).start()
        requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back!!)
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back!!)
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // --- Paging and swipe-to-close, only while the image isn't zoomed ---

    private enum class Drag { NONE, SIDEWAYS, DOWN }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; drag = Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> if (!image.zoomed && ev.pointerCount == 1) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                drag = when {
                    abs(dx) > touchSlop && abs(dx) > abs(dy) && items.size > 1 -> Drag.SIDEWAYS
                    dy > touchSlop && dy > abs(dx) -> Drag.DOWN
                    else -> Drag.NONE
                }
            }
        }
        return drag != Drag.NONE
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> when (drag) {
                Drag.SIDEWAYS -> image.translationX = dx
                Drag.DOWN -> {
                    image.translationY = dy.coerceAtLeast(0f)
                    background.alpha = (255 * (1f - dy / height).coerceIn(0.2f, 1f)).toInt()
                }
                Drag.NONE -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (drag) {
                    Drag.SIDEWAYS -> {
                        val next = when {
                            dx < -width / 4f && index < items.size - 1 -> index + 1
                            dx > width / 4f && index > 0 -> index - 1
                            else -> index
                        }
                        if (next == index) {
                            image.animate().translationX(0f).setDuration(150).start()
                        } else {
                            val out = if (next > index) -width.toFloat() else width.toFloat()
                            image.animate().translationX(out).setDuration(150).withEndAction {
                                index = next
                                showItem()
                                image.translationX = -out
                                image.animate().translationX(0f).setDuration(150).start()
                            }.start()
                        }
                    }
                    Drag.DOWN -> if (dy > height / 6f) close() else {
                        image.animate().translationY(0f).setDuration(150).start()
                        background.alpha = 255
                    }
                    Drag.NONE -> {}
                }
                drag = Drag.NONE
            }
        }
        return true
    }
}

/** An image that fits the screen and can be pinched, double-tapped and panned. */
@SuppressLint("ClickableViewAccessibility", "AppCompatCustomView")
private class ZoomImageView(context: Context) : ImageView(context) {
    private val m = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f
    var onTap: () -> Unit = {}

    val zoomed get() = currentScale() > fitScale * 1.01f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (currentScale() * d.scaleFactor).coerceIn(fitScale, fitScale * 6f)
            val f = target / currentScale()
            m.postScale(f, f, d.focusX, d.focusY)
            settle()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed) reset() else {
                val f = 2.5f
                m.postScale(f, f, e.x, e.y)
                settle()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed) return false
            m.postTranslate(-dx, -dy)
            settle()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        // Keep the pan from being taken for a page swipe.
        if (zoomed) parent.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /** Fit and centre the current image. */
    fun reset() {
        val d = drawable
        if (d == null || width == 0 || d.intrinsicWidth <= 0) return
        fitScale = minOf(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        m.reset()
        m.postScale(fitScale, fitScale)
        m.postTranslate((width - d.intrinsicWidth * fitScale) / 2, (height - d.intrinsicHeight * fitScale) / 2)
        imageMatrix = m
    }

    private fun currentScale(): Float {
        m.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** Centre what's smaller than the screen; don't let what's larger pull away from the edges. */
    private fun settle() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        fun shift(start: Float, end: Float, size: Float) = when {
            end - start <= size -> (size - (end - start)) / 2 - start
            start > 0 -> -start
            end < size -> size - end
            else -> 0f
        }
        m.postTranslate(shift(r.left, r.right, width.toFloat()), shift(r.top, r.bottom, height.toFloat()))
        imageMatrix = m
    }
}
