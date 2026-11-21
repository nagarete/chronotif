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
import app.chronotif.SearchSetup
import app.chronotif.Tutorial
import app.chronotif.StatusBarSwipe
import app.chronotif.Updater
import app.chronotif.UsageData
import app.chronotif.core.SettingMatch
import kotlin.math.abs

/**
 * Setup and settings, not needed in daily use. Each section is its own page, and a drawer lists the
 * sections in groups. On a narrow screen the page has the full width and the drawer slides in over
 * it from the menu button; on a wide one the drawer stays open beside the page. Settings reopen on
 * the last section, or on General while a permission is missing.
 *
 * The look is Material 3: settings sit in rows joined into rounded groups, in colours taken from
 * the wallpaper ([M3]). General starts with the setup checklist, and Search with its own ([SearchSetup]).
 */
@Suppress("SetTextI18n")
class SetupActivity : Activity() {

    private enum class Section(val title: Int, val icon: Int) {
        GENERAL(R.string.setup_general, R.drawable.ic_section_general),
        TUTORIAL(R.string.setup_tutorial, R.drawable.ic_section_tutorial),
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
        const val REQUEST_SPEECH = 1
        /** Results shown at most; more means the query is too short to be useful. */
        const val RESULTS = 30
        /** Every field Usage data sends, for people using the app. */
        const val USAGE_DATA_DOC = "https://github.com/nagarete/chronotif/blob/main/docs/usage-data.md"
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
        val main = FrameLayout(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(searchBar(), Ui.lp(h = dp(56)).apply {
                    setMargins(dp(12), dp(8), dp(12), dp(4))
                })
                addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(searchLayer(), FrameLayout.LayoutParams(-1, -1))
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
            // Results end above the keyboard.
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            searchLayer.setPadding(0, 0, 0, maxOf(0, ime - bars.bottom))
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
        // Back from Android's settings, a permission may have changed.
        index = null
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

    /** Back closes search, then the drawer over the page, before it leaves settings. */
    private val collapseOnBack = if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { back() } else null
    private var backRegistered = false

    private fun backCloses() = searching || (!wide && expanded)

    private fun back() {
        if (searching) closeSearch() else expand(false)
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = backCloses()
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
        if (Build.VERSION.SDK_INT < 33 && event.keyCode == android.view.KeyEvent.KEYCODE_BACK && backCloses()) {
            if (event.action == android.view.KeyEvent.ACTION_UP) back()
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
            null to listOf(Section.GENERAL, Section.TUTORIAL, Section.TIPS),
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
        val missing = when (s) {
            Section.GENERAL -> setupSteps - stepsDone()
            Section.SEARCH -> SearchSetup.left(this)
            else -> 0
        }
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
            setPadding(dp(4), dp(12), dp(4), 0)
        }, Ui.lp())
        build(s)
        refreshers.forEach { it() }
        reshapeGroups()
        page.scrollTo(0, 0)
    }

    /** Adds [s]'s settings to [body]. */
    private fun build(s: Section) {
        when (s) {
            Section.GENERAL -> general()
            Section.TUTORIAL -> tutorial()
            Section.TIPS -> tips()
            Section.PANEL -> panel()
            Section.HANDLE -> handle()
            Section.SWIPE -> swipe()
            Section.LOCK -> lock()
            Section.SEARCH -> search()
            Section.UPDATES -> updates()
            Section.ABOUT -> about()
        }
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
        group {
            toggle(R.string.enabled, R.string.enabled_desc, { Prefs.enabled }) {
                Prefs.enabled = it
                applyHandle()
                StatusBarSwipe.instance?.refresh()
                TileService.requestListeningState(this, ComponentName(this, PanelTile::class.java))
            }
        }
        footnote(getString(R.string.setup_intro))
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
            val themes = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
            segmented(R.string.settings_theme, listOf(getString(R.string.theme_system), getString(R.string.theme_light),
                getString(R.string.theme_dark)), themes.indexOf(Prefs.settingsTheme).coerceAtLeast(0)) {
                if (Prefs.settingsTheme == themes[it]) return@segmented
                Prefs.settingsTheme = themes[it]
                // Let the pick show before the screen redraws in the new colours.
                body.postDelayed({ recreate() }, 150)
            }
        }
        usageNotice()
    }

