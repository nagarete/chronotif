// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.Process
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TextAppearanceSpan
import android.util.LruCache
import app.chronotif.core.Kind
import app.chronotif.core.MessageStamp

/** What a card displays, extracted from a notification's extras (no foreign RemoteViews). */
class Content(
    val appName: CharSequence,
    val title: CharSequence,
    val text: CharSequence?,
    /** Full text for the expanded card (big text style), if different from [text]. */
    val bigText: CharSequence?,
    /** Inbox lines or recent messages. */
    val lines: List<CharSequence>,
    /** When each of [lines] was sent, for conversations; empty otherwise. */
    val lineTimes: List<Long>,
    val subText: CharSequence?,
    val progress: Progress?,
    val redacted: Boolean,
    /** The image or video still sent with each of [lines], if any; empty for non-conversations. */
    val attachments: List<Attachment?> = emptyList(),
    /** A conversation's messages, oldest first, one per [lines] entry; empty when it has none. */
    val messages: List<Msg> = emptyList(),
    /** Every attachment in the conversation, for the viewer to page through. */
    val gallery: List<Attachment> = attachments.filterNotNull(),
) {
    class Progress(val value: Int, val max: Int, val indeterminate: Boolean)

    /** One message of a conversation; [id] stays the same when the app reposts the notification. */
    class Msg(val id: String, val line: CharSequence, val time: Long, val attachment: Attachment?, val incoming: Boolean)

    /** What a single message's card shows: the conversation's title over just that message. */
    fun forMessage(m: Msg) = Content(
        appName = appName, title = title, text = m.line, bigText = null,
        lines = listOf(m.line), lineTimes = listOf(m.time), subText = subText, progress = null,
        redacted = redacted, attachments = listOf(m.attachment), messages = listOf(m), gallery = gallery,
    )

    /** An image, or a video's still frame, sent inside a message. */
    class Attachment(val uri: Uri, val mime: String) {
        val isVideo get() = mime.startsWith("video/")
    }

    val expandable: Boolean
        get() = !redacted && (bigText != null || lines.size > 1 || (text?.length ?: 0) > 80)

    /** The messages sent after [since] (at least the latest), oldest first; the collapsed New card shows them. */
    fun messagesSince(since: Long): List<CharSequence> {
        if (lineTimes.size != lines.size || lines.isEmpty()) return emptyList()
        return lines.takeLast(lineTimes.count { it > since }.coerceAtLeast(1))
    }

    companion object {
        /** Where MessagingStyle keeps a conversation icon; the key is hidden and rarely sent by apps. */
        private const val EXTRA_CONVERSATION_ICON = "android.conversationIcon"

        fun of(context: Context, e: Entry, redact: Boolean): Content {
            val appName = Apps.label(context, e.pkg)
            if (redact) {
                val pub = e.n.publicVersion?.extras
                return Content(
                    appName = appName,
                    title = pub?.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let(::uncolored)
                        ?: appName,
                    text = pub?.getCharSequence(Notification.EXTRA_TEXT)?.let(::uncolored)
                        ?: context.getString(R.string.hidden_content),
                    bigText = null, lines = emptyList(), lineTimes = emptyList(), subText = null, progress = null,
                    redacted = true,
                )
            }
            val x = e.n.extras
            var title = x.getCharSequence(Notification.EXTRA_TITLE)
                ?: x.getCharSequence(Notification.EXTRA_TITLE_BIG)
            var text = x.getCharSequence(Notification.EXTRA_TEXT)
            val big = x.getCharSequence(Notification.EXTRA_BIG_TEXT)
            var lines: List<CharSequence> =
                x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.filterNotNull().orEmpty()
            var lineTimes = emptyList<Long>()
            var attachments = emptyList<Attachment?>()
            var msgs = emptyList<Msg>()

            val messages = (messages(x, Notification.EXTRA_HISTORIC_MESSAGES) + messages(x))
                .distinctBy { Triple(it.time, it.senderKey ?: it.sender?.toString(), it.body.toString()) }
                .sortedBy { it.time }
            if (messages.isNotEmpty()) {
                val conversation = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                val group = isGroup(x)
                // Like the system shade: the conversation's shortcut names it best. TikTok, for one,
                // titles its DMs "sent you a message" and puts the sender only in its shortcut.
                val shortcut = if (Build.VERSION.SDK_INT >= 31) {
                    e.ranking.conversationShortcutInfo?.let { it.longLabel ?: it.shortLabel }
                } else null
                title = shortcut?.takeIf { it.isNotBlank() } ?: conversation ?: title
                lines = messages.map { message ->
                    if (group && message.sender != null) "${message.sender}: ${message.body}" else message.body
                }
                lineTimes = messages.map { it.time }
                attachments = messages.map { it.attachment }
                val user = user(x)
                msgs = messages.mapIndexed { i, m ->
                    Msg(
                        id = idOf(m),
                        line = uncolored(lines[i]), time = m.time, attachment = m.attachment,
                        incoming = !isOwn(m, user),
                    )
                }
                text = lines.last()
            }

            val progressMax = x.getInt(Notification.EXTRA_PROGRESS_MAX)
            val indeterminate = x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
            val progress = if (progressMax > 0 || indeterminate) {
                Content.Progress(x.getInt(Notification.EXTRA_PROGRESS), progressMax, indeterminate)
            } else null

            return Content(
                appName = appName,
                title = title?.takeIf { it.isNotBlank() }?.let(::uncolored) ?: appName,
                text = text?.takeIf { it.isNotBlank() }?.let(::uncolored),
                bigText = big?.takeIf { it.isNotBlank() && it.toString() != text?.toString() }?.let(::uncolored),
                lines = lines.map(::uncolored),
                lineTimes = lineTimes,
                subText = (x.getCharSequence(Notification.EXTRA_SUB_TEXT)
                    ?: x.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))?.let(::uncolored),
                progress = progress,
                redacted = false,
                attachments = attachments,
                messages = msgs,
            )
        }

        /** Changes when the visible content changes; used to tell real arrivals from refreshes. */
        fun signature(n: Notification): Int {
            val x = n.extras
            val last = messages(x).lastOrNull()
            return listOf(
                x.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                x.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() },
                last?.body?.toString(),
                last?.time,
            ).hashCode()
        }

        /** Media players stay put while paused, when apps drop the ongoing flag. */
        fun kind(sbn: StatusBarNotification): Kind {
            val n = sbn.notification
            val ongoing = sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            val template = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
            return when {
                isMedia(n) -> Kind.MEDIA
                template.contains("CallStyle") || (ongoing && n.category == Notification.CATEGORY_CALL) -> Kind.CALL
                ongoing -> Kind.RUNNING
                else -> Kind.NOTIFICATION
            }
        }

        fun isMedia(n: Notification): Boolean =
            n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true ||
                n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

        fun isConversation(n: Notification): Boolean =
            n.category == Notification.CATEGORY_MESSAGE || n.extras.containsKey(Notification.EXTRA_MESSAGES)

        /** Only active incoming messages advance attention; historic and outgoing messages don't. */
        fun incomingMessage(n: Notification): MessageStamp? {
            val x = n.extras
            if (x.containsKey(Notification.EXTRA_MESSAGES)) {
                val last = latestIncoming(x) ?: return null
                return MessageStamp(last.time, listOf(last.senderKey, last.sender?.toString(), last.body.toString()).hashCode())
            }
            if (!isConversation(n)) return null
            // Some chat apps supply only plain text. With no message timestamp, compare content.
            val body = x.getCharSequence(Notification.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return null
            return MessageStamp(0, listOf(x.getCharSequence(Notification.EXTRA_TITLE)?.toString(), body.toString()).hashCode())
        }

        /**
         * Drops the app's text colours but keeps bold/italic, as the system shade does: apps colour
         * for their own background (Gmail greys its snippet), which can vanish on our cards.
         */
        private fun uncolored(cs: CharSequence): CharSequence {
            if (cs !is Spanned) return cs
            val out = SpannableString(cs)
            for (span in out.getSpans(0, out.length, Any::class.java)) {
                when (span) {
                    is ForegroundColorSpan, is BackgroundColorSpan -> out.removeSpan(span)
                    is TextAppearanceSpan -> if (span.textColor != null || span.linkTextColor != null) {
                        val start = out.getSpanStart(span)
                        val end = out.getSpanEnd(span)
                        val flags = out.getSpanFlags(span)
                        out.removeSpan(span)
                        out.setSpan(TextAppearanceSpan(span.family, span.textStyle, span.textSize, null, null), start, end, flags)
                    }
                }
            }
            return out
        }

        /**
         * Who a conversation is with, as the system's conversation layout picks it: the conversation's own
         * icon if supplied, then for a group its large icon (the group picture) before the latest sender's
         * photo, and for a one-to-one chat the other way round.
         */
        @Suppress("DEPRECATION")
        fun avatar(n: Notification): Icon? {
            if (!isConversation(n)) return null
            val x = n.extras
            (x.getParcelable(EXTRA_CONVERSATION_ICON) as? Icon)?.let { return it }
            val sender = latestIncoming(x)?.senderIcon
            return if (isGroup(x)) n.getLargeIcon() ?: sender else sender ?: n.getLargeIcon()
        }

        /**
         * Apps state it explicitly (X titles its one-to-one chats yet says they aren't groups);
         * older ones only hint at it with a conversation title, as Android itself falls back to.
         */
        private fun isGroup(x: Bundle): Boolean =
            if (x.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
            else x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null

        /** The ids of a conversation's incoming messages, as [Msg.id], without building its [Content]. */
        fun incomingIds(n: Notification): List<String> {
            val x = n.extras
            val user = user(x)
            return (messages(x, Notification.EXTRA_HISTORIC_MESSAGES) + messages(x))
                .filter { !isOwn(it, user) }.map(::idOf).distinct()
        }

        private fun idOf(m: Message) = "${m.time}|${m.senderKey ?: m.sender}|${m.body.toString().hashCode()}"

        private fun latestIncoming(x: Bundle): Message? {
            val user = user(x)
            return messages(x).filter { !isOwn(it, user) }.maxByOrNull { it.time }
        }

        @Suppress("DEPRECATION")
        private fun user(x: Bundle) = x.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? Person

        /** The user's own messages have no sender, or the messaging style's user as sender. */
        private fun isOwn(message: Message, user: Person?): Boolean =
            message.sender == null || (user != null &&
                (if (user.key != null && message.senderKey != null) user.key == message.senderKey
                else user.name?.toString() == message.sender.toString()))

        private class Message(
            val sender: CharSequence?, val senderKey: String?, val senderIcon: Icon?,
            val body: CharSequence, val time: Long, val attachment: Attachment?,
        )

        @Suppress("DEPRECATION")
        private fun messages(x: Bundle, key: String = Notification.EXTRA_MESSAGES): List<Message> {
            val arr: Array<Parcelable>? = x.getParcelableArray(key)
            return arr.orEmpty().mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val uri = b.getParcelable("uri") as? Uri
                val mime = b.getString("type")
                val attachment = if (uri != null && mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                    Attachment(uri, mime)
                } else null
                // Only an attachment makes a message without text worth keeping.
                val text = b.getCharSequence("text") ?: if (attachment != null) "" else return@mapNotNull null
                val person = b.getParcelable("sender_person") as? Person
                val sender = b.getCharSequence("sender") ?: person?.name
                // A message with no text (TikTok's image-only DMs) reads as its sender line, as in the shade.
                val body = if (text.isBlank() && !sender.isNullOrBlank()) sender else text
                Message(sender, person?.key, person?.icon, body, b.getLong("time"), attachment)
            }
        }

        @Suppress("DEPRECATION")
        fun picture(context: Context, e: Entry): Drawable? {
            val x = e.n.extras
            (x.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)?.let {
                return BitmapDrawable(context.resources, it)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                (x.getParcelable(Notification.EXTRA_PICTURE_ICON) as? Icon)?.let {
                    return runCatching { it.loadDrawable(context) }.getOrNull()
                }
            }
            return null
        }
    }
}

