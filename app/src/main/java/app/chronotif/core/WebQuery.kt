// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import java.net.URLEncoder

/**
 * Search's last row (ADR 0008): what's typed goes to the default browser, as an address when it
 * looks like one ("klikbca.com"), otherwise as a search with the chosen engine. Chronotif sends
 * nothing itself; the browser does.
 */
object WebQuery {
    enum class Engine(val prefix: String) {
        GOOGLE("https://www.google.com/search?q="),
        DUCKDUCKGO("https://duckduckgo.com/?q="),
        BING("https://www.bing.com/search?q="),
        BRAVE("https://search.brave.com/search?q="),
    }

    private val ADDRESS = Regex("^(https?://)?([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(:\\d+)?([/?#]\\S*)?$", RegexOption.IGNORE_CASE)

    /** True for "klikbca.com", "github.com/x" or "http://192.168.1.1"-like text: one word with a dot and a real-looking ending. */
    fun isAddress(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.any(Char::isWhitespace)) return false
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return true
        // "1.5" or "3.14" are numbers, not addresses.
        return ADDRESS.matches(t) && !t.substringBefore('/').all { it.isDigit() || it == '.' }
    }

    fun address(text: String): String {
        val t = text.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    fun search(engine: Engine, text: String): String = engine.prefix + URLEncoder.encode(text.trim(), "UTF-8")
}
