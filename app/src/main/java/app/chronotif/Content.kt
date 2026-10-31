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

/** App labels and icons, cached; resolved locally via the package manager. */
object Apps {
    private val labels = HashMap<String, CharSequence>()
    private val icons = LruCache<String, Drawable>(48)
    private val large = LruCache<String, Drawable>(48)
    private val loading = HashSet<String>()
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun label(context: Context, pkg: String): CharSequence = labels.getOrPut(pkg) {
        val pm = context.packageManager
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    fun icon(context: Context, e: Entry): Drawable? {
        icons.get(e.appId)?.let { return it }
        val pm = context.packageManager
        val d = try {
            val plain = pm.getApplicationIcon(e.pkg)
            if (e.sbn.user == Process.myUserHandle()) plain
            else runCatching { pm.getUserBadgedIcon(plain, e.sbn.user) }.getOrDefault(plain)
        } catch (_: PackageManager.NameNotFoundException) {
            runCatching { e.n.smallIcon?.loadDrawable(context) }.getOrNull()
        } ?: return null
        icons.put(e.appId, d)
        return d
    }

    /** Contact photo / sender avatar etc. Keyed by post time so updates refresh it. */
    fun largeIcon(context: Context, e: Entry): Drawable? {
        val k = "${e.key}@${e.sbn.postTime}"
        large.get(k)?.let { return it }
        val d = runCatching { e.n.getLargeIcon()?.loadDrawable(context) }.getOrNull() ?: return null
        large.put(k, d)
        return d
    }

    /**
     * The conversation's [Content.avatar], keyed by post time like [largeIcon]. A photo behind
     * another app's content link loads off the main thread, which a slow app could otherwise
     * freeze; the card redraws once it's in.
     */
    fun avatar(context: Context, e: Entry): Drawable? {
        val k = "a:${e.key}@${e.sbn.postTime}"
        large.get(k)?.let { return it }
        val icon = Content.avatar(e.n) ?: return null
        if (icon.type != Icon.TYPE_URI && icon.type != Icon.TYPE_URI_ADAPTIVE_BITMAP) {
            val d = runCatching { icon.loadDrawable(context) }.getOrNull() ?: return null
            large.put(k, d)
            return d
        }
        if (loading.add(k)) {
            val app = context.applicationContext
            io.execute {
                val d = runCatching { icon.loadDrawable(app) }.getOrNull()
                main.post {
                    loading.remove(k)
                    if (d != null) {
                        large.put(k, d)
                        NotifStore.changed()
                    }
                }
            }
        }
        return null
    }

    fun actionIcon(context: Context, a: Notification.Action): Drawable? =
        runCatching { a.getIcon()?.loadDrawable(context) }.getOrNull()
}
