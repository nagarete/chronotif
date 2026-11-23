// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** The flashlight, for its Lock-screen shortcut: the back camera's torch, as the system tile uses. */
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
 * Do Not Disturb, for its Lock-screen shortcut. Android lets an app with notification access set
 * it, as the system tile does: on lets only priority interruptions through, with no end time.
 */
object QuietMode {
    fun on(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

    /** Flips it; false when Android refuses (notification access missing). */
    fun toggle(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return false
        val next = if (on(context)) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        return runCatching { nm.setInterruptionFilter(next) }.isSuccess
    }
}

/** Android's own haptic feedback switch: while it's off, Android drops every app's touch vibration. */
object Haptics {
    fun on(context: Context): Boolean =
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
}
