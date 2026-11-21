// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import app.chronotif.core.FingerprintSpot

/** Finds an under-display fingerprint sensor for the lock-screen view (see [FingerprintSpot]). */
object FingerprintSensor {
    /** The screen's height in pixels, portrait, at its current resolution. */
    fun screenHeight(context: Context): Int {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return maxOf(b.width(), b.height())
    }

    /** What the phone says about where the sensor's top edge is, when it says anything. */
    private fun exact(context: Context): Float? {
        val system = Resources.getSystem()
        val id = system.getIdentifier("config_udfps_sensor_props", "array", "android")
        val aosp = if (id != 0) runCatching { system.getIntArray(id) }.getOrNull() else null
        val xiaomi = property("persist.vendor.sys.fp.fod.location.X_Y")
        if ((aosp == null || aosp.isEmpty()) && xiaomi.isNullOrEmpty()) return null
        // Both are in the panel's native pixels, whatever resolution the phone is set to.
        val native = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?.supportedModes?.maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) } ?: screenHeight(context)
        return FingerprintSpot.fromUdfpsProps(aosp, native) ?: FingerprintSpot.fromXiaomi(xiaomi, native)
    }

    private fun property(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    /** Whether the phone has a sensor under the screen, also when it doesn't say where. */
    fun present(context: Context): Boolean =
        exact(context) != null ||
            // HyperOS and MIUI keep the sensor's animation here, on phones with an in-screen sensor.
            runCatching { Settings.System.getString(context.contentResolver, "fod_animation_type") }.getOrNull() != null

    /** The sensor's top edge as a fraction of the screen's height, or null with no sensor there. */
    fun top(context: Context): Float? =
        FingerprintSpot.top(Prefs.fingerprintTop.takeIf { it > 0f }, exact(context), present(context))
}
