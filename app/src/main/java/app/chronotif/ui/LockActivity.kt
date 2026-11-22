// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.window.OnBackInvokedDispatcher
import app.chronotif.BuildConfig
import app.chronotif.CardSource
import app.chronotif.Entry
import app.chronotif.FingerprintSensor
import app.chronotif.LockSession
import app.chronotif.Prefs
import app.chronotif.Tutorial
import app.chronotif.TutorialSource
import app.chronotif.R
import app.chronotif.core.FingerprintSpot
import kotlin.math.abs

/**
 * Shown over the (still locked) keyguard when the screen turns on. It rests with the clock big
 * and New as a Pile at the bottom (ADR 0011), and a Media player's artwork large between them (ADR 0012); scrolling up unfolds the list, New then Earlier,
 * and a tap on the Pile does the same ([LockPile]). Content follows the lock-screen privacy
 * rules, and anything beyond dismissing goes through the system unlock first. Tap outside a card,
 * swipe up from Tap to unlock (or unlock with fingerprint/face) to continue to the phone, or tap the
 * clock or the date to unlock into the alarms or the calendar; back
 * folds the list back into the Pile, then reveals the system lock screen. Pulling down opens Search. Left alone, the display sleeps after the keyguard's short timeout, with
 * Chronotif still showing (see [useKeyguardTimeout]). Double-tapping empty space turns the screen
 * black and lets Android put the display to sleep (ADR 0014, see [sleep]).
 */
class LockActivity : Activity(), NotifList.Host {

    override val context: Context get() = this

    private lateinit var scroll: ScrollView
    private lateinit var listBox: LinearLayout
    private lateinit var hint: View
    private lateinit var pile: LockPile
    private lateinit var list: NotifList
    private val main = Handler(Looper.getMainLooper())
    private val render: () -> Unit = { list.render() }
    private var unlocking = false
    private val markSeen = Runnable { LockSession.seen(list.shownUpTo) }
    private val shortcuts by lazy { LockShortcuts(this, practice) { then -> unlock(then) } }

    /**
     * The Tutorial's stand-in, on an unlocked phone: it shows the samples and stays up, says what
     * would wait for an unlock instead of asking for one, goes black only for a moment on a double
     * tap, and never touches the real check or the screen timeout.
     */
    private val practice by lazy { intent.getBooleanExtra(EXTRA_PRACTICE, false) && Tutorial.source != null }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Screen off: start fresh next time. Unlocked by biometrics: step aside —
            // unless we asked for the unlock ourselves and still have something to run.
            if (intent.action == Intent.ACTION_SCREEN_OFF || !unlocking) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        Ui.edgeToEdge(window)
        if (!practice) useKeyguardTimeout()
        val pal = Palette(this)

