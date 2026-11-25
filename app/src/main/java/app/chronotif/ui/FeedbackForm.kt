// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.Feedback
import app.chronotif.R
import app.chronotif.core.FeedbackMessage

/**
 * Settings' Send feedback page: what it's about, the message, a way to reply and the phone's
 * details, then Send. The form lives in [Feedback.Draft], so it outlasts a page switch, and a send
 * that fails leaves it filled in.
 *
 * None of its rows are settings, so none carry a title tag for Settings' search to hold the index to.
 */
class FeedbackForm(private val context: Context, private val c: M3, private val openIssue: () -> Unit) {
    private val draft = Feedback.Draft
    private lateinit var send: TextView
    private lateinit var status: TextView

    /** Adds the page's views to [body]. */
    fun build(body: LinearLayout) {
        body.addView(text(context.getString(R.string.feedback_intro), 13f, secondary = true).apply {
            setPadding(dp(4), 0, dp(4), 0)
        }, gap(12))

        val group = SettingGroup(context, c)
        body.addView(group, gap(16))
        group.addView(row(R.string.feedback_kind, content = kinds()), Ui.lp())
        group.addView(row(R.string.feedback_text, content = field(
            draft.text, R.string.feedback_text_hint, FeedbackMessage.MAX_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            lines = 5,
        ) { draft.text = it }), Ui.lp())
        group.addView(row(R.string.feedback_contact, context.getString(R.string.feedback_contact_desc), content = field(
            draft.contact, R.string.feedback_contact_hint, FeedbackMessage.MAX_CONTACT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            lines = 1,
        ) { draft.contact = it }), Ui.lp())
        group.addView(details(), Ui.lp())

        send = text(context.getString(R.string.feedback_send), 14f, weight = 600).apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(24), 0, dp(24), 0)
            setTextColor(c.onPrimary)
            background = c.pressable(c.primary, dp(20).toFloat())
            setOnClickListener { sendNow() }
        }
        body.addView(send, gap(16).apply { width = -2; marginStart = dp(4) })
        status = text("", 13f, secondary = true)
        body.addView(status, gap(8).apply { marginStart = dp(4); marginEnd = dp(4) })

        body.addView(text(context.getString(R.string.feedback_github), 13f, weight = 500).apply {
            setTextColor(c.primary)
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            background = c.pressable(0, dp(12).toFloat())
            setOnClickListener { openIssue() }
        }, gap(8).apply { width = -2 })
        refresh()
        draft.shown = java.lang.ref.WeakReference(onSendEnded)
    }

    /** Held strongly here and weakly by [Feedback.Draft.shown]: it lives as long as this form. */
    private val onSendEnded: () -> Unit = { refresh(cleared = draft.result == Feedback.Result.SENT) }

    private fun sendNow() {
        if (draft.sending || !FeedbackMessage.ready(draft.text, draft.contact)) return
        draft.sending = true
        draft.result = null
        refresh()
        val kind = draft.kind
        val text = draft.text
        val contact = draft.contact
        fun post(details: List<Pair<String, String>>?) =
            Feedback.send(FeedbackMessage.body(kind, text, contact, details)) { result ->
                draft.sending = false
                draft.result = result
                if (result == Feedback.Result.SENT) draft.clear()
                draft.shown?.get()?.invoke()
            }
        if (draft.withDetails) Feedback.details(::post) else post(null)
    }

    /** Empties each field after a send; [clearing] keeps that from reading as the user editing. */
    private val onCleared = ArrayList<() -> Unit>()
    private var clearing = false

    private fun refresh(cleared: Boolean = false) {
        if (cleared) {
            clearing = true
            onCleared.forEach { it() }
            clearing = false
        }
        val ready = !draft.sending && FeedbackMessage.ready(draft.text, draft.contact)
        send.isEnabled = ready
        send.alpha = if (ready) 1f else 0.5f
        val (line, colour) = when {
            draft.sending -> R.string.feedback_sending to c.textSecondary
            else -> when (draft.result) {
                Feedback.Result.SENT -> R.string.feedback_sent to c.good
                Feedback.Result.OFFLINE -> R.string.feedback_offline to c.error
                Feedback.Result.TOO_MANY -> R.string.feedback_too_many to c.error
                Feedback.Result.FAILED -> R.string.feedback_failed to c.error
                null -> null to c.textSecondary
            }
        }
        status.text = line?.let(context::getString) ?: ""
        status.setTextColor(colour)
        status.visibility = if (line == null) View.GONE else View.VISIBLE
    }

    /** Problem, Idea or Other as chips; optional, and tapping the picked one unpicks it. */
    private fun kinds(): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val names = mapOf(
            FeedbackMessage.Kind.PROBLEM to R.string.feedback_kind_problem,
            FeedbackMessage.Kind.IDEA to R.string.feedback_kind_idea,
            FeedbackMessage.Kind.OTHER to R.string.feedback_kind_other,
        )
        val chips = names.map { (kind, name) ->
            kind to text(context.getString(name), 14f, weight = 500).apply {
                gravity = Gravity.CENTER
                minHeight = dp(40)
                setPadding(dp(16), 0, dp(16), 0)
                compoundDrawablePadding = dp(4)
            }
        }
        val check = context.getDrawable(R.drawable.ic_check)!!.mutate()
        fun style() = chips.forEach { (kind, v) ->
            val on = draft.kind == kind
            v.isSelected = on
            v.background = c.pressable(if (on) c.primary else c.highest, dp(20).toFloat())
            v.setTextColor(if (on) c.onPrimary else c.text)
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(if (on) check else null, null, null, null)
            v.compoundDrawableTintList = ColorStateList.valueOf(c.onPrimary)
        }
        chips.forEachIndexed { i, (kind, v) ->
            v.setOnClickListener {
                draft.kind = if (draft.kind == kind) null else kind
                draft.result = null
                style()
                refresh()
            }
            row.addView(v, Ui.lp(-2).apply { if (i > 0) marginStart = dp(8) })
        }
        style()
        onCleared += { style() }
        return row
    }

    private fun field(value: String, hint: Int, max: Int, type: Int, lines: Int, onChange: (String) -> Unit) =
        EditText(context).apply {
            setText(value)
            this.hint = context.getString(hint)
            inputType = type
            filters = arrayOf(InputFilter.LengthFilter(max))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(c.text)
            setHintTextColor(c.textSecondary)
            gravity = Gravity.TOP or Gravity.START
            if (lines > 1) {
                minLines = lines
                isSingleLine = false
            } else {
                isSingleLine = true
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.rounded(c.highest, dp(12).toFloat())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (clearing) return
                    onChange(s?.toString() ?: "")
                    draft.result = null
                    refresh()
                }
            })
            onCleared += { if (text.isNotEmpty()) setText("") }
        }

    /** The switch that leaves the phone's details in, and the details themselves under it. */
    private fun details(): View {
        val sw = M3Switch(context, c)
        sw.setChecked(draft.withDetails, animate = false)
        val shown = text(context.getString(R.string.feedback_details_loading), 13.5f, secondary = true)
        val row = row(R.string.feedback_details, trailing = sw, content = shown)
        row.accessibilityDelegate = M3Switch.RowAccessibility(sw)
        row.setOnClickListener {
            sw.setChecked(!sw.isChecked, animate = true)
            draft.withDetails = sw.isChecked
            shown.alpha = if (sw.isChecked) 1f else 0.4f
        }
        shown.alpha = if (draft.withDetails) 1f else 0.4f
        Feedback.details { list -> shown.text = list.joinToString("\n") { (k, v) -> "$k: $v" } }
        onCleared += {
            sw.setChecked(draft.withDetails, animate = true)
            shown.alpha = 1f
        }
        return row
    }

    /** A row as Settings draws one: a title, an optional line under it, then [content] or [trailing]. */
    private fun row(title: Int, desc: String? = null, trailing: View? = null, content: View? = null) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            minimumHeight = dp(56)
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context.getString(title), 14f, secondary = true))
                if (desc != null) addView(text(desc, 13f, secondary = true), gap(2))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (trailing != null) addView(trailing, Ui.lp(-2).apply { marginStart = dp(16) })
            }, Ui.lp())
            if (content != null) addView(content, gap(10))
        }

    private fun text(s: CharSequence, sp: Float, weight: Int = 400, secondary: Boolean = false) =
        TextView(context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (weight != 400) typeface = Typeface.create(Typeface.DEFAULT, weight, false)
            setTextColor(if (secondary) c.textSecondary else c.text)
            setLineSpacing(0f, 1.1f)
        }

    private fun gap(top: Int) = Ui.lp().apply { topMargin = dp(top) }

    private fun dp(v: Int) = context.dp(v)
}
