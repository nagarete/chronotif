// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.SharedPreferences

/**
 * Excluded apps and channels (ADR 0005): Chronotif leaves them out entirely, and Android's own
 * notifications are untouched. Also remembers which apps and channels have posted, because a
 * listener can't read an app's channel list, and the settings screen can only offer what it has seen.
 */
object Exclusions {
    /** A channel Chronotif has seen, under its app. */
    class Channel(val id: String, val name: String)

    private const val SEP = '\u001F'
    private lateinit var sp: SharedPreferences
    private val apps = HashSet<String>()
    private val channels = HashSet<String>()
    /** appId to channel id to name, in the order first seen. */
    private val seen = LinkedHashMap<String, LinkedHashMap<String, String>>()

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("exclusions", Context.MODE_PRIVATE)
        apps.clear(); apps += sp.getStringSet("apps", null).orEmpty()
        channels.clear(); channels += sp.getStringSet("channels", null).orEmpty()
        seen.clear()
        for (s in sp.getStringSet("seen", null).orEmpty()) {
            val (app, id, name) = s.split(SEP, limit = 3).takeIf { it.size == 3 } ?: continue
            seen.getOrPut(app) { LinkedHashMap() }[id] = name
        }
    }

    fun isExcluded(e: Entry): Boolean =
        e.appId in apps || channelKey(e.appId, e.channelId) in channels

    fun appExcluded(appId: String) = appId in apps
    fun channelExcluded(appId: String, channelId: String) = channelKey(appId, channelId) in channels

    fun setApp(appId: String, excluded: Boolean) {
        if (if (excluded) apps.add(appId) else apps.remove(appId)) {
            sp.edit().putStringSet("apps", HashSet(apps)).apply()
            NotifStore.changed()
        }
    }

    fun setChannel(appId: String, channelId: String, excluded: Boolean) {
        val k = channelKey(appId, channelId)
        if (if (excluded) channels.add(k) else channels.remove(k)) {
            sp.edit().putStringSet("channels", HashSet(channels)).apply()
            NotifStore.changed()
        }
    }

    /** Apps that have posted since this was first installed, by appId, with their seen channels. */
    fun seenApps(): Map<String, List<Channel>> =
        seen.mapValues { (_, chs) -> chs.map { (id, name) -> Channel(id, name) } }

    /** Remembers [e]'s app and channel. Writes only when something is new or renamed. */
    fun note(e: Entry) {
        if (!::sp.isInitialized || e.pkg == BuildConfig.APPLICATION_ID) return
        val chs = seen.getOrPut(e.appId) { LinkedHashMap() }
        val name = e.channelName ?: chs[e.channelId] ?: e.channelId
        if (chs[e.channelId] == name) return
        chs[e.channelId] = name
        val all = seen.flatMap { (app, c) -> c.map { (id, n) -> "$app$SEP$id$SEP$n" } }
        sp.edit().putStringSet("seen", all.toHashSet()).apply()
    }

    /** Drops apps that are no longer installed, so the list doesn't keep dead entries forever. */
    fun forget(appId: String) {
        if (seen.remove(appId) == null) return
        apps.remove(appId)
        channels.removeAll { it.startsWith("$appId$SEP") }
        val all = seen.flatMap { (app, c) -> c.map { (id, n) -> "$app$SEP$id$SEP$n" } }
        sp.edit()
            .putStringSet("seen", all.toHashSet())
            .putStringSet("apps", HashSet(apps))
            .putStringSet("channels", HashSet(channels))
            .apply()
    }

    private fun channelKey(appId: String, channelId: String) = "$appId$SEP$channelId"
}
