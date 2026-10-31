// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import app.chronotif.Act
import app.chronotif.Apps
import app.chronotif.Content
import app.chronotif.Entry
import app.chronotif.Images
import app.chronotif.R
import app.chronotif.Viewable
import app.chronotif.core.Kind

/** [id] names the card: a notification's key, or one of a conversation's message cards. */
interface CardCallbacks {
    fun onTap(e: Entry, c: Content)
    fun onToggleExpand(e: Entry, id: String)
    fun onAction(e: Entry, id: String, index: Int)
    fun onSendReply(e: Entry, id: String, index: Int, text: CharSequence)
    fun onDismiss(e: Entry)
    /** Opens [items] full screen at [start], over the window that holds [anchor]. */
    fun onViewImages(anchor: View, e: Entry, items: List<Viewable>, start: Int)
    /** Holds the card showing [c] (sent at [at]) up on its own, over the window that holds [anchor]. */
    fun onPreview(anchor: View, e: Entry, c: Content, at: Long)
}

/** One notification, iOS-style: app icon or avatar, title + time, body, optional large icon; expands in place. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CardView(context: Context, private val cb: CardCallbacks) : LinearLayout(context) {

    enum class Style { FULL, RECEDED, COMPACT }

    private val pal = Palette(context)
    private val iconBox = AvatarView(context)
    private val app = Ui.text(context, 12f, pal.textSecondary, lines = 1)
    private val title = Ui.text(context, 15f, pal.text, bold = true, lines = 1)
    private val time = Ui.text(context, 13f, pal.textSecondary, lines = 1)
    private val chevron = ImageView(context)
    private val body = Ui.text(context, 15f, pal.text)
    /** The right-hand thumbnail: a message's Attachment, or another notification's large icon. */
    private val large = ImageView(context)
    private val thumbPlay = ImageView(context)
    private val morePhotos = Ui.text(context, 11f, 0xFFFFFFFF.toInt(), bold = true, lines = 1)
    private val thumb = FrameLayout(context)
    private val more = LinearLayout(context)
    private val lines = LinearLayout(context)
    private val picture = ImageView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val actions = LinearLayout(context)
    private val reply = LinearLayout(context)
    private val replyInput = EditText(context)
    private val replySend = ImageView(context)

    private var entry: Entry? = null
    private var id = ""
    private var at = 0L
    private var style: Style? = null
    private var replyIndex = -1

    /** Owners (e.g. a collapsed stack) can redirect gestures. */
    var tapOverride: (() -> Unit)? = null
    var swipeAllowed: () -> Boolean = { entry?.clearable == true }
    var onSwiped: () -> Unit = { entry?.let(cb::onDismiss) }
    val swipe = SwipeDismiss({ swipeAllowed() }, { onSwiped() })

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))

        chevron.setImageResource(R.drawable.ic_chevron)
        chevron.imageTintList = ColorStateList.valueOf(pal.textSecondary)
        chevron.contentDescription = context.getString(R.string.expand)
        chevron.setPadding(dp(4), dp(2), 0, dp(2))
        chevron.setOnClickListener { entry?.let { cb.onToggleExpand(it, id) } }

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(time, Ui.lp(LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(chevron, Ui.lp(dp(24), dp(20)))
        }
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(app, Ui.lp().apply { bottomMargin = dp(2) })
            addView(head)
            addView(body, Ui.lp().apply { topMargin = dp(3) })
        }
        Ui.clipRounded(large, dp(10).toFloat())
        large.scaleType = ImageView.ScaleType.CENTER_CROP
        thumbPlay.setImageResource(R.drawable.ic_media_play)
        thumbPlay.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        thumbPlay.background = Ui.rounded(0x80000000.toInt(), dp(10).toFloat())
        thumbPlay.setPadding(dp(5), dp(5), dp(5), dp(5))
        morePhotos.gravity = Gravity.CENTER
        morePhotos.setPadding(dp(5), 0, dp(5), 0)
        morePhotos.background = Ui.rounded(0xB3000000.toInt(), dp(8).toFloat())
        thumb.apply {
            addView(large, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(thumbPlay, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            addView(morePhotos, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(16), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(3), dp(3))
            })
        }

        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconBox, Ui.lp(dp(36), dp(36)).apply { marginEnd = dp(12) })
            addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(thumb, Ui.lp(dp(44), dp(44)).apply { marginStart = dp(10) })
            clipChildren = false
        }
        clipChildren = false
        clipToPadding = false
        addView(top)

        lines.orientation = VERTICAL
        picture.adjustViewBounds = true
        picture.maxHeight = dp(240)
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        Ui.clipRounded(picture, dp(12).toFloat())
        actions.orientation = HORIZONTAL

        replyInput.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(pal.text)
            setHintTextColor(pal.textSecondary)
            background = Ui.rounded(pal.chip, dp(18).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) sendReply()
                id == EditorInfo.IME_ACTION_SEND
            }
        }
        replySend.apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = Ui.rounded(pal.accent, dp(18).toFloat())
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = context.getString(R.string.send)
            setOnClickListener { sendReply() }
        }
        reply.orientation = HORIZONTAL
        reply.gravity = Gravity.BOTTOM
        reply.addView(replyInput, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        reply.addView(replySend, Ui.lp(dp(36), dp(36)).apply { marginStart = dp(8) })

        more.orientation = VERTICAL
        more.addView(lines)
        more.addView(picture, Ui.lp().apply { topMargin = dp(8) })
        more.addView(progress, Ui.lp().apply { topMargin = dp(8) })
        more.addView(actions, Ui.lp().apply { topMargin = dp(10) })
        more.addView(reply, Ui.lp().apply { topMargin = dp(10) })
        addView(more, Ui.lp().apply { marginStart = dp(48) })

        setOnClickListener {
            val tap = tapOverride
            val e = entry
            when {
                tap != null -> tap()
                e != null && current != null -> cb.onTap(e, current!!)
            }
        }
        // Press and hold holds the card up on its own (the Preview), stacks included.
        setOnLongClickListener {
            val e = entry
            val c = current
            if (e != null && c != null) cb.onPreview(this, e, c, at)
            true
        }
        setOnTouchListener(swipe)
    }

    private var current: Content? = null

    /**
     * [newSince]: a New card lists every message sent after it, not just the latest. A message
     * card passes its own [id] and the message's send time as [at].
     */
    fun bind(
        e: Entry, c: Content, style: Style, expanded: Boolean, replyIndex: Int, now: Long,
        newSince: Long = Long.MAX_VALUE, id: String = e.key, at: Long = e.alertTime,
    ) {
        val keyChanged = this.id != id
        entry = e
        this.id = id
        this.at = at
        current = c
        if (keyChanged) swipe.reset(this)

        if (this.style != style) {
            this.style = style
            val radius = dp(if (style == Style.COMPACT) 18 else 22).toFloat()
            background = Ui.pressable(if (style == Style.RECEDED) pal.cardReceded else pal.card, radius, pal.ripple)
            val iconSize = dp(when (style) { Style.FULL -> 36; Style.RECEDED -> 32; Style.COMPACT -> 28 })
            iconBox.layoutParams = (iconBox.layoutParams as LayoutParams).apply { width = iconSize; height = iconSize }
            val textSp = if (style == Style.FULL) 15f else 14f
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            val vpad = dp(if (style == Style.FULL) 12 else 10)
            setPadding(dp(14), vpad, dp(14), vpad)
        }

        iconBox.bind(e, c)

        val showApp = expanded && !c.redacted
        app.visibility = if (showApp) VISIBLE else GONE
        if (showApp) app.text = listOfNotNull(c.appName, c.subText).joinToString(" · ")

        title.text = c.title
        time.text = if (e.kind != Kind.NOTIFICATION) "" else Ui.relTime(context, at, now)

        val acts = if (c.redacted) emptyList() else e.n.actions.orEmpty().take(3)
        val canExpand = c.expandable || acts.isNotEmpty()
        chevron.visibility = if (canExpand) VISIBLE else GONE
        chevron.rotation = if (expanded) 180f else 0f

        val conversationExpanded = expanded && c.lines.size > 1 && c.text == c.lines.last()
        val newMessages = if (expanded) emptyList() else c.messagesSince(newSince)
        val shownLines = when {
            expanded && c.lines.size > 1 -> c.lines.indices.toList()
            newMessages.size > 1 -> (c.lines.size - newMessages.size until c.lines.size).toList()
            else -> emptyList()
        }
        val bodyText = when {
            conversationExpanded || newMessages.size > 1 -> null
            expanded -> c.bigText ?: c.text
            else -> c.text
        }
        body.text = bodyText
        body.visibility = if (bodyText.isNullOrEmpty()) GONE else VISIBLE
        body.maxLines = when {
            expanded -> 24
            style == Style.FULL -> 4
            style == Style.RECEDED -> 2
            else -> 1
        }
        body.ellipsize = android.text.TextUtils.TruncateAt.END

        // A conversation's large icon is its avatar, already shown on the left; its thumbnail is
        // the latest Attachment the card shows. Tapping it opens the app, like the rest of the card.
        val attachment = if (c.redacted) null else c.attachments.lastOrNull { it != null }
        thumbPlay.visibility = if (attachment?.isVideo == true) VISIBLE else GONE
        morePhotos.visibility = GONE
        if (attachment != null) {
            thumb.visibility = VISIBLE
            large.contentDescription = context.getString(if (attachment.isVideo) R.string.video else R.string.view_image)
            // Unreadable (the app revoked it, or never shared it): just the text.
            Images.into(large, attachment, Images.CARD_PX) { ok -> if (!ok) thumb.visibility = GONE }
        } else {
            large.tag = null
            large.contentDescription = null
            val icon = if (c.redacted || Content.isConversation(e.n)) null else Apps.largeIcon(context, e)
            large.setImageDrawable(icon)
            thumb.visibility = if (icon != null) VISIBLE else GONE
        }

        // Expanded extras, and the messages a New card lists.
        lines.removeAllViews()
        for (i in shownLines) {
            lines.addView(Ui.text(context, 14f, pal.text).apply {
                text = c.lines[i]
                if (!expanded) {
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }, Ui.lp().apply { topMargin = dp(3) })
        }
        lines.visibility = if (lines.childCount > 0) VISIBLE else GONE

        val pic = if (expanded && !c.redacted) Content.picture(context, e) else null
        bindImage(picture, pic)
        picture.setOnClickListener(if (pic == null) null else OnClickListener {
            cb.onViewImages(this, e, listOf(Viewable(null, pic)), 0)
        })

        val p = c.progress
        progress.visibility = if (p != null && !c.redacted) VISIBLE else GONE
        if (p != null) {
            progress.isIndeterminate = p.indeterminate
            progress.max = p.max.coerceAtLeast(1)
            progress.progress = p.value
            progress.progressTintList = ColorStateList.valueOf(e.n.color.takeIf { it != 0 } ?: pal.accent)
        }

        actions.removeAllViews()
        if (expanded && acts.isNotEmpty()) acts.forEachIndexed { i, a ->
            actions.addView(actionChip(a, e, i), Ui.lp(LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) })
        }
        actions.visibility = if (actions.childCount > 0) VISIBLE else GONE

        val wasReplying = this.replyIndex >= 0 && reply.visibility == VISIBLE
        this.replyIndex = if (expanded) replyIndex else -1
        val replying = this.replyIndex >= 0 && this.replyIndex < acts.size
        reply.visibility = if (replying) VISIBLE else GONE
        if (replying) {
            replyInput.hint = Act.replyInput(acts[this.replyIndex])?.label ?: context.getString(R.string.reply_hint)
            if (!wasReplying || keyChanged) {
                replyInput.setText("")
                replyInput.requestFocus()
                replyInput.post {
                    context.getSystemService(InputMethodManager::class.java)
                        .showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        more.visibility =
            if (lines.visibility == VISIBLE || picture.visibility == VISIBLE || progress.visibility == VISIBLE ||
                actions.visibility == VISIBLE || reply.visibility == VISIBLE
            ) VISIBLE else GONE
    }

    private fun actionChip(a: android.app.Notification.Action, e: Entry, index: Int): View {
        val label = a.title?.takeIf { it.isNotBlank() }
        val click = OnClickListener { cb.onAction(e, id, index) }
        val chip: View = if (label != null) {
            Ui.text(context, 14f, pal.text, bold = true, lines = 1).apply {
                text = label
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
            }
        } else {
            ImageView(context).apply {
                setImageDrawable(Apps.actionIcon(context, a))
                imageTintList = ColorStateList.valueOf(pal.text)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                contentDescription = a.title
            }
        }
        chip.background = Ui.pressable(pal.chip, dp(17).toFloat(), pal.ripple)
        chip.setOnClickListener(click)
        return chip
    }

    private fun sendReply() {
        val e = entry ?: return
        val text = replyInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || replyIndex < 0) return
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        cb.onSendReply(e, id, replyIndex, text)
    }

    /** The message count on a collapsed Conversation stack's avatar; 0 hides it. */
    fun showCount(n: Int) = iconBox.showCount(n)

    /** "+n" on the thumbnail: a collapsed stack's other photos. Reset by every [bind]. */
    fun showMorePhotos(n: Int) {
        morePhotos.text = context.getString(R.string.more_photos, n)
        morePhotos.visibility = if (n > 0 && thumb.visibility == VISIBLE) VISIBLE else GONE
    }

    private fun bindImage(v: ImageView, d: Drawable?) {
        v.setImageDrawable(d)
        v.visibility = if (d != null) VISIBLE else GONE
    }
}

/** A collapsed EARLIER stack: the newest card with the rest peeking out underneath. */
@SuppressLint("ViewConstructor")
class StackView(context: Context, cb: CardCallbacks) : FrameLayout(context) {
    val card = CardView(context, cb)
    private val pal = Palette(context)
    private val sliver1 = View(context)
    private val sliver2 = View(context)

    init {
        clipChildren = false
        // Slivers sit below the card rather than behind it: the card is translucent.
        sliver2.background = bottomRounded(pal.sliver2, dp(12).toFloat())
        sliver1.background = bottomRounded(pal.sliver1, dp(14).toFloat())
        addView(sliver2, LayoutParams(LayoutParams.MATCH_PARENT, dp(7), Gravity.BOTTOM).apply {
            marginStart = dp(30); marginEnd = dp(30)
        })
        addView(sliver1, LayoutParams(LayoutParams.MATCH_PARENT, dp(7), Gravity.BOTTOM).apply {
            marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(7)
        })
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })
    }

    private fun bottomRounded(color: Int, r: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
    }

    fun bind(count: Int) {
        sliver2.visibility = if (count > 2) VISIBLE else GONE
        (sliver1.layoutParams as LayoutParams).bottomMargin = dp(if (count > 2) 7 else 0)
        (card.layoutParams as LayoutParams).bottomMargin = dp(if (count > 2) 14 else 7)
        contentDescription = context.getString(R.string.more_in_stack, count - 1)
    }
}

