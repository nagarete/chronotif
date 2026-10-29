// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.chronotif.BuildConfig
import app.chronotif.ListenerService
import app.chronotif.PanelTile
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.Updater

/** One-time setup and settings. Not needed in daily use. */
@Suppress("SetTextI18n")
class SetupActivity : Activity() {

    private lateinit var body: LinearLayout
    private val refreshers = ArrayList<() -> Unit>()
    private val isXiaomi = Build.MANUFACTURER.equals("xiaomi", true) ||
        Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
        ListenerService.ensureBound(this)
        Updater.foreground = true
    }

    override fun onPause() {
        super.onPause()
        Updater.foreground = false
    }

    private fun build() {
        body.addView(text(getString(R.string.app_name), 28f, bold = true))
        body.addView(text(getString(R.string.setup_intro), 15f, secondary = true), gap(8))

        toggle(R.string.enabled, R.string.enabled_desc, { Prefs.enabled }) {
            Prefs.enabled = it
            applyHandle()
            TileService.requestListeningState(this, ComponentName(this, PanelTile::class.java))
        }

        section(R.string.setup_permissions)
        permission(R.string.perm_listener, R.string.perm_listener_desc,
            granted = { ListenerService.isEnabled(this) }) { openListenerSettings() }
        val restricted = text(getString(R.string.perm_restricted_hint), 13f, secondary = true)
        body.addView(restricted, gap(4))
        refreshers += {
            restricted.visibility =
                if (Build.VERSION.SDK_INT >= 33 && !ListenerService.isEnabled(this)) View.VISIBLE else View.GONE
        }
        permission(R.string.perm_overlay, R.string.perm_overlay_desc,
            granted = { Settings.canDrawOverlays(this) }) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (isXiaomi) {
            permission(R.string.perm_xiaomi, R.string.perm_xiaomi_desc, granted = null) {
                open(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", packageName),
                )
            }
            permission(R.string.perm_autostart, R.string.perm_autostart_desc, granted = null) {
                open(
                    Intent().setComponent(
                        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    ),
                )
            }
        }

        section(R.string.setup_panel)
        toggle(R.string.handle_enabled, R.string.handle_enabled_desc, { Prefs.handleEnabled }) {
            Prefs.handleEnabled = it
            applyHandle()
        }
        body.addView(text(getString(R.string.handle_side), 15f), gap(12))
        radios(
            listOf(R.string.side_left, R.string.side_right),
            selected = if (Prefs.handleRight) 1 else 0,
        ) {
            Prefs.handleRight = it == 1
            applyHandle()
        }
        body.addView(text(getString(R.string.handle_position), 15f), gap(12))
        body.addView(SeekBar(this).apply {
            max = 100
            progress = (Prefs.handleY * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.handleY = p / 100f
                    applyHandle()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, gap(4))
        toggle(R.string.handle_landscape, null, { Prefs.handleHideLandscape }) {
            Prefs.handleHideLandscape = it
            applyHandle()
        }
        body.addView(text(getString(R.string.tile_hint), 13f, secondary = true), gap(12))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            setText(R.string.open_panel)
            setOnClickListener { startActivity(Intent(context, PanelActivity::class.java)) }
        })
        if (Build.VERSION.SDK_INT >= 33) buttons.addView(Button(this).apply {
            text = "+ " + getString(R.string.tile_label)
            setOnClickListener {
                getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(context, PanelTile::class.java),
                    getString(R.string.tile_label),
                    Icon.createWithResource(context, R.drawable.ic_tile),
                    mainExecutor,
                ) {}
            }
        }, gap(0).apply { marginStart = dp(8) })
        body.addView(buttons, gap(8))

        section(R.string.setup_lock)
        body.addView(text(getString(R.string.lock_mode), 15f), gap(4))
        radios(
            listOf(R.string.lock_off, R.string.lock_new, R.string.lock_any),
            selected = Prefs.lockMode,
            vertical = true,
        ) { Prefs.lockMode = it }
        toggle(R.string.lock_privacy, R.string.lock_privacy_desc, { Prefs.lockHideContent }) {
            Prefs.lockHideContent = it
        }

        section(R.string.setup_updates)
        body.addView(text(getString(R.string.update_channel_label), 15f), gap(4))
        radios(
            listOf(R.string.update_stable, R.string.update_nightly),
            selected = if (Prefs.nightlyUpdates) 1 else 0,
            vertical = false,
        ) {
            Prefs.nightlyUpdates = it == 1
            Prefs.updateFailedVersion = null
        }
        body.addView(text(getString(R.string.update_channel_desc), 13f, secondary = true), gap(4))
        toggle(R.string.update_auto, R.string.update_auto_desc, { Prefs.autoUpdate }) {
            Prefs.autoUpdate = it
            Updater.sync(this)
            if (it && Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
            refreshers.forEach { r -> r() }
        }
        val install = permission(R.string.perm_install, R.string.perm_install_desc,
            granted = { packageManager.canRequestPackageInstalls() }) {
            open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        refreshers += { install.visibility = if (Prefs.autoUpdate) View.VISIBLE else View.GONE }
        val updateStatus = text("", 13f, secondary = true).apply { visibility = View.GONE }
        body.addView(Button(this).apply {
            setText(R.string.update_check_now)
            setOnClickListener {
                isEnabled = false
                Updater.run(context, manual = true, onStatus = {
                    updateStatus.text = it
                    updateStatus.visibility = View.VISIBLE
                }, done = { isEnabled = true })
            }
        }, gap(8).apply { width = -2 })
        body.addView(updateStatus, gap(4))

        section(R.string.setup_privacy)
        body.addView(text(getString(R.string.privacy_body), 15f, secondary = true), gap(4))
        body.addView(text(getString(R.string.version, BuildConfig.VERSION_NAME), 13f, secondary = true), gap(12))
    }

    private fun applyHandle() {
        ListenerService.instance?.refreshHandle()
    }

    private fun openListenerSettings() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(this, ListenerService::class.java).flattenToString(),
        )
        if (!tryOpen(detail)) open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Opens a settings screen, falling back to this app's info page. */
    private fun open(intent: Intent) {
        if (!tryOpen(intent)) {
            tryOpen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun tryOpen(intent: Intent) = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // --- Tiny view helpers ---

    private fun text(s: CharSequence, sp: Float, bold: Boolean = false, secondary: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (secondary) alpha = 0.7f
        }

    private fun gap(top: Int) = Ui.lp().apply { topMargin = dp(top) }

    private fun section(title: Int) {
        body.addView(text(getString(title), 13f, bold = true).apply {
            setTextColor(Ui.accent(context))
            isAllCaps = true
        }, gap(28))
    }

    private fun permission(title: Int, desc: Int, granted: (() -> Boolean)?, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(getString(title), 16f))
            addView(text(getString(desc), 13f, secondary = true))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val button = Button(this).apply { setOnClickListener { onClick() } }
        row.addView(button, Ui.lp(-2).apply { marginStart = dp(12) })
        body.addView(row, gap(12))
        val refresh = {
            if (granted == null) {
                button.setText(R.string.open)
            } else {
                // Stays tappable when granted: ROMs can drop a grant (or the binding) behind
                // our back, and the settings screen is the only way to repair it.
                button.setText(if (granted()) R.string.granted else R.string.grant)
            }
        }
        refresh()
        refreshers += refresh
        return row
    }

    private fun toggle(title: Int, desc: Int?, value: () -> Boolean, onChange: (Boolean) -> Unit) {
        val s = Switch(this).apply {
            text = getString(title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isChecked = value()
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        body.addView(s, gap(12))
        if (desc != null) body.addView(text(getString(desc), 13f, secondary = true))
    }

    private fun radios(labels: List<Int>, selected: Int, vertical: Boolean = false, onPick: (Int) -> Unit) {
        val group = RadioGroup(this).apply {
            orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL
        }
        labels.forEachIndexed { i, l ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                setText(l)
                isChecked = i == selected
                setOnClickListener { onPick(i) }
            })
        }
        body.addView(group)
    }
}
