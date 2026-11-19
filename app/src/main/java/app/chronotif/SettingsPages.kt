// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import app.chronotif.core.AppMatch

/**
 * The Settings pages Search can open (ADR 0008). Android offers no way to search Settings, so this
 * is a fixed list of the pages it lets apps open directly; a page this phone doesn't have is left
 * out. Each matches its name and a few other words people type for it.
 */
object SettingsPages {
    class Page(val label: Int, val keywords: List<String>, val intent: Intent)

    private fun page(label: Int, action: String, vararg keywords: String, data: Uri? = null) =
        Page(label, keywords.toList(), Intent(action).apply { if (data != null) this.data = data })

    private val all: List<Page> = listOf(
        page(R.string.settings_wifi, Settings.ACTION_WIFI_SETTINGS, "wifi", "wi-fi", "wlan", "internet"),
        page(R.string.settings_bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS, "bluetooth", "bt", "pair", "headphones", "earbuds"),
        page(R.string.settings_mobile, Settings.ACTION_DATA_ROAMING_SETTINGS, "mobile network", "cellular", "sim", "roaming", "data", "apn"),
        page(R.string.settings_data_usage, Settings.ACTION_DATA_USAGE_SETTINGS, "data usage", "data saver"),
        page(R.string.settings_airplane, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "airplane", "flight mode", "aeroplane"),
        page(R.string.settings_hotspot, Settings.ACTION_WIRELESS_SETTINGS, "hotspot", "tethering", "network", "connections"),
        page(R.string.settings_vpn, Settings.ACTION_VPN_SETTINGS, "vpn"),
        page(R.string.settings_nfc, Settings.ACTION_NFC_SETTINGS, "nfc", "tap to pay", "contactless"),
        page(R.string.settings_nfc_payment, Settings.ACTION_NFC_PAYMENT_SETTINGS, "contactless payments", "wallet", "tap to pay"),
        page(R.string.settings_cast, Settings.ACTION_CAST_SETTINGS, "cast", "screen mirroring", "chromecast"),
        page(R.string.settings_display, Settings.ACTION_DISPLAY_SETTINGS, "display", "brightness", "screen", "dark mode", "font size", "refresh rate", "screen timeout"),
        page(R.string.settings_night_light, Settings.ACTION_NIGHT_DISPLAY_SETTINGS, "night light", "reading mode", "eye comfort", "blue light"),
        page(R.string.settings_wallpaper, Intent.ACTION_SET_WALLPAPER, "wallpaper", "background"),
        page(R.string.settings_sound, Settings.ACTION_SOUND_SETTINGS, "sound", "volume", "ringtone", "vibration", "silent"),
        page(R.string.settings_dnd, Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "do not disturb", "dnd", "focus", "quiet"),
        page(R.string.settings_notifications, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "notification access", "listener"),
        page(R.string.settings_battery, Intent.ACTION_POWER_USAGE_SUMMARY, "battery", "power", "charging"),
        page(R.string.settings_battery_saver, Settings.ACTION_BATTERY_SAVER_SETTINGS, "battery saver", "power saving", "low power"),
        page(R.string.settings_storage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "storage", "space", "memory", "clean"),
        page(R.string.settings_apps, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, "apps", "applications", "uninstall", "app info"),
        page(R.string.settings_default_apps, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "default apps", "default browser", "assistant", "home app"),
        page(R.string.settings_overlay, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "display over other apps", "overlay", "draw over"),
        page(R.string.settings_usage_access, Settings.ACTION_USAGE_ACCESS_SETTINGS, "usage access"),
        page(R.string.settings_location, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "location", "gps"),
        page(R.string.settings_security, Settings.ACTION_SECURITY_SETTINGS, "security", "lock screen", "screen lock", "password", "pin", "pattern"),
        page(R.string.settings_fingerprint, Settings.ACTION_BIOMETRIC_ENROLL, "fingerprint", "face unlock", "biometric"),
        page(R.string.settings_privacy, Settings.ACTION_PRIVACY_SETTINGS, "privacy", "permissions", "permission manager"),
        page(R.string.settings_accounts, Settings.ACTION_SYNC_SETTINGS, "accounts", "sync", "google account"),
        page(R.string.settings_accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility", "talkback"),
        page(R.string.settings_language, Settings.ACTION_LOCALE_SETTINGS, "language", "bahasa", "locale"),
        page(R.string.settings_keyboard, Settings.ACTION_INPUT_METHOD_SETTINGS, "keyboard", "input", "gboard"),
        page(R.string.settings_date, Settings.ACTION_DATE_SETTINGS, "date", "time", "time zone", "clock"),
        page(R.string.settings_developer, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "developer options", "usb debugging", "adb"),
        page(R.string.settings_about, Settings.ACTION_DEVICE_INFO_SETTINGS, "about phone", "device info", "model", "android version", "imei"),
        page(R.string.settings_update, "android.settings.SYSTEM_UPDATE_SETTINGS", "system update", "software update", "update"),
        page(R.string.settings_home, Settings.ACTION_HOME_SETTINGS, "home screen", "launcher"),
        page(R.string.settings_print, Settings.ACTION_PRINT_SETTINGS, "print", "printer"),
        page(R.string.settings_all, Settings.ACTION_SETTINGS, "settings", "pengaturan", "setelan"),
    )

    /** The pages this phone has, cached: resolving them all on every keystroke would be slow. */
    private var available: List<Page>? = null

    private fun available(context: Context): List<Page> = available ?: all.filter {
        context.packageManager.resolveActivity(it.intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.also { available = it }

    /** Shown at most; a short query matches many pages, and the rest of the list is apps and the web. */
    const val SHOWN = 4

    /**
     * Pages whose name or words start with [query] (or have a word that does), best match first,
     * then in the list's order. A match in the middle of a word doesn't count: "s" would find them all.
     */
    fun search(context: Context, query: String): List<Page> {
        if (AppMatch.fold(query).isEmpty()) return emptyList()
        return available(context).mapNotNull { p ->
            val tiers = (listOf(context.getString(p.label)) + p.keywords).mapNotNull { AppMatch.tier(it, query) }
            tiers.filter { it < 2 }.minOrNull()?.let { it to p }
        }.sortedBy { it.first }.map { it.second }.take(SHOWN)
    }

    fun open(context: Context, page: Page) {
        runCatching { context.startActivity(Intent(page.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
