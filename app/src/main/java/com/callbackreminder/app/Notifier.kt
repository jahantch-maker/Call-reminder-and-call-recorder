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
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_REMINDER = "reminders"
    const val CH_POSTCALL = "postcall"
    const val CH_RINGING = "ringing"
    const val NOTIF_RING = 42
    private const val NOTIF_POSTCALL = 1

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "Call-back reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Banner used only if the ringing reminder cannot start"
                enableVibration(true)
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
            NotificationChannel(CH_RINGING, "Ringing reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while a call-back reminder is ringing. The sound itself comes from the app."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
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

    /** Plain banner. Only used as a fallback when the ringing reminder cannot start. */
    @SuppressLint("MissingPermission")
    fun showReminder(ctx: Context, r: Reminder) {
        if (!canNotify(ctx)) return

        val open = PendingIntent.getActivity(
            ctx,
            r.id.toInt(),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            FLAGS
        )

        val text = listOfNotNull(
            r.note.takeIf { it.isNotBlank() },
            r.number.takeIf { it.isNotBlank() && r.name.isNotBlank() }
        ).joinToString(" · ").ifBlank { "Time for your follow-up call" }

        val b = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Call back ${r.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(r.dueAt)
            .setShowWhen(true)

        if (r.number.isNotBlank()) {
            // Straight to the call when the Call permission is given, otherwise the dialer.
            val callAction = if (hasCallPhonePermission(ctx)) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val dial = PendingIntent.getActivity(
                ctx,
                r.id.toInt() + 500_000,
                Intent(callAction, Uri.fromParts("tel", r.number.trim(), null)),
                FLAGS
            )
            b.addAction(0, "Call", dial)
        }
        b.addAction(0, "Snooze 1h", actionIntent(ctx, r.id, ActionReceiver.ACTION_SNOOZE, 60))
        b.addAction(0, "Done", actionIntent(ctx, r.id, ActionReceiver.ACTION_DONE, 0))

        NotificationManagerCompat.from(ctx).notify(r.id.toInt(), b.build())
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
            list.size == 1 -> "Call back ${first.title}"
            else -> "${list.size} call-backs due"
        }
        val text = when {
            first == null -> "Time for your follow-up call"
            list.size == 1 -> listOfNotNull(
                first.note.takeIf { it.isNotBlank() },
                first.number.takeIf { it.isNotBlank() && first.name.isNotBlank() }
            ).joinToString(" · ").ifBlank { "Time for your follow-up call" }
            else -> list.joinToString(", ") { it.title }
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
        NotificationManagerCompat.from(ctx).cancel(id.toInt())
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
