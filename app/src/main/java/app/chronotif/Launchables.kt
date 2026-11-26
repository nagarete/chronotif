// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.LruCache
import app.chronotif.core.AppMatch
import java.util.concurrent.Executors

/** One launcher entry, in one profile. A work-profile or dual copy of an app is its own entry. */
class Launchable(val label: String, internal val info: LauncherActivityInfo) {
    val component: ComponentName get() = info.componentName
    val user: UserHandle get() = info.user
    /** Same form as [Entry.appId], so suggestions can be matched to notifications. */
    val appId = "${info.componentName.packageName}|${info.user.hashCode()}"
    /** In the profile Chronotif runs in, not a work profile or dual copy. */
    val mine: Boolean get() = info.user == android.os.Process.myUserHandle()
}

/**
 * Search's data (ADR 0008): the launchable apps, read through [LauncherApps] off the main
 * thread, their badged icons, and the suggestion row built from [Prefs.lastNotified].
 */
object Launchables {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val icons = LruCache<String, Drawable>(64)

    /** Every launcher entry in every profile Chronotif can see, alphabetical. Delivered on the main thread. */
    fun load(context: Context, done: (List<Launchable>) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val la = app.getSystemService(LauncherApps::class.java)
            val all = la.profiles.flatMap { user -> runCatching { la.getActivityList(null, user) }.getOrDefault(emptyList()) }
                .filter { it.componentName.packageName != BuildConfig.APPLICATION_ID }
                .map { Launchable(it.label?.toString().orEmpty(), it) }
                .sortedBy { AppMatch.fold(it.label) }
            main.post { done(all) }
        }
    }

    /** The apps that notified most recently, newest first: one entry per app. */
    fun suggestions(apps: List<Launchable>): List<Launchable> {
        val byApp = LinkedHashMap<String, Launchable>()
        for (a in apps) byApp.putIfAbsent(a.appId, a)
        return AppMatch.suggestions(Prefs.lastNotified, byApp::containsKey).mapNotNull(byApp::get)
    }

    /** The icon, badged for a work or dual profile, delivered on the main thread. */
    fun icon(context: Context, a: Launchable, done: (Drawable) -> Unit) {
        val k = "${a.component.flattenToShortString()}|${a.appId}"
        icons.get(k)?.let { return done(it) }
        val density = context.resources.displayMetrics.densityDpi
        io.execute {
            val d = runCatching { a.info.getBadgedIcon(density) }.getOrNull() ?: return@execute
            main.post {
                icons.put(k, d)
                done(d)
            }
        }
    }

    /** A lasting name for [a], kept by a Lock-screen shortcut: its activity and its profile's serial number. */
    fun key(context: Context, a: Launchable): String {
        val serial = context.getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(a.user)
        return "${a.component.flattenToString()}#$serial"
    }

    /** The launcher entry [key] names, or null once its app or profile is gone. Reads the package manager. */
    fun find(context: Context, key: String?): Launchable? {
        val component = ComponentName.unflattenFromString(key?.substringBefore('#') ?: return null) ?: return null
        val serial = key.substringAfter('#').toLongOrNull() ?: return null
        val user = context.getSystemService(android.os.UserManager::class.java).getUserForSerialNumber(serial) ?: return null
        val la = context.getSystemService(LauncherApps::class.java)
        val info = runCatching { la.getActivityList(component.packageName, user) }.getOrNull()
            ?.firstOrNull { it.componentName == component } ?: return null
        return Launchable(info.label?.toString().orEmpty(), info)
    }

    /**
     * Opens [a]; false when it can't. A result read earlier may be gone by the tap: once its app
     * is uninstalled or disabled, Android refuses with a SecurityException.
     */
    fun launch(context: Context, a: Launchable): Boolean = try {
        context.getSystemService(LauncherApps::class.java).startMainActivity(a.component, a.user, null, null)
        true
    } catch (_: SecurityException) {
        false
    }
}
