// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import java.time.ZoneId

/**
 * "time in tokyo" for Search (ADR 0008), offline: every city in the time-zone database, plus
 * common places it doesn't name (Bali, Mumbai, Beijing) and countries with one time zone.
 */
object WorldClock {
    class Place(val name: String, val zone: ZoneId)

    private val PREFIXES = listOf("what time is it in ", "what's the time in ", "whats the time in ", "time in ", "time at ", "jam di ", "waktu di ", "time ")
    private val SUFFIXES = listOf(" time now", " time", " now")

    /** Places the database doesn't name, or names differently. */
    private val ALIASES: Map<String, String> = mapOf(
        "bali" to "Asia/Makassar", "denpasar" to "Asia/Makassar", "surabaya" to "Asia/Jakarta",
        "bandung" to "Asia/Jakarta", "yogyakarta" to "Asia/Jakarta", "jogja" to "Asia/Jakarta",
        "medan" to "Asia/Jakarta", "batam" to "Asia/Jakarta", "pontianak" to "Asia/Pontianak",
        "balikpapan" to "Asia/Makassar", "lombok" to "Asia/Makassar", "papua" to "Asia/Jayapura",
        "mumbai" to "Asia/Kolkata", "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata",
        "bangalore" to "Asia/Kolkata", "bengaluru" to "Asia/Kolkata", "chennai" to "Asia/Kolkata",
        "beijing" to "Asia/Shanghai", "shenzhen" to "Asia/Shanghai", "guangzhou" to "Asia/Shanghai",
        "ho chi minh city" to "Asia/Ho_Chi_Minh", "saigon" to "Asia/Ho_Chi_Minh", "hanoi" to "Asia/Bangkok",
        "osaka" to "Asia/Tokyo", "kyoto" to "Asia/Tokyo", "busan" to "Asia/Seoul",
        "penang" to "Asia/Kuala_Lumpur", "abu dhabi" to "Asia/Dubai", "mecca" to "Asia/Riyadh",
        "makkah" to "Asia/Riyadh", "medina" to "Asia/Riyadh", "jeddah" to "Asia/Riyadh",
        "doha" to "Asia/Qatar", "tel aviv" to "Asia/Jerusalem", "cebu" to "Asia/Manila",
        "san francisco" to "America/Los_Angeles", "sf" to "America/Los_Angeles", "seattle" to "America/Los_Angeles",
        "la" to "America/Los_Angeles", "las vegas" to "America/Los_Angeles", "san diego" to "America/Los_Angeles",
        "nyc" to "America/New_York", "washington" to "America/New_York", "boston" to "America/New_York",
        "miami" to "America/New_York", "atlanta" to "America/New_York", "dallas" to "America/Chicago",
        "houston" to "America/Chicago", "austin" to "America/Chicago", "san antonio" to "America/Chicago",
        "montreal" to "America/Toronto", "ottawa" to "America/Toronto", "rio de janeiro" to "America/Sao_Paulo",
        "rio" to "America/Sao_Paulo", "munich" to "Europe/Berlin", "frankfurt" to "Europe/Berlin",
        "hamburg" to "Europe/Berlin", "milan" to "Europe/Rome", "barcelona" to "Europe/Madrid",
        "geneva" to "Europe/Zurich", "edinburgh" to "Europe/London", "manchester" to "Europe/London",
        "st petersburg" to "Europe/Moscow", "saint petersburg" to "Europe/Moscow", "cape town" to "Africa/Johannesburg",
        "canberra" to "Australia/Sydney", "gold coast" to "Australia/Brisbane", "wellington" to "Pacific/Auckland",
        "hawaii" to "Pacific/Honolulu", "utc" to "UTC", "gmt" to "GMT",
        // Countries with one time zone.
        "japan" to "Asia/Tokyo", "korea" to "Asia/Seoul", "south korea" to "Asia/Seoul", "china" to "Asia/Shanghai",
        "taiwan" to "Asia/Taipei", "hong kong" to "Asia/Hong_Kong", "singapore" to "Asia/Singapore",
        "malaysia" to "Asia/Kuala_Lumpur", "thailand" to "Asia/Bangkok", "vietnam" to "Asia/Bangkok",
        "philippines" to "Asia/Manila", "india" to "Asia/Kolkata", "pakistan" to "Asia/Karachi",
        "bangladesh" to "Asia/Dhaka", "nepal" to "Asia/Kathmandu", "sri lanka" to "Asia/Colombo",
        "uae" to "Asia/Dubai", "united arab emirates" to "Asia/Dubai", "saudi arabia" to "Asia/Riyadh",
        "saudi" to "Asia/Riyadh", "qatar" to "Asia/Qatar", "turkey" to "Europe/Istanbul", "turkiye" to "Europe/Istanbul",
        "israel" to "Asia/Jerusalem", "egypt" to "Africa/Cairo", "uk" to "Europe/London",
        "united kingdom" to "Europe/London", "england" to "Europe/London", "britain" to "Europe/London",
        "ireland" to "Europe/Dublin", "france" to "Europe/Paris", "germany" to "Europe/Berlin",
        "netherlands" to "Europe/Amsterdam", "holland" to "Europe/Amsterdam", "belgium" to "Europe/Brussels",
        "switzerland" to "Europe/Zurich", "austria" to "Europe/Vienna", "italy" to "Europe/Rome",
        "spain" to "Europe/Madrid", "portugal" to "Europe/Lisbon", "sweden" to "Europe/Stockholm",
        "norway" to "Europe/Oslo", "denmark" to "Europe/Copenhagen", "finland" to "Europe/Helsinki",
        "poland" to "Europe/Warsaw", "greece" to "Europe/Athens", "ukraine" to "Europe/Kyiv",
        "nigeria" to "Africa/Lagos", "kenya" to "Africa/Nairobi", "south africa" to "Africa/Johannesburg",
        "new zealand" to "Pacific/Auckland", "argentina" to "America/Argentina/Buenos_Aires",
        "colombia" to "America/Bogota", "peru" to "America/Lima", "chile" to "America/Santiago",
    )

    private val cities: Map<String, String> by lazy {
        buildMap {
            for (id in ZoneId.getAvailableZoneIds()) {
                if ('/' !in id || id.startsWith("Etc/") || id.startsWith("SystemV/")) continue
                put(AppMatch.fold(id.substringAfterLast('/').replace('_', ' ')), id)
            }
        }
    }

    /** The place asked about, or null if [text] isn't a time question about a known place. */
    fun place(text: String): Place? {
        val q = AppMatch.fold(text).replace(Regex("\\s+"), " ").trimEnd('?', ' ')
        val prefix = PREFIXES.firstOrNull { q.startsWith(it) }
        val suffix = SUFFIXES.firstOrNull { q.endsWith(it) }
        val name = when {
            prefix != null -> q.removePrefix(prefix)
            suffix != null -> q.removeSuffix(suffix)
            else -> return null
        }.trim()
        if (name.isEmpty()) return null
        val id = ALIASES[name] ?: cities[name] ?: return null
        val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return null
        return Place(name.split(' ').joinToString(" ") { w -> if (w.length <= 3 && w in setOf("uk", "uae", "la", "sf", "nyc", "utc", "gmt")) w.uppercase() else w.replaceFirstChar(Char::titlecase) }, zone)
    }
}
