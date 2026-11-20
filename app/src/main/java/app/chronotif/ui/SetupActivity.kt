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
import android.service.notification.NotificationListenerService
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
import app.chronotif.StatusBarSwipe
import app.chronotif.Updater

/** One-time setup and settings. Not needed in daily use. */
@Suppress("SetTextI18n")
class SetupActivity : Activity() {

    private lateinit var body: LinearLayout
    private val refreshers = ArrayList<() -> Unit>()
    private val isXiaomi = Build.MANUFACTURER.equals("xiaomi", true) ||
        Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    private companion object {
        /** A listener the system is still binding looks disconnected for a moment after launch. */
        const val SETTLE_MS = 2000L
        const val REQUEST_SEARCH_PERMISSION = 10
    }

    private var settled = false
    private val settle = Runnable {
        settled = true
        refreshers.forEach { it() }
    }

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
        // Back from granting a permission (e.g. display over other apps): let the strip re-check.
        StatusBarSwipe.instance?.refresh()
        Updater.foreground = true
        body.postDelayed(settle, SETTLE_MS)
    }

    override fun onPause() {
        super.onPause()
        body.removeCallbacks(settle)
        settled = false
        Updater.foreground = false
        // Settings changed here may change how the panel looks; the next open builds it afresh.
        Panel.drop()
    }

    private fun build() {
        body.addView(text(getString(R.string.app_name), 28f, bold = true))
        body.addView(text(getString(R.string.setup_intro), 15f, secondary = true), gap(8))

        toggle(R.string.enabled, R.string.enabled_desc, { Prefs.enabled }) {
            Prefs.enabled = it
            applyHandle()
            StatusBarSwipe.instance?.refresh()
            TileService.requestListeningState(this, ComponentName(this, PanelTile::class.java))
        }

        section(R.string.setup_permissions)
        permission(R.string.perm_listener, R.string.perm_listener_desc,
            granted = { ListenerService.isEnabled(this) }, broken = { disconnected() }) { openListenerSettings() }
        // Granted but unbound: the system can't restart the listener (MIUI without autostart),
        // and only toggling access off and on reconnects it.
        val disconnectedHint = text(
            getString(if (isXiaomi) R.string.perm_disconnected_xiaomi else R.string.perm_disconnected),
            13f, secondary = true,
        )
        body.addView(disconnectedHint, gap(4))
        refreshers += { disconnectedHint.visibility = if (disconnected()) View.VISIBLE else View.GONE }
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
        // The feature is optional, so the restricted-settings hint waits until someone tries it.
        var swipeAsked = false
        permission(R.string.status_swipe_title, R.string.status_swipe_desc,
            granted = { StatusBarSwipe.isEnabled(this) },
            broken = { settled && StatusBarSwipe.isEnabled(this) && StatusBarSwipe.instance == null }) {
            swipeAsked = true
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val swipeRestricted = text(getString(R.string.perm_restricted_hint), 13f, secondary = true)
        body.addView(swipeRestricted, gap(4))
        refreshers += {
            swipeRestricted.visibility =
                if (Build.VERSION.SDK_INT >= 33 && swipeAsked && !StatusBarSwipe.isEnabled(this)) View.VISIBLE else View.GONE
        }
        toggle(R.string.status_swipe_enabled, null, { Prefs.statusSwipeEnabled }) {
            Prefs.statusSwipeEnabled = it
            StatusBarSwipe.instance?.refresh()
        }
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
        header()
        body.addView(text(getString(R.string.tile_hint), 13f, secondary = true), gap(12))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            setText(R.string.open_panel)
            setOnClickListener { Panel.open(context) }
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

        section(R.string.setup_search)
        toggle(R.string.search_pull_down, R.string.search_pull_down_desc, { Prefs.pullDownSearch }) {
            Prefs.pullDownSearch = it
        }
        body.addView(text(getString(R.string.search_engine), 15f), gap(12))
        radios(
            listOf(R.string.search_engine_google, R.string.search_engine_duckduckgo, R.string.search_engine_bing, R.string.search_engine_brave),
            selected = Prefs.searchEngine,
            vertical = true,
        ) { Prefs.searchEngine = it }
        body.addView(text(getString(R.string.search_engine_desc), 13f, secondary = true))
        permissionToggle(R.string.search_find_contacts, R.string.search_find_contacts_desc,
            android.Manifest.permission.READ_CONTACTS, { Prefs.searchContacts }) { Prefs.searchContacts = it }
        permissionToggle(R.string.search_calls, R.string.search_calls_desc,
            android.Manifest.permission.CALL_PHONE, { Prefs.searchCalls }) { Prefs.searchCalls = it }
        toggle(R.string.search_currency_rates, R.string.search_currency_rates_desc, { Prefs.currencyRates }) {
            Prefs.currencyRates = it
        }
        permissionToggle(R.string.search_find_calendar, R.string.search_find_calendar_desc,
            android.Manifest.permission.READ_CALENDAR, { Prefs.searchCalendar }) { Prefs.searchCalendar = it }

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

    /** The panel header's clock and date. Changes show the next time the panel opens. */
    private fun header() {
        body.addView(text(getString(R.string.header_title), 16f, bold = true), gap(20))
        body.addView(text(getString(R.string.header_desc), 13f, secondary = true))
        val clockOptions = group()
        val besideOptions = group()
        fun refresh() {
            clockOptions.visibility = if (Prefs.headerClock) View.VISIBLE else View.GONE
            besideOptions.visibility = if (Prefs.headerDateBeside) View.VISIBLE else View.GONE
        }
        toggle(R.string.header_clock, null, { Prefs.headerClock }) {
            Prefs.headerClock = it
            refresh()
        }
        body.addView(clockOptions)
        val sizeLabel = text("", 15f)
        fun showSize() { sizeLabel.text = getString(R.string.header_clock_size, Prefs.headerClockSize) }
        showSize()
        clockOptions.addView(sizeLabel, gap(12))
        clockOptions.addView(SeekBar(this).apply {
            max = Prefs.CLOCK_SIZE_MAX - Prefs.CLOCK_SIZE_MIN
            progress = Prefs.headerClockSize - Prefs.CLOCK_SIZE_MIN
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.headerClockSize = Prefs.CLOCK_SIZE_MIN + p
                    showSize()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, gap(4))
        clockOptions.addView(text(getString(R.string.header_date_position), 15f), gap(12))
        radios(
            listOf(R.string.header_date_under, R.string.header_date_beside),
            selected = if (Prefs.headerDateBeside) 1 else 0,
            into = clockOptions,
        ) {
            Prefs.headerDateBeside = it == 1
            refresh()
        }
        clockOptions.addView(besideOptions)
        besideOptions.addView(text(getString(R.string.header_date_align), 15f), gap(12))
        radios(
            listOf(R.string.header_align_top, R.string.header_align_center, R.string.header_align_bottom),
            selected = Prefs.headerDateAlign,
            into = besideOptions,
        ) { Prefs.headerDateAlign = it }
        toggle(R.string.header_weekday, null, { Prefs.headerWeekday }) { Prefs.headerWeekday = it }
        toggle(R.string.header_year, null, { Prefs.headerYear }) { Prefs.headerYear = it }
        toggle(R.string.header_short_names, null, { Prefs.headerShortNames }) { Prefs.headerShortNames = it }
        toggle(R.string.header_settings, null, { Prefs.headerSettings }) { Prefs.headerSettings = it }
        refresh()
    }

    private fun group() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun applyHandle() {
        ListenerService.instance?.refreshHandle()
    }

    private fun openListenerSettings() {
        // Granted but not connected (process killed and never rebound): ask for a rebind first.
        if (ListenerService.isEnabled(this) && ListenerService.instance == null) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, ListenerService::class.java))
            } catch (_: RuntimeException) {
            }
        }
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

    // Explicit colours: some themes (HyperOS dark) make plain text grey, unlike the switch labels.
    private val palette by lazy { Palette(this) }

    private fun text(s: CharSequence, sp: Float, bold: Boolean = false, secondary: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (secondary) palette.textSecondary else palette.text)
        }

    private fun gap(top: Int) = Ui.lp().apply { topMargin = dp(top) }

    private fun section(title: Int) {
        body.addView(text(getString(title), 13f, bold = true).apply {
            setTextColor(Ui.accent(context))
            isAllCaps = true
        }, gap(28))
    }

    private fun disconnected() = settled && ListenerService.isEnabled(this) && ListenerService.instance == null

    private fun permission(
        title: Int,
        desc: Int,
        granted: (() -> Boolean)?,
        broken: () -> Boolean = { false },
        onClick: () -> Unit,
    ): View {
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
                button.setText(
                    when {
                        !granted() -> R.string.grant
                        broken() -> R.string.reconnect
                        else -> R.string.granted
                    },
                )
            }
        }
        refresh()
        refreshers += refresh
        return row
    }

    private fun toggle(title: Int, desc: Int?, value: () -> Boolean, onChange: (Boolean) -> Unit): Switch {
        val s = Switch(this).apply {
            text = getString(title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(palette.text)
            isChecked = value()
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        body.addView(s, gap(12))
        if (desc != null) body.addView(text(getString(desc), 13f, secondary = true))
        return s
    }

    /** Switches waiting on a permission answer, by request code. */
    private val pendingPermission = HashMap<Int, Pair<Switch, (Boolean) -> Unit>>()

    /**
     * A switch that needs [permission] (ADR 0008): turning it on asks for it, and it stays on only
     * if granted. Once Android stops asking, the switch points to the app's permission page.
     */
    private fun permissionToggle(title: Int, desc: Int, permission: String, value: () -> Boolean, set: (Boolean) -> Unit) {
        val code = REQUEST_SEARCH_PERMISSION + pendingPermission.size
        val s = toggle(title, desc, { value() && checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED }) { on ->
            if (!on || checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return@toggle set(on)
            requestPermissions(arrayOf(permission), code)
        }
        pendingPermission[code] = s to set
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val (s, set) = pendingPermission[requestCode] ?: return
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        set(granted)
        s.isChecked = granted
        if (!granted && permissions.firstOrNull()?.let(::shouldShowRequestPermissionRationale) == false) {
            android.widget.Toast.makeText(this, R.string.search_permission_denied, android.widget.Toast.LENGTH_LONG).show()
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun radios(
        labels: List<Int>,
        selected: Int,
        vertical: Boolean = false,
        into: LinearLayout = body,
        onPick: (Int) -> Unit,
    ) {
        val group = RadioGroup(this).apply {
            orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL
        }
        labels.forEachIndexed { i, l ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                setText(l)
                setTextColor(palette.text)
                isChecked = i == selected
                setOnClickListener { onPick(i) }
            })
        }
        into.addView(group)
    }
}
