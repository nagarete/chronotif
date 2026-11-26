// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import kotlin.math.abs

/**
 * A way to touch the edge handle that the user can give a [HandleAction] (ADR 0022). [key] is what's
 * saved and what Usage data sends, so it never changes. Holding and then dragging moves the handle
 * and isn't one of these. [default] is a new install's (ADR 0024); [before] is what an install from
 * before 0.3.3 keeps.
 */
enum class HandleGesture(val key: String, val default: HandleAction, val before: HandleAction) {
    TAP("tap", HandleAction.SEARCH, HandleAction.PANEL),
    DOUBLE_TAP("double_tap", HandleAction.NONE, HandleAction.NONE),
    HOLD("hold", HandleAction.SLEEP, HandleAction.SEARCH),
    SWIPE_DOWN("swipe_down", HandleAction.PANEL, HandleAction.PANEL),
    SWIPE_UP("swipe_up", HandleAction.NONE, HandleAction.NONE),
    SWIPE_INWARD("swipe_inward", HandleAction.SEARCH, HandleAction.PANEL);

    companion object {
        /**
         * The swipe a finger made, once it has moved [trigger] from where it went down: its longer
         * direction decides. Outward has no room past the screen edge, so it's no gesture (null).
         */
        fun swipe(dx: Float, dy: Float, right: Boolean, trigger: Float): HandleGesture? {
            if (abs(dx) < trigger && abs(dy) < trigger) return null
            if (abs(dy) >= abs(dx)) return if (dy > 0) SWIPE_DOWN else SWIPE_UP
            val inward = if (right) -dx else dx
            return if (inward > 0) SWIPE_INWARD else null
        }
    }
}

/**
 * What a [HandleGesture] does: the Panel, Search, Sleep, one of a Lock-screen shortcut's actions,
 * or nothing. [key] is what's saved and what Usage data sends, so it never changes; the shortcut
 * actions share their [LockShortcut] keys.
 */
enum class HandleAction(val key: String) {
    NONE("none"),
    PANEL("panel"),
    SEARCH("search"),
    SLEEP("sleep"),
    FLASHLIGHT("flashlight"),
    CAMERA("camera"),
    QR_SCANNER("qr"),
    DO_NOT_DISTURB("dnd"),
    /** An app the user picked; which one is saved beside it, never sent. */
    APP("app");

    /** The Lock-screen shortcut that does the same, or null for the handle's own actions. */
    val shortcut: LockShortcut? get() = LockShortcut.entries.firstOrNull { it.key == key && it != LockShortcut.NONE }

    companion object {
        fun of(key: String?, default: HandleAction): HandleAction = entries.firstOrNull { it.key == key } ?: default
    }
}
