package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.ZoneId

/**
 * The 8 AM morning summary: one notification with today's calls, texts, My tasks and dossier, and
 * what is still overdue. Tapping it opens "Your day". Set in Settings › Morning summary.
 */
object MorningSummary {
    const val ACTION_FIRE = "com.callbackreminder.app.MORNING"
    const val ACTION_LATER = "com.callbackreminder.app.MORNING_LATER"
    private const val REQ_DAILY = 1_099_010
    private const val REQ_LATER = 1_099_011
    private const val NOTIF_ID = 1_099_012
    const val LATER_MINUTES = 30

    /** Today's plan. */
    data class Day(
        val calls: Int,
        val texts: Int,
        val myTasks: Int,
        val dossier: Int,
        val overdue: List<WorkItem>,
        /** Today's work in time order. */
        val today: List<WorkItem>,
    ) {
        val total: Int get() = calls + texts + myTasks + dossier
        val countsLine: String
            get() = "Today: $calls ${if (calls == 1) "call" else "calls"} · $texts ${if (texts == 1) "text" else "texts"} · " +
                "$myTasks my ${if (myTasks == 1) "task" else "tasks"} · $dossier dossier"
    }

    // ---- Settings ----

    private fun p(c: Context) = c.getSharedPreferences("morning_summary", Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean("on", on).apply()
        schedule(c)
    }

    /** Minutes after midnight; 8:00 AM to start with. */
    fun minute(c: Context) = p(c).getInt("minute", 8 * 60)
    fun setMinute(c: Context, m: Int) {
        p(c).edit().putInt("minute", m).apply()
        schedule(c)
    }

    /** Bit 0 = Monday … bit 6 = Sunday. Monday to Saturday to start with. */
    fun days(c: Context) = p(c).getInt("days", 0b0111111)
    fun setDays(c: Context, mask: Int) {
        p(c).edit().putInt("days", mask).apply()
        schedule(c)
    }

    fun fullScreen(c: Context) = p(c).getBoolean("full", false)
    fun setFullScreen(c: Context, on: Boolean) = p(c).edit().putBoolean("full", on).apply()

    fun skipEmpty(c: Context) = p(c).getBoolean("skip_empty", true)
    fun setSkipEmpty(c: Context, on: Boolean) = p(c).edit().putBoolean("skip_empty", on).apply()

    fun summary(c: Context): String =
        if (!enabled(c)) "Off" else Dossier.timeLabel(minute(c)) + " · " + ReviewReminder.daysLabel(days(c))

    // ---- What today holds ----

    fun compute(ctx: Context, now: Long = System.currentTimeMillis()): Day {
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        MyTaskStore.init(ctx)
        DossierStore.init(ctx)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val open = Work.open(
            now,
            ReminderStore.items.value,
            SmsStore.items.value,
            MyTaskStore.items.value,
            DossierStore.tasks.value,
            DossierStore.records.value,
        )
        val todays = open.filter { !it.inReview && it.at in start until end }.sortedBy { it.at }
        return Day(
            calls = todays.count { it.kind == WorkItem.Kind.CALL },
            texts = todays.count { it.kind == WorkItem.Kind.TEXT },
            myTasks = todays.count { it.kind == WorkItem.Kind.MY_TASK },
            dossier = todays.count { it.kind == WorkItem.Kind.DOSSIER },
            overdue = open.filter { it.overdue && it.at < start }.sortedBy { it.at },
            today = todays,
        )
    }

    // ---- Alarms ----

    private fun alarm(ctx: Context, req: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        req,
        Intent(ctx, TaskReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("callback://morning/$req")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openDay(ctx: Context, req: Int): PendingIntent = PendingIntent.getActivity(
        ctx,
        req,
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MY_DAY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context, now: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarm(ctx, REQ_DAILY)
        if (!enabled(ctx)) {
            am.cancel(pi)
            return
        }
        val zone = ZoneId.systemDefault()
        val m = minute(ctx)
        val mask = days(ctx)
        val at = (0..7).map { LocalDate.now(zone).plusDays(it.toLong()) }
            .filter { mask and Dossier.dayOfWeekBit(it.dayOfWeek) != 0 }
            .map { it.atTime(m / 60, m % 60).atZone(zone).toInstant().toEpochMilli() }
            .firstOrNull { it > now }
        if (at == null) {
            am.cancel(pi)
            return
        }
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openDay(ctx, REQ_DAILY)), pi)
    }

    /** Later on the notification: the same summary again in [LATER_MINUTES] minutes. */
    fun later(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        val at = System.currentTimeMillis() + LATER_MINUTES * 60_000L
        ctx.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(at, openDay(ctx, REQ_LATER)), alarm(ctx, REQ_LATER))
    }

    fun fire(ctx: Context) {
        schedule(ctx, System.currentTimeMillis() + 60_000L)
        val day = compute(ctx)
        if (day.total == 0 && day.overdue.isEmpty() && skipEmpty(ctx)) return
        show(ctx, day)
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, day: Day) {
        if (!Notifier.canNotify(ctx)) return
        val text = if (day.overdue.isEmpty()) "Nothing overdue · tap to plan your day"
        else "${day.overdue.size} overdue · tap to plan your day"
        val later = PendingIntent.getBroadcast(
            ctx,
            REQ_LATER + 10,
            Intent(ctx, TaskReceiver::class.java).setAction(ACTION_LATER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = openDay(ctx, NOTIF_ID)
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(day.countsLine)
            .setContentText(text)
            .setSubText("Morning summary")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Open", open)
            .addAction(0, "Later", later)
        if (fullScreen(ctx)) b.setFullScreenIntent(open, true)
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build())
    }

    fun cancelNotification(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
}
