package com.callbackreminder.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    /** The old fixed banner channel; replaced by [bannerChannel], which carries the chosen sound. */
    private const val CH_REMINDER_OLD = "reminders"
    private const val BANNER_PREFIX = "banner_"
    const val CH_POSTCALL = "postcall"
    const val CH_RINGING = "ringing"
    const val CH_RECORDING = "recording"
    const val CH_MISSED = "missed_reminders"
    const val CH_REC_OFF = "recording_off"
    const val NOTIF_RING = 42
    private const val NOTIF_POSTCALL = 1

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        bannerChannel(ctx)
        Notify.createChannel(ctx)
        nm.createNotificationChannel(
            NotificationChannel(CH_MISSED, "Unanswered reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A quiet notification left behind when a full-screen reminder times out"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_POSTCALL, "After-call prompt", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Backup prompt if the popup after a call cannot open by itself"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Call recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a call is being recorded"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REC_OFF, "Recording switched off", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminds you to switch call recording back on after using a bank app"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RINGING, "Ringing reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while a call-back reminder is ringing. The sound itself comes from the app."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /**
     * The channel for banners with sound (call-back banners and text-message alerts). Android fixes a
     * channel's sound once it is made, so each sound choice gets its own channel and the old one goes.
     */
    fun bannerChannel(ctx: Context): String {
        val saved = ReminderSound.BANNER.saved(ctx)
        val id = BANNER_PREFIX + Integer.toHexString(saved.hashCode())
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) != null) return id

        nm.createNotificationChannel(
            NotificationChannel(id, "Call-back reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Banner reminders and text-message alerts, with the notification sound you chose"
                enableVibration(true)
                setShowBadge(true)
                if (!ReminderSound.BANNER.isPhoneDefault(ctx)) {
                    setSound(
                        ReminderSound.BANNER.uri(ctx),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            }
        )
        nm.notificationChannels
            .filter { it.id == CH_REMINDER_OLD || (it.id.startsWith(BANNER_PREFIX) && it.id != id) }
            .forEach { nm.deleteNotificationChannel(it.id) }
        return id
    }

    fun canNotify(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }

    fun actionIntent(ctx: Context, id: Long, action: String, minutes: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("callback://action/$action/$id"))
            .putExtra(ActionReceiver.EXTRA_ID, id)
            .putExtra(ActionReceiver.EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(ctx, id.toInt(), i, FLAGS)
    }

    /**
     * Plain banner with Call, Snooze and Done. Used for the "Banner" reminder style, as the
     * fallback when the ringing reminder cannot start, and ([quiet], no sound) when a timed
     * full-screen reminder moves to the notifications.
     */
    @SuppressLint("MissingPermission")
    fun showReminder(ctx: Context, r: Reminder, quiet: Boolean = false) {
        if (!canNotify(ctx)) return

        val open = PendingIntent.getActivity(
            ctx,
            r.id.toInt(),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            FLAGS
        )

        // Task first: the task is the title, the person and number the line under it.
        val text = listOfNotNull(r.title, r.personDetail()).joinToString(" · ")

        val b = NotificationCompat.Builder(ctx, if (quiet) CH_MISSED else bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(r.headline())
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setDefaults(if (quiet) 0 else NotificationCompat.DEFAULT_ALL)
            .setSilent(quiet)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(r.dueAt)
            .setShowWhen(true)

        if (r.number.isNotBlank()) {
            // Goes through the reminder screen, which places the call and remembers which task it
            // is for, so the after-call popup can ask how the task went.
            val dial = PendingIntent.getActivity(
                ctx,
                r.id.toInt() + 500_000,
                Intent(ctx, ReminderAlertActivity::class.java)
                    .putExtra(ReminderAlertActivity.EXTRA_CALLBACK_ID, r.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                FLAGS
            )
            b.addAction(0, "Call", dial)
        }
        b.addAction(0, "Snooze 1h", actionIntent(ctx, r.id, ActionReceiver.ACTION_SNOOZE, 60))
        b.addAction(0, "Done", actionIntent(ctx, r.id, ActionReceiver.ACTION_DONE, 0))

        Notify.post(ctx, if (quiet) NotifType.MISSED else NotifType.CALLBACK, NotifIds.call(r.id), b)
    }

    /** The notification that stays up while reminders are ringing (also opens the ringing screen). */
    fun buildRingNotification(ctx: Context, list: List<Reminder>): Notification {
        val alert = PendingIntent.getActivity(
            ctx,
            9001,
            Intent(ctx, ReminderAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            FLAGS
        )
        val first = list.firstOrNull()
        val title = when {
            first == null -> "Call-back reminder"
            list.size == 1 -> first.headline()
            else -> "${list.size} call-backs due"
        }
        val text = when {
            first == null -> "Time for your follow-up call"
            list.size == 1 -> listOfNotNull(first.title, first.personDetail()).joinToString(" · ")
            else -> list.joinToString(", ") { it.headline() }
        }

        val b = NotificationCompat.Builder(ctx, CH_RINGING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(alert)
            .setFullScreenIntent(alert, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (list.size == 1 && first != null) {
            if (first.number.isNotBlank()) {
                val callBack = PendingIntent.getActivity(
                    ctx,
                    first.id.toInt() + 700_000,
                    Intent(ctx, ReminderAlertActivity::class.java)
                        .putExtra(ReminderAlertActivity.EXTRA_CALLBACK_ID, first.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    FLAGS
                )
                b.addAction(0, "Call back", callBack)
            }
            b.addAction(0, "Snooze 10m", actionIntent(ctx, first.id, ActionReceiver.ACTION_SNOOZE, 10))
            b.addAction(0, "Done", actionIntent(ctx, first.id, ActionReceiver.ACTION_DONE, 0))
        }
        return b.build()
    }

    fun cancel(ctx: Context, id: Long) {
        NotificationManagerCompat.from(ctx).cancel(NotifIds.call(id))
    }

    /** Backup path: a tap-to-open prompt in case the popup is blocked by the phone. */
    @SuppressLint("MissingPermission")
    fun showPostCallPrompt(ctx: Context, popup: Intent) {
        if (!canNotify(ctx)) return
        val pi = PendingIntent.getActivity(ctx, 7, Intent(popup), FLAGS)
        val n = NotificationCompat.Builder(ctx, CH_POSTCALL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Set a follow-up call?")
            .setContentText("Tap to choose when to call back")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_POSTCALL, n)
    }

    fun cancelPostCallPrompt(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_POSTCALL)
    }
}
