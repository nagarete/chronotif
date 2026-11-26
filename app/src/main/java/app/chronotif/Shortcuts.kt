// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import android.provider.Settings

/** The flashlight, for its Lock-screen shortcut and Handle action: the back camera's torch, as the system tile uses. */
object Torch {
    /** The camera whose torch is the flashlight, or null on a phone without one. */
    fun cameraId(context: Context): String? {
        val cm = context.getSystemService(CameraManager::class.java) ?: return null
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        fun flash(id: String) = runCatching {
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.getOrDefault(false)
        fun back(id: String) = runCatching {
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(false)
        return ids.firstOrNull { flash(it) && back(it) } ?: ids.firstOrNull(::flash)
    }

    /** Turns it on or off; false when the camera is busy (another app filming) or missing. */
    fun set(context: Context, id: String, on: Boolean): Boolean = try {
        context.getSystemService(CameraManager::class.java).setTorchMode(id, on)
        true
    } catch (_: CameraAccessException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

/**
 * Do Not Disturb, for its Lock-screen shortcut and Handle action. Android lets an app with Do Not Disturb access turn
 * it on: only priority interruptions get through, with no end time.
 *
 * From Android 15, an app that targets it can't change the phone's own Do Not Disturb. Its calls
 * turn its own mode on and off instead (an implicit AutomaticZenRule), and a mode the user, a
 * schedule or another app turned on stays on. Only the system and companion apps for a watch or a car
 * are exempt; notification access doesn't count. So the shortcut reads whether any mode is on,
 * and when one Chronotif doesn't own is what keeps it on, says so instead of claiming it's off.
 */
object QuietMode {
    /** What holding the shortcut did. */
    enum class Toggle {
        /** Do Not Disturb turned on, or off. */
        CHANGED,
        /** Android refused: Chronotif has no Do Not Disturb access. */
        NO_ACCESS,
        /** A mode Chronotif doesn't own is on; only Android's settings can turn it off. */
        NOT_OURS,
    }

    /** Whether any mode silences the phone: Chronotif's own, the user's, a schedule's or another app's. */
    fun on(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

    /**
     * Flips it. Turning off asks Android to turn everything off, which before Android 15 does, and
     * from Android 15 turns off only Chronotif's own mode; what stays on after that belongs to
     * someone else.
     */
    fun toggle(context: Context): Toggle {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return Toggle.NO_ACCESS
        val wasOn = on(context)
        val next = if (wasOn) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        if (runCatching { nm.setInterruptionFilter(next) }.isFailure) return Toggle.NO_ACCESS
        return if (wasOn && on(context)) Toggle.NOT_OURS else Toggle.CHANGED
    }

    /**
     * Android's own page for turning a mode off: the Modes list from Android 15 (where Settings
     * sends this action), the Do Not Disturb page before.
     */
    fun openSettings(context: Context): Boolean {
        for (action in listOf(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, Settings.ACTION_SOUND_SETTINGS)) {
            val opened = runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return true
        }
        return false
    }
}

/** Android's own haptic feedback switch: while it's off, Android drops every app's touch vibration. */
object Haptics {
    fun on(context: Context): Boolean =
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
}

/** The camera, for its Lock-screen shortcut and Handle action. */
object Camera {
    /**
     * Opens the phone's camera, in its secure mode while [locked]: it opens over the lock screen and
     * shows only what it takes. With several camera apps and none set as the default, Android would
     * show its chooser, which can't work over the lock screen; the phone's own camera opens instead,
     * as from the stock lock screen. False when no camera would start.
     */
    fun open(context: Context, locked: Boolean): Boolean {
        val action = if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
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
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