    /** Says once that Usage data is on (ADR 0016), with the switch a tap away. Gone once answered. */
    private fun usageNotice() {
        if (Prefs.usageNoticed) return
        group {
            // One row: the link to what's sent on the left, the answer on the right.
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(getString(R.string.usage_data_whats_sent), 14f, weight = 600).apply {
                    setTextColor(c.primary)
                    minHeight = dp(40)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { openWhatsSent() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button(getString(R.string.usage_notice_off), filled = false) {
                    UsageData.set(false)
                    Prefs.usageNoticed = true
                    reshow()
                }, Ui.lp(-2))
                addView(button(getString(R.string.usage_notice_ok), filled = true) {
                    Prefs.usageNoticed = true
                    reshow()
                }, Ui.lp(-2).apply { marginStart = dp(8) })
            }
            item(R.string.usage_notice_title, getString(R.string.usage_notice_body), content = content)
        }
    }

    private fun openWhatsSent() = open(Intent(Intent.ACTION_VIEW, Uri.parse(USAGE_DATA_DOC)))

    /** How much of setup is left, in words, with a ring; "All set" once nothing is. */
    private fun progressCard() = progressCard({ stepsDone() }, setupSteps) {
        getString(when {
            !ListenerService.isEnabled(this) -> R.string.setup_next_listener
            disconnected() -> R.string.setup_next_reconnect
            !Settings.canDrawOverlays(this) -> R.string.setup_next_overlay
            else -> R.string.setup_done_desc
        })
    }

