// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification

/**
 * Made-up notifications Chronotif draws itself, for the Tutorial: the cast of the promo video
 * and the website, under [Entry.SAMPLE_PREFIX], so no installed app is named and nothing ever
 * reaches Android. Their apps' names and icons come from here, not the package manager.
 */
object Samples {

    class App(val id: String, val label: String, val color: Int) {
        val pkg = Entry.SAMPLE_PREFIX + id
    }

    val murmur = App("murmur", "Murmur", 0xFF2EAD6B.toInt())
    val parcelly = App("parcelly", "Parcelly", 0xFFE8813A.toInt())
    val daybook = App("daybook", "Daybook", 0xFF4C6FE0.toInt())
    val fernbank = App("fernbank", "Fernbank", 0xFF14806F.toInt())
    val stride = App("stride", "Stride", 0xFF8A56D6.toInt())

    private val apps = listOf(murmur, parcelly, daybook, fernbank, stride)

    fun app(pkg: String) = apps.firstOrNull { it.pkg == pkg }

    /** The made-up app's name, or null when [pkg] isn't one. */
    fun label(pkg: String): CharSequence? = app(pkg)?.label

    private val icons = HashMap<String, Drawable>()

    /** The made-up app's icon: its first letter on its colour. */
    fun icon(context: Context, pkg: String): Drawable? {
        val app = app(pkg) ?: return null
        return icons.getOrPut(pkg) { BitmapDrawable(context.resources, disc(app.label.take(1), app.color, 96)) }
    }

    /** A made-up person's picture: their initials on a colour. */
    fun avatar(name: String, color: Int): Icon =
        Icon.createWithBitmap(disc(name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString(""), color, 128))

    private fun disc(letters: String, color: Int, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = size * if (letters.length > 1) 0.38f else 0.5f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        c.drawText(letters, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return b
    }

    /** One made-up notification from [app], posted at [time]. */
    @Suppress("DEPRECATION") // The only way to build a StatusBarNotification without posting it.
    fun entry(context: Context, app: App, id: Int, time: Long, build: Notification.Builder.() -> Unit): Entry {
        val n = Notification.Builder(context, "samples")
            .setSmallIcon(Icon.createWithBitmap(disc(app.label.take(1), app.color, 96)))
            .setWhen(time)
            .apply(build)
            .build()
        val sbn = StatusBarNotification(app.pkg, app.pkg, id, null, Process.myUid(), 0, 0, n, Process.myUserHandle(), time)
        return Entry(sbn, Ranking(), time)
    }

    /** A reply button that types and sends in the card, as a chat app's does. Nothing receives it. */
    fun replyButton(label: CharSequence): Notification.Action =
        Notification.Action.Builder(null, label, null)
            .addRemoteInput(RemoteInput.Builder("reply").setLabel(label).build())
            .build()

    val me: Person = Person.Builder().setName("You").setKey("me").build()

    fun person(name: String, color: Int): Person =
        Person.Builder().setName(name).setKey(name).setIcon(avatar(name, color)).build()
}
