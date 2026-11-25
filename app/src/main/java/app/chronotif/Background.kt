// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One background thread that runs work in the order it was handed in, one piece at a time, and
 * ends after a few idle seconds. Work must catch what it expects to throw: an uncaught exception
 * here ends the app, as it would on the main thread.
 */
open class Lane(name: String) {
    private val executor = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, name)
    }.apply { allowCoreThreadTimeOut(true) }
    private val main = Handler(Looper.getMainLooper())

    fun run(work: () -> Unit) = executor.execute(work)

    /** Runs [work] on the lane and hands its result to [done] on the main thread. */
    fun <T> run(work: () -> T, done: (T) -> Unit) = executor.execute {
        val result = work()
        main.post { done(result) }
    }
}

/**
 * The lane for the small system reads the open and wake paths need (package manager, camera
 * service, Do Not Disturb, bundled assets), so none of them run on the main thread.
 */
object Background : Lane("chronotif-background")
