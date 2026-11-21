// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * Where an under-display fingerprint sensor sits, so the lock-screen view can put Tap to unlock's
 * fingerprint icon on it and keep the Pile above. Android has no public call for this. A phone may describe it in
 * AOSP's `config_udfps_sensor_props` (centre x, centre y, radius) or Xiaomi's
 * `persist.vendor.sys.fp.fod.*` properties (top-left corner, then width and height), both in the
 * panel's native pixels, but HyperOS leaves the first empty and keeps the second from apps. It
 * does let apps see that a sensor is there, so Chronotif leaves [DEFAULT_TOP] free until the
 * person touches their sensor once to fit it (Settings → Lock screen).
 *
 * Positions are fractions of the screen's height, so they survive a change of resolution.
 */
object FingerprintSpot {
    /**
     * Where to assume a sensor's top edge starts when only its presence is known. Xiaomi puts
     * them in the bottom eighth or so: on a 17T Pro the top is at 0.87 (2413 of 2772 px).
     */
    const val DEFAULT_TOP = 0.86f

    /** About half a sensor's height, in dp: a fitting touch lands on its centre. */
    const val HALF_DP = 40

    /** AOSP's integer array (centre x, centre y, radius): the top edge as a fraction of [height]. */
    fun fromUdfpsProps(props: IntArray?, height: Int): Float? {
        if (props == null || props.size < 3 || height <= 0) return null
        val (y, r) = props[1] to props[2]
        if (y <= 0 || r <= 0 || y - r <= 0) return null
        return (y - r).toFloat() / height
    }

    /** Xiaomi's "x,y" top-left corner: the top edge as a fraction of [height]. */
    fun fromXiaomi(location: String?, height: Int): Float? {
        val y = location?.split(',')?.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (y <= 0 || height <= 0) return null
        return y.toFloat() / height
    }

    /** The sensor's top edge: what the person fitted, else what the phone says, else a default. */
    fun top(fitted: Float?, exact: Float?, present: Boolean): Float? =
        fitted?.takeIf { it in 0.5f..1f } ?: exact?.takeIf { it in 0.5f..1f } ?: DEFAULT_TOP.takeIf { present }

    /** Where a touch at [y] of [height] puts a sensor's top edge, [half] above the touch. */
    fun fitted(y: Float, height: Int, half: Float): Float? {
        if (height <= 0) return null
        val top = (y - half) / height
        return top.takeIf { it in 0.5f..1f }
    }

    /**
     * The bottom padding that puts Tap to unlock's fingerprint icon on the sensor, so the icon
     * marks where to press and the Pile above it stays clear of the finger: [current] is the
     * padding now, [icon] the icon's centre on screen with it, and [sensor] the sensor's centre.
     * Never less than [min].
     */
    fun hintPadding(current: Int, icon: Int, sensor: Int, min: Int): Int = maxOf(min, current + icon - sensor)
}
