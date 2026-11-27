package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Twice a day (times and days set in Settings › Review) it pops up what's waiting in each Review page
 * section that is switched on, with Start review and Open Review page. Nothing waiting: no popup.
 */
object ReviewReminder {
    const val ACTION_FIRE = "com.callbackreminder.app.REVIEW"
    const val ACTION_SNOOZE = "com.callbackreminder.app.REVIEW_SNOOZE"
    const val EXTRA_MINUTES = "minutes"
    private const val NOTIF_ID = 77_001
    private const val REQ_DAILY = 77_002
    private const val REQ_SNOOZE = 77_003

    val SNOOZE_MINUTES = listOf(15, 30, 60, 120)
    private const val ALL_DAYS = 0b1111111

    /** What's waiting in each section shown on the Review page (Settings › Review). */
    data class Counts(val sections: List<Pair<ReviewSection, Int>>) {
        val total: Int get() = sections.sumOf { it.second }

        /** Like "6 overdue · 2 to schedule · follow-ups". */
        val summary: String
            get() = sections.filter { it.second > 0 }.joinToString(" · ") { (s, n) -> "$n ${s.title.lowercase()}" }
                .ifBlank { "Nothing waiting" }
    }

    // ---- Settings ----

    private fun p(c: Context) = c.getSharedPreferences("review_reminder", Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean("on", on).apply()
        schedule(c)
    }

    /** The two review times, as minutes after midnight (1:00 PM and 6:00 PM to start with). */
    fun times(c: Context): List<Int> = listOf(p(c).getInt("t1", 13 * 60), p(c).getInt("t2", 18 * 60))
    fun setTime(c: Context, index: Int, minutes: Int) {
        p(c).edit().putInt(if (index == 0) "t1" else "t2", minutes).apply()
        schedule(c)
    }

    /** Which days it runs: bit 0 = Monday … bit 6 = Sunday. Every day to start with. */
    fun days(c: Context): Int = p(c).getInt("days", ALL_DAYS)
    fun setDays(c: Context, mask: Int) {
        p(c).edit().putInt("days", mask).apply()
        schedule(c)
    }

    fun isDayOn(mask: Int, day: DayOfWeek) = mask and (1 shl (day.value - 1)) != 0

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    fun timeLabel(minutes: Int): String = LocalTime.of(minutes / 60, minutes % 60).format(timeFmt)

