package app.chronotif.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs

/** Drawn stand-ins for avatars, photos and album art, so no real image is ever used. */
object Art {
    private val palette = intArrayOf(
        0xFFE57373.toInt(), 0xFF64B5F6.toInt(), 0xFF81C784.toInt(), 0xFFFFB74D.toInt(),
        0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt(), 0xFFF06292.toInt(), 0xFF7986CB.toInt(),
    )

    fun avatar(name: String, group: Boolean = false): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = palette[abs(name.hashCode()) % palette.size]
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), lighter(base), base, Shader.TileMode.CLAMP)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.shader = null
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val initials = if (group) name.take(1).uppercase()
        else name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
        p.textSize = if (initials.length > 1) 104f else 120f
        c.drawText(initials, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /** A flat-illustrated landscape; [name] picks the time of day. */
    fun photo(name: String): Bitmap {
        val w = 1200
        val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val (top, bottom, sun) = when (name) {
            "sunset" -> Triple(0xFF3B2A6B.toInt(), 0xFFF2995E.toInt(), 0xFFFFD27A.toInt())
            "morning" -> Triple(0xFF8EC9F0.toInt(), 0xFFE9F4FB.toInt(), 0xFFFFF1B8.toInt())
            else -> Triple(0xFF4E8FD1.toInt(), 0xFFBFE0F5.toInt(), 0xFFFFF4CC.toInt())
        }
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.7f, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.7f, h * 0.32f, 160f, sun, sun and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.7f, h * 0.32f, 160f, p)
        p.shader = null
        p.color = sun
        c.drawCircle(w * 0.7f, h * 0.32f, 70f, p)
        ridge(c, p, 0xFF5B6E8C.toInt(), h * 0.55f, 0.9f, 140f)
        ridge(c, p, 0xFF3E5470.toInt(), h * 0.66f, 1.7f, 90f)
        ridge(c, p, 0xFF253A52.toInt(), h * 0.78f, 2.6f, 60f)
        return bmp
    }

    fun artwork(): Bitmap {
        val s = 600
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), 0xFFFF7A59.toInt(), 0xFF6A3DE8.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 14f
        p.color = 0x55FFFFFF
        for (r in 1..6) c.drawCircle(s * 0.5f, s * 0.62f, r * 46f, p)
        p.style = Paint.Style.FILL
        p.color = 0xEEFFFFFF.toInt()
        c.drawCircle(s * 0.5f, s * 0.62f, 34f, p)
        return bmp
    }

    private fun ridge(c: Canvas, p: Paint, color: Int, base: Float, freq: Float, amp: Float) {
        val w = c.width.toFloat()
        val path = Path().apply {
            moveTo(0f, c.height.toFloat())
            var x = 0f
            while (x <= w) {
                val t = x / w * Math.PI * freq
                lineTo(x, base - (amp * (0.6 * Math.sin(t) + 0.4 * Math.sin(t * 2.3 + 1))).toFloat())
                x += 8f
            }
            lineTo(w, c.height.toFloat())
            close()
        }
        p.color = color
        c.drawPath(path, p)
    }

    private fun lighter(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= 0.7f
        hsv[2] = minOf(1f, hsv[2] * 1.15f)
        return Color.HSVToColor(hsv)
    }
}
