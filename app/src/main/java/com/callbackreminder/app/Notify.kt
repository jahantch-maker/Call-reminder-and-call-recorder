package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** How one kind of notification shows: not at all, quietly, or with your Notification ringtone. */
enum class NotifLevel(val label: String) { OFF("Off"), SILENT("Silent"), SOUND("Sound") }

/**
 * Every kind of notification the app posts, grouped as on Settings › Notifications. [locked] ones can't
 * be changed (the reason is shown instead of the switch).
 */
enum class NotifType(
    val group: String,
    val title: String,
    val description: String,
    val default: NotifLevel,
    val locked: String? = null,
) {
    CALLBACK(
        "Calls", "Call-back reminder", "The reminder itself, in your Reminder style", NotifLevel.SOUND,
        locked = "Always on: without it the app can't remind you."
    ),
    MISSED("Calls", "Unanswered reminder", "Left behind when a full-screen reminder times out", NotifLevel.SILENT),
    TEXT_REPLIED("Calls", "Reply to a moved text", "Someone texted back the number of a text you turned into a call", NotifLevel.SOUND),

    TEXT_SENT("Texts", "Each text sent", "\"Sent to Ali ✓\", also every text of a burst", NotifLevel.SILENT),
    TEXT_REVIEW("Texts", "Text to review", "A Done reply came in, or every text went out with no reply", NotifLevel.SOUND),
    TEXT_FAILED(
        "Texts", "Text failed or not sent", "After every retry failed", NotifLevel.SOUND,
        locked = "Always on: a text that fails quietly is the worst surprise."
    ),
    TAP_TO_SEND(
        "Texts", "Tap to send", "SMS permission is off, so you send it yourself", NotifLevel.SOUND,
        locked = "Always on: otherwise the text never goes out."
    ),
    DAILY_LIST("Texts", "Daily list sent", "\"Sent the list to Ali\"", NotifLevel.SILENT),
    DAILY_LIST_WA("Texts", "Daily lists for WhatsApp", "\"Daily lists ready\": you press Send for each one", NotifLevel.SOUND),

    MY_TASK("My tasks", "One-time task due", "With a Done button", NotifLevel.SOUND),
    DOSSIER("My tasks", "Dossier task due", "With a Done button", NotifLevel.SOUND),
    DOSSIER_BEFORE("My tasks", "Dossier early reminder", "The \"remind me before\" one", NotifLevel.SOUND),
    DOSSIER_NUDGE("My tasks", "Dossier reminder", "Your own dossier reminder times", NotifLevel.SOUND),

    REVIEW("Review", "Review reminder", "1 PM and 6 PM: what is waiting for you", NotifLevel.SOUND),
    MORNING("Review", "Morning summary", "Today's plan at 8 AM", NotifLevel.SOUND),

    RECORDING(
        "Recording and backup", "Recording in progress", "Shown while a call is recorded", NotifLevel.SILENT,
        locked = "Android needs it while the microphone records. It is already silent."
    ),
    BANKING("Recording and backup", "Recording is off", "Every 10 min after \"Switch off for banking\"", NotifLevel.SOUND),
    BACKUP_FAILED(
        "Recording and backup", "Backup failed", "The nightly backup couldn't be saved", NotifLevel.SOUND,
        locked = "Always on: you need to know when your data isn't safe."
    ),
}

object Notify {
    /** For notifications set to Silent: shows in the shade, no sound or vibration. */
    const val CH_QUIET = "quiet_alerts"

    private fun p(c: Context) = c.getSharedPreferences("notif_levels", Context.MODE_PRIVATE)

    fun level(ctx: Context, t: NotifType): NotifLevel {
        if (t.locked != null) return t.default
        return p(ctx).getString(t.name, null)?.let { s -> NotifLevel.values().firstOrNull { it.name == s } } ?: t.default
    }

    fun setLevel(ctx: Context, t: NotifType, l: NotifLevel) {
        if (t.locked != null) return
        p(ctx).edit().putString(t.name, l.name).apply()
    }

    fun on(ctx: Context, t: NotifType) = level(ctx, t) != NotifLevel.OFF
    fun loud(ctx: Context, t: NotifType) = level(ctx, t) == NotifLevel.SOUND

    /** Like "12 with sound · 3 silent · 1 off". */
    fun summary(ctx: Context): String {
        val all = NotifType.values().map { level(ctx, it) }
        return listOfNotNull(
            all.count { it == NotifLevel.SOUND }.let { "$it with sound" },
            all.count { it == NotifLevel.SILENT }.takeIf { it > 0 }?.let { "$it silent" },
            all.count { it == NotifLevel.OFF }.takeIf { it > 0 }?.let { "$it off" },
        ).joinToString(" · ")
    }

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_QUIET, "Silent notifications", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifications you set to Silent in Settings › Notifications"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * Posts [b] as [id] the way Settings › Notifications says: skipped when Off, moved to the quiet
     * channel when Silent, and given the sound channel when Sound (for kinds that are quiet by default).
     */
    @SuppressLint("MissingPermission")
    fun post(ctx: Context, t: NotifType, id: Int, b: NotificationCompat.Builder) {
        if (!Notifier.canNotify(ctx)) return
        when (level(ctx, t)) {
            NotifLevel.OFF -> return
            NotifLevel.SILENT -> {
                createChannel(ctx)
                b.setChannelId(CH_QUIET).setSilent(true).setDefaults(0).setPriority(NotificationCompat.PRIORITY_LOW)
            }
            NotifLevel.SOUND -> if (t.default == NotifLevel.SILENT) {
                b.setChannelId(Notifier.bannerChannel(ctx)).setSilent(false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL)
            }
        }
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }
}
