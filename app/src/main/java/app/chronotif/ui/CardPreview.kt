// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.chronotif.Act
import app.chronotif.Content
import app.chronotif.Entry
import app.chronotif.Images
import app.chronotif.R
import app.chronotif.Viewable
import app.chronotif.core.Kind

/** Something laid over a window's content that Back closes first: the Preview, the image viewer. */
interface Overlay {
    fun close()

    companion object {
        /** Closes the topmost overlay in [view]'s window; false if there was none. */
        fun dismissTop(view: View): Boolean {
            val root = view.rootView as? ViewGroup ?: return false
            for (i in root.childCount - 1 downTo 0) {
                val o = root.getChildAt(i) as? Overlay ?: continue
                o.close()
                return true
            }
            return false
        }
    }
}

/**
 * The Preview: one card held up on its own over a dimmed screen, iOS-style. Its full text, its
 * Attachment large (tap it for the full-screen viewer) and the app's buttons; Reply types right
 * here. Like the viewer it sits on whatever window holds the card, so it needs no unlock.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CardPreview private constructor(
    context: Context,
    private val e: Entry,
    private val c: Content,
    at: Long,
    private val actions: Actions,
) : FrameLayout(context), Overlay {

    interface Actions {
        fun open()
        /** A button other than an inline reply; the Preview closes after it. */
        fun action(index: Int)
        /** Replying needs the keyboard, which the lock screen can't offer: it may continue elsewhere. */
        fun canReplyHere(): Boolean
        fun replyElsewhere(index: Int)
        fun sendReply(index: Int, text: CharSequence)
        fun viewImages(anchor: View, items: List<Viewable>, start: Int)
    }

    companion object {
        fun show(anchor: View, e: Entry, c: Content, at: Long, actions: Actions): CardPreview? {
            val root = anchor.rootView as? ViewGroup ?: return null
            if ((0 until root.childCount).any { root.getChildAt(it) is Overlay }) return null
            val preview = CardPreview(anchor.context, e, c, at, actions)
            root.addView(preview, ViewGroup.LayoutParams(-1, -1))
            preview.requestFocus()
            return preview
        }
    }

    private val pal = Palette(context)
    private val buttons = LinearLayout(context)
    private val reply = LinearLayout(context)
    private val replyInput = EditText(context)
    private var replyIndex = -1
    private var closing = false
    private val acts: List<Notification.Action> = if (c.redacted) emptyList() else e.n.actions.orEmpty().take(3)

    private val back = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { close() } else null

    init {
        // Behind a blur (Android 12+) a light dim is enough; without one, dim harder so the list doesn't compete.
        setBackgroundColor(if (Build.VERSION.SDK_INT >= 31) 0x80000000.toInt() else 0xD9000000.toInt())
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { close() }

        val avatar = AvatarView(context).apply { bind(e, c) }
        val title = Ui.text(context, 16f, pal.text, bold = true, lines = 1).apply { text = c.title }
        val time = Ui.text(context, 13f, pal.textSecondary, lines = 1).apply {
            text = if (e.kind != Kind.NOTIFICATION) "" else Ui.relTime(context, at)
        }
        val body = Ui.text(context, 15f, pal.text).apply {
            text = c.bigText ?: c.text
            visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(time, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                })
                addView(body, Ui.lp().apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            background = Ui.pressable(0, 0f, pal.ripple)
            setOnClickListener { actions.open() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.card or 0xFF000000.toInt(), dp(22).toFloat())
            Ui.clipRounded(this, dp(22).toFloat())
            isClickable = true // Taps on the card don't fall through to the backdrop.
            addView(head)
            image()?.let { addView(it, Ui.lp()) }
        }

        buttons.orientation = LinearLayout.VERTICAL
        acts.forEachIndexed { i, a ->
            buttons.addView(button(a, i), Ui.lp(LayoutParams.WRAP_CONTENT, dp(52)).apply { topMargin = dp(8) })
        }
        buildReply()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(card)
            addView(buttons, Ui.lp(LayoutParams.WRAP_CONTENT))
            addView(reply, Ui.lp().apply { topMargin = dp(10) })
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(context).apply {
                // Empty space around the card closes, like the backdrop.
                setOnClickListener { close() }
                addView(column, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { setMargins(dp(12), dp(24), dp(12), dp(24)) })
            })
        }
        addView(scroll, LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** The Attachment (or a picture notification's picture), full width. */
    private fun image(): View? {
        if (c.redacted) return null
        val a = c.attachments.lastOrNull { it != null }
        val pic = if (a == null) Content.picture(context, e) else null
        if (a == null && pic == null) return null
        val image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            setBackgroundColor(0xFF000000.toInt())
            contentDescription = context.getString(if (a?.isVideo == true) R.string.video else R.string.view_image)
        }
        val box = FrameLayout(context).apply {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            if (a?.isVideo == true) addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_media_play)
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                background = Ui.rounded(0x80000000.toInt(), dp(24).toFloat())
                setPadding(dp(13), dp(13), dp(13), dp(13))
            }, LayoutParams(dp(48), dp(48), Gravity.CENTER))
            setOnClickListener {
                if (a != null) {
                    val all = c.gallery
                    actions.viewImages(this@CardPreview, all.map { Viewable(it, null) }, all.indexOf(a))
                } else {
                    actions.viewImages(this@CardPreview, listOf(Viewable(null, pic)), 0)
                }
            }
        }
        if (a != null) Images.into(image, a, Images.VIEWER_PX) { ok -> if (!ok) box.visibility = GONE }
        else image.setImageDrawable(pic)
        return box
    }

    private fun button(a: Notification.Action, index: Int): View =
        Ui.text(context, 16f, pal.text, bold = true, lines = 1).apply {
            text = a.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.open_in_app)
            gravity = Gravity.CENTER_VERTICAL
            minWidth = dp(180)
            setPadding(dp(22), 0, dp(28), 0)
            background = Ui.pressable(pal.card or 0xFF000000.toInt(), dp(26).toFloat(), pal.ripple)
            setOnClickListener {
                when {
                    Act.replyInput(a) == null -> {
                        actions.action(index)
                        close()
                    }
                    actions.canReplyHere() -> startReply(index, a)
                    else -> {
                        actions.replyElsewhere(index)
                        close()
                    }
                }
            }
        }

    private fun buildReply() {
        replyInput.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(pal.text)
            setHintTextColor(pal.textSecondary)
            background = Ui.rounded(pal.card or 0xFF000000.toInt(), dp(22).toFloat())
            setPadding(dp(16), dp(11), dp(16), dp(11))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) send()
                id == EditorInfo.IME_ACTION_SEND
            }
        }
        val sendButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = Ui.rounded(pal.accent, dp(22).toFloat())
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = context.getString(R.string.send)
            setOnClickListener { send() }
        }
        reply.orientation = LinearLayout.HORIZONTAL
        reply.gravity = Gravity.BOTTOM
        reply.addView(replyInput, LinearLayout.LayoutParams(0, -2, 1f))
        reply.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        reply.visibility = GONE
    }

    private fun startReply(index: Int, a: Notification.Action) {
        replyIndex = index
        buttons.visibility = GONE
        reply.visibility = VISIBLE
        replyInput.hint = Act.replyInput(a)?.label ?: context.getString(R.string.reply_hint)
        replyInput.requestFocus()
        replyInput.post {
            context.getSystemService(InputMethodManager::class.java).showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun send() {
        val text = replyInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || replyIndex < 0) return
        actions.sendReply(replyIndex, text)
        close()
    }

    override fun close() {
        if (closing) return
        closing = true
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        animate().alpha(0f).setDuration(150).withEndAction { (parent as? ViewGroup)?.removeView(this) }.start()
    }

    /** What sits behind in the same window, blurred while the Preview is up. */
    private val blurred = ArrayList<View>()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val parent = parent as? ViewGroup
        if (Build.VERSION.SDK_INT >= 31 && parent != null) {
            val blur = android.graphics.RenderEffect.createBlurEffect(
                dp(24).toFloat(), dp(24).toFloat(), android.graphics.Shader.TileMode.CLAMP,
            )
            for (i in 0 until parent.childCount) {
                val v = parent.getChildAt(i)
                if (v === this || v is Overlay) continue
                v.setRenderEffect(blur)
                blurred += v
            }
        }
        alpha = 0f
        animate().alpha(1f).setDuration(150).start()
        requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back!!)
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) blurred.forEach { it.setRenderEffect(null) }
        blurred.clear()
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
}
