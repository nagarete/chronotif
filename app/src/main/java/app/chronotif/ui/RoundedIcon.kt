// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/**
 * An app icon in one shape on every phone: a rounded square, like the icons on notification
 * cards. An adaptive icon is drawn from its own layers instead of the system's mask, which some
 * ROMs (MIUI on Android 12) make a plain square.
 */
class RoundedIcon(private val icon: Drawable) : Drawable() {
    companion object {
        /** Corner radius as a share of the icon's size. */
        const val CORNER = 0.23f
        /** An adaptive icon's layers are 108 units, of which the middle 72 show. */
        private const val LAYER_OVERHANG = 18f / 72f
    }

    private val clip = Path()

    override fun onBoundsChange(bounds: Rect) {
        clip.reset()
        val r = bounds.width() * CORNER
        clip.addRoundRect(RectF(bounds), r, r, Path.Direction.CW)
        val adaptive = icon as? AdaptiveIconDrawable
        if (adaptive == null) {
            icon.bounds = bounds
            return
        }
        val dx = (bounds.width() * LAYER_OVERHANG).toInt()
        val dy = (bounds.height() * LAYER_OVERHANG).toInt()
        val layers = Rect(bounds.left - dx, bounds.top - dy, bounds.right + dx, bounds.bottom + dy)
        adaptive.background?.bounds = layers
        adaptive.foreground?.bounds = layers
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clip)
        val adaptive = icon as? AdaptiveIconDrawable
        if (adaptive == null) {
            icon.draw(canvas)
        } else {
            adaptive.background?.draw(canvas)
            adaptive.foreground?.draw(canvas)
        }
        canvas.restoreToCount(save)
    }

    override fun getIntrinsicWidth() = icon.intrinsicWidth
    override fun getIntrinsicHeight() = icon.intrinsicHeight
    override fun setAlpha(alpha: Int) { icon.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
