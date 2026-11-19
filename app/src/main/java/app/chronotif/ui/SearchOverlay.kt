// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
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
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.chronotif.Launchables
import app.chronotif.Launchable
import app.chronotif.R
import app.chronotif.core.AppMatch

/**
 * Search (ADR 0008), laid over the panel, the lock-screen view or its own window: the apps that notified most
 * recently, and a field that finds apps by name. It never searches notifications. Opening an app
 * goes through [NotifList.Host.guarded], so on the lock screen it unlocks first.
 */
@SuppressLint("ViewConstructor")
class SearchOverlay private constructor(
    context: Context,
    private val host: NotifList.Host,
    private val onClosed: () -> Unit,
) : FrameLayout(context), Overlay {

    companion object {
        /** Lays Search over [anchor]'s window. Nothing happens if something already covers it. */
        fun show(anchor: View, host: NotifList.Host, onClosed: () -> Unit = {}): SearchOverlay? {
            val root = anchor.rootView as? ViewGroup ?: return null
            if ((0 until root.childCount).any { root.getChildAt(it) is Overlay }) return null
            val v = SearchOverlay(anchor.context, host, onClosed)
            root.addView(v, ViewGroup.LayoutParams(-1, -1))
            return v
        }
    }

    private val pal = Palette(context)
    private val column = LinearLayout(context)
    private val suggestionsBlock = LinearLayout(context)
    private val suggestionsRow = LinearLayout(context)
    private val resultsScroll = ScrollView(context)
    private val results = LinearLayout(context)
    private val empty: TextView
    private val spacer = View(context)
    private val field = EditText(context)
    private val fieldBox = LinearLayout(context)
    private var apps: List<Launchable> = emptyList()
    private var closing = false

    private val back = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { close() } else null

    init {
        setBackgroundColor(if (Build.VERSION.SDK_INT >= 31) 0x80000000.toInt() else 0xE6000000.toInt())
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        // Taps on empty space close it.
        setOnClickListener { close() }

        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(12), 0, dp(12), 0)

        suggestionsBlock.orientation = LinearLayout.VERTICAL
        suggestionsBlock.addView(Ui.text(context, 13f, pal.onScrimSecondary, bold = true, lines = 1).apply {
            setText(R.string.search_suggestions)
            Ui.shadow(this)
            setPadding(dp(8), 0, dp(8), dp(8))
        })
        suggestionsRow.apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(pal.card, dp(22).toFloat())
            setPadding(dp(6), dp(12), dp(6), dp(10))
            isClickable = true // Taps between icons don't fall through and close.
        }
        suggestionsBlock.addView(suggestionsRow, Ui.lp())
        suggestionsBlock.visibility = GONE
        column.addView(suggestionsBlock, Ui.lp())

        results.apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(pal.card, dp(22).toFloat())
            Ui.clipRounded(this, dp(22).toFloat())
            isClickable = true
        }
        empty = Ui.text(context, 15f, pal.onScrimSecondary).apply {
            setText(R.string.search_no_match)
            gravity = Gravity.CENTER_HORIZONTAL
            Ui.shadow(this)
            visibility = GONE
        }
        resultsScroll.apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // Taps below the results close it, as on the backdrop.
                setOnClickListener { close() }
                addView(results, Ui.lp())
                addView(empty, Ui.lp().apply { topMargin = dp(24) })
            }, LayoutParams(-1, -2))
            visibility = GONE
        }
        column.addView(resultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // Keeps the field at the bottom while only the suggestions show.
        column.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))

        fieldBox.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(pal.card, dp(14).toFloat())
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(pal.textSecondary)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            addView(field, LinearLayout.LayoutParams(0, dp(44), 1f))
            setOnClickListener { focusField() }
        }
        field.apply {
            background = null
            setPadding(0, 0, 0, 0)
            textSize = 17f
            setTextColor(pal.text)
            setHintTextColor(pal.textSecondary)
            setHint(R.string.search_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            // Go opens the top result.
            setOnEditorActionListener { _, id, _ ->
                if (id != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
                AppMatch.search(apps, text.toString()) { it.label }.firstOrNull()?.let(::open)
                true
            }
        }
        column.addView(fieldBox, Ui.lp().apply { topMargin = dp(12); bottomMargin = dp(10) })

        addView(column, LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            column.setPadding(bars.left + dp(12), bars.top + dp(16), bars.right + dp(12), maxOf(bars.bottom, ime.bottom))
            insets
        }

        Launchables.load(context) { list ->
            apps = list
            refresh()
        }
    }

    private fun refresh() {
        if (closing) return
        val query = field.text.toString()
        if (AppMatch.fold(query).isEmpty()) {
            resultsScroll.visibility = GONE
            spacer.visibility = VISIBLE
            val picks = Launchables.suggestions(apps)
            suggestionsBlock.visibility = if (picks.isEmpty()) GONE else VISIBLE
            suggestionsRow.removeAllViews()
            for (a in picks) suggestionsRow.addView(cell(a), LinearLayout.LayoutParams(0, -2, 1f))
            // Keep four columns, so fewer suggestions don't stretch apart.
            repeat(AppMatch.SUGGESTIONS - picks.size) {
                suggestionsRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            }
            return
        }
        suggestionsBlock.visibility = GONE
        resultsScroll.visibility = VISIBLE
        spacer.visibility = GONE
        val found = AppMatch.search(apps, query) { it.label }
        results.removeAllViews()
        results.visibility = if (found.isEmpty()) GONE else VISIBLE
        empty.visibility = if (found.isEmpty()) VISIBLE else GONE
        for (a in found) results.addView(row(a), Ui.lp())
        resultsScroll.scrollTo(0, 0)
    }

    /** An icon with its name under it, for the suggestion row. */
    private fun cell(a: Launchable): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = Ui.pressable(0, dp(14).toFloat(), pal.ripple)
        setPadding(dp(2), dp(4), dp(2), dp(4))
        val icon = ImageView(context)
        Launchables.icon(context, a) { icon.setImageDrawable(RoundedIcon(it)) }
        addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(Ui.text(context, 12f, pal.text, lines = 1).apply {
            text = a.label
            gravity = Gravity.CENTER_HORIZONTAL
        }, Ui.lp().apply { topMargin = dp(6) })
        contentDescription = a.label
        setOnClickListener { open(a) }
    }

    private fun row(a: Launchable): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        val icon = ImageView(context)
        Launchables.icon(context, a) { icon.setImageDrawable(RoundedIcon(it)) }
        addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        addView(Ui.text(context, 16f, pal.text, lines = 1).apply { text = a.label }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { open(a) }
    }

    private fun open(a: Launchable) {
        hideKeyboard()
        host.guarded {
            Launchables.launch(host.context, a)
            host.launched()
        }
    }

    private fun focusField() {
        field.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun close() {
        if (closing) return
        closing = true
        hideKeyboard()
        animate().alpha(0f).setDuration(150).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onClosed()
        }.start()
    }

    /** What sits behind in the same window, blurred while Search is up. */
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
        // The keyboard only comes up once the field is in a focused window.
        post(::focusField)
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) blurred.forEach { it.setRenderEffect(null) }
        blurred.clear()
        if (Build.VERSION.SDK_INT >= 33) findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back!!)
        super.onDetachedFromWindow()
    }

    // --- Swipe up (once the results can't scroll further) closes it.

    private var downY = 0f
    private var atBottomOnDown = false
    private var tracker: VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                atBottomOnDown = resultsScroll.visibility != VISIBLE || !resultsScroll.canScrollVertically(1)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> tracker?.addMovement(ev)
            MotionEvent.ACTION_UP -> {
                val t = tracker
                t?.addMovement(ev)
                t?.computeCurrentVelocity(1000)
                val vy = t?.yVelocity ?: 0f
                t?.recycle()
                tracker = null
                if (atBottomOnDown && downY - ev.rawY > dp(90) && vy < -dp(500)) {
                    MotionEvent.obtain(ev).also {
                        it.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(it)
                        it.recycle()
                    }
                    close()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
