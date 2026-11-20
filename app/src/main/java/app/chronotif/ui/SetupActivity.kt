// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.quicksettings.TileService
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.chronotif.BuildConfig
import app.chronotif.EdgeHandle
import app.chronotif.ListenerService
import app.chronotif.PanelTile
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.StatusBarSwipe
import app.chronotif.Updater
import kotlin.math.abs

/**
 * Setup and settings, not needed in daily use. A sidebar lists the sections, each with a summary of
 * its settings, and each section is its own page. On a narrow screen the sidebar collapses to its
 * icons beside the page and expands over it; on a wide one it stays open beside the page. Settings
 * reopen on the last section, or on General while a permission is missing.
 */
@Suppress("SetTextI18n")
class SetupActivity : Activity() {

    private enum class Section(val title: Int, val icon: Int) {
        GENERAL(R.string.setup_general, R.drawable.ic_section_general),
        TIPS(R.string.setup_tips, R.drawable.ic_section_tips),
        PANEL(R.string.setup_panel, R.drawable.ic_section_panel),
        HANDLE(R.string.handle_enabled, R.drawable.ic_section_handle),
        SWIPE(R.string.status_swipe_title, R.drawable.ic_section_swipe),
        LOCK(R.string.setup_lock, R.drawable.ic_lock),
        SEARCH(R.string.setup_search, R.drawable.ic_search),
        UPDATES(R.string.setup_updates, R.drawable.ic_section_updates),
        ABOUT(R.string.setup_about, R.drawable.ic_section_about),
    }

    /** The open section's page content. */
    private lateinit var body: LinearLayout
    private lateinit var page: ScrollView
    /** The collapsed sidebar: the sections' icons. */
    private lateinit var rail: LinearLayout
    /** The expanded sidebar: a card per section. */
    private lateinit var side: View
    private lateinit var cards: LinearLayout
    private var scrim: View? = null
    /** Wide enough for the expanded sidebar to sit beside the page rather than over it. */
    private var wide = false
    private var expanded = false
    private var current = Section.GENERAL
    private val refreshers = ArrayList<() -> Unit>()
    private val isXiaomi = Build.MANUFACTURER.equals("xiaomi", true) ||
        Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    private companion object {
        /** A listener the system is still binding looks disconnected for a moment after launch. */
        const val SETTLE_MS = 2000L
        const val REQUEST_SEARCH_PERMISSION = 10
        const val WARN = 0xFFFF9F0A.toInt()
    }

