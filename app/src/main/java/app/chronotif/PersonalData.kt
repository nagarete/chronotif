// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.LruCache
import app.chronotif.core.PersonalMatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Contact(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val photo: Uri?,
    val numbers: List<String>,
    /** What the call and message buttons use; null when the contact has no number. */
    val callNumber: String?,
    /** The contact's WhatsApp chat and voice-call entries, when WhatsApp has added them to Contacts. */
    val whatsapp: WhatsApp? = null,
)

class WhatsApp(val chat: Long?, val call: Long?)

class CalendarEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean)

/**
 * Search's contacts and calendar events (ADR 0008), read on the phone and never stored. Each is
 * read only while its switch in Settings → Search is on and its permission granted; otherwise
 * Search gets an empty list.
 */
object PersonalData {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val photos = LruCache<Uri, Drawable>(32)

    fun contactsOn(context: Context) = Prefs.searchContacts && granted(context, Manifest.permission.READ_CONTACTS)
    fun calendarOn(context: Context) = Prefs.searchCalendar && granted(context, Manifest.permission.READ_CALENDAR)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Every contact with a name, delivered on the main thread. */
    fun contacts(context: Context, done: (List<Contact>) -> Unit) {
        if (!contactsOn(context)) return done(emptyList())
        val app = context.applicationContext
        io.execute {
            val list = runCatching { readContacts(app) }.getOrDefault(emptyList())
            main.post { done(list) }
        }
    }

    private fun readContacts(context: Context): List<Contact> {
        val cr = context.contentResolver
        val numbers = HashMap<Long, MutableList<PersonalMatch.Number>>()
        cr.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.TYPE, Phone.IS_SUPER_PRIMARY),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                numbers.getOrPut(c.getLong(0)) { ArrayList() } +=
                    PersonalMatch.Number(number, isDefault = c.getInt(3) != 0, isMobile = c.getInt(2) == Phone.TYPE_MOBILE)
            }
        }
        val whatsapp = readWhatsApp(context)
        val out = ArrayList<Contact>()
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val id = c.getLong(0)
                val own = numbers[id].orEmpty()
                out += Contact(
                    id = id,
                    lookupKey = c.getString(1).orEmpty(),
                    name = name,
                    photo = c.getString(3)?.let(Uri::parse),
                    numbers = own.map { it.number }.distinct(),
                    callNumber = PersonalMatch.callNumber(own),
                    whatsapp = whatsapp[id],
                )
            }
        }
        return out
    }

    /**
     * WhatsApp adds a chat and a voice-call entry to each contact who uses it. Opening one of those
     * entries is how the Contacts app starts a WhatsApp chat or call, and Search does the same.
     */
    private fun readWhatsApp(context: Context): Map<Long, WhatsApp> {
        val chats = HashMap<Long, Long>()
        val calls = HashMap<Long, Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(WHATSAPP_CHAT, WHATSAPP_CALL), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val target = if (c.getString(2) == WHATSAPP_CHAT) chats else calls
                target.putIfAbsent(c.getLong(1), c.getLong(0))
            }
        }
        return (chats.keys + calls.keys).associateWith { WhatsApp(chats[it], calls[it]) }
    }

    private const val WHATSAPP_CHAT = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    private const val WHATSAPP_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"

    /** The contact's photo, delivered on the main thread; nothing when it has none. */
    fun photo(context: Context, contact: Contact, done: (Drawable) -> Unit) {
        val uri = contact.photo ?: return
        photos.get(uri)?.let { return done(it) }
        val app = context.applicationContext
        io.execute {
            val bitmap = runCatching { app.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
                ?: return@execute
            val d = BitmapDrawable(app.resources, bitmap)
            main.post {
                photos.put(uri, d)
                done(d)
            }
        }
    }

    /** Events from [PersonalMatch.EVENTS_PAST_DAYS] ago to [PersonalMatch.EVENTS_AHEAD_DAYS] ahead, on the main thread. */
    fun events(context: Context, done: (List<CalendarEvent>) -> Unit) {
        if (!calendarOn(context)) return done(emptyList())
        val app = context.applicationContext
        io.execute {
            val list = runCatching { readEvents(app) }.getOrDefault(emptyList())
            main.post { done(list) }
        }
    }

    private fun readEvents(context: Context): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - TimeUnit.DAYS.toMillis(PersonalMatch.EVENTS_PAST_DAYS))
            ContentUris.appendId(it, now + TimeUnit.DAYS.toMillis(PersonalMatch.EVENTS_AHEAD_DAYS))
        }.build()
        val out = ArrayList<CalendarEvent>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                out += CalendarEvent(c.getLong(0), title, c.getLong(2), c.getLong(3), c.getInt(4) != 0)
            }
        }
        return out
    }

    fun openContact(context: Context, contact: Contact) = start(context,
        Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey)))

    /**
     * A WhatsApp voice call when the contact is on WhatsApp. Otherwise the dialer opens with the
     * number ready, so Chronotif needs no permission to place calls.
     */
    fun call(context: Context, contact: Contact) {
        if (openWhatsApp(context, contact.whatsapp?.call, WHATSAPP_CALL)) return
        val number = contact.callNumber ?: return
        start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }

    /** A WhatsApp chat when the contact is on WhatsApp, otherwise the SMS app. */
    fun message(context: Context, contact: Contact) {
        if (openWhatsApp(context, contact.whatsapp?.chat, WHATSAPP_CHAT)) return
        val number = contact.callNumber ?: return
        start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
    }

    private fun openWhatsApp(context: Context, dataId: Long?, type: String): Boolean {
        if (dataId == null) return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId), type)
            .setPackage("com.whatsapp")
        return start(context, intent)
    }

    fun openEvent(context: Context, event: CalendarEvent) = start(context,
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end))

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
