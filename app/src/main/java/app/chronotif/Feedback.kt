// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.chronotif.core.FeedbackMessage
import app.chronotif.core.Usage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Feedback (GLOSSARY): a message the user writes in Settings and sends on purpose. It goes to
 * chronotif.com/feedback, whose Worker emails it to the maintainer; it's never public. Nothing is
 * sent until Send is tapped, the debug build included, and a failed send is never retried on its
 * own: the text stays in the form.
 */
object Feedback {
    private const val ENDPOINT = "https://chronotif.com/feedback"

    enum class Result { SENT, OFFLINE, TOO_MANY, FAILED }

    /** The form as the user left it, kept while the app runs so switching pages doesn't lose it. */
    object Draft {
        var kind: FeedbackMessage.Kind? = null
        var text = ""
        var contact = ""
        var withDetails = true
        var sending = false
        /** What the last send came to, shown under Send until the form is used again. */
        var result: Result? = null
        /**
         * The form showing now, told when a send ends. Weak, so a page that was left or rebuilt
         * (a rotation mid-send) isn't kept alive, and the one shown after it still hears the result.
         */
        var shown: java.lang.ref.WeakReference<() -> Unit>? = null

        fun clear() {
            kind = null
            text = ""
            contact = ""
            withDetails = true
        }
    }

    /** Reading system properties and sending: off the main thread, one at a time. The thread ends when idle. */
    internal var worker: Executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "feedback")
    }

    /** Posts a body and returns the HTTP status. Tests replace it. */
    internal var transport: (String) -> Int = ::post

    private val main = Handler(Looper.getMainLooper())

    /**
     * The phone's details, as the form shows them and the email lists them: the same app version,
     * channel, Android version, model and maker's system Usage data sends. Reads system
     * properties, so [done] is called on the main thread once [worker] has them.
     */
    fun details(done: (List<Pair<String, String>>) -> Unit) {
        known?.let { done(it); return }
        worker.execute {
            val channel = when {
                BuildConfig.DEBUG -> "debug"
                BuildConfig.NIGHTLY -> "nightly"
                else -> "stable"
            }
            val list = listOf(
                "Chronotif" to "${BuildConfig.VERSION_NAME} ($channel)",
                "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                "Phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "System" to Usage.system(UsageData::systemProperty),
            )
            known = list
            main.post { done(list) }
        }
    }

    /** The details once read; they don't change while the app runs. */
    @Volatile private var known: List<Pair<String, String>>? = null

    /** Sends [body] and calls [done] on the main thread with how it went. */
    fun send(body: String, done: (Result) -> Unit) {
        worker.execute {
            val result = try {
                when (transport(body)) {
                    in 200..299 -> Result.SENT
                    429 -> Result.TOO_MANY
                    else -> Result.FAILED
                }
            } catch (_: IOException) {
                Result.OFFLINE
            }
            main.post { done(result) }
        }
    }

    private fun post(body: String): Int {
        val bytes = body.toByteArray()
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Chronotif/${BuildConfig.VERSION_NAME}")
        c.setFixedLengthStreamingMode(bytes.size)
        return try {
            c.outputStream.use { it.write(bytes) }
            c.responseCode
        } finally {
            c.disconnect()
        }
    }
}
