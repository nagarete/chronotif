// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.chronotif.Launchable
import app.chronotif.Launchables
import app.chronotif.R
import app.chronotif.core.AppMatch
import app.chronotif.core.HandleAction
import app.chronotif.core.LockShortcut

/**
 * What a Lock-screen shortcut slot or a Handle gesture is set to, picked on one bottom sheet: the
 * built-in actions as tiles with their icons, then every app with its icon, searchable. Picking
 * either closes it. [onPick] gets the action, and for [HandleAction.APP] the app. A Lock-screen
 * shortcut's choices are the [HandleAction]s with the same keys.
 */
internal class ShortcutPicker(
    private val activity: Activity,
    private val c: M3,
    title: String,
    choices: List<HandleAction>,
    private val current: HandleAction,
    private val currentApp: String?,
    lens: Boolean,
    private val onPick: (HandleAction, Launchable?) -> Unit,
) {
    private val dialog = Dialog(activity)
    private val actions: View
    private val search: EditText
    private val list = ListView(activity)
    private val empty: TextView
    private var apps: List<Launchable> = emptyList()
    private var shown: List<Launchable> = emptyList()

    private val rows = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val row = convert as? AppRow ?: AppRow()
            row.bind(shown[position])
            return row
        }
    }

    private fun dp(v: Int) = activity.dp(v)

    private fun text(s: CharSequence, sp: Float, weight: Int = 400, color: Int = c.text) = TextView(activity).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (weight != 400) typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        setTextColor(color)
    }

    init {
        actions = tiles(choices.filter { it != HandleAction.APP }, lens)
        search = EditText(activity).apply {
            background = null
            hint = activity.getString(R.string.lock_shortcut_search_apps)
            setHintTextColor(c.textSecondary)
            setTextColor(c.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                override fun afterTextChanged(s: Editable?) = filter()
            })
        }
        empty = text(activity.getString(R.string.lock_shortcut_no_apps), 14f, color = c.textSecondary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        list.apply {
            divider = null
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(12))
            adapter = rows
            setOnItemClickListener { _, _, i, _ -> pick(HandleAction.APP, shown[i]) }
        }

        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            val r = dp(28).toFloat()
            background = GradientDrawable().apply {
                setColor(c.drawer)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(View(context).apply {
                background = Ui.rounded(c.outline and 0x00FFFFFF or 0x66000000, dp(2).toFloat())
            }, LinearLayout.LayoutParams(dp(32), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            })
            addView(text(title, 22f).apply { setPadding(dp(24), 0, dp(24), 0) }, Ui.lp().apply { topMargin = dp(16) })
            addView(actions, Ui.lp().apply {
                topMargin = dp(16)
                marginStart = dp(16)
                marginEnd = dp(16)
            })
            addView(text(activity.getString(R.string.lock_shortcut_apps), 14f, weight = 600, color = c.primary).apply {
                setPadding(dp(24), 0, dp(24), 0)
            }, Ui.lp().apply { topMargin = dp(20) })
            addView(searchBar(), LinearLayout.LayoutParams(-1, dp(52)).apply {
                topMargin = dp(10)
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = dp(6)
            })
            addView(FrameLayout(context).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
                empty.visibility = View.GONE
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        // The sheet sits at the bottom, at most most of the screen tall; above the keyboard while searching.
        val root = object : FrameLayout(activity) {
            override fun onMeasure(w: Int, h: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.88f).toInt()
                val lp = sheet.layoutParams as LayoutParams
                lp.topMargin = (MeasureSpec.getSize(h) - paddingTop - paddingBottom - cap).coerceAtLeast(dp(24))
                super.onMeasure(w, h)
            }
        }.apply {
            setOnClickListener { dialog.dismiss() }
            addView(sheet, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, 0)
                sheet.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDecorFitsSystemWindows(false)
            setDimAmount(0.4f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }
        dialog.setOnDismissListener { hideKeyboard() }

        Launchables.load(activity) { all ->
            val picked = if (current == HandleAction.APP) Launchables.find(activity, currentApp) else null
            // The app this slot opens now, then the ones that notified lately, then the rest.
            val first = listOfNotNull(picked?.let { p -> all.firstOrNull { same(it, p) } }) +
                Launchables.suggestions(all).filter { s -> picked == null || !same(s, picked) }
            apps = first + all.filter { a -> first.none { same(it, a) } }
            filter()
        }
    }

    fun show() = dialog.show()

    private fun same(a: Launchable, b: Launchable) = a.component == b.component && a.user == b.user

    private fun isCurrent(a: Launchable): Boolean =
        current == HandleAction.APP && currentApp == Launchables.key(activity, a)

    private fun pick(kind: HandleAction, app: Launchable?) {
        dialog.dismiss()
        onPick(kind, app)
    }

    private fun filter() {
        val q = search.text?.toString().orEmpty()
        shown = if (q.isBlank()) apps else AppMatch.search(apps, q) { it.label }
        rows.notifyDataSetChanged()
        list.setSelection(0)
        empty.visibility = if (shown.isEmpty() && apps.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun searchBar() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.rounded(c.high, dp(26).toFloat())
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(c.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(48), -1).apply { marginStart = dp(4) })
        addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(c.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.pressable(0, dp(20).toFloat(), c.ripple)
            contentDescription = context.getString(R.string.close)
            visibility = View.GONE
            setOnClickListener {
                search.setText("")
                search.clearFocus()
                hideKeyboard()
                actions.visibility = View.VISIBLE
            }
        }
        addView(clear, LinearLayout.LayoutParams(dp(48), -1).apply { marginEnd = dp(4) })
        // While searching, the apps take the room the actions had.
        search.setOnFocusChangeListener { _, focused ->
            if (focused) {
                actions.visibility = View.GONE
                clear.visibility = View.VISIBLE
            }
        }
        setOnClickListener {
            search.requestFocus()
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
        }
    }

    /** The built-in actions, three to a row, the one set now filled. */
    private fun tiles(kinds: List<HandleAction>, lens: Boolean): View {
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        kinds.chunked(3).forEachIndexed { r, chunk ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 3) {
                // An empty cell keeps the column width but no height: a bare View would take all the sheet offers.
                val kind = chunk.getOrNull(col)
                val v = kind?.let { tile(it, lens) } ?: View(activity)
                row.addView(v, LinearLayout.LayoutParams(0, if (kind != null) -1 else 0, 1f).apply { if (col > 0) marginStart = dp(8) })
            }
            grid.addView(row, Ui.lp().apply { if (r > 0) topMargin = dp(8) })
        }
        return grid
    }

    private fun tile(kind: HandleAction, lens: Boolean): View {
        val on = kind == current
        val fg = if (on) c.onPrimaryContainer else c.text
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(14), dp(6), dp(12))
            background = c.pressable(if (on) c.primaryContainer else c.high, dp(if (on) 24 else 16).toFloat())
            isSelected = on
            addView(ImageView(context).apply {
                setImageResource(icon(kind) ?: R.drawable.ic_none)
                imageTintList = ColorStateList.valueOf(fg)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text(activity.getString(label(kind)), 13f, weight = 500, color = fg).apply {
                gravity = Gravity.CENTER
                maxLines = 2
            }, Ui.lp().apply { topMargin = dp(8) })
            if (kind == HandleAction.QR_SCANNER && !lens) {
                addView(text(activity.getString(R.string.qr_tile_needs_lens), 11f, color = c.textSecondary).apply {
                    gravity = Gravity.CENTER
                }, Ui.lp().apply { topMargin = dp(2) })
            }
            setOnClickListener { pick(kind, null) }
        }
    }

    /** An app: its icon, its name, and a check when this slot opens it. */
    private inner class AppRow : LinearLayout(activity) {
        val icon = ImageView(activity)
        val name = text("", 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val check = ImageView(activity).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(c.primary)
            scaleType = ImageView.ScaleType.CENTER
        }
        var bound: Launchable? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(24), dp(8), dp(20), dp(8))
            background = Ui.pressable(0, 0f, c.ripple)
            addView(icon, LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
            addView(name, LayoutParams(0, -2, 1f))
            addView(check, LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
        }

        fun bind(a: Launchable) {
            bound = a
            name.text = a.label
            val on = isCurrent(a)
            check.visibility = if (on) View.VISIBLE else View.GONE
            name.typeface = Typeface.create(Typeface.DEFAULT, if (on) 600 else 400, false)
            name.setTextColor(if (on) c.primary else c.text)
            icon.setImageDrawable(null)
            Launchables.icon(activity, a) { d -> if (bound === a) icon.setImageDrawable(RoundedIcon(d)) }
        }
    }

    companion object {
        fun icon(kind: HandleAction): Int? = when (kind) {
            HandleAction.NONE, HandleAction.APP -> null
            HandleAction.PANEL -> R.drawable.ic_section_panel
            HandleAction.SEARCH -> R.drawable.ic_search
            HandleAction.SLEEP -> R.drawable.ic_sleep
            HandleAction.FLASHLIGHT -> R.drawable.ic_flashlight
            HandleAction.CAMERA -> R.drawable.ic_camera
            HandleAction.QR_SCANNER -> R.drawable.ic_qr
            HandleAction.DO_NOT_DISTURB -> R.drawable.ic_dnd
        }

        fun icon(kind: LockShortcut): Int? = icon(action(kind))

        fun label(kind: HandleAction): Int = when (kind) {
            HandleAction.PANEL -> R.string.handle_action_panel
            HandleAction.SEARCH -> R.string.handle_action_search
            HandleAction.SLEEP -> R.string.handle_action_sleep
            else -> LockShortcuts.label(kind.shortcut ?: LockShortcut.NONE)
        }

        fun action(kind: LockShortcut): HandleAction = HandleAction.of(kind.key, HandleAction.NONE)
    }
}
