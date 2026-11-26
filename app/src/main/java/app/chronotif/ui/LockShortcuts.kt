// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import app.chronotif.Background
import app.chronotif.GoogleLens
import app.chronotif.Launchable
import app.chronotif.Launchables
import app.chronotif.Prefs
import app.chronotif.QuietMode
import app.chronotif.R
import app.chronotif.Torch
import app.chronotif.Tutorial
import app.chronotif.TutorialSource
import app.chronotif.UsageData
import app.chronotif.core.LockShortcut
import app.chronotif.core.Usage

/**
 * The two Lock-screen shortcuts, either side of Tap to unlock. Holding one uses it, with the long
 * press's haptic; a tap only says to hold, since a tap on the lock-screen view otherwise unlocks
 * and a pocket shouldn't turn the flashlight on. The flashlight lights up while on, and Do Not
 * Disturb while any mode is on, whoever turned it on.
 * On the Tutorial's stand-in, holding one says what it would do and does nothing.
 */
internal class LockShortcuts(
    private val activity: Activity,
    private val practice: Boolean,
    /** Asks to unlock, then runs the action (see LockActivity.unlock). */
    private val unlock: (() -> Unit) -> Unit,
) {
    val left: ImageView = button()
    val right: ImageView = button()
    private var leftKind = LockShortcut.NONE
    private var rightKind = LockShortcut.NONE
    /** The flashlight's camera, once [Background] has looked it up. */
    @Volatile private var torchId: String? = null
    private var torchOn = false
    private var dndOn = false
    /** What [start] listens to, so [stop] undoes only that. */
    private var torchListening = false
    private var quietListening = false
    /** Counts [bind]s, so an app looked up for an earlier one doesn't land on a changed slot. */
    private var binds = 0
    private var covered = false
    private val main = Handler(Looper.getMainLooper())
    private val appContext: Context = activity.applicationContext

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            torchOn = enabled
            paint()
        }
    }

    private val quietChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readQuiet()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Only shows the press; clicks stay with the view.
    private fun button(): ImageView = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(PADDING_DP)
        setPadding(pad, pad, pad, pad)
        Ui.clipOval(this)
        visibility = View.INVISIBLE
        setOnClickListener { Toast.makeText(activity, R.string.lock_shortcut_hold, Toast.LENGTH_SHORT).show() }
        // The long press's own haptic doesn't come through on HyperOS; [haptic] gives a firm click instead.
        isHapticFeedbackEnabled = false
        setOnLongClickListener {
            haptic()
            fired(this)
            val onLeft = this === this@LockShortcuts.left
            use(if (onLeft) leftKind else rightKind, onLeft)
            true
        }
        // While held, the button brightens and grows until the hold counts, so a press shows even with
        // haptic feedback off; when it counts, it settles back.
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> held(v, true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> held(v, false)
            }
            false
        }
    }

    private fun held(v: View, down: Boolean) {
        v.foreground = if (down) Ui.rounded(look.pressed, v.dp(SIZE_DP / 2).toFloat()) else null
        v.animate().cancel()
        if (down) {
            v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                .setDuration(android.view.ViewConfiguration.getLongPressTimeout().toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }
    }

    /** The hold counted: a quick pop back to size. */
    private fun fired(v: View) {
        v.foreground = null
        v.animate().cancel()
        v.animate().scaleX(1f).scaleY(1f).setDuration(120)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }

    private fun haptic() {
        val vibrator = (if (Build.VERSION.SDK_INT >= 31) activity.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else activity.getSystemService(android.os.Vibrator::class.java)) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching { vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK)) }
    }

    /** Reads the slots from Settings. */
    fun bind() {
        leftKind = Prefs.lockShortcutLeft
        rightKind = Prefs.lockShortcutRight
        val bound = ++binds
        icon(left, leftKind, Prefs.lockShortcutLeftApp, bound)
        icon(right, rightKind, Prefs.lockShortcutRightApp, bound)
        paint()
    }

    /**
     * Runs on every wake, so it asks the system for nothing on the main thread. The torch and Do
     * Not Disturb are only listened to when a slot uses them; finding the flashlight lists the
     * phone's cameras, and both registrations call into system services, so that happens on
     * [Background], whose order also keeps [stop]'s unregistering after it.
     */
    fun start() {
        bind()
        val kinds = setOf(leftKind, rightKind)
        if (LockShortcut.FLASHLIGHT in kinds) {
            torchListening = true
            Background.run {
                val id = torchCamera(appContext) ?: return@run
                torchId = id
                runCatching { appContext.getSystemService(CameraManager::class.java).registerTorchCallback(torchCallback, main) }
            }
        }
        if (LockShortcut.DO_NOT_DISTURB in kinds) {
            quietListening = true
            Background.run {
                val filter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(quietChanged, filter, Context.RECEIVER_NOT_EXPORTED)
                    else activity.registerReceiver(quietChanged, filter)
                }
            }
            readQuiet()
        }
    }

    fun stop() {
        if (torchListening) {
            torchListening = false
            Background.run { runCatching { appContext.getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCallback) } }
        }
        if (quietListening) {
            quietListening = false
            Background.run { runCatching { activity.unregisterReceiver(quietChanged) } }
        }
    }

    /** Whether Do Not Disturb is on, asked of the notification service off the main thread. */
    private fun readQuiet() {
        Background.run({ runCatching { QuietMode.on(appContext) }.getOrDefault(false) }) { on ->
            if (dndOn == on) return@run
            dndOn = on
            paint()
        }
    }

    // After a tap, which already asked Android to change it: the button shows the answer at once.
    private fun quietNow() {
        dndOn = runCatching { QuietMode.on(appContext) }.getOrDefault(false)
        paint()
    }

    /** Hidden while a Preview, the image viewer or Search is over the view. */
    fun cover(on: Boolean) {
        if (covered == on) return
        covered = on
        paint()
    }

    private fun icon(v: ImageView, kind: LockShortcut, appKey: String?, bound: Int) {
        v.setImageDrawable(null)
        v.imageTintList = null
        val res = when (kind) {
            LockShortcut.NONE -> null
            LockShortcut.FLASHLIGHT -> R.drawable.ic_flashlight
            LockShortcut.CAMERA -> R.drawable.ic_camera
            LockShortcut.QR_SCANNER -> R.drawable.ic_qr
            LockShortcut.DO_NOT_DISTURB -> R.drawable.ic_dnd
            LockShortcut.APP -> {
                appIcon(v, appKey, bound)
                null
            }
        }
        if (res != null) v.setImageResource(res)
        v.contentDescription = activity.getString(label(kind))
    }

    /**
     * An app slot's icon. Finding the app asks the package manager, so it happens on [Background];
     * the app found on an earlier wake shows meanwhile, and goes if the app has.
     */
    private fun appIcon(v: ImageView, appKey: String?, bound: Int) {
        val known = appKey?.let(found::get)
        if (known != null) Launchables.icon(activity, known) { if (bound == binds) v.setImageDrawable(it) }
        Background.run({ runCatching { Launchables.find(appContext, appKey) }.getOrNull() }) { a ->
            if (bound != binds || appKey == null) return@run
            if (a == null) {
                found.remove(appKey)
                v.setImageDrawable(null)
                return@run
            }
            found[appKey] = a
            if (known?.component != a.component || known.user != a.user) {
                Launchables.icon(activity, a) { if (bound == binds) v.setImageDrawable(it) }
            }
        }
    }

    private fun paint() {
        paint(left, leftKind)
        paint(right, rightKind)
    }

    private fun paint(v: ImageView, kind: LockShortcut) {
        v.visibility = if (kind == LockShortcut.NONE || covered) View.INVISIBLE else View.VISIBLE
        val lit = when (kind) {
            LockShortcut.FLASHLIGHT -> torchOn
            LockShortcut.DO_NOT_DISTURB -> dndOn
            else -> false
        }
        v.background = Ui.rounded(if (lit) 0xFFFFFFFF.toInt() else look.fill, v.dp(SIZE_DP / 2).toFloat()).apply {
            if (!lit && look.rim != 0) setStroke(v.dp(1), look.rim)
        }
        if (kind != LockShortcut.APP) v.imageTintList = ColorStateList.valueOf(if (lit) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        v.isActivated = lit
    }

    private fun use(kind: LockShortcut, onLeft: Boolean) {
        if (practice) {
            Toast.makeText(activity, practiceSays(kind), Toast.LENGTH_LONG).show()
            Tutorial.notice(TutorialSource.Event.SHORTCUT_HELD)
            return
        }
        when (kind) {
            LockShortcut.NONE -> return
            LockShortcut.FLASHLIGHT -> {
                // Held before the lookup in [start] finished: look it up now.
                val id = torchId ?: torchCamera(appContext)
                if (id == null || !Torch.set(activity, id, !torchOn)) say(R.string.lock_shortcut_no_flashlight)
            }
            LockShortcut.CAMERA -> camera()
            LockShortcut.QR_SCANNER ->
                if (GoogleLens.installed(activity)) unlock { GoogleLens.open(activity) } else showLensMissing(activity, unlock)
            LockShortcut.DO_NOT_DISTURB -> when (QuietMode.toggle(activity)) {
                QuietMode.Toggle.CHANGED -> quietNow()
                QuietMode.Toggle.NO_ACCESS -> say(R.string.lock_shortcut_no_dnd)
                QuietMode.Toggle.NOT_OURS -> {
                    quietNow()
                    say(R.string.lock_shortcut_dnd_not_ours)
                    unlock { QuietMode.openSettings(activity) }
                }
            }
            LockShortcut.APP -> {
                val app = Launchables.find(activity, if (onLeft) Prefs.lockShortcutLeftApp else Prefs.lockShortcutRightApp)
                if (app == null) say(R.string.lock_shortcut_app_gone) else unlock { if (!Launchables.launch(activity, app)) say(R.string.lock_shortcut_app_gone) }
            }
        }
        UsageData.count(when (kind) {
            LockShortcut.FLASHLIGHT -> Usage.Count.SHORTCUT_FLASHLIGHT
            LockShortcut.CAMERA -> Usage.Count.SHORTCUT_CAMERA
            LockShortcut.QR_SCANNER -> Usage.Count.SHORTCUT_QR
            LockShortcut.DO_NOT_DISTURB -> Usage.Count.SHORTCUT_DND
            else -> Usage.Count.SHORTCUT_APP
        })
    }

    private fun camera() {
        val locked = activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!app.chronotif.Camera.open(activity, locked)) say(R.string.lock_shortcut_no_camera)
    }

    private fun say(text: Int) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private fun practiceSays(kind: LockShortcut) = activity.getString(when (kind) {
        LockShortcut.FLASHLIGHT -> R.string.tutorial_shortcut_flashlight
        LockShortcut.CAMERA -> R.string.tutorial_shortcut_camera
        LockShortcut.QR_SCANNER -> R.string.tutorial_shortcut_qr
        LockShortcut.DO_NOT_DISTURB -> R.string.tutorial_shortcut_dnd
        else -> R.string.tutorial_shortcut_app
    })

    companion object {
        @Volatile private var torchLooked = false
        @Volatile private var torchFound: String? = null

        /** The flashlight's camera, looked up once per process: listing cameras asks the camera service. */
        private fun torchCamera(context: Context): String? {
            if (!torchLooked) {
                torchFound = Torch.cameraId(context)
                torchLooked = true
            }
            return torchFound
        }

        /** Each app slot's app as last found, by its key; read and written on the main thread. */
        private val found = HashMap<String, Launchable>()

        /** The button, and the room around its icon. */
        const val SIZE_DP = 60
        private const val PADDING_DP = 15
        /** How far a held button grows, and the light over it, before the hold counts. */
        private const val PRESSED_SCALE = 1.15f

        /** How a button is drawn: its fill, its rim (0 for none), and the light over it while held. */
        private class Look(val fill: Int, val rim: Int, val pressed: Int)

        /**
         * On HyperOS (a build number starting "OS") a light fill sits best. On MIUI over a dark
         * wallpaper it nearly vanishes, so elsewhere a darker fill and a light rim keep it visible.
         */
        private val look: Look = if (android.os.Build.VERSION.INCREMENTAL.startsWith("OS")) {
            Look(fill = 0x40000000, rim = 0, pressed = 0x40FFFFFF)
        } else {
            Look(fill = 0x73000000, rim = 0x66FFFFFF, pressed = 0x80FFFFFF.toInt())
        }

        fun label(kind: LockShortcut) = when (kind) {
            LockShortcut.NONE -> R.string.lock_shortcut_none
            LockShortcut.FLASHLIGHT -> R.string.lock_shortcut_flashlight
            LockShortcut.CAMERA -> R.string.lock_shortcut_camera
            LockShortcut.QR_SCANNER -> R.string.qr_scanner
            LockShortcut.DO_NOT_DISTURB -> R.string.lock_shortcut_dnd
            LockShortcut.APP -> R.string.lock_shortcut_app
        }
    }
}