/** An app icon, or for a conversation its Avatar badged with the app icon; optionally a message count. */
@SuppressLint("ViewConstructor")
class AvatarView(context: Context) : FrameLayout(context) {
    private val pal = Palette(context)
    private val icon = ImageView(context)
    private val avatar = ImageView(context)
    private val badge = ImageView(context)
    private val count = Ui.text(context, 11f, 0xFFFFFFFF.toInt(), bold = true, lines = 1)

    init {
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        Ui.clipRounded(icon, dp(9).toFloat())
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        Ui.clipOval(avatar)
        badge.background = Ui.rounded(pal.card or 0xFF000000.toInt(), dp(10).toFloat())
        badge.setPadding(dp(1), dp(1), dp(1), dp(1))
        Ui.clipOval(badge)
        count.gravity = Gravity.CENTER
        count.minWidth = dp(18)
        count.setPadding(dp(5), 0, dp(5), 0)
        count.background = Ui.rounded(pal.accent, dp(9).toFloat())
        count.visibility = GONE
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(avatar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(badge, LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, -dp(3), -dp(3))
        })
        addView(count, LayoutParams(LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.END).apply {
            setMargins(0, -dp(5), -dp(6), 0)
        })
        clipChildren = false
    }

    fun bind(e: Entry, c: Content) {
        val appIcon = Apps.icon(context, e)
        val face = if (c.redacted) null else Apps.avatar(context, e)
        icon.setImageDrawable(if (face == null) appIcon else null)
        icon.visibility = if (face == null) VISIBLE else GONE
        avatar.setImageDrawable(face)
        avatar.visibility = if (face != null) VISIBLE else GONE
        badge.setImageDrawable(if (face != null) appIcon else null)
        badge.visibility = if (face != null) VISIBLE else GONE
        contentDescription = c.appName
    }

    fun showCount(n: Int) {
        count.text = java.text.NumberFormat.getIntegerInstance().format(n)
        count.visibility = if (n > 1) VISIBLE else GONE
    }
}
