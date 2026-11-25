// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * Feedback as it's posted to chronotif.com/feedback: what it's about, the text, how to reach the
 * sender, and the phone's details when they're left in. website/worker/feedback.js reads it and
 * refuses anything past the same limits.
 */
object FeedbackMessage {
    const val MAX_TEXT = 4000
    const val MAX_CONTACT = 200

    enum class Kind(val key: String) { PROBLEM("problem"), IDEA("idea"), OTHER("other") }

    /** Something to send, and short enough to be taken. */
    fun ready(text: String, contact: String) =
        text.isNotBlank() && text.trim().length <= MAX_TEXT && contact.trim().length <= MAX_CONTACT

    /** The request body. [details] are the phone's, in the order the form shows them; null leaves them out. */
    fun body(kind: Kind?, text: String, contact: String, details: List<Pair<String, String>>?): String = buildString {
        append("{\"source\":\"app\"")
        if (kind != null) append(",\"kind\":").append(Usage.quote(kind.key))
        append(",\"text\":").append(Usage.quote(text.trim()))
        contact.trim().takeIf { it.isNotEmpty() }?.let { append(",\"contact\":").append(Usage.quote(it)) }
        if (details != null) {
            append(",\"details\":{")
            details.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(Usage.quote(k)).append(':').append(Usage.quote(v))
            }
            append('}')
        }
        append('}')
    }
}