    fun daysLabel(mask: Int): String = when (mask and ALL_DAYS) {
        ALL_DAYS -> "every day"
        0b0111111 -> "Monday to Saturday"
        0b0011111 -> "Monday to Friday"
        0 -> "no days"
        else -> DayOfWeek.values().filter { isDayOn(mask, it) }
            .joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    fun summary(c: Context): String =
        if (WebRules.managed(c)) webSummary(c)
        else if (!enabled(c)) "Off"
        else times(c).sorted().joinToString(" and ") { timeLabel(it) } + " · " + daysLabel(days(c))

    /** "1 hour before duty end and 3 hours before duty end · Set by office". */
    fun webSummary(c: Context, prefix: String = "rev"): String =
        (1..2).filter { WebRules.bool(c, "$prefix${it}On") }.joinToString(" and ") { WebRules.offsetLabel(WebRules.int(c, "$prefix${it}Min")) }
            .ifBlank { "Off" } + " · Set by office"

    // ---- What is waiting ----

    fun counts(ctx: Context): Counts = Counts(ReviewData.shownCounts(ctx))

    // ---- Alarms ----

    private fun pending(ctx: Context, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            req,
            Intent(ctx, ReviewReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(Uri.parse("callback://review/$req")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** The next review time after [now], or null when switched off or no day is chosen. */
    fun nextAt(ctx: Context, now: Long = System.currentTimeMillis()): Long? {
        // 27 Nov (S4.8) A team member's review times come from the office: up to 2 from his duty end, never on his
        // off day or a holiday (saved on the phone, so they work offline).
        if (WebRules.managed(ctx)) {
            val today = LocalDate.now(ZoneId.systemDefault())
            return (0..7).asSequence().flatMap { d -> WebRules.reminderTimes(ctx, "rev", today.plusDays(d.toLong())) }.firstOrNull { it > now }
        }
        if (!enabled(ctx)) return null
        val mask = days(ctx)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        for (d in 0..7) {
            val date = today.plusDays(d.toLong())
            if (!isDayOn(mask, date.dayOfWeek) || OffDays.isOff(date)) continue
            val at = times(ctx).sorted()
                .map { date.atTime(it / 60, it % 60).atZone(zone).toInstant().toEpochMilli() }
                .firstOrNull { it > now }
            if (at != null) return at
        }
        return null
    }

    /** Sets the alarm for the next review time (or clears it). Safe to call any time. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, REQ_DAILY)
        val at = nextAt(ctx)
        if (at == null) {
            am.cancel(pi)
            return
        }
        am.setClockSafely(AlarmManager.AlarmClockInfo(at, openApp(ctx)), pi)
    }

    /** A review session started: a snoozed review reminder still waiting would ask again for nothing. */
    fun cancelSnooze(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, REQ_SNOOZE))
        cancelNotification(ctx)
    }

    fun snooze(ctx: Context, minutes: Int) {
        cancelNotification(ctx)
        val at = System.currentTimeMillis() + minutes * 60_000L
        ctx.getSystemService(AlarmManager::class.java)
            .setClockSafely(AlarmManager.AlarmClockInfo(at, openApp(ctx)), pending(ctx, REQ_SNOOZE))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        REQ_DAILY,
        Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** A review time arrived: show the popup if anything is waiting, and set the next one. */
    fun fire(ctx: Context) {
        schedule(ctx)
        // Only a session you are in right now skips it (on screen, or a call from it in the last hour). A paused
        // one doesn't: "Review now" starts with its leftovers first.
        val s = ReviewSession.init(ctx)
        if (s.status == ReviewSession.Status.RUNNING) {
            val calling = s.awaiting.isNotEmpty() && System.currentTimeMillis() - s.awaitingAt < 60 * 60_000L
            if (MainActivity.visible || calling) return
            ReviewSession.pause(ctx)
        }
        val counts = counts(ctx)
        if (counts.total == 0 || !Notify.on(ctx, NotifType.REVIEW)) return
        show(ctx, counts)
    }

    /** Pops up the review reminder in the Reminder style chosen in Settings. */
    fun show(ctx: Context, counts: Counts) {
        // Settings › Notifications › Review reminder set to Silent: a quiet notification, no popup.
        if (Notify.level(ctx, NotifType.REVIEW) == NotifLevel.SILENT) {
            notify(ctx, counts, quiet = true, fullScreen = false)
            return
        }
        if (ReminderStyle.mode(ctx) == ReminderStyle.Mode.BANNER) {
            notify(ctx, counts, quiet = false, fullScreen = false)
            return
        }
        // The full-screen notification is the backup if the popup cannot open by itself.
        notify(ctx, counts, quiet = false, fullScreen = true)
        if (Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(
                    Intent(ctx, ReviewAlertActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (e: Exception) {
                // The notification is already showing.
            }
        }
    }

    // ---- Notification ----

    /** Opens the Review page; with [start], a review session of everything starts right away. */
    fun reviewIntent(ctx: Context, start: Boolean = false): Intent =
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_REVIEW)
            .putExtra(MainActivity.EXTRA_START, start)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    @SuppressLint("MissingPermission")
    fun notify(ctx: Context, counts: Counts, quiet: Boolean, fullScreen: Boolean) {
        if (!Notifier.canNotify(ctx)) return
        Notifier.createChannels(ctx)
        // Like the call-back reminders: a banner sounds with your Notification ringtone; the
        // full-screen popup plays your Popup ringtone itself, so its notification stays silent.
        val channel = when {
            fullScreen -> Notifier.CH_RINGING
            quiet -> Notifier.CH_MISSED
            else -> Notifier.bannerChannel(ctx)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val review = PendingIntent.getActivity(ctx, NOTIF_ID, reviewIntent(ctx), flags)
        val snooze = PendingIntent.getBroadcast(
            ctx,
            NOTIF_ID + 1,
            Intent(ctx, ReviewReceiver::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_MINUTES, 30),
            flags
        )
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle("Time to review")
            .setContentText(counts.summary)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(quiet || fullScreen)
            .setContentIntent(review)
            .setAutoCancel(true)
            .addAction(0, "Open Review", review)
            .addAction(0, "Snooze 30 min", snooze)
        if (fullScreen) {
            val popup = PendingIntent.getActivity(
                ctx,
                NOTIF_ID + 2,
                Intent(ctx, ReviewAlertActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            b.setFullScreenIntent(popup, true)
        }
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build())
    }

    fun cancelNotification(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }
}

/** The daily review alarm, a snoozed one, and Snooze on the notification. */
class ReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReviewReminder.ACTION_FIRE -> ReviewReminder.fire(context)
            ReviewReminder.ACTION_SNOOZE ->
                ReviewReminder.snooze(context, intent.getIntExtra(ReviewReminder.EXTRA_MINUTES, 30))
        }
    }
}
