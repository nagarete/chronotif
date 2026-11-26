// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import app.chronotif.core.AppMatch
import java.util.concurrent.Executors

class FoundFile(
    val uri: Uri,
    val name: String,
    val mime: String?,
    val kind: FoundFile.Kind,
    /** Seconds since the epoch, as Android's file index keeps it. */
    val modified: Long,
    /** The folder it's in, such as "Download" or "Music/Album". */
    val folder: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
) {
    enum class Kind { IMAGE, VIDEO, AUDIO, DOCUMENT }
}

/**
 * Search's Files (ADR 0023): looked up by name in Android's own file index when a query is typed,
 * never copied or kept. A build that declares All files access finds documents too and asks for
 * that; one that doesn't (the Play build) finds what Android's media permissions allow.
 */
object Files {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val thumbnails = LruCache<Uri, Drawable>(32)

    /** The most files one query finds; three show before Show more. */
    const val MAX = 20
    const val SHOWN = 3

    @Volatile private var declared: Set<String>? = null

    /** The permissions this build declares, read once: the Play build leaves All files access out. */
    private fun declared(ctx: Context): Set<String> = declared ?: runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet()).also { declared = it }

    /** Whether this build finds documents, through All files access. */
    fun allFilesBuild(ctx: Context) = Manifest.permission.MANAGE_EXTERNAL_STORAGE in declared(ctx)

    /** The media permissions this build can ask for, on this Android version. */
    fun mediaPermissions(ctx: Context): Array<String> {
        val wanted = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val have = declared(ctx)
        return wanted.filter { it in have }.toTypedArray()
    }

    /** Whether Android lets Search read files: all of them, or at least some media. */
    fun allowed(ctx: Context): Boolean {
        if (allFilesBuild(ctx)) return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        // Android 14's "Select photos" grants only this, and the index then shows just those.
        val partial = if (Build.VERSION.SDK_INT >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyList()
        return (mediaPermissions(ctx).toList() + partial).any {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun on(ctx: Context) = Prefs.searchFiles && allowed(ctx)

    /** Android's page for All files access, where the user allows it for Chronotif. */
    fun allFilesPage(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    /**
     * Files whose name, or a song's title, artist or album, holds every word of [query], newest
     * first. Blocking: Search calls it off the main thread. Android's index matches case but not
     * accents, so "cafe" misses "Café" here, unlike apps and contacts.
     */
    fun search(ctx: Context, query: String): List<FoundFile> {
        if (!on(ctx)) return emptyList()
        val words = AppMatch.fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val oneWord = "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\' OR ${MediaStore.MediaColumns.TITLE} LIKE ? ESCAPE '\\'" +
            " OR ${MediaStore.MediaColumns.ARTIST} LIKE ? ESCAPE '\\' OR ${MediaStore.MediaColumns.ALBUM} LIKE ? ESCAPE '\\')"
        // Folders have no MIME type; hidden files and folders (".thumbnails") aren't what anyone looks for.
        val selection = (words.map { oneWord } + listOf(
            "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL",
            "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE '.%'",
            "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE '.%'",
            "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE '%/.%'",
        )).joinToString(" AND ")
        val args = words.flatMap { w -> List(4) { "%" + like(w) + "%" } }.toTypedArray()
        val extras = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX)
        }
        val out = ArrayList<FoundFile>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), PROJECTION, extras, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val kind = when (c.getInt(3)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> FoundFile.Kind.IMAGE
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> FoundFile.Kind.VIDEO
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> FoundFile.Kind.AUDIO
                        else -> FoundFile.Kind.DOCUMENT
                    }
                    out += FoundFile(
                        uri = ContentUris.withAppendedId(collection(kind), id),
                        name = name,
                        mime = c.getString(2),
                        kind = kind,
                        modified = c.getLong(4),
                        folder = c.getString(5)?.trimEnd('/')?.takeIf { it.isNotEmpty() },
                        title = c.getString(6)?.takeIf { it.isNotBlank() },
                        artist = c.getString(7)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING },
                        album = c.getString(8)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING },
                    )
                }
            }
        }
        return out
    }

    /** Media opens through its own collection, which more apps take than the general file one. */
    private fun collection(kind: FoundFile.Kind): Uri = when (kind) {
        FoundFile.Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        FoundFile.Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        FoundFile.Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        FoundFile.Kind.DOCUMENT -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }

    private fun like(w: String) = w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** A photo's or video's thumbnail, or a song's cover, delivered on the main thread; nothing for a document or on failure. */
    fun thumbnail(context: Context, f: FoundFile, px: Int, done: (Drawable) -> Unit) {
        if (f.kind == FoundFile.Kind.DOCUMENT) return
        thumbnails.get(f.uri)?.let { return done(it) }
        val app = context.applicationContext
        io.execute {
            val bitmap = runCatching { app.contentResolver.loadThumbnail(f.uri, Size(px, px), null) }.getOrNull() ?: return@execute
            val d = BitmapDrawable(app.resources, bitmap)
            main.post {
                thumbnails.put(f.uri, d)
                done(d)
            }
        }
    }

    /** Opens [f] in the app that handles its type, which gets to read just this file. */
    fun open(context: Context, f: FoundFile): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(f.uri, f.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    private val PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.TITLE,
        MediaStore.MediaColumns.ARTIST,
        MediaStore.MediaColumns.ALBUM,
    )
}