    /** A checklist's card: [done] of [total] on a ring, the steps left (or "All set"), and [next] under them. */
    private fun progressCard(done: () -> Int, total: Int, next: () -> String) {
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
            val n = done()
            val left = total - n
            ring.set(n, total, c.onPrimaryContainer)
            title.text = if (left == 0) getString(R.string.setup_all_set)
            else resources.getQuantityString(R.plurals.setup_steps_left, left, left)
            desc.text = next()
        }
    }

    /** The gestures nobody can see: a row each, always a tap away in the drawer. */
    /** The Tutorial's parts, each with how far it got and a button to start, continue or redo it. */
    private fun tutorial() {
        footnote(getString(R.string.tutorial_intro), top = 6)
        group {
            Tutorial.Part.entries.forEach(::tutorialPart)
        }
    }

    private fun tutorialPart(part: Tutorial.Part) {
        val steps = Tutorial.steps(part).size
        val mark = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER }
        val status = text("", 13.5f, secondary = true)
        val start = button("", filled = true) {
            open(TutorialActivity.intent(this, part, fromStart = Tutorial.placeOf(part) == null && Tutorial.finished(part)))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(18), dp(14))
            tag = part.title
            addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(1) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(getString(part.title), 16f, weight = 500))
                addView(text(getString(part.desc), 13.5f, secondary = true), gap(3))
                addView(status, gap(6))
                addView(start, gap(12).apply { width = -2 })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        }
        target.addView(row, Ui.lp())
        indexed(part.title, getString(part.desc), row)
        refreshers += {
            val place = Tutorial.placeOf(part)
            val done = Tutorial.finished(part)
            mark.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (done) setColor(c.goodContainer) else setStroke(dp(2), c.outline)
            }
            mark.setImageResource(if (done) R.drawable.ic_check else 0)
            mark.imageTintList = ColorStateList.valueOf(c.good)
            status.text = when {
                place != null -> getString(R.string.tutorial_part_at, place + 1, steps)
                done -> getString(R.string.tutorial_part_done)
                else -> resources.getQuantityString(R.plurals.tutorial_part_steps, steps, steps)
            }
            status.setTextColor(if (done && place == null) c.good else c.textSecondary)
            start.setText(when {
                place != null -> R.string.tutorial_continue_part
                done -> R.string.tutorial_again
                else -> R.string.tutorial_start
            })
        }
    }

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
            toggle(R.string.lock_double_tap, R.string.lock_double_tap_desc, { Prefs.lockDoubleTapSleep }) {
                Prefs.lockDoubleTapSleep = it
            }
        }
    }

    private fun search() {
        searchChecklist()
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
        group(R.string.setup_privacy) {
            item(null, getString(R.string.privacy_body))
            toggle(R.string.usage_data, R.string.usage_data_desc, { Prefs.usageData }) {
                UsageData.set(it)
                Prefs.usageNoticed = true
            }
            item(R.string.usage_data_whats_sent, getString(R.string.usage_data_whats_sent_desc)) { openWhatsSent() }
        }
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

    /**
     * A setting changed. The panel can open over settings (edge handle, Open panel) while this
     * screen stays resumed, so the kept panel goes now, not only in [onPause]: the next open
     * builds one that shows the change.
     */
    private fun changed() {
        Panel.drop()
        // Search shows values; it reads them afresh next time.
        index = null
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

    // --- Search ---

    /** One searchable setting: a row on a section's page, or the page itself (no [title] row). */
    private class Entry(
        val section: Section,
        val title: Int?,
        val label: String,
        val path: String,
        val row: View?,
        val doc: SettingMatch.Doc,
    ) {
        /** What it's set to, read from its row once the page is built. */
        var value: String? = null
    }

    /** Every setting, built from the pages themselves when search opens, so it can't drift from them. */
    private var index: List<Entry>? = null
    /** While [buildIndex] runs: where [indexed] collects rows, and the section being built. */
    private var indexing: MutableList<Entry>? = null
    private var indexSection = Section.GENERAL
    private var groupHeading: Int? = null

    private var searching = false
    private lateinit var searchLayer: LinearLayout
    private lateinit var query: android.widget.EditText
    private lateinit var results: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private lateinit var clearQuery: View

    /** Words people type for a setting that its name and description don't contain. */
    private val keywords = mapOf(
        R.string.enabled to "on off disable pause",
        R.string.perm_listener to "permission notification listener",
        R.string.perm_overlay to "permission overlay draw",
        R.string.handle_enabled to "handle tab button",
        R.string.handle_colour to "color",
        R.string.handle_new_colour to "color light glow",
        R.string.handle_fill to "count number frosted solid",
        R.string.handle_position to "height move",
        R.string.header_clock_size to "font text big small",
        R.string.swipe_to_clear to "dismiss delete remove",
        R.string.lock_mode to "lockscreen keyguard",
        R.string.lock_privacy to "private hide privacy",
        R.string.lock_double_tap to "screen off turn off gesture wake",
        R.string.search_setup_widget to "home screen bar launcher",
        R.string.search_setup_tile to "quick settings",
        R.string.setup_tutorial to "learn guide how practice try",
        R.string.search_engine to "google duckduckgo bing brave web browser",
        R.string.update_auto to "upgrade install",
        R.string.update_channel_label to "beta nightly stable",
    )

    private val speech by lazy {
        Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.settings_search_hint))
    }

    /** Voice input is Android's: its speech service listens and hands back the words. */
    private val canSpeak by lazy { packageManager.resolveActivity(speech, 0) != null }

    private fun listen() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(speech, REQUEST_SPEECH)
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SPEECH || resultCode != RESULT_OK) return
        val said = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        openSearch(said)
    }

    private fun micButton() = ImageView(this).apply {
        setImageResource(R.drawable.ic_mic)
        imageTintList = ColorStateList.valueOf(c.textSecondary)
        scaleType = ImageView.ScaleType.CENTER
        background = c.pressable(0, dp(24).toFloat())
        contentDescription = getString(R.string.settings_search_voice)
        setOnClickListener { listen() }
    }

    /** The bar over every page: the drawer's menu button, then a field that opens search. */
    private fun searchBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        background = c.pressable(c.high, dp(28).toFloat())
        if (!wide) addView(menuButton(), LinearLayout.LayoutParams(dp(48), dp(48)))
        else addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(c.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(text(getString(R.string.settings_search_hint), 16f, secondary = true).apply {
            setPadding(dp(4), 0, 0, 0)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (canSpeak) addView(micButton(), LinearLayout.LayoutParams(dp(48), dp(48)))
        contentDescription = getString(R.string.settings_search_hint)
        setOnClickListener { openSearch() }
    }

    /** Search over the page: a field, and results that change as you type. */
    private fun searchLayer(): View {
        query = android.widget.EditText(this).apply {
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(c.text)
            setHintTextColor(c.textSecondary)
            hint = getString(R.string.settings_search_hint)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(4), 0, dp(4), 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (searching) showResults()
                }
            })
            setOnEditorActionListener { _, _, _ ->
                resultRows.firstOrNull()?.view?.takeIf { it.visibility == View.VISIBLE }
                    ?.let { (it.getTag(R.id.search_entry) as? Entry)?.let(::pick) }
                true
            }
        }
        clearQuery = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(c.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
            background = c.pressable(0, dp(24).toFloat())
            contentDescription = getString(R.string.clear)
            setOnClickListener { query.setText("") }
        }
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        resultsScroll = ScrollView(this).apply {
            addView(results)
            isVerticalScrollBarEnabled = false
            // Scrolling the results puts the keyboard away, so more of them show.
            setOnScrollChangeListener { _, _, y, _, oldY -> if (abs(y - oldY) > 0 && y > dp(8)) hideKeyboard() }
        }
        searchLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.high)
            visibility = View.GONE
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    imageTintList = ColorStateList.valueOf(c.text)
                    scaleType = ImageView.ScaleType.CENTER
                    background = c.pressable(0, dp(24).toFloat())
                    contentDescription = getString(R.string.close)
                    setOnClickListener { closeSearch() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(query, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(clearQuery, LinearLayout.LayoutParams(dp(48), dp(48)))
                if (canSpeak) addView(micButton(), LinearLayout.LayoutParams(dp(48), dp(48)))
            }, Ui.lp())
            addView(View(context).apply { setBackgroundColor(c.highest) }, Ui.lp(h = dp(1)))
            addView(resultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return searchLayer
    }

    private fun openSearch(text: String? = null) {
        if (!wide) expand(false)
        searching = true
        updateBack()
        searchLayer.visibility = View.VISIBLE
        query.setText(text ?: "")
        query.setSelection(query.text.length)
        showResults()
        // The index and the result rows are ready before the first letter: made once search has
        // drawn, not on the keystroke.
        searchLayer.postOnAnimation {
            searchLayer.post {
                if (!searching) return@post
                if (index == null) index = buildIndex()
                for (i in resultRows.size until RESULTS) resultRow(i).view.visibility = View.GONE
            }
        }
        if (text == null) {
            query.requestFocus()
            query.post {
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(query, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun closeSearch() {
        if (!searching) return
        searching = false
        updateBack()
        hideKeyboard()
        query.clearFocus()
        searchLayer.visibility = View.GONE
    }

    private fun hideKeyboard() {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(query.windowToken, 0)
    }

    /** Records a row while [buildIndex] builds the pages; does nothing otherwise. */
    private fun indexed(title: Int, desc: CharSequence?, row: View) {
        val into = indexing ?: return
        val label = getString(title)
        val heading = groupHeading?.let { getString(it) }
        val section = getString(indexSection.title)
        val path = if (heading != null) "$section › $heading" else section
        val other = listOfNotNull(section, heading, desc, keywords[title]).joinToString(" ")
        into += Entry(indexSection, title, label, path, row, SettingMatch.Doc(label, other))
    }

    /**
     * Builds every page off screen and keeps its rows, each page also findable by its name. Rows
     * are found by the same words they show, and their values are read from the rows themselves.
     */
    private fun buildIndex(): List<Entry> {
        val savedBody = body
        val savedRefreshers = ArrayList(refreshers)
        val savedPending = HashMap(pendingPermission)
        val out = ArrayList<Entry>()
        indexing = out
        try {
            for (s in Section.entries) {
                val start = out.size
                body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                target = body
                indexSection = s
                build(s)
                refreshers.forEach { it() }
                refreshers.clear()
                for (i in start until out.size) out[i].value = out[i].row?.let(::valueOf)
                // The page itself, unless a row already has its name (Edge handle's switch).
                val name = getString(s.title)
                if (out.subList(start, out.size).none { it.label == name }) {
                    out.add(start, Entry(s, null, name, getString(R.string.settings_search_page), null,
                        SettingMatch.Doc(name, keywords[s.title] ?: "")))
                }
            }
        } finally {
            indexing = null
            body = savedBody
            target = body
            groupHeading = null
            refreshers.clear()
            refreshers += savedRefreshers
            pendingPermission.clear()
            pendingPermission.putAll(savedPending)
        }
        return out
    }

    /** Result rows, made once and reused, so typing only rebinds them. */
    private class ResultRow(val view: LinearLayout, val icon: ImageView, val label: TextView, val path: TextView, val value: TextView)
    private val resultRows = ArrayList<ResultRow>()
    private var noResults: TextView? = null

    private fun resultRow(i: Int): ResultRow {
        resultRows.getOrNull(i)?.let { return it }
        val icon = ImageView(this).apply {
            imageTintList = ColorStateList.valueOf(c.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
        }
        val label = text("", 16f).apply { maxLines = 2 }
        val path = text("", 13f, secondary = true).apply { maxLines = 1 }
        val value = text("", 13f, secondary = true).apply {
            maxLines = 1
            maxWidth = dp(120)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = c.pressable(0, 0f)
            addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(path, gap(2))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(20) })
            addView(value, Ui.lp(-2).apply { marginStart = dp(12) })
        }
        results.addView(view, i, Ui.lp())
        return ResultRow(view, icon, label, path, value).also { resultRows += it }
    }

    private fun bind(i: Int, s: Section, label: String, path: String?, value: String?, typed: String, entry: Entry?, onClick: () -> Unit) {
        val r = resultRow(i)
        r.icon.setImageResource(s.icon)
        r.label.text = highlight(label, typed)
        r.path.text = path
        r.path.visibility = if (path == null) View.GONE else View.VISIBLE
        r.value.text = value
        r.value.visibility = if (value == null) View.GONE else View.VISIBLE
        r.view.setTag(R.id.search_entry, entry)
        r.view.setOnClickListener { onClick() }
        r.view.visibility = View.VISIBLE
    }

    private fun showResults() {
        val typed = query.text.toString()
        clearQuery.visibility = if (typed.isEmpty()) View.GONE else View.VISIBLE
        resultsScroll.scrollTo(0, 0)
        var shown = 0
        if (typed.isBlank()) {
            // Before typing: the pages, as in the drawer.
            for (s in Section.entries) bind(shown++, s, getString(s.title), null, null, typed, null) { closeSearch(); select(s) }
        } else {
            val all = index ?: buildIndex().also { index = it }
            for (e in SettingMatch.search(all, typed, RESULTS) { it.doc }) {
                bind(shown++, e.section, e.label, e.path, e.value, typed, e) { pick(e) }
            }
        }
        for (i in shown until resultRows.size) resultRows[i].view.visibility = View.GONE
        val none = noResults ?: text("", 15f, secondary = true).apply {
            setPadding(dp(24), dp(20), dp(24), dp(20))
            results.addView(this, Ui.lp())
            noResults = this
        }
        none.text = if (shown == 0) getString(R.string.settings_search_none, typed.trim()) else ""
        none.visibility = if (shown == 0) View.VISIBLE else View.GONE
    }

    /** The typed text, where the name has it, in bold and the accent colour. */
    private fun highlight(label: String, typed: String): CharSequence {
        val q = typed.trim()
        val at = if (q.isEmpty()) -1 else label.indexOf(q, ignoreCase = true)
        if (at < 0) return label
        return android.text.SpannableString(label).apply {
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), at, at + q.length, 0)
            setSpan(android.text.style.ForegroundColorSpan(c.primary), at, at + q.length, 0)
        }
    }

    /** What a row is set to: a switch's On or Off, or the picked option's name. */
    private fun valueOf(row: View): String? {
        fun find(v: View, test: (View) -> Boolean): View? {
            if (test(v)) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), test)?.let { return it }
            return null
        }
        (find(row) { it is M3Switch } as? M3Switch)?.let { return getString(if (it.isChecked) R.string.value_on else R.string.value_off) }
        val picked = find(row) { it !== row && it.isSelected } ?: return null
        return when (picked) {
            is TextView -> picked.text.toString()
            is LinearLayout -> (picked.getChildAt(picked.childCount - 1) as? TextView)?.text?.toString()
            else -> picked.contentDescription?.toString()
        }
    }

    /** Opens a result's page, scrolled to its row, which flashes so the eye finds it. */
    private fun pick(e: Entry) {
        closeSearch()
        if (e.section != current) {
            current = e.section
            Prefs.settingsSection = e.section.name
            show(e.section)
            renderDrawer()
        }
        val title = e.title ?: return
        page.post {
            val row = body.findViewWithTag<View>(title) ?: return@post
            var y = 0
            var v: View = row
            while (v !== body) {
                y += v.top
                v = v.parent as? View ?: break
            }
            page.smoothScrollTo(0, maxOf(0, y - dp(24)))
            flash(row)
        }
    }

    private fun flash(row: View) {
        val radii = when (val bg = row.background) {
            is GradientDrawable -> bg.cornerRadii
            is android.graphics.drawable.RippleDrawable -> (bg.getDrawable(0) as? GradientDrawable)?.cornerRadii
            else -> null
        }
        val glow = GradientDrawable().apply {
            setColor(c.primary)
            if (radii != null) cornerRadii = radii
            alpha = 0
        }
        row.foreground = glow
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            startDelay = 250
            addUpdateListener {
                val t = it.animatedFraction
                // Up quickly, then a slow fade.
                glow.alpha = (60 * if (t < 0.2f) t / 0.2f else (1 - t) / 0.8f).toInt()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { row.foreground = null }
            })
            start()
        }
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
        groupHeading = heading
        if (heading != null) body.addView(text(getString(heading), 14f, weight = 600).apply {
            setTextColor(c.primary)
            setPadding(dp(16), 0, dp(16), 0)
        }, gap(24))
        val g = SettingGroup(this, c)
        body.addView(g, gap(if (heading != null) 10 else 16))
        target = g
        block()
        target = body
        groupHeading = null
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
        if (title != null) indexed(title, desc, row)
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
            changed()
            renderDrawer()
        }
        // Kept in step with the setting, which a checklist step above may change, or a permission taken away.
        refreshers += { if (sw.isChecked != value()) sw.setChecked(value(), animate = true) }
        return sw
    }

    // --- Search's checklist ([SearchSetup]).

    /** Search's card and steps: what it can do once allowed, its widget, and the tile as an extra. */
    private fun searchChecklist() {
        val counted = SearchSetup.counted
        progressCard({ counted.size - SearchSetup.left(this) }, counted.size) {
            SearchSetup.next(this)?.let { getString(R.string.search_setup_next, getString(stepTitle(it))) }
                ?: getString(R.string.search_setup_done)
        }
        group(R.string.search_setup) {
            searchStep(SearchSetup.Step.CONTACTS, R.string.search_setup_contacts_desc, R.string.allow,
                permissionRequest(android.Manifest.permission.READ_CONTACTS) { Prefs.searchContacts = it })
            searchStep(SearchSetup.Step.CALLS, R.string.search_setup_calls_desc, R.string.allow,
                permissionRequest(android.Manifest.permission.CALL_PHONE) { Prefs.searchCalls = it })
            searchStep(SearchSetup.Step.CURRENCY, R.string.search_setup_currency_desc, R.string.turn_on, onAct = {
                Prefs.currencyRates = true
                refreshAll()
            })
            searchStep(SearchSetup.Step.CALENDAR, R.string.search_setup_calendar_desc, R.string.allow,
                permissionRequest(android.Manifest.permission.READ_CALENDAR) { Prefs.searchCalendar = it })
            searchStep(SearchSetup.Step.WIDGET, R.string.search_setup_widget_desc, R.string.add, ::addSearchWidget,
                hint = { getString(R.string.search_setup_widget_manual).takeUnless { canPinWidget() } })
            searchStep(SearchSetup.Step.TILE, R.string.search_setup_tile_desc, R.string.add, ::addSearchTile,
                hint = { getString(R.string.search_setup_tile_manual).takeIf { Build.VERSION.SDK_INT < 33 } })
        }
    }

    private fun stepTitle(step: SearchSetup.Step) = SearchSetup.title(step)

    /**
     * One step of Search's checklist: a mark, its name and what it does, then [act] (Allow, Turn on
     * or Add) and Not now. Done, it shows a check and no buttons; put off, it keeps [act] so the
     * user can still do it here. The optional tile has no Not now and never counts. When the
     * step's own button can't work, [hint] says how to do it by hand.
     */
    private fun searchStep(step: SearchSetup.Step, desc: Int, act: Int, onAct: () -> Unit, hint: () -> String? = { null }) {
        val title = stepTitle(step)
        val mark = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER }
        val descView = text("", 13.5f, secondary = true)
        val hintView = text("", 13f, secondary = true)
        val action = button(getString(act), filled = true, onAct)
        val notNow = button(getString(R.string.not_now), filled = false) {
            SearchSetup.skip(step)
            refreshAll()
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(action, LinearLayout.LayoutParams(-2, -2))
            if (step.counted) addView(notNow, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(18), dp(14))
            tag = title
            addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(1) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(getString(title), 16f, weight = 500))
                addView(descView, gap(3))
                addView(hintView, gap(6))
                addView(buttons, gap(12))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        }
        target.addView(row, Ui.lp())
        // The widget and the tile have no switch below, so search finds them here.
        if (step == SearchSetup.Step.WIDGET || step == SearchSetup.Step.TILE) indexed(title, getString(desc), row)
        refreshers += {
            val done = SearchSetup.done(this, step)
            val skipped = !done && SearchSetup.skipped(step)
            mark.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when {
                    done -> setColor(c.goodContainer)
                    skipped -> setColor(c.highest)
                    else -> setStroke(dp(2), c.outline)
                }
            }
            mark.setImageResource(if (done) R.drawable.ic_check else 0)
            mark.imageTintList = ColorStateList.valueOf(c.good)
            val placed = step == SearchSetup.Step.WIDGET || step == SearchSetup.Step.TILE
            descView.text = when {
                done -> getString(if (placed) R.string.search_setup_added else R.string.search_setup_on)
                skipped -> getString(if (placed) R.string.search_setup_skipped_widget else R.string.search_setup_skipped)
                step.counted -> getString(desc)
                else -> getString(R.string.search_setup_optional, getString(desc))
            }
            descView.setTextColor(if (done) c.good else c.textSecondary)
            val h = if (done) null else hint()
            hintView.text = h
            hintView.visibility = if (h == null) View.GONE else View.VISIBLE
            // A button that can't work is left out; the hint says what to do instead.
            action.visibility = if (h == null) View.VISIBLE else View.GONE
            notNow.visibility = if (skipped || !step.counted) View.GONE else View.VISIBLE
            buttons.visibility = if (done || (action.visibility == View.GONE && notNow.visibility == View.GONE)) View.GONE
            else View.VISIBLE
        }
    }

    private fun canPinWidget() = SearchSetup.canPinWidget(this)

    private fun addSearchWidget() = SearchSetup.pinWidget(this)

    private fun addSearchTile() = SearchSetup.addTile(this) { refreshAll() }

    /** What to do with a permission answer, by request code: a switch or a checklist step waits on each. */
    private val pendingPermission = HashMap<Int, (Boolean) -> Unit>()

    private fun granted(permission: String) =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Turns on what needs [permission]: at once if it's granted, otherwise after Android asks and
     * the user allows it. [set] gets the answer.
     */
    private fun permissionRequest(permission: String, set: (Boolean) -> Unit): () -> Unit {
        val code = REQUEST_SEARCH_PERMISSION + pendingPermission.size
        pendingPermission[code] = set
        return {
            if (!granted(permission)) {
                requestPermissions(arrayOf(permission), code)
            } else {
                set(true)
                refreshAll()
            }
        }
    }

    /**
     * A switch that needs [permission] (ADR 0008): turning it on asks for it, and it stays on only
     * if granted. Once Android stops asking, the switch points to the app's permission page.
     */
    private fun permissionToggle(title: Int, desc: Int, permission: String, value: () -> Boolean, set: (Boolean) -> Unit) {
        lateinit var s: M3Switch
        val ask = permissionRequest(permission) { granted ->
            set(granted)
            s.setChecked(granted, animate = true)
        }
        s = toggle(title, desc, { value() && granted(permission) }) { on ->
            if (!on || granted(permission)) set(on) else ask()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val answer = pendingPermission[requestCode] ?: return
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        answer(granted)
        refreshAll()
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
        indexed(title, getString(desc), row)
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
                changed()
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
                changed()
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