    private var settled = false
    private val settle = Runnable {
        settled = true
        refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        wide = resources.configuration.screenWidthDp >= 600
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        page = ScrollView(this).apply { addView(body) }
        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(12))
        }
        cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
        }
        val railScroll = Swipes(this, onRight = { expand(true) }, onLeft = {}).apply {
            setBackgroundColor(sideBg)
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(rail) })
        }
        side = Swipes(this, onRight = {}, onLeft = { if (!wide) expand(false) }).apply {
            setBackgroundColor(sideBg)
            addView(ScrollView(context).apply { addView(cards) })
            elevation = if (wide) 0f else dp(8).toFloat()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(railScroll, LinearLayout.LayoutParams(dp(64), -1))
        if (wide) row.addView(side, LinearLayout.LayoutParams(dp(290), -1))
        row.addView(page, LinearLayout.LayoutParams(0, -1, 1f))
        val root = FrameLayout(this)
        root.addView(row, FrameLayout.LayoutParams(-1, -1))
        if (!wide) {
            scrim = View(this).apply {
                setBackgroundColor(0x73000000)
                setOnClickListener { expand(false) }
            }
            root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            val width = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.85f).toInt())
            root.addView(side, FrameLayout.LayoutParams(width, -1))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            row.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            if (!wide) side.setPadding(bars.left, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)
        current = if (missingPermission()) Section.GENERAL
        else Section.entries.firstOrNull { it.name == Prefs.settingsSection } ?: Section.GENERAL
        expanded = wide
        show(current)
        applyExpanded()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
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

    /** Back collapses a sidebar expanded over the page, before it leaves settings. */
    private val collapseOnBack = if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { expand(false) } else null
    private var backRegistered = false

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = !wide && expanded
        if (want == backRegistered) return
        backRegistered = want
        if (want) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, collapseOnBack!!)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(collapseOnBack!!)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Before Android 13, Back arrives as a key.
        if (Build.VERSION.SDK_INT < 33 && event.keyCode == android.view.KeyEvent.KEYCODE_BACK && !wide && expanded) {
            if (event.action == android.view.KeyEvent.ACTION_UP) expand(false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        renderSidebar()
    }

    // --- Sidebar ---

    private val dark get() = palette.dark
    private val sideBg get() = if (dark) 0xFF1A1B1D.toInt() else 0xFFEDEDF0.toInt()
    private val cardBg get() = if (dark) 0xFF2A2B2F.toInt() else 0xFFFFFFFF.toInt()
    private val tileBg get() = if (dark) 0xFF2E3038.toInt() else 0xFFE2E3EA.toInt()

    /** Notification access and display over other apps: without them Chronotif can't work. */
    private fun missingPermission() = !ListenerService.isEnabled(this) || !Settings.canDrawOverlays(this)

    private fun expand(open: Boolean) {
        if (expanded == open) return
        expanded = open
        applyExpanded()
    }

    private fun applyExpanded() {
        if (wide) {
            rail.parentFrame().visibility = if (expanded) View.GONE else View.VISIBLE
            side.visibility = if (expanded) View.VISIBLE else View.GONE
        } else {
            side.visibility = if (expanded) View.VISIBLE else View.GONE
            scrim?.visibility = side.visibility
        }
        updateBack()
    }

    private fun View.parentFrame(): View = (parent as View).parent as View

    private fun select(s: Section) {
        if (!wide) expand(false)
        if (s == current) return
        current = s
        Prefs.settingsSection = s.name
        show(s)
        renderSidebar()
    }

    private fun renderSidebar() {
        rail.removeAllViews()
        rail.addView(menuButton(), LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(6) })
        for (s in Section.entries) {
            rail.addView(iconTile(s, outlineCurrent = true).apply {
                setOnClickListener { select(s) }
                contentDescription = getString(s.title)
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(8) })
        }
        cards.removeAllViews()
        cards.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menuButton(), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(text(getString(R.string.app_name), 22f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }, Ui.lp().apply { bottomMargin = dp(8) })
        for (s in Section.entries) {
            val (summary, warn) = summary(s)
            cards.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = outlined(if (s == current) selectedBg else cardBg, dp(14), s == current)
                addView(iconTile(s, outlineCurrent = false), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(getString(s.title), 15f, bold = true).apply { if (warn) setTextColor(WARN) })
                    addView(text(summary, 12f, secondary = true).apply { maxLines = 2 })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                setOnClickListener { select(s) }
            }, Ui.lp().apply { topMargin = dp(7) })
        }
    }

    private val selectedBg get() = if (dark) 0xFF34374A.toInt() else 0xFFE6E9FA.toInt()

    private fun menuButton() = ImageView(this).apply {
        setImageResource(R.drawable.ic_menu)
        imageTintList = android.content.res.ColorStateList.valueOf(palette.text)
        scaleType = ImageView.ScaleType.CENTER
        background = Ui.pressable(0, dp(22).toFloat(), palette.ripple)
        contentDescription = getString(R.string.settings_sections)
        setOnClickListener { expand(!expanded) }
    }

    /** A section's icon on a rounded tile; General gets an orange dot while a permission is missing. */
    private fun iconTile(s: Section, outlineCurrent: Boolean): View = FrameLayout(this).apply {
        background = outlined(tileBg, dp(12), outlineCurrent && s == current)
        addView(ImageView(context).apply {
            setImageResource(s.icon)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.text)
            scaleType = ImageView.ScaleType.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        if (s == Section.GENERAL && missingPermission()) {
            addView(View(context).apply { background = Ui.rounded(WARN, dp(5).toFloat()) },
                FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END))
        }
    }

    private fun outlined(color: Int, radius: Int, selected: Boolean) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (selected) setStroke(dp(2), palette.accent)
    }

    /** A section's one-line summary, and whether it needs attention. */
    private fun summary(s: Section): Pair<String, Boolean> = when (s) {
        Section.GENERAL -> if (missingPermission()) getString(R.string.summary_permission_needed) to true
            else getString(if (Prefs.enabled) R.string.summary_on else R.string.summary_off) to false
        Section.TIPS -> getString(R.string.summary_tips) to false
        Section.PANEL -> getString(if (Prefs.headerClock) R.string.summary_clock_date else R.string.summary_date_only) to false
        Section.HANDLE -> (if (!Prefs.handleEnabled) getString(R.string.summary_off) else getString(R.string.summary_handle,
            getString(shapes.first { it.second == Prefs.handleShape }.first),
            getString(if (Prefs.handleRight) R.string.side_right else R.string.side_left))) to false
        Section.SWIPE -> getString(when {
            !StatusBarSwipe.isEnabled(this) -> R.string.summary_swipe_access
            Prefs.statusSwipeEnabled -> R.string.summary_on
            else -> R.string.summary_off
        }) to false
        Section.LOCK -> getString(listOf(R.string.lock_off, R.string.lock_new, R.string.lock_any)[Prefs.lockMode.coerceIn(0, 2)]) to false
        Section.SEARCH -> getString(engines[Prefs.searchEngine.coerceIn(0, engines.size - 1)]) to false
        Section.UPDATES -> getString(if (Prefs.nightlyUpdates) R.string.update_nightly else R.string.update_stable).let {
            if (Prefs.autoUpdate) getString(R.string.summary_updates_auto, it) else it
        } to false
        Section.ABOUT -> getString(R.string.version, BuildConfig.VERSION_NAME) to false
    }

    private val shapes = listOf(
        R.string.shape_half to Prefs.SHAPE_HALF_SMALL,
        R.string.shape_half_large to Prefs.SHAPE_HALF_LARGE,
        R.string.shape_tab to Prefs.SHAPE_TAB,
        R.string.shape_circle to Prefs.SHAPE_CIRCLE,
        R.string.shape_line to Prefs.SHAPE_LINE,
    )

    private val engines = listOf(R.string.search_engine_google, R.string.search_engine_duckduckgo,
        R.string.search_engine_bing, R.string.search_engine_brave)

    // --- Pages ---

    private fun show(s: Section) {
        body.removeAllViews()
        refreshers.clear()
        pendingPermission.clear()
        body.addView(text(getString(s.title), 24f, bold = true), Ui.lp().apply { bottomMargin = dp(4) })
        when (s) {
            Section.GENERAL -> general()
            Section.TIPS -> tips()
            Section.PANEL -> panel()
            Section.HANDLE -> handle()
            Section.SWIPE -> swipe()
            Section.LOCK -> lock()
            Section.SEARCH -> search()
            Section.UPDATES -> updates()
            Section.ABOUT -> about()
        }
        refreshers.forEach { it() }
        page.scrollTo(0, 0)
    }

    /** Builds [current] again where it was scrolled to, after a change its previews show. */
    private fun reshow() {
        val y = page.scrollY
        show(current)
        page.post { page.scrollTo(0, y) }
        renderSidebar()
    }

    private fun general() {
        body.addView(text(getString(R.string.setup_intro), 14f, secondary = true), gap(4))
        toggle(R.string.enabled, R.string.enabled_desc, { Prefs.enabled }) {
            Prefs.enabled = it
            applyHandle()
            StatusBarSwipe.instance?.refresh()
            TileService.requestListeningState(this, ComponentName(this, PanelTile::class.java))
        }
        label(R.string.setup_permissions)
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
    }

    /** The gestures nobody can see: a line each, always a tap away in the sidebar. */
    private fun tips() {
        listOf(
            R.string.tip_open_title to R.string.tip_open_body,
            R.string.tip_check_title to R.string.tip_check_body,
            R.string.tip_search_title to R.string.tip_search_body,
            R.string.tip_preview_title to R.string.tip_preview_body,
            R.string.tip_stacks_title to R.string.tip_stacks_body,
            R.string.tip_pictures_title to R.string.tip_pictures_body,
            R.string.tip_move_title to R.string.tip_move_body,
            R.string.tip_close_title to R.string.tip_close_body,
        ).forEach { (title, tip) ->
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(cardBg, dp(14).toFloat())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(text(getString(title), 15f, bold = true))
                addView(text(getString(tip), 13.5f, secondary = true), gap(4))
            }, gap(10))
        }
    }

    private fun panel() {
        header()
        body.addView(text(getString(R.string.tile_hint), 13f, secondary = true), gap(16))
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
    }

    private fun handle() {
        toggle(R.string.handle_enabled, R.string.handle_enabled_desc, { Prefs.handleEnabled }) {
            Prefs.handleEnabled = it
            applyHandle()
        }
        label(R.string.handle_side)
        segmented(listOf(getString(R.string.side_left), getString(R.string.side_right)), if (Prefs.handleRight) 1 else 0) {
            Prefs.handleRight = it == 1
            applyHandle()
        }
        label(R.string.handle_shape)
        tiles(shapes.map { (name, shape) -> getString(name) to { EdgeHandle.preview(this, shape) } }, columns = 3,
            selected = shapes.indexOfFirst { it.second == Prefs.handleShape }) {
            Prefs.handleShape = shapes[it].second
            applyHandle()
        }
        label(R.string.handle_fill)
        val looks = listOf(Prefs.LOOK_FROSTED, Prefs.LOOK_SOLID, Prefs.LOOK_COUNT)
        segmented(listOf(getString(R.string.fill_frosted), getString(R.string.fill_solid), getString(R.string.style_count_short)),
            looks.indexOf(Prefs.handleLook)) {
            Prefs.handleLook = looks[it]
            applyHandle()
            reshow()
        }
        body.addView(text(getString(R.string.fill_count_desc), 13f, secondary = true), gap(6))
        label(R.string.handle_colour)
        val usual = listOf(Prefs.COLOUR_GREY, Prefs.COLOUR_WHITE, Prefs.COLOUR_BLACK)
        swatches(usual.map { EdgeHandle.idleColour(it) },
            listOf(R.string.colour_grey, R.string.colour_white, R.string.colour_black), usual.indexOf(Prefs.handleColour)) {
            Prefs.handleColour = usual[it]
            applyHandle()
            reshow()
        }
        label(R.string.handle_new_colour)
        val fresh = listOf(Prefs.NEW_ACCENT, Prefs.NEW_BLUE, Prefs.NEW_GREEN, Prefs.NEW_ORANGE, Prefs.NEW_RED,
            Prefs.NEW_PURPLE, Prefs.NEW_UNCHANGED)
        swatches(fresh.map { EdgeHandle.newColour(this, it) },
            listOf(R.string.colour_accent, R.string.colour_blue, R.string.colour_green, R.string.colour_orange,
                R.string.colour_red, R.string.colour_purple, R.string.colour_unchanged),
            fresh.indexOf(Prefs.handleNewColour)) {
            Prefs.handleNewColour = fresh[it]
            applyHandle()
        }
        label(R.string.handle_position)
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
    }

    private fun swipe() {
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
    }

    private fun lock() {
        label(R.string.lock_mode)
        tiles(listOf(R.string.lock_off, R.string.lock_new, R.string.lock_any).map { getString(it) to null }, columns = 3,
            selected = Prefs.lockMode) { Prefs.lockMode = it }
        toggle(R.string.lock_privacy, R.string.lock_privacy_desc, { Prefs.lockHideContent }) {
            Prefs.lockHideContent = it
        }
    }

    private fun search() {
        toggle(R.string.search_pull_down, R.string.search_pull_down_desc, { Prefs.pullDownSearch }) {
            Prefs.pullDownSearch = it
        }
        label(R.string.search_engine)
        tiles(engines.map { getString(it) to null }, columns = 2, selected = Prefs.searchEngine) { Prefs.searchEngine = it }
        body.addView(text(getString(R.string.search_engine_desc), 13f, secondary = true), gap(6))
        permissionToggle(R.string.search_find_contacts, R.string.search_find_contacts_desc,
            android.Manifest.permission.READ_CONTACTS, { Prefs.searchContacts }) { Prefs.searchContacts = it }
        permissionToggle(R.string.search_calls, R.string.search_calls_desc,
            android.Manifest.permission.CALL_PHONE, { Prefs.searchCalls }) { Prefs.searchCalls = it }
        toggle(R.string.search_currency_rates, R.string.search_currency_rates_desc, { Prefs.currencyRates }) {
            Prefs.currencyRates = it
        }
        permissionToggle(R.string.search_find_calendar, R.string.search_find_calendar_desc,
            android.Manifest.permission.READ_CALENDAR, { Prefs.searchCalendar }) { Prefs.searchCalendar = it }
    }

    private fun updates() {
        label(R.string.update_channel_label)
        segmented(listOf(getString(R.string.update_stable), getString(R.string.update_nightly)), if (Prefs.nightlyUpdates) 1 else 0) {
            Prefs.nightlyUpdates = it == 1
            Prefs.updateFailedVersion = null
        }
        body.addView(text(getString(R.string.update_channel_desc), 13f, secondary = true), gap(6))
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
        }, gap(12).apply { width = -2 })
        body.addView(updateStatus, gap(4))
    }

    private fun about() {
        label(R.string.setup_privacy)
        body.addView(text(getString(R.string.privacy_body), 15f, secondary = true), gap(4))
        body.addView(text(getString(R.string.version, BuildConfig.VERSION_NAME), 13f, secondary = true), gap(16))
    }

    /** The panel header's clock and date. Changes show the next time the panel opens. */
    private fun header() {
        label(R.string.header_title)
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
        val sizeLabel = text("", 13f, secondary = true)
        fun showSize() { sizeLabel.text = getString(R.string.header_clock_size, Prefs.headerClockSize) }
        showSize()
        clockOptions.addView(sizeLabel, gap(16))
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
        label(R.string.header_date_position, into = clockOptions)
        segmented(listOf(getString(R.string.header_date_under), getString(R.string.header_date_beside)),
            if (Prefs.headerDateBeside) 1 else 0, into = clockOptions) {
            Prefs.headerDateBeside = it == 1
            refresh()
        }
        clockOptions.addView(besideOptions)
        label(R.string.header_date_align, into = besideOptions)
        segmented(listOf(getString(R.string.header_align_top), getString(R.string.header_align_center), getString(R.string.header_align_bottom)),
            Prefs.headerDateAlign, into = besideOptions) { Prefs.headerDateAlign = it }
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
            setOnCheckedChangeListener { _, v ->
                onChange(v)
                renderSidebar()
            }
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


    /** A small heading over a setting. */
    private fun label(title: Int, into: LinearLayout = body) {
        into.addView(text(getString(title), 13f, secondary = true), gap(18))
    }

    /** A pick of one, picked by tapping it. Notifies [onPick] and updates the sidebar's summary. */
    private fun choose(views: List<View>, selected: Int, style: (View, Boolean) -> Unit, onPick: (Int) -> Unit) {
        views.forEachIndexed { i, v ->
            style(v, i == selected)
            v.setOnClickListener {
                views.forEachIndexed { j, w -> style(w, j == i) }
                onPick(i)
                renderSidebar()
            }
        }
    }

    /** Two or three options side by side, one picked. */
    private fun segmented(labels: List<String>, selected: Int, into: LinearLayout = body, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(cardBg, dp(12).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val views = labels.map { l ->
            text(l, 14f).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(6), dp(9), dp(6), dp(9))
            }.also { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        choose(views, selected, { v, on ->
            v.background = if (on) Ui.rounded(selectedBg, dp(9).toFloat()) else null
            (v as TextView).setTextColor(if (on) palette.text else palette.textSecondary)
        }, onPick)
        into.addView(row, gap(8))
    }

    /** Options as tiles in [columns], each with an optional picture above its name, one picked. */
    private fun tiles(options: List<Pair<String, (() -> View)?>>, columns: Int, selected: Int, onPick: (Int) -> Unit) {
        val views = options.map { (name, picture) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                picture?.let { addView(it(), LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(6) }) }
                addView(text(name, 12.5f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 3
                })
            }
        }
        choose(views, selected, { v, on -> v.background = outlined(if (on) selectedBg else cardBg, dp(12), on) }, onPick)
        views.chunked(columns).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until columns) {
                val v = chunk.getOrNull(c) ?: View(this)
                row.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (c > 0) marginStart = dp(8) })
            }
            body.addView(row, gap(if (r == 0) 8 else 8))
        }
    }

    /** Colour dots, wrapping to fit; a null colour is "none", drawn struck through. */
    private fun swatches(colours: List<Int?>, names: List<Int>, selected: Int, onPick: (Int) -> Unit) {
        val size = dp(34)
        val gap = dp(8)
        val sideWidth = if (wide && expanded) dp(290) else dp(64)
        val room = resources.displayMetrics.widthPixels - sideWidth - dp(32)
        val perRow = (room / (size + gap)).coerceAtLeast(3)
        val views = colours.mapIndexed { i, c -> Swatch(this, c, cardBg, palette.accent).apply { contentDescription = getString(names[i]) } }
        choose(views, selected, { v, on -> (v as Swatch).picked = on }, onPick)
        views.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { row.addView(it, LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }) }
            body.addView(row, gap(8))
        }
    }

    /** One colour dot; picked, it's ringed in the accent colour. */
    private class Swatch(context: Context, private val colour: Int?, private val empty: Int, private val accent: Int) : View(context) {
        var picked = false
            set(v) {
                field = v
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            background = Ui.pressable(0, context.dp(17).toFloat(), 0x22808080)
        }

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val r = c - context.dp(5)
            paint.style = Paint.Style.FILL
            paint.color = colour?.let { it or 0xFF000000.toInt() } ?: empty
            canvas.drawCircle(c, c, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(1).toFloat()
            paint.color = 0x55808080
            canvas.drawCircle(c, c, r, paint)
            if (colour == null) {
                // None: struck through.
                paint.strokeWidth = context.dp(2).toFloat()
                paint.color = 0xFF808088.toInt()
                val d = r * 0.7f
                canvas.drawLine(c - d, c + d, c + d, c - d, paint)
            }
            if (picked) {
                paint.strokeWidth = context.dp(2).toFloat()
                paint.color = accent
                canvas.drawCircle(c, c, c - context.dp(1), paint)
            }
        }
    }

    /**
     * Holds part of the sidebar and notices a sideways swipe across it ([onRight] expands, [onLeft]
     * collapses) without getting in the way of taps and scrolling.
     */
    private class Swipes(context: Context, private val onRight: () -> Unit, private val onLeft: () -> Unit) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var done = false
        private val reach = context.dp(48)

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    done = false
                }
                MotionEvent.ACTION_MOVE -> if (!done) {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > reach && abs(dx) > abs(dy) * 2) {
                        done = true
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        if (dx > 0) onRight() else onLeft()
                        return true
                    }
                }
            }
            if (done) return true
            return super.dispatchTouchEvent(e)
        }
    }
}