/**
 * App labels and icons, cached; resolved locally via the package manager. Anything that has to
 * read another app's files loads on a background [Lane], so drawing the panel never waits on
 * it: a miss returns a placeholder (null, or an empty label) and the list redraws once the lane
 * has caught up. [warm] loads them ahead of time, as notifications arrive.
 */
object Apps {
    private val labels = HashMap<String, CharSequence>()
    private val icons = LruCache<String, Drawable>(128)
    private val large = LruCache<String, Drawable>(48)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Goes up whenever a load lands, so a card bound without its icon or name is bound again. */
    var generation = 0
        private set

    /**
     * One background thread whose results land on the main thread. Everything here is touched on
     * the main thread only. The list redraws once the lane runs dry, so a burst of loads (the
     * first panel after an update reads dozens of apps) redraws once rather than per app.
     */
    private class Lane(name: String) {
        val looper: android.os.Looper = android.os.HandlerThread(name).apply { start() }.looper
        private val handler = android.os.Handler(looper)
        private val keys = HashSet<String>()

        fun <T : Any> load(key: String, work: () -> T?, done: (T?) -> Unit) {
            if (!keys.add(key)) return
            handler.post {
                val v = runCatching(work).getOrNull()
                main.post {
                    keys.remove(key)
                    done(v)
                    generation++
                    if (keys.isEmpty()) NotifStore.changed()
                }
            }
        }
    }

