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
    /** The contact's WhatsApp chat and voice-call entries, when WhatsApp or WhatsApp Business added them to Contacts. */
    val whatsapp: WhatsApp? = null,
)

class WhatsApp(val chat: WhatsApp.Entry?, val call: WhatsApp.Entry?, val video: WhatsApp.Entry?) {
    /**
     * A row WhatsApp added to the contact: opening it starts the chat or call in [app]. [phone] is
     * the WhatsApp number in international form, digits only, read from the row's account id.
     */
    class Entry(val dataId: Long, val mimeType: String, val app: String, val phone: String?)
}

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
    /** Call buttons place calls themselves; WhatsApp only starts a call for an app allowed to. */
    fun callsOn(context: Context) = Prefs.searchCalls && granted(context, Manifest.permission.CALL_PHONE)

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
     * WhatsApp adds a chat and a voice-call entry to each contact who uses it, and WhatsApp
     * Business adds its own. Opening one of those entries is how the Contacts app starts a chat or
     * call, and Search does the same, preferring WhatsApp when a contact has both.
     */
    private fun readWhatsApp(context: Context): Map<Long, WhatsApp> {
        val chats = HashMap<Long, WhatsApp.Entry>()
        val calls = HashMap<Long, WhatsApp.Entry>()
        val videos = HashMap<Long, WhatsApp.Entry>()
        val types = WHATSAPP_APPS.flatMap { listOf(chatType(it), callType(it), videoType(it)) }
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} IN (${types.joinToString { "?" }})",
            types.toTypedArray(), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(2)
                val app = WHATSAPP_APPS.first { type == chatType(it) || type == callType(it) || type == videoType(it) }
                // DATA1 is the WhatsApp account, "6281234567890@s.whatsapp.net".
                val phone = c.getString(3)?.substringBefore('@')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                val entry = WhatsApp.Entry(c.getLong(0), type, app, phone)
                val target = when (type) {
                    chatType(app) -> chats
                    callType(app) -> calls
                    else -> videos
                }
                val held = target[c.getLong(1)]
                if (held == null || WHATSAPP_APPS.indexOf(app) < WHATSAPP_APPS.indexOf(held.app)) target[c.getLong(1)] = entry
            }
        }
        return (chats.keys + calls.keys + videos.keys).associateWith { WhatsApp(chats[it], calls[it], videos[it]) }
    }

    /** WhatsApp first, then WhatsApp Business. */
    private val WHATSAPP_APPS = listOf("com.whatsapp", "com.whatsapp.w4b")
    private fun chatType(app: String) = "vnd.android.cursor.item/vnd.$app.profile"
    private fun callType(app: String) = "vnd.android.cursor.item/vnd.$app.voip.call"
    private fun videoType(app: String) = "vnd.android.cursor.item/vnd.$app.video.call"

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
     * With *Call from Search* on: a WhatsApp voice call when the contact is on WhatsApp, otherwise
     * a phone call. With it off, the dialer opens with the number ready.
     */
    fun call(context: Context, contact: Contact) {
        val calls = callsOn(context)
        if (calls && openWhatsApp(context, contact.whatsapp?.call)) return
        val number = contact.callNumber ?: return
        start(context, Intent(if (calls) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }

    /** True when the video button shows: the contact is on WhatsApp and calls are allowed. */
    fun canVideoCall(context: Context, contact: Contact) = contact.whatsapp?.video != null && callsOn(context)

    /** A WhatsApp video call; only offered when [canVideoCall]. */
    fun videoCall(context: Context, contact: Contact) {
        openWhatsApp(context, contact.whatsapp?.video)
    }

    /** A WhatsApp chat when the contact is on WhatsApp, otherwise the SMS app. */
    fun message(context: Context, contact: Contact) {
        if (openWhatsApp(context, contact.whatsapp?.chat)) return
        val number = contact.callNumber ?: return
        start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
    }

    /**
     * Opening WhatsApp's own Contacts entry only works while WhatsApp has a task open: from cold it
     * closes again without showing anything. So a chat goes through WhatsApp's documented chat
     * link, and a call opens WhatsApp first with the call entry on top of it.
     */
    private fun openWhatsApp(context: Context, entry: WhatsApp.Entry?): Boolean {
        if (entry == null) return false
        if (entry.mimeType == chatType(entry.app) && entry.phone != null) {
            val chat = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${entry.phone}")).setPackage(entry.app)
            if (start(context, chat)) return true
        }
        val open = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, entry.dataId), entry.mimeType)
            .setPackage(entry.app)
        val home = context.packageManager.getLaunchIntentForPackage(entry.app)
            ?: return start(context, open)
        return runCatching {
            context.startActivities(arrayOf(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), open))
        }.isSuccess || start(context, open)
    }

    fun openEvent(context: Context, event: CalendarEvent) = start(context,
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end))

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
