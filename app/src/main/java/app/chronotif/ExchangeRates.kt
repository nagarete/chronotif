// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import app.chronotif.core.Money
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Search's exchange rates (ADR 0008): the European Central Bank's public daily reference rates.
 * Nothing is downloaded until *Currency rates* is turned on, and then only when a currency
 * question meets rates more than a day old. The request carries nothing typed; it's the same
 * file for everyone.
 */
object ExchangeRates {
    private const val SOURCE = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(1)
    /** After a failed download, wait this long before trying again, so typing doesn't retry per key. */
    private val RETRY_MS = TimeUnit.MINUTES.toMillis(1)

    private val main = Handler(Looper.getMainLooper())
    private var fetching = false
    private var failedAt = 0L

    val rates: Money.Rates? get() = Money.decode(Prefs.exchangeRates)
    val loading: Boolean get() = fetching
    /** The last download failed and there's nothing saved to fall back on. */
    val failed: Boolean get() = failedAt != 0L && rates == null

    /**
     * Downloads fresh rates if they're on, over a day old, and not already coming. [done] runs on
     * the main thread when a download ends, whether it worked or not.
     */
    fun refreshIfStale(done: () -> Unit) {
        if (!Prefs.currencyRates || fetching) return
        val now = System.currentTimeMillis()
        if (rates != null && now - Prefs.exchangeRatesFetchedAt < MAX_AGE_MS) return
        if (now - failedAt < RETRY_MS) return
        fetching = true
        thread(name = "exchange-rates") {
            val got = runCatching { download() }.getOrNull()
            main.post {
                fetching = false
                if (got != null) {
                    Prefs.exchangeRates = Money.encode(got)
                    Prefs.exchangeRatesFetchedAt = System.currentTimeMillis()
                    failedAt = 0L
                } else {
                    failedAt = System.currentTimeMillis()
                }
                done()
            }
        }
    }

    private fun download(): Money.Rates? {
        val c = URL(SOURCE).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Chronotif/${BuildConfig.VERSION_NAME}")
        return try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) null
            else Money.parseEcb(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /**
     * The currency an amount converts to when none is named: the one of the country the phone is
     * in (by its mobile network, then SIM), else of its language's region, else US dollars.
     */
    fun localCurrency(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val countries = listOfNotNull(
            runCatching { tm?.networkCountryIso }.getOrNull(),
            runCatching { tm?.simCountryIso }.getOrNull(),
            context.resources.configuration.locales[0].country,
        )
        for (cc in countries) {
            if (cc.length != 2) continue
            val code = runCatching { java.util.Currency.getInstance(Locale("", cc.uppercase())).currencyCode }.getOrNull()
            if (code != null && code in Money.CODES) return code
        }
        return "USD"
    }
}
