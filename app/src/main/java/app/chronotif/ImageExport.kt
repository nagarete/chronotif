// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** An image the viewer shows: a message's attachment, or a notification's picture. */
class Viewable(val attachment: Content.Attachment?, val picture: Drawable?) {
    val isVideo get() = attachment?.isVideo == true
    val mime get() = attachment?.mime?.takeIf { it.startsWith("image/") } ?: "image/png"
}

/** Saves to the gallery and hands images to other apps. Both copy, so nothing depends on the source staying readable. */
object ImageExport {
    private const val ALBUM = "Chronotif"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Into Pictures/Chronotif via MediaStore: visible in the gallery at once, no storage permission. */
    fun save(context: Context, v: Viewable, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName(v))
                put(MediaStore.Images.Media.MIME_TYPE, v.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val ok = runCatching {
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                val written = resolver.openOutputStream(uri)!!.use { write(app, v, it) }
                if (written) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } else {
                    resolver.delete(uri, null, null)
                }
                written
            }.getOrDefault(false)
            main.post { done(ok) }
        }
    }

    /** Copies into our cache and calls back with a share sheet for it, or null if it couldn't be read. */
    fun share(context: Context, v: Viewable, done: (Intent?) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val dir = ShareProvider.dir(app)
            val week = System.currentTimeMillis() - 7 * 24 * 3600_000L
            dir.listFiles()?.filter { it.lastModified() < week }?.forEach { it.delete() }
            val file = File(dir, fileName(v))
            val ok = runCatching { file.outputStream().use { write(app, v, it) } }.getOrDefault(false)
            val intent = if (!ok) null else {
                val uri = ShareProvider.uri(app, file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(v.mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(null, uri)
                Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            main.post { done(intent) }
        }
    }

    private fun write(context: Context, v: Viewable, out: OutputStream): Boolean {
        v.attachment?.let { return Images.copy(context, it, out) }
        val bitmap = v.picture?.let(::bitmapOf) ?: return false
        return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }

    private fun bitmapOf(d: Drawable): Bitmap? {
        (d as? BitmapDrawable)?.bitmap?.let { return it }
        if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return null
        return Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            d.setBounds(0, 0, c.width, c.height)
            d.draw(c)
        }
    }

    private fun fileName(v: Viewable): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(v.mime) ?: "png"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "Chronotif_$stamp.$ext"
    }
}

/** Serves images copied for sharing, read-only, to the app the user picked. */
class ShareProvider : ContentProvider() {
    companion object {
        fun dir(context: Context) = File(context.cacheDir, "shared").apply { mkdirs() }
        fun uri(context: Context, file: File): Uri =
            Uri.parse("content://${context.packageName}.share/${Uri.encode(file.name)}")
    }

    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val dir = dir(context!!)
        val f = File(dir, uri.lastPathSegment ?: return null)
        return f.takeIf { it.canonicalFile.parentFile == dir.canonicalFile && it.isFile }
    }

    override fun getType(uri: Uri): String? =
        file(uri)?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
