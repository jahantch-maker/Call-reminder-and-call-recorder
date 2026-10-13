package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * "Call instead" on a text (and "Not done, call later" after calling about one): the text becomes a
 * call-back reminder that carries the text's History. Remaining texts are cancelled. If the person
 * later replies "Done <number>" to a text that already went out, the call task is still found.
 */
object TextToCall {
    /** This text's History as lines on the call reminder, oldest first. */
    fun historyFor(r: SmsReminder): List<CallEvent> {
        val lines = r.log.map { e ->
            when (e.status) {
                SmsLogEntry.Status.CALL_EVENT -> CallEvent(e.at, e.detail)
                SmsLogEntry.Status.FROM_CALL -> CallEvent(e.at, e.status.label)
                SmsLogEntry.Status.CALLED -> CallEvent(e.at, "Called · ${e.detail}")
                else -> CallEvent(e.at, "Text: ${e.status.label}", note = e.detail)
            }
        }
        val created = CallEvent(r.createdAt, "Text created" + if (r.code.isNotEmpty()) " · task ${r.code}" else "", note = r.message)
        return listOf(created) + lines
    }

    /**
     * Moves the text [r] to Calls, due at [dueAt]. [extra] lines (like the call just made, or why it is
     * not done) are added to its History first. Returns the new call reminder.
     */
    fun move(
        ctx: Context,
        r: SmsReminder,
        name: String,
        number: String,
        note: String,
        dueAt: Long,
        repeat: Repeat? = null,
        extra: List<CallEvent> = emptyList(),
    ): Reminder {
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        val live = SmsStore.get(r.id) ?: r
        val call = ReminderStore.add(
            name = name,
            number = number,
            note = note,
            dueAt = dueAt,
            repeat = repeat,
            createdAs = "Changed from a text to a call",
            earlier = historyFor(live) + extra,
        )
        if (live.tracked && live.code.isNotEmpty()) remember(ctx, live.code, live.number, call.id)
        SmsStore.delete(live.id)
        return call
    }

    // ---- Old task numbers that now belong to a call ----

    private fun p(c: Context) = c.getSharedPreferences("moved_codes", Context.MODE_PRIVATE)

    private fun remember(ctx: Context, code: String, number: String, reminderId: Long) {
        p(ctx).edit().putString(code, JSONObject().put("number", number).put("id", reminderId).toString()).apply()
    }

    /**
     * A "Done <code>" reply that matched no text: if that number moved to a call, the call task goes
     * to To review (like a replied text) and you get a notification. True when it was handled.
     */
    fun replied(ctx: Context, fromNumber: String, code: String): Boolean {
        val raw = p(ctx).getString(code, null) ?: return false
        val o = try { JSONObject(raw) } catch (e: Exception) { return false }
        if (!SmsStore.sameNumber(o.optString("number"), fromNumber)) return false
        ReminderStore.init(ctx)
        val r = ReminderStore.get(o.optLong("id", -1L))?.takeIf { !it.done } ?: return false
        p(ctx).edit().remove(code).apply()
        ReminderStore.logEvent(r.id, "Replied Done $code by text")
        ReminderStore.markDone(r.id, review = true)
        notifyReplied(ctx, r, code)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun notifyReplied(ctx: Context, r: Reminder, code: String) {
        if (!Notifier.canNotify(ctx)) return
        val open = PendingIntent.getActivity(
            ctx,
            1_400_000 + r.id.toInt(),
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_REVIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("${r.title} replied Done $code")
            .setContentText("The call task is waiting in To review")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
        NotificationManagerCompat.from(ctx).notify(1_400_000 + r.id.toInt(), b.build())
    }
}
