// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import app.chronotif.GoogleLens
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
 * and a pocket shouldn't turn the flashlight on. The flashlight and Do Not Disturb light up while on.
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
    private val torchId: String? by lazy { Torch.cameraId(activity) }
    private var torchOn = false
    private var covered = false
    private val main = Handler(Looper.getMainLooper())

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            torchOn = enabled
            paint()
        }
    }

    private val quietChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = paint()
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
        icon(left, leftKind, Prefs.lockShortcutLeftApp)
        icon(right, rightKind, Prefs.lockShortcutRightApp)
        paint()
    }

    fun start() {
        if (torchId != null) runCatching {
            activity.getSystemService(CameraManager::class.java).registerTorchCallback(torchCallback, main)
        }
        val filter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(quietChanged, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(quietChanged, filter)
        bind()
    }

    fun stop() {
        runCatching { activity.getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCallback) }
        runCatching { activity.unregisterReceiver(quietChanged) }
    }

    /** Hidden while a Preview, the image viewer or Search is over the view. */
    fun cover(on: Boolean) {
        if (covered == on) return
        covered = on
        paint()
    }

    private fun icon(v: ImageView, kind: LockShortcut, appKey: String?) {
        v.setImageDrawable(null)
        v.imageTintList = null
        val res = when (kind) {
            LockShortcut.NONE -> null
            LockShortcut.FLASHLIGHT -> R.drawable.ic_flashlight
            LockShortcut.CAMERA -> R.drawable.ic_camera
            LockShortcut.QR_SCANNER -> R.drawable.ic_qr
            LockShortcut.DO_NOT_DISTURB -> R.drawable.ic_dnd
            LockShortcut.APP -> {
                val app = Launchables.find(activity, appKey)
                if (app != null) Launchables.icon(activity, app) { v.setImageDrawable(it) }
                null
            }
        }
        if (res != null) v.setImageResource(res)
        v.contentDescription = activity.getString(label(kind))
    }

    private fun paint() {
        paint(left, leftKind)
        paint(right, rightKind)
    }

    private fun paint(v: ImageView, kind: LockShortcut) {
        v.visibility = if (kind == LockShortcut.NONE || covered) View.INVISIBLE else View.VISIBLE
        val lit = when (kind) {
            LockShortcut.FLASHLIGHT -> torchOn
            LockShortcut.DO_NOT_DISTURB -> QuietMode.on(activity)
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
                val id = torchId
                if (id == null || !Torch.set(activity, id, !torchOn)) say(R.string.lock_shortcut_no_flashlight)
            }
            LockShortcut.CAMERA -> camera()
            LockShortcut.QR_SCANNER ->
                if (GoogleLens.installed(activity)) unlock { GoogleLens.open(activity) } else showLensMissing(activity, unlock)
            LockShortcut.DO_NOT_DISTURB -> if (QuietMode.toggle(activity)) paint() else say(R.string.lock_shortcut_no_dnd)
            LockShortcut.APP -> {
                val app = Launchables.find(activity, if (onLeft) Prefs.lockShortcutLeftApp else Prefs.lockShortcutRightApp)
                if (app == null) say(R.string.lock_shortcut_app_gone) else unlock { runCatching { Launchables.launch(activity, app) } }
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

    /**
     * The phone's camera, in its secure mode while locked: it opens over the lock screen and shows
     * only what it takes. With several camera apps and none set as the default, Android would show
     * its chooser, which can't work over the lock screen; the phone's own camera opens instead, as
     * from the stock lock screen.
     */
    private fun camera() {
        val locked = activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val action = if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = activity.packageManager
        val all = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val default = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val pick = all.firstOrNull { it.activityInfo.packageName == default?.packageName && it.activityInfo.name == default.name }
            ?: all.firstOrNull { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            ?: all.firstOrNull()
        if (pick != null) intent.setClassName(pick.activityInfo.packageName, pick.activityInfo.name)
        // MIUI Camera stays over the lock screen only when told it came from there, as its own
        // lock screen tells it; without these it opens and the lock screen returns over it.
        if (locked) intent.putExtra("ShowCameraWhenLocked", true).putExtra("StartActivityWhenLocked", true)
            .putExtra("com.android.systemui.camera_launch_source", "lockscreen_affordance")
        val started = runCatching { activity.startActivity(intent) }.isSuccess
        if (!started) say(R.string.lock_shortcut_no_camera)
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
        /** The button, and the room around its icon. */
        const val SIZE_DP = 60
        private const val PADDING_DP = 15
        /** How far a held button grows, and the light over it, before the hold counts. */
        private const val PRESSED_SCALE = 1.15f

        /** How a button is drawn: its fill, its rim (0 for none), and the light over it while held. */
        private class Look(val fill: Int, val rim: Int, val pressed: Int)

        /**
         * Picked by eye on the phones, for now: on HyperOS (a build number starting "OS") a light fill
         * sits best; on the MIUI phone, with a dark wallpaper, it nearly vanished, so elsewhere a darker
         * fill and a light rim keep it visible. The light fill vanishes on any dark wallpaper; a frosted
         * glass button that reads on both is the better fix.
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
