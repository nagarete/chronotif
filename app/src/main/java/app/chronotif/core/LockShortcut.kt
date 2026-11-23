// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * What a Lock-screen shortcut is set to. The two slots, left and right, each hold one; [key] is
 * what's saved and what Usage data sends, so it never changes.
 */
enum class LockShortcut(val key: String) {
    NONE("none"),
    FLASHLIGHT("flashlight"),
    CAMERA("camera"),
    QR_SCANNER("qr"),
    DO_NOT_DISTURB("dnd"),
    /** An app the user picked; which one is saved beside it, never sent. */
    APP("app");

    companion object {
        val DEFAULT_LEFT = FLASHLIGHT
        val DEFAULT_RIGHT = CAMERA

        fun of(key: String?, default: LockShortcut): LockShortcut = entries.firstOrNull { it.key == key } ?: default

        /**
         * The choices a slot offers while the other slot holds [other]: one thing can't sit in both
         * corners, except None and An app (two different apps are two different shortcuts).
         */
        fun choicesBeside(other: LockShortcut): List<LockShortcut> =
            entries.filter { it == NONE || it == APP || it != other }
    }
}