    /** The package manager and app resources: labels, icons, large icons. */
    private val apps = Lane("chronotif-apps")

    /** Other apps' content, which can be slow: avatar URIs, custom layouts. Kept apart so it never holds up icons. */
    private val content = Lane("chronotif-content")

    /** For tests, which wait for the lanes to finish. */
    internal val loopers get() = listOf(apps.looper, content.looper)

    /** Starts loading what [entries] will need, so the panel finds it ready. */
    fun warm(context: Context, entries: Collection<Entry>) {
        for (e in entries) {
            label(context, e.pkg)
            icon(context, e)
        }
    }

    /** The app's name, or an empty one while it loads. */
    fun label(context: Context, pkg: String): CharSequence {
        labels[pkg]?.let { return it }
        val app = context.applicationContext
        apps.load("l:$pkg", {
            val pm = app.packageManager
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
        }) { labels[pkg] = it ?: pkg }
        return ""
    }

    /** Apps with no icon to show, so a failed load isn't retried on every redraw. */
    private val noIcon = HashSet<String>()

    /** The app's icon, badged for a work profile, or null while it loads. */
    fun icon(context: Context, e: Entry): Drawable? {
        icons.get(e.appId)?.let { return it }
        if (e.appId in noIcon) return null
        val app = context.applicationContext
        val pkg = e.pkg
        val user = e.sbn.user
        val small = e.n.smallIcon
        apps.load("i:${e.appId}", {
            val pm = app.packageManager
            try {
                val plain = pm.getApplicationIcon(pkg)
                if (user == Process.myUserHandle()) plain
                else runCatching { pm.getUserBadgedIcon(plain, user) }.getOrDefault(plain)
            } catch (_: PackageManager.NameNotFoundException) {
                small?.loadDrawable(app)
            }
        }) { d -> if (d != null) icons.put(e.appId, d) else remember(noIcon, e.appId) }
        return null
    }

