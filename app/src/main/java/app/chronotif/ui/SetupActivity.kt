// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
 * Setup and settings, not needed in daily use. Each section is its own page, and a drawer lists the
 * sections in groups. On a narrow screen the page has the full width and the drawer slides in over
 * it from the menu button; on a wide one the drawer stays open beside the page. Settings reopen on
 * the last section, or on General while a permission is missing.
 *
 * The look is Material 3: settings sit in rows joined into rounded groups, in colours taken from
 * the wallpaper ([M3]). General starts with the setup checklist.
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
    /** Where the setting helpers add their rows: a [SettingGroup], or [body] between groups. */
    private lateinit var target: LinearLayout
    /** The sections, full height: over the page on a narrow screen, beside it on a wide one. */
    private lateinit var drawer: View
    private lateinit var rows: LinearLayout
    private var drawerWidth = 0
    private var scrim: View? = null
    /** Wide enough for the drawer to stay beside the page rather than over it. */
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
    }

    private var settled = false
    private val settle = Runnable {
        settled = true
        refreshAll()
    }

    private val c by lazy { M3(this) }

    /** Light or dark as picked in General, or as the system is: the whole screen, system parts too. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val night = when (Prefs.settingsTheme) {
            Prefs.THEME_LIGHT -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            Prefs.THEME_DARK -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        applyOverrideConfiguration(android.content.res.Configuration().apply {
            uiMode = (base.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        window.decorView.setBackgroundColor(c.surface)
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (c.dark) 0 else light, light)
        wide = resources.configuration.screenWidthDp >= 600
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        target = body
        page = ScrollView(this).apply {
            addView(body)
            isVerticalScrollBarEnabled = false
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!wide) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(16), 0)
                addView(menuButton(), LinearLayout.LayoutParams(dp(48), dp(48)))
            }, Ui.lp())
            addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        drawer = Swipes(this, onRight = {}, onLeft = { if (!wide) expand(false) }).apply {
            background = if (wide) GradientDrawable().apply { setColor(c.drawer) }
            else GradientDrawable().apply {
                setColor(c.drawer)
                val r = dp(28).toFloat()
                cornerRadii = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(rows) })
            elevation = if (wide) 0f else dp(16).toFloat()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        drawerWidth = if (wide) dp(300) else minOf(dp(340), (resources.displayMetrics.widthPixels * 0.82f).toInt())
        if (wide) row.addView(drawer, LinearLayout.LayoutParams(drawerWidth, -1))
        row.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        val root = FrameLayout(this)
        root.addView(row, FrameLayout.LayoutParams(-1, -1))
        if (!wide) {
            scrim = View(this).apply {
                setBackgroundColor(0x52000000)
                setOnClickListener { expand(false) }
            }
            root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            root.addView(drawer, FrameLayout.LayoutParams(drawerWidth, -1))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            row.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            if (!wide) drawer.setPadding(bars.left, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)
        current = if (missingPermission()) Section.GENERAL
        else Section.entries.firstOrNull { it.name == Prefs.settingsSection } ?: Section.GENERAL
        expanded = wide
        show(current)
        renderDrawer()
        applyExpanded(animate = false)
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

    /** Back closes the drawer over the page, before it leaves settings. */
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
        renderDrawer()
    }

    // --- Setup state ---

    /** Notification access and display over other apps: without them Chronotif can't work. */
    private fun missingPermission() = !ListenerService.isEnabled(this) || !Settings.canDrawOverlays(this)

    private fun disconnected() = settled && ListenerService.isEnabled(this) && ListenerService.instance == null

    private fun listenerReady() = ListenerService.isEnabled(this) && !disconnected()

    /** The setup steps done, out of [setupSteps]: notification access (connected), and display over other apps. */
    private fun stepsDone() = listOf(listenerReady(), Settings.canDrawOverlays(this)).count { it }

    private val setupSteps = 2

    // --- Drawer ---

    private fun expand(open: Boolean) {
        if (expanded == open) return
        expanded = open
        applyExpanded()
    }

    /** Slides the drawer in or out over the page; a wide screen keeps it in place. */
    private fun applyExpanded(animate: Boolean = true) {
        updateBack()
        val scrim = scrim ?: return
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val away = (drawerWidth + dp(16)).toFloat() * if (rtl) 1 else -1
        drawer.animate().cancel()
        scrim.animate().cancel()
        if (expanded) {
            drawer.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            if (!animate) {
                drawer.translationX = 0f
                scrim.alpha = 1f
                return
            }
            drawer.animate().translationX(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            scrim.animate().alpha(1f).setDuration(260).start()
        } else {
            if (!animate) {
                drawer.translationX = away
                drawer.visibility = View.GONE
                scrim.alpha = 0f
                scrim.visibility = View.GONE
                return
            }
            drawer.animate().translationX(away).setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
                .withEndAction { if (!expanded) drawer.visibility = View.GONE }.start()
            scrim.animate().alpha(0f).setDuration(200)
                .withEndAction { if (!expanded) scrim.visibility = View.GONE }.start()
        }
    }

    private fun select(s: Section) {
        if (s != current) {
            current = s
            Prefs.settingsSection = s.name
            show(s)
            renderDrawer()
        }
        // The new page is in place before the drawer slides off it.
        if (!wide) expand(false)
    }

    /** The app's name, setup progress while it's unfinished, then the sections in groups. */
    private fun renderDrawer() {
        rows.removeAllViews()
        rows.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            addView(text(getString(R.string.app_name), 22f, weight = 600))
            addView(text(getString(R.string.version, BuildConfig.VERSION_NAME), 13f, secondary = true), gap(2))
        }, Ui.lp())
        val done = stepsDone()
        if (done < setupSteps) rows.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = c.pressable(c.high, dp(20).toFloat())
            addView(text(getString(R.string.setup_progress), 13f, weight = 600))
            addView(View(context).apply {
                background = LayerDrawable(arrayOf(
                    Ui.rounded(c.highest, dp(3).toFloat()),
                    ClipDrawable(Ui.rounded(c.primary, dp(3).toFloat()), Gravity.START, ClipDrawable.HORIZONTAL).apply {
                        level = 10000 * done / setupSteps
                    },
                ))
            }, LinearLayout.LayoutParams(0, dp(6), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            addView(text(getString(R.string.setup_progress_count, done, setupSteps), 13f))
            setOnClickListener { select(Section.GENERAL) }
        }, Ui.lp().apply { bottomMargin = dp(6) })
        val groups = listOf(
            null to listOf(Section.GENERAL, Section.TIPS),
            R.string.settings_group_features to listOf(Section.PANEL, Section.HANDLE, Section.SWIPE, Section.LOCK, Section.SEARCH),
            R.string.settings_group_app to listOf(Section.UPDATES, Section.ABOUT),
        )
        for ((title, sections) in groups) {
            if (title != null) {
                rows.addView(text(getString(title), 13f, weight = 600, secondary = true).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(6))
                }, Ui.lp())
            }
            for (s in sections) rows.addView(drawerRow(s), Ui.lp())
        }
    }

    private fun drawerRow(s: Section): View {
        val on = s == current
        val tint = if (on) c.onPrimaryContainer else c.textSecondary
        val missing = if (s == Section.GENERAL) setupSteps - stepsDone() else 0
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            background = c.pressable(if (on) c.primaryContainer else 0, dp(26).toFloat())
            addView(ImageView(context).apply {
                setImageResource(s.icon)
                imageTintList = ColorStateList.valueOf(tint)
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text(getString(s.title), 15f, weight = if (on) 600 else 500).apply {
                maxLines = 1
                if (on) setTextColor(c.onPrimaryContainer)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            if (missing > 0) addView(text(missing.toString(), 11f, weight = 700).apply {
                setTextColor(c.onErrorContainer)
                gravity = Gravity.CENTER
                minWidth = dp(20)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = Ui.rounded(c.errorContainer, dp(10).toFloat())
            })
            isSelected = on
            setOnClickListener { select(s) }
        }
    }

    private fun menuButton() = ImageView(this).apply {
        setImageResource(R.drawable.ic_menu)
        imageTintList = ColorStateList.valueOf(c.text)
        scaleType = ImageView.ScaleType.CENTER
        background = c.pressable(0, dp(24).toFloat())
        contentDescription = getString(R.string.settings_sections)
        setOnClickListener { expand(!expanded) }
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
        target = body
        body.addView(text(getString(s.title), 30f, weight = 500).apply {
            setPadding(dp(4), dp(if (wide) 28 else 12), dp(4), 0)
        }, Ui.lp())
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
        reshapeGroups()
        page.scrollTo(0, 0)
    }

    private fun reshapeGroups() {
        for (i in 0 until body.childCount) (body.getChildAt(i) as? SettingGroup)?.reshape()
    }

    /** Builds [current] again where it was scrolled to, after a change its previews show. */
    private fun reshow() {
        val y = page.scrollY
        show(current)
        page.post { page.scrollTo(0, y) }
        renderDrawer()
    }

    private fun general() {
        footnote(getString(R.string.setup_intro), top = 6)
        progressCard()
        group(R.string.setup_permissions) {
            step(R.string.perm_listener, R.string.perm_listener_desc,
                done = { ListenerService.isEnabled(this) }, broken = { disconnected() },
                hint = {
                    when {
                        disconnected() -> getString(if (isXiaomi) R.string.perm_disconnected_xiaomi else R.string.perm_disconnected)
                        Build.VERSION.SDK_INT >= 33 && !ListenerService.isEnabled(this) -> getString(R.string.perm_restricted_hint)
                        else -> null
                    }
                }) { openListenerSettings() }
            step(R.string.perm_overlay, R.string.perm_overlay_desc, done = { Settings.canDrawOverlays(this) }) {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        if (isXiaomi) group(R.string.setup_xiaomi) {
            permission(R.string.perm_autostart, R.string.perm_autostart_desc, granted = null) {
                open(
                    Intent().setComponent(
                        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    ),
                )
            }
            permission(R.string.perm_xiaomi, R.string.perm_xiaomi_desc, granted = null) {
                open(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", packageName),
                )
            }
        }
        group {
            toggle(R.string.enabled, R.string.enabled_desc, { Prefs.enabled }) {
                Prefs.enabled = it
                applyHandle()
                StatusBarSwipe.instance?.refresh()
                TileService.requestListeningState(this, ComponentName(this, PanelTile::class.java))
            }
        }
        group {
            val themes = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
            segmented(R.string.settings_theme, listOf(getString(R.string.theme_system), getString(R.string.theme_light),
                getString(R.string.theme_dark)), themes.indexOf(Prefs.settingsTheme).coerceAtLeast(0)) {
                if (Prefs.settingsTheme == themes[it]) return@segmented
                Prefs.settingsTheme = themes[it]
                // Let the pick show before the screen redraws in the new colours.
                body.postDelayed({ recreate() }, 150)
            }
        }
    }

    /** How much of setup is left, in words, with a ring; "All set" once nothing is. */
    private fun progressCard() {
        val ring = ProgressRing(this)
        val title = text("", 17f, weight = 600).apply { setTextColor(c.onPrimaryContainer) }
        val desc = text("", 13.5f).apply { setTextColor(c.onPrimaryContainer); alpha = 0.85f }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = Ui.rounded(c.primaryContainer, dp(28).toFloat())
            addView(ring, LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(desc, gap(4))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        }, gap(16))
        refreshers += {
            val done = stepsDone()
            val left = setupSteps - done
            ring.set(done, setupSteps, c.onPrimaryContainer)
            title.text = if (left == 0) getString(R.string.setup_all_set)
            else resources.getQuantityString(R.plurals.setup_steps_left, left, left)
            desc.text = getString(when {
                !ListenerService.isEnabled(this) -> R.string.setup_next_listener
                disconnected() -> R.string.setup_next_reconnect
                !Settings.canDrawOverlays(this) -> R.string.setup_next_overlay
                else -> R.string.setup_done_desc
            })
        }
    }

    /** The gestures nobody can see: a row each, always a tap away in the drawer. */
    private fun tips() {
        group {
            listOf(
                R.string.tip_open_title to R.string.tip_open_body,
                R.string.tip_check_title to R.string.tip_check_body,
                R.string.tip_search_title to R.string.tip_search_body,
                R.string.tip_preview_title to R.string.tip_preview_body,
                R.string.tip_stacks_title to R.string.tip_stacks_body,
                R.string.tip_pictures_title to R.string.tip_pictures_body,
                R.string.tip_move_title to R.string.tip_move_body,
                R.string.tip_close_title to R.string.tip_close_body,
                R.string.tip_lock_title to R.string.tip_lock_body,
            ).forEach { (title, tip) -> item(title, getString(tip)) }
        }
    }

    private fun panel() {
        header()
        group {
            item(null, getString(R.string.tile_hint), content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button(getString(R.string.open_panel), filled = true) { Panel.open(context) })
                if (Build.VERSION.SDK_INT >= 33) addView(button("+ " + getString(R.string.tile_label), filled = false) {
                    getSystemService(StatusBarManager::class.java).requestAddTileService(
                        ComponentName(context, PanelTile::class.java),
                        getString(R.string.tile_label),
                        Icon.createWithResource(context, R.drawable.ic_tile),
                        mainExecutor,
                    ) {}
                }, Ui.lp(-2).apply { marginStart = dp(8) })
            })
            toggle(R.string.swipe_to_clear, R.string.swipe_to_clear_desc, { Prefs.swipeToClear }) {
                Prefs.swipeToClear = it
            }
        }
    }

    private fun handle() {
        group {
            toggle(R.string.handle_enabled, R.string.handle_enabled_desc, { Prefs.handleEnabled }) {
                Prefs.handleEnabled = it
                applyHandle()
            }
        }
        group(R.string.handle_look) {
            segmented(R.string.handle_side, listOf(getString(R.string.side_left), getString(R.string.side_right)),
                if (Prefs.handleRight) 1 else 0) {
                Prefs.handleRight = it == 1
                applyHandle()
            }
            tiles(R.string.handle_shape, shapes.map { (name, shape) -> getString(name) to { EdgeHandle.preview(this, shape) } },
                columns = 3, selected = shapes.indexOfFirst { it.second == Prefs.handleShape }) {
                Prefs.handleShape = shapes[it].second
                applyHandle()
            }
            val looks = listOf(Prefs.LOOK_FROSTED, Prefs.LOOK_SOLID, Prefs.LOOK_COUNT)
            segmented(R.string.handle_fill, listOf(getString(R.string.fill_frosted), getString(R.string.fill_solid),
                getString(R.string.style_count_short)), looks.indexOf(Prefs.handleLook),
                note = getString(R.string.fill_count_desc)) {
                Prefs.handleLook = looks[it]
                applyHandle()
                reshow()
            }
        }
        group(R.string.handle_colours) {
            val usual = listOf(Prefs.COLOUR_GREY, Prefs.COLOUR_WHITE, Prefs.COLOUR_BLACK)
            swatches(R.string.handle_colour, usual.map { EdgeHandle.idleColour(it) },
                listOf(R.string.colour_grey, R.string.colour_white, R.string.colour_black), usual.indexOf(Prefs.handleColour)) {
                Prefs.handleColour = usual[it]
                applyHandle()
                reshow()
            }
            val fresh = listOf(Prefs.NEW_ACCENT, Prefs.NEW_BLUE, Prefs.NEW_GREEN, Prefs.NEW_ORANGE, Prefs.NEW_RED,
                Prefs.NEW_PURPLE, Prefs.NEW_UNCHANGED)
            swatches(R.string.handle_new_colour, fresh.map { EdgeHandle.newColour(this, it) },
                listOf(R.string.colour_accent, R.string.colour_blue, R.string.colour_green, R.string.colour_orange,
                    R.string.colour_red, R.string.colour_purple, R.string.colour_unchanged),
                fresh.indexOf(Prefs.handleNewColour)) {
                Prefs.handleNewColour = fresh[it]
                applyHandle()
            }
        }
        group(R.string.handle_placement) {
            slider(R.string.handle_position, 100, (Prefs.handleY * 100).toInt()) {
                Prefs.handleY = it / 100f
                applyHandle()
            }
            toggle(R.string.handle_landscape, null, { Prefs.handleHideLandscape }) {
                Prefs.handleHideLandscape = it
                applyHandle()
            }
        }
    }

    private fun swipe() {
        // The feature is optional, so the restricted-settings hint waits until someone tries it.
        var swipeAsked = false
        group {
            permission(R.string.status_swipe_title, R.string.status_swipe_desc,
                granted = { StatusBarSwipe.isEnabled(this) },
                broken = { settled && StatusBarSwipe.isEnabled(this) && StatusBarSwipe.instance == null }) {
                swipeAsked = true
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            val swipeRestricted = item(null, getString(R.string.perm_restricted_hint))
            refreshers += {
                swipeRestricted.visibility =
                    if (Build.VERSION.SDK_INT >= 33 && swipeAsked && !StatusBarSwipe.isEnabled(this)) View.VISIBLE else View.GONE
            }
            toggle(R.string.status_swipe_enabled, null, { Prefs.statusSwipeEnabled }) {
                Prefs.statusSwipeEnabled = it
                StatusBarSwipe.instance?.refresh()
            }
        }
    }

    private fun lock() {
        group {
            tiles(R.string.lock_mode, listOf(R.string.lock_off, R.string.lock_new, R.string.lock_any).map { getString(it) to null },
                columns = 3, selected = Prefs.lockMode) { Prefs.lockMode = it }
            toggle(R.string.lock_privacy, R.string.lock_privacy_desc, { Prefs.lockHideContent }) {
                Prefs.lockHideContent = it
            }
        }
    }

    private fun search() {
        group {
            toggle(R.string.search_pull_down, R.string.search_pull_down_desc, { Prefs.pullDownSearch }) {
                Prefs.pullDownSearch = it
            }
            tiles(R.string.search_engine, engines.map { getString(it) to null }, columns = 2, selected = Prefs.searchEngine,
                note = getString(R.string.search_engine_desc)) { Prefs.searchEngine = it }
        }
        group(R.string.search_finds) {
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
        // The settings first; what Search can do is reference, below them.
        group(R.string.search_can_do) {
            listOf(
                R.string.search_can_apps to R.string.search_can_apps_desc,
                R.string.search_can_math to R.string.search_can_math_desc,
                R.string.search_can_units to R.string.search_can_units_desc,
                R.string.search_can_time to R.string.search_can_time_desc,
                R.string.search_can_currency to R.string.search_can_currency_desc,
                R.string.search_can_contacts to R.string.search_can_contacts_desc,
                R.string.search_can_calendar to R.string.search_can_calendar_desc,
                R.string.search_can_settings to R.string.search_can_settings_desc,
                R.string.search_can_web to R.string.search_can_web_desc,
            ).forEach { (title, desc) -> item(title, getString(desc)) }
        }
        footnote(getString(R.string.search_can_private))
    }

    private fun updates() {
        group {
            segmented(R.string.update_channel_label, listOf(getString(R.string.update_stable), getString(R.string.update_nightly)),
                if (Prefs.nightlyUpdates) 1 else 0, note = getString(R.string.update_channel_desc)) {
                Prefs.nightlyUpdates = it == 1
                Prefs.updateFailedVersion = null
            }
        }
        group {
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
        }
        val updateStatus = text("", 13f, secondary = true).apply { visibility = View.GONE }
        lateinit var check: TextView
        check = button(getString(R.string.update_check_now), filled = true) {
            check.isEnabled = false
            check.alpha = 0.6f
            Updater.run(this, manual = true, onStatus = {
                updateStatus.text = it
                updateStatus.visibility = View.VISIBLE
            }, done = { check.isEnabled = true; check.alpha = 1f })
        }
        body.addView(check, gap(16).apply { width = -2; marginStart = dp(4) })
        body.addView(updateStatus, gap(8).apply { marginStart = dp(4) })
    }

    private fun about() {
        group(R.string.setup_privacy) { item(null, getString(R.string.privacy_body)) }
        footnote(getString(R.string.version, BuildConfig.VERSION_NAME))
    }

    /** The panel header's clock and date. Changes show the next time the panel opens. */
    private fun header() {
        group(R.string.header_title) {
            item(null, getString(R.string.header_desc))
            // Hidden with the clock, or unless the date sits beside it.
            val clockOptions = ArrayList<View>()
            val besideOptions = ArrayList<View>()
            fun refresh() {
                clockOptions.forEach { it.visibility = if (Prefs.headerClock) View.VISIBLE else View.GONE }
                besideOptions.forEach {
                    it.visibility = if (Prefs.headerClock && Prefs.headerDateBeside) View.VISIBLE else View.GONE
                }
            }
            toggle(R.string.header_clock, null, { Prefs.headerClock }) {
                Prefs.headerClock = it
                refresh()
            }
            clockOptions += slider(R.string.header_clock_size, Prefs.CLOCK_SIZE_MAX - Prefs.CLOCK_SIZE_MIN,
                Prefs.headerClockSize - Prefs.CLOCK_SIZE_MIN, label = { getString(R.string.header_clock_size, Prefs.CLOCK_SIZE_MIN + it) }) {
                Prefs.headerClockSize = Prefs.CLOCK_SIZE_MIN + it
            }
            clockOptions += segmented(R.string.header_date_position,
                listOf(getString(R.string.header_date_under), getString(R.string.header_date_beside)),
                if (Prefs.headerDateBeside) 1 else 0) {
                Prefs.headerDateBeside = it == 1
                refresh()
            }
            besideOptions += segmented(R.string.header_date_align,
                listOf(getString(R.string.header_align_top), getString(R.string.header_align_center), getString(R.string.header_align_bottom)),
                Prefs.headerDateAlign) { Prefs.headerDateAlign = it }
            toggle(R.string.header_weekday, null, { Prefs.headerWeekday }) { Prefs.headerWeekday = it }
            toggle(R.string.header_year, null, { Prefs.headerYear }) { Prefs.headerYear = it }
            toggle(R.string.header_short_names, null, { Prefs.headerShortNames }) { Prefs.headerShortNames = it }
            toggle(R.string.header_settings, null, { Prefs.headerSettings }) { Prefs.headerSettings = it }
            refresh()
        }
    }

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

    // --- Setting helpers ---

    private fun text(s: CharSequence, sp: Float, weight: Int = 400, secondary: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (weight != 400) typeface = Typeface.create(Typeface.DEFAULT, weight, false)
            setTextColor(if (secondary) c.textSecondary else c.text)
            setLineSpacing(0f, 1.1f)
        }

    private fun gap(top: Int) = Ui.lp().apply { topMargin = dp(top) }

    /**
     * Rows joined into one [SettingGroup], under an optional heading. The helpers called in [block]
     * add their rows to it.
     */
    private fun group(heading: Int? = null, block: () -> Unit): SettingGroup {
        if (heading != null) body.addView(text(getString(heading), 14f, weight = 600).apply {
            setTextColor(c.primary)
            setPadding(dp(16), 0, dp(16), 0)
        }, gap(24))
        val g = SettingGroup(this, c)
        body.addView(g, gap(if (heading != null) 10 else 16))
        target = g
        block()
        target = body
        return g
    }

    /** Small print between groups. */
    private fun footnote(s: CharSequence, top: Int = 12) {
        body.addView(text(s, 13f, secondary = true).apply { setPadding(dp(4), 0, dp(4), 0) }, gap(top))
    }

    /** A row: optional [title], optional [desc], then [content] below or [trailing] beside them. */
    private fun item(
        title: Int?,
        desc: CharSequence? = null,
        trailing: View? = null,
        content: View? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (title != null) addView(text(getString(title), 16f, weight = 500))
            if (desc != null) addView(text(desc, 13.5f, secondary = true), if (title != null) gap(3) else Ui.lp())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            minimumHeight = dp(56)
            if (title != null) tag = title
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (trailing != null) addView(trailing, Ui.lp(-2).apply { marginStart = dp(16) })
            }, Ui.lp())
            if (content != null) addView(content, gap(if (title != null || desc != null) 12 else 0))
            if (onClick != null) setOnClickListener { onClick() }
        }
        target.addView(row, Ui.lp())
        return row
    }

    /** A pill-shaped button: filled for the thing to do next, tonal otherwise. */
    private fun button(label: CharSequence, filled: Boolean, onClick: () -> Unit) = text(label, 14f, weight = 600).apply {
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(20), 0, dp(20), 0)
        setTextColor(if (filled) c.onPrimary else c.text)
        background = c.pressable(if (filled) c.primary else c.highest, dp(20).toFloat())
        setOnClickListener { onClick() }
    }

    private fun toggle(title: Int, desc: Int?, value: () -> Boolean, onChange: (Boolean) -> Unit): M3Switch {
        val sw = M3Switch(this, c)
        sw.setChecked(value(), animate = false)
        val row = item(title, desc?.let { getString(it) }, trailing = sw)
        row.accessibilityDelegate = M3Switch.RowAccessibility(sw)
        row.setOnClickListener {
            sw.setChecked(!sw.isChecked, animate = true)
            onChange(sw.isChecked)
            renderDrawer()
        }
        return sw
    }

    /** Switches waiting on a permission answer, by request code. */
    private val pendingPermission = HashMap<Int, Pair<M3Switch, (Boolean) -> Unit>>()

    /**
     * A switch that needs [permission] (ADR 0008): turning it on asks for it, and it stays on only
     * if granted. Once Android stops asking, the switch points to the app's permission page.
     */
    private fun permissionToggle(title: Int, desc: Int, permission: String, value: () -> Boolean, set: (Boolean) -> Unit) {
        val code = REQUEST_SEARCH_PERMISSION + pendingPermission.size
        val granted = { checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val s = toggle(title, desc, { value() && granted() }) { on ->
            if (!on || granted()) return@toggle set(on)
            requestPermissions(arrayOf(permission), code)
        }
        pendingPermission[code] = s to set
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val (s, set) = pendingPermission[requestCode] ?: return
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        set(granted)
        s.setChecked(granted, animate = true)
        if (!granted && permissions.firstOrNull()?.let(::shouldShowRequestPermissionRationale) == false) {
            android.widget.Toast.makeText(this, R.string.search_permission_denied, android.widget.Toast.LENGTH_LONG).show()
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /**
     * A permission Chronotif asks for elsewhere, with a button that opens it. [granted] null means
     * Android can't tell us, so the button just opens the page.
     */
    private fun permission(
        title: Int,
        desc: Int,
        granted: (() -> Boolean)?,
        broken: () -> Boolean = { false },
        onClick: () -> Unit,
    ): View {
        val button = button("", filled = true, onClick)
        val row = item(title, getString(desc), trailing = button)
        val refresh = {
            if (granted == null) {
                button.setText(R.string.open)
                styleButton(button, filled = false)
            } else {
                // Stays tappable when granted: ROMs can drop a grant (or the binding) behind
                // our back, and the settings screen is the only way to repair it.
                val ok = granted() && !broken()
                button.setText(when {
                    !granted() -> R.string.grant
                    broken() -> R.string.reconnect
                    else -> R.string.granted
                })
                styleButton(button, filled = !ok)
            }
        }
        refresh()
        refreshers += refresh
        return row
    }

    private fun styleButton(b: TextView, filled: Boolean) {
        b.setTextColor(if (filled) c.onPrimary else c.text)
        b.background = c.pressable(if (filled) c.primary else c.highest, dp(20).toFloat())
    }

    /**
     * A setup step: a check once [done], else a button that does it. A [broken] step (done but not
     * working) gets the button back, and [hint] explains what's wrong.
     */
    private fun step(
        title: Int,
        desc: Int,
        done: () -> Boolean,
        broken: () -> Boolean = { false },
        hint: () -> String? = { null },
        onClick: () -> Unit,
    ) {
        val mark = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER }
        val titleView = text(getString(title), 16f, weight = 500)
        val descView = text("", 13.5f, secondary = true)
        val hintView = text("", 13f, secondary = true)
        val action = button(getString(R.string.allow), filled = true, onClick)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(18), dp(14))
            tag = title
            addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(1) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(descView, gap(3))
                addView(hintView, gap(6))
                addView(action, gap(12).apply { width = -2 })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            // Done stays tappable: ROMs drop grants, and their settings page is where to fix it.
            setOnClickListener { onClick() }
        }
        target.addView(row, Ui.lp())
        refreshers += {
            val ok = done() && !broken()
            mark.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (ok) setColor(c.goodContainer) else setStroke(dp(2), if (broken()) c.error else c.outline)
            }
            mark.setImageResource(if (ok) R.drawable.ic_check else 0)
            mark.imageTintList = ColorStateList.valueOf(c.good)
            descView.text = if (ok) getString(R.string.step_allowed) else getString(desc)
            descView.setTextColor(if (ok) c.good else c.textSecondary)
            val h = if (ok) null else hint()
            hintView.text = h
            hintView.visibility = if (h == null) View.GONE else View.VISIBLE
            action.visibility = if (ok) View.GONE else View.VISIBLE
            action.setText(if (broken()) R.string.reconnect else R.string.allow)
        }
    }

    /** A pick of one, picked by tapping it. Notifies [onPick] and updates the drawer. */
    private fun choose(views: List<View>, selected: Int, style: (View, Boolean) -> Unit, onPick: (Int) -> Unit) {
        views.forEachIndexed { i, v ->
            style(v, i == selected)
            v.setOnClickListener {
                views.forEachIndexed { j, w -> style(w, j == i) }
                onPick(i)
                renderDrawer()
            }
        }
    }

    /** Two or three options joined into a button group, the picked one a filled pill with a check. */
    private fun segmented(title: Int, labels: List<String>, selected: Int, note: String? = null, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            dividerDrawable = GradientDrawable().apply { setSize(dp(2), 0) }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val full = dp(20).toFloat()
        val tight = dp(6).toFloat()
        val check = getDrawable(R.drawable.ic_check)!!.mutate()
        val views = labels.mapIndexed { i, l ->
            text(l, 14f, weight = 500).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                minHeight = dp(40)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                compoundDrawablePadding = dp(4)
            }.also { row.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
        }
        fun radii(i: Int, picked: Boolean): FloatArray {
            if (picked) return FloatArray(8) { full }
            val first = if (rtl) i == labels.size - 1 else i == 0
            val last = if (rtl) i == 0 else i == labels.size - 1
            val l = if (first) full else tight
            val r = if (last) full else tight
            return floatArrayOf(l, l, r, r, r, r, l, l)
        }
        choose(views, selected, { v, on ->
            val i = views.indexOf(v)
            v.background = c.pressable(if (on) c.primary else c.highest, radii(i, on))
            (v as TextView).setTextColor(if (on) c.onPrimary else c.text)
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(if (on) check else null, null, null, null)
            v.compoundDrawableTintList = ColorStateList.valueOf(c.onPrimary)
            v.isSelected = on
        }, onPick)
        return item(title, note, content = row).also { labelStyle(it, title) }
    }

    /** Options as tiles in [columns], each with an optional picture above its name, one picked. */
    private fun tiles(
        title: Int,
        options: List<Pair<String, (() -> View)?>>,
        columns: Int,
        selected: Int,
        note: String? = null,
        onPick: (Int) -> Unit,
    ): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val views = options.map { (name, picture) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(12))
                minimumHeight = dp(52)
                picture?.let { addView(it(), LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(6) }) }
                addView(text(name, 13f, weight = 500).apply {
                    gravity = Gravity.CENTER
                    maxLines = 3
                })
            }
        }
        choose(views, selected, { v, on ->
            v.background = c.pressable(if (on) c.primaryContainer else c.high, dp(if (on) 22 else 14).toFloat())
            ((v as LinearLayout).getChildAt(v.childCount - 1) as TextView).setTextColor(if (on) c.onPrimaryContainer else c.text)
            v.isSelected = on
        }, onPick)
        views.chunked(columns).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until columns) {
                val v = chunk.getOrNull(col) ?: View(this)
                row.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (col > 0) marginStart = dp(6) })
            }
            grid.addView(row, gap(if (r == 0) 0 else 6))
        }
        return item(title, note, content = grid).also { labelStyle(it, title) }
    }

    /** Colour dots, wrapping to fit; a null colour is "none", drawn struck through. */
    private fun swatches(title: Int, colours: List<Int?>, names: List<Int>, selected: Int, onPick: (Int) -> Unit): View {
        val size = dp(40)
        val gapPx = dp(6)
        val sideWidth = if (wide) drawerWidth else 0
        val room = resources.displayMetrics.widthPixels - sideWidth - dp(32 + 36)
        val perRow = (room / (size + gapPx)).coerceAtLeast(3)
        val views = colours.mapIndexed { i, col -> Swatch(this, col, c.high, c.primary).apply { contentDescription = getString(names[i]) } }
        choose(views, selected, { v, on -> (v as Swatch).picked = on; v.isSelected = on }, onPick)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        views.chunked(perRow).forEachIndexed { r, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { row.addView(it, LinearLayout.LayoutParams(size, size).apply { marginEnd = gapPx }) }
            grid.addView(row, gap(if (r == 0) 0 else 4))
        }
        return item(title, null, content = grid).also { labelStyle(it, title) }
    }

    /** A thick Material 3 slider; [label] replaces the title with one that shows the value. */
    private fun slider(title: Int, max: Int, progress: Int, label: ((Int) -> String)? = null, onChange: (Int) -> Unit): View {
        val bar = SeekBar(this).apply {
            this.max = max
            this.progress = progress
            val r = dp(8).toFloat()
            progressDrawable = LayerDrawable(arrayOf(
                Ui.rounded(if (c.dark) c.highest else c.primaryContainer, r),
                ClipDrawable(Ui.rounded(c.primary, r), Gravity.START, ClipDrawable.HORIZONTAL),
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            // A narrow bar with a gap either side, cut out of the track.
            thumb = InsetDrawable(GradientDrawable().apply {
                setColor(c.primary)
                cornerRadius = dp(2).toFloat()
                setSize(dp(4), dp(40))
            }, dp(6), 0, dp(6), 0)
            splitTrack = true
            minHeight = dp(16)
            maxHeight = dp(16)
            setPadding(dp(8), 0, dp(8), 0)
            background = null
        }
        val row = item(title, null, content = bar).also { labelStyle(it, title) }
        val titleView = (row.getChildAt(0) as LinearLayout).getChildAt(0).let { (it as LinearLayout).getChildAt(0) as TextView }
        if (label != null) titleView.text = label(progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (label != null) titleView.text = label(p)
                onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        return row
    }

    /** A control's title reads as a small label over it, so the control is what stands out. */
    private fun labelStyle(row: View, title: Int) {
        val texts = ((row as LinearLayout).getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout
        (texts.getChildAt(0) as TextView).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(c.textSecondary)
        }
        row.tag = title
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
            background = Ui.pressable(0, context.dp(20).toFloat(), 0x22808080)
        }

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val r = c - context.dp(6)
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
                canvas.drawCircle(c, c, c - context.dp(2), paint)
            }
        }
    }

    /**
     * Holds the drawer and notices a sideways swipe across it ([onLeft] closes it) without getting
     * in the way of taps and scrolling.
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
