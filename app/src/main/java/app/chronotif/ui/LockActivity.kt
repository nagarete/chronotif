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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.window.OnBackInvokedDispatcher
import app.chronotif.BuildConfig
import app.chronotif.Entry
import app.chronotif.LockSession
import app.chronotif.NotifStore
import app.chronotif.R

/**
 * Shown over the (still locked) keyguard when the screen turns on. NEW notifications are
 * front and centre, EARLIER is folded behind a pill. Content follows the lock-screen privacy
 * rules, and anything beyond dismissing goes through the system unlock first. Tap outside a
 * card or swipe up (or unlock with fingerprint/face) to continue to the phone; back reveals the
 * system lock screen. Left alone, the display sleeps after the keyguard's short timeout, with
 * Chronotif still showing (see [useKeyguardTimeout]).
 */
class LockActivity : Activity(), NotifList.Host {

    override val context: Context get() = this

    private lateinit var scroll: ScrollView
    private lateinit var list: NotifList
    private val main = Handler(Looper.getMainLooper())
    private val render: () -> Unit = { list.render() }
    private var unlocking = false
    private val markSeen = Runnable { LockSession.seen(list.shownUpTo) }

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
        useKeyguardTimeout()
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
        column.addView(date, Ui.lp(-2).apply { topMargin = dp(28) })
        column.addView(clock, Ui.lp(-2))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            // Taps that no card, chip or pill takes (empty space, headers) unlock.
            setOnClickListener { unlock(null) }
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(content)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(14))
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
        column.addView(hint, Ui.lp(-1))
        column.setOnClickListener { unlock(null) }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        list = NotifList(this, content, onLock = true)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter, if (Build.VERSION.SDK_INT >= 33) RECEIVER_EXPORTED else 0)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!unlocking && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            finish()
            return
        }
        NotifStore.addListener(render)
        list.render()
        // Seen long enough to count — committed only if the user goes on to unlock.
        main.postDelayed(markSeen, 1200)
    }

    override fun onPause() {
        super.onPause()
        NotifStore.removeListener(render)
        main.removeCallbacks(markSeen)
        // Unlocked (any method) while we were up: what was shown has now been seen.
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) LockSession.unlocked()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !unlocking) finish()
    }

    override fun onDestroy() {
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
        try {
            val attrs = window.attributes
            WindowManager.LayoutParams::class.java.getField("userActivityTimeout").setLong(attrs, ms)
            window.attributes = attrs
        } catch (e: ReflectiveOperationException) {
            if (BuildConfig.DEBUG) android.util.Log.w("Chronotif", "keyguard timeout unavailable", e)
        }
    }

    /** Asks the system to unlock (PIN/pattern/biometric bouncer), then runs [then]. */
    private fun unlock(then: (() -> Unit)?) {
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

    // --- Swipe up anywhere (once the list can't scroll further) to unlock.

    private var downY = 0f
    private var atBottomOnDown = false
    private var tracker: VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                atBottomOnDown = !scroll.canScrollVertically(1)
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
                if (atBottomOnDown && downY - ev.rawY > dp(110) && vy < -dp(500)) {
                    unlock(null)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        /** AOSP's config_lockScreenDisplayTimeout, if the ROM doesn't expose its own. */
        const val KEYGUARD_TIMEOUT_MS = 10_000L
    }
}
