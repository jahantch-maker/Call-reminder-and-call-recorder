package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * "Switch off for banking". Banking apps refuse to open while an accessibility service like ours
 * is on, and Android does not let an app switch its own service off, so this opens the
 * Accessibility page for the user. Every 10 minutes after that, until the service is back on,
 * a notification reminds them, so calls do not go unrecorded.
 */
object BankingPause {
    private const val REMIND_MS = 10 * 60_000L
    private const val NOTIF_ID = 79
    private const val KEY_PAUSED = "bank_pause"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("callstate", Context.MODE_PRIVATE)

    fun isPaused(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PAUSED, false)

    fun accessibilityIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the Accessibility page and starts the reminders. */
    fun start(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_PAUSED, true).apply()
        schedule(ctx)
        ctx.startActivity(accessibilityIntent())
    }

    /** The service is back on: stop reminding. */
    fun end(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_PAUSED, false).apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx))
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }

    /** After a restart the alarm is gone; set it again if the pause is still on. */
    fun resume(ctx: Context) {
        if (isPaused(ctx)) check(ctx)
    }

    /** Runs when the 10 minutes are up. */
    fun check(ctx: Context) {
        if (RecorderAccessibilityService.isEnabled(ctx)) {
            end(ctx)
            return
        }
        remind(ctx)
        schedule(ctx)
    }

    private fun schedule(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + REMIND_MS,
            alarm(ctx)
        )
    }

    private fun alarm(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            NOTIF_ID,
            Intent(ctx, BankingReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @SuppressLint("MissingPermission")
    private fun remind(ctx: Context) {
        if (!Notifier.canNotify(ctx)) return
        val open = PendingIntent.getActivity(
            ctx,
            NOTIF_ID,
            accessibilityIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, Notifier.CH_REC_OFF)
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle("Call recording is off")
            .setContentText("Done with your bank app? Tap to switch recording back on.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Done with your bank app? Tap, then switch on RB360 call recording " +
                        "so your calls are recorded again."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
        Notify.post(ctx, NotifType.BANKING, NOTIF_ID, n)
    }
}

/** Fires 10 minutes after "Switch off for banking", and every 10 minutes until recording is back on. */
class BankingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BankingPause.check(context)
    }
}
