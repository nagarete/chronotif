// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.Act
import app.chronotif.Apps
import app.chronotif.Content
import app.chronotif.Entry
import app.chronotif.LockPolicy
import app.chronotif.NotifStore
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.core.Attention
import app.chronotif.core.Stack

/**
 * Renders calls, media, NEW / EARLIER and the collapsed Running group into a [LinearLayout], shared by the panel and the
 * lock-screen view. Rows are keyed and views reused, so live updates animate in place
 * instead of rebuilding the list.
 */
class NotifList(
    private val host: Host,
    private val container: LinearLayout,
    private val onLock: Boolean,
) : CardCallbacks {

    interface Host {
        /** Starts what the user opens; the panel and lock screen may be windows, not activities. */
        val context: Context
        /** Something was launched; get out of the way. */
        fun launched()
        /** Run [action] now, or after the user unlocks (lock screen). */
        fun guarded(action: () -> Unit)
        /** Lock screen only: replying needs the keyboard, so continue in the panel. */
        fun replyElsewhere(e: Entry, actionIndex: Int) {}
    }

    private class Row(val id: String, val make: () -> View, val bind: (View) -> Unit)

    private val ctx = container.context
    private val pal = Palette(ctx)
    private val views = HashMap<String, View>()
    private val expanded = HashSet<String>()
    private val openStacks = HashSet<String>()
    private val pendingDismiss = HashSet<String>()
    private var replying: Pair<String, Int>? = null
    private var earlierRevealed = !onLock
    private var runningRevealed = false

    /** Newest arrival time the user has had on screen; feeds the last-check watermark. */
    var shownUpTo = 0L
        private set

    init {
        container.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(200)
        }
    }

    fun focus(key: String, replyIndex: Int) {
        expanded += key
        if (replyIndex >= 0) replying = key to replyIndex
        NotifStore.get(key)?.let { openStacks += it.stackId }
    }

    fun render() {
        val now = System.currentTimeMillis()
        val vis = HashMap<String, LockPolicy.Vis>()
        val all = NotifStore.visible().filter { e ->
            if (!onLock) return@filter true
            val v = LockPolicy.visibility(ctx, e)
            vis[e.key] = v
            v != LockPolicy.Vis.HIDE
        }
        val lastCheck = Prefs.lastCheck
        val s = Attention.sections(all, lastCheck)
        // The lock-screen view is about what needs attention; background work stays out of it.
        val running = if (onLock) emptyList() else s.running
        val rows = ArrayList<Row>()
        shownUpTo = 0L

        fun redacted(e: Entry) = vis[e.key] == LockPolicy.Vis.REDACT

        fun card(e: Entry, style: CardView.Style, newSince: Long = Long.MAX_VALUE) {
            shownUpTo = maxOf(shownUpTo, e.alertTime)
            rows += Row("c:${e.key}", { CardView(ctx, this) }) { v ->
                v as CardView
                val r = redacted(e)
                val open = e.key in expanded && !r
                val reply = replying?.takeIf { it.first == e.key }?.second ?: -1
                v.bind(e, Content.of(ctx, e, r), style, open, reply, now, newSince)
                if (e.key !in pendingDismiss) v.swipe.reset(v)
                v.layoutParams = gap(8)
            }
        }

        fun player(e: Entry) {
            rows += Row("mp:${e.key}", { MediaPlayerView(ctx, this) }) { v ->
                v as MediaPlayerView
                v.bind(e, Content.of(ctx, e, redacted(e)))
                if (e.key !in pendingDismiss) v.swipe.reset(v)
                v.layoutParams = gap(8)
            }
        }

        if (!NotifStore.connected) {
            rows += message("m:access", R.string.no_access_title, R.string.no_access_body) {
                host.context.startActivity(Intent(ctx, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                host.launched()
            }
        } else if (s.isEmpty && running.isEmpty()) {
            if (!onLock) rows += message("m:empty", R.string.empty_title, R.string.empty_body)
        }

        // Live state first, like the system shade: calls, then media players.
        s.calls.forEach { card(it, CardView.Style.FULL) }
        s.media.forEach { player(it) }

        if (s.fresh.isNotEmpty()) {
            rows += header("h:new", ctx.getString(R.string.section_new) + "  ${s.fresh.size}", null)
            s.fresh.forEach { card(it, CardView.Style.FULL, newSince = lastCheck) }
        }

        if (s.earlier.isNotEmpty()) {
            val earlierAll = s.earlier.flatMap { it.items }
            if (!earlierRevealed) {
                rows += pill("p:earlier", ctx.getString(R.string.earlier_count, s.earlierCount)) {
                    earlierRevealed = true
                    render()
                }
            } else {
                rows += header("h:earlier", ctx.getString(R.string.section_earlier),
                    clear = earlierAll.takeIf { l -> l.any { it.clearable } })
                for (stack in s.earlier) {
                    when {
                        stack.items.size == 1 -> card(stack.items[0], CardView.Style.RECEDED)
                        stack.id in openStacks -> {
                            rows += stackHeader(stack)
                            stack.items.forEach { card(it, CardView.Style.FULL) }
                        }
                        else -> collapsedStack(stack, rows, now, ::redacted)
                    }
                }
            }
        }

        // Always-running services and other background work: out of the way unless asked for.
        if (running.isNotEmpty()) {
            if (!runningRevealed) {
                rows += pill("p:running", ctx.getString(R.string.running_count, running.size)) {
                    runningRevealed = true
                    render()
                }
            } else {
                rows += header("h:running", ctx.getString(R.string.section_running),
                    clear = running.takeIf { l -> l.any { it.clearable } })
                running.forEach { card(it, CardView.Style.COMPACT) }
            }
        }

        reconcile(rows)
    }

    private fun collapsedStack(
        stack: Stack<Entry>, rows: MutableList<Row>, now: Long, redacted: (Entry) -> Boolean,
    ) {
        stack.items.forEach { shownUpTo = maxOf(shownUpTo, it.alertTime) }
        val top = stack.items[0]
        rows += Row("s:${stack.id}", { StackView(ctx, this) }) { v ->
            v as StackView
            v.bind(stack.items.size)
            v.card.tapOverride = {
                openStacks += stack.id
                render()
            }
            v.card.swipeAllowed = { stack.items.any { it.clearable } }
            v.card.onSwiped = { dismissAll(stack.items) }
            v.card.bind(top, Content.of(ctx, top, redacted(top)), CardView.Style.RECEDED, false, -1, now)
            if (top.key !in pendingDismiss) v.card.swipe.reset(v.card)
            v.layoutParams = gap(8)
        }
    }

    // --- Card callbacks ---------------------------------------------------------------

    override fun onTap(e: Entry, c: Content) {
        if (e.n.contentIntent == null) {
            if (!c.redacted) onToggleExpand(e)
            return
        }
        host.guarded { if (Act.open(host.context, e)) host.launched() }
    }

    override fun onToggleExpand(e: Entry) {
        if (!expanded.remove(e.key)) expanded += e.key else if (replying?.first == e.key) replying = null
        render()
    }

    override fun onAction(e: Entry, index: Int) {
        val a = e.n.actions?.getOrNull(index) ?: return
        if (Act.replyInput(a) != null) {
            if (onLock) {
                host.guarded { host.replyElsewhere(e, index) }
            } else {
                expanded += e.key
                replying = e.key to index
                render()
            }
            return
        }
        host.guarded { if (Act.action(host.context, a)) host.launched() }
    }

    override fun onSendReply(e: Entry, index: Int, text: CharSequence) {
        val a = e.n.actions?.getOrNull(index) ?: return
        Act.reply(host.context, a, text)
        replying = null
        expanded -= e.key
        render()
    }

    override fun onDismiss(e: Entry) = dismissAll(listOf(e))

    private fun dismissAll(entries: List<Entry>) {
        val keys = entries.filter { it.clearable }.map { it.key }
        pendingDismiss += keys
        Act.dismissAll(entries)
        // If the system refused (or we lost the listener), bring the cards back.
        container.postDelayed({
            pendingDismiss -= keys.toSet()
            if (keys.any { NotifStore.get(it) != null }) render()
        }, 900)
    }

    // --- Rows -------------------------------------------------------------------------

    private fun header(id: String, label: String, clear: List<Entry>?) = Row(id, {
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(14), dp(2), dp(6))
            addView(Ui.text(ctx, 13f, pal.onScrimSecondary, bold = true, lines = 1).apply { Ui.shadow(this) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(ctx.getString(R.string.clear)))
        }
    }) { v ->
        v as LinearLayout
        (v.getChildAt(0) as TextView).text = label.uppercase()
        v.getChildAt(1).apply {
            visibility = if (clear != null) View.VISIBLE else View.GONE
            setOnClickListener { clear?.let(::dismissAll) }
        }
        v.layoutParams = gap(0)
    }

    private fun stackHeader(stack: Stack<Entry>) = Row("sh:${stack.id}", {
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(2), dp(2))
            addView(Ui.text(ctx, 17f, pal.onScrim, bold = true, lines = 1).apply { Ui.shadow(this) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(ctx.getString(R.string.show_less)))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(pal.onScrim)
                background = Ui.pressable(pal.pill, dp(15).toFloat(), 0x33FFFFFF)
                setPadding(dp(7), dp(7), dp(7), dp(7))
                contentDescription = ctx.getString(R.string.clear)
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(8) })
        }
    }) { v ->
        v as LinearLayout
        (v.getChildAt(0) as TextView).text = Apps.label(ctx, stack.items[0].pkg)
        v.getChildAt(1).setOnClickListener {
            openStacks -= stack.id
            render()
        }
        v.getChildAt(2).apply {
            visibility = if (stack.items.any { it.clearable }) View.VISIBLE else View.GONE
            setOnClickListener {
                openStacks -= stack.id
                dismissAll(stack.items)
            }
        }
        v.layoutParams = gap(6)
    }

    private fun pill(id: String, label: String, onClick: () -> Unit) = Row(id, {
        LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            addView(chip(""))
        }
    }) { v ->
        v as LinearLayout
        (v.getChildAt(0) as TextView).apply {
            text = label
            setOnClickListener { onClick() }
        }
        v.layoutParams = gap(10)
    }

    private fun message(id: String, title: Int, body: Int, action: (() -> Unit)? = null) = Row(id, {
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(48), 0, dp(48))
            addView(Ui.text(ctx, 17f, pal.onScrim, bold = true).apply { setText(title); Ui.shadow(this) })
            addView(Ui.text(ctx, 15f, pal.onScrimSecondary).apply { setText(body); Ui.shadow(this) },
                Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            if (action != null) {
                addView(chip(ctx.getString(R.string.open_setup)).apply { setOnClickListener { action() } },
                    Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            }
        }
    }) { v -> v.layoutParams = gap(0) }

    private fun chip(label: String) = Ui.text(ctx, 13f, pal.onScrim, bold = true, lines = 1).apply {
        text = label
        gravity = Gravity.CENTER
        background = Ui.pressable(pal.pill, dp(15).toFloat(), 0x33FFFFFF)
        setPadding(dp(12), dp(7), dp(12), dp(7))
    }

    private fun gap(top: Int) = Ui.lp().apply { topMargin = dp(top) }

    private fun dp(v: Int) = ctx.dp(v)

    /** Keyed diff: reuse views by id, insert/move/remove only what changed. */
    private fun reconcile(rows: List<Row>) {
        val wanted = rows.mapTo(HashSet()) { it.id }
        for (i in container.childCount - 1 downTo 0) {
            val v = container.getChildAt(i)
            val id = v.tag as? String
            if (id !in wanted) {
                container.removeViewAt(i)
                views.remove(id)
            }
        }
        rows.forEachIndexed { i, r ->
            val v = views.getOrPut(r.id) { r.make().also { it.tag = r.id } }
            r.bind(v)
            if (container.getChildAt(i) !== v) {
                if (v.parent === container) {
                    container.removeView(v)
                    container.endViewTransition(v)
                }
                container.addView(v, i)
            }
        }
    }
}