        val root = FrameLayout(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(pal.onScrim)
            setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x40000000)
        }
        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 76f)
            setTextColor(pal.onScrim)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false)
            includeFontPadding = false
            setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x40000000)
        }
        // The clock stays put over the list; the list scrolls up under it (see LockPile).
        val clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(date, Ui.lp(-2).apply { topMargin = dp(28) })
            addView(clock, Ui.lp(-2))
        }
        // As in the Panel header: the clock opens the alarms and the date the calendar, after the unlock.
        with(PanelHeader) {
            clock.tapTarget(this@LockActivity, R.string.header_open_alarms) { alarmsIntents(this@LockActivity) }
            date.tapTarget(this@LockActivity, R.string.header_open_calendar) {
                listOf(calendarIntent(System.currentTimeMillis()))
            }
        }
        this.clockBox = clockBox
        clockFace = listOf(date, clock)
        val clockRoom = View(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Taps that no card, chip or pill takes (empty space, headers) unlock.
            setOnClickListener { tapped() }
            addView(clockRoom, LinearLayout.LayoutParams(-1, 0))
        }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(16))
            setOnClickListener { tapped() }
        }
        content.addView(listBox, LinearLayout.LayoutParams(-1, -2))
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        val stage = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(clockBox, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        column.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_fingerprint)
                imageTintList = ColorStateList.valueOf(pal.onScrimSecondary)
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { bottomMargin = dp(4) })
            addView(Ui.text(context, 13f, pal.onScrimSecondary).apply {
                setText(R.string.tap_to_unlock)
                Ui.shadow(this)
            }, Ui.lp(-2))
            setOnClickListener { unlock(null) }
        }
        // The Tutorial's card, between the list and Tap to unlock.
        if (practice) column.addView(TutorialGuide(this, inPanel = false) { finish() },
            Ui.lp().apply { leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(8) })
        // Tap to unlock between the two Lock-screen shortcuts, all three raised together over a fingerprint sensor.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(6), dp(28), dp(14))
            addView(shortcuts.left, LinearLayout.LayoutParams(dp(LockShortcuts.SIZE_DP), dp(LockShortcuts.SIZE_DP)))
            addView(hint, LinearLayout.LayoutParams(0, -2, 1f))
            addView(shortcuts.right, LinearLayout.LayoutParams(dp(LockShortcuts.SIZE_DP), dp(LockShortcuts.SIZE_DP)))
        }
        column.addView(bar, Ui.lp(-1))
        this.hint = hint
        column.setOnClickListener { tapped() }
        // Blurred artwork in place of the wallpaper while a Media player shows (see LockPile).
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        this.root = root
        // Put Tap to unlock's fingerprint icon on an under-display sensor, the Pile above it.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clearFingerprint(bar, hint) }
        // A Preview, the image viewer or Search over the view hides the shortcuts.
        val decor = window.decorView as android.view.ViewGroup
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            shortcuts.cover((0 until decor.childCount).any { decor.getChildAt(it) is Overlay })
        }

        list = NotifList(this, listBox, onLock = true, source = Tutorial.source.takeIf { practice } ?: CardSource.Real)
        pile = LockPile(stage, scroll, clockRoom, clockBox, listBox, list, backdrop) { openPile() }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter, if (Build.VERSION.SDK_INT >= 33) RECEIVER_EXPORTED else 0)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    /** Unfolds the Pile and scrolls New to the top of the screen. */
    private fun openPile() {
        scroll.smoothScrollTo(0, pile.openAt)
        if (practice) Tutorial.notice(TutorialSource.Event.PILE_TAPPED)
    }

    /** Back folds the open list back into the Pile, then shows the stock lock screen. Not while dark. */
    internal fun back() {
        if (dark != null) return
        if (pile.resting) {
            finish()
            return
        }
        scroll.smoothScrollTo(0, 0)
        if (practice) Tutorial.notice(TutorialSource.Event.PILE_FOLDED)
    }

    // Before Android 13, Back arrives as a key.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33 && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (practice) Tutorial.shown(Tutorial.Surface.LOCK, true)
        shortcuts.start()
    }

    override fun onResume() {
        super.onResume()
        if (!practice && !unlocking && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            finish()
            return
        }
        list.source.addListener(render)
        list.render()
        if (!practice) app.chronotif.UsageData.count(app.chronotif.core.Usage.Count.LOCK_SHOWN)
        // Seen long enough to count — committed only if the user goes on to unlock.
        if (!practice) main.postDelayed(markSeen, 1200)
    }

    override fun onPause() {
        super.onPause()
        list.source.removeListener(render)
        main.removeCallbacks(markSeen)
        // Unlocked (any method) while we were up: what was shown has now been seen.
        if (!practice && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) LockSession.unlocked()
    }

    override fun onStop() {
        super.onStop()
        if (practice) Tutorial.shown(Tutorial.Surface.LOCK, false)
        shortcuts.stop()
        if (!isFinishing && !unlocking) finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(tapUnlocks)
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    // --- Host ---

    override fun launched() = finish()

    override fun guarded(action: () -> Unit) = unlock(action)

    override fun replyElsewhere(e: Entry, actionIndex: Int) {
        Panel.open(this, e.key, actionIndex)
    }

    /**
     * Covering the keyguard switches Android to the normal (long) screen timeout. Giving our
     * window the keyguard's short one keeps a single wake session: Chronotif stays up, then the
     * display sleeps, with no stock lock-screen interval tacked on. Touches still extend it.
     */
    @SuppressLint("DiscouragedApi") // The keyguard timeout has no public resource id.
    private fun useKeyguardTimeout() {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("config_lockScreenDisplayTimeout", "integer", "android")
        val ms = (if (id != 0) res.getInteger(id).toLong() else 0L).takeIf { it > 0 } ?: KEYGUARD_TIMEOUT_MS
        userActivityTimeout(ms)
    }

    /** How long after the last touch the display sleeps while this window shows. Android won't go below its own minimum. */
    private fun userActivityTimeout(ms: Long) {
        try {
            val attrs = window.attributes
            WindowManager.LayoutParams::class.java.getField("userActivityTimeout").setLong(attrs, ms)
            window.attributes = attrs
        } catch (e: ReflectiveOperationException) {
            if (BuildConfig.DEBUG) android.util.Log.w("Chronotif", "screen timeout unavailable", e)
        }
    }

    /** Asks the system to unlock (PIN/pattern/biometric bouncer), then runs [then]. */
    private fun unlock(then: (() -> Unit)?) {
        if (practice) {
            // The phone is unlocked already: say what the real lock screen would do.
            android.widget.Toast.makeText(this, if (then != null) R.string.tutorial_after_unlock else R.string.tutorial_unlock_here,
                android.widget.Toast.LENGTH_SHORT).show()
            if (then != null) Tutorial.notice(TutorialSource.Event.UNLOCK_ASKED)
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            then?.invoke()
            finish()
            return
        }
        unlocking = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                unlocking = false
                LockSession.seen(list.shownUpTo)
                LockSession.unlocked()
                then?.invoke()
                finish()
            }

            override fun onDismissCancelled() {
                unlocking = false
            }

            override fun onDismissError() {
                unlocking = false
            }
        })
    }

    // --- Tap the Pile to open it; swipe up from Tap to unlock to unlock; pull down at the top for Search.

    private var downY = 0f
    private var downAt = 0L
    private var onHint = false
    /** The gesture started on the Pile's card while folded: a tap opens the Pile rather than the app. */
    private var onPile = false
    private var moved = false
    private val slop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }

    private fun cancelChildren(ev: MotionEvent) = MotionEvent.obtain(ev).also {
        it.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(it)
        it.recycle()
    }
    private var tracker: VelocityTracker? = null

    /** Pulling down at the top of the list opens Search; opening an app from it unlocks first. */
    private val pull by lazy {
        PullToSearch(list = scroll, atTop = { !scroll.canScrollVertically(-1) }) {
            SearchOverlay.show(scroll, this)
            list.source.noticed(CardSource.Noticed.SEARCH)
        }
    }

    // --- Double-tap to sleep (ADR 0014).

    private lateinit var root: FrameLayout
    private lateinit var clockBox: View
    /** The date and the clock, which open the calendar and the alarms: never empty space. */
    private lateinit var clockFace: List<View>
    /** The black cover while Android counts down to sleep, or null while the view shows. */
    private var dark: View? = null
    private var downX = 0f
    /** Whether the touch under way started on empty space. */
    private var downEmpty = false
    /** Whether the touch under way is the second tap of a double tap on empty space. */
    private var secondTap = false
    /** A first tap on empty space still waiting for a second: when it ended (uptime) and where, or 0. */
    private var firstTapAt = 0L
    private var firstX = 0f
    private var firstY = 0f
    private val tapUnlocks = Runnable {
        firstTapAt = 0L
        unlock(null)
    }
    /** On the black cover: when the last tap ended, or 0, and whether the touch under way is a second tap. */
    private var darkTapAt = 0L
    private var darkSecond = false
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlop by lazy { ViewConfiguration.get(this).scaledDoubleTapSlop }

    /**
     * Empty space: where a tap means nothing but "unlock". Not a row of the list (cards, players,
     * the artwork's room, headers), the Pile, the clock and date, or Tap to unlock. The blank gap
     * the Pile unfolds from is empty space.
     */
    private fun emptyAt(ev: MotionEvent): Boolean {
        if (onHint || onPile) return false
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        val r = android.graphics.Rect()
        fun on(v: View) = v.isShown && v.getGlobalVisibleRect(r) && r.contains(x, y)
        if (clockBox.alpha > 0f && clockFace.any(::on)) return false
        val gap = list.spacer
        return (0 until listBox.childCount).none { listBox.getChildAt(it).let { row -> row !== gap && on(row) } }
    }

    /**
     * A tap that no card, chip or pill took. On empty space, with double-tap to sleep on, it waits
     * for a second tap before asking to unlock; anywhere else it asks at once.
     */
    private fun tapped() {
        if (!Prefs.lockDoubleTapSleep || !downEmpty) {
            unlock(null)
            return
        }
        if (secondTap) {
            secondTap = false
            sleep()
            return
        }
        firstTapAt = SystemClock.uptimeMillis()
        firstX = downX
        firstY = downY
        main.postDelayed(tapUnlocks, doubleTapTimeout)
    }

    /**
     * Turns the screen black, as dim as it goes and without the system bars, and asks for the
     * shortest screen timeout. Android has no call for an app to put the display to sleep, so it
     * sleeps on its own once its minimum timeout runs out (6 seconds on HyperOS). The screen-off
     * broadcast then closes the view, as after any wake.
     */
    private fun sleep() {
        if (dark != null) return
        if (!practice) app.chronotif.UsageData.count(app.chronotif.core.Usage.Count.DOUBLE_TAP_SLEEP)
        dark = View(this).apply { setBackgroundColor(Color.BLACK) }
            .also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        if (practice) {
            // Only a moment: the phone isn't locked, and the real display would be asleep now.
            main.postDelayed({
                wake()
                android.widget.Toast.makeText(this, R.string.tutorial_slept, android.widget.Toast.LENGTH_LONG).show()
                Tutorial.notice(TutorialSource.Event.SLEPT)
            }, PRACTICE_DARK_MS)
            return
        }
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF }
        userActivityTimeout(1L)
    }

    /** Brings the view back from [sleep], as double tap to wake would. */
    private fun wake() {
        val cover = dark ?: return
        root.removeView(cover)
        dark = null
        window.insetsController?.show(WindowInsets.Type.systemBars())
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        if (!practice) useKeyguardTimeout()
    }

    /** The black cover acts as a sleeping screen: a double tap brings the view back, and nothing else does anything. */
    private fun darkTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> darkSecond = darkTapAt != 0L && ev.eventTime - darkTapAt <= doubleTapTimeout
            MotionEvent.ACTION_UP -> if (darkSecond) {
                darkTapAt = 0L
                wake()
            } else {
                darkTapAt = ev.eventTime
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dark != null) {
            darkTouch(ev)
            return true
        }
        // A Preview or the image viewer scrolls and swipes on its own; swiping up there isn't "unlock".
        val decor = window.decorView as android.view.ViewGroup
        if ((0 until decor.childCount).any { decor.getChildAt(it) is Overlay }) {
            tracker?.recycle()
            tracker = null
            return super.dispatchTouchEvent(ev)
        }
        val pulling = pull.onTouch(ev) { cancelChildren(ev) }
        if (pulling) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                downAt = ev.eventTime
                moved = false
                onPile = pile.hits(ev)
                val r = android.graphics.Rect()
                onHint = hint.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())
                downX = ev.rawX
                downEmpty = emptyAt(ev)
                // A new touch settles a tap still waiting: a second tap close by sleeps, anything else drops it.
                secondTap = firstTapAt != 0L && downEmpty && ev.eventTime - firstTapAt <= doubleTapTimeout &&
                    kotlin.math.hypot(ev.rawX - firstX, ev.rawY - firstY) < doubleTapSlop
                main.removeCallbacks(tapUnlocks)
                firstTapAt = 0L
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (abs(ev.rawY - downY) > slop) moved = true
            }
            MotionEvent.ACTION_UP -> {
                val t = tracker
                t?.addMovement(ev)
                t?.computeCurrentVelocity(1000)
                val vy = t?.yVelocity ?: 0f
                t?.recycle()
                tracker = null
                // A plain tap on the folded Pile opens it; holding it still opens its Preview.
                if (onPile && !moved && ev.eventTime - downAt < android.view.ViewConfiguration.getLongPressTimeout()) {
                    cancelChildren(ev)
                    openPile()
                    return true
                }
                // Swiping up from Tap to unlock unlocks; swiping up on the list scrolls it.
                if (onHint && downY - ev.rawY > dp(60) && vy < -dp(300)) {
                    unlock(null)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * With a fingerprint sensor under the screen, raises Tap to unlock until its fingerprint icon
     * sits on the sensor, and with it the Pile above, so a finger on the sensor touches no card.
     */
    private fun clearFingerprint(bar: LinearLayout, hint: LinearLayout) {
        val top = fingerprintTop ?: return
        val icon = hint.getChildAt(0) ?: return
        if (icon.height == 0) return
        // A little above the sensor's centre, where HyperOS's own icon sits.
        val sensor = (top * FingerprintSensor.screenHeight(this)).toInt() + dp(FingerprintSpot.HALF_DP) - dp(4)
        val at = IntArray(2).also { icon.getLocationOnScreen(it) }
        val bottom = FingerprintSpot.hintPadding(bar.paddingBottom, at[1] + icon.height / 2, sensor, dp(14))
        if (kotlin.math.abs(bar.paddingBottom - bottom) > 1) bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bottom)
    }

    /** The sensor's top edge, read once per view: it doesn't move while the view is up. */
    private val fingerprintTop: Float? by lazy { FingerprintSensor.top(this) }

    companion object {
        /** AOSP's config_lockScreenDisplayTimeout, if the ROM doesn't expose its own. */
        private const val KEYGUARD_TIMEOUT_MS = 10_000L
        private const val EXTRA_PRACTICE = "practice"
        /** How long a practice double tap keeps the screen black. */
        private const val PRACTICE_DARK_MS = 1200L

        /**
         * The lock-screen view as a stand-in for the Tutorial's Lock screen part, while the phone is
         * unlocked: the Tutorial's samples, nothing unlocked, slept or checked.
         */
        fun practice(context: Context): Intent = Intent(context, LockActivity::class.java)
            .putExtra(EXTRA_PRACTICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
