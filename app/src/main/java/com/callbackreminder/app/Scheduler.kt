package com.callbackreminder.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Sets and cancels the phone alarms behind each reminder.
 * We use "alarm clock" style alarms because Android treats them as the most reliable:
 * they fire on time even when the phone is idle or in battery-saving mode.
 */
object Scheduler {
    const val ACTION_REMIND = "com.callbackreminder.app.REMIND"
    const val EXTRA_ID = "reminder_id"

    private fun pending(ctx: Context, id: Long): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .setData(Uri.parse("callback://reminder/$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx,
            id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * When the alarm should go off. A one-off rings at its due time. A repeating reminder rings at
     * its due time, or at its next repeat if that comes first or the due time has already passed.
     */
    private fun alarmAt(r: Reminder, now: Long): Long? {
        if (r.repeat == null) return r.dueAt
        val next = r.nextOccurrence()
        val due = r.dueAt.takeIf { it > now }
        return listOfNotNull(due, next).minOrNull()
    }

    fun schedule(ctx: Context, r: Reminder) {
        val at = alarmAt(r, System.currentTimeMillis())
        if (at == null) {
            cancel(ctx, r.id)
            return
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setClockSafely(AlarmManager.AlarmClockInfo(at, open), pending(ctx, r.id))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, id))
    }

    /**
     * After a restart or an app update: set every alarm that is still ahead. Overdue ones already rang and wait in
     * Review, so they stay quiet. Only with [missedSince] (a restart) the ones that came due while the phone was off
     * and never rang are returned, for [PhoneOff.ringMissed].
     */
    fun restoreAll(ctx: Context, missedSince: Long?): List<Reminder> {
        val now = System.currentTimeMillis()
        val missed = ArrayList<Reminder>()
        ReminderStore.items.value.filter { !it.done }.forEach { r ->
            val at = alarmAt(r, now)
            when {
                at == null -> cancel(ctx, r.id)
                at > now -> schedule(ctx, r)
                missedSince != null && at > missedSince &&
                    r.events.none { e -> (e.what == "Rang" || e.what == PhoneOff.MISSED_EVENT) && e.at >= at } -> missed += r
            }
        }
        return missed
    }

    /** Rings one reminder in a few seconds (a single one missed while the phone was off). */
    fun ringSoon(ctx: Context, r: Reminder) =
        schedule(ctx, r.copy(dueAt = System.currentTimeMillis() + 5_000L, repeat = null))
}
