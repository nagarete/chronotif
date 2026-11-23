// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import java.util.UUID

/**
 * Usage data (ADR 0016), the parts that don't need Android: what an event is, how the day's counts
 * are kept and how a batch is written for PostHog. Nothing here may carry anything from a
 * notification, a contact, a calendar event or a Search query; the names below are the whole list
 * of what's counted, and `docs/usage-data.md` lists them for people using the app.
 */
object Usage {
    /** What's counted per day. A count is how often, never what. */
    enum class Count(val key: String) {
        PANEL_OPENED("panel_opened"),
        LOCK_SHOWN("lock_shown"),
        STACK_OPENED("stack_opened"),
        PREVIEW_OPENED("preview_opened"),
        NOTIFICATION_OPENED("notification_opened"),
        ACTION_TAPPED("action_tapped"),
        REPLY_SENT("reply_sent"),
        CLEARED("cleared"),
        SNOOZED("snoozed"),
        MEDIA_DISMISSED("media_dismissed"),
        SEARCH_OPENED("search_opened"),
        SEARCH_APP("search_app"),
        SEARCH_CONTACT("search_contact"),
        SEARCH_CALENDAR("search_calendar"),
        SEARCH_SETTING("search_setting"),
        SEARCH_WEB("search_web"),
        DOUBLE_TAP_SLEEP("double_tap_sleep"),
        /** Lock-screen shortcuts used, by what they're set to; never which app. */
        SHORTCUT_FLASHLIGHT("shortcut_flashlight"),
        SHORTCUT_CAMERA("shortcut_camera"),
        SHORTCUT_QR("shortcut_qr"),
        SHORTCUT_DND("shortcut_dnd"),
        SHORTCUT_APP("shortcut_app"),
        QR_TILE("qr_tile"),
        /** An app made a Priority app or a Quiet app, from a Preview or Settings; never which app. */
        PRIORITY_SET("priority_set"),
        QUIET_SET("quiet_set"),
        TUTORIAL_PART_DONE("tutorial_part_done"),
        ANR("anr"),
        NATIVE_CRASH("native_crash"),
    }

    /** A rough size for a list of cards, so a slow open can be told apart from a full one. */
    fun cardBucket(cards: Int): String = when {
        cards <= 10 -> "0-10"
        cards <= 50 -> "10-50"
        else -> "50+"
    }

    // --- Day counts, kept as "key=n;key=n" ---

