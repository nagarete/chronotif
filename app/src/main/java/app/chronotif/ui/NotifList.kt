// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.SystemClock
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
import app.chronotif.MediaDismiss
import app.chronotif.NotifStore
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.Viewable
import app.chronotif.core.Attention
import app.chronotif.core.Kind

/**
 * Renders calls, media, NEW / EARLIER and the collapsed Running group into a [LinearLayout], shared by the panel and the
 * lock-screen view. Rows are keyed and views reused, so live updates animate in place
 * instead of rebuilding the list.
 */
class NotifList(
    private val host: Host,
    private val container: LinearLayout,
    private val onLock: Boolean,
    private val mediaDismiss: (Context, Entry) -> Boolean = MediaDismiss::dismiss,
) : CardCallbacks {

    interface Host {
        /** Starts what the user opens; the panel and lock screen may be windows, not activities. */
        val context: Context
        /** Something was launched; get out of the way. */
        fun launched()
        /** Run [action] now, or after the user unlocks (lock screen). */
        fun guarded(action: () -> Unit)
        /** Still showing once [guarded] has unlocked, so Search can stay open rather than reopen. */
        val keepsSearchAfterUnlock: Boolean get() = false
        /** Lock screen only: a reply the app wants unlocked first continues in the panel. */
        fun replyElsewhere(e: Entry, actionIndex: Int) {}
    }

    /**
     * One row of the list. A row with a [sig] (everything its binding depends on) is only bound
     * again when that changes, so reopening a kept panel updates just the cards that changed.
     */
    private class Row(val id: String, val make: () -> View, val sig: (() -> Any?)? = null, val bind: (View) -> Unit)

    private val ctx = container.context
    private val pal = Palette(ctx)
    private val views = HashMap<String, View>()
    /** What each row was last bound with: see [Row.sig]. */
    private val bound = HashMap<String, Any?>()
    private val expanded = HashSet<String>()
    private val openStacks = HashSet<String>()
    private val pendingDismiss = HashSet<String>()
    private var replying: Pair<String, Int>? = null
    private var runningRevealed = false

    /**
     * Lock screen only: room between the clock and New. The lock-screen view sizes it so that,
     * scrolled to the top, New's first card rests at the bottom as the Pile (ADR 0011).
     */
    var gap = 0
        set(v) {
            if (field == v) return
            field = v
            views["sp"]?.let { it.layoutParams = LinearLayout.LayoutParams(-1, v) }
        }

    /**
     * Lock screen only: room above the newest Media player for its artwork, shown large while the
     * list rests (ADR 0012). The lock-screen view sizes it; 0 when there is no artwork to show.
     */
    var artRoom = 0
        set(v) {
            if (field == v) return
            field = v
            views["art"]?.let { it.layoutParams = LinearLayout.LayoutParams(-1, v) }
        }

    /** The row [artRoom] sizes. */
    val artRow: View? get() = views["art"]?.takeIf { it.parent === container }

    /** The player whose artwork shows large: the newest one, on the lock screen, with no call going. */
    val featured: MediaPlayerView?
        get() = (featuredId?.let { views[it] } as? MediaPlayerView)?.takeIf { it.parent === container }
    private var featuredId: String? = null

    /** The row [gap] sizes; the rows after it are the list the Pile unfolds into. */
    val spacer: View? get() = views["sp"]?.takeIf { it.parent === container }

    /** The Pile's card: New's newest card or Conversation stack, or null when nothing is New. */
    val pileTop: View? get() = pileTopId?.let { views[it] }?.takeIf { it.parent === container }
    private var pileTopId: String? = null

    /** Cards or Conversation stacks in New, and cards or stacks in Earlier, as last rendered. */
    var newPieces = 0
        private set
    var earlierPieces = 0
        private set
    /** An open Preview or image viewer: whose notification it shows, and whether an update still matches it. */
    private class Open(val key: String, val overlay: Overlay, val stillShows: (Entry) -> Boolean = { true })

    private val overlays = ArrayList<Open>()

    /** Newest arrival time the user has had on screen; feeds the last-check watermark. */
    var shownUpTo = 0L
        private set

    private val transition = LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        setDuration(200)
    }

    /**
     * The panel animates cards into place. The lock-screen view doesn't: its Pile measures where
     * cards are to fold them, and a card caught mid-animation would throw it off.
     */
    private val animates = !onLock

    init {
        if (animates) container.layoutTransition = transition
    }

    /**
     * Back to how a newly built list starts: cards collapsed, no reply box and Running folded. A panel kept between opens calls this so each open starts the same.
     */
    fun resetUi() {
        expanded.clear()
        openStacks.clear()
        replying = null
        runningRevealed = false
    }

    /** A conversation focuses its latest message card: see [messageCard]. */
    fun focus(key: String, replyIndex: Int) {
        expanded += key
        if (replyIndex >= 0) replying = key to replyIndex
        openStacks += newStackId(key)
        openStacks += earlierStackId(key)
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
        // An open overlay shows only what its card may: it closes once the notification is gone,
        // or the lock screen starts hiding its content.
        overlays.removeAll { open ->
            val now = all.firstOrNull { it.key == open.key }
            when {
                !(open.overlay as View).isAttachedToWindow -> true
                now == null || vis[open.key] == LockPolicy.Vis.REDACT || !open.stillShows(now) -> {
                    open.overlay.close()
                    true
                }
                else -> false
            }
        }
        val lastCheck = Prefs.lastCheck
        val s = Attention.sections(all, lastCheck)
        val running = s.running
        val rows = ArrayList<Row>()
        shownUpTo = 0L

        fun redacted(e: Entry) = vis[e.key] == LockPolicy.Vis.REDACT

        fun card(e: Entry, style: CardView.Style, newSince: Long = Long.MAX_VALUE) {
            shownUpTo = maxOf(shownUpTo, e.alertTime)
            val sig = {
                listOf(e, style, e.key in expanded, replying, newSince, redacted(e), e.key in pendingDismiss,
                    Ui.relTime(ctx, e.alertTime, now), conversationName(e), Apps.generation)
            }
            rows += Row("c:${e.key}", { CardView(ctx, this) }, sig) { v ->
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
        featuredId = null
        if (onLock && s.calls.isEmpty() && s.media.isNotEmpty() && !redacted(s.media[0])) {
            featuredId = "mp:${s.media[0].key}"
            rows += Row("art", { View(ctx) }) { v -> v.layoutParams = LinearLayout.LayoutParams(-1, artRoom) }
        }
        s.media.forEach { player(it) }
        // On the lock screen, the clock's room: the rest of the list unfolds from the Pile below it.
        if (onLock) rows += Row("sp", { View(ctx) }) { v -> v.layoutParams = LinearLayout.LayoutParams(-1, gap) }

        // A conversation that lists its messages is split by message: those since the check
        // stack in New, the rest in Earlier (ADR 0002). Each part sorts by its newest message, the
        // time its card shows: apps quietly repost old chats, which moves the notification's own time.
        val fresh = ArrayList<Pair<Long, List<Row>>>()
        var newCount = 0
        val earlier = ArrayList<Piece>()
        for (e in s.fresh) {
            val c = Content.of(ctx, e, redacted(e))
            val incoming = c.messages.filter { it.incoming }
            if (incoming.isEmpty()) {
                val before = rows.size
                card(e, CardView.Style.FULL, newSince = lastCheck)
                fresh += e.alertTime to listOf(rows.removeAt(before))
                newCount++
                continue
            }
            val newIds = incoming.takeLast(Attention.newMessages(incoming.map { it.time }, lastCheck)).map { it.id }.toSet()
            val hidden = NotifStore.hiddenMessages(e.key)
            val shown = incoming.filter { it.id !in hidden }
            val (new, old) = shown.partition { it.id in newIds }
            if (new.isNotEmpty()) {
                val before = rows.size
                conversation(newStackId(e.key), e, c, new, CardView.Style.FULL, rows, now)
                val part = ArrayList<Row>()
                while (rows.size > before) part += rows.removeAt(before)
                fresh += new.last().time to part
                newCount++
            }
            if (old.isNotEmpty()) earlier += Piece(old.last().time, e, c, old) { style ->
                conversation(earlierStackId(e.key), e, c, old, style, rows, now)
            }
        }
        newPieces = newCount
        pileTopId = null
        if (fresh.isNotEmpty()) {
            rows += header("h:new", ctx.getString(R.string.section_new) + "  $newCount", null)
            val sorted = fresh.sortedByDescending { it.first }
            pileTopId = sorted[0].second.firstOrNull()?.id
            sorted.forEach { rows += it.second }
        }

        for (e in s.earlier) {
            val c = Content.of(ctx, e, redacted(e))
            val hidden = NotifStore.hiddenMessages(e.key)
            val shown = c.messages.filter { it.incoming && it.id !in hidden }
            if (c.messages.any { it.incoming }) {
                if (shown.isNotEmpty()) earlier += Piece(shown.last().time, e, c, shown) { style ->
                    conversation(earlierStackId(e.key), e, c, shown, style, rows, now)
                }
            } else earlier += Piece(e.alertTime, e, c, null) { style -> card(e, style) }
        }

        earlierPieces = earlier.size
        if (earlier.isNotEmpty()) {
            val earlierAll = s.earlier
            rows += header("h:earlier", ctx.getString(R.string.section_earlier),
                clear = earlierAll.takeIf { l -> l.any { it.clearable } })
            // One app's neighbouring cards that arrived close together fold into a stack (ADR 0004).
            // A conversation with several messages is already its own stack, so it stands alone.
            val stacks = Attention.appStacks(earlier.sortedByDescending { it.time }, time = { it.time },
                app = { p -> p.e.appId.takeIf { p.msgs == null || p.msgs.size == 1 } })
            for (stack in stacks) {
                if (stack.size == 1) stack[0].draw(CardView.Style.RECEDED)
                else appStack(stack, rows, now)
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

    /** A conversation's title can come from its shortcut, which the ranking carries rather than the notification. */
    private fun conversationName(e: Entry): Any? = if (android.os.Build.VERSION.SDK_INT >= 31) {
        e.ranking.conversationShortcutInfo?.let { listOf(it.id, it.longLabel, it.shortLabel) }
    } else {
        null
    }

    private fun newStackId(key: String) = "new|$key"
    private fun earlierStackId(key: String) = "earlier|$key"

    /**
     * One Earlier card or Conversation stack, at the time it shows; [msgs] is null for a plain
     * notification. [draw] adds its rows in the given style.
     */
    private class Piece(
        val time: Long, val e: Entry, val c: Content, val msgs: List<Content.Msg>?, val draw: (CardView.Style) -> Unit,
    ) {
        val id get() = msgs?.singleOrNull()?.let { "${e.key}#${it.id}" } ?: e.key
    }

    /**
     * One app's neighbouring Earlier cards, collapsed to the newest with the rest peeking out underneath,
     * or opened in place under the app's name. A reply being typed in one keeps it open.
     */
    private fun appStack(stack: List<Piece>, rows: MutableList<Row>, now: Long) {
        val top = stack[0]
        val stackId = "app|${top.id}"
        val entries = stack.map { it.e }
        val replyingHere = replying?.first.let { r -> r != null && stack.any { r == it.e.key || r.startsWith("${it.e.key}#") } }
        if (stackId in openStacks || replyingHere) {
            rows += stackHeader("sh:$stackId", Apps.label(ctx, top.e.pkg), entries.any { it.clearable },
                close = {
                    openStacks -= stackId
                    if (replyingHere) replying = null
                    render()
                }) {
                openStacks -= stackId
                dismissAll(entries)
            }
            stack.forEach { it.draw(CardView.Style.FULL) }
            return
        }
        entries.forEach { shownUpTo = maxOf(shownUpTo, it.alertTime) }
        rows += Row("as:$stackId", { StackView(ctx, this) }) { v ->
            v as StackView
            v.bind(stack.size)
            v.card.showCount(0)
            v.card.tapOverride = {
                openStacks += stackId
                render()
            }
            v.card.swipeAllowed = { entries.any { it.clearable } }
            v.card.onSwiped = { dismissAll(entries) }
            val msg = top.msgs?.single()
            if (msg != null) {
                v.card.bind(top.e, top.c.forMessage(msg), CardView.Style.RECEDED, false, -1, now, id = "$stackId#top", at = msg.time)
            } else {
                v.card.bind(top.e, top.c, CardView.Style.RECEDED, false, -1, now, id = "$stackId#top")
            }
            if (top.e.key !in pendingDismiss) v.card.swipe.reset(v.card)
            v.layoutParams = gap(8)
        }
    }

    /**
     * A Conversation stack of [msgs] (oldest first): one card for a single message; otherwise
     * collapsed to the latest with a count, or opened into one card per message, newest first.
     */
    private fun conversation(
        stackId: String, e: Entry, c: Content, msgs: List<Content.Msg>, style: CardView.Style,
        rows: MutableList<Row>, now: Long,
    ) {
        shownUpTo = maxOf(shownUpTo, e.alertTime)
        val latest = msgs.last()
        // The conversation's newest shown message answers a focus (reply handoff), even if a newer one was hidden.
        val hidden = NotifStore.hiddenMessages(e.key)
        val newest = c.messages.lastOrNull { it.incoming && it.id !in hidden }?.id
        // A reply being typed keeps its card on screen when another message turns it into a stack.
        val replyingHere = replying?.first.let { r -> r == e.key || msgs.any { r == "${e.key}#${it.id}" } }
        when {
            msgs.size == 1 -> rows += messageCard(stackId, e, c, latest, latest.id == newest, style, now)
            stackId in openStacks || replyingHere -> {
                val close = {
                    openStacks -= stackId
                    if (replyingHere) replying = null
                    render()
                }
                rows += stackHeader("sh:$stackId", c.title, e.clearable, close) {
                    openStacks -= stackId
                    dismissAll(listOf(e))
                }
                msgs.asReversed().forEach { rows += messageCard(stackId, e, c, it, it.id == newest, CardView.Style.FULL, now) }
            }
            else -> rows += Row("cs:$stackId", { StackView(ctx, this) }) { v ->
                v as StackView
                v.bind(msgs.size)
                v.card.showCount(msgs.size)
                v.card.tapOverride = {
                    openStacks += stackId
                    render()
                }
                // The whole conversation is one notification: swiping the stack clears it.
                v.card.swipeAllowed = { e.clearable }
                v.card.onSwiped = { dismissAll(listOf(e)) }
                v.card.bind(e, c.forMessage(latest), style, false, -1, now, id = "$stackId#top", at = latest.time)
                // The thumbnail is the latest message's photo; "+n" counts the others inside.
                if (latest.attachment != null) v.card.showMorePhotos(msgs.count { it.attachment != null } - 1)
                if (e.key !in pendingDismiss) v.card.swipe.reset(v.card)
                v.layoutParams = gap(8)
            }
        }
    }

    /** One message's card. The conversation's latest also answers to its key (reply handoff, [focus]). */
    private fun messageCard(
        stackId: String, e: Entry, c: Content, m: Content.Msg, latest: Boolean, style: CardView.Style, now: Long,
    ): Row {
        val id = "${e.key}#${m.id}"
        val sig = {
            listOf(e, c.redacted, m.id, m.time, latest, style, id in expanded, e.key in expanded, replying,
                Ui.relTime(ctx, m.time, now), Apps.generation)
        }
        return Row("m:$stackId#${m.id}", { CardView(ctx, this) }, sig) { v ->
            v as CardView
            val open = id in expanded || (latest && e.key in expanded)
            val reply = replying?.takeIf { it.first == id || (latest && it.first == e.key) }?.second ?: -1
            // Android can't drop one message from a notification, so only Chronotif hides it.
            v.swipeAllowed = { true }
            v.onSwiped = { NotifStore.hideMessage(e.key, m.id) }
            v.showCount(0)
            v.bind(e, c.forMessage(m), style, open, reply, now, id = id, at = m.time)
            v.swipe.reset(v)
            v.layoutParams = gap(8)
        }
    }

    // --- Card callbacks ---------------------------------------------------------------

    override fun onTap(e: Entry, c: Content) {
        if (e.n.contentIntent == null) {
            if (!c.redacted) onToggleExpand(e, e.key)
            return
        }
        host.guarded { if (Act.open(host.context, e)) host.launched() }
    }

    override fun onToggleExpand(e: Entry, id: String) {
        // A latest message card can be open under the notification's key (focus); closing closes both.
        val wasOpen = expanded.remove(id) or (id.startsWith("${e.key}#") && expanded.remove(e.key))
        if (!wasOpen) expanded += id
        else if (replying?.first == id || replying?.first == e.key) replying = null
        render()
    }

    override fun onAction(e: Entry, id: String, index: Int) {
        val a = e.n.actions?.getOrNull(index) ?: return
        if (Act.replyInput(a) != null) {
            if (onLock && Act.needsUnlock(a)) {
                host.guarded { host.replyElsewhere(e, index) }
            } else {
                expanded += id
                replying = id to index
                render()
            }
            return
        }
        runAction(a)
    }

    /** On the lock screen, a button that works in the background runs without unlocking. */
    private fun runAction(a: android.app.Notification.Action) {
        if (onLock && !Act.needsUnlock(a)) {
            Act.action(host.context, a)
            return
        }
        host.guarded { if (Act.action(host.context, a)) host.launched() }
    }

    override fun onSendReply(e: Entry, id: String, index: Int, text: CharSequence) {
        val a = e.n.actions?.getOrNull(index) ?: return
        Act.reply(host.context, a, text)
        replying = null
        expanded -= id
        expanded -= e.key
        render()
    }

    override fun onDismiss(e: Entry) {
        if (e.kind != Kind.MEDIA) {
            dismissAll(listOf(e))
            return
        }
        // Recheck the current session before attempting the system dismissal:
        // playback may have resumed after the swipe began.
        if (!mediaDismiss(ctx, e)) {
            render()
            return
        }
        // The listener must confirm removal. If Android refuses to cancel the
        // notification, restore the card instead of leaving a phantom dismissal.
        pendingDismiss += e.key
        container.postDelayed({
            pendingDismiss -= e.key
            if (NotifStore.get(e.key) != null) render()
        }, 900)
    }

    /** The notification as it is now: apps repost with fresh buttons, and an old one may no longer work. */
    private fun live(e: Entry) = NotifStore.get(e.key) ?: e

    override fun onViewImages(anchor: View, e: Entry, items: List<Viewable>, start: Int) {
        val viewer = ImageViewer.show(anchor, items, start, object : ImageViewer.Actions {
            override fun share(start: () -> Unit) = host.guarded(start)

            override fun launched() = host.launched()

            override fun openInApp() = host.guarded { if (Act.open(host.context, live(e))) host.launched() }
        }) ?: return
        overlays += Open(e.key, viewer)
    }

    override fun onPreview(anchor: View, e: Entry, c: Content, at: Long) {
        // A message's Preview lasts while that message does (new ones may arrive meanwhile);
        // any other notification's, while what it shows is unchanged.
        val message = c.messages.singleOrNull()?.id
        val shown = Content.signature(e.n)
        val card = anchor as? CardView
        val stillShows: (Entry) -> Boolean =
            if (message != null) { now -> message in Content.incomingIds(now.n) }
            else { now -> Content.signature(now.n) == shown }
        val preview = CardPreview.show(anchor, e, c, at, object : CardPreview.Actions {
            override fun open() = onTap(live(e), c)

            override fun action(index: Int) {
                val a = live(e).n.actions?.getOrNull(index) ?: return
                runAction(a)
            }

            override fun canReplyHere(index: Int) = !onLock || live(e).n.actions?.getOrNull(index)?.let(Act::needsUnlock) == false

            override fun replyElsewhere(index: Int) = host.guarded { host.replyElsewhere(live(e), index) }

            override fun sendReply(index: Int, text: CharSequence) {
                val a = live(e).n.actions?.getOrNull(index) ?: return
                Act.reply(host.context, a, text)
            }

            override fun viewImages(anchor: View, items: List<Viewable>, start: Int) = onViewImages(anchor, e, items, start)

            // Clear does what swiping the card would: a message card hides that message, a
            // collapsed stack clears all of it.
            override fun canClear() = card?.swipeAllowed?.invoke() == true

            override fun clear() {
                card?.onSwiped?.invoke()
            }

            override fun canSnooze() = e.kind == Kind.NOTIFICATION && e.clearable

            override fun snooze(until: Long) = Act.snooze(live(e), until)
        }) ?: return
        overlays += Open(e.key, preview, stillShows)
    }

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

    /** An open stack's title, with Show less ([close]) and × ([clear]). Tapping the title closes it too. */
    private fun stackHeader(
        id: String, title: CharSequence, clearable: Boolean, close: () -> Unit, clear: () -> Unit,
    ) = Row(id, {
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
        (v.getChildAt(0) as TextView).text = title
        v.setOnClickListener { close() }
        v.getChildAt(1).setOnClickListener { close() }
        v.getChildAt(2).apply {
            visibility = if (clearable) View.VISIBLE else View.GONE
            setOnClickListener { clear() }
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

    /** Rows still to be placed by [place], on a later frame. */
    private var resume: Runnable? = null

    /** Keyed diff: reuse views by id, insert/move/remove only what changed. */
    private fun reconcile(rows: List<Row>) {
        resume?.let(container::removeCallbacks)
        resume = null
        val wanted = rows.mapTo(HashSet()) { it.id }
        for (i in container.childCount - 1 downTo 0) {
            val v = container.getChildAt(i)
            val id = v.tag as? String
            if (id !in wanted) {
                container.removeViewAt(i)
                views.remove(id)
                bound.remove(id)
            }
        }
        // A whole list arriving at once doesn't animate in card by card.
        if (views.isEmpty()) container.layoutTransition = null
        place(rows, 0)
    }

    /**
     * Places [rows] from [from] on. A phone with a hundred cards in Earlier would hold the list
     * back for most of a second if they were all made, or even all updated, at once. So once a
     * screenful is up, the rest are made and updated a few per frame; until then, cards further
     * down keep what they showed before.
     */
    private fun place(rows: List<Row>, from: Int) {
        val start = SystemClock.uptimeMillis()
        for (i in from until rows.size) {
            val r = rows[i]
            var v = views[r.id]
            if (i >= FIRST_SCREEN_ROWS && i > from && SystemClock.uptimeMillis() - start >= FRAME_BUDGET_MS) {
                resume = Runnable {
                    resume = null
                    place(rows, i)
                }.also(container::postOnAnimation)
                return
            }
            if (v == null) v = r.make().also { it.tag = r.id; views[r.id] = it }
            val sig = r.sig?.invoke()
            if (sig == null || bound[r.id] != sig || !bound.containsKey(r.id)) {
                r.bind(v)
                bound[r.id] = sig
            }
            if (container.getChildAt(i) !== v) {
                if (v.parent === container) {
                    container.removeView(v)
                    container.endViewTransition(v)
                }
                container.addView(v, i)
            }
        }
        if (animates) container.layoutTransition = transition
    }

    private companion object {
        /** Rows made at once, whatever they cost: enough to fill the screen. */
        const val FIRST_SCREEN_ROWS = 12
        /** Time spent making rows per frame after that. */
        const val FRAME_BUDGET_MS = 8L
    }
}
