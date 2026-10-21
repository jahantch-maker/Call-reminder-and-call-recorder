package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * A reply that has an open task number in it but more than a "done" word, like "26102003 material nahi aya".
 * It is never taken as Done: it waits in Needs you › Replied, check it with the text shown, and the call still
 * rings. Done or New time on that task clears it. Only these texts are kept, nothing else that comes in.
 */
object ReplyNotes {
    data class Note(val code: String, val from: String, val text: String, val at: Long)

    private fun p(c: Context) = c.getSharedPreferences("reply_notes", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Note> = p(ctx).all.mapNotNull { (code, v) ->
        val o = try { JSONObject(v as? String ?: return@mapNotNull null) } catch (e: Exception) { return@mapNotNull null }
        Note(code, o.optString("from"), o.optString("text"), o.optLong("at"))
    }

    fun get(ctx: Context, code: String): Note? = all(ctx).firstOrNull { it.code == code }

    fun add(ctx: Context, from: String, code: String, text: String) {
        val now = System.currentTimeMillis()
        p(ctx).edit().putString(code, JSONObject().put("from", from).put("text", text).put("at", now).toString()).apply()
        logOnTask(ctx, from, code, text, now)
        notify(ctx, from, code, text)
    }

    fun clear(ctx: Context, code: String) {
        if (code.isNotEmpty() && p(ctx).contains(code)) p(ctx).edit().remove(code).apply()
    }

    /** The open follow-up a note belongs to, as a review item: its call when it has one, else its text. */
    fun items(ctx: Context): List<ReviewItem> {
        val calls = ReminderStore.items.value
        val texts = SmsStore.items.value
        val out = mutableListOf<ReviewItem>()
        all(ctx).forEach { n ->
            val chips = listOf("💬 “${n.text}”")
            val call = calls.firstOrNull { !it.done && it.code == n.code && SmsStore.sameNumber(it.number, n.from) }
            val text = texts.firstOrNull { !it.done && it.code == n.code && SmsStore.sameNumber(it.number, n.from) }
            when {
                call != null -> out += ReviewItem("c:${call.id}", ReviewSection.REPLIED, ReviewItem.Kind.CALL, call.id,
                    call.note.ifBlank { "Call back" }, call.title, call.number, n.at, chips, code = call.code)
                text != null -> out += ReviewItem("t:${text.id}", ReviewSection.REPLIED, ReviewItem.Kind.TEXT, text.id,
                    text.message, text.title, text.number, n.at, chips, code = text.code)
                // Its task was finished or deleted elsewhere: the note has nothing left to say.
                else -> clear(ctx, n.code)
            }
        }
        return out
    }

    private fun logOnTask(ctx: Context, from: String, code: String, text: String, now: Long) {
        ReminderStore.items.value.firstOrNull { !it.done && it.code == code && SmsStore.sameNumber(it.number, from) }?.let {
            ReminderStore.logEvent(it.id, "Replied: “$text”")
        }
        SmsStore.items.value.firstOrNull { !it.done && it.code == code && SmsStore.sameNumber(it.number, from) }?.let {
            SmsStore.addLog(it.id, SmsLogEntry(now, SmsLogEntry.Status.CALL_EVENT, "Replied: “$text”"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(ctx: Context, from: String, code: String, text: String) {
        if (!Notifier.canNotify(ctx)) return
        val call = ReminderStore.items.value.firstOrNull { !it.done && it.code == code && SmsStore.sameNumber(it.number, from) }
        val sms = SmsStore.items.value.firstOrNull { !it.done && it.code == code && SmsStore.sameNumber(it.number, from) }
        val who = call?.title ?: sms?.title
        val task = call?.note?.ifBlank { null } ?: sms?.message ?: ""
        val open = PendingIntent.getActivity(
            ctx,
            NotifIds.replyNote(code),
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_REVIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = "“$text”" + if (task.isNotBlank()) "\n$task" else ""
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Read ${Names.possessive(who, "the")} reply")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
        Notify.post(ctx, NotifType.TEXT_REPLIED, NotifIds.replyNote(code), b)
    }
}
