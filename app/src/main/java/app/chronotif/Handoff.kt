// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The rows under Search's web row (ADR 0023): the same query handed to an app's own search. A
 * row shows only while its app is installed, and Chronotif sends nothing itself.
 */
enum class Handoff(val pkg: String) {
    PLAY_STORE("com.android.vending"),
    MAPS("com.google.android.apps.maps"),
    SPOTIFY("com.spotify.music"),
    YOUTUBE_MUSIC("com.google.android.apps.youtube.music");

    /** The ways into this app's search, best first; the first one the app takes is used. */
    fun intents(query: String): List<Intent> {
        val q = Uri.encode(query.trim())
        return when (this) {
            PLAY_STORE -> listOf(view("market://search?q=$q&c=apps"))
            MAPS -> listOf(view("geo:0,0?q=$q"))
            // Spotify's documented search link.
            SPOTIFY -> listOf(view("spotify:search:$q"))
            YOUTUBE_MUSIC -> listOf(
                Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, query.trim()),
                view("https://music.youtube.com/search?q=$q"),
            )
        }.map { it.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Opens the app's search for [query]; false when the app took none of the ways in. */
    fun open(context: Context, query: String): Boolean = intents(query).any { intent ->
        val home = if (this == YOUTUBE_MUSIC) context.packageManager.getLaunchIntentForPackage(pkg) else null
        // YouTube Music drops a search it gets from cold and only opens, so it starts first and
        // the search lands on top of it, as Contacts opens a WhatsApp chat.
        runCatching {
            if (home != null) context.startActivities(arrayOf(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), intent))
            else context.startActivity(intent)
        }.isSuccess
    }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
}
