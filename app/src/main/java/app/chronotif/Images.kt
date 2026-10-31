// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import app.chronotif.Content.Attachment
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Attachments, decoded off the main thread and cached. They're the sending app's content URIs,
 * which the system lets a notification listener read while the notification is active.
 */
object Images {
    /** Cards and the viewer ask for different sizes; a card never needs the viewer's. */
    const val CARD_PX = 720
    const val VIEWER_PX = 2048

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Shows [a] in [v] once it's decoded; [done] gets false if it can't be read. */
    fun into(v: ImageView, a: Attachment, maxPx: Int, done: (Boolean) -> Unit = {}) {
        val key = "${a.uri}@$maxPx"
        v.tag = key
        cache.get(key)?.let {
            v.setImageBitmap(it)
            done(true)
            return
        }
        v.setImageDrawable(null)
        val resolver = v.context.contentResolver
        pool.execute {
            val bitmap = decode(resolver, a, maxPx)
            main.post {
                if (bitmap != null) cache.put(key, bitmap)
                if (v.tag != key) return@post
                v.setImageBitmap(bitmap)
                done(bitmap != null)
            }
        }
    }

    /** Off the main thread: the original bytes, for saving and sharing. */
    fun copy(context: Context, a: Attachment, out: java.io.OutputStream): Boolean = runCatching {
        context.contentResolver.openInputStream(a.uri)!!.use { it.copyTo(out) }
        true
    }.getOrDefault(false)

    private fun decode(resolver: ContentResolver, a: Attachment, maxPx: Int): Bitmap? {
        if (a.isVideo) {
            runCatching { resolver.loadThumbnail(a.uri, Size(maxPx, maxPx), null) }.getOrNull()?.let { return it }
        }
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, a.uri)) { decoder, info, _ ->
                val scale = min(1f, maxPx / max(info.size.width, info.size.height).toFloat())
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        }.getOrNull()
    }
}
