package app.chronotif.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Posts made-up notifications on command:
 *   am broadcast -f 32 -n <pkg>/app.chronotif.demo.Post --es do msg --es conv maya --es from "Maya Chen" --es text "Hi"
 * Commands: msg (adds a message to a conversation and reposts it), note, cancel, reset.
 */
class Post : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        channels(nm)
        when (intent.getStringExtra("do")) {
            "msg" -> message(context, nm, intent)
            "note" -> note(context, nm, intent)
            "cancel" -> nm.cancel(intent.getIntExtra("id", 0))
            "reset" -> {
                nm.cancelAll()
                state(context).edit().clear().commit()
            }
        }
    }

    private fun channels(nm: NotificationManager) {
        nm.createNotificationChannels(listOf(
            NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("activity", "Activity", NotificationManager.IMPORTANCE_LOW),
        ))
    }

    private fun state(context: Context) = context.getSharedPreferences("convs", Context.MODE_PRIVATE)

    private fun message(context: Context, nm: NotificationManager, i: Intent) {
        val conv = i.getStringExtra("conv") ?: "chat"
        val prefs = state(context)
        val stored = JSONObject(prefs.getString(conv, null) ?: "{\"messages\":[]}")
        i.getStringExtra("title")?.let { stored.put("title", it) }
        if (i.hasExtra("group")) stored.put("group", i.getBooleanExtra("group", false))
        val messages = stored.getJSONArray("messages")
        messages.put(JSONObject()
            .put("from", i.getStringExtra("from") ?: "me")
            .put("text", i.getStringExtra("text") ?: "")
            .put("time", System.currentTimeMillis() - i.getIntExtra("ago", 0) * 60_000L)
            .put("photo", i.getStringExtra("photo") ?: ""))
        prefs.edit().putString(conv, stored.toString()).commit()
        nm.notify(conv.hashCode(), build(context, conv, stored, messages))
    }

    private fun build(context: Context, conv: String, stored: JSONObject, messages: JSONArray): Notification {
        val me = Person.Builder().setName("You").setKey("me").build()
        val group = stored.optBoolean("group", false)
        val title = stored.optString("title", "")
        val style = Notification.MessagingStyle(me).setGroupConversation(group)
        if (group) style.conversationTitle = title
        var lastSender: Person? = null
        for (k in 0 until messages.length()) {
            val m = messages.getJSONObject(k)
            val from = m.getString("from")
            val person = if (from == "me") null else Person.Builder()
                .setName(from).setKey(from)
                .setIcon(Icon.createWithBitmap(Art.avatar(from))).build()
            if (person != null) lastSender = person
            val msg = Notification.MessagingStyle.Message(m.getString("text"), m.getLong("time"), person)
            m.optString("photo").takeIf { it.isNotEmpty() }?.let {
                msg.setData("image/png", Uri.parse("content://${context.packageName}.photos/$it.png"))
            }
            style.addMessage(msg)
        }
        val b = builder(context, "messages")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(style)
            .setShowWhen(true)
            .setWhen(messages.getJSONObject(messages.length() - 1).getLong("time"))
        val large = if (group) Art.avatar(title, group = true) else lastSender?.let { Art.avatar(it.name.toString()) }
        large?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun note(context: Context, nm: NotificationManager, i: Intent) {
        val ongoing = i.getBooleanExtra("ongoing", false)
        val b = builder(context, i.getStringExtra("channel") ?: if (ongoing) "activity" else "updates")
            .setContentTitle(i.getStringExtra("title"))
            .setContentText(i.getStringExtra("text"))
            .setOngoing(ongoing)
            .setShowWhen(!ongoing)
        i.getStringExtra("sub")?.let { b.setSubText(it) }
        i.getStringExtra("big")?.let { b.setStyle(Notification.BigTextStyle().bigText(it)) }
        if (i.hasExtra("progress")) b.setProgress(100, i.getIntExtra("progress", 0), false)
        i.getStringExtra("category")?.let { b.setCategory(it) }
        nm.notify(i.getIntExtra("id", 1), b.build())
    }

    companion object {
        fun builder(context: Context, channel: String): Notification.Builder =
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.glyph)
                .setColor(context.getColor(R.color.brand))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                    Intent(context, Open::class.java), PendingIntent.FLAG_IMMUTABLE))
    }
}