    /** Keys whose large icon or avatar couldn't be loaded, so it isn't retried on every redraw. */
    private val noLarge = HashSet<String>()

    /**
     * Contact photo / sender avatar etc. Keyed by post time so updates refresh it. With no large icon,
     * the photo inside the app's custom layout stands in for it (see [customPicture]). A bitmap is
     * already in memory; anything else (another app's resource, a URI) loads on [apps].
     */
    fun largeIcon(context: Context, e: Entry): Drawable? {
        val k = "${e.key}@${e.sbn.postTime}"
        large.get(k)?.let { return it }
        val icon = e.n.getLargeIcon()
        if (icon == null || k in noLarge) return customPicture(context, e, k)
        if (icon.type == Icon.TYPE_BITMAP || icon.type == Icon.TYPE_ADAPTIVE_BITMAP) {
            runCatching { icon.loadDrawable(context) }.getOrNull()?.let {
                large.put(k, it)
                return it
            }
            remember(noLarge, k)
            return customPicture(context, e, k)
        }
        val app = context.applicationContext
        apps.load(k, { icon.loadDrawable(app) }) { d -> if (d != null) large.put(k, d) else remember(noLarge, k) }
        return null
    }

    /** Keys whose custom layout has been searched and held no picture. */
    private val noPicture = HashSet<String>()

