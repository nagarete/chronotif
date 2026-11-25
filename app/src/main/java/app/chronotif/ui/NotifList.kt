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
import app.chronotif.CardSource
import app.chronotif.Apps
import app.chronotif.Content
import app.chronotif.Entry
import app.chronotif.LockPolicy
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
    /** Whose notifications these are, and where what the user does with them goes. */
    val source: CardSource = CardSource.Real,
    private val mediaDismiss: (Context, Entry) -> Boolean = source::dismissMedia,
) : CardCallbacks {

    interface Host {
        /** Starts what the user opens; the panel and lock screen may be windows, not activities. */
        val context: Context
        /** Something was launched; get out of the way. */
        fun launched()
        /** Run [action] now, or after the user unlocks (lock screen). */
        fun guarded(action: () -> Unit)
        /**
         * As [guarded], for an [action] that finishes later, such as a copy before a share sheet:
         * the host stays up until the action says it's finished, and closes if it launched something.
         */
        fun guardedUntil(action: (finished: (launched: Boolean) -> Unit) -> Unit) =
            guarded { action { opened -> if (opened) launched() } }
        /** Still showing once [guarded] has unlocked, so Search can stay open rather than reopen. */
        val keepsSearchAfterUnlock: Boolean get() = false
        /** Lock screen only: a reply the app wants unlocked first continues in the panel. */
        fun replyElsewhere(e: Entry, actionIndex: Int) {}
    }

    /**
     * One row of the list. Most rows are bound again on every render. A card is [drawn] from its
     * look alone, and drawn again only when the look changes, so reopening a kept panel updates
     * just the cards that changed.
     */
    private class Row private constructor(
        val id: String, val make: () -> View, val look: (() -> Any)?, val draw: (View, Any?) -> Unit,
    ) {
        companion object {
            operator fun invoke(id: String, make: () -> View, bind: (View) -> Unit) = Row(id, make, null) { v, _ -> bind(v) }

            /**
             * [draw] gets the look that decided the redraw, and the draw functions sit in the
             * companion, where the list's state (what's expanded, being replied to, dismissed) is
             * out of reach. What a card shows from elsewhere, such as the entry's ranking, still
             * has to be put in its look by hand: see [CardLook].
             */
            fun <L : Any> drawn(id: String, make: () -> View, look: () -> L, draw: (View, L) -> Unit) =
                Row(id, make, look) { v, l ->
                    @Suppress("UNCHECKED_CAST")
                    draw(v, l as L)
                }
        }
    }

    private val ctx = container.context
    private val pal = Palette(ctx)
    private val views = HashMap<String, View>()
    /** The look each card was last drawn with: see [Row.drawn]. */
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

    /**
     * The Pile's card: New's newest card or Conversation stack, or empty when nothing is New. Opened,
     * a stack is several rows (its header and one card per message), and all of them stay out of the fold.
     */
    val pile: List<View> get() = pileIds.mapNotNull { views[it]?.takeIf { v -> v.parent === container } }
    private var pileIds: List<String> = emptyList()

    /** Whether the Pile's card is open: a stack opened in place, or a card expanded. */
    var pileOpened = false
        private set
    private var pileOpen: (() -> Unit)? = null
    private var pileClose: (() -> Unit)? = null

    /**
     * Opens the Pile's card in place, as tapping it in the open list would: a Conversation stack
     * opens with its newest message expanded, a card expands. False when there's nothing to open.
     */
    fun openPileCard(): Boolean {
        val open = pileOpen?.takeIf { !pileOpened } ?: return false
        open()
        render()
        return true
    }

    /** Closes the Pile's card back to how it rests. */
    fun closePileCard() {
        val close = pileClose?.takeIf { pileOpened } ?: return
        close()
        render()
    }

    /** What closing one notification's card or stack undoes: opened, expanded, being replied to. */
    private fun closeOf(e: Entry, stackId: String?) = {
        stackId?.let { openStacks -= it }
        expanded.removeAll { it == e.key || it.startsWith("${e.key}#") }
        if (replying?.first.let { it == e.key || it?.startsWith("${e.key}#") == true }) replying = null
    }

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
        val all = source.visible().filter { e ->
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
        val lastCheck = source.lastCheck
        val s = Attention.sections(all, lastCheck)
        val running = s.running
        val rows = ArrayList<Row>()
        shownUpTo = 0L

        fun redacted(e: Entry) = vis[e.key] == LockPolicy.Vis.REDACT

        fun card(e: Entry, style: CardView.Style, newSince: Long = Long.MAX_VALUE) {
            shownUpTo = maxOf(shownUpTo, e.alertTime)
            val look = {
                val r = redacted(e)
                CardLook(e, style, open = e.key in expanded && !r,
                    reply = replying?.takeIf { it.first == e.key }?.second ?: -1, newSince, r,
                    dismissing = e.key in pendingDismiss, ago = Ui.relTime(ctx, e.alertTime, now),
                    name = conversationName(e), icons = Apps.generation).also { it.now = now }
            }
            rows += Row.drawn("c:${e.key}", { CardView(ctx, this) }, look, ::drawCard)
        }

        fun player(e: Entry) {
            rows += Row("mp:${e.key}", { MediaPlayerView(ctx, this) }) { v ->
                v as MediaPlayerView
                v.bind(e, Content.of(ctx, e, redacted(e)))
                if (e.key !in pendingDismiss) v.swipe.reset(v)
                v.layoutParams = gap(8)
            }
        }

        if (!source.connected) {
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
        class Fresh(val time: Long, val rows: List<Row>, val opened: Boolean, val open: (() -> Unit)?, val close: () -> Unit)
        val fresh = ArrayList<Fresh>()
        var newCount = 0
        val earlier = ArrayList<Piece>()
        for (e in s.fresh + s.earlier) {
            val c = Content.of(ctx, e, redacted(e))
            val hidden = source.hiddenMessages(e.key)
            val incoming = c.messages.filter { it.incoming }
            if (incoming.isEmpty()) {
                when (cardPlace(e, c, hidden, lastCheck)) {
                    true -> {
                        val before = rows.size
                        card(e, CardView.Style.FULL, newSince = lastCheck)
                        val canOpen = !c.redacted && (c.expandable || !e.n.actions.isNullOrEmpty())
                        fresh += Fresh(e.alertTime, listOf(rows.removeAt(before)), e.key in expanded,
                            { expanded += e.key }.takeIf { canOpen }, closeOf(e, null))
                        newCount++
                    }
                    false -> earlier += Piece(e.alertTime, e, c, null, whole = true) { style -> card(e, style) }
                    null -> {}
                }
                continue
            }
            val split = Attention.split(e, lastCheck, incoming, time = { it.time }, hidden = { it.id in hidden })
            val new = split.new
            val old = split.earlier
            if (new.isNotEmpty()) {
                val before = rows.size
                // Clearing New's stack clears the notification, its Earlier part with it (ADR 0002).
                conversation(newStackId(e.key), e, c, new, CardView.Style.FULL, rows, now, e.clearable) { dismissAll(listOf(e)) }
                val part = ArrayList<Row>()
                while (rows.size > before) part += rows.removeAt(before)
                val stackId = newStackId(e.key)
                val one = new.singleOrNull()?.let { "${e.key}#${it.id}" }
                val replyingHere = replying?.first.let { r -> r == e.key || new.any { r == "${e.key}#${it.id}" } }
                // New holds the conversation's newest shown message, which also answers to its key (focus).
                val opened = if (one != null) one in expanded || e.key in expanded
                    else stackId in openStacks || replyingHere
                // A stack opens with its newest message expanded, the one woken up for.
                val open = if (one != null) ({ expanded += one }) else ({
                    openStacks += stackId
                    expanded += e.key
                    source.noticed(CardSource.Noticed.STACK_OPENED)
                })
                fresh += Fresh(new.last().time, part, opened, open, closeOf(e, stackId))
                newCount++
            }
            if (old.isNotEmpty()) earlier += Piece(old.last().time, e, c, old, whole = new.isEmpty()) { style ->
                conversation(earlierStackId(e.key), e, c, old, style, rows, now, canClear) { clear(listOf(this)) }
            }
        }
        newPieces = newCount
        pileIds = emptyList()
        pileOpened = false
        pileOpen = null
        pileClose = null
        if (fresh.isNotEmpty()) {
            rows += header("h:new", ctx.getString(R.string.section_new) + "  $newCount", null)
            val sorted = fresh.sortedByDescending { it.time }
            sorted[0].let { top ->
                pileIds = top.rows.map { it.id }
                pileOpened = top.opened
                pileOpen = top.open
                pileClose = top.close
            }
            sorted.forEach { rows += it.rows }
        }

        earlierPieces = earlier.size
        if (earlier.isNotEmpty()) {
            // Clear acts on what Earlier shows, the Earlier part of a conversation that is also in New included.
            rows += header("h:earlier", ctx.getString(R.string.section_earlier),
                clear = { clear(earlier) }.takeIf { earlier.any { it.canClear } })
            // One app's neighbouring cards that arrived close together fold into a stack (ADR 0004).
            // A conversation with several messages is already its own stack, so it stands alone.
            val stacks = Attention.appStacks(earlier.sortedByDescending { it.time }, time = { it.time },
                app = { p -> p.e.appId.takeIf { p.msgs == null || p.msgs.size == 1 } })
            for (stack in stacks) {
                if (stack.size == 1) stack[0].show(CardView.Style.RECEDED)
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
                    clear = { dismissAll(running) }.takeIf { running.any { it.clearable } })
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
     * Where a card without messages of its own goes: New (true), Earlier (false) or nowhere (null).
     * A conversation whose content is hidden on the lock screen is one card, which follows the
     * messages swiped away in the Panel: it isn't New once its New messages are gone.
     */
    private fun cardPlace(e: Entry, c: Content, hidden: Set<String>, lastCheck: Long): Boolean? {
        if (!c.redacted || hidden.isEmpty()) return Attention.isNew(e, lastCheck)
        val msgs = Content.incoming(e.n)
        if (msgs.isEmpty()) return Attention.isNew(e, lastCheck)
        val split = Attention.split(e, lastCheck, msgs, time = { it.time }, hidden = { it.id in hidden })
        return when {
            split.new.isNotEmpty() -> true
            split.earlier.isNotEmpty() -> false
            else -> null
        }
    }

    /**
     * One Earlier card or Conversation stack, at the time it shows; [msgs] is null for a plain
     * notification. [whole] is true when it is everything its notification shows: false for the
     * Earlier part of a conversation that also has messages in New. [draw] adds its rows in the given style.
     */
    private class Piece(
        val time: Long, val e: Entry, val c: Content, val msgs: List<Content.Msg>?, val whole: Boolean,
        val draw: Piece.(CardView.Style) -> Unit,
    ) {
        val id get() = msgs?.singleOrNull()?.let { "${e.key}#${it.id}" } ?: e.key
        /** Hiding messages needs nothing from the app; clearing the notification needs it to be clearable. */
        val canClear get() = !whole || e.clearable

        fun show(style: CardView.Style) = draw(this, style)
    }

    /**
     * Clears what [pieces] show. A piece that is all of its notification clears the notification.
     * The Earlier part of a conversation also in New hides just its messages, so clearing Earlier
     * never takes New messages with it (ADR 0019).
     */
    private fun clear(pieces: List<Piece>) {
        for (p in pieces) if (!p.whole) p.msgs?.forEach { source.hideMessage(p.e.key, it.id) }
        val whole = pieces.filter { it.whole }.map { it.e }
        if (whole.isNotEmpty()) dismissAll(whole)
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
            rows += stackHeader("sh:$stackId", Apps.label(ctx, top.e.pkg), stack.any { it.canClear },
                close = {
                    openStacks -= stackId
                    if (replyingHere) replying = null
                    render()
                    source.noticed(CardSource.Noticed.STACK_CLOSED)
                }) {
                openStacks -= stackId
                clear(stack)
            }
            stack.forEach { it.show(CardView.Style.FULL) }
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
                source.noticed(CardSource.Noticed.STACK_OPENED)
            }
            v.card.swipeAllowed = { stack.any { it.canClear } }
            v.card.onSwiped = { clear(stack) }
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
     * Swiping the collapsed stack, or ×, runs [clear] when [canClear].
     */
    private fun conversation(
        stackId: String, e: Entry, c: Content, msgs: List<Content.Msg>, style: CardView.Style,
        rows: MutableList<Row>, now: Long, canClear: Boolean, clear: () -> Unit,
    ) {
        shownUpTo = maxOf(shownUpTo, e.alertTime)
        val latest = msgs.last()
        // The conversation's newest shown message answers a focus (reply handoff), even if a newer one was hidden.
        val hidden = source.hiddenMessages(e.key)
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
                    source.noticed(CardSource.Noticed.STACK_CLOSED)
                }
                rows += stackHeader("sh:$stackId", c.title, canClear, close) {
                    openStacks -= stackId
                    clear()
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
                    source.noticed(CardSource.Noticed.STACK_OPENED)
                }
                v.card.swipeAllowed = { canClear }
                v.card.onSwiped = clear
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
        val look = {
            MessageLook(e, c.redacted, m.id, m.time, style, open = id in expanded || (latest && e.key in expanded),
                reply = replying?.takeIf { it.first == id || (latest && it.first == e.key) }?.second ?: -1,
                ago = Ui.relTime(ctx, m.time, now), name = conversationName(e), icons = Apps.generation)
                .also {
                    it.now = now
                    it.content = c
                    it.message = m
                }
        }
        // The row's id holds the notification's key and the message's, so the view keeps its message.
        val make = {
            CardView(ctx, this).apply {
                // Android can't drop one message from a notification, so only Chronotif hides it.
                swipeAllowed = { true }
                onSwiped = { source.hideMessage(e.key, m.id) }
            }
        }
        return Row.drawn("m:$stackId#${m.id}", make, look, ::drawMessage)
    }

    // --- Card callbacks ---------------------------------------------------------------

    override fun onTap(e: Entry, c: Content) {
        if (e.n.contentIntent == null) {
            if (!c.redacted) onToggleExpand(e, e.key)
            return
        }
        host.guarded { if (source.open(host.context, e)) host.launched() }
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
        runAction(e, a)
    }

    /** On the lock screen, a button that works in the background runs without unlocking. */
    private fun runAction(e: Entry, a: android.app.Notification.Action) {
        if (onLock && !Act.needsUnlock(a)) {
            source.action(host.context, e, a)
            return
        }
        host.guarded { if (source.action(host.context, e, a)) host.launched() }
    }

    override fun onSendReply(e: Entry, id: String, index: Int, text: CharSequence) {
        val a = e.n.actions?.getOrNull(index) ?: return
        source.reply(host.context, e, a, text)
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
            if (source.get(e.key) != null) render()
        }, 900)
    }

    /** The notification as it is now: apps repost with fresh buttons, and an old one may no longer work. */
    private fun live(e: Entry) = source.get(e.key) ?: e

    override fun onViewImages(anchor: View, e: Entry, items: List<Viewable>, start: Int) {
        val viewer = ImageViewer.show(anchor, items, start, object : ImageViewer.Actions {
            override fun share(start: (finished: (launched: Boolean) -> Unit) -> Unit) = host.guardedUntil(start)

            override fun openInApp() = host.guarded { if (source.open(host.context, live(e))) host.launched() }
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
                val now = live(e)
                val a = now.n.actions?.getOrNull(index) ?: return
                runAction(now, a)
            }

            override fun canReplyHere(index: Int) = !onLock || live(e).n.actions?.getOrNull(index)?.let(Act::needsUnlock) == false

            override fun replyElsewhere(index: Int) = host.guarded { host.replyElsewhere(live(e), index) }

            override fun sendReply(index: Int, text: CharSequence) {
                val now = live(e)
                val a = now.n.actions?.getOrNull(index) ?: return
                source.reply(host.context, now, a, text)
            }

            override fun viewImages(anchor: View, items: List<Viewable>, start: Int) = onViewImages(anchor, e, items, start)

            // Clear does what swiping the card would: a message card hides that message, a
            // collapsed stack clears what it shows.
            override fun canClear() = card?.swipeAllowed?.invoke() == true

            override fun clear() {
                card?.onSwiped?.invoke()
            }

            override fun canSnooze() = e.kind == Kind.NOTIFICATION && e.clearable

            override fun snooze(until: Long) = source.snooze(live(e), until)
        }) ?: return
        overlays += Open(e.key, preview, stillShows)
        source.noticed(CardSource.Noticed.PREVIEW)
    }

    private fun dismissAll(entries: List<Entry>) {
        val keys = entries.filter { it.clearable }.map { it.key }
        pendingDismiss += keys
        source.dismissAll(entries)
        // If the system refused (or we lost the listener), bring the cards back.
        container.postDelayed({
            pendingDismiss -= keys.toSet()
            if (keys.any { source.get(it) != null }) render()
        }, 900)
    }

    // --- Rows -------------------------------------------------------------------------

    /** A section's title, with Clear when there is something to [clear]. */
    private fun header(id: String, label: String, clear: (() -> Unit)?) = Row(id, {
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
            setOnClickListener { clear?.invoke() }
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
            val look = r.look?.invoke()
            if (look == null || bound[r.id] != look) {
                r.draw(v, look)
                bound[r.id] = look
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

    /**
     * Everything a notification's card shows. [e] compares as the same object, and ranking
     * updates change it in place, so anything a card shows from the ranking has to be in the
     * look too, as the conversation's [name] is. [ago] is the time as shown; [now] only feeds
     * it, so it stays out of equality.
     */
    private data class CardLook(
        val e: Entry, val style: CardView.Style, val open: Boolean, val reply: Int, val newSince: Long,
        val redacted: Boolean, val dismissing: Boolean, val ago: String, val name: Any?, val icons: Int,
    ) {
        var now = 0L
    }

    /**
     * Everything a message's card shows: as [CardLook], for message [msg] at [at]. [content] and
     * [message] are what the entry, [redacted] and [msg] give, built once for the whole
     * conversation; like [now], they stay out of equality.
     */
    private data class MessageLook(
        val e: Entry, val redacted: Boolean, val msg: String, val at: Long, val style: CardView.Style,
        val open: Boolean, val reply: Int, val ago: String, val name: Any?, val icons: Int,
    ) {
        var now = 0L
        lateinit var content: Content
        lateinit var message: Content.Msg
    }

    private companion object {
        /** Rows made at once, whatever they cost: enough to fill the screen. */
        const val FIRST_SCREEN_ROWS = 12
        /** Time spent making rows per frame after that. */
        const val FRAME_BUDGET_MS = 8L

        fun drawCard(v: View, l: CardLook) {
            v as CardView
            v.bind(l.e, Content.of(v.context, l.e, l.redacted), l.style, l.open, l.reply, l.now, l.newSince)
            if (!l.dismissing) v.swipe.reset(v)
            v.layoutParams = cardGap(v)
        }

        fun drawMessage(v: View, l: MessageLook) {
            v as CardView
            v.showCount(0)
            v.bind(l.e, l.content.forMessage(l.message), l.style, l.open, l.reply, l.now, id = "${l.e.key}#${l.msg}", at = l.at)
            v.swipe.reset(v)
            v.layoutParams = cardGap(v)
        }

        fun cardGap(v: View) = Ui.lp().apply { topMargin = v.dp(8) }
    }
}