    fun decodeCounts(s: String?): Map<String, Int> = s.orEmpty().split(';').mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0) null else pair.substring(i + 1).toIntOrNull()?.let { pair.substring(0, i) to it }
    }.toMap()

    fun encodeCounts(counts: Map<String, Int>): String =
        counts.filterValues { it > 0 }.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** [all] less what was [sent], for counts that grew while a batch was on its way. */
    fun minus(all: Map<String, Int>, sent: Map<String, Int>): Map<String, Int> =
        all.mapValues { (k, v) -> v - (sent[k] ?: 0) }.filterValues { it > 0 }

    // --- Panel speed samples, kept as "at:ms:bucket:kept:pulled;..." ---

    /** One timed Panel open: when, how many ms until its cards were drawn, and how it opened. */
    data class Speed(val at: Long, val ms: Long, val cards: String, val kept: Boolean, val pulled: Boolean) {
        fun encode() = "$at:$ms:$cards:${if (kept) 1 else 0}:${if (pulled) 1 else 0}"
    }

    /** The most samples kept per day; later opens that day aren't timed. */
    const val MAX_SPEEDS = 30

    fun decodeSpeeds(s: String?): List<Speed> = s.orEmpty().split(';').mapNotNull { one ->
        val p = one.split(':')
        if (p.size != 5) return@mapNotNull null
        val at = p[0].toLongOrNull() ?: return@mapNotNull null
        val ms = p[1].toLongOrNull() ?: return@mapNotNull null
        Speed(at, ms, p[2], p[3] == "1", p[4] == "1")
    }

    fun encodeSpeeds(speeds: List<Speed>) = speeds.joinToString(";") { it.encode() }

    // --- Crashes ---

    /** The most stack frames sent for a crash. */
    const val MAX_FRAMES = 25

    /**
     * A crash as its exception types and stack frames, never its messages: a message can quote
     * whatever value the app was handling, and in Chronotif that's often a notification.
     */
    fun crashProps(t: Throwable): Map<String, Any?> {
        val types = generateSequence(t) { it.cause.takeIf { c -> c !== it } }.take(5).map { it.javaClass.name }.toList()
        val frames = t.stackTrace.take(MAX_FRAMES).joinToString("\n") { f ->
            "${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})"
        }
        return mapOf("exception" to types.first(), "causes" to types.drop(1).joinToString(" < "), "frames" to frames)
    }

    // --- The phone's system ---

    /**
     * The maker's name and version for the phone's system, such as "HyperOS 3.0" or "One UI 6.1",
     * from the system properties [prop] reads; "Android" when it's none of those.
     */
    fun system(prop: (String) -> String?): String {
        prop("ro.mi.os.version.name")?.takeIf { it.isNotBlank() }?.let { v ->
            // "OS3.0" on HyperOS 3; the build after it ("OS3.0.4.0.WOCMIXM") says nothing more useful.
            val n = Regex("""(\d+(?:\.\d+)?)""").find(v)?.value
            return if (n != null) "HyperOS $n" else "HyperOS"
        }
        prop("ro.miui.ui.version.name")?.takeIf { it.isNotBlank() }?.let { v ->
            // "V140" is MIUI 14, "V125" MIUI 12.5, "V10" MIUI 10.
            val d = v.removePrefix("V")
            return when {
                d.length == 3 && d[2] == '0' -> "MIUI ${d.take(2)}"
                d.length == 3 -> "MIUI ${d.take(2)}.${d[2]}"
                else -> "MIUI $d"
            }
        }
        prop("ro.build.version.oneui")?.toIntOrNull()?.let { v ->
            // 60101 is One UI 6.1.1; the patch is left out.
            return "One UI ${v / 10000}.${v / 100 % 100}"
        }
        prop("ro.build.version.oplusrom")?.takeIf { it.isNotBlank() }?.let { v ->
            return "ColorOS ${v.removePrefix("V").substringBefore('.')}"
        }
        return "Android"
    }

    // --- Events, written for PostHog's batch endpoint ---

    class Event(val name: String, val at: Long, val props: Map<String, Any?>, val uuid: String)

    /**
     * An event whose id comes from what it is, so a batch sent twice (its answer lost on the way)
     * is recorded once.
     */
    fun event(install: String, name: String, at: Long, props: Map<String, Any?>, seq: Int = 0) =
        Event(name, at, props, UUID.nameUUIDFromBytes("$install:$name:$at:$seq".toByteArray()).toString())

    /**
     * The request body. Every event says it has no person profile (an install is all there is)
     * and that PostHog mustn't look up where the IP address is: the country comes from the phone.
     */
    fun batch(key: String, install: String, events: List<Event>): String = buildString {
        append("{\"api_key\":").append(quote(key)).append(",\"batch\":[")
        events.forEachIndexed { i, e ->
            if (i > 0) append(',')
            val props = e.props + mapOf(
                "distinct_id" to install,
                "\$process_person_profile" to false,
                "\$geoip_disable" to true,
                "\$lib" to "chronotif",
            )
            append("{\"event\":").append(quote(e.name))
            append(",\"uuid\":").append(quote(e.uuid))
            append(",\"timestamp\":").append(quote(java.time.Instant.ofEpochMilli(e.at).toString()))
            append(",\"properties\":{")
            props.entries.forEachIndexed { j, (k, v) ->
                if (j > 0) append(',')
                append(quote(k)).append(':').append(value(v))
            }
            append("}}")
        }
        append("]}")
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Int, is Long -> v.toString()
        is Number -> v.toDouble().takeIf { it.isFinite() }?.toString() ?: "null"
        else -> quote(v.toString())
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }
}
