// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.chronotif.BuildConfig
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.core.WhatsNew
import org.json.JSONObject

/**
 * What's new: after an update, the first panel open shows the release notes the user hasn't seen,
 * on a sheet over the cards. While it covers them the panel isn't seen, so it's no check (see
 * [PanelView.held]). It shows once per update, however it's closed.
 */
@SuppressLint("ViewConstructor")
class WhatsNewSheet private constructor(
    context: Context,
    private val host: NotifList.Host,
    releases: List<WhatsNew.Release>,
    private val onClosed: () -> Unit,
) : FrameLayout(context), Overlay {

    companion object {
        const val NOTES_URL = "https://chronotif.com/releases"

        /** The notes this update brings that haven't been shown; null if none, or already looked at. */
        fun pending(context: Context): List<WhatsNew.Release>? {
            val code = BuildConfig.VERSION_CODE.toLong()
            if (Prefs.whatsNewCode == code) return null
            val releases = read(context)
            val current = BuildConfig.VERSION_NAME
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val fresh = info.firstInstallTime == info.lastUpdateTime
            if (Prefs.whatsNewSeen == null && fresh) {
                // A new install has nothing to catch up on.
                Prefs.whatsNewSeen = WhatsNew.allKeys(releases, current)
                Prefs.whatsNewCode = code
                return null
            }
            val unseen = WhatsNew.unseen(releases, current, Prefs.whatsNewSeen)
            if (unseen.isEmpty()) {
                Prefs.whatsNewCode = code
                return null
            }
            return unseen
        }

        /** Records everything this version has as seen, so it shows once. */
        private fun markSeen(context: Context) {
            val all = WhatsNew.allKeys(read(context), BuildConfig.VERSION_NAME)
            Prefs.whatsNewSeen = Prefs.whatsNewSeen.orEmpty() + all
            Prefs.whatsNewCode = BuildConfig.VERSION_CODE.toLong()
        }

        /** The release notes bundled from the website's releases.json: the next version, then stable ones. */
        private fun read(context: Context): List<WhatsNew.Release> = runCatching {
            val json = JSONObject(context.assets.open("releases.json").bufferedReader().use { it.readText() })
            fun release(o: JSONObject): WhatsNew.Release {
                val notes = o.optJSONArray("notes")
                return WhatsNew.Release(
                    o.getString("version"),
                    o.optString("headline").takeIf { it.isNotBlank() },
                    if (notes == null) emptyList() else (0 until notes.length()).map { notes.getString(it) },
                )
            }
            val out = ArrayList<WhatsNew.Release>()
            json.optJSONObject("next")?.let { out += release(it) }
            json.optJSONArray("stable")?.let { a -> for (i in 0 until a.length()) out += release(a.getJSONObject(i)) }
            out
        }.getOrDefault(emptyList())

        /** Lays the sheet over [anchor]'s window, unless something already covers it. */
        fun show(anchor: View, host: NotifList.Host, releases: List<WhatsNew.Release>, onClosed: () -> Unit): Boolean {
            val root = anchor.rootView as? ViewGroup ?: return false
            if ((0 until root.childCount).any { root.getChildAt(it) is Overlay }) return false
            markSeen(anchor.context)
            root.addView(WhatsNewSheet(anchor.context, host, releases, onClosed), ViewGroup.LayoutParams(-1, -1))
            return true
        }
    }

    private val pal = Palette(context)
    private val card: LinearLayout
    private var closed = false

    init {
        setBackgroundColor(0x66000000)
        // Taps beside the sheet close it, and never reach the cards under it.
        isClickable = true
        setOnClickListener { close() }

        val notes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        releases.forEachIndexed { i, r ->
            if (releases.size > 1) notes.addView(Ui.text(context, 13f, pal.textSecondary, bold = true).apply {
                text = context.getString(R.string.whats_new_version, r.version)
            }, Ui.lp().apply { topMargin = dp(if (i == 0) 4 else 16); bottomMargin = dp(2) })
            for (n in r.notes) notes.addView(Ui.text(context, 15f, pal.text).apply {
                text = SpannableStringBuilder(n).apply {
                    setSpan(BulletSpan(dp(10), pal.textSecondary, dp(2)), 0, length, 0)
                }
            }, Ui.lp().apply { topMargin = dp(10) })
        }

        val first = releases.first()
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            background = Ui.rounded(pal.card or 0xFF000000.toInt(), dp(28).toFloat())
            isClickable = true
            addView(Ui.text(context, 13f, pal.textSecondary).apply {
                text = context.getString(R.string.whats_new_updated, BuildConfig.VERSION_NAME)
            })
            addView(Ui.text(context, 21f, pal.text, bold = true).apply {
                text = first.headline ?: context.getString(R.string.whats_new_title)
            }, Ui.lp().apply { topMargin = dp(6) })
            addView(object : ScrollView(context) {
                override fun onMeasure(w: Int, h: Int) {
                    val cap = (resources.displayMetrics.heightPixels * 0.55f).toInt()
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
                }
            }.apply {
                isVerticalScrollBarEnabled = false
                addView(notes)
            }, Ui.lp().apply { topMargin = dp(6) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(button(context.getString(R.string.whats_new_all), filled = false) {
                    close()
                    try {
                        host.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NOTES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        host.launched()
                    } catch (_: RuntimeException) {
                    }
                })
                addView(button(context.getString(R.string.whats_new_done), filled = true) { close() },
                    Ui.lp(-2).apply { marginStart = dp(8) })
            }, Ui.lp().apply { topMargin = dp(16) })
        }
        addView(card, LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        alpha = 0f
        card.translationY = dp(24).toFloat()
        animate().alpha(1f).setDuration(180).start()
        card.animate().translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(18), 0, dp(18), 0)
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else pal.text)
        background = Ui.pressable(if (filled) pal.accent else pal.chip, dp(20).toFloat(), pal.ripple)
        setOnClickListener { onClick() }
    }

    override fun close() {
        if (closed) return
        closed = true
        animate().alpha(0f).setDuration(150).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
        }.start()
        onClosed()
    }
}
