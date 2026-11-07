// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.chronotif.Exclusions
import app.chronotif.R

/**
 * Excluded apps (ADR 0005). Lists the apps Chronotif has seen post, each with a switch, and under
 * it the channels Chronotif has seen. A switch that's on means Chronotif shows it, as in Android's
 * own settings. Order is fixed while the screen is open so rows don't jump as you toggle.
 */
class ExcludedAppsActivity : Activity() {

    private class App(val id: String, val label: String, val icon: Drawable?, val channels: List<Exclusions.Channel>)

    private lateinit var body: LinearLayout
    private val palette by lazy { Palette(this) }

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

    private fun build() {
        body.addView(text(getString(R.string.excluded_title), 28f, bold = true))
        body.addView(text(getString(R.string.excluded_intro), 15f, secondary = true), gap(8))
        body.addView(text(getString(R.string.excluded_stock_note), 13f, secondary = true), gap(8))

        val apps = resolve()
        if (apps.isEmpty()) {
            body.addView(text(getString(R.string.excluded_empty), 15f, secondary = true), gap(24))
            return
        }
        val sorted = apps.sortedWith(
            compareByDescending<App> { touched(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
        for (app in sorted) addApp(app)
    }

    private fun touched(app: App) =
        Exclusions.appExcluded(app.id) || app.channels.any { Exclusions.channelExcluded(app.id, it.id) }

    /** Seen apps that are still installed; uninstalled ones are forgotten. */
    private fun resolve(): List<App> {
        val launcher = getSystemService(LauncherApps::class.java)
        return Exclusions.seenApps().mapNotNull { (id, channels) ->
            val pkg = id.substringBefore('|')
            val user = id.substringAfter('|').toIntOrNull()
                ?.let { UserHandle.getUserHandleForUid(it * PER_USER_RANGE) } ?: Process.myUserHandle()
            // LauncherApps reaches the work profile; the plain lookup covers system packages it may skip.
            val info = runCatching { launcher.getApplicationInfo(pkg, 0, user) }.getOrNull()
                ?: if (user == Process.myUserHandle()) {
                    try {
                        packageManager.getApplicationInfo(pkg, 0)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                } else null
            if (info == null) {
                Exclusions.forget(id)
                return@mapNotNull null
            }
            val label = info.loadLabel(packageManager).toString()
            val icon = runCatching {
                val plain = info.loadIcon(packageManager)
                if (user == Process.myUserHandle()) plain else packageManager.getUserBadgedIcon(plain, user)
            }.getOrNull()
            App(id, label, icon, channels)
        }
    }

    private fun addApp(app: App) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        row.addView(ImageView(this).apply { setImageDrawable(app.icon) }, Ui.lp(dp(36), dp(36)))
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(app.label, 16f))
            addView(text(resources.getQuantityString(R.plurals.excluded_channels, app.channels.size, app.channels.size),
                13f, secondary = true))
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        val appSwitch = Switch(this).apply {
            isChecked = !Exclusions.appExcluded(app.id)
            contentDescription = app.label
        }
        row.addView(appSwitch, Ui.lp(-2))
        body.addView(row, gap(8))

        val channels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val channelSwitches = app.channels.map { ch ->
            Switch(this).apply {
                text = ch.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(palette.text)
                minimumHeight = dp(48)
                isChecked = !Exclusions.channelExcluded(app.id, ch.id)
                setOnCheckedChangeListener { _, on -> Exclusions.setChannel(app.id, ch.id, !on) }
            }.also { channels.addView(it, Ui.lp().apply { marginStart = dp(52) }) }
        }
        body.addView(channels)

        // An excluded app's channels keep their own choice, greyed, for when the app comes back.
        fun syncChannels() = channelSwitches.forEach { it.isEnabled = appSwitch.isChecked }
        syncChannels()
        appSwitch.setOnCheckedChangeListener { _, on ->
            Exclusions.setApp(app.id, !on)
            syncChannels()
        }
        row.setOnClickListener {
            channels.visibility = if (channels.visibility == View.GONE) View.VISIBLE else View.GONE
        }
    }

    private fun text(s: CharSequence, sp: Float, bold: Boolean = false, secondary: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (secondary) palette.textSecondary else palette.text)
        }

    private fun gap(top: Int) = Ui.lp().apply { topMargin = dp(top) }

    private companion object {
        /** UIDs per Android user; appId keeps only the user's number. */
        const val PER_USER_RANGE = 100000
    }
}
