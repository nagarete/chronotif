package app.chronotif.demo

import android.app.Activity
import android.os.Bundle

/** Tapping a made-up notification opens nothing. */
class Open : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
