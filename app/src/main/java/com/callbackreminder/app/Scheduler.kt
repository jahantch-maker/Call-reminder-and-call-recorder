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

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(r.dueAt, open), pending(ctx, r.id))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, id))
    }

    /** After a restart or an app update all alarms are gone, so set them again. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        ReminderStore.items.value
            .filter { !it.done }
            .forEach { r -> schedule(ctx, r.copy(dueAt = maxOf(r.dueAt, now + 5_000L))) }
    }
}
