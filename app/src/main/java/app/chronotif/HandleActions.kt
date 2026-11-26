// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.chronotif.core.HandleAction
import app.chronotif.core.HandleGesture
import app.chronotif.core.Usage
import app.chronotif.ui.Panel
import app.chronotif.ui.SearchActivity
import app.chronotif.ui.SleepActivity

/**
 * Runs the Handle action a Handle gesture is set to (ADR 0022). The handle only shows while the
 * phone is unlocked, so nothing here asks to unlock first, unlike the Lock-screen shortcuts.
 */
object HandleActions {
    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, gesture: HandleGesture) {
        val action = Prefs.handleAction(gesture)
        when (action) {
            HandleAction.NONE -> return
            HandleAction.PANEL -> runCatching { Panel.open(context) }
            HandleAction.SEARCH -> runCatching { SearchActivity.open(context) }
            HandleAction.SLEEP -> runCatching { SleepActivity.open(context) }
            HandleAction.FLASHLIGHT -> flashlight(context.applicationContext)
            HandleAction.CAMERA -> if (!Camera.open(context, locked = false)) say(context, R.string.lock_shortcut_no_camera)
            HandleAction.QR_SCANNER -> if (!GoogleLens.open(context)) say(context, R.string.handle_qr_needs_lens)
            HandleAction.DO_NOT_DISTURB -> when (QuietMode.toggle(context)) {
                QuietMode.Toggle.CHANGED -> Unit
                QuietMode.Toggle.NO_ACCESS -> say(context, R.string.lock_shortcut_no_dnd)
                QuietMode.Toggle.NOT_OURS -> {
                    say(context, R.string.lock_shortcut_dnd_not_ours)
                    QuietMode.openSettings(context)
                }
            }
            HandleAction.APP -> {
                val app = Launchables.find(context, Prefs.handleActionApp(gesture))
                if (app == null || !Launchables.launch(context, app)) say(context, R.string.lock_shortcut_app_gone)
            }
        }
        when (action) {
            HandleAction.SLEEP -> UsageData.count(Usage.Count.HANDLE_SLEEP)
            HandleAction.PANEL, HandleAction.SEARCH -> Unit
            else -> UsageData.count(Usage.Count.HANDLE_SHORTCUT)
        }
    }

    // --- The flashlight. Android tells an app the torch's state only through a callback, so the
    // first use registers one and flips the torch once it hears; the callback then stays, and
    // costs nothing until the torch changes.

    private var torchId: String? = null
    private var torchOn = false
    private var listening = false
    /** Held before the callback first answered: flip the torch when it does. */
    private var flipWhenKnown = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            torchOn = enabled
            if (flipWhenKnown) {
                flipWhenKnown = false
                flip(appContext ?: return)
            }
        }
    }
    private var appContext: Context? = null

    private fun flashlight(context: Context) {
        if (listening) {
            if (torchId == null || flipWhenKnown) return
            flip(context)
            return
        }
        listening = true
        flipWhenKnown = true
        appContext = context
        // Listing the cameras asks the camera service, so it happens off the main thread.
        Background.run({ Torch.cameraId(context) }) { id ->
            if (id == null) {
                listening = false
                flipWhenKnown = false
                say(context, R.string.lock_shortcut_no_flashlight)
                return@run
            }
            torchId = id
            runCatching { context.getSystemService(CameraManager::class.java).registerTorchCallback(torchCallback, main) }
                .onFailure {
                    listening = false
                    flipWhenKnown = false
                    say(context, R.string.lock_shortcut_no_flashlight)
                }
        }
    }

    private fun flip(context: Context) {
        val id = torchId ?: return
        if (!Torch.set(context, id, !torchOn)) say(context, R.string.lock_shortcut_no_flashlight)
    }

    private fun say(context: Context, text: Int) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
