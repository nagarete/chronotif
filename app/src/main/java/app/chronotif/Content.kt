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

/**
 * What a card displays, extracted from a notification's extras. A custom layout's RemoteViews are
 * only inflated to find its photo ([biggestPhoto]).
 */
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

            val messages = conversation(x)
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
                val ids = ids(messages)
                msgs = messages.mapIndexed { i, m ->
                    Msg(
                        id = ids[i],
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

        /** One incoming message's [Msg.id] and send time. */
        class Incoming(val id: String, val time: Long)

        /**
         * A conversation's incoming messages, oldest first, as [of] gives them in [messages], without
         * building its [Content]: what is New is decided per message, on every change, so this
         * skips the text and attachments a card needs.
         */
        fun incoming(n: Notification): List<Incoming> {
            val x = n.extras
            val all = conversation(x)
            if (all.isEmpty()) return emptyList()
            val user = user(x)
            val ids = ids(all)
            return all.indices.filter { !isOwn(all[it], user) }.map { Incoming(ids[it], all[it].time) }
        }

        /** The ids of a conversation's incoming messages, as [Msg.id], without building its [Content]. */
        fun incomingIds(n: Notification): List<String> = incoming(n).map { it.id }

        /**
         * The historic messages Android keeps for context, then the active ones, oldest first. Apps
         * repeat some active messages in the historic list, so a historic one that is also active
         * is dropped. Repeats within one list stay: two photos sent together can share a time,
         * a sender and the same placeholder text.
         */
        private fun conversation(x: Bundle): List<Message> {
            val active = messages(x)
            val historic = messages(x, Notification.EXTRA_HISTORIC_MESSAGES)
            if (historic.isEmpty()) return active.sortedBy { it.time }
            val activeKeys = active.mapTo(HashSet(), ::keyOf)
            return (historic.filter { keyOf(it) !in activeKeys } + active).sortedBy { it.time }
        }

        /**
         * Ids that stay the same when the app reposts the conversation, so a hidden message stays
         * hidden. A message is known by its time, sender, attachment and text. The text is hashed to
         * 64 bits to keep ids short; a repeat of the same message gets its place among the repeats,
         * so every id in a conversation is distinct.
         */
        private fun ids(messages: List<Message>): List<String> {
            val seen = HashMap<String, Int>()
            return messages.map { m ->
                val base = "${m.time}|${m.senderKey ?: m.sender}|${m.attachment?.uri ?: ""}|${hash64(m.body)}"
                val n = seen.merge(base, 1, Int::plus)!!
                if (n == 1) base else "$base|$n"
            }
        }

        private fun keyOf(m: Message) =
            listOf(m.time, m.senderKey ?: m.sender?.toString(), m.attachment?.uri, m.body.toString())

        /** FNV-1a over the text's UTF-16 units: 64 bits, so two texts sharing an id is out of reach in practice. */
        private fun hash64(cs: CharSequence): String {
            var h = -0x340d631b7bdddcdbL
            for (i in cs.indices) {
                h = h xor cs[i].code.toLong()
                h *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(h)
        }

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
    }
}

/**
 * App labels and icons, cached; resolved locally via the package manager. Anything that has to
 * read another app's files or decode a picture loads on a background [Lane], so drawing the
 * panel never waits on it: a miss returns a placeholder (null, or an empty label) and the list
 * redraws once the lane has caught up. A bitmap the notification already carries is used at
 * once. [warm] loads them ahead of time, as notifications arrive.
 */
object Apps {
    private val labels = HashMap<String, CharSequence>()
    private val icons = LruCache<String, Drawable>(128)

    /**
     * Large icons, avatars, big pictures and custom-layout photos, bounded by their bitmaps'
     * bytes rather than their number: one big picture can outweigh a hundred avatars.
     */
    private val large = object : LruCache<String, Drawable>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Drawable) = bytes(value)
    }

    /** Action buttons' icons, keyed by where they come from, so every post of a notification shares them. */
    private val actionIcons = LruCache<String, Drawable>(64)

    /** Action icons with no source to key them by (raw bytes), kept only as long as their notification. */
    private val looseActionIcons = java.util.WeakHashMap<Icon, Drawable?>()

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

        /** Loads in flight, each with whoever asked to hear when it lands. */
        private val pending = HashMap<String, MutableList<() -> Unit>>()

        fun <T : Any> load(key: String, work: () -> T?, done: (T?) -> Unit, then: (() -> Unit)? = null) {
            pending[key]?.let { waiting ->
                if (then != null) waiting += then
                return
            }
            pending[key] = ArrayList<() -> Unit>().also { if (then != null) it += then }
            handler.post {
                val v = runCatching(work).getOrNull()
                main.post {
                    val after = pending.remove(key).orEmpty()
                    done(v)
                    generation++
                    after.forEach { it() }
                    if (pending.isEmpty()) NotifStore.changed()
                }
            }
        }
    }

    /** The package manager and app resources: labels, icons, resource and byte icons. */
    private val apps = Lane("chronotif-apps")

    /** Other apps' content, which can be slow: picture and avatar URIs, custom layouts. Kept apart so it never holds up icons. */
    private val content = Lane("chronotif-content")

    /** For tests, which wait for the lanes to finish. */
    internal val loopers get() = listOf(apps.looper, content.looper)

    /** Starts loading what [entries] will need, so the panel finds it ready. */
    fun warm(context: Context, entries: Collection<Entry>) {
        for (e in entries) {
            label(context, e.pkg)
            icon(context, e)
            avatar(context, e)
        }
    }

    /** The app's name, or an empty one while it loads. */
    fun label(context: Context, pkg: String): CharSequence {
        Samples.label(pkg)?.let { return it }
        labels[pkg]?.let { return it }
        val app = context.applicationContext
        apps.load("l:$pkg", {
            val pm = app.packageManager
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
        }, { labels[pkg] = it ?: pkg })
        return ""
    }

    /** Apps with no icon to show, so a failed load isn't retried on every redraw. */
    private val noIcon = HashSet<String>()

    /** The app's icon, badged for a work profile, or null while it loads. */
    fun icon(context: Context, e: Entry): Drawable? {
        if (e.isSample) return Samples.icon(context, e.pkg)
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
        }, { d -> if (d != null) icons.put(e.appId, d) else remember(noIcon, e.appId) })
        return null
    }

    /** Keys whose large icon, avatar or picture couldn't be loaded, so it isn't retried on every redraw. */
    private val noLarge = HashSet<String>()

    /**
     * Contact photo / sender avatar etc. Keyed by post time so updates refresh it. With no large icon,
     * the photo inside the app's custom layout stands in for it (see [customPicture]). A bitmap is
     * already in memory; anything else loads in the background ([loadIcon]).
     */
    fun largeIcon(context: Context, e: Entry): Drawable? {
        val k = "${e.key}@${e.sbn.postTime}"
        large.get(k)?.let { return it }
        val icon = e.n.getLargeIcon()
        if (icon == null || k in noLarge) return customPicture(context, e, k)
        if (isBitmap(icon)) {
            ready(context, icon)?.let {
                large.put(k, it)
                return it
            }
            remember(noLarge, k)
            return customPicture(context, e, k)
        }
        // Also the lock screen's large artwork for a Media player (ADR 0012).
        loadIcon(context, icon, k, Images.CARD_PX, { d -> if (d != null) large.put(k, d) else remember(noLarge, k) })
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
        content.load("p:$k", { biggestPhoto(app, rv) }, { d ->
            if (d == null) remember(noPicture, k) else large.put(k, d)
        })
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

    /** Avatars show at most about 48dp; this covers the densest screens. */
    private const val AVATAR_PX = 256

    /**
     * The conversation's [Content.avatar], keyed by post time like [largeIcon]. A bitmap is used
     * at once; a resource, raw bytes or another app's content link loads in the background
     * ([loadIcon]), and the card redraws once it's in.
     */
    fun avatar(context: Context, e: Entry): Drawable? {
        val k = "a:${e.key}@${e.sbn.postTime}"
        large.get(k)?.let { return it }
        if (k in noLarge) return null
        val icon = Content.avatar(e.n) ?: return null
        if (isBitmap(icon)) {
            val d = ready(context, icon)
            if (d == null) remember(noLarge, k) else large.put(k, d)
            return d
        }
        loadIcon(context, icon, k, AVATAR_PX, { d -> if (d != null) large.put(k, d) else remember(noLarge, k) })
        return null
    }

    @Suppress("DEPRECATION")
    private fun pictureSource(n: Notification): Any? {
        (n.extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)?.let { return it }
        if (Build.VERSION.SDK_INT >= 31) return n.extras.getParcelable(Notification.EXTRA_PICTURE_ICON) as? Icon
        return null
    }

    /** Whether [e] is a big-picture notification with a picture to show. */
    fun hasPicture(e: Entry): Boolean = pictureSource(e.n) != null

    /**
     * A big-picture notification's picture, or null while it loads or when there's none. A
     * bitmap is used at once; an icon the app pointed to loads in the background, no larger
     * than the screen, and the card redraws once it's in. When this returns null, [then] gets
     * the picture once it's known, or null if it can't be read.
     */
    fun picture(context: Context, e: Entry, then: ((Drawable?) -> Unit)? = null): Drawable? {
        val source = pictureSource(e.n)
        if (source is Bitmap) return BitmapDrawable(context.resources, source)
        val icon = source as? Icon
        if (icon == null) {
            then?.invoke(null)
            return null
        }
        if (isBitmap(icon)) return ready(context, icon).also { if (it == null) then?.invoke(null) }
        val k = "pic:${e.key}@${e.sbn.postTime}"
        large.get(k)?.let { return it }
        if (k in noLarge) {
            then?.invoke(null)
            return null
        }
        val m = context.resources.displayMetrics
        val maxPx = minOf(maxOf(m.widthPixels, m.heightPixels), Images.VIEWER_PX)
        loadIcon(context, icon, k, maxPx,
            { d -> if (d != null) large.put(k, d) else remember(noLarge, k) },
            then?.let { f -> { f(large.get(k)) } })
        return null
    }

    /** Keys whose action icon couldn't be loaded, so it isn't retried on every redraw. */
    private val noAction = HashSet<String>()

    /**
     * An action button's icon, or null while it loads. Another app's resource loads once on
     * [apps] and is shared by every post that uses it. Each caller gets its own copy, as the
     * card and the player tint and size it themselves.
     */
    fun actionIcon(context: Context, a: Notification.Action): Drawable? {
        val icon = a.getIcon() ?: return null
        if (isBitmap(icon)) return ready(context, icon)
        val k = when (icon.type) {
            Icon.TYPE_RESOURCE -> "r:${icon.resPackage}:${icon.resId}"
            Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP -> "u:${icon.uri}"
            else -> null
        }
        if (k == null) {
            if (looseActionIcons.containsKey(icon)) return looseActionIcons[icon]?.let { copy(context, it) }
            loadIcon(context, icon, "x:${System.identityHashCode(icon)}", AVATAR_PX, { d -> looseActionIcons[icon] = d })
            return null
        }
        actionIcons.get(k)?.let { return copy(context, it) }
        if (k in noAction) return null
        loadIcon(context, icon, "act:$k", AVATAR_PX, { d -> if (d != null) actionIcons.put(k, d) else remember(noAction, k) })
        return null
    }

    private fun copy(context: Context, d: Drawable): Drawable =
        d.constantState?.newDrawable(context.resources) ?: d

    private fun isBitmap(icon: Icon) = icon.type == Icon.TYPE_BITMAP || icon.type == Icon.TYPE_ADAPTIVE_BITMAP
    private fun isUri(icon: Icon) = icon.type == Icon.TYPE_URI || icon.type == Icon.TYPE_URI_ADAPTIVE_BITMAP

    /** A bitmap icon is already decoded in the notification: wrapping it is all there is to do. */
    private fun ready(context: Context, icon: Icon): Drawable? = runCatching { icon.loadDrawable(context) }.getOrNull()

    /**
     * Loads [icon] off the main thread: another app's content link on [content], decoded at most
     * [maxPx] on its long side, and a resource or raw bytes on [apps].
     */
    private fun loadIcon(
        context: Context, icon: Icon, key: String, maxPx: Int,
        done: (Drawable?) -> Unit, then: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        (if (isUri(icon)) content else apps).load(key, { decode(app, icon, maxPx) }, done, then)
    }

    private fun decode(context: Context, icon: Icon, maxPx: Int): Drawable? {
        if (!isUri(icon)) return icon.loadDrawable(context)
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, icon.uri)
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // In memory, as Icon.loadDrawable gives it: the lock screen draws artwork into a small canvas to blur it.
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, maxPx / maxOf(info.size.width, info.size.height).toFloat())
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        val d = BitmapDrawable(context.resources, bitmap)
        // As Icon.loadDrawable wraps an adaptive bitmap.
        return if (icon.type == Icon.TYPE_URI_ADAPTIVE_BITMAP) android.graphics.drawable.AdaptiveIconDrawable(null, d) else d
    }

    /** What [large] counts a drawable as: its bitmap's bytes, or its drawn size for one without a bitmap. */
    private fun bytes(d: Drawable): Int {
        val bitmap = (d as? BitmapDrawable)?.bitmap
            ?: ((d as? android.graphics.drawable.AdaptiveIconDrawable)?.foreground as? BitmapDrawable)?.bitmap
        if (bitmap != null) return bitmap.allocationByteCount
        return d.intrinsicWidth.coerceAtLeast(1) * d.intrinsicHeight.coerceAtLeast(1) * 4
    }

    /** Memory is short: lets go of the pictures, which reload as cards are drawn again. */
    fun trim() {
        large.evictAll()
        actionIcons.evictAll()
        looseActionIcons.clear()
    }

    /**
     * Forgets what Android now draws differently: names after a language change, icons after
     * a density, theme or layout-direction change.
     */
    fun configurationChanged(diff: Int) {
        val affects = android.content.pm.ActivityInfo.CONFIG_LOCALE or android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION or
            android.content.pm.ActivityInfo.CONFIG_DENSITY or android.content.pm.ActivityInfo.CONFIG_UI_MODE
        if (diff and affects == 0) return
        labels.clear()
        icons.evictAll()
        noIcon.clear()
        actionIcons.evictAll()
        noAction.clear()
        looseActionIcons.clear()
        generation++
        NotifStore.changed()
    }

    /** [pkg] was updated, or turned a component on or off: its name and icons may have changed. */
    fun packageChanged(pkg: String) {
        labels.remove(pkg)
        for (k in icons.snapshot().keys) if (k.substringBefore('|') == pkg) icons.remove(k)
        noIcon.removeAll { it.substringBefore('|') == pkg }
        for (k in actionIcons.snapshot().keys) if (k.startsWith("r:$pkg:")) actionIcons.remove(k)
        noAction.removeAll { it.startsWith("r:$pkg:") }
        generation++
        NotifStore.changed()
    }

    /** Hears about app updates for [packageChanged], for as long as the process runs. */
    fun watchPackages(context: Context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) {
                intent.data?.schemeSpecificPart?.let(::packageChanged)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Sent by the system, which reaches non-exported receivers.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // The flags only exist from API 33.
            context.registerReceiver(receiver, filter)
        }
    }
}
