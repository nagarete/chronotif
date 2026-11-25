// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import app.chronotif.Apps
import app.chronotif.Content
import app.chronotif.Entry
import app.chronotif.MediaActionSignal
import app.chronotif.MediaDismiss
import app.chronotif.R
import kotlin.math.abs

/**
 * A media notification drawn as a player, like the system shade's: artwork, title and artist,
 * transport controls and a seek bar, all read live from the app's media session. Apps that
 * don't share a session fall back to their notification actions.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MediaPlayerView(context: Context, private val cb: CardCallbacks) : LinearLayout(context) {

    private val pal = Palette(context)
    private val art = ImageView(context)
    private val artBox = FrameLayout(context)
    private val texts = LinearLayout(context)
    private val badge = ImageView(context)
    private val title = Ui.text(context, 17f, pal.text, bold = true, lines = 1)
    private val artist = Ui.text(context, 14f, pal.textSecondary, lines = 1)
    private val controls = LinearLayout(context)
    private val prev = control(R.drawable.ic_media_prev, R.string.media_previous, 44)
    private val playPause = control(R.drawable.ic_media_play, R.string.media_play, 56)
    private val next = control(R.drawable.ic_media_next, R.string.media_next, 44)
    private val seekRow = LinearLayout(context)
    private val elapsed = Ui.text(context, 12f, pal.textSecondary, lines = 1)
    private val total = Ui.text(context, 12f, pal.textSecondary, lines = 1)
    private val seek = SeekBar(context)

    /**
     * The track's artwork, for the lock-screen view to show large: null when content is hidden or
     * the app gives none. The same object until the picture itself changes.
     */
    var artwork: Drawable? = null
        private set
    private var artSource: Any? = null

    /** The thumbnail the large artwork shrinks into. */
    val thumb: android.view.View get() = art

    private var entry: Entry? = null
    private var content: Content? = null
    private var controller: MediaController? = null
    /** The session's last state and track, as its callback reported them, so drawing and touch don't ask it again. */
    private var playback: PlaybackState? = null
    private var meta: MediaMetadata? = null
    /** Whether the callback and ticker run: only while the player can be seen (ADR 0009 keeps a hidden Panel). */
    private var listening = false
    /** [MediaActionSignal]'s answer for this notification, worked out on the first swipe that needs it. */
    private var playSignal: Boolean? = null
    private var seeking = false
    private var swipeInterceptArmed = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    val swipe = SwipeDismiss(::canSwipe, { entry?.let(cb::onDismiss) })

    /**
     * Runs on every touch move, so it reads only what this view already holds. Dismissal itself
     * re-reads the live notification and playback state ([MediaDismiss.dismiss]).
     */
    private fun canSwipe(): Boolean {
        val e = entry ?: return false
        return MediaDismiss.canSwipe(e, playback, context) {
            playSignal ?: MediaActionSignal.hasPlayWithoutPause(e.n, context).also { playSignal = it }
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playback = state
            refresh()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            meta = metadata
            refresh()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (playing()) postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(12))
        background = Ui.pressable(pal.card, dp(22).toFloat(), pal.ripple)

        art.scaleType = ImageView.ScaleType.CENTER_CROP
        Ui.clipRounded(art, dp(14).toFloat())
        Ui.clipRounded(badge, dp(6).toFloat())
        artBox.apply {
            addView(art, FrameLayout.LayoutParams(dp(64), dp(64)))
            addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, -dp(4), -dp(4))
            })
            clipChildren = false
        }
        texts.apply {
            orientation = VERTICAL
            addView(title)
            addView(artist, Ui.lp().apply { topMargin = dp(4) })
        }
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(artBox, Ui.lp(dp(64), dp(64)).apply { marginEnd = dp(14) })
            addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })

        controls.orientation = HORIZONTAL
        controls.gravity = Gravity.CENTER
        addView(controls, Ui.lp().apply { topMargin = dp(10) })

        seek.progressTintList = ColorStateList.valueOf(pal.text)
        seek.thumbTintList = ColorStateList.valueOf(pal.text)
        seek.progressBackgroundTintList = ColorStateList.valueOf(pal.textSecondary)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = clock(p.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) {
                seeking = true
            }
            override fun onStopTrackingTouch(s: SeekBar) {
                seeking = false
                controller?.transportControls?.seekTo(s.progress.toLong())
            }
        })
        seekRow.orientation = HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        seekRow.addView(elapsed, Ui.lp(dp(44)))
        seekRow.addView(seek, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(total, Ui.lp(dp(44)).apply { marginStart = dp(4) })
        total.gravity = Gravity.END
        addView(seekRow, Ui.lp().apply { topMargin = dp(4) })

        setOnClickListener { val e = entry; val c = content; if (e != null && c != null) cb.onTap(e, c) }
        setOnTouchListener(swipe)
    }

    /**
     * ViewGroup children (media buttons, artwork, titles) can consume DOWN before the
     * parent's OnTouchListener sees it. Track DOWN here and intercept only an actual
     * horizontal swipe; taps still go to children and the seek bar keeps its drag.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = event.rawX
                swipeDownY = event.rawY
                swipeInterceptArmed = !swipe.dismissing && canSwipe() && !touchesSeekBar(event)
                if (swipeInterceptArmed) swipe.onTouch(this, event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeInterceptArmed && canSwipe()) {
                    val dx = event.rawX - swipeDownX
                    val dy = event.rawY - swipeDownY
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeInterceptArmed = false
        }
        return super.onInterceptTouchEvent(event)
    }

    private fun touchesSeekBar(event: MotionEvent): Boolean {
        if (seekRow.visibility != VISIBLE || seek.visibility != VISIBLE || seek.width == 0) return false
        val screen = IntArray(2)
        seek.getLocationOnScreen(screen)
        return event.rawX >= screen[0] && event.rawX < screen[0] + seek.width &&
            event.rawY >= screen[1] && event.rawY < screen[1] + seek.height
    }

    fun bind(e: Entry, c: Content) {
        if (entry?.key != e.key) swipe.reset(this)
        if (entry?.n !== e.n) playSignal = null
        entry = e
        content = c
        val token = MediaDismiss.mediaToken(e.n)
        if (controller?.sessionToken != token) {
            if (listening) controller?.unregisterCallback(callback)
            controller = token?.let { runCatching { MediaController(context, it) }.getOrNull() }
            if (listening) controller?.registerCallback(callback)
            // The same session keeps these current through its callback.
            readSession()
        }
        refresh()
    }

    private fun readSession() {
        playback = controller?.playbackState
        meta = controller?.metadata
    }

    /**
     * Seen or not, counting its window and every parent: a closed Panel is kept but hidden
     * (ADR 0009) and never detaches this view, and the lock screen's window hides when the
     * screen goes off. Hidden, the player stops its ticker and its session callback.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) listen() else stopListening()
    }

    override fun onDetachedFromWindow() {
        stopListening()
        super.onDetachedFromWindow()
    }

    private fun listen() {
        if (listening) return
        listening = true
        controller?.registerCallback(callback)
        // What changed while it was hidden.
        readSession()
        refresh()
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        controller?.unregisterCallback(callback)
        removeCallbacks(ticker)
    }

    /** For tests: whether the session callback and ticker are running. */
    internal val isListening get() = listening

    private fun refresh() {
        val e = entry ?: return
        val c = content ?: return
        val meta = meta
        val redacted = c.redacted

        badge.setImageDrawable(Apps.icon(context, e))
        val bitmap = if (redacted) null else meta?.let(::artBitmap)
        val artwork = if (redacted) null else bitmap?.let { keep(it) } ?: Apps.largeIcon(context, e)?.let { keep(it) }
        if (artwork == null) { this.artwork = null; artSource = null }
        art.setImageDrawable(artwork ?: Apps.icon(context, e))
        badge.visibility = if (artwork != null) VISIBLE else GONE

        title.text = if (redacted) c.appName else meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: c.title
        val by = if (redacted) null else meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: c.text
        artist.text = by
        artist.visibility = if (by.isNullOrEmpty()) GONE else VISIBLE

        bindControls(e)

        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        seekRow.visibility = if (duration > 0 && !redacted) VISIBLE else GONE
        seek.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        seek.isEnabled = (playback?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
        total.text = clock(duration)
        tick()

        removeCallbacks(ticker)
        if (playing() && listening) postDelayed(ticker, 1000)
    }

    private fun bindControls(e: Entry) {
        controls.removeAllViews()
        val ctl = controller
        if (ctl == null) {
            // No session to drive: offer the notification's own actions, as icons.
            e.n.actions.orEmpty().take(5).forEachIndexed { i, a ->
                controls.addView(ImageView(context).apply {
                    setImageDrawable(Apps.actionIcon(context, a))
                    imageTintList = ColorStateList.valueOf(pal.text)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = Ui.pressable(0, dp(22).toFloat(), pal.ripple)
                    contentDescription = a.title
                    setOnClickListener { cb.onAction(e, e.key, i) }
                }, Ui.lp(dp(44), dp(44)).apply { marginStart = dp(8); marginEnd = dp(8) })
            }
            controls.visibility = if (controls.childCount > 0) VISIBLE else GONE
            return
        }
        controls.visibility = VISIBLE
        val actions = playback?.actions ?: 0L
        val playing = playing()
        playPause.setImageResource(if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play)
        playPause.contentDescription = context.getString(if (playing) R.string.media_pause else R.string.media_play)
        playPause.setOnClickListener {
            if (playing()) ctl.transportControls.pause() else ctl.transportControls.play()
        }
        prev.setOnClickListener { ctl.transportControls.skipToPrevious() }
        next.setOnClickListener { ctl.transportControls.skipToNext() }
        enable(prev, actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        enable(next, actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        controls.addView(prev, Ui.lp(dp(44), dp(44)))
        controls.addView(playPause, Ui.lp(dp(56), dp(56)).apply { marginStart = dp(28); marginEnd = dp(28) })
        controls.addView(next, Ui.lp(dp(44), dp(44)))
    }

    /** Moves the seek bar along without re-reading everything else. */
    private fun tick() {
        if (seeking) return
        val state = playback ?: return
        val duration = seek.max.toLong()
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0, duration.coerceAtLeast(0))
        seek.progress = position.toInt()
        elapsed.text = clock(position)
    }

    private fun playing(): Boolean = when (playback?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
        else -> false
    }

    private fun artBitmap(meta: MediaMetadata): Bitmap? =
        meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    /**
     * Keeps [artwork] the same object while the picture is the same: sessions hand out a fresh
     * copy of the bitmap with every update, and the lock-screen view redraws its backdrop on change.
     */
    private fun keep(source: Any): Drawable {
        val old = artSource
        val same = old === source || (old is Bitmap && source is Bitmap && !old.isRecycled &&
            old.width == source.width && old.height == source.height && old.sameAs(source))
        if (!same || artwork == null) {
            artSource = source
            artwork = if (source is Bitmap) BitmapDrawable(resources, source) else source as Drawable
        }
        return artwork!!
    }

    /**
     * How much of the player the lock-screen view's large artwork stands in for: at 1 the
     * thumbnail is hidden and the titles move into its place, at 0 the player looks as usual.
     */
    fun feature(f: Float) {
        artBox.alpha = if (f > 0f) 0f else 1f
        texts.translationX = -f * (artBox.width + dp(14)) * (if (layoutDirection == LAYOUT_DIRECTION_RTL) -1 else 1)
    }

    private fun control(icon: Int, label: Int, size: Int) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(pal.text)
        val pad = dp(if (size > 44) 14 else 10)
        setPadding(pad, pad, pad, pad)
        background = Ui.pressable(if (size > 44) pal.chip else 0, dp(size / 2).toFloat(), pal.ripple)
        contentDescription = context.getString(label)
    }

    private fun enable(v: ImageView, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.35f
    }

    private fun clock(ms: Long) = DateUtils.formatElapsedTime(ms / 1000)
}