    /** Failure sets are keyed per post, so they're bounded rather than kept forever. */
    private fun remember(set: HashSet<String>, k: String) {
        if (set.size >= 512) set.clear()
        set += k
    }

    /**
     * The biggest photo inside a notification's custom layout. TikTok draws a LIVE host's profile
     * photo only there, with no large icon. The layout is inflated once, on [content], so binding a
     * card never waits on it; the card redraws once the photo is in.
     */
    private fun customPicture(context: Context, e: Entry, k: String): Drawable? {
        val rv = e.n.contentView ?: e.n.bigContentView ?: return null
        if (k in noPicture) return null
        val app = context.applicationContext
        content.load("p:$k", { biggestPhoto(app, rv) }) { d ->
            if (d == null) remember(noPicture, k) else large.put(k, d)
        }
        return null
    }

    /**
     * A photo, not decoration: the largest bitmap the app set when posting, at least [MIN_PICTURE_PX]
     * on its short side. An image already in the bare layout (TikTok's logo, bigger than the photo)
     * is part of the design, so it doesn't count.
     */
    private fun biggestPhoto(context: Context, rv: android.widget.RemoteViews): Drawable? {
        val parent = android.widget.FrameLayout(context)
        val bare = HashMap<Int, Bitmap>()
        runCatching {
            val pkg = context.createPackageContext(rv.`package`, 0)
            images(android.view.LayoutInflater.from(pkg).inflate(rv.layoutId, parent, false)) { v, d -> (d as? BitmapDrawable)?.bitmap?.let { bare[v.id] = it } }
        }
        var best: BitmapDrawable? = null
        images(rv.apply(context, parent)) { v, d ->
            val b = (d as? BitmapDrawable)?.bitmap
            if (b == null || bare[v.id]?.sameAs(b) == true || minOf(b.width, b.height) < MIN_PICTURE_PX) return@images
            if (best == null || b.width * b.height > best!!.bitmap.width * best!!.bitmap.height) best = d
        }
        return best
    }

    private fun images(v: android.view.View, f: (android.widget.ImageView, Drawable) -> Unit) {
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) images(v.getChildAt(i), f)
        if (v is android.widget.ImageView) v.drawable?.let { f(v, it) }
    }

    private const val MIN_PICTURE_PX = 64

    /**
     * The conversation's [Content.avatar], keyed by post time like [largeIcon]. A photo behind
     * another app's content link loads on [content], which a slow app could otherwise
     * freeze; the card redraws once it's in.
     */
    fun avatar(context: Context, e: Entry): Drawable? {
        val k = "a:${e.key}@${e.sbn.postTime}"
        large.get(k)?.let { return it }
        if (k in noLarge) return null
        val icon = Content.avatar(e.n) ?: return null
        if (icon.type != Icon.TYPE_URI && icon.type != Icon.TYPE_URI_ADAPTIVE_BITMAP) {
            val d = runCatching { icon.loadDrawable(context) }.getOrNull() ?: return null
            large.put(k, d)
            return d
        }
        val app = context.applicationContext
        content.load(k, { icon.loadDrawable(app) }) { d -> if (d != null) large.put(k, d) else remember(noLarge, k) }
        return null
    }

    fun actionIcon(context: Context, a: Notification.Action): Drawable? =
        runCatching { a.getIcon()?.loadDrawable(context) }.getOrNull()
}
